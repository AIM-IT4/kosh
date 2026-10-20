package com.amitjha.kosh.finance

import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightEngineTest {
    private val today = LocalDate.of(2026, 4, 20) // a Monday
    private fun rs(x: Double) = Money.fromRupees(x)
    private fun exp(date: LocalDate, rupees: Double, cat: String = "Shopping", merchant: String? = null) =
        InsightTxn(date, rs(rupees), TxnType.EXPENSE, cat, merchant, 1)
    private fun inc(date: LocalDate, rupees: Double) = InsightTxn(date, rs(rupees), TxnType.INCOME, "Salary", "Employer", 1)
    private fun run(input: InsightInput) = InsightEngine.generate(input)
    private fun List<Insight>.ids() = map { it.id }
    private fun List<Insight>.has(prefix: String) = any { it.id.startsWith(prefix) }
    private fun List<Insight>.get(prefix: String) = first { it.id.startsWith(prefix) }

    /** Three earlier months of steady spending and salary, to give the averages something to work with. */
    private fun history(monthly: Double = 30_000.0, income: Double = 100_000.0, cat: String = "Shopping") = (1..3).flatMap { m ->
        val d = today.minusMonths(m.toLong()).withDayOfMonth(5)
        listOf(exp(d, monthly, cat), inc(d.withDayOfMonth(1), income))
    }

    @Test
    fun budgetExceededProjectedAndOnTrack() {
        val base = InsightInput(today, transactions = listOf(exp(today.withDayOfMonth(2), 25_000.0, "Food")))
        val exceeded = run(base.copy(budgets = listOf(BudgetInput("Food", rs(20_000.0)))))
        assertEquals(Severity.ALERT, exceeded.get("budget_food").severity)
        val projected = run(base.copy(budgets = listOf(BudgetInput("Food", rs(30_000.0)))))
        assertEquals(Severity.WARNING, projected.get("budget_food").severity)
        val fine = run(base.copy(budgets = listOf(BudgetInput(null, rs(60_000.0)))))
        assertEquals(Severity.GOOD, fine.get("budget_overall").severity)
        assertFalse(run(base.copy(budgets = listOf(BudgetInput("Food", rs(40_000.0))))).has("budget_food"))
    }

    @Test
    fun budgetAlertsUseTheBudgetScreensNetFigures() {
        // ₹25,000 on food, ₹8,000 of it refunded: the Budgets screen shows ₹17,000 against ₹20,000, so no alert.
        val base = InsightInput(today, transactions = listOf(exp(today.withDayOfMonth(2), 25_000.0, "Food")))
        val net = BudgetProjection(rs(20_000.0), rs(17_000.0), daysElapsed = 20, daysInMonth = 30)
        val insights = run(base.copy(budgets = listOf(BudgetInput("Food", rs(20_000.0), net))))
        assertFalse(insights.any { it.id.startsWith("budget_food") && it.severity == Severity.ALERT })
        val over = BudgetProjection(rs(20_000.0), rs(21_000.0), daysElapsed = 20, daysInMonth = 30)
        val alert = run(base.copy(budgets = listOf(BudgetInput("Food", rs(20_000.0), over)))).get("budget_food")
        assertEquals(Severity.ALERT, alert.severity)
        assertTrue(alert.message.contains(Money.format(rs(21_000.0), showPaise = false)))
    }

    @Test
    fun categorySpike() {
        val txns = history(10_000.0, cat = "Travel") + exp(today.withDayOfMonth(3), 20_000.0, "Travel")
        assertTrue(run(InsightInput(today, txns)).has("spike_travel"))
        val calm = history(10_000.0, cat = "Travel") + exp(today.withDayOfMonth(3), 12_000.0, "Travel")
        assertFalse(run(InsightInput(today, calm)).has("spike_travel"))
    }

    @Test
    fun savingsRateBands() {
        val salary = inc(today.withDayOfMonth(1), 100_000.0)
        assertEquals(Severity.ALERT, run(InsightInput(today, listOf(salary, exp(today, 120_000.0)))).get("savings_rate").severity)
        assertEquals(Severity.TIP, run(InsightInput(today, listOf(salary, exp(today, 90_000.0)))).get("savings_rate").severity)
        assertEquals(Severity.GOOD, run(InsightInput(today, listOf(salary, exp(today, 50_000.0)))).get("savings_rate").severity)
        assertFalse(run(InsightInput(today, listOf(salary, exp(today, 75_000.0)))).has("savings_rate"))
        assertFalse(run(InsightInput(today, listOf(exp(today, 75_000.0)))).has("savings_rate"))
        assertTrue(run(InsightInput(today, listOf(exp(today, 75_000.0)), monthlyIncomeHint = rs(80_000.0))).has("savings_rate"))
    }

    @Test
    fun noIncomeThisMonth() {
        val lastMonthSalary = inc(today.minusMonths(1).withDayOfMonth(1), 100_000.0)
        assertTrue(run(InsightInput(today, listOf(lastMonthSalary))).has("no_income"))
        assertFalse(run(InsightInput(today.withDayOfMonth(5), listOf(lastMonthSalary))).has("no_income"))
        assertFalse(run(InsightInput(today, listOf(lastMonthSalary, inc(today.withDayOfMonth(1), 1.0)))).has("no_income"))
    }

    @Test
    fun goals() {
        val savingHistory = history(30_000.0, 50_000.0) // saving 20,000 a month
        val onTrack = GoalInput("Bike", rs(100_000.0), rs(40_000.0), today.plusMonths(6))
        val offTrack = GoalInput("Car", rs(500_000.0), rs(0.0), today.plusMonths(6))
        val overdue = GoalInput("Trip", rs(100_000.0), rs(10_000.0), today.minusDays(5))
        val done = GoalInput("Phone", rs(50_000.0), rs(60_000.0), today.plusMonths(2))
        val out = run(InsightInput(today, savingHistory, goals = listOf(onTrack, offTrack, overdue, done)))
        assertEquals(Severity.GOOD, out.get("goal_bike").severity)
        assertEquals(Severity.WARNING, out.get("goal_car").severity)
        assertTrue(out.get("goal_car").message.contains("₹83,333"))
        assertEquals(Severity.ALERT, out.get("goal_trip").severity)
        assertEquals("Goal reached: Phone", out.get("goal_phone").title)
    }

    @Test
    fun emergencyFundAndIdleCash() {
        val txns = history(40_000.0)
        fun accounts(balance: Double) = listOf(AccountInput(1, "HDFC Savings", AccountType.BANK, rs(balance)))
        assertEquals(Severity.WARNING, run(InsightInput(today, txns, accounts = accounts(80_000.0))).get("emergency_fund").severity)
        assertEquals(Severity.TIP, run(InsightInput(today, txns, accounts = accounts(200_000.0))).get("emergency_fund").severity)
        val rich = run(InsightInput(today, txns, accounts = accounts(400_000.0)))
        assertFalse(rich.has("emergency_fund"))
        assertTrue(rich.get("idle_cash").message.contains("₹1,60,000"))
        assertFalse(run(InsightInput(today, txns, accounts = accounts(260_000.0))).has("idle_cash"))
    }

    @Test
    fun cardUtilisation() {
        fun card(used: Double) = AccountInput(7, "ICICI Amazon Pay", AccountType.CREDIT_CARD, rs(-used), rs(100_000.0))
        assertEquals(Severity.ALERT, run(InsightInput(today, accounts = listOf(card(80_000.0)))).get("card_util_7").severity)
        assertEquals(Severity.WARNING, run(InsightInput(today, accounts = listOf(card(40_000.0)))).get("card_util_7").severity)
        assertFalse(run(InsightInput(today, accounts = listOf(card(20_000.0)))).has("card_util"))
    }

    @Test
    fun bills() {
        val bills = listOf(
            BillInput("HDFC Regalia", rs(12_000.0), today.plusDays(2)),
            BillInput("Kotak 811", rs(3_000.0), today.minusDays(1)),
            BillInput("DBS", rs(1_000.0), today.plusDays(6)),
            BillInput("ICICI", rs(1_000.0), today.plusDays(20)),
            BillInput("Paid", rs(1_000.0), today.plusDays(1), paid = true),
        )
        val out = run(InsightInput(today, upcomingBills = bills))
        assertEquals("HDFC Regalia bill due in 2 days", out.get("bill_hdfc_regalia").title)
        assertEquals(Severity.ALERT, out.get("bill_kotak_811").severity)
        assertTrue(out.get("bill_kotak_811").title.contains("overdue"))
        assertEquals(Severity.TIP, out.get("bill_dbs").severity)
        assertFalse(out.has("bill_icici") || out.has("bill_paid"))
        assertEquals("bill_kotak_811_2026-04-19", out.first().id) // overdue ranks first
    }

    @Test
    fun fdMaturity() {
        val inv = listOf(
            InvestmentInput("SBI FD", InvestmentType.FD, rs(110_000.0), today.plusDays(10)),
            InvestmentInput("Old FD", InvestmentType.FD, rs(50_000.0), today.minusDays(5)),
            InvestmentInput("Long FD", InvestmentType.FD, rs(50_000.0), today.plusDays(200)),
        )
        val out = run(InsightInput(today, investments = inv))
        assertTrue(out.has("fd_maturing_sbi_fd"))
        assertTrue(out.has("fd_matured_old_fd"))
        assertFalse(out.has("fd_maturing_long_fd"))
    }

    @Test
    fun subscriptions() {
        val netflix = (0..3).map { exp(today.minusDays(it * 30L + 2), 649.0, "Entertainment", "Netflix") }
        val out = run(InsightInput(today, netflix))
        assertTrue(out.get("subscription_netflix").message.contains("₹7,788"))
        val irregular = listOf(5L, 12L, 70L).map { exp(today.minusDays(it), 649.0, "Entertainment", "Netflix") }
        assertFalse(run(InsightInput(today, irregular)).has("subscription"))
    }

    @Test
    fun frequentSmallSpends() {
        val swiggy = (1..12).map { exp(today.withDayOfMonth(it), 350.0, "Food", "Swiggy") }
        assertTrue(run(InsightInput(today, swiggy)).get("small_spends_food").message.contains("₹4,200"))
        assertFalse(run(InsightInput(today, swiggy.take(5))).has("small_spends"))
    }

    @Test
    fun weekendSpending() {
        val saturday = LocalDate.of(2026, 4, 18)
        val weekend = listOf(exp(saturday, 4_000.0), exp(saturday.plusDays(1), 3_000.0), exp(today, 500.0))
        assertTrue(run(InsightInput(today, weekend)).has("weekend_spending"))
        val balanced = weekend + (1..20).map { exp(LocalDate.of(2026, 4, 17).minusDays(it % 4L), 1_000.0) }
        assertFalse(run(InsightInput(today, balanced)).has("weekend_spending"))
    }

    @Test
    fun largeTransaction() {
        val typical = (10..60 step 10).map { exp(today.minusDays(it.toLong()), 1_000.0) }
        assertTrue(run(InsightInput(today, typical + exp(today.minusDays(2), 25_000.0, merchant = "Croma"))).has("large_txn"))
        assertFalse(run(InsightInput(today, typical + exp(today.minusDays(2), 2_500.0))).has("large_txn"))
    }

    @Test
    fun merchantConcentration() {
        val txns = listOf(exp(today.withDayOfMonth(2), 6_000.0, merchant = "Amazon"), exp(today.withDayOfMonth(3), 2_000.0, merchant = "Zara"))
        assertEquals("Amazon is your biggest spend", run(InsightInput(today, txns)).get("top_merchant").title)
        val spread = (1..5).map { exp(today.withDayOfMonth(it), 2_000.0, merchant = "M$it") }
        assertFalse(run(InsightInput(today, spread)).has("top_merchant"))
    }

    @Test
    fun spendingDownAndStartInvesting() {
        val last = today.minusMonths(1)
        val txns = listOf(exp(last.withDayOfMonth(3), 50_000.0), inc(last.withDayOfMonth(1), 100_000.0), exp(today.withDayOfMonth(2), 10_000.0))
        val out = run(InsightInput(today, txns))
        assertTrue(out.has("spending_down"))
        assertTrue(out.has("start_investing"))
        val withInv = run(InsightInput(today, txns, investments = listOf(InvestmentInput("EPF", InvestmentType.EPF, rs(1.0)))))
        assertFalse(withInv.has("start_investing"))
        assertFalse(run(InsightInput(today.withDayOfMonth(10), txns)).has("spending_down"))
    }

    @Test
    fun rankingIsDeterministicAndBySeverity() {
        val input = InsightInput(
            today,
            history(40_000.0) + exp(today.withDayOfMonth(2), 25_000.0, "Food"),
            budgets = listOf(BudgetInput("Food", rs(20_000.0))),
            accounts = listOf(AccountInput(1, "Bank", AccountType.BANK, rs(50_000.0))),
            upcomingBills = listOf(BillInput("DBS", rs(1_000.0), today.plusDays(6))),
        )
        val a = run(input)
        assertEquals(a.ids(), run(input).ids())
        assertEquals(a.map { it.severity }, a.map { it.severity }.sortedBy { it.ordinal })
        assertTrue(a.size >= 3)
    }

    @Test
    fun emptyInputGivesNoInsights() {
        assertTrue(run(InsightInput(today)).isEmpty())
    }
}
