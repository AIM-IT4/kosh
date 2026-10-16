package com.amitjha.kosh.finance

import com.amitjha.kosh.finance.Loans.Prepayment
import com.amitjha.kosh.finance.Loans.Reduce
import com.amitjha.kosh.finance.Loans.Row
import com.amitjha.kosh.finance.Loans.Spec
import com.amitjha.kosh.finance.Loans.YearSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.assertFailsWith

class LoansTest {

    private fun rs(rupees: Long): Long = rupees * 100

    /** One month of [reference]: interest, principal repaid by the EMI, and the balance after any prepayment. */
    private data class RefRow(val interest: Long, val principal: Long, val balance: Long)

    /**
     * Independent amortisation in plain Long arithmetic, with the monthly rate as the fraction [num]/[den]
     * (8.5% a year is 85/12000) and interest rounded half-up. [prepayAfter] maps an EMI number to a prepayment
     * made right after it; [emiAfter] maps an EMI number to the EMI charged from the next month on.
     */
    private fun reference(
        principal: Long,
        num: Long,
        den: Long,
        emi: Long,
        prepayAfter: Map<Int, Long> = emptyMap(),
        emiAfter: Map<Int, Long> = emptyMap(),
    ): List<RefRow> {
        val rows = mutableListOf<RefRow>()
        var balance = principal
        var instalment = emi
        while (balance > 0) {
            val interest = (2 * balance * num + den) / (2 * den)
            val paid = minOf(instalment, balance + interest) - interest
            balance -= paid
            balance -= minOf(prepayAfter[rows.size + 1] ?: 0L, balance)
            rows += RefRow(interest, paid, balance)
            instalment = emiAfter[rows.size] ?: instalment
        }
        return rows
    }

    private fun List<Row>.asRef() = map { RefRow(it.interest, it.principal, it.balance) }

    /** The EMI formula in decimal arithmetic, in paise. */
    private fun exactEmi(principal: Long, annualRatePct: String, months: Int): BigDecimal {
        val mc = MathContext.DECIMAL128
        val r = BigDecimal(annualRatePct).divide(BigDecimal(1200), mc)
        val f = BigDecimal.ONE.add(r).pow(months, mc)
        return BigDecimal.valueOf(principal).multiply(r).multiply(f).divide(f.subtract(BigDecimal.ONE), mc)
    }

    private fun ceilRupee(paise: BigDecimal): Long = paise.divide(BigDecimal(100)).setScale(0, RoundingMode.CEILING).toLong() * 100

    // ₹10,00,000 home loan at 8.5% for 20 years, first EMI on 5 May 2025.
    private val home = Spec(rs(10_00_000), 8.5, 240, LocalDate.of(2025, 5, 5))
    private val homeRef = reference(rs(10_00_000), 85, 12_000, rs(8_679))

    // ₹1,00,000 at 12% (1% a month) for a year, first EMI on 10 Oct 2025: small enough to check by hand.
    private val small = Spec(rs(1_00_000), 12.0, 12, LocalDate.of(2025, 10, 10))
    private val smallRef = reference(rs(1_00_000), 1, 100, rs(8_885))

    // ------------------------------------------------------------------ EMI

    @Test
    fun emiIsTheFormulaRoundedUpToAWholeRupee() {
        val exact = exactEmi(rs(10_00_000), "8.5", 240)
        // The formula gives ₹8,678.23; lenders charge ₹8,679.
        assertEquals(8_678_23L, exact.setScale(0, RoundingMode.HALF_UP).toLong())
        assertEquals(rs(8_679), Loans.emi(rs(10_00_000), 8.5, 240))
        // Same formula as GoalLoan.emi, which keeps the paise.
        assertEquals(8_678_23L, GoalLoan.emi(rs(10_00_000), 8.5, 240))
        // ₹5,00,000 at 9% for 3 years: ₹15,899.87 by the formula.
        assertEquals(rs(15_900), Loans.emi(rs(5_00_000), 9.0, 36))
    }

    @Test
    fun emiIsAlwaysAWholeRupeeLessThanOneAboveTheFormula() {
        val cases = listOf(
            Triple(rs(5_00_000), "9", 36),
            Triple(rs(25_00_000), "8.75", 300),
            Triple(rs(1_00_000), "12", 12),
            Triple(rs(7_50_000), "10.5", 60),
            Triple(rs(3_00_000), "13.99", 48),
            Triple(rs(10_00_000), "8.5", 240),
        )
        for ((principal, rate, months) in cases) {
            val exact = exactEmi(principal, rate, months)
            val emi = Loans.emi(principal, rate.toDouble(), months)
            assertEquals("$principal at $rate% for $months months", ceilRupee(exact), emi)
            assertEquals(0L, emi % 100)
            val over = BigDecimal.valueOf(emi).subtract(exact)
            assertTrue(over.signum() >= 0 && over < BigDecimal(100))
        }
    }

    @Test
    fun zeroRateSplitsThePrincipalEvenly() {
        // ₹1,00,000 / 12 = ₹8,333.33, so ₹8,334 a month and ₹8,326 at the end.
        assertEquals(rs(8_334), Loans.emi(rs(1_00_000), 0.0, 12))
        assertEquals(rs(1_000), Loans.emi(rs(12_000), 0.0, 12))
        val spec = Spec(rs(1_00_000), 0.0, 12, LocalDate.of(2025, 1, 1))
        val rows = Loans.schedule(spec)
        assertEquals(12, rows.size)
        assertTrue(rows.all { it.interest == 0L })
        assertEquals(List(11) { rs(8_334) } + rs(8_326), rows.map { it.principal })
        assertEquals(0L, Loans.totalInterest(spec))
    }

    // ------------------------------------------------------------------ schedule

    @Test
    fun firstAndLastMonthsMatchHandComputation() {
        // 1,00,000 × 0.01 × 1.01^12 / (1.01^12 − 1) = ₹8,884.88, charged as ₹8,885.
        assertEquals(rs(8_885), Loans.emi(rs(1_00_000), 12.0, 12))
        val rows = Loans.schedule(small)
        assertEquals(12, rows.size)
        // Month 1: 1% of ₹1,00,000 = ₹1,000 interest, so ₹7,885 of principal.
        assertEquals(Row(1, LocalDate.of(2025, 10, 10), rs(8_885), rs(1_000), rs(7_885), 0, rs(92_115)), rows[0])
        // Month 2: 1% of ₹92,115 = ₹921.15.
        assertEquals(Row(2, LocalDate.of(2025, 11, 10), rs(8_885), 921_15, 7_963_85, 0, 84_151_15), rows[1])
        // Month 3: 1% of ₹84,151.15 = ₹841.5115, rounded to ₹841.51.
        assertEquals(Row(3, LocalDate.of(2025, 12, 10), rs(8_885), 841_51, 8_043_49, 0, 76_107_66), rows[2])
        // The last EMI only clears what is left: ₹8,795.50 plus 1% (₹87.955, rounded to ₹87.96) interest.
        assertEquals(Row(12, LocalDate.of(2026, 9, 10), 8_883_46, 87_96, 8_795_50, 0, 0), rows[11])
        assertEquals(smallRef, rows.asRef())
        // 11 × ₹8,885 + ₹8,883.46 − ₹1,00,000.
        assertEquals(6_618_46L, Loans.totalInterest(small))
    }

    @Test
    fun scheduleAddsUpAndEndsAtZero() {
        val rows = Loans.schedule(home)
        assertEquals(240, rows.size)
        assertEquals((1..240).toList(), rows.map { it.index })
        assertEquals(rs(10_00_000), rows.sumOf { it.principal + it.prepayment })
        assertTrue(rows.zipWithNext().all { (a, b) -> b.balance < a.balance })
        assertEquals(0L, rows.last().balance)
        assertTrue(rows.all { it.emi == it.interest + it.principal })
        assertTrue(rows.dropLast(1).all { it.emi == rs(8_679) })
        assertTrue(rows.last().emi in 1L until rs(8_679))
        assertEquals(LocalDate.of(2045, 4, 5), rows.last().date)
        assertEquals(homeRef, rows.asRef())
        assertEquals(homeRef.sumOf { it.interest }, Loans.totalInterest(home))
        // ₹8,679 × 239 + the smaller last EMI − ₹10,00,000.
        assertEquals(10_82_478_64L, Loans.totalInterest(home))
        assertEquals(rows.sumOf { it.emi } - rs(10_00_000), Loans.totalInterest(home))
    }

    @Test
    fun emiDatesAreClampedToMonthEnd() {
        val leap = Loans.schedule(Spec(rs(40_000), 10.0, 4, LocalDate.of(2024, 1, 31)))
        assertEquals(
            listOf(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 30)),
            leap.map { it.date },
        )
        // Counted from the first EMI, so February's clamp does not stick: back to the 31st in March.
        val plain = Loans.schedule(Spec(rs(40_000), 10.0, 4, LocalDate.of(2025, 1, 31)))
        assertEquals(LocalDate.of(2025, 2, 28), plain[1].date)
        assertEquals(LocalDate.of(2025, 3, 31), plain[2].date)
    }

    @Test
    fun lenderQuotedEmiIsUsedAsGiven() {
        // A higher EMI than the formula's clears the loan sooner.
        val higher = Loans.schedule(home.copy(emi = rs(9_000)))
        assertEquals(reference(rs(10_00_000), 85, 12_000, rs(9_000)), higher.asRef())
        assertEquals(220, higher.size)
        assertTrue(higher.dropLast(1).all { it.emi == rs(9_000) })
        // ₹8,678 is just under the formula's ₹8,678.23, so a small 241st EMI is left over.
        val lower = Loans.schedule(home.copy(emi = rs(8_678)))
        assertEquals(reference(rs(10_00_000), 85, 12_000, rs(8_678)), lower.asRef())
        assertEquals(241, lower.size)
        assertTrue(lower.last().emi < rs(200))
        assertEquals(rs(10_00_000), lower.sumOf { it.principal })
    }

    // ------------------------------------------------------------------ outstanding

    @Test
    fun outstandingBeforeDuringAndAfterTheLoan() {
        assertEquals(rs(10_00_000), Loans.outstanding(home, LocalDate.of(2024, 1, 1)))
        assertEquals(rs(10_00_000), Loans.outstanding(home, LocalDate.of(2025, 5, 4)))
        // ₹10,00,000 − (₹8,679 − ₹7,083.33 interest) from the first EMI until the second.
        assertEquals(9_98_404_33L, Loans.outstanding(home, LocalDate.of(2025, 5, 5)))
        assertEquals(homeRef[0].balance, Loans.outstanding(home, LocalDate.of(2025, 6, 4)))
        // EMI 120 falls on 5 Apr 2035.
        assertEquals(homeRef[119].balance, Loans.outstanding(home, LocalDate.of(2035, 4, 5)))
        assertEquals(homeRef[119].balance, Loans.outstanding(home, LocalDate.of(2035, 4, 20)))
        assertEquals(homeRef[238].balance, Loans.outstanding(home, LocalDate.of(2045, 4, 4)))
        assertEquals(0L, Loans.outstanding(home, LocalDate.of(2045, 4, 5)))
        assertEquals(0L, Loans.outstanding(home, LocalDate.of(2050, 1, 1)))
    }

    // ------------------------------------------------------------------ prepayments

    @Test
    fun prepaymentReducingTenureKeepsTheEmiAndEndsSooner() {
        // ₹2,00,000 on 20 Apr 2026, between EMI 12 (5 Apr) and EMI 13 (5 May).
        val date = LocalDate.of(2026, 4, 20)
        val ref = reference(rs(10_00_000), 85, 12_000, rs(8_679), prepayAfter = mapOf(12 to rs(2_00_000)))
        val rows = Loans.schedule(home.copy(prepayments = listOf(Prepayment(date, rs(2_00_000)))))
        assertEquals(ref, rows.asRef())
        assertEquals(156, rows.size)
        assertEquals(rs(2_00_000), rows[11].prepayment)
        assertEquals(rs(2_00_000), rows.sumOf { it.prepayment })
        assertEquals(rs(10_00_000), rows.sumOf { it.principal + it.prepayment })
        assertTrue(rows.zipWithNext().all { (a, b) -> b.balance <= a.balance })
        assertTrue(rows.dropLast(1).all { it.emi == rs(8_679) })

        val impact = Loans.prepaymentImpact(home, date, rs(2_00_000), Reduce.TENURE)
        assertEquals(240 - ref.size, impact.monthsSaved)
        assertEquals(84, impact.monthsSaved)
        assertEquals(homeRef.sumOf { it.interest } - ref.sumOf { it.interest }, impact.interestSaved)
        assertEquals(5_33_430_75L, impact.interestSaved)
        assertEquals(rs(8_679), impact.newEmi)
        assertEquals(LocalDate.of(2038, 4, 5), impact.newEndDate)
    }

    @Test
    fun prepaymentReducingEmiKeepsTheEndDate() {
        val date = LocalDate.of(2026, 4, 20)
        // What is owed after EMI 12 and the prepayment, re-spread over the 228 months left.
        val balance = homeRef[11].balance - rs(2_00_000)
        val newEmi = ceilRupee(exactEmi(balance, "8.5", 228))
        assertEquals(rs(6_908), newEmi)
        val ref = reference(rs(10_00_000), 85, 12_000, rs(8_679), mapOf(12 to rs(2_00_000)), mapOf(12 to newEmi))
        val rows = Loans.schedule(home.copy(prepayments = listOf(Prepayment(date, rs(2_00_000), Reduce.EMI))))
        assertEquals(ref, rows.asRef())
        assertEquals(240, rows.size)
        assertTrue(rows.take(12).all { it.emi == rs(8_679) })
        assertTrue(rows.subList(12, 239).all { it.emi == newEmi })
        assertEquals(rs(10_00_000), rows.sumOf { it.principal + it.prepayment })

        val impact = Loans.prepaymentImpact(home, date, rs(2_00_000), Reduce.EMI)
        assertEquals(0, impact.monthsSaved)
        assertEquals(newEmi, impact.newEmi)
        assertEquals(LocalDate.of(2045, 4, 5), impact.newEndDate)
        assertEquals(homeRef.sumOf { it.interest } - ref.sumOf { it.interest }, impact.interestSaved)
        assertEquals(2_03_726_54L, impact.interestSaved)
        // Keeping the EMI and cutting the tenure saves more interest.
        assertTrue(impact.interestSaved < Loans.prepaymentImpact(home, date, rs(2_00_000), Reduce.TENURE).interestSaved)
    }

    @Test
    fun prepaymentOnAnEmiDateIsAppliedAfterThatEmi() {
        val onDate = Loans.schedule(home.copy(prepayments = listOf(Prepayment(LocalDate.of(2026, 4, 5), rs(2_00_000)))))
        assertEquals(rs(2_00_000), onDate[11].prepayment)
        assertEquals(homeRef[11].interest, onDate[11].interest)
        // A day earlier it follows EMI 11, so EMI 12 already charges less interest.
        val dayBefore = Loans.schedule(home.copy(prepayments = listOf(Prepayment(LocalDate.of(2026, 4, 4), rs(2_00_000)))))
        assertEquals(rs(2_00_000), dayBefore[10].prepayment)
        assertEquals(0L, dayBefore[11].prepayment)
        assertTrue(dayBefore[11].interest < homeRef[11].interest)
        // Before the first EMI: shown on row 1, and the first month's interest is 8.5% / 12 of ₹9,00,000.
        val early = home.copy(prepayments = listOf(Prepayment(LocalDate.of(2025, 4, 20), rs(1_00_000))))
        val rows = Loans.schedule(early)
        assertEquals(rs(1_00_000), rows[0].prepayment)
        assertEquals(rs(6_375), rows[0].interest)
        assertEquals(rs(10_00_000), rows.sumOf { it.principal + it.prepayment })
        assertEquals(rs(10_00_000), Loans.outstanding(early, LocalDate.of(2025, 4, 19)))
        assertEquals(rs(9_00_000), Loans.outstanding(early, LocalDate.of(2025, 4, 20)))
    }

    @Test
    fun prepaymentAboveTheBalanceClosesTheLoan() {
        // ₹67,983.74 is owed after EMI 4 (10 Jan 2026); ₹5,00,000 offered on 15 Jan clears just that.
        val date = LocalDate.of(2026, 1, 15)
        val spec = small.copy(prepayments = listOf(Prepayment(date, rs(5_00_000))))
        val rows = Loans.schedule(spec)
        assertEquals(4, rows.size)
        assertEquals(smallRef[3].balance, rows[3].prepayment)
        assertEquals(67_983_74L, rows[3].prepayment)
        assertEquals(0L, rows[3].balance)
        assertEquals(rs(1_00_000), rows.sumOf { it.principal + it.prepayment })
        assertEquals(67_983_74L, Loans.outstanding(spec, LocalDate.of(2026, 1, 14)))
        assertEquals(0L, Loans.outstanding(spec, date))

        val impact = Loans.prepaymentImpact(small, date, rs(5_00_000), Reduce.EMI)
        assertEquals(8, impact.monthsSaved)
        assertEquals(smallRef.drop(4).sumOf { it.interest }, impact.interestSaved)
        assertEquals(0L, impact.newEmi)
        assertEquals(LocalDate.of(2026, 1, 10), impact.newEndDate)
    }

    // ------------------------------------------------------------------ financial year

    @Test
    fun financialYearSplitsALoanAcrossTwoYears() {
        // EMIs 1–6 (Oct 2025 – Mar 2026) fall in FY 2025-26, EMIs 7–12 (Apr – Sep 2026) in FY 2026-27.
        val fy25 = Loans.financialYear(small, 2025)
        val fy26 = Loans.financialYear(small, 2026)
        assertEquals(YearSplit(smallRef.take(6).sumOf { it.interest }, smallRef.take(6).sumOf { it.principal }), fy25)
        assertEquals(YearSplit(smallRef.drop(6).sumOf { it.interest }, smallRef.drop(6).sumOf { it.principal }), fy26)
        assertEquals(YearSplit(4_801_37, 48_508_63), fy25)
        assertEquals(rs(1_00_000), fy25.principal + fy26.principal)
        assertEquals(Loans.totalInterest(small), fy25.interest + fy26.interest)
        assertEquals(YearSplit(0, 0), Loans.financialYear(small, 2024))
        assertEquals(YearSplit(0, 0), Loans.financialYear(small, 2027))

        // 31 March closes a year and 30 April is in the next: ₹20,000 at 1% a month, EMI ₹10,151.
        val edge = Spec(rs(20_000), 12.0, 2, LocalDate.of(2026, 3, 31))
        assertEquals(YearSplit(rs(200), rs(9_951)), Loans.financialYear(edge, 2025))
        assertEquals(YearSplit(100_49, rs(10_049)), Loans.financialYear(edge, 2026))
    }

    @Test
    fun financialYearCountsAPrepaymentByItsOwnDate() {
        // ₹10,000 on 2 Apr 2026 follows EMI 6 (10 Mar, FY 2025-26) but is paid in FY 2026-27.
        val spec = small.copy(prepayments = listOf(Prepayment(LocalDate.of(2026, 4, 2), rs(10_000))))
        val rows = Loans.schedule(spec)
        assertEquals(rs(10_000), rows[5].prepayment)
        val fy25 = Loans.financialYear(spec, 2025)
        val fy26 = Loans.financialYear(spec, 2026)
        assertEquals(smallRef.take(6).sumOf { it.principal }, fy25.principal)
        assertEquals(rows.drop(6).sumOf { it.principal } + rs(10_000), fy26.principal)
        assertEquals(rs(1_00_000), fy25.principal + fy26.principal)
        assertEquals(Loans.totalInterest(spec), fy25.interest + fy26.interest)
    }

    @Test
    fun financialYearsAddUpToTheWholeLoan() {
        val spec = home.copy(
            prepayments = listOf(
                Prepayment(LocalDate.of(2026, 4, 20), rs(2_00_000)),
                Prepayment(LocalDate.of(2030, 3, 31), rs(50_000), Reduce.EMI),
            ),
        )
        val years = (2024..2046).map { Loans.financialYear(spec, it) }
        assertEquals(rs(10_00_000), years.sumOf { it.principal })
        assertEquals(Loans.totalInterest(spec), years.sumOf { it.interest })
        assertEquals(Loans.schedule(spec).sumOf { it.interest }, Loans.totalInterest(spec))
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun invalidInputIsRejected() {
        val start = LocalDate.of(2025, 1, 5)
        assertFailsWith<IllegalArgumentException> { Spec(0, 8.5, 12, start) }
        assertFailsWith<IllegalArgumentException> { Spec(rs(1_000), 8.5, 0, start) }
        assertFailsWith<IllegalArgumentException> { Spec(rs(1_000), -1.0, 12, start) }
        assertFailsWith<IllegalArgumentException> { Loans.emi(0, 8.5, 12) }
        assertFailsWith<IllegalArgumentException> { Loans.emi(rs(1_000), 8.5, 0) }
        assertFailsWith<IllegalArgumentException> { Loans.emi(rs(1_000), -0.5, 12) }
        assertFailsWith<IllegalArgumentException> { Prepayment(start, 0) }
        // ₹100 a month never repays ₹10,00,000 at 8.5%: the interest alone is ₹7,083.33.
        assertFailsWith<IllegalArgumentException> { Loans.schedule(home.copy(emi = rs(100))) }
    }
}
