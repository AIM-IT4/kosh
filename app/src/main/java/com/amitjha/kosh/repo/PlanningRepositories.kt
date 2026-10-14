package com.amitjha.kosh.repo

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.finance.BudgetProjection
import com.amitjha.kosh.finance.Budgets
import com.amitjha.kosh.finance.GoalPlan
import com.amitjha.kosh.finance.Goals
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** An unpaid (or paid) card bill with its card and days until due (negative when overdue). */
data class BillItem(val bill: CardBillEntity, val card: AccountEntity?, val daysLeft: Long?) {
    val dueDate: LocalDate? get() = bill.dueDate?.let(LocalDate::ofEpochDay)
    val isOverdue: Boolean get() = !bill.paid && (daysLeft ?: 0) < 0
}

class BillRepository(private val db: KoshDatabase, private val transactions: TransactionRepository) {

    fun observeUnpaid(): Flow<List<BillItem>> =
        combine(db.bills().observeUnpaid(), db.accounts().observeActive()) { bills, accounts -> items(bills, accounts) }

    fun observeForCard(cardId: Long): Flow<List<BillItem>> =
        combine(db.bills().observeForCard(cardId), db.accounts().observeActive()) { bills, accounts -> items(bills, accounts) }

    suspend fun unpaid(): List<BillItem> = items(db.bills().unpaid(), db.accounts().all())

    /** Adds a bill entered by hand. */
    suspend fun addManual(cardId: Long, totalDue: Paise, minimumDue: Paise?, dueDate: LocalDate, statementDate: LocalDate? = null): Long =
        db.bills().insertIgnore(
            CardBillEntity(
                accountId = cardId,
                totalDue = totalDue,
                minimumDue = minimumDue,
                dueDate = dueDate.toEpochDay(),
                statementDate = statementDate?.toEpochDay(),
                source = com.amitjha.kosh.model.TxnSource.MANUAL,
                fingerprint = "manual-$cardId-${dueDate.toEpochDay()}-$totalDue-${System.nanoTime()}",
            ),
        )

    /**
     * Marks a bill paid. With [fromAccountId], records the payment as a transfer from that bank account to the
     * card ([amount] defaults to the total due), so both balances update.
     */
    suspend fun markPaid(billId: Long, fromAccountId: Long? = null, amount: Paise? = null, at: Long = System.currentTimeMillis()) {
        val bill = db.bills().byId(billId) ?: return
        if (fromAccountId != null) {
            transactions.addTransfer(fromAccountId, bill.accountId, amount ?: bill.totalDue, at, note = "Card bill payment")
        }
        db.bills().update(bill.copy(paid = true, paidAt = at))
    }

    suspend fun markUnpaid(billId: Long) {
        db.bills().byId(billId)?.let { db.bills().update(it.copy(paid = false, paidAt = null)) }
    }

    suspend fun delete(billId: Long) {
        db.bills().byId(billId)?.let { db.bills().delete(it) }
    }

    private fun items(bills: List<CardBillEntity>, accounts: List<AccountEntity>): List<BillItem> {
        val today = Time.today()
        val byId = accounts.associateBy { it.id }
        return bills.map { b ->
            BillItem(b, byId[b.accountId], b.dueDate?.let { ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(it)) })
        }.sortedBy { it.bill.dueDate ?: Long.MAX_VALUE }
    }
}

/** A budget with this period's spending and month-end projection. */
data class BudgetStatus(
    val budget: BudgetEntity,
    val category: CategoryEntity?,
    val spent: Paise,
    val projection: BudgetProjection,
) {
    val isOverall: Boolean get() = budget.categoryKey == null
}

class BudgetRepository(private val db: KoshDatabase, private val settings: SettingsStore) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatuses(): Flow<List<BudgetStatus>> = settings.settings.flatMapLatest { s ->
        val period = Period.containing(Time.today(), s.monthStartDay)
        combine(
            db.budgets().observeAll(),
            db.transactions().observeBetween(period.startMillis, period.endMillis),
            db.categories().observeAll(),
        ) { budgets, txns, cats ->
            val expenses = txns.filter { it.type == TxnType.EXPENSE && SmsMapping.countsInStats(it) }
            val byCat = cats.associateBy { it.key }
            budgets.map { b ->
                val spent = expenses.filter { b.categoryKey == null || it.categoryKey == b.categoryKey }.sumOf { it.amount }
                BudgetStatus(b, b.categoryKey?.let(byCat::get), spent, project(b.monthlyLimit, spent, period))
            }.sortedWith(compareBy({ !it.isOverall }, { -it.projection.usedFraction }))
        }
    }

    suspend fun upsert(categoryKey: String?, monthlyLimit: Paise) {
        val existing = db.budgets().all().firstOrNull { it.categoryKey == categoryKey }
        db.budgets().upsert(BudgetEntity(id = existing?.id ?: 0, categoryKey = categoryKey, monthlyLimit = monthlyLimit))
    }

    suspend fun delete(budget: BudgetEntity) = db.budgets().delete(budget)

    companion object {
        /** Projection over a custom-length period (the finance engine assumes calendar months). */
        fun project(limit: Paise, spent: Paise, period: Period, today: LocalDate = Time.today()): BudgetProjection {
            val elapsed = (ChronoUnit.DAYS.between(period.start, today).toInt() + 1).coerceIn(1, period.lengthDays)
            return if (period.start.dayOfMonth == 1) {
                Budgets.project(limit, spent, today)
            } else {
                BudgetProjection(limit, spent, elapsed, period.lengthDays)
            }
        }
    }
}

data class GoalStatus(val goal: GoalEntity, val plan: GoalPlan) {
    val deadline: LocalDate get() = LocalDate.ofEpochDay(goal.deadline)
}

class GoalRepository(private val db: KoshDatabase) {

    fun observeStatuses(): Flow<List<GoalStatus>> = combine(db.goals().observeAll(), db.transactions().observeChanges()) { goals, _ ->
        val today = Time.today()
        goals.map { g -> GoalStatus(g, Goals.plan(g.target, g.saved, LocalDate.ofEpochDay(g.deadline), today, g.expectedReturnPct)) }
    }

    suspend fun upsert(goal: GoalEntity): Long = db.goals().upsert(goal)

    /** Adds (or with a negative amount, withdraws) savings towards a goal. */
    suspend fun contribute(goalId: Long, amount: Paise) {
        val goal = db.goals().all().firstOrNull { it.id == goalId } ?: return
        db.goals().upsert(goal.copy(saved = (goal.saved + amount).coerceAtLeast(0)))
    }

    suspend fun delete(goal: GoalEntity) = db.goals().delete(goal)
}
