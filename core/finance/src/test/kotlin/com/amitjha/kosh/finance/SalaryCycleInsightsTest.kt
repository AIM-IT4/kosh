package com.amitjha.kosh.finance

import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Budgeting months that start on the salary day (e.g. the 25th) instead of the 1st. */
class SalaryCycleInsightsTest {

    private fun txn(date: LocalDate, rupees: Long, type: TxnType, category: String, merchant: String? = null) =
        InsightTxn(date, rupees * 100, type, category, merchant, accountId = 1, isCreditCard = false)

    private val today = LocalDate.of(2026, 10, 15)
    private val transactions = listOf(
        txn(LocalDate.of(2026, 9, 25), 171_000, TxnType.INCOME, "salary", "Acme Corp"),
        txn(LocalDate.of(2026, 10, 2), 25_000, TxnType.EXPENSE, "rent", "Landlord"),
        txn(LocalDate.of(2026, 10, 6), 9_000, TxnType.EXPENSE, "groceries", "BigBasket"),
        txn(LocalDate.of(2026, 10, 11), 7_000, TxnType.EXPENSE, "food", "Swiggy"),
    )

    @Test
    fun salaryCreditedBeforeTheFirstCountsForTheCycle() {
        val insights = InsightEngine.generate(InsightInput(today = today, transactions = transactions, monthStartDay = 25))
        assertFalse(
            "should not say spending exceeds income when ₹1.71L came in and ₹41K went out",
            insights.any { it.title == "Spending more than you earn" },
        )
        assertFalse(insights.any { it.id.startsWith("no_income") })
        val summary = insights.firstOrNull { it.id.startsWith("monthly_summary") }
        if (summary != null) {
            assertTrue("cycle 25 Sep – 24 Oct is labelled October: ${summary.message}", summary.message.contains("October"))
        }
    }

    @Test
    fun calendarMonthsStillWorkWhenStartDayIsTheFirst() {
        val insights = InsightEngine.generate(InsightInput(today = today, transactions = transactions, monthStartDay = 1))
        // With calendar months, October has spending but its salary arrived in September.
        assertTrue(insights.any { it.title == "Spending more than you earn" || it.id.startsWith("no_income") })
    }

    @Test
    fun displayedDatesAreRealDates() {
        val bills = listOf(BillInput("HDFC Card", 4_200_000, LocalDate.of(2026, 10, 17), false))
        val insights = InsightEngine.generate(
            InsightInput(today = today, transactions = transactions, upcomingBills = bills, monthStartDay = 25),
        )
        val bill = insights.first { it.id.contains("bill") }
        assertTrue("due date shown as the real 17 Oct: ${bill.message}", bill.message.contains("17 Oct") || bill.title.contains("2 days"))
    }

    @Test
    fun salaryOnTheFridayBeforeASundayPaydayStartsTheNewPeriod() {
        // 25 Oct 2026 is a Sunday: salary lands on Friday 23 Oct and the November period starts then.
        val day = LocalDate.of(2026, 10, 24)
        val txns = listOf(
            txn(LocalDate.of(2026, 10, 22), 5_000, TxnType.EXPENSE, "food", "Swiggy"),
            txn(LocalDate.of(2026, 10, 23), 171_000, TxnType.INCOME, "salary", "Acme Corp"),
            txn(LocalDate.of(2026, 10, 24), 1_200, TxnType.EXPENSE, "groceries", "BigBasket"),
        )
        val insights = InsightEngine.generate(
            InsightInput(today = day, transactions = txns, monthStartDay = 25, payCycle = PayCycle(25)),
        )
        val summary = insights.first { it.isSummary }
        assertTrue(summary.message, summary.message.contains("In November"))
        assertTrue(summary.message, summary.message.contains("earned ₹1,71,000"))
        assertTrue("the 22 Oct spend belongs to October: ${summary.message}", summary.message.contains("spent ₹1,200"))
        assertFalse(insights.any { it.id.startsWith("no_income") })
    }
}
