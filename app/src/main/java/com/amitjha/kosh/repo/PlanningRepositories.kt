package com.amitjha.kosh.repo

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.domain.MoneyIn
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.finance.BudgetProjection
import com.amitjha.kosh.finance.Budgets
import com.amitjha.kosh.finance.GoalPlan
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    /** Rolled over from earlier periods (negative after overspending); 0 without rollover. */
    val carry: Paise = 0,
) {
    val isOverall: Boolean get() = budget.categoryKey == null
    /** This period's limit including what rolled over. */
    val limit: Paise get() = (budget.monthlyLimit + carry).coerceAtLeast(0)
}

/** The zero-based view of a period: income, what category budgets assign, and what's left to assign. */
data class EnvelopeStatus(val envelope: com.amitjha.kosh.finance.BudgetMath.Envelope, val period: Period)

class BudgetRepository(
    private val db: KoshDatabase,
    private val settings: SettingsStore,
    private val features: com.amitjha.kosh.data.prefs.FeaturePrefs,
) {

    /** Only the pay cycle matters here, so other settings writes (scan times, privacy mode) don't re-query 13 months. */
    private fun cycle() = settings.settings.map { it.payCycle }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatuses(): Flow<List<BudgetStatus>> = cycle().flatMapLatest { payCycle ->
        val period = Period.containing(Time.today(), payCycle)
        // Rollover looks back up to [MAX_ROLLOVER_PERIODS] periods.
        var earliest = period
        repeat(MAX_ROLLOVER_PERIODS) { earliest = earliest.previous() }
        combine(
            combine(db.budgets().observeAll(), features.settings) { budgets, f -> budgets to f.rolloverSince },
            db.transactions().observeBetween(earliest.startMillis, period.endMillis),
            db.categories().observeAll(),
            db.splits().observeAll(),
        ) { (budgets, since), txns, cats, splits ->
            val parts = com.amitjha.kosh.domain.Splits.expand(
                txns.filter { SmsMapping.countsInStats(it) },
                com.amitjha.kosh.domain.Splits.byTransaction(splits),
            )
            val all = parts.filter { it.type == TxnType.EXPENSE }
            val back = parts.filter(MoneyIn::isGivenBack)
            val byCat = cats.associateBy { it.key }
            // Spending per category in each period, with refunds and cashback taken off.
            val netIn = HashMap<Period, Map<String, Paise>>()
            fun net(p: Period) = netIn.getOrPut(p) {
                MoneyIn.netByCategory(all.filter { Time.toDate(it.timestamp) in p }, back.filter { Time.toDate(it.timestamp) in p }, all)
            }
            fun spentIn(b: BudgetEntity, p: Period): Paise =
                net(p).let { m -> if (b.categoryKey == null) m.values.sum() else m[b.categoryKey] ?: 0L }
            budgets.map { b ->
                val spent = spentIn(b, period)
                val carry = if (!b.rollover) 0L else {
                    val start = since[b.id]
                    val history = generateSequence(period.previous()) { it.previous() }.take(MAX_ROLLOVER_PERIODS)
                        .filter { start == null || it.start.toEpochDay() >= start }
                        .toList().asReversed()
                        .map { p -> b.monthlyLimit to spentIn(b, p) }
                    com.amitjha.kosh.finance.BudgetMath.rolloverCarry(history)
                }
                val limit = (b.monthlyLimit + carry).coerceAtLeast(0)
                BudgetStatus(b, b.categoryKey?.let(byCat::get), spent, project(limit, spent, period), carry)
            }.sortedWith(compareBy({ !it.isOverall }, { -it.projection.usedFraction }))
        }
    }

    /** Income of the current period against what category budgets assign. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeEnvelope(): Flow<EnvelopeStatus> = cycle().flatMapLatest { payCycle ->
        val period = Period.containing(Time.today(), payCycle)
        combine(db.budgets().observeAll(), db.transactions().observeBetween(period.startMillis, period.endMillis)) { budgets, txns ->
            val income = txns.filter { MoneyIn.isEarned(it) && SmsMapping.countsInStats(it) && Time.toDate(it.timestamp) in period }
                .sumOf { it.amount }
            EnvelopeStatus(
                com.amitjha.kosh.finance.BudgetMath.envelope(income, budgets.filter { it.categoryKey != null }.map { it.monthlyLimit }),
                period,
            )
        }
    }

    suspend fun upsert(categoryKey: String?, monthlyLimit: Paise, rollover: Boolean? = null) {
        val existing = db.budgets().all().firstOrNull { it.categoryKey == categoryKey }
        val roll = rollover ?: existing?.rollover ?: false
        val id = db.budgets().upsertReturning(
            BudgetEntity(id = existing?.id ?: 0, categoryKey = categoryKey, monthlyLimit = monthlyLimit, rollover = roll),
        )
        val budgetId = existing?.id ?: id
        if (roll && existing?.rollover != true) {
            // Rollover starts with the current period: earlier months don't carry anything in.
            val period = Period.containing(Time.today(), settings.current().payCycle)
            features.setRolloverSince(budgetId, period.start.toEpochDay())
        } else if (!roll) {
            features.setRolloverSince(budgetId, null)
        }
    }

    suspend fun delete(budget: BudgetEntity) {
        db.budgets().delete(budget)
        features.setRolloverSince(budget.id, null)
    }

    companion object {
        const val MAX_ROLLOVER_PERIODS = 12

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

data class GoalStatus(
    val goal: GoalEntity,
    val plan: GoalPlan,
    /** How the goal stands against the usual monthly surplus; the insights use the same judgement. */
    val standing: com.amitjha.kosh.finance.GoalStanding = com.amitjha.kosh.finance.GoalStanding.UNKNOWN,
    /** Surplus left for this goal after earlier ones, a month; null without history. */
    val capacity: Paise? = null,
    val inflationPct: Double = 0.0,
    /** Free for it right now, while earlier goals are still being saved for. */
    val freeNow: Paise? = capacity,
    /** What the user usually has free a month for all goals, and the pay periods it comes from (shown on the Goals tab). */
    val usualSurplus: Paise? = null,
    val surplusPeriods: List<com.amitjha.kosh.finance.PeriodMoney> = emptyList(),
    /** The take-home salary the user entered, the least a period counts as earning. */
    val statedSalary: Paise? = null,
) {
    val deadline: LocalDate get() = LocalDate.ofEpochDay(goal.deadline)

    /** Green on the Goals screen: reached, on track, or not yet judged. */
    val looksOnTrack: Boolean
        get() = standing == com.amitjha.kosh.finance.GoalStanding.REACHED || standing == com.amitjha.kosh.finance.GoalStanding.ON_TRACK ||
            standing == com.amitjha.kosh.finance.GoalStanding.UNKNOWN
    val hasLoan: Boolean get() = goal.loanAmount > 0
    /** Monthly EMI of the purchase loan, or 0 without one. */
    val loanEmi: Long get() = com.amitjha.kosh.domain.GoalLoan.emi(goal.loanAmount, goal.loanRatePct, goal.loanTenureMonths)
}

/** A goal as the shared goal maths sees it. */
fun GoalEntity.toInput(inflationPct: Double = 0.0) = com.amitjha.kosh.finance.GoalInput(
    name = name,
    target = target,
    saved = saved,
    deadline = LocalDate.ofEpochDay(deadline),
    expectedReturnPct = expectedReturnPct,
    loanAmount = loanAmount,
    loanRatePct = loanRatePct,
    loanTenureMonths = loanTenureMonths,
    createdAt = Time.toDate(createdAt),
    id = id,
    inflationPct = inflationPct,
)

/**
 * Each of the last [GOAL_SURPLUS_PERIODS] complete pay periods as goals see it: earned income, spending, the part put
 * into investments (saved, not spent) and refunds and cashback (which come off spending). [GoalSurplus.usual] turns
 * them into the usual monthly surplus, by the insights' rule. Newest period first.
 */
internal fun goalPeriods(txns: List<com.amitjha.kosh.data.TransactionEntity>, periods: List<Period>): List<com.amitjha.kosh.finance.PeriodMoney> {
    val counted = txns.filter(SmsMapping::countsInStats)
    val day = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
    return periods.map { p ->
        val inP = counted.filter { it.timestamp in p.startMillis..p.endMillis }
        val expenses = inP.filter { it.type == com.amitjha.kosh.model.TxnType.EXPENSE }
        com.amitjha.kosh.finance.PeriodMoney(
            label = "${day.format(p.start)} – ${day.format(p.endExclusive.minusDays(1))}",
            earned = inP.filter(MoneyIn::isEarned).sumOf { it.amount },
            spent = expenses.sumOf { it.amount },
            invested = expenses.filter { it.categoryKey in com.amitjha.kosh.finance.InsightEngine.SAVED_CATEGORIES }.sumOf { it.amount },
            givenBack = inP.filter(MoneyIn::isGivenBack).sumOf { it.amount },
        )
    }
}

const val GOAL_SURPLUS_PERIODS = com.amitjha.kosh.finance.InsightEngine.GOAL_SURPLUS_MONTHS

private data class GoalInputs(
    val goals: List<GoalEntity>,
    val inflation: Map<Long, Double>,
    val cycle: com.amitjha.kosh.finance.PayCycle,
    val salary: Paise?,
)

class GoalRepository(
    private val db: KoshDatabase,
    private val settings: SettingsStore,
    private val prefs: com.amitjha.kosh.data.prefs.PlanningPrefs,
    private val featurePrefs: com.amitjha.kosh.data.prefs.FeaturePrefs,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatuses(): Flow<List<GoalStatus>> = combine(
        db.goals().observeAll(),
        prefs.goalInflation,
        settings.settings.map { it.payCycle }.distinctUntilChanged(),
        db.transactions().observeChanges(),
        featurePrefs.settings.map { it.salary }.distinctUntilChanged(),
    ) { goals, inflation, cycle, _, salary -> GoalInputs(goals, inflation, cycle, salary) }
        .mapLatest { (goals, inflation, cycle, salary) ->
            val today = Time.today()
            val current = Period.containing(today, cycle)
            val periods = generateSequence(current.previous()) { it.previous() }.take(GOAL_SURPLUS_PERIODS).toList()
            val txns = com.amitjha.kosh.domain.Splits.expand(
                db.transactions().between(periods.last().startMillis, current.startMillis - 1),
                com.amitjha.kosh.domain.Splits.byTransaction(db.splits().all()),
            )
            val months = goalPeriods(txns, periods)
            val surplus = com.amitjha.kosh.finance.GoalSurplus.usual(months, salary)
            val assessed = com.amitjha.kosh.finance.GoalTracker.assess(goals.map { it.toInput(inflation[it.id] ?: 0.0) }, surplus, today)
                .associate { (g, a) -> g.id to a }
            goals.map { g ->
                val a = assessed.getValue(g.id)
                GoalStatus(g, a.plan, a.standing, a.capacity, inflation[g.id] ?: 0.0, a.freeNow, surplus, months, salary)
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)

    suspend fun upsert(goal: GoalEntity): Long = db.goals().upsert(goal)

    /** Adds (or with a negative amount, withdraws) savings towards a goal. */
    suspend fun contribute(goalId: Long, amount: Paise) {
        val goal = db.goals().all().firstOrNull { it.id == goalId } ?: return
        db.goals().upsert(goal.copy(saved = (goal.saved + amount).coerceAtLeast(0)))
    }

    suspend fun delete(goal: GoalEntity) = db.goals().delete(goal)
}
