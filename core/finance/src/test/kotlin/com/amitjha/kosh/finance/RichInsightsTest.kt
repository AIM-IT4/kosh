package com.amitjha.kosh.finance

import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RichInsightsTest {
    private val today = LocalDate.of(2026, 10, 20)
    private fun rs(x: Double) = Money.fromRupees(x)
    private fun exp(date: LocalDate, rupees: Double, cat: String = "food", merchant: String? = null, account: Long = 1) =
        InsightTxn(date, rs(rupees), TxnType.EXPENSE, cat, merchant, account)
    private fun inc(date: LocalDate, rupees: Double) = InsightTxn(date, rs(rupees), TxnType.INCOME, "salary", "Employer", 1)
    private fun List<Insight>.get(prefix: String) = first { it.id.startsWith(prefix) }
    private fun List<Insight>.has(prefix: String) = any { it.id.startsWith(prefix) }

    private val month = listOf(
        inc(today.withDayOfMonth(1), 100_000.0),
        exp(today.withDayOfMonth(2), 20_000.0, "rent", "Landlord"),
        exp(today.withDayOfMonth(3), 4_000.0, "food", "Swiggy"),
        exp(today.withDayOfMonth(5), 3_000.0, "shopping", "Amazon"),
        exp(today.withDayOfMonth(8), 1_500.0, "food", "Zomato"),
        exp(today.withDayOfMonth(10), 2_500.0, "transport", "Uber"),
    )

    @Test
    fun monthlySummaryHasNarrativeScoreAndChart() {
        val out = InsightEngine.generate(InsightInput(today, month, categoryNames = mapOf("rent" to "Rent", "food" to "Food & Dining")))
        val s = out.get(Insight.SUMMARY_PREFIX)
        assertTrue(s.isSummary)
        assertTrue(s.detail!!.contains("Rent ₹20,000"))
        assertTrue(s.metrics.any { it.label == "Score" })
        assertEquals(6, s.chart!!.values.size)
    }

    @Test
    fun topMerchantsListsShares() {
        val t = InsightEngine.generate(InsightInput(today, month)).get("merchants_top5")
        assertEquals("Landlord", t.metrics.first().label)
        assertTrue(t.metrics.size in 3..5)
    }

    @Test
    fun categoryMoversCompareLikeForLike() {
        val last = today.minusMonths(1)
        val txns = month + exp(last.withDayOfMonth(3), 500.0, "food") + exp(last.withDayOfMonth(4), 9_000.0, "travel")
        val mv = InsightEngine.generate(InsightInput(today, txns, categoryNames = mapOf("food" to "Food"))).get("movers")
        // Rent is new this month (+₹20,000), the biggest rise; travel fell by ₹9,000.
        assertTrue(mv.message.contains("Rent is up ₹20,000"))
        assertTrue(mv.message.contains("Travel is down ₹9,000"))
        assertTrue(mv.metrics.any { it.label == "Food" })
    }

    @Test
    fun forecastWarnsWhenBalanceRunsOut() {
        val accounts = listOf(AccountInput(1, "HDFC", AccountType.BANK, rs(5_000.0)))
        val f = InsightEngine.generate(InsightInput(today, month, accounts = accounts)).get("forecast")
        assertEquals(Severity.WARNING, f.severity)
        assertEquals(InsightChart.Kind.LINE, f.chart!!.kind)
        val rich = listOf(AccountInput(1, "HDFC", AccountType.BANK, rs(500_000.0)))
        assertEquals(Severity.TIP, InsightEngine.generate(InsightInput(today, month, accounts = rich)).get("forecast").severity)
    }

    @Test
    fun recurringListAndCardVsBill() {
        val netflix = (0..2).map { exp(today.minusMonths(it.toLong()).withDayOfMonth(5), 649.0, "subscriptions", "Netflix") }
        val spotify = (0..2).map { exp(today.minusMonths(it.toLong()).withDayOfMonth(7), 119.0, "subscriptions", "Spotify") }
        val out = InsightEngine.generate(InsightInput(today, month + netflix + spotify))
        val r = out.get("recurring_list")
        assertTrue(r.metrics.any { it.label == "Netflix" })
        val card = InsightEngine.generate(
            InsightInput(today, month + exp(today.withDayOfMonth(12), 7_000.0, "shopping", "Croma", account = 9),
                upcomingBills = listOf(BillInput("HDFC Regalia", rs(12_000.0), today.plusDays(10), accountId = 9))),
        ).get("card_vs_bill")
        assertTrue(card.message.contains("₹7,000"))
    }

    @Test
    fun existingInsightsAreEnriched() {
        val past = (1..3).map { exp(today.minusMonths(it.toLong()).withDayOfMonth(4), 2_000.0, "food") }
        val spiky = month + past + exp(today.withDayOfMonth(15), 6_000.0, "food")
        val spike = InsightEngine.generate(InsightInput(today, spiky, categoryNames = mapOf("food" to "Food & Dining"))).get("spike_food")
        assertEquals("Food & Dining spending is up", spike.title)
        assertNotNull(spike.detail)
        assertEquals(InsightRoute.CATEGORY, spike.action!!.route)
        assertEquals("food", spike.action!!.argument)
        assertFalse(spike.metrics.isEmpty())
        val budget = InsightEngine.generate(InsightInput(today, month, budgets = listOf(BudgetInput("food", rs(4_000.0))))).get("budget_food")
        assertEquals(rs(4_000.0), budget.chart!!.reference)
    }

    @Test
    fun nothingForEmptyInput() {
        assertFalse(InsightEngine.generate(InsightInput(today)).has(Insight.SUMMARY_PREFIX))
    }
}
