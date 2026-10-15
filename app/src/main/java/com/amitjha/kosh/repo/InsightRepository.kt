package com.amitjha.kosh.repo

import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.NetWorthSnapshotEntity
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.finance.AccountInput
import com.amitjha.kosh.finance.BillInput
import com.amitjha.kosh.finance.BudgetInput
import com.amitjha.kosh.finance.GoalInput
import com.amitjha.kosh.finance.Insight
import com.amitjha.kosh.finance.InsightEngine
import com.amitjha.kosh.finance.InsightInput
import com.amitjha.kosh.finance.InsightTxn
import com.amitjha.kosh.finance.InvestmentInput
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate

/** Offline insights from the rule engine, regenerated whenever the underlying data changes. */
class InsightRepository(
    private val db: KoshDatabase,
    private val accounts: AccountRepository,
    private val settings: SettingsStore,
) {

    /** Insights the user has not dismissed, most important first. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observeInsights(): Flow<List<Insight>> =
        combine(
            listOf<Flow<Any?>>(
            db.transactions().observeChanges(),
            db.accounts().observeActive(),
            db.budgets().observeAll(),
            db.goals().observeAll(),
            db.bills().observeUnpaid(),
            db.investments().observeActive(),
            settings.settings,
            ),
        ) { values -> values.size }
            .debounce(300)
            .mapLatest { generate(includeDismissed = false) }
            .flowOn(Dispatchers.Default)

    suspend fun generate(includeDismissed: Boolean = false, today: LocalDate = Time.today()): List<Insight> {
        val input = buildInput(today)
        val dismissed = if (includeDismissed) emptySet() else settings.current().dismissedInsightIds
        return InsightEngine.generate(input).filterNot { it.id in dismissed }
    }

    suspend fun dismiss(id: String) = settings.dismissInsight(id)

    /** Builds the engine input from the last ~4 months of data. */
    suspend fun buildInput(today: LocalDate = Time.today()): InsightInput {
        val since = Time.startOfDay(today.withDayOfMonth(1).minusMonths(4))
        val accountList = accounts.accounts()
        val cardIds = accountList.filter { it.isCard }.map { it.id }.toSet()
        val txns = db.transactions().since(since)
            .filter { SmsMapping.countsInStats(it) }
            .map { t ->
                InsightTxn(
                    date = Time.toDate(t.timestamp),
                    amount = t.amount,
                    type = t.type,
                    category = t.categoryKey,
                    merchant = t.merchant,
                    accountId = t.accountId,
                    isCreditCard = t.accountId in cardIds,
                )
            }
        val byId = accountList.associateBy { it.id }
        return InsightInput(
            today = today,
            transactions = txns,
            budgets = db.budgets().all().map { BudgetInput(it.categoryKey, it.monthlyLimit) },
            goals = db.goals().all().map { GoalInput(it.name, it.target, it.saved, LocalDate.ofEpochDay(it.deadline)) },
            accounts = accountList.map { a ->
                AccountInput(
                    id = a.id,
                    name = a.account.name,
                    type = a.account.type,
                    // The engine reads a card's balance as the amount owed.
                    balance = if (a.isCard) a.outstanding else a.balance,
                    creditLimit = a.account.creditLimit,
                )
            },
            upcomingBills = db.bills().unpaid().mapNotNull { b ->
                val due = b.dueDate ?: return@mapNotNull null
                BillInput(byId[b.accountId]?.account?.name ?: "Card", b.totalDue, LocalDate.ofEpochDay(due), b.paid, accountId = b.accountId)
            },
            investments = db.investments().active().map {
                InvestmentInput(it.name, it.type, it.currentValue, it.maturityDate?.let(LocalDate::ofEpochDay))
            },
            categoryNames = db.categories().all().associate { it.key to it.name },
        )
    }
}

/** Net worth split into its parts. */
data class NetWorth(
    val liquid: Paise,
    val cardDebt: Paise,
    val investments: Paise,
    val investmentsByType: Map<InvestmentType, Paise>,
) {
    val total: Paise get() = liquid - cardDebt + investments
}

class NetWorthRepository(
    private val db: KoshDatabase,
    private val accounts: AccountRepository,
) {

    fun observeNetWorth(): Flow<NetWorth> =
        combine(accounts.observeAccounts(), db.investments().observeActive()) { accs, invs -> compute(accs, invs.map { it.type to it.currentValue }) }

    fun observeHistory(): Flow<List<NetWorthSnapshotEntity>> = db.netWorth().observeAll()

    suspend fun current(): NetWorth =
        compute(accounts.accounts(), db.investments().active().map { it.type to it.currentValue })

    /** Records today's net worth for the history chart (called by the daily worker). */
    suspend fun snapshot(today: LocalDate = Time.today()) {
        val nw = current()
        db.netWorth().upsert(NetWorthSnapshotEntity(today.toEpochDay(), nw.liquid, nw.cardDebt, nw.investments, nw.total))
    }

    private fun compute(accs: List<AccountWithBalance>, investments: List<Pair<InvestmentType, Paise>>): NetWorth {
        val liquid = accs.filter { it.account.type != AccountType.CREDIT_CARD }.sumOf { it.balance }
        val debt = accs.filter { it.isCard }.sumOf { it.outstanding.coerceAtLeast(0) }
        return NetWorth(
            liquid = liquid,
            cardDebt = debt,
            investments = investments.sumOf { it.second },
            investmentsByType = investments.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } },
        )
    }
}
