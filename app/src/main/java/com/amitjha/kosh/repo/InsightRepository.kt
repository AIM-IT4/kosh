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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val budgetRepo: BudgetRepository,
) {

    /** Insights the user has not dismissed, most important first. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observeInsights(): Flow<List<Insight>> =
        combine(
            listOf<Flow<Any?>>(
            refreshTick,
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

    /** Bumped to force [observeInsights] to regenerate, e.g. on pull-to-refresh. */
    private val refreshTick = MutableStateFlow(0)

    /** Regenerates the observed insights now. */
    fun refresh() {
        refreshTick.value = refreshTick.value + 1
    }

    /** Builds the engine input from the last ~4 months of data. */
    suspend fun buildInput(today: LocalDate = Time.today()): InsightInput {
        val since = Time.startOfDay(today.withDayOfMonth(1).minusMonths(5))
        val monthStartDay = settings.settings.first().monthStartDay
        val accountList = accounts.accounts()
        val cardIds = accountList.filter { it.isCard }.map { it.id }.toSet()
        // Split transactions count as their parts, as on the Home and Budgets screens.
        val txns = com.amitjha.kosh.domain.Splits.expand(
            db.transactions().since(since).filter { SmsMapping.countsInStats(it) },
            com.amitjha.kosh.domain.Splits.byTransaction(db.splits().all()),
        )
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
            // Limits include what rolled over, matching the Budgets screen.
            budgets = budgetRepo.observeStatuses().first().map { BudgetInput(it.budget.categoryKey, it.limit) },
            goals = db.goals().all().map {
                GoalInput(
                    name = it.name,
                    target = it.target,
                    saved = it.saved,
                    deadline = LocalDate.ofEpochDay(it.deadline),
                    expectedReturnPct = it.expectedReturnPct,
                    loanAmount = it.loanAmount,
                    loanRatePct = it.loanRatePct,
                    loanTenureMonths = it.loanTenureMonths,
                    createdAt = Time.toDate(it.createdAt),
                    id = it.id,
                )
            },
            // Accounts the user hasn't confirmed have no trustworthy balance, so balance rules ignore them.
            accounts = accountList.filter { it.counted }.map { a ->
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
                BillInput(
                    byId[b.accountId]?.account?.name ?: "Card", b.totalDue, LocalDate.ofEpochDay(due), b.paid,
                    accountId = b.accountId, statementDate = b.statementDate?.let(LocalDate::ofEpochDay),
                )
            },
            investments = db.investments().active().map {
                InvestmentInput(
                    it.name, it.type, it.currentValue, it.maturityDate?.let(LocalDate::ofEpochDay),
                    maturityValue = maturityValue(it),
                )
            },
            categoryNames = db.categories().all().associate { it.key to it.name },
            monthStartDay = monthStartDay,
        )
    }
}

/**
 * Net worth split into its parts: money in confirmed bank, cash and wallet accounts, minus amounts owed on
 * confirmed credit cards, plus investments. [counted] lists the accounts behind [liquid] and [cardDebt];
 * [pending] lists accounts found in SMS/e-mail that are not counted until the user confirms them.
 */
data class NetWorth(
    val liquid: Paise,
    val cardDebt: Paise,
    val investments: Paise,
    val investmentsByType: Map<InvestmentType, Paise>,
    val counted: List<AccountWithBalance> = emptyList(),
    val pending: List<AccountWithBalance> = emptyList(),
    val investmentItems: List<Triple<String, InvestmentType, Paise>> = emptyList(),
    /** Still owed on loans (home, car, personal, education). */
    val loans: Paise = 0,
    val loanItems: List<Pair<String, Paise>> = emptyList(),
) {
    val total: Paise get() = liquid - cardDebt - loans + investments
}

class NetWorthRepository(
    private val db: KoshDatabase,
    private val accounts: AccountRepository,
    private val loanRepo: LoanRepository,
) {

    fun observeNetWorth(): Flow<NetWorth> =
        combine(accounts.observeAccounts(), db.investments().observeActive(), loanRepo.observe()) { accs, invs, loans ->
            compute(accs, invs.map { Triple(it.name, it.type, it.currentValue) }, loans)
        }

    fun observeHistory(): Flow<List<NetWorthSnapshotEntity>> = db.netWorth().observeAll()

    suspend fun current(): NetWorth =
        compute(accounts.accounts(), db.investments().active().map { Triple(it.name, it.type, it.currentValue) }, loanRepo.active())

    /** Records today's net worth for the history chart (called by the daily worker). Loans count with card dues as debt. */
    suspend fun snapshot(today: LocalDate = Time.today()) {
        val nw = current()
        db.netWorth().upsert(NetWorthSnapshotEntity(today.toEpochDay(), nw.liquid, nw.cardDebt + nw.loans, nw.investments, nw.total))
    }

    /** Clears the recorded history (used once after the v1.4 balance-rule repair) and records today afresh. */
    suspend fun resetHistory(today: LocalDate = Time.today()) {
        db.netWorth().deleteAll()
        snapshot(today)
    }

    private fun compute(
        accs: List<AccountWithBalance>,
        investments: List<Triple<String, InvestmentType, Paise>>,
        loans: List<LoanStatus> = emptyList(),
    ): NetWorth {
        val counted = accs.filter { it.counted }
        val liquid = counted.filter { it.account.type != AccountType.CREDIT_CARD }.sumOf { it.balance }
        val debt = counted.filter { it.isCard }.sumOf { it.outstanding.coerceAtLeast(0) }
        return NetWorth(
            liquid = liquid,
            cardDebt = debt,
            investments = investments.sumOf { it.third },
            investmentsByType = investments.groupBy { it.second }.mapValues { (_, v) -> v.sumOf { it.third } },
            counted = counted,
            pending = accs.filter { it.isPending },
            investmentItems = investments,
            loans = loans.sumOf { it.outstanding },
            loanItems = loans.filter { it.outstanding > 0 }.map { it.loan.name to it.outstanding },
        )
    }
}

/** What an FD pays at maturity, from its stored spec (null for other investments or unreadable specs). */
internal fun maturityValue(e: com.amitjha.kosh.data.InvestmentEntity): Paise? {
    if (e.type != InvestmentType.FD) return null
    val spec = runCatching { com.amitjha.kosh.domain.SpecCodec.decode(e.specJson) }.getOrNull() as? com.amitjha.kosh.domain.InvestmentSpec.Fd
        ?: return null
    return spec.toFinance(e.institution, e.name).maturityValue()
}
