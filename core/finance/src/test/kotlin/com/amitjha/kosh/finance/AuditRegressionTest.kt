package com.amitjha.kosh.finance

import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Regression tests for the finance audit findings, using the scenarios that reproduced them. */
class AuditRegressionTest {

    private fun rupees(r: Long) = r * 100

    private fun expense(date: LocalDate, r: Long, category: String, account: Long = 1, card: Boolean = false, merchant: String? = null) =
        InsightTxn(date, rupees(r), TxnType.EXPENSE, category, merchant, account, card)

    private fun income(date: LocalDate, r: Long, category: String = "salary") =
        InsightTxn(date, rupees(r), TxnType.INCOME, category, "Employer", 1)

    // 1. A negative bank balance must not crash the savings-interest estimate.
    @Test
    fun negativeBalanceDoesNotCrashSavingsInterest() {
        val card = SavingsRates.forBank(Bank.DBS)
        assertEquals(2.50, card.headlineRatePct(-50_000), 0.0)
        val today = LocalDate.of(2026, 10, 15)
        val est = SavingsInterest.estimate(-50_000, emptyList(), today, today.minusDays(30), card)
        assertEquals(0L, est.accrued)
        assertEquals(2.50, est.ratePct, 0.0)
    }

    // 6. Card spends are paid through the bill, so they must not also reduce the bank run-rate.
    @Test
    fun forecastDoesNotCountCardSpendsTwice() {
        val today = LocalDate.of(2026, 10, 10)
        val txns = (1..10).map { expense(LocalDate.of(2026, 10, it), 2_000, "shopping", account = 2, card = true) }
        val input = InsightInput(
            today = today,
            transactions = txns,
            accounts = listOf(
                AccountInput(1, "Bank", AccountType.BANK, rupees(100_000)),
                AccountInput(2, "Card", AccountType.CREDIT_CARD, rupees(60_000), rupees(200_000)),
            ),
            upcomingBills = listOf(BillInput("Card", rupees(60_000), LocalDate.of(2026, 10, 25), accountId = 2)),
        )
        val forecast = InsightEngine.generate(input).first { it.id.startsWith("forecast_") }
        assertEquals(Severity.TIP, forecast.severity)
        assertTrue(forecast.title, forecast.title.contains("₹40,000"))
    }

    // 7. A goal whose savings already grow to the target needs nothing more: not "off track".
    @Test
    fun goalNeedingNothingMoreIsOnTrack() {
        val today = LocalDate.of(2026, 10, 15)
        val goal = GoalInput("Car", rupees(100_000), rupees(95_000), today.plusMonths(12), expectedReturnPct = 12.0)
        val txns = (1..3).flatMap { m ->
            val d = today.minusMonths(m.toLong()).withDayOfMonth(5)
            listOf(income(d, 50_000), expense(d, 60_000, "rent"))
        }
        val insight = InsightEngine.generate(InsightInput(today = today, transactions = txns, goals = listOf(goal)))
            .first { it.id == "goal_car" }
        assertEquals(Severity.GOOD, insight.severity)
        assertFalse(insight.message, insight.message.contains("₹0 a month"))
    }

    // 8. With a salary-day month, the forecast date and chart use real dates.
    @Test
    fun forecastDateIsTheRealPeriodEnd() {
        val today = LocalDate.of(2026, 10, 27)
        val input = InsightInput(
            today = today,
            transactions = listOf(income(LocalDate.of(2026, 10, 25), 100_000), expense(LocalDate.of(2026, 10, 26), 2_000, "food")),
            accounts = listOf(AccountInput(1, "Bank", AccountType.BANK, rupees(150_000))),
            monthStartDay = 25,
        )
        val forecast = InsightEngine.generate(input).first { it.id.startsWith("forecast_") }
        assertTrue(forecast.message, forecast.message.contains("24 Nov"))
        assertEquals("27", forecast.chart!!.labels.first())
        assertEquals("24", forecast.chart!!.labels.last())
    }

    // 9. New users: the average uses only months that have data.
    @Test
    fun spikeAverageIgnoresMonthsWithoutData() {
        val today = LocalDate.of(2026, 10, 15)
        val txns = listOf(expense(LocalDate.of(2026, 9, 10), 6_000, "food"), expense(LocalDate.of(2026, 10, 5), 3_100, "food"))
        val insights = InsightEngine.generate(InsightInput(today = today, transactions = txns))
        assertTrue(insights.none { it.id.startsWith("spike_food") })
    }

    // 10. The engine names every period exactly like the app's Period.label: its start month for a start day of
    // 1–15, the next month for 16–28. (The month of its 15th day skipped February for a start day of 16.)
    @Test
    fun periodLabelMatchesAppRuleForEveryStartDay() {
        for (startDay in 1..28) {
            var d = LocalDate.of(2025, 1, 1)
            while (d.isBefore(LocalDate.of(2027, 1, 1))) {
                val realStart = if (d.dayOfMonth >= startDay) d.withDayOfMonth(startDay) else d.minusMonths(1).withDayOfMonth(startDay)
                val expected = YearMonth.from(realStart).let { if (startDay > 15) it.plusMonths(1) else it }
                val engineMonth = YearMonth.from(d.minusDays(startDay - 1L))
                assertEquals("start $startDay, date $d", expected, InsightEngine.periodLabel(engineMonth, startDay))
                d = d.plusDays(1)
            }
        }
    }

    @Test
    fun summaryUsesTheSameMonthName() {
        // Start day 16: 16 Feb – 15 Mar is named after its 15th day, 2 March.
        val input = InsightInput(
            today = LocalDate.of(2026, 2, 20),
            transactions = listOf(income(LocalDate.of(2026, 2, 16), 90_000), expense(LocalDate.of(2026, 2, 18), 4_000, "food")),
            monthStartDay = 16,
        )
        val summary = InsightEngine.generate(input).first { it.isSummary }
        assertTrue(summary.message, summary.message.contains("March"))
    }

    // 14. A "Health insurance" budget must get its own details, not the "Health" budget's.
    @Test
    fun budgetDetailMatchesTheExactBudget() {
        val today = LocalDate.of(2026, 10, 15)
        val input = InsightInput(
            today = today,
            transactions = listOf(expense(today.minusDays(3), 9_000, "health_insurance")),
            budgets = listOf(BudgetInput("health", rupees(5_000)), BudgetInput("health_insurance", rupees(10_000))),
            categoryNames = mapOf("health" to "Health", "health_insurance" to "Health insurance"),
        )
        val insight = InsightEngine.generate(input).first { it.id.startsWith("budget_health_insurance_") }
        assertTrue(insight.detail!!, insight.detail!!.startsWith("Health insurance spending is ₹9,000"))
    }

    // 15. Spends before the statement date are already on the bill.
    @Test
    fun cardSpendVsBillCountsOnlySpendsAfterTheStatement() {
        val today = LocalDate.of(2026, 10, 15)
        val txns = listOf(
            expense(LocalDate.of(2026, 10, 2), 5_000, "shopping", account = 2, card = true),
            expense(LocalDate.of(2026, 10, 12), 1_500, "food", account = 2, card = true),
        )
        val bill = BillInput("Card", rupees(5_000), LocalDate.of(2026, 10, 25), accountId = 2, statementDate = LocalDate.of(2026, 10, 5))
        val insight = InsightEngine.generate(InsightInput(today = today, transactions = txns, upcomingBills = listOf(bill)))
            .first { it.id.startsWith("card_vs_bill_") }
        assertTrue(insight.message, insight.message.contains("₹1,500"))
    }

    // 12. A mid-year passbook balance earns this fiscal year's interest from 1 April.
    @Test
    fun epfAccruesCurrentYearInterestBeforeTheEntryDate() {
        val epf = EpfAccount(rupees(1_000_000), LocalDate.of(2026, 10, 15), 0, 0, 8.25)
        assertEquals(rupees(41_250).toDouble(), epf.interestBeforeAsOf, 1.0)
        val atYearEnd = epf.projectBalance(LocalDate.of(2027, 3, 31))
        // 6 months before the entry date + 6 after it, credited on 31 March.
        assertEquals(rupees(1_082_500).toDouble(), atYearEnd.creditedBalance.toDouble(), 100.0)
        val includes = epf.copy(balanceIncludesCurrentYearInterest = true)
        assertEquals(0.0, includes.interestBeforeAsOf, 0.0)
        assertEquals(rupees(1_041_250).toDouble(), includes.projectBalance(LocalDate.of(2027, 3, 31)).creditedBalance.toDouble(), 100.0)
    }

    // 16. A zero NAV never becomes infinite units, and NAV dates come from the quote.
    @Test
    fun zeroNavIsRejectedAndQuoteDatesAreKept() {
        assertTrue(NavJson.parseObjects("""[{"Scheme Code":"SM1","Scheme Name":"X","NAV":"0","Last Updated":"10-10-2026"}]""").isEmpty())
        val account = NpsAccount(listOf(NpsHolding("SM1", "X", 100.0, 50.0)), monthlyContribution = rupees(1_000))
        val quoted = account.withQuotes(mapOf("SM1" to NavQuote("SM1", "X", 52.0, LocalDate.of(2026, 10, 10))), LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 10), quoted.holdings.first().lastNavDate)
        val bought = account.applyContribution(rupees(1_000), mapOf("SM1" to 0.0)).unitsBought.getValue("SM1")
        assertEquals(20.0, bought, 1e-9)
        assertTrue(bought.isFinite())
        assertNull(account.withQuotes(mapOf("SM1" to NavQuote("SM1", "X", 0.0, null)), LocalDate.of(2026, 10, 15)).holdings.first().lastNavDate)
        assertNotNull(quoted)
    }
}
