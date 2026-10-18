package com.amitjha.kosh.repo

import com.amitjha.kosh.finance.BudgetMath
import com.amitjha.kosh.finance.PayCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SafeToSpendTest {

    private val today = LocalDate.of(2026, 10, 15)
    // Salary on the 25th exactly (25 Oct 2026 is a Sunday; see weekendPaydayComesEarlier): 10 days away.
    private val payDay = PayCycle(25, toWorkingDay = false)
    private fun rupees(r: Long) = r * 100

    @Test
    fun savingsInTheBankDoNotCountAsSpendable() {
        // ₹6 lakh in the bank used to give ₹59,000 a day; the ₹50,000 budget is what counts.
        val r = SafeToSpend.plan(
            today, payDay, liquid = rupees(600_000), cardDues = 0,
            upcoming = listOf(BudgetMath.Due(today.plusDays(3), rupees(1_000), "Netflix")),
            overallBudget = rupees(50_000), incomeThisPeriod = rupees(190_000), usualMonthlySpend = rupees(60_000),
            spentThisPeriod = rupees(20_000),
        )!!
        assertEquals(SafeToSpend.Basis.BUDGET, r.basis)
        assertEquals(rupees(29_000), r.available)
        assertEquals(10, r.days)
        assertEquals(rupees(2_900), r.perDay)
        assertFalse(r.cashLimited)
    }

    @Test
    fun theBankBalanceAfterCardBillsCapsThePlan() {
        val r = SafeToSpend.plan(
            today, payDay, liquid = rupees(10_000), cardDues = rupees(8_000), upcoming = emptyList(),
            overallBudget = rupees(50_000), incomeThisPeriod = 0, usualMonthlySpend = null, spentThisPeriod = 0,
        )!!
        assertTrue(r.cashLimited)
        assertEquals(rupees(2_000), r.available)
        assertEquals(rupees(200), r.perDay)
    }

    @Test
    fun withoutABudgetItUsesIncomeThenUsualSpending() {
        val income = SafeToSpend.plan(today, payDay, rupees(500_000), 0, emptyList(), null, rupees(100_000), rupees(60_000), rupees(40_000))!!
        assertEquals(SafeToSpend.Basis.INCOME, income.basis)
        assertEquals(rupees(60_000), income.available)

        val usual = SafeToSpend.plan(today, payDay, rupees(500_000), 0, emptyList(), null, 0, rupees(60_000), rupees(40_000))!!
        assertEquals(SafeToSpend.Basis.USUAL, usual.basis)
        assertEquals(rupees(20_000), usual.available)

        assertNull(SafeToSpend.plan(today, payDay, rupees(500_000), 0, emptyList(), null, 0, null, rupees(40_000)))
    }

    @Test
    fun overspendingShowsNothingLeftAndPaymentsAfterPaydayAreIgnored() {
        val r = SafeToSpend.plan(
            today, payDay, liquid = rupees(200_000), cardDues = 0,
            upcoming = listOf(BudgetMath.Due(LocalDate.of(2026, 10, 26), rupees(9_000), "EMI after payday")),
            overallBudget = rupees(30_000), incomeThisPeriod = 0, usualMonthlySpend = null, spentThisPeriod = rupees(35_000),
        )!!
        assertEquals(0L, r.upcoming)
        assertEquals(rupees(-5_000), r.available)
        assertEquals(0L, r.perDay)
    }

    @Test
    fun salaryMinusSavingsTargetIsWhatIsLeftToSpendUntilPayday() {
        // ₹1,00,000 salary, save ₹20,000; ₹30,000 spent since payday, ₹2,000 still due before the next one.
        val r = SafeToSpend.plan(
            today, payDay, liquid = rupees(400_000), cardDues = 0,
            upcoming = listOf(BudgetMath.Due(today.plusDays(2), rupees(2_000), "SIP")),
            overallBudget = rupees(90_000), incomeThisPeriod = rupees(100_000), usualMonthlySpend = null,
            spentThisPeriod = rupees(30_000), salary = rupees(100_000), savingsTarget = rupees(20_000),
        )!!
        assertEquals(SafeToSpend.Basis.SALARY, r.basis)
        assertEquals(rupees(20_000), r.savings)
        assertEquals(rupees(48_000), r.available)
        assertEquals(rupees(4_800), r.perDay)
    }

    @Test
    fun theSalaryPlanNeedsATargetAndPrefersWhatTheUserEntered() {
        // No savings target yet: the budget is used.
        val noTarget = SafeToSpend.plan(today, payDay, rupees(400_000), 0, emptyList(), rupees(50_000), 0, null, 0, salary = rupees(100_000))!!
        assertEquals(SafeToSpend.Basis.BUDGET, noTarget.basis)
        assertEquals(rupees(120_000), SafeToSpend.salaryFor(rupees(120_000), rupees(100_000), rupees(95_000)))
        assertEquals(rupees(100_000), SafeToSpend.salaryFor(null, rupees(100_000), rupees(95_000)))
        assertEquals(rupees(95_000), SafeToSpend.salaryFor(null, 0, rupees(95_000)))
        assertNull(SafeToSpend.salaryFor(null, 0, null))
    }

    @Test
    fun weekendPaydayComesEarlier() {
        // Salary due Sunday 25 Oct arrives Friday 23 Oct, so the money has to last 8 days, not 10.
        val r = SafeToSpend.plan(
            today, PayCycle(25), liquid = rupees(600_000), cardDues = 0, upcoming = emptyList(),
            overallBudget = rupees(50_000), incomeThisPeriod = 0, usualMonthlySpend = null, spentThisPeriod = rupees(10_000),
        )!!
        assertEquals(LocalDate.of(2026, 10, 23), r.payday)
        assertEquals(8, r.days)
        assertEquals(rupees(5_000), r.perDay)
    }
}
