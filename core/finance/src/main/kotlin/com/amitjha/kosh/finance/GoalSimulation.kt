package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.util.SplittableRandom
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Monte Carlo view of a savings goal: how likely the plan is to reach the target by the deadline when
 * returns are uncertain.
 *
 * Each month the pot grows by a lognormal factor whose mean equals the deterministic monthly growth
 * `1 + i` ([Goals.monthlyRate]) used by [Goals.requiredMonthlySip], then the monthly saving is added at month end (an
 * ordinary annuity, as in the plan). With zero volatility the simulation therefore reproduces [Goals.plan] exactly.
 * Saving exactly the plan's amount therefore succeeds in a little under half the paths; the useful figure is the
 * saving needed for [CONFIDENCE].
 *
 * The pot after month t is linear in the monthly saving m: `A_t + m · B_t`, where A is the current savings
 * grown along the path and B the grown sum of one-rupee instalments. One pass therefore gives the success
 * probability for any m, and the saving needed for a given confidence is a percentile of
 * `(target − A) / B` across paths — no bisection needed.
 */
object GoalSimulation {

    const val DEFAULT_PATHS = 5000
    const val CONFIDENCE = 0.80
    private const val FAN_POINTS = 24

    data class Input(
        /** The price of the goal today (before any loan). */
        val target: Paise,
        val saved: Paise,
        val months: Int,
        /** Monthly saving put towards the goal. */
        val monthlySaving: Paise,
        /** Expected annual return of the money set aside, %. Picks the risk profile too. */
        val expectedReturnPct: Double,
        /** Annual volatility, %; null derives it from [expectedReturnPct] via [volatilityFor]. */
        val volatilityPct: Double? = null,
        /** Price inflation of the target, % a year (0 = target fixed in today's rupees). */
        val inflationPct: Double = 0.0,
        /** A purchase loan covering part of the price; a fixed amount, so it doesn't grow with inflation. */
        val loanAmount: Paise = 0,
    )

    /** One point of the fan chart: month index and the 10th / 50th / 90th percentile of the pot. */
    data class FanPoint(val month: Int, val p10: Paise, val p50: Paise, val p90: Paise, val target: Paise)

    data class Result(
        /** Chance (0–1) of reaching the target by the deadline at [Input.monthlySaving]. */
        val probability: Double,
        val median: Paise,
        val p10: Paise,
        val p90: Paise,
        /** Target at the deadline (grown by inflation when set). */
        val targetAtDeadline: Paise,
        /** Monthly saving that reaches the target in [CONFIDENCE] of the paths. */
        val monthlyForConfidence: Paise,
        val fan: List<FanPoint>,
        val volatilityPct: Double,
    )

    /**
     * Risk profile from the expected return: savings account ≈3% (σ 0.5%), FD/debt ≈7% (σ 2%),
     * equity ≈12% (σ 16%), interpolated linearly between and extended above 12%.
     */
    fun volatilityFor(expectedReturnPct: Double): Double = when {
        expectedReturnPct <= 3.0 -> 0.5
        expectedReturnPct <= 7.0 -> 0.5 + (expectedReturnPct - 3.0) / 4.0 * 1.5
        expectedReturnPct <= 12.0 -> 2.0 + (expectedReturnPct - 7.0) / 5.0 * 14.0
        else -> (16.0 + (expectedReturnPct - 12.0) * 1.5).coerceAtMost(30.0)
    }

    /** A stable seed per goal so the probability doesn't flicker between redraws. */
    fun seedFor(vararg parts: Any?): Long = parts.fold(1125899906842597L) { h, p -> 31 * h + (p?.hashCode() ?: 0) }

    fun simulate(input: Input, paths: Int = DEFAULT_PATHS, seed: Long = seedFor(input)): Result {
        val months = input.months.coerceAtLeast(0)
        val sigma = input.volatilityPct ?: volatilityFor(input.expectedReturnPct)
        val targetAt = toSave(input, months)
        val m = input.monthlySaving.toDouble()

        if (months == 0) {
            val p = if (input.saved >= targetAt) 1.0 else 0.0
            val need = (targetAt - input.saved).coerceAtLeast(0)
            return Result(p, input.saved, input.saved, input.saved, targetAt, need,
                listOf(FanPoint(0, input.saved, input.saved, input.saved, targetAt)), sigma)
        }

        val sM = sigma / 100.0 / sqrt(12.0)
        val mu = ln(1 + Goals.monthlyRate(input.expectedReturnPct)) - 0.5 * sM * sM
        val sampleMonths = sampleMonths(months)
        val aAt = Array(sampleMonths.size) { DoubleArray(paths) }
        val bAt = Array(sampleMonths.size) { DoubleArray(paths) }
        val finalA = DoubleArray(paths)
        val finalB = DoubleArray(paths)
        val gauss = Gaussian(SplittableRandom(seed))
        for (p in 0 until paths) {
            var a = input.saved.toDouble()
            var b = 0.0
            var next = 0
            if (sampleMonths[0] == 0) { aAt[0][p] = a; bAt[0][p] = b; next = 1 }
            for (t in 1..months) {
                val g = if (sM == 0.0) exp(mu) else exp(mu + sM * gauss.next())
                a *= g
                b = b * g + 1.0
                if (next < sampleMonths.size && sampleMonths[next] == t) { aAt[next][p] = a; bAt[next][p] = b; next++ }
            }
            finalA[p] = a
            finalB[p] = b
        }

        val finals = DoubleArray(paths) { finalA[it] + m * finalB[it] }
        val success = finals.count { it + 0.5 >= targetAt }
        // Saving each path needs to finish exactly on target; the confidence level is a percentile of that.
        val need = DoubleArray(paths) { max(0.0, (targetAt - finalA[it]) / finalB[it]) }.also { it.sort() }
        val monthlyFor = ceil(need[((paths - 1) * CONFIDENCE).toInt()]).toLong()

        finals.sort()
        val fan = sampleMonths.mapIndexed { i, month ->
            val v = DoubleArray(paths) { aAt[i][it] + m * bAt[i][it] }.also { it.sort() }
            val tgt = toSave(input, month)
            FanPoint(month, pct(v, 0.10), pct(v, 0.50), pct(v, 0.90), tgt)
        }
        return Result(
            probability = success.toDouble() / paths,
            median = pct(finals, 0.50),
            p10 = pct(finals, 0.10),
            p90 = pct(finals, 0.90),
            targetAtDeadline = targetAt,
            monthlyForConfidence = monthlyFor,
            fan = fan,
            volatilityPct = sigma,
        )
    }

    /** What has to be saved by month [months]: the grown price less the loan. */
    private fun toSave(input: Input, months: Int): Paise =
        GoalLoan.savingsTarget(Goals.grown(input.target, input.inflationPct, months), input.loanAmount)

    /**
     * The outlook for [goal] at its [plan]'s monthly saving, with the same paths and seed wherever it is shown (the
     * Goals screen and the insights), so both quote the same figures.
     */
    fun forGoal(goal: GoalInput, plan: GoalPlan): Result = simulate(
        Input(goal.target, goal.saved, plan.monthsLeft, plan.requiredMonthly, goal.expectedReturnPct,
            inflationPct = goal.inflationPct, loanAmount = goal.loanAmount),
        seed = seedFor(goal.id, goal.target),
    )

    private fun sampleMonths(months: Int): IntArray {
        val n = minOf(FAN_POINTS, months)
        return (0..n).map { (it.toLong() * months / n).toInt() }.distinct().toIntArray()
    }

    private fun pct(sorted: DoubleArray, q: Double): Paise = sorted[((sorted.size - 1) * q).toInt()].roundToLong()

    /** Marsaglia polar method; holds its own spare so each run is independent and reproducible. */
    private class Gaussian(private val rng: SplittableRandom) {
        private var spare = Double.NaN
        fun next(): Double {
            if (!spare.isNaN()) { val s = spare; spare = Double.NaN; return s }
            while (true) {
                val u = rng.nextDouble() * 2 - 1
                val v = rng.nextDouble() * 2 - 1
                val s = u * u + v * v
                if (s > 0 && s < 1) {
                    val f = sqrt(-2 * ln(s) / s)
                    spare = v * f
                    return u * f
                }
            }
        }
    }
}
