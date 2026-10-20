package com.amitjha.kosh.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Interest credits of payout FDs: how much and when. */
class FdPayoutTest {

    private val lakh = 100_000L * 100
    private val start = LocalDate.of(2026, 1, 5)

    private fun fd(
        frequency: PayoutFrequency,
        maturity: LocalDate = start.plusYears(1),
        rate: Double = 7.0,
        amount: Long? = null,
        day: Int? = null,
        from: LocalDate = start,
    ) = FixedDeposit(
        lakh, rate, from, maturity, Compounding.QUARTERLY, FdPayout.INTEREST_PAYOUT,
        payoutFrequency = frequency, payoutAmount = amount, payoutDay = day,
    )

    @Test
    fun banksPayTheDiscountedValueMonthly() {
        // ₹1,750 a quarter at 7% on ₹1 lakh; monthly it is 1,750 × (0.07/12) / ((1 + 0.07/12)³ − 1) = ₹579.94.
        assertEquals(57_994L, fd(PayoutFrequency.MONTHLY).computedPayout())
        assertEquals(175_000L, fd(PayoutFrequency.QUARTERLY).computedPayout())
        // Longer periods compound quarterly within them: 1 lakh × (1.0175² − 1) and × (1.0175⁴ − 1).
        assertEquals(353_063L, fd(PayoutFrequency.HALF_YEARLY).computedPayout())
        assertEquals(718_590L, fd(PayoutFrequency.YEARLY).computedPayout())
    }

    @Test
    fun monthlyCreditsFallOnTheDepositDate() {
        val schedule = fd(PayoutFrequency.MONTHLY).payoutSchedule()
        assertEquals(12, schedule.size)
        assertEquals(LocalDate.of(2026, 2, 5), schedule.first().date)
        assertEquals(LocalDate.of(2027, 1, 5), schedule.last().date)
        assertTrue(schedule.last().final)
        assertTrue(schedule.all { it.amount == 57_994L })
    }

    @Test
    fun aPartPeriodIsPaidWithThePrincipal() {
        // Quarterly from 5 Jan, maturing 20 Apr: ₹1,750 on 5 Apr, then 15 days' simple interest (₹287.67) on 20 Apr.
        val schedule = fd(PayoutFrequency.QUARTERLY, maturity = LocalDate.of(2026, 4, 20)).payoutSchedule()
        assertEquals(listOf(LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 20)), schedule.map { it.date })
        assertEquals(listOf(175_000L, 28_767L), schedule.map { it.amount })
        assertTrue(schedule.last().final)
    }

    @Test
    fun aFixedCreditDayStartsWithTheDaysSinceTheDeposit() {
        // Made on 20 Jan, credited on the 1st: 12 of January's 31 days in the first credit, full credits after.
        val schedule = fd(PayoutFrequency.MONTHLY, day = 1, from = LocalDate.of(2026, 1, 20), maturity = LocalDate.of(2026, 7, 20)).payoutSchedule()
        assertEquals(LocalDate.of(2026, 2, 1), schedule[0].date)
        assertEquals(22_449L, schedule[0].amount)
        assertEquals(LocalDate.of(2026, 3, 1), schedule[1].date)
        assertEquals(57_994L, schedule[1].amount)
        assertEquals(LocalDate.of(2026, 7, 20), schedule.last().date)
    }

    @Test
    fun theBanksFigureWinsAndCreditsAddUp() {
        val f = fd(PayoutFrequency.MONTHLY, amount = 58_000L)
        assertEquals(58_000L, f.regularPayout())
        assertEquals(LocalDate.of(2026, 3, 5), f.nextPayout(LocalDate.of(2026, 2, 6))?.date)
        assertEquals(LocalDate.of(2026, 2, 5), f.nextPayout(LocalDate.of(2026, 2, 5))?.date)
        assertEquals(2 * 58_000L, f.interestPaidTill(LocalDate.of(2026, 3, 5)))
        assertNull(f.nextPayout(LocalDate.of(2027, 1, 6)))
        // The deposit itself stays at the principal.
        assertEquals(lakh, f.valueOn(LocalDate.of(2026, 6, 1)))
    }

    @Test
    fun cumulativeDepositsPayNothingOut() {
        val cumulative = FixedDeposit(lakh, 7.0, start, start.plusYears(1))
        assertTrue(cumulative.payoutSchedule().isEmpty())
        assertEquals(0L, cumulative.interestPaidTill(start.plusYears(1)))
    }

    @Test
    fun creditsInTheBankMessagesAreMatchedToTheirDueDates() {
        val schedule = fd(PayoutFrequency.MONTHLY).payoutSchedule().take(3)
        val credits = listOf(
            LocalDate.of(2026, 2, 5) to 57_994L,
            // March's came on the 6th, net of 10% TDS.
            LocalDate.of(2026, 3, 6) to 52_195L,
            // A different credit the same week isn't interest.
            LocalDate.of(2026, 4, 4) to 2_000_000L,
        )
        val seen = FdPayouts.match(schedule, credits)
        assertEquals(LocalDate.of(2026, 2, 5), seen[0].creditedOn)
        assertEquals(52_195L, seen[1].credited)
        assertEquals(false, seen[2].seen)
    }
}
