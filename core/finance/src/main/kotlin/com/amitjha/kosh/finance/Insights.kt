package com.amitjha.kosh.finance

import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class InsightTxn(
    val date: LocalDate,
    val amount: Paise,
    val type: TxnType,
    val category: String,
    val merchant: String?,
    val accountId: Long,
    val isCreditCard: Boolean = false,
    /**
     * For income: whether it is earned (salary, interest, business, rent). Refunds, cashback and money received from
     * others come in too but aren't income, so savings figures and goals leave them out.
     */
    val earned: Boolean = true,
)

data class BudgetInput(val category: String?, val monthlyLimit: Paise)
/**
 * A savings goal exactly as the user planned it. A purchase [loanAmount] reduces what has to be
 * saved, and savings grow at [expectedReturnPct].
 */
data class GoalInput(
    val name: String,
    val target: Paise,
    val saved: Paise,
    val deadline: LocalDate,
    val expectedReturnPct: Double = 0.0,
    val loanAmount: Paise = 0,
    val loanRatePct: Double = 9.0,
    val loanTenureMonths: Int = 36,
    val createdAt: LocalDate? = null,
    /** Database id, used to open the goal from the insight. */
    val id: Long? = null,
    /** How fast the price rises, % a year (0 = fixed in today's rupees). */
    val inflationPct: Double = 0.0,
)
data class AccountInput(val id: Long, val name: String, val type: AccountType, val balance: Paise, val creditLimit: Paise? = null)
data class BillInput(
    val cardName: String,
    val amount: Paise,
    val dueDate: LocalDate,
    val paid: Boolean = false,
    /** The card account the bill belongs to, when known (links new card spends to the bill). */
    val accountId: Long? = null,
    /** Statement date of the bill, when known; spends after it belong to the next bill. */
    val statementDate: LocalDate? = null,
)
data class InvestmentInput(
    val name: String,
    val type: InvestmentType,
    val value: Paise,
    val maturityDate: LocalDate? = null,
    /** What an FD pays out at maturity, when known (today's value is used otherwise). */
    val maturityValue: Paise? = null,
)

data class InsightInput(
    val today: LocalDate,
    val transactions: List<InsightTxn> = emptyList(),
    val budgets: List<BudgetInput> = emptyList(),
    val goals: List<GoalInput> = emptyList(),
    val accounts: List<AccountInput> = emptyList(),
    val upcomingBills: List<BillInput> = emptyList(),
    val investments: List<InvestmentInput> = emptyList(),
    val monthlyIncomeHint: Paise? = null,
    /** Display names for category keys ("food" → "Food & Dining"); keys are shown as-is when missing. */
    val categoryNames: Map<String, String> = emptyMap(),
    /**
     * First day of the user's budgeting month (e.g. 25 = salary day). Periods then run from the
     * 25th to the 24th and are labelled with the month holding most of their days.
     */
    val monthStartDay: Int = 1,
    /**
     * When salary actually arrives (weekends, holidays, the day it came). With one that moves paydays, each period
     * runs from its real payday instead of a fixed day; null keeps the fixed [monthStartDay].
     */
    val payCycle: PayCycle? = null,
)

/** Severity, from most to least urgent. */
enum class Severity { ALERT, WARNING, TIP, GOOD }

/** A labelled figure shown as a chip on an insight, e.g. "3-mo avg" / "₹8,100". */
data class InsightMetric(val label: String, val value: String)

/** Small chart data attached to an insight. Values are in paise unless [percent] is set. */
data class InsightChart(
    val kind: Kind,
    val labels: List<String>,
    val values: List<Long>,
    /** An optional reference line, e.g. the budget limit on a cumulative spend line. */
    val reference: Long? = null,
    val caption: String? = null,
) {
    enum class Kind { BARS, LINE }
}

/** Where an insight's action button leads. */
enum class InsightRoute { CATEGORY, BUDGETS, GOALS, BILLS, ACTIVITY, WEALTH, ACCOUNTS }

data class InsightAction(val label: String, val route: InsightRoute, val argument: String? = null)

data class Insight(
    /** Stable across runs for the same underlying situation, so the UI can dismiss it. */
    val id: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val category: String? = null,
    /** Higher is more important within a severity. */
    val score: Int = 0,
    /** A longer explanation with the numbers behind the insight. */
    val detail: String? = null,
    val metrics: List<InsightMetric> = emptyList(),
    val chart: InsightChart? = null,
    val action: InsightAction? = null,
) {
    /** The monthly overview shown as the header of the insights screen rather than as a card. */
    val isSummary: Boolean get() = id.startsWith(SUMMARY_PREFIX)

    companion object {
        const val SUMMARY_PREFIX = "summary_month_"
    }
}

/** Offline rule engine: turns transactions, budgets, goals and balances into ranked insights. */
object InsightEngine {

    // Thresholds
    const val SPIKE_FACTOR = 1.5
    val SPIKE_MIN_BASE: Paise = Money.fromRupees(1_000.0)
    const val SAVINGS_GUIDELINE = 0.20
    /** A goal is comfortably on track when it needs at most this share of the available surplus. */
    const val GOAL_COMFORT_SHARE = GoalTracker.COMFORT_SHARE

    /** Expense categories that are money put aside rather than spent. */
    val SAVED_CATEGORIES = setOf("investment")
    /** Complete budgeting months averaged to estimate the monthly surplus available for goals. */
    const val GOAL_SURPLUS_MONTHS = 3
    const val SAVINGS_GREAT = 0.30
    const val EMERGENCY_MIN_MONTHS = 3.0
    const val EMERGENCY_TARGET_MONTHS = 6.0
    val IDLE_CASH_MIN_EXCESS: Paise = Money.fromRupees(50_000.0)
    const val CARD_UTILISATION_WARN = 0.30
    const val CARD_UTILISATION_ALERT = 0.75
    const val BILL_ALERT_DAYS = 3L
    const val BILL_NOTICE_DAYS = 7L
    const val FD_MATURITY_DAYS = 30L
    const val SUBSCRIPTION_MIN_OCCURRENCES = 3
    const val SUBSCRIPTION_AMOUNT_TOLERANCE = 0.10
    val SUBSCRIPTION_INTERVAL_DAYS = 25L..35L
    val SMALL_SPEND_MAX: Paise = Money.fromRupees(500.0)
    const val SMALL_SPEND_MIN_COUNT = 10
    const val WEEKEND_FACTOR = 1.5
    val WEEKEND_MIN_TOTAL: Paise = Money.fromRupees(3_000.0)
    const val LARGE_TXN_FACTOR = 3.0
    val LARGE_TXN_MIN: Paise = Money.fromRupees(10_000.0)
    const val CONCENTRATION_SHARE = 0.25
    val CONCENTRATION_MIN_TOTAL: Paise = Money.fromRupees(5_000.0)
    const val NO_INCOME_FROM_DAY = 10
    const val BUDGET_ON_TRACK_FROM_DAY = 20
    const val SPENDING_DOWN_FROM_DAY = 15

    /** A period due to start after this day of the month is named after the next month (as `Period.label`). */
    const val PERIOD_LABEL_AFTER_DAY = 15

    /** Assumed gap between a card's statement date and its due date when the statement date is unknown. */
    const val ASSUMED_STATEMENT_TO_DUE_DAYS = 20L

    fun generate(input: InsightInput): List<Insight> {
        val startDay = input.monthStartDay.coerceIn(1, 28)
        val shift = (startDay - 1).toLong()
        val cycle = input.payCycle?.takeIf { it.moves && it.dueDay == startDay }
        // A period from day S of month M to day S-1 of M+1 has exactly as many days as M, so moving
        // every date back by S-1 days maps budgeting periods exactly onto calendar months. When paydays move
        // (weekends, holidays), each date maps to its day within its own period instead.
        dateShift.set(shift)
        engineCycle.set(cycle)
        labelDay.set(startDay)
        val toEngine: (LocalDate) -> LocalDate = if (cycle != null) { d -> engineDate(cycle, d) } else { d -> d.minusDays(shift) }
        val shifted = if (shift == 0L) input else input.copy(
            today = toEngine(input.today),
            transactions = input.transactions.map { it.copy(date = toEngine(it.date)) },
            goals = input.goals.map { it.copy(deadline = toEngine(it.deadline), createdAt = it.createdAt?.let(toEngine)) },
            upcomingBills = input.upcomingBills.map {
                it.copy(dueDate = toEngine(it.dueDate), statementDate = it.statementDate?.let(toEngine))
            },
            investments = input.investments.map { it.copy(maturityDate = it.maturityDate?.let(toEngine)) },
        )
        val ctx = Context(shifted)
        val rules: List<(Context) -> List<Insight>> = listOf(
            ::budgetRules, ::categorySpikes, ::savingsRate, ::noIncome, ::goalRules, ::emergencyFund,
            ::idleCash, ::cardUtilisation, ::billsDue, ::fdMaturity, ::subscriptions, ::frequentSmallSpends,
            ::weekendSpending, ::largeTransactions, ::merchantConcentration, ::spendingDown, ::startInvesting,
            ::monthlySummary, ::topMerchants, ::categoryMovers, ::cashFlowForecast, ::recurringPayments, ::cardSpendVsBill,
        )
        return rules.flatMap { it(ctx) }
            .map { enrich(ctx, it) }
            .sortedWith(compareBy<Insight> { it.severity.ordinal }.thenByDescending { it.score }.thenBy { it.id })
    }

    // ------------------------------------------------------------------ helpers

    private class Context(val input: InsightInput) {
        val today = input.today
        val month: YearMonth = YearMonth.from(today)
        val expenses = input.transactions.filter { it.type == TxnType.EXPENSE }
        val incomes = input.transactions.filter { it.type == TxnType.INCOME }

        fun expensesIn(m: YearMonth, category: String? = null) =
            expenses.filter { YearMonth.from(it.date) == m && (category == null || it.category == category) }

        fun spent(m: YearMonth, category: String? = null): Paise = expensesIn(m, category).sumOf { it.amount }

        /** Earned income only: refunds, cashback and money from others aren't income. */
        fun income(m: YearMonth): Paise = incomes.filter { it.earned && YearMonth.from(it.date) == m }.sumOf { it.amount }

        /** Money moved into investments (SIPs and the like) in [m]: saved, not spent. */
        fun invested(m: YearMonth): Paise = expensesIn(m).filter { it.category in SAVED_CATEGORIES }.sumOf { it.amount }

        /** Average monthly expense over the three full months before this one (only months with data). */
        val avgMonthlyExpense: Paise by lazy {
            val months = (1..3).map { month.minusMonths(it.toLong()) }.map { spent(it) }.filter { it > 0 }
            if (months.isEmpty()) 0 else months.sum() / months.size
        }

        val liquid: Paise by lazy {
            input.accounts.filter { it.type != AccountType.CREDIT_CARD }.sumOf { maxOf(0, it.balance) }
        }

        fun label(category: String?): String =
            category?.let { input.categoryNames[it] ?: it.replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() } } ?: "Overall"

        /** The last [n] months, oldest first, ending with this month. */
        fun lastMonths(n: Int = 6): List<YearMonth> = (n - 1 downTo 0).map { month.minusMonths(it.toLong()) }

        fun monthLabel(m: YearMonth): String =
            periodLabel(m).month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)

        /** Spending this month accumulated day by day, up to today. */
        fun cumulativeThisMonth(category: String? = null): List<Long> {
            var acc = 0L
            val byDay = expensesIn(month, category).groupBy { it.date.dayOfMonth }.mapValues { (_, t) -> t.sumOf { it.amount } }
            return (1..today.dayOfMonth).map { d -> acc += byDay[d] ?: 0L; acc }
        }

        val daysInMonth: Int get() = month.lengthOfMonth()
        val daysLeft: Int get() = daysInMonth - today.dayOfMonth
    }

    private val dateShift = ThreadLocal.withInitial { 0L }
    private val engineCycle = ThreadLocal<PayCycle?>()
    private val labelDay = ThreadLocal.withInitial { 1 }

    /**
     * The engine date of real date [d] when paydays move: engine month = the month whose salary starts d's period,
     * day = d's day within that period (a period longer than the month ends on its last day).
     */
    private fun engineDate(cycle: PayCycle, d: LocalDate): LocalDate {
        val p = cycle.periodContaining(d)
        val day = java.time.temporal.ChronoUnit.DAYS.between(p.start, d) + 1
        return p.month.atDay(day.coerceAtMost(p.month.lengthOfMonth().toLong()).toInt())
    }

    /**
     * The month a budgeting period is named after. The period behind engine month [m] is due to start on day
     * `monthStartDay` of [m]; like the app's `Period.label`, one due on day 16–28 is named after the next month.
     */
    internal fun periodLabel(m: YearMonth, monthStartDay: Int = labelDay.get()): YearMonth =
        if (monthStartDay.coerceIn(1, 28) > PERIOD_LABEL_AFTER_DAY) m.plusMonths(1) else m

    /** The real calendar date of a (shifted) engine date. */
    private fun real(d: LocalDate): LocalDate =
        engineCycle.get()?.let { it.payday(YearMonth.from(d)).plusDays(d.dayOfMonth - 1L) } ?: d.plusDays(dateShift.get())

    private fun isWeekendDay(d: LocalDate): Boolean =
        real(d).dayOfWeek == DayOfWeek.SATURDAY || real(d).dayOfWeek == DayOfWeek.SUNDAY

    private fun realDay(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${d.year}"

    private fun fmt(p: Paise) = Money.format(p, showPaise = false)
    private fun day(d: LocalDate) = real(d).let { "${it.dayOfMonth} ${it.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}" }

    /** Regular commitments that a "still using it?" nudge makes no sense for. */
    private val NON_DISCRETIONARY = setOf("rent", "emi", "insurance", "investment", "taxes", "people", "transfer", "bills", "education", "salary")
    private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    // ------------------------------------------------------------------ rules

    private fun budgetRules(c: Context): List<Insight> = c.input.budgets.mapNotNull { b ->
        val spent = c.spent(c.month, b.category)
        val p = Budgets.project(b.monthlyLimit, spent, c.today)
        val label = c.label(b.category)
        val id = "budget_${slug(label)}_${c.month}"
        when {
            p.isExceeded -> Insight(
                id, Severity.ALERT, "$label budget exceeded",
                "You've spent ${fmt(spent)} against a ${fmt(b.monthlyLimit)} budget, ${fmt(spent - b.monthlyLimit)} over.",
                b.category, 90,
            )
            p.willExceed -> Insight(
                id, Severity.WARNING, "$label budget on course to overshoot",
                "At this pace you'll spend about ${fmt(p.projected)} this month, ${fmt(p.projected - b.monthlyLimit)} over " +
                    "your ${fmt(b.monthlyLimit)} budget. Keep daily spending under ${fmt(p.dailyAllowance)} to stay within it.",
                b.category, 70,
            )
            b.category == null && c.today.dayOfMonth >= BUDGET_ON_TRACK_FROM_DAY && p.projected <= b.monthlyLimit * 0.9 -> Insight(
                id, Severity.GOOD, "Comfortably within budget",
                "You're heading for about ${fmt(p.projected)} against a ${fmt(b.monthlyLimit)} budget. Nice discipline.",
                null, 10,
            )
            else -> null
        }
    }

    private fun categorySpikes(c: Context): List<Insight> {
        val categories = c.expensesIn(c.month).map { it.category }.distinct()
        return categories.mapNotNull { cat ->
            // New users have little history: average only the months that had spending in this category.
            val past = (1..3).map { c.spent(c.month.minusMonths(it.toLong()), cat) }.filter { it > 0 }
            if (past.isEmpty()) return@mapNotNull null
            val avg = past.sum() / past.size
            val now = c.spent(c.month, cat)
            if (avg >= SPIKE_MIN_BASE && now > avg * SPIKE_FACTOR) {
                Insight(
                    "spike_${slug(cat)}_${c.month}", Severity.WARNING, "${c.label(cat)} spending is up",
                    "You've spent ${fmt(now)} on ${c.label(cat)} this month, ${((now.toDouble() / avg - 1) * 100).roundToInt()}% more than " +
                        "your ${if (past.size == 1) "previous month's" else "${past.size}-month average of"} ${fmt(avg)}.",
                    cat, 60,
                )
            } else null
        }
    }

    private fun savingsRate(c: Context): List<Insight> {
        val income = c.income(c.month).takeIf { it > 0 } ?: c.input.monthlyIncomeHint ?: return emptyList()
        if (income <= 0) return emptyList()
        val spent = c.spent(c.month)
        val rate = (income - spent).toDouble() / income
        val last = c.month.minusMonths(1)
        val lastIncome = c.income(last)
        val lastNote = if (lastIncome > 0) {
            " Last month you saved ${pct((lastIncome - c.spent(last)).toDouble() / lastIncome)}."
        } else ""
        val id = "savings_rate_${c.month}"
        return listOf(
            when {
                rate < 0 -> Insight(
                    id, Severity.ALERT, "Spending more than you earn",
                    "Expenses of ${fmt(spent)} exceed this month's income of ${fmt(income)} by ${fmt(spent - income)}.$lastNote",
                    null, 95,
                )
                rate < SAVINGS_GUIDELINE -> Insight(
                    id, Severity.TIP, "Savings rate ${pct(rate)}",
                    "You've kept ${fmt(income - spent)} of ${fmt(income)} this month. Aim for at least ${pct(SAVINGS_GUIDELINE)} " +
                        "(${fmt((income * SAVINGS_GUIDELINE).roundToLong())}).$lastNote",
                    null, 40,
                )
                rate >= SAVINGS_GREAT -> Insight(
                    id, Severity.GOOD, "Saving ${pct(rate)} of income",
                    "You've kept ${fmt(income - spent)} of ${fmt(income)} so far this month.$lastNote",
                    null, 20,
                )
                else -> return emptyList()
            },
        )
    }

    private fun noIncome(c: Context): List<Insight> {
        if (c.today.dayOfMonth < NO_INCOME_FROM_DAY) return emptyList()
        if (c.income(c.month) > 0 || c.income(c.month.minusMonths(1)) == 0L) return emptyList()
        return listOf(
            Insight(
                "no_income_${c.month}", Severity.TIP, "No income recorded this month",
                "Last month you received ${fmt(c.income(c.month.minusMonths(1)))}. If your salary has arrived, scan SMS " +
                    "or add it so your savings figures stay accurate.",
                null, 30,
            ),
        )
    }

    /**
     * Monthly surplus averaged over the last complete budgeting months that have data: earned income less spending,
     * where money put into investments counts as saved, not spent. The Goals screen uses the same rule.
     */
    private fun recentSurplus(c: Context): Paise? {
        val months = (1..GOAL_SURPLUS_MONTHS).map { c.month.minusMonths(it.toLong()) }
            .filter { c.income(it) != 0L || c.spent(it) != 0L }
        if (months.isEmpty()) return null
        return months.sumOf { c.income(it) - (c.spent(it) - c.invested(it)) } / months.size
    }

    private fun goalRules(c: Context): List<Insight> {
        val today = real(c.today)
        val surplus = recentSurplus(c)
        return GoalTracker.assess(c.input.goals, surplus, today, deadlineOf = { real(it.deadline) })
            .map { (g, a) -> goalInsight(c, g, a.plan, surplus, a.capacity, today, a.standing) }
    }

    private fun goalInsight(
        c: Context,
        g: GoalInput,
        plan: GoalPlan,
        surplus: Paise?,
        capacity: Paise?,
        today: LocalDate,
        standing: GoalStanding,
    ): Insight {
        // Unique per goal (names can repeat, and non-Latin names have no slug) and per standing, so dismissing
        // "on track" doesn't also hide a later "off track".
        val key = g.id?.toString() ?: slug(g.name).ifEmpty { Integer.toHexString(g.name.hashCode()) }
        val id = "goal_${key}_${standing.name.lowercase()}"
        val toSave = plan.target
        val deadline = real(g.deadline)
        val loanNote = if (g.loanAmount > 0) {
            val emi = GoalLoan.emi(g.loanAmount, g.loanRatePct, g.loanTenureMonths)
            " plus a ${fmt(g.loanAmount)} loan (EMI ${fmt(emi)} for ${g.loanTenureMonths} months)"
        } else ""
        val metrics = buildList {
            add(m("Target", compact(g.target)))
            add(m("Saved", compact(g.saved)))
            if (g.loanAmount > 0) add(m("Loan", compact(g.loanAmount)))
            add(m("To save", compact(toSave)))
            add(m("Months left", "${plan.monthsLeft}"))
            add(m("Needed/month", compact(plan.requiredMonthly)))
            add(m("Your surplus/month", surplus?.let { compact(it) } ?: "-"))
            add(m("Expected return", "${"%.1f".format(g.expectedReturnPct)}%"))
            if (g.inflationPct > 0) add(m("Price rise", "${"%.1f".format(g.inflationPct)}%/yr"))
            if (!plan.reached && !plan.overdue && plan.monthsLeft > 0) {
                // Returns vary: the plan's amount makes it about half the time, so show what makes it 4 times in 5.
                val sim = GoalSimulation.forGoal(g.copy(deadline = real(g.deadline)), plan)
                add(m("For 80% confidence", compact(sim.monthlyForConfidence) + "/mo"))
            }
        }
        val chart = goalProjection(g, plan, today)
        val action = InsightAction("Open goal", InsightRoute.GOALS, g.id?.toString())
        fun insight(sev: Severity, title: String, msg: String, detail: String, score: Int) =
            Insight(id, sev, title, msg, null, score, detail = detail, metrics = metrics, chart = chart, action = action)

        val dateText = day(g.deadline)
        val basics = "The goal is ${fmt(g.target)} by $dateText$loanNote, so ${fmt(toSave)} has to come from savings. " +
            "You've saved ${fmt(g.saved)} and expect ${"%.1f".format(g.expectedReturnPct)}% a year on it."
        return when {
            plan.reached -> insight(
                Severity.GOOD, if (g.loanAmount > 0) "${g.name}: savings target reached" else "Goal reached: ${g.name}",
                "You've saved ${fmt(g.saved)}, enough for the ${fmt(toSave)} you planned to save.",
                basics, 30,
            )
            plan.dueNow -> insight(
                Severity.WARNING, "${g.name}: ${fmt(plan.gap)} still needed",
                "The date is ${dateText} and no month-end saving is left before it, so the rest is needed in one go. " +
                    "Add what you've saved, or move the date.",
                basics, 70,
            )
            plan.overdue -> {
                val remaining = toSave - g.saved
                val pace = capacity?.takeIf { it > 0 }
                val newDate = pace?.let { Goals.monthsToReach(toSave, g.saved, it, g.expectedReturnPct) }?.let { today.plusMonths(it.toLong()) }
                insight(
                    Severity.ALERT, "${g.name}: the date has passed",
                    "${fmt(remaining)} is still to save. " +
                        (newDate?.let { "At your usual surplus you'd finish around ${realDay(it)} — update the date." } ?: "Set a new date to get a fresh plan."),
                    basics, 80,
                )
            }
            plan.requiredMonthly <= 0 -> insight(
                Severity.GOOD, "${g.name} is on track",
                "What you've saved should grow to ${fmt(toSave)} by $dateText$loanNote without adding more.",
                "$basics At ${"%.1f".format(g.expectedReturnPct)}% a year your savings reach the target on their own.", 15,
            )
            surplus == null -> insight(
                Severity.TIP, "${g.name}: save ${fmt(plan.requiredMonthly)} a month",
                "Saving ${fmt(plan.requiredMonthly)} a month gets you ${fmt(toSave)} by $dateText$loanNote.",
                "$basics Kosh needs a full month of income and spending to judge whether this fits your budget.", 25,
            )
            capacity != null && capacity > 0 && plan.requiredMonthly <= capacity * GOAL_COMFORT_SHARE -> insight(
                Severity.GOOD, "${g.name} is on track",
                "At ${fmt(plan.requiredMonthly)} a month you'll reach ${fmt(toSave)} by $dateText$loanNote.",
                "$basics That's ${pct(plan.requiredMonthly.toDouble() / capacity)} of what's left of your usual monthly surplus of " +
                    "${fmt(surplus)} after earlier goals.", 15,
            )
            capacity != null && capacity > 0 && plan.requiredMonthly <= capacity -> insight(
                Severity.TIP, "${g.name} is tight",
                "Needs ${fmt(plan.requiredMonthly)} a month to save ${fmt(toSave)} by $dateText$loanNote — about " +
                    "${pct(plan.requiredMonthly.toDouble() / capacity)} of your usual surplus of ${fmt(capacity)}.",
                "$basics It fits, but leaves little room for surprises. A later date or a bigger loan would ease it.", 40,
            )
            else -> {
                val pace = (capacity ?: 0).coerceAtLeast(0)
                val options = mutableListOf("save ${fmt(plan.requiredMonthly)} a month")
                if (pace > 0) {
                    Goals.monthsToReach(toSave, g.saved, pace, g.expectedReturnPct)?.let {
                        options += "move the date to ${realDay(today.plusMonths(it.toLong()))} at ${fmt(pace)} a month"
                    }
                    val reachable = Goals.futureValue(g.saved, pace, plan.monthsLeft, g.expectedReturnPct)
                    val extraLoan = (toSave - reachable).coerceAtLeast(0)
                    if (extraLoan > 0) {
                        val loan = g.loanAmount + extraLoan
                        options += "take a ${fmt(loan)} loan (EMI ${fmt(GoalLoan.emi(loan, g.loanRatePct, g.loanTenureMonths))} for ${g.loanTenureMonths} months)"
                    }
                }
                val usual = if (pace > 0) "Your usual surplus after earlier goals is ${fmt(pace)} a month."
                else "Your recent months leave no surplus after earlier goals."
                insight(
                    Severity.WARNING, "${g.name} is off track",
                    "$usual To reach it by $dateText, ${options.joinToString(", or ")}.",
                    basics, 65,
                )
            }
        }
    }

    /** Saved amount month by month if the user saves the required amount, up to the deadline. */
    private fun goalProjection(g: GoalInput, plan: GoalPlan, today: LocalDate): InsightChart? {
        if (plan.reached || plan.overdue || plan.monthsLeft <= 0) return null
        val step = maxOf(1, plan.monthsLeft / 6)
        val months = (0..plan.monthsLeft step step).toMutableList().apply { if (last() != plan.monthsLeft) add(plan.monthsLeft) }
        val values = months.map { Goals.futureValue(g.saved, plan.requiredMonthly, it, g.expectedReturnPct).coerceAtMost(plan.target) }
        val labels = months.map { today.plusMonths(it.toLong()).let { d -> d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) } }
        return InsightChart(InsightChart.Kind.LINE, labels, values, reference = plan.target, caption = "Savings if you stick to the plan")
    }

    private fun emergencyFund(c: Context): List<Insight> {
        val avg = c.avgMonthlyExpense
        if (avg <= 0 || c.input.accounts.isEmpty()) return emptyList()
        val months = c.liquid.toDouble() / avg
        val shortfall = (avg * EMERGENCY_TARGET_MONTHS).roundToLong() - c.liquid
        val text = "Your bank and cash balances of ${fmt(c.liquid)} cover ${"%.1f".format(months)} months of expenses " +
            "(about ${fmt(avg)} a month)."
        return when {
            months < EMERGENCY_MIN_MONTHS -> listOf(
                Insight("emergency_fund", Severity.WARNING, "Thin emergency cushion", "$text Build up ${fmt(shortfall)} more to reach 6 months.", null, 55),
            )
            months < EMERGENCY_TARGET_MONTHS -> listOf(
                Insight("emergency_fund", Severity.TIP, "Grow your emergency fund", "$text ${fmt(shortfall)} more reaches the 6-month mark.", null, 25),
            )
            else -> emptyList()
        }
    }

    private fun idleCash(c: Context): List<Insight> {
        val avg = c.avgMonthlyExpense
        if (avg <= 0) return emptyList()
        // Money already put aside for goals isn't idle, even while it sits in the bank.
        val forGoals = c.input.goals.sumOf { it.saved.coerceAtLeast(0) }
        val excess = c.liquid - forGoals - (avg * EMERGENCY_TARGET_MONTHS).roundToLong()
        if (excess < IDLE_CASH_MIN_EXCESS) return emptyList()
        return listOf(
            Insight(
                "idle_cash", Severity.TIP, "Idle cash could earn more",
                "About ${fmt(excess)} sits above a 6-month emergency fund. Consider an FD, NPS or other investments for it.",
                null, 35,
            ),
        )
    }

    private fun cardUtilisation(c: Context): List<Insight> = c.input.accounts
        .filter { it.type == AccountType.CREDIT_CARD && (it.creditLimit ?: 0) > 0 }
        .mapNotNull { a ->
            val used = abs(a.balance).toDouble() / a.creditLimit!!
            val msg = "You've used ${fmt(abs(a.balance))} of your ${fmt(a.creditLimit)} limit (${pct(used)}). " +
                "Keeping it under 30% helps your credit score."
            when {
                used >= CARD_UTILISATION_ALERT -> Insight("card_util_${a.id}", Severity.ALERT, "${a.name} nearly maxed out", msg, null, 75)
                used > CARD_UTILISATION_WARN -> Insight("card_util_${a.id}", Severity.WARNING, "High usage on ${a.name}", msg, null, 45)
                else -> null
            }
        }

    private fun billsDue(c: Context): List<Insight> = c.input.upcomingBills.filter { !it.paid }.mapNotNull { b ->
        val days = ChronoUnit.DAYS.between(c.today, b.dueDate)
        val id = "bill_${slug(b.cardName)}_${b.dueDate}"
        when {
            days < 0 -> Insight(
                id, Severity.ALERT, "${b.cardName} bill overdue",
                "${fmt(b.amount)} was due on ${day(b.dueDate)}. Pay it now to limit late fees and interest.", null, 100,
            )
            days <= BILL_ALERT_DAYS -> Insight(
                id, Severity.ALERT, "${b.cardName} bill due ${if (days == 0L) "today" else "in $days day${if (days == 1L) "" else "s"}"}",
                "Pay ${fmt(b.amount)} by ${day(b.dueDate)} to avoid late fees.", null, 85 + (3 - days.toInt()),
            )
            days <= BILL_NOTICE_DAYS -> Insight(
                id, Severity.TIP, "${b.cardName} bill due on ${day(b.dueDate)}", "${fmt(b.amount)} is due in $days days.", null, 30,
            )
            else -> null
        }
    }

    private fun fdMaturity(c: Context): List<Insight> = c.input.investments
        .filter { it.type == InvestmentType.FD && it.maturityDate != null }
        .mapNotNull { fd ->
            val days = ChronoUnit.DAYS.between(c.today, fd.maturityDate)
            when {
                days in 0..FD_MATURITY_DAYS -> Insight(
                    "fd_maturing_${slug(fd.name)}", Severity.TIP, "${fd.name} matures ${if (days == 0L) "today" else "in $days days"}",
                    "It will pay about ${fmt(fd.maturityValue ?: fd.value)}. Decide whether to renew, reinvest or move it before ${day(fd.maturityDate!!)}.",
                    null, 50,
                )
                days < 0 && days >= -FD_MATURITY_DAYS -> Insight(
                    "fd_matured_${slug(fd.name)}", Severity.TIP, "${fd.name} has matured",
                    "${fmt(fd.maturityValue ?: fd.value)} matured on ${day(fd.maturityDate!!)}. Make sure it's been renewed or reinvested.", null, 45,
                )
                else -> null
            }
        }

    private fun subscriptions(c: Context): List<Insight> {
        val since = c.today.minusDays(120)
        return c.expenses.filter { !it.date.isBefore(since) && !it.merchant.isNullOrBlank() && it.category !in NON_DISCRETIONARY }
            .groupBy { it.merchant!!.trim().lowercase() }
            .mapNotNull { (_, txns) ->
                if (txns.size < SUBSCRIPTION_MIN_OCCURRENCES) return@mapNotNull null
                val sorted = txns.sortedBy { it.date }
                val amounts = sorted.map { it.amount }
                val mean = amounts.average()
                if (amounts.any { abs(it - mean) > mean * SUBSCRIPTION_AMOUNT_TOLERANCE }) return@mapNotNull null
                val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
                if (gaps.any { it !in SUBSCRIPTION_INTERVAL_DAYS }) return@mapNotNull null
                val name = sorted.last().merchant!!.trim()
                val yearly = (mean * 12).roundToLong()
                Insight(
                    "subscription_${slug(name)}", Severity.TIP, "Recurring payment: $name",
                    "About ${fmt(mean.roundToLong())} goes to $name every month, roughly ${fmt(yearly)} a year. Still using it?",
                    sorted.last().category, 20 + (yearly / 100_000).toInt().coerceAtMost(30),
                )
            }
    }

    private fun frequentSmallSpends(c: Context): List<Insight> =
        c.expensesIn(c.month).filter { it.amount <= SMALL_SPEND_MAX }.groupBy { it.category }
            .mapNotNull { (cat, txns) ->
                if (txns.size < SMALL_SPEND_MIN_COUNT) return@mapNotNull null
                val total = txns.sumOf { it.amount }
                Insight(
                    "small_spends_${slug(cat)}_${c.month}", Severity.TIP, "Small ${c.label(cat)} spends add up",
                    "${txns.size} payments under ${fmt(SMALL_SPEND_MAX)} on ${c.label(cat)} this month total ${fmt(total)}.",
                    cat, 25 + txns.size,
                )
            }

    private fun weekendSpending(c: Context): List<Insight> {
        val from = c.today.minusDays(30)
        val recent = c.expenses.filter { it.date.isAfter(from) && !it.date.isAfter(c.today) }
        val days = (1..30).map { c.today.minusDays(it.toLong() - 1) }
        val weekendDays = days.count { isWeekendDay(it) }
        val weekdayDays = days.size - weekendDays
        if (weekendDays == 0 || weekdayDays == 0) return emptyList()
        val isWeekend = { t: InsightTxn -> isWeekendDay(t.date) }
        val weekendTotal = recent.filter(isWeekend).sumOf { it.amount }
        val weekdayTotal = recent.filterNot(isWeekend).sumOf { it.amount }
        val weekendDaily = weekendTotal.toDouble() / weekendDays
        val weekdayDaily = weekdayTotal.toDouble() / weekdayDays
        if (weekendTotal < WEEKEND_MIN_TOTAL || weekendDaily < weekdayDaily * WEEKEND_FACTOR) return emptyList()
        return listOf(
            Insight(
                "weekend_spending", Severity.TIP, "Weekends cost more",
                "Over the last 30 days you spent about ${fmt(weekendDaily.roundToLong())} per weekend day vs " +
                    "${fmt(weekdayDaily.roundToLong())} on weekdays.",
                null, 22,
            ),
        )
    }

    private fun largeTransactions(c: Context): List<Insight> {
        val history = c.expenses.filter { it.date.isAfter(c.today.minusDays(90)) }.map { it.amount }.sorted()
        if (history.size < 5) return emptyList()
        val median = history[history.size / 2]
        return c.expenses.filter { it.date.isAfter(c.today.minusDays(7)) && !it.date.isAfter(c.today) }
            .filter { it.amount >= LARGE_TXN_MIN && it.amount > median * LARGE_TXN_FACTOR }
            .map { t ->
                Insight(
                    "large_txn_${t.date}_${t.amount}", Severity.WARNING, "Unusually large payment",
                    "${fmt(t.amount)}${t.merchant?.let { " to $it" } ?: ""} on ${day(t.date)} is far above your typical spend of ${fmt(median)}. " +
                        "Check it's expected.",
                    t.category, 50,
                )
            }
    }

    private fun merchantConcentration(c: Context): List<Insight> {
        val month = c.expensesIn(c.month).filter { !it.merchant.isNullOrBlank() }
        val total = c.spent(c.month)
        if (total < CONCENTRATION_MIN_TOTAL) return emptyList()
        val top = month.groupBy { it.merchant!!.trim() }.mapValues { (_, t) -> t.sumOf { it.amount } }.maxByOrNull { it.value }
            ?: return emptyList()
        val share = top.value.toDouble() / total
        if (share < CONCENTRATION_SHARE) return emptyList()
        return listOf(
            Insight(
                "top_merchant_${c.month}", Severity.TIP, "${top.key} is your biggest spend",
                "${fmt(top.value)} (${pct(share)} of this month's spending) went to ${top.key}.", null, 18,
            ),
        )
    }

    private fun spendingDown(c: Context): List<Insight> {
        if (c.today.dayOfMonth < SPENDING_DOWN_FROM_DAY) return emptyList()
        val last = c.spent(c.month.minusMonths(1))
        if (last <= 0) return emptyList()
        val projected = Budgets.project(Long.MAX_VALUE, c.spent(c.month), c.today).projected
        if (projected > last * 0.9) return emptyList()
        return listOf(
            Insight(
                "spending_down_${c.month}", Severity.GOOD, "Spending is down",
                "You're on course for about ${fmt(projected)} this month, ${fmt(last - projected)} less than last month's ${fmt(last)}.",
                null, 12,
            ),
        )
    }

    private fun startInvesting(c: Context): List<Insight> {
        if (c.input.investments.isNotEmpty()) return emptyList()
        val last = c.month.minusMonths(1)
        val surplus = c.income(last) - c.spent(last)
        if (surplus <= 0) return emptyList()
        return listOf(
            Insight(
                "start_investing", Severity.TIP, "Put your surplus to work",
                "You had ${fmt(surplus)} left over last month but no tracked investments. Add your FDs, EPF or NPS, " +
                    "or start a monthly SIP.",
                null, 28,
            ),
        )
    }

    // ------------------------------------------------------------------ richer detail

    private fun compact(p: Paise) = Money.compact(p)
    private fun m(label: String, value: String) = InsightMetric(label, value)

    private fun monthlyBars(c: Context, category: String? = null, caption: String? = null) = InsightChart(
        InsightChart.Kind.BARS,
        c.lastMonths().map { c.monthLabel(it) },
        c.lastMonths().map { c.spent(it, category) },
        caption = caption,
    )

    private fun changeText(now: Paise, before: Paise): String =
        if (before <= 0) "new" else ((now.toDouble() / before - 1) * 100).roundToInt().let { if (it >= 0) "+$it%" else "$it%" }

    /** Adds detail text, metric chips, a small chart and an action to the insights produced by the rules. */
    private fun enrich(c: Context, i: Insight): Insight {
        if (i.detail != null || i.metrics.isNotEmpty()) return i
        val id = i.id
        return when {
            id.startsWith("budget_") -> {
                val b = c.input.budgets.firstOrNull { id == "budget_${slug(c.label(it.category))}_${c.month}" } ?: return i
                val spent = c.spent(c.month, b.category)
                val p = Budgets.project(b.monthlyLimit, spent, c.today)
                i.copy(
                    detail = "${c.label(b.category)} spending is ${fmt(spent)} after ${c.today.dayOfMonth} days, " +
                        "${pct(p.usedFraction)} of the ${fmt(b.monthlyLimit)} budget. At the current pace the month ends near " +
                        "${fmt(p.projected)}. With ${c.daysLeft} days left, about ${fmt(maxOf(0, p.dailyAllowance))} a day keeps you within it.",
                    metrics = listOf(m("Spent", compact(spent)), m("Budget", compact(b.monthlyLimit)), m("Projected", compact(p.projected)),
                        m("Per day left", compact(maxOf(0, p.dailyAllowance)))),
                    chart = InsightChart(InsightChart.Kind.LINE, (1..c.today.dayOfMonth).map { "$it" }, c.cumulativeThisMonth(b.category),
                        reference = b.monthlyLimit, caption = "Spent so far vs budget"),
                    action = InsightAction("View budgets", InsightRoute.BUDGETS),
                )
            }
            id.startsWith("spike_") && i.category != null -> {
                val cat = i.category
                val now = c.spent(c.month, cat)
                val avg = (1..3).sumOf { c.spent(c.month.minusMonths(it.toLong()), cat) } / 3
                val count = c.expensesIn(c.month, cat).size
                i.copy(
                    detail = "${c.label(cat)} is at ${fmt(now)} across $count payments this month, against a 3-month average of " +
                        "${fmt(avg)}. The bars show the last six months, so you can see whether this is a one-off or a trend.",
                    metrics = listOf(m("This month", compact(now)), m("3-mo avg", compact(avg)), m("Change", changeText(now, avg)),
                        m("Payments", "$count")),
                    chart = monthlyBars(c, cat, "${c.label(cat)}, last 6 months"),
                    action = InsightAction("See ${c.label(cat)}", InsightRoute.CATEGORY, cat),
                )
            }
            id.startsWith("savings_rate_") -> {
                val income = c.income(c.month)
                val spent = c.spent(c.month)
                val saved = c.lastMonths().map { c.income(it) - c.spent(it) }
                i.copy(
                    detail = "Income this month is ${fmt(income)} and spending ${fmt(spent)}. A savings rate of 20% or more is a " +
                        "healthy target; it builds an emergency fund and frees money to invest.",
                    metrics = listOf(m("Income", compact(income)), m("Spent", compact(spent)), m("Saved", compact(income - spent))),
                    chart = InsightChart(InsightChart.Kind.BARS, c.lastMonths().map { c.monthLabel(it) }, saved, caption = "Saved per month"),
                    action = InsightAction("See activity", InsightRoute.ACTIVITY),
                )
            }
            id == "emergency_fund" || id == "idle_cash" -> {
                val avg = c.avgMonthlyExpense
                i.copy(
                    detail = "An emergency fund of six months' expenses protects you from job loss or medical bills without borrowing. " +
                        "This counts bank and cash balances only, not investments or credit cards.",
                    metrics = listOf(m("Bank & cash", compact(c.liquid)), m("Avg monthly spend", compact(avg)),
                        m("Months covered", if (avg > 0) "%.1f".format(c.liquid.toDouble() / avg) else "-"),
                        m("6-month target", compact(avg * 6))),
                    action = if (id == "idle_cash") InsightAction("Open wealth", InsightRoute.WEALTH) else InsightAction("Open accounts", InsightRoute.ACCOUNTS),
                )
            }
            id.startsWith("card_util_") -> i.copy(
                detail = "Card utilisation is the share of your credit limit in use. Lenders and credit bureaus like it below 30%; " +
                    "paying the bill early in the cycle lowers it.",
                action = InsightAction("Open accounts", InsightRoute.ACCOUNTS),
            )
            id.startsWith("bill_") -> i.copy(
                detail = "Paying the full amount by the due date avoids late fees and interest of up to 3–4% a month on the unpaid balance.",
                action = InsightAction("Open card bills", InsightRoute.BILLS),
            )
            id.startsWith("fd_") -> i.copy(action = InsightAction("Open wealth", InsightRoute.WEALTH))
            id.startsWith("subscription_") -> {
                val name = i.title.removePrefix("Recurring payment: ")
                val payments = c.expenses.filter { it.merchant?.trim().equals(name, ignoreCase = true) }.sortedBy { it.date }.takeLast(6)
                val next = payments.lastOrNull()?.date?.plusMonths(1)
                i.copy(
                    detail = "We spotted ${payments.size} similar payments to $name about a month apart. Cancelling services you no " +
                        "longer use is the easiest saving there is.",
                    metrics = listOfNotNull(
                        m("Monthly", compact(payments.map { it.amount }.average().roundToLong())),
                        m("Yearly", compact(payments.map { it.amount }.average().roundToLong() * 12)),
                        next?.let { m("Next expected", day(it)) },
                    ),
                    chart = InsightChart(InsightChart.Kind.BARS, payments.map { c.monthLabel(YearMonth.from(it.date)) }, payments.map { it.amount }),
                    action = i.category?.let { InsightAction("See ${c.label(it)}", InsightRoute.CATEGORY, it) },
                )
            }
            id.startsWith("small_spends_") && i.category != null -> {
                val txns = c.expensesIn(c.month, i.category).filter { it.amount <= SMALL_SPEND_MAX }
                i.copy(
                    detail = "Individually small, these payments add up over a month. Setting a weekly cap or a category budget helps.",
                    metrics = listOf(m("Payments", "${txns.size}"), m("Total", compact(txns.sumOf { it.amount })),
                        m("Average", compact(if (txns.isEmpty()) 0 else txns.sumOf { it.amount } / txns.size))),
                    action = InsightAction("See ${c.label(i.category)}", InsightRoute.CATEGORY, i.category),
                )
            }
            id == "weekend_spending" -> {
                val from = c.today.minusDays(30)
                val recent = c.expenses.filter { it.date.isAfter(from) && !it.date.isAfter(c.today) }
                val byDow = DayOfWeek.entries.map { d -> recent.filter { real(it.date).dayOfWeek == d }.sumOf { it.amount } }
                i.copy(
                    detail = "Spending per day of the week over the last 30 days. Planning weekend outings in advance is a simple way to trim it.",
                    chart = InsightChart(InsightChart.Kind.BARS, listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), byDow),
                    action = InsightAction("See activity", InsightRoute.ACTIVITY),
                )
            }
            id.startsWith("large_txn_") || id.startsWith("top_merchant_") || id.startsWith("no_income_") ->
                i.copy(action = InsightAction("See activity", InsightRoute.ACTIVITY))
            id.startsWith("spending_down_") -> i.copy(chart = monthlyBars(c, caption = "Spending, last 6 months"))
            id == "start_investing" -> i.copy(action = InsightAction("Add investments", InsightRoute.WEALTH))
            else -> i
        }
    }

    // ------------------------------------------------------------------ overview rules

    /** A month score from 0 to 100 combining savings rate, budgets and bills. */
    private fun monthScore(c: Context): Int {
        val income = c.income(c.month)
        val spent = c.spent(c.month)
        var score = 50.0
        if (income > 0) score += ((income - spent).toDouble() / income).coerceIn(-0.5, 0.5) * 80
        val budgets = c.input.budgets
        if (budgets.isNotEmpty()) {
            val over = budgets.count { Budgets.project(it.monthlyLimit, c.spent(c.month, it.category), c.today).willExceed }
            score += 10 - 20.0 * over / budgets.size
        }
        val overdue = c.input.upcomingBills.count { !it.paid && it.dueDate.isBefore(c.today) }
        score -= overdue * 15
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun monthlySummary(c: Context): List<Insight> {
        val income = c.income(c.month)
        val spent = c.spent(c.month)
        if (income == 0L && spent == 0L) return emptyList()
        val top = c.expensesIn(c.month).groupBy { it.category }.mapValues { (_, t) -> t.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }.take(3)
        val projected = Budgets.project(Long.MAX_VALUE, spent, c.today).projected
        val monthName = periodLabel(c.month).month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        val share = if (income > 0) " (${pct(spent.toDouble() / income)} of income)" else ""
        val topText = if (top.isEmpty()) "" else " Biggest categories: " + top.joinToString(", ") { "${c.label(it.key)} ${fmt(it.value)}" } + "."
        val score = monthScore(c)
        val verdict = when {
            score >= 75 -> "A strong month so far"
            score >= 50 -> "On a reasonable track"
            else -> "This month needs attention"
        }
        return listOf(
            Insight(
                "${Insight.SUMMARY_PREFIX}${c.month}", Severity.TIP, verdict,
                "In $monthName you've earned ${fmt(income)} and spent ${fmt(spent)}$share.",
                null, 0,
                detail = "In $monthName so far you've earned ${fmt(income)} and spent ${fmt(spent)}$share.$topText " +
                    "With ${c.daysLeft} days to go, spending is heading for about ${fmt(projected)}.",
                metrics = listOf(
                    m("Score", "$score/100"),
                    m("Income", compact(income)),
                    m("Spent", compact(spent)),
                    m("Saved", compact(income - spent)),
                    m("Savings rate", if (income > 0) pct((income - spent).toDouble() / income) else "-"),
                ),
                chart = monthlyBars(c, caption = "Spending, last 6 months"),
                action = InsightAction("See activity", InsightRoute.ACTIVITY),
            ),
        )
    }

    private fun topMerchants(c: Context): List<Insight> {
        val byMerchant = c.expensesIn(c.month).filter { !it.merchant.isNullOrBlank() }
            .groupBy { it.merchant!!.trim() }.mapValues { (_, t) -> t.sumOf { it.amount } }
        val total = c.spent(c.month)
        if (byMerchant.size < 3 || total < Money.fromRupees(2_000.0)) return emptyList()
        val top = byMerchant.entries.sortedByDescending { it.value }.take(5)
        val topShare = top.sumOf { it.value }.toDouble() / total
        return listOf(
            Insight(
                "merchants_top5_${c.month}", Severity.TIP, "Where your money went",
                "Your top ${top.size} merchants took ${pct(topShare)} of this month's spending; ${top.first().key} leads with ${fmt(top.first().value)}.",
                null, 16,
                detail = "These are the merchants you paid most this month. Concentrated spending is often the easiest to plan or trim.",
                metrics = top.map { m(it.key, "${compact(it.value)} · ${pct(it.value.toDouble() / total)}") },
                chart = InsightChart(InsightChart.Kind.BARS, top.map { it.key.take(8) }, top.map { it.value }),
                action = InsightAction("See activity", InsightRoute.ACTIVITY),
            ),
        )
    }

    private fun categoryMovers(c: Context): List<Insight> {
        if (c.today.dayOfMonth < 5) return emptyList()
        val last = c.month.minusMonths(1)
        val cutoff = minOf(c.today.dayOfMonth, last.lengthOfMonth())
        val lastToDate = c.expensesIn(last).filter { it.date.dayOfMonth <= cutoff }.groupBy { it.category }
            .mapValues { (_, t) -> t.sumOf { it.amount } }
        val now = c.expensesIn(c.month).groupBy { it.category }.mapValues { (_, t) -> t.sumOf { it.amount } }
        val deltas = (lastToDate.keys + now.keys).associateWith { (now[it] ?: 0L) - (lastToDate[it] ?: 0L) }
            .filter { abs(it.value) >= Money.fromRupees(500.0) }
        if (deltas.isEmpty() || lastToDate.isEmpty()) return emptyList()
        val up = deltas.maxByOrNull { it.value }?.takeIf { it.value > 0 }
        val down = deltas.minByOrNull { it.value }?.takeIf { it.value < 0 }
        if (up == null && down == null) return emptyList()
        val parts = listOfNotNull(
            up?.let { "${c.label(it.key)} is up ${fmt(it.value)}" },
            down?.let { "${c.label(it.key)} is down ${fmt(-it.value)}" },
        )
        return listOf(
            Insight(
                "movers_${c.month}", Severity.TIP, "Biggest changes vs last month",
                parts.joinToString("; ") + " compared with the same point last month.",
                up?.key, 17,
                detail = "This compares spending so far this month with the first $cutoff days of last month, category by category, " +
                    "so a half-finished month isn't compared with a full one.",
                metrics = deltas.entries.sortedByDescending { abs(it.value) }.take(4).map {
                    m(c.label(it.key), (if (it.value >= 0) "+" else "−") + compact(abs(it.value)))
                },
                action = up?.let { InsightAction("See ${c.label(it.key)}", InsightRoute.CATEGORY, it.key) },
            ),
        )
    }

    private fun cashFlowForecast(c: Context): List<Insight> {
        if (c.input.accounts.none { it.type != AccountType.CREDIT_CARD } || c.daysLeft <= 0) return emptyList()
        // Card spends leave the bank only when the bill is paid (counted below as bills due), so the daily
        // run-rate uses bank, cash and wallet spending only.
        fun bankSpent(m: YearMonth) = c.expensesIn(m).filter { !it.isCreditCard }.sumOf { it.amount }
        val spent = bankSpent(c.month)
        val dailyNow = if (c.today.dayOfMonth > 0) spent.toDouble() / c.today.dayOfMonth else 0.0
        val pastBank = (1..3).map { bankSpent(c.month.minusMonths(it.toLong())) }.filter { it > 0 }
        val dailyAvg = if (pastBank.isEmpty()) 0.0 else pastBank.average() / 30.0
        val daily = if (c.today.dayOfMonth >= 7 || dailyAvg == 0.0) dailyNow else dailyAvg
        val expectedIncome = if (c.income(c.month) == 0L) c.income(c.month.minusMonths(1)) else 0L
        val monthEnd = c.month.atEndOfMonth()
        val billsDue = c.input.upcomingBills.filter { !it.paid && !it.dueDate.isAfter(monthEnd) && !it.dueDate.isBefore(c.today) }
            .sumOf { it.amount }
        val path = (0..c.daysLeft).map { d -> (c.liquid - daily * d).roundToLong() }
        val endBalance = c.liquid + expectedIncome - (daily * c.daysLeft).roundToLong() - billsDue
        val severity = if (endBalance < 0) Severity.WARNING else Severity.TIP
        return listOf(
            Insight(
                "forecast_${c.month}", severity, "Month-end balance: about ${fmt(endBalance)}",
                "If you keep spending about ${fmt(daily.roundToLong())} a day, bank and cash should be near ${fmt(endBalance)} on ${day(monthEnd)}.",
                null, if (endBalance < 0) 58 else 14,
                detail = "Starting from ${fmt(c.liquid)} today, this assumes ${fmt(daily.roundToLong())} of spending per day for the " +
                    "${c.daysLeft} remaining days" +
                    (if (expectedIncome > 0) ", income of ${fmt(expectedIncome)} like last month" else "") +
                    (if (billsDue > 0) " and ${fmt(billsDue)} of card bills due before month end" else "") + ".",
                metrics = listOfNotNull(
                    m("Today", compact(c.liquid)),
                    m("Daily spend", compact(daily.roundToLong())),
                    if (expectedIncome > 0) m("Expected income", compact(expectedIncome)) else null,
                    if (billsDue > 0) m("Bills due", compact(billsDue)) else null,
                    m("Month end", compact(endBalance)),
                ),
                chart = InsightChart(InsightChart.Kind.LINE, (0..c.daysLeft).map { "${real(c.today.plusDays(it.toLong())).dayOfMonth}" }, path,
                    caption = "Projected balance before income and bills"),
                action = InsightAction("Open accounts", InsightRoute.ACCOUNTS),
            ),
        )
    }

    private fun recurringPayments(c: Context): List<Insight> {
        val since = c.today.minusDays(100)
        val recurring = c.expenses.filter { !it.date.isBefore(since) && !it.merchant.isNullOrBlank() }
            .groupBy { it.merchant!!.trim().lowercase() }
            .mapNotNull { (_, txns) ->
                if (txns.size < 2) return@mapNotNull null
                val sorted = txns.sortedBy { it.date }
                val mean = sorted.map { it.amount }.average()
                if (sorted.any { abs(it.amount - mean) > mean * SUBSCRIPTION_AMOUNT_TOLERANCE }) return@mapNotNull null
                val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
                if (gaps.any { it !in SUBSCRIPTION_INTERVAL_DAYS }) return@mapNotNull null
                Triple(sorted.last().merchant!!.trim(), mean.roundToLong(), sorted.last().date.plusMonths(1))
            }.sortedBy { it.third }
        if (recurring.size < 2) return emptyList()
        val monthly = recurring.sumOf { it.second }
        return listOf(
            Insight(
                "recurring_list", Severity.TIP, "${recurring.size} recurring payments",
                "About ${fmt(monthly)} goes out every month on repeat payments, roughly ${fmt(monthly * 12)} a year.",
                null, 19,
                detail = "These merchants were paid similar amounts about a month apart. The next expected date helps you keep enough in the account.",
                metrics = recurring.take(6).map { (name, amount, next) ->
                    m(name, "${compact(amount)} · ${day(next)}")
                },
                action = InsightAction("See activity", InsightRoute.ACTIVITY),
            ),
        )
    }

    private fun cardSpendVsBill(c: Context): List<Insight> = c.input.upcomingBills
        .filter { !it.paid && it.accountId != null }
        .mapNotNull { b ->
            val statement = b.statementDate ?: b.dueDate.minusDays(ASSUMED_STATEMENT_TO_DUE_DAYS)
            val newSpend = c.expenses.filter { it.accountId == b.accountId && it.date.isAfter(statement) && !it.date.isAfter(c.today) }
                .sumOf { it.amount }
            if (newSpend <= 0) return@mapNotNull null
            Insight(
                "card_vs_bill_${slug(b.cardName)}_${b.dueDate}", Severity.TIP, "${b.cardName}: bill plus new spends",
                "${fmt(b.amount)} is due on ${day(b.dueDate)}, and you've added ${fmt(newSpend)} of new spends since the statement that will appear on the next bill.",
                null, 21,
                metrics = listOf(m("Bill due", compact(b.amount)), m("New spends", compact(newSpend)), m("Total owed", compact(b.amount + newSpend))),
                action = InsightAction("Open card bills", InsightRoute.BILLS),
            )
        }
}
