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
)

data class BudgetInput(val category: String?, val monthlyLimit: Paise)
data class GoalInput(val name: String, val target: Paise, val saved: Paise, val deadline: LocalDate)
data class AccountInput(val id: Long, val name: String, val type: AccountType, val balance: Paise, val creditLimit: Paise? = null)
data class BillInput(val cardName: String, val amount: Paise, val dueDate: LocalDate, val paid: Boolean = false)
data class InvestmentInput(val name: String, val type: InvestmentType, val value: Paise, val maturityDate: LocalDate? = null)

data class InsightInput(
    val today: LocalDate,
    val transactions: List<InsightTxn> = emptyList(),
    val budgets: List<BudgetInput> = emptyList(),
    val goals: List<GoalInput> = emptyList(),
    val accounts: List<AccountInput> = emptyList(),
    val upcomingBills: List<BillInput> = emptyList(),
    val investments: List<InvestmentInput> = emptyList(),
    val monthlyIncomeHint: Paise? = null,
)

/** Severity, from most to least urgent. */
enum class Severity { ALERT, WARNING, TIP, GOOD }

data class Insight(
    /** Stable across runs for the same underlying situation, so the UI can dismiss it. */
    val id: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val category: String? = null,
    /** Higher is more important within a severity. */
    val score: Int = 0,
)

/** Offline rule engine: turns transactions, budgets, goals and balances into ranked insights. */
object InsightEngine {

    // Thresholds
    const val SPIKE_FACTOR = 1.5
    val SPIKE_MIN_BASE: Paise = Money.fromRupees(1_000.0)
    const val SAVINGS_GUIDELINE = 0.20
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

    fun generate(input: InsightInput): List<Insight> {
        val ctx = Context(input)
        val rules: List<(Context) -> List<Insight>> = listOf(
            ::budgetRules, ::categorySpikes, ::savingsRate, ::noIncome, ::goalRules, ::emergencyFund,
            ::idleCash, ::cardUtilisation, ::billsDue, ::fdMaturity, ::subscriptions, ::frequentSmallSpends,
            ::weekendSpending, ::largeTransactions, ::merchantConcentration, ::spendingDown, ::startInvesting,
        )
        return rules.flatMap { it(ctx) }
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
        fun income(m: YearMonth): Paise = incomes.filter { YearMonth.from(it.date) == m }.sumOf { it.amount }

        /** Average monthly expense over the three full months before this one (only months with data). */
        val avgMonthlyExpense: Paise by lazy {
            val months = (1..3).map { month.minusMonths(it.toLong()) }.map { spent(it) }.filter { it > 0 }
            if (months.isEmpty()) 0 else months.sum() / months.size
        }

        val liquid: Paise by lazy {
            input.accounts.filter { it.type != AccountType.CREDIT_CARD }.sumOf { maxOf(0, it.balance) }
        }
    }

    private fun fmt(p: Paise) = Money.format(p, showPaise = false)
    private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    // ------------------------------------------------------------------ rules

    private fun budgetRules(c: Context): List<Insight> = c.input.budgets.mapNotNull { b ->
        val spent = c.spent(c.month, b.category)
        val p = Budgets.project(b.monthlyLimit, spent, c.today)
        val label = b.category ?: "Overall"
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
            val past = (1..3).map { c.spent(c.month.minusMonths(it.toLong()), cat) }
            val avg = past.sum() / 3
            val now = c.spent(c.month, cat)
            if (avg >= SPIKE_MIN_BASE && now > avg * SPIKE_FACTOR) {
                Insight(
                    "spike_${slug(cat)}_${c.month}", Severity.WARNING, "$cat spending is up",
                    "You've spent ${fmt(now)} on $cat this month, ${((now.toDouble() / avg - 1) * 100).roundToInt()}% more than " +
                        "your 3-month average of ${fmt(avg)}.",
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

    private fun goalRules(c: Context): List<Insight> {
        val monthlySaving = (1..3).map { c.month.minusMonths(it.toLong()) }
            .map { c.income(it) - c.spent(it) }.filter { it != 0L }.let { if (it.isEmpty()) 0L else it.sum() / it.size }
        return c.input.goals.mapNotNull { g ->
            val plan = Goals.plan(g.target, g.saved, g.deadline, c.today)
            val id = "goal_${slug(g.name)}"
            when {
                plan.reached -> Insight(id, Severity.GOOD, "Goal reached: ${g.name}", "You've saved ${fmt(g.saved)} of ${fmt(g.target)}.", null, 30)
                plan.overdue -> Insight(
                    id, Severity.ALERT, "${g.name} deadline has passed",
                    "${fmt(g.target - g.saved)} is still needed. Set a new date or a monthly amount to finish it.", null, 80,
                )
                !plan.isOnTrack(monthlySaving) -> Insight(
                    id, Severity.WARNING, "${g.name} is off track",
                    "Save ${fmt(plan.requiredMonthly)} a month for the next ${plan.monthsLeft} months to reach ${fmt(g.target)}. " +
                        "You've been saving about ${fmt(maxOf(0, monthlySaving))} a month.",
                    null, 65,
                )
                else -> Insight(
                    id, Severity.GOOD, "${g.name} is on track",
                    "${pct(plan.progress)} done. ${fmt(plan.requiredMonthly)} a month keeps you on schedule.", null, 15,
                )
            }
        }
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
        val excess = c.liquid - (avg * EMERGENCY_TARGET_MONTHS).roundToLong()
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
                "${fmt(b.amount)} was due on ${b.dueDate}. Pay it now to limit late fees and interest.", null, 100,
            )
            days <= BILL_ALERT_DAYS -> Insight(
                id, Severity.ALERT, "${b.cardName} bill due ${if (days == 0L) "today" else "in $days day${if (days == 1L) "" else "s"}"}",
                "Pay ${fmt(b.amount)} by ${b.dueDate} to avoid late fees.", null, 85 + (3 - days.toInt()),
            )
            days <= BILL_NOTICE_DAYS -> Insight(
                id, Severity.TIP, "${b.cardName} bill due on ${b.dueDate}", "${fmt(b.amount)} is due in $days days.", null, 30,
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
                    "It will be worth about ${fmt(fd.value)}. Decide whether to renew, reinvest or move it before ${fd.maturityDate}.",
                    null, 50,
                )
                days < 0 && days >= -FD_MATURITY_DAYS -> Insight(
                    "fd_matured_${slug(fd.name)}", Severity.TIP, "${fd.name} has matured",
                    "${fmt(fd.value)} matured on ${fd.maturityDate}. Make sure it's been renewed or reinvested.", null, 45,
                )
                else -> null
            }
        }

    private fun subscriptions(c: Context): List<Insight> {
        val since = c.today.minusDays(120)
        return c.expenses.filter { !it.date.isBefore(since) && !it.merchant.isNullOrBlank() }
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
                    "small_spends_${slug(cat)}_${c.month}", Severity.TIP, "Small $cat spends add up",
                    "${txns.size} payments under ${fmt(SMALL_SPEND_MAX)} on $cat this month total ${fmt(total)}.",
                    cat, 25 + txns.size,
                )
            }

    private fun weekendSpending(c: Context): List<Insight> {
        val from = c.today.minusDays(30)
        val recent = c.expenses.filter { it.date.isAfter(from) && !it.date.isAfter(c.today) }
        val days = (1..30).map { c.today.minusDays(it.toLong() - 1) }
        val weekendDays = days.count { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        val weekdayDays = days.size - weekendDays
        if (weekendDays == 0 || weekdayDays == 0) return emptyList()
        val isWeekend = { t: InsightTxn -> t.date.dayOfWeek == DayOfWeek.SATURDAY || t.date.dayOfWeek == DayOfWeek.SUNDAY }
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
                    "${fmt(t.amount)}${t.merchant?.let { " to $it" } ?: ""} on ${t.date} is far above your typical spend of ${fmt(median)}. " +
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
}
