package com.amitjha.kosh.repo

import com.amitjha.kosh.finance.BudgetMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SafeToSpendTest {

    private val today = LocalDate.of(2026, 10, 15)
    private val payDay = 25 // next payday 25 Oct: 10 days away
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
}
