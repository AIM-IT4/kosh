package com.amitjha.kosh.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Goal maths that the Goals screen and the insights share. */
class GoalAccuracyTest {

    private val today = LocalDate.of(2026, 10, 18)
    private fun rupees(r: Long) = r * 100

    @Test
    fun aYearlyReturnCompoundsMonthly() {
        assertEquals(0.009489, Goals.monthlyRate(12.0), 1e-6)
        // 12 months at the monthly rate give back exactly 12% a year.
        assertEquals(1.12, Math.pow(1 + Goals.monthlyRate(12.0), 12.0), 1e-12)
    }

    @Test
    fun aPartMonthAtTheEndHasNoSavingInIt() {
        // 32 days away: one month-end (18 Nov) before the 19 Nov deadline, so all of it that month.
        val plan = Goals.plan(rupees(100_000), 0, LocalDate.of(2026, 11, 19), today)
        assertEquals(1, plan.monthsLeft)
        assertEquals(rupees(100_000), plan.requiredMonthly)
        // Due before the next month-end: needed in one go, not overdue.
        val soon = Goals.plan(rupees(100_000), rupees(40_000), LocalDate.of(2026, 11, 10), today)
        assertTrue(soon.dueNow)
        assertFalse(soon.overdue)
        assertEquals(rupees(60_000), soon.gap)
    }

    @Test
    fun thePriceRisesBeforeTheLoanComesOff() {
        // ₹10L price, ₹6L loan, 5 years at 6%: 10L × 1.338 − 6L = ₹7.38L to save (not 4L × 1.338 = ₹5.35L).
        val plan = Goals.planGoal(rupees(1_000_000), 0, today.plusMonths(60), today, 0.0, loanAmount = rupees(600_000), inflationPct = 6.0)
        assertEquals(738_225.0, plan.target / 100.0, 1.0)
        // The simulation with no volatility asks for the same monthly saving.
        val g = GoalInput("Car", rupees(1_000_000), 0, today.plusMonths(60), loanAmount = rupees(600_000), inflationPct = 6.0, id = 1)
        val sim = GoalSimulation.simulate(
            GoalSimulation.Input(g.target, 0, plan.monthsLeft, plan.requiredMonthly, 0.0, volatilityPct = 0.0, inflationPct = 6.0, loanAmount = g.loanAmount),
        )
        assertEquals(plan.target, sim.targetAtDeadline)
        assertEquals(plan.requiredMonthly.toDouble(), sim.monthlyForConfidence.toDouble(), 100.0)
    }

    @Test
    fun aShortGoalHoldsBackItsSavingOnlyWhileItRuns() {
        // ₹40K free a month. A needs ₹30K/month for 2 months; B ₹15K/month for 24 months. B used to get ₹10K for all
        // 24 months and show off track; A only overlaps 2 of them.
        val a = GoalInput("Phone", rupees(60_000), 0, today.plusMonths(2), id = 1)
        val b = GoalInput("Trip", rupees(360_000), 0, today.plusMonths(24), id = 2)
        val result = GoalTracker.assess(listOf(b, a), rupees(40_000), today).associate { (g, r) -> g.id to r }
        assertEquals(GoalStanding.ON_TRACK, result.getValue(1).standing)
        assertEquals(rupees(37_500), result.getValue(2).capacity)
        assertEquals(GoalStanding.ON_TRACK, result.getValue(2).standing)
        assertEquals(GoalStanding.UNKNOWN, GoalTracker.assess(listOf(b), null, today).single().second.standing)
    }

    @Test
    fun goalInsightsHaveDistinctIdsEvenForHindiNames() {
        val txns = listOf(
            InsightTxn(today.minusMonths(1), rupees(100_000), com.amitjha.kosh.model.TxnType.INCOME, "salary", "Acme", 1),
            InsightTxn(today.minusMonths(1), rupees(40_000), com.amitjha.kosh.model.TxnType.EXPENSE, "rent", "Landlord", 1),
        )
        val goals = listOf(
            GoalInput("घर", rupees(500_000), 0, today.plusYears(2), id = 1),
            GoalInput("शादी", rupees(500_000), 0, today.plusYears(3), id = 2),
        )
        val ids = InsightEngine.generate(InsightInput(today = today, transactions = txns, goals = goals))
            .filter { it.id.startsWith("goal_") }.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun theSurplusLeavesOutSipsAndMoneyThatIsNotIncome() {
        val last = today.minusMonths(1)
        val txns = listOf(
            InsightTxn(last, rupees(100_000), com.amitjha.kosh.model.TxnType.INCOME, "salary", "Acme", 1),
            // A friend paying back isn't income.
            InsightTxn(last, rupees(150_000), com.amitjha.kosh.model.TxnType.INCOME, "other_income", "Ravi", 1, earned = false),
            InsightTxn(last, rupees(50_000), com.amitjha.kosh.model.TxnType.EXPENSE, "rent", "Landlord", 1),
            // A ₹25K SIP is saving, not spending.
            InsightTxn(last, rupees(25_000), com.amitjha.kosh.model.TxnType.EXPENSE, "investment", "Zerodha", 1),
        )
        // ₹1L − ₹50K = ₹50K free; the ₹30K goal fits comfortably (was ₹1L + ₹1.5L − ₹75K, or tight at ₹25K).
        val goal = GoalInput("Bike", rupees(30_000 * 12), 0, today.plusMonths(12), id = 7)
        val insight = InsightEngine.generate(InsightInput(today = today, transactions = txns, goals = listOf(goal)))
            .first { it.id.startsWith("goal_7_") }
        assertEquals("goal_7_on_track", insight.id)
        assertTrue(insight.metrics.any { it.label == "Your surplus/month" && it.value.contains("50") })
    }
}
