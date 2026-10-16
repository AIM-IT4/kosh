package com.amitjha.kosh.finance

import com.amitjha.kosh.finance.BudgetMath.Due
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetMathTest {

    private val today = LocalDate.of(2026, 10, 16)

    private fun d(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

    private fun period(limit: Long, spent: Long) = limit to spent

    @Test
    fun carryAccumulatesUnspentBudget() {
        // ₹2,000 left in the first period, ₹1,000 in the second.
        assertEquals(3_000_00L, BudgetMath.rolloverCarry(listOf(period(10_000_00, 8_000_00), period(10_000_00, 9_000_00))))
        // Overspending ₹1,000 in a third period eats into the carry.
        assertEquals(
            2_000_00L,
            BudgetMath.rolloverCarry(listOf(period(10_000_00, 8_000_00), period(10_000_00, 9_000_00), period(10_000_00, 11_000_00))),
        )
    }

    @Test
    fun carryNeverExceedsOnePeriodsBudget() {
        // Two untouched ₹5,000 periods carry ₹5,000, not ₹10,000.
        assertEquals(5_000_00L, BudgetMath.rolloverCarry(listOf(period(5_000_00, 0), period(5_000_00, 0))))
        // The clamp uses each period's own limit: ₹10,000 carried into a ₹3,000 period is cut to ₹3,000.
        assertEquals(3_000_00L, BudgetMath.rolloverCarry(listOf(period(10_000_00, 0), period(3_000_00, 3_000_00))))
    }

    @Test
    fun overspendingClampsAtMinusOnePeriodsBudget() {
        // ₹7,000 over a ₹5,000 budget only takes ₹5,000 off the next period.
        assertEquals(-5_000_00L, BudgetMath.rolloverCarry(listOf(period(5_000_00, 12_000_00))))
        // Clamping happens after each period, so ₹1,000 saved next brings it to −₹4,000 (not −₹6,000).
        assertEquals(-4_000_00L, BudgetMath.rolloverCarry(listOf(period(5_000_00, 12_000_00), period(5_000_00, 4_000_00))))
    }

    @Test
    fun emptyHistoryCarriesNothing() {
        assertEquals(0L, BudgetMath.rolloverCarry(emptyList()))
    }

    @Test
    fun nextPaydayLaterThisMonthOrToday() {
        assertEquals(d(2026, 10, 25), BudgetMath.nextPayday(today, 25))
        assertEquals(today, BudgetMath.nextPayday(today, 16))
    }

    @Test
    fun nextPaydayMovesToNextMonthOncePassed() {
        assertEquals(d(2026, 11, 10), BudgetMath.nextPayday(today, 10))
        assertEquals(d(2027, 1, 5), BudgetMath.nextPayday(d(2026, 12, 20), 5))
    }

    @Test
    fun nextPaydayClampsToMonthEnd() {
        assertEquals(d(2026, 2, 28), BudgetMath.nextPayday(d(2026, 2, 10), 31))
        assertEquals(d(2028, 2, 29), BudgetMath.nextPayday(d(2028, 2, 10), 31))
        assertEquals(d(2026, 3, 31), BudgetMath.nextPayday(d(2026, 3, 1), 31))
        // 30 Jan has passed, and February has no 30th.
        assertEquals(d(2026, 2, 28), BudgetMath.nextPayday(d(2026, 1, 31), 30))
        // The 31st in April is the 30th, which is today.
        assertEquals(d(2026, 4, 30), BudgetMath.nextPayday(d(2026, 4, 30), 31))
    }

    @Test
    fun safeToSpendCountsOnlyDuesBeforePayday() {
        val dues = listOf(
            Due(d(2026, 10, 24), 3_000_00, "Card bill"),
            Due(d(2026, 10, 10), 999_00, "Already past"),
            Due(d(2026, 10, 16), 2_000_00, "Electricity"), // today counts
            Due(d(2026, 10, 25), 25_000_00, "Rent"), // payday: paid from the salary
            Due(d(2026, 11, 1), 1_500_00, "Gym"),
        )
        val s = BudgetMath.safeToSpend(today, 25, spendable = 50_000_00, dues = dues, reserved = 5_000_00)
        assertEquals(d(2026, 10, 25), s.payday)
        assertEquals(5_000_00L, s.dueBeforePayday)
        assertEquals(listOf("Electricity", "Card bill"), s.dues.map { it.label })
        assertEquals(50_000_00L, s.spendable)
        assertEquals(5_000_00L, s.reserved)
        assertEquals(40_000_00L, s.available)
        assertEquals(9, s.days)
        assertEquals(4_444_44L, s.perDay) // ₹40,000 / 9 = ₹4,444.44
    }

    @Test
    fun perDayRoundsDown() {
        // ₹200 over 3 days is ₹66.66 a day, never ₹66.67 (which would add up to more than ₹200).
        val s = BudgetMath.safeToSpend(today, 19, spendable = 200_00, dues = emptyList())
        assertEquals(3, s.days)
        assertEquals(0L, s.reserved)
        assertEquals(200_00L, s.available)
        assertEquals(66_66L, s.perDay)
    }

    @Test
    fun negativeAvailableGivesZeroPerDay() {
        val s = BudgetMath.safeToSpend(today, 25, spendable = 1_000_00, dues = listOf(Due(d(2026, 10, 20), 3_000_00, "EMI")))
        assertEquals(-2_000_00L, s.available)
        assertEquals(0L, s.perDay)
    }

    @Test
    fun onPaydayDaysIsAtLeastOneAndNothingIsDue() {
        val payday = d(2026, 10, 25)
        val s = BudgetMath.safeToSpend(payday, 25, spendable = 30_000_00, dues = listOf(Due(payday, 25_000_00, "Rent")))
        assertEquals(payday, s.payday)
        assertEquals(1, s.days)
        assertEquals(0L, s.dueBeforePayday)
        assertTrue(s.dues.isEmpty())
        assertEquals(30_000_00L, s.perDay)
    }

    @Test
    fun envelopeShowsWhatIsLeftToAssign() {
        assertEquals(
            BudgetMath.Envelope(income = 1_00_000_00, assigned = 60_000_00, toAssign = 40_000_00),
            BudgetMath.envelope(1_00_000_00, listOf(30_000_00, 20_000_00, 10_000_00)),
        )
        // Over-assigned by ₹10,000.
        assertEquals(-10_000_00L, BudgetMath.envelope(50_000_00, listOf(30_000_00, 30_000_00)).toAssign)
        assertEquals(BudgetMath.Envelope(50_000_00, 0, 50_000_00), BudgetMath.envelope(50_000_00, emptyList()))
    }
}
