package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Bank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SavingsInterestTest {

    private val flat25 = SavingsRates.overridden(Bank.HDFC, 2.5)

    @Test
    fun thirtyDaysOnALakhAtTwoAndAHalfPercent() {
        // 1,00,000 × 2.5% / 365 × 30 = ₹205.479… → ₹205.48
        val today = LocalDate.of(2026, 10, 30)
        val e = SavingsInterest.estimate(
            currentBalance = 100_000_00,
            flows = emptyList(),
            today = today,
            entryDate = LocalDate.of(2026, 10, 1),
            card = flat25,
        )
        assertEquals(30, e.daysCounted)
        assertEquals(20548L, e.accrued)
        assertEquals(LocalDate.of(2026, 12, 31), e.nextCreditDate)
        // projected adds 62 more days (31 Oct – 31 Dec) at the same balance: 92 days in all
        assertEquals(Math.round(100_000_00 * 0.025 / 365 * 92), e.projected)
    }

    @Test
    fun tieredSlabsEarnEachBandAtItsRate() {
        val slabs = listOf(RateSlab(0, 3.0), RateSlab(50_00_000_00, 3.5)) // 3% up to ₹50L, 3.5% above
        // ₹60L: 50L at 3% + 10L at 3.5%, per day
        val daily = SavingsRates.dailyInterest(60_00_000_00, slabs)
        val expected = (50_00_000_00 * 0.03 + 10_00_000_00 * 0.035) / 365
        assertEquals(expected, daily, 1e-6)
        assertEquals(0.0, SavingsRates.dailyInterest(-500_00, slabs), 0.0)
    }

    @Test
    fun balanceChangeMidPeriodUsesDailyClosingBalances() {
        // Tracking since 1 Oct with ₹50,000; ₹50,000 salary lands on 16 Oct; today 30 Oct, balance ₹1,00,000.
        val today = LocalDate.of(2026, 10, 30)
        val e = SavingsInterest.estimate(
            currentBalance = 100_000_00,
            flows = listOf(BalanceFlow(LocalDate.of(2026, 10, 16), 50_000_00)),
            today = today,
            entryDate = LocalDate.of(2026, 10, 1),
            card = flat25,
        )
        // 1–15 Oct (15 days) at 50k, 16–30 Oct (15 days) at 1L
        val expected = (50_000_00 * 15 + 100_000_00 * 15) * 0.025 / 365
        assertEquals(Math.round(expected), e.accrued)
    }

    @Test
    fun daysBeforeEntryAreNotCounted() {
        val e = SavingsInterest.estimate(
            currentBalance = 100_000_00,
            flows = emptyList(),
            today = LocalDate.of(2026, 10, 30),
            entryDate = LocalDate.of(2026, 10, 21),
            card = flat25,
        )
        assertEquals(10, e.daysCounted)
    }

    @Test
    fun monthlyFrequencyAndQuarterBoundaries() {
        assertEquals(
            CreditPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)),
            SavingsInterest.periodContaining(LocalDate.of(2026, 2, 14), CreditFrequency.QUARTERLY),
        )
        assertEquals(
            CreditPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
            SavingsInterest.periodContaining(LocalDate.of(2026, 2, 14), CreditFrequency.MONTHLY),
        )
    }

    @Test
    fun lastPeriodCreditIsComparedWithItsEstimate() {
        // Tracked since 1 Jul at ₹1,00,000; bank credited ₹620 on 30 Sep; today 15 Oct.
        val credit = BalanceFlow(LocalDate.of(2026, 9, 30), 620_00)
        val e = SavingsInterest.estimate(
            currentBalance = 100_620_00,
            flows = listOf(credit),
            today = LocalDate.of(2026, 10, 15),
            entryDate = LocalDate.of(2026, 7, 1),
            card = flat25,
            interestCredits = listOf(credit),
        )
        assertNotNull(e.lastCredit)
        val last = e.lastCredit!!
        assertEquals(620_00L, last.credited)
        // Jul–Sep: 92 days, every day at 1,00,000 plus the ₹620 credit on the last day (30 Sep)
        val expected = (100_000_00.0 * 92 + 620_00) * 0.025 / 365
        assertEquals(Math.round(expected), last.estimated)
        assertEquals(15, e.daysCounted) // the new quarter's accrual starts over on 1 Oct
    }

    @Test
    fun noCreditMeansNoComparison() {
        val e = SavingsInterest.estimate(100_000_00, emptyList(), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 7, 1), flat25)
        assertNull(e.lastCredit)
    }

    @Test
    fun tableCoversKnownBanks() {
        Bank.entries.forEach { assertEquals(it == Bank.OTHER || SavingsRates.table.containsKey(it), true) }
        assertEquals(RateConfidence.OFFICIAL, SavingsRates.forBank(Bank.HDFC).confidence)
        assertEquals(2.5, SavingsRates.forBank(Bank.HDFC).headlineRatePct(), 0.0)
    }
}
