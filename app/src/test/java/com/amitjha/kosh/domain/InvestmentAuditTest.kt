package com.amitjha.kosh.domain

import com.amitjha.kosh.finance.FdPayout
import com.amitjha.kosh.repo.InvestmentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Regression tests for investment values and returns found in the finance audit. */
class InvestmentAuditTest {

    private val today = LocalDate.of(2026, 10, 15)

    // 4. Simple NPS shows its expected return, not value-vs-cost growth since the last edit (was 620% p.a.).
    @Test
    fun simpleNpsReturnIsTheExpectedRate() {
        val spec = NpsSimple(7_00_000_00, today.minusDays(30), 10_000_00, 1, 10.0, 6_00_000_00)
        assertEquals(10.0, InvestmentRepository.annualisedReturn(spec, 7_00_000_00, 6_00_000_00, today)!!, 0.0)
    }

    // 4. An interest-payout FD earns its rate even though its value stays at the principal.
    @Test
    fun payoutFdReturnIsItsRate() {
        val fd = InvestmentSpec.Fd(1_00_000_00, 7.0, today.minusYears(1), today.plusYears(1), payout = FdPayout.INTEREST_PAYOUT)
        assertEquals(7.0, InvestmentRepository.annualisedReturn(fd, 1_00_000_00, 1_00_000_00, today)!!, 0.0)
    }

    // 4. EPF contributions made later shouldn't drag the return down: show the EPF rate.
    @Test
    fun epfReturnIsTheEpfRate() {
        val epf = InvestmentSpec.Epf(5_00_000_00, today.minusYears(1), 5_000_00, 2_000_00, 8.25)
        assertEquals(8.25, InvestmentRepository.annualisedReturn(epf, 6_10_000_00, 5_84_000_00, today)!!, 0.0)
        assertNull(InvestmentRepository.annualisedReturn(InvestmentSpec.Nps(emptyList()), 0, 0, today))
    }

    @Test
    fun cumulativeFdReturnStillUsesGrowth() {
        val fd = InvestmentSpec.Fd(1_00_000_00, 7.0, today.minusYears(1), today.plusYears(1))
        val r = InvestmentRepository.annualisedReturn(fd, 1_07_186_00, 1_00_000_00, today)!!
        assertEquals(7.19, r, 0.05)
    }

    // 3. Editing a simple NPS must start from today's projected value, which includes a contribution made
    // since the last stored revalue.
    @Test
    fun simpleNpsValueOnIncludesContributionDueToday() {
        val spec = NpsSimple(5_00_000_00, LocalDate.of(2026, 9, 30), 10_000_00, 1, 10.0)
        val (value, invested) = spec.valueOn(LocalDate.of(2026, 10, 1))
        assertTrue(value >= 5_10_000_00)
        assertEquals(5_10_000_00L, invested)
    }

    // 12. The "balance includes this year's interest" choice survives the JSON round trip.
    @Test
    fun epfFlagRoundTrips() {
        val epf = InvestmentSpec.Epf(10_00_000_00, today, 5_000_00, 1_800_00, 8.25, 15, includesCurrentYearInterest = true)
        assertEquals(epf, SpecCodec.decode(SpecCodec.encode(epf)))
        val legacy = """{"type":"EPF","balance":100,"asOf":"2026-10-15","employee":0,"employer":0,"rate":8.25,"day":15}"""
        assertEquals(false, (SpecCodec.decode(legacy) as InvestmentSpec.Epf).includesCurrentYearInterest)
    }
}
