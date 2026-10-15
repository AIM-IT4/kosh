package com.amitjha.kosh.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.system.measureTimeMillis

class TaxAndGoalSimulationTest {

    private fun rupees(r: Long) = r * 100

    // ------------------------------------------------------------------ tax

    @Test
    fun salaryOf1275LakhPaysNothingInTheNewRegime() {
        val b = Tax.compute(Tax.Regime.NEW, Tax.Inputs(grossSalary = rupees(12_75_000)))
        assertEquals(rupees(12_00_000), b.taxableIncome)
        assertEquals(0L, b.total)
    }

    @Test
    fun fifteenLakhSalaryOldVsNew() {
        val input = Tax.Inputs(grossSalary = rupees(15_00_000), sec80C = rupees(1_50_000))
        val c = Tax.compare(input)
        // New: taxable 14.25L → 20,000 + 40,000 + 33,750 = 93,750 + 4% cess = 97,500.
        assertEquals(rupees(97_500), c.new.total)
        // Old: taxable 13L → 12,500 + 1,00,000 + 90,000 = 2,02,500 + 4% cess = 2,10,600.
        assertEquals(rupees(2_10_600), c.old.total)
        assertEquals(Tax.Regime.NEW, c.better)
        assertEquals(rupees(1_13_100), c.saving)
    }

    @Test
    fun newRegimeMarginalReliefJustAboveTwelveLakh() {
        // Taxable 12.10L: slab tax 61,500 but tax may not exceed the ₹10,000 above 12L → 10,000 + 4% cess.
        val b = Tax.compute(Tax.Regime.NEW, Tax.Inputs(grossSalary = rupees(12_85_000)))
        assertEquals(rupees(12_10_000), b.taxableIncome)
        assertEquals(rupees(10_400), b.total)
    }

    @Test
    fun oldRegimeRebateUpToFiveLakh() {
        val b = Tax.compute(Tax.Regime.OLD, Tax.Inputs(grossSalary = rupees(5_50_000)))
        assertEquals(rupees(5_00_000), b.taxableIncome)
        assertEquals(0L, b.total)
    }

    @Test
    fun deductionCapsAndInterest() {
        val b = Tax.compute(
            Tax.Regime.OLD,
            Tax.Inputs(grossSalary = rupees(20_00_000), savingsInterest = rupees(15_000), sec80C = rupees(3_00_000), sec80CCD1B = rupees(80_000)),
        )
        // 50k std + 1.5L 80C + 50k 80CCD(1B) + 10k 80TTA.
        assertEquals(rupees(2_60_000), b.deductions)
    }

    @Test
    fun hraExemptionIsTheLeastOfThree() {
        // Basic 6L, HRA 3L, rent 3.6L, metro: min(3L, 3.6L − 60k = 3L, 50% × 6L = 3L) = 3L.
        assertEquals(rupees(3_00_000), Tax.hraExemption(rupees(6_00_000), rupees(3_00_000), rupees(3_60_000), metro = true))
        // Non-metro caps at 40% of basic.
        assertEquals(rupees(2_40_000), Tax.hraExemption(rupees(6_00_000), rupees(3_00_000), rupees(3_60_000), metro = false))
    }

    @Test
    fun toppingUp80cSavesAtTheMarginalRate() {
        val input = Tax.Inputs(grossSalary = rupees(15_00_000), sec80C = rupees(1_20_000))
        // Taxable income in the 30% slab: ₹30,000 more saves 30% + 4% cess = ₹9,360.
        assertEquals(rupees(9_360), Tax.oldRegimeSavingFrom(input, extra80C = rupees(30_000)))
    }

    // ------------------------------------------------------------------ goal simulation

    @Test
    fun zeroVolatilityMatchesThePlan() {
        val today = LocalDate.of(2026, 10, 15)
        val deadline = today.plusMonths(36)
        val plan = Goals.plan(rupees(10_00_000), rupees(1_00_000), deadline, today, 7.0)
        val r = GoalSimulation.simulate(
            GoalSimulation.Input(rupees(10_00_000), rupees(1_00_000), plan.monthsLeft, plan.requiredMonthly, 7.0, volatilityPct = 0.0),
            paths = 50,
        )
        assertEquals(1.0, r.probability, 0.0)
        // The plan's monthly amount is rounded up to the paisa, so the deterministic pot lands just on target.
        assertTrue(r.median >= rupees(10_00_000) && r.median - rupees(10_00_000) < rupees(50))
        assertTrue(kotlin.math.abs(r.monthlyForConfidence - plan.requiredMonthly) <= 1)
    }

    @Test
    fun probabilityRisesWithMonthlySaving() {
        fun p(monthly: Long) = GoalSimulation.simulate(
            GoalSimulation.Input(rupees(20_00_000), rupees(2_00_000), 60, rupees(monthly), 12.0), seed = 42,
        ).probability
        val ps = listOf(10_000L, 20_000L, 25_000L, 30_000L, 40_000L).map(::p)
        assertTrue("monotonic: $ps", ps.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(ps.first() < 0.2 && ps.last() > 0.9)
    }

    @Test
    fun reproducibleWithSeedAndConfidenceAmountHitsEightyPercent() {
        val input = GoalSimulation.Input(rupees(15_00_000), rupees(1_00_000), 48, rupees(20_000), 12.0)
        val a = GoalSimulation.simulate(input, seed = 7)
        val b = GoalSimulation.simulate(input, seed = 7)
        assertEquals(a, b)
        val at80 = GoalSimulation.simulate(input.copy(monthlySaving = a.monthlyForConfidence), seed = 7)
        assertEquals(0.80, at80.probability, 0.01)
    }

    @Test
    fun inflationRaisesTheTarget() {
        val r = GoalSimulation.simulate(GoalSimulation.Input(rupees(10_00_000), 0, 120, rupees(10_000), 7.0, inflationPct = 6.0), paths = 200)
        assertTrue(r.targetAtDeadline in rupees(17_80_000)..rupees(17_95_000))
    }

    @Test
    fun fiveThousandPathsOverTwentyYearsIsFast() {
        val input = GoalSimulation.Input(rupees(1_00_00_000), rupees(5_00_000), 240, rupees(30_000), 12.0)
        GoalSimulation.simulate(input) // warm up the JIT
        val ms = measureTimeMillis { GoalSimulation.simulate(input) }
        assertTrue("took $ms ms", ms < 1500)
    }
}
