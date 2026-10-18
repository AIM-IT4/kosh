package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** How a monthly budget is tracking on [today]. */
data class BudgetProjection(
    val limit: Paise,
    val spent: Paise,
    val daysElapsed: Int,
    val daysInMonth: Int,
) {
    /** Month-end spend if the current daily pace continues. */
    val projected: Paise get() = if (daysElapsed == 0) spent else (spent.toDouble() / daysElapsed * daysInMonth).roundToLong()
    val remaining: Paise get() = limit - spent
    val usedFraction: Double get() = if (limit > 0) spent.toDouble() / limit else 0.0
    val isExceeded: Boolean get() = spent > limit
    val willExceed: Boolean get() = projected > limit
    /** Spend allowed per remaining day to stay within the limit (0 if already exceeded). */
    val dailyAllowance: Paise get() = max(0L, remaining) / max(1, daysInMonth - daysElapsed + 1)
}

object Budgets {
    fun project(limit: Paise, spent: Paise, today: LocalDate): BudgetProjection =
        BudgetProjection(limit, spent, today.dayOfMonth, today.lengthOfMonth())
}

/** A savings goal and what it takes to reach it. */
data class GoalPlan(
    val target: Paise,
    val saved: Paise,
    val monthsLeft: Int,
    /** Monthly saving needed to reach the target by the deadline, given the expected return. */
    val requiredMonthly: Paise,
    val reached: Boolean,
    val overdue: Boolean,
) {
    val progress: Double get() = if (target > 0) (saved.toDouble() / target).coerceIn(0.0, 1.0) else 1.0
    fun isOnTrack(monthlySaving: Paise): Boolean = reached || (!overdue && monthlySaving >= requiredMonthly)

    /** No month-end is left before the deadline, so what's missing is needed in one go, not per month. */
    val dueNow: Boolean get() = !reached && !overdue && monthsLeft == 0

    /** What is still missing. */
    val gap: Paise get() = (target - saved).coerceAtLeast(0)
}

object Goals {
    /**
     * Required end-of-month saving (an ordinary annuity) so that the current savings, growing at
     * [expectedAnnualReturnPct], plus the monthly instalments reach [target] in the months left.
     */
    fun plan(target: Paise, saved: Paise, deadline: LocalDate, today: LocalDate, expectedAnnualReturnPct: Double = 0.0): GoalPlan {
        val reached = saved >= target
        val overdue = !reached && deadline.isBefore(today)
        val months = monthsBetween(today, deadline)
        val required = when {
            reached -> 0L
            months <= 0 -> target - saved
            else -> requiredMonthlySip(target, saved, months, expectedAnnualReturnPct)
        }
        return GoalPlan(target, saved, months, required, reached, overdue)
    }

    /**
     * The monthly rate that compounds to [annualPct] a year: 12% a year is 0.949% a month. (12%/12 = 1% a month would
     * compound to 12.68% and understate the saving needed, by 8.6% over 20 years.)
     */
    fun monthlyRate(annualPct: Double): Double = (1 + annualPct / 100.0).pow(1.0 / 12) - 1

    fun requiredMonthlySip(target: Paise, saved: Paise, months: Int, annualReturnPct: Double): Paise {
        val i = monthlyRate(annualReturnPct)
        if (i == 0.0) return ceil((target - saved).toDouble() / months).toLong().coerceAtLeast(0)
        val growth = (1 + i).pow(months)
        val gap = target - saved * growth
        if (gap <= 0) return 0
        return ceil(gap * i / (growth - 1)).toLong()
    }

    /**
     * Month-ends left from [from] up to [to]: saving happens at the end of each month, so a part month at the end has
     * no saving in it (rounding it up asked for too little each month).
     */
    fun monthsBetween(from: LocalDate, to: LocalDate): Int {
        if (!to.isAfter(from)) return 0
        return ChronoUnit.MONTHS.between(from, to).toInt()
    }

    /** [price] after [months] of [inflationPct] a year. */
    fun grown(price: Paise, inflationPct: Double, months: Int): Paise =
        if (inflationPct == 0.0 || months <= 0) price else (price * (1 + inflationPct / 100.0).pow(months / 12.0)).roundToLong()
}

/** The optional loan a user is willing to take when buying a goal, and what it costs. */
object GoalLoan {

    /** The part of the target the user still has to save. */
    fun savingsTarget(target: Paise, loanAmount: Paise): Paise = (target - loanAmount.coerceIn(0, target)).coerceAtLeast(0)

    /** Standard EMI: P·r·(1+r)^n / ((1+r)^n − 1), with r the monthly rate; P/n when the rate is 0. */
    fun emi(principal: Paise, annualRatePct: Double, months: Int): Paise {
        if (principal <= 0 || months <= 0) return 0
        val r = annualRatePct / 100.0 / 12.0
        if (r == 0.0) return (principal.toDouble() / months).roundToLong()
        val f = (1 + r).pow(months)
        return (principal * r * f / (f - 1)).roundToLong()
    }

    /** Interest paid over the whole loan. */
    fun totalInterest(principal: Paise, annualRatePct: Double, months: Int): Paise =
        (emi(principal, annualRatePct, months) * months - principal).coerceAtLeast(0)
}

/**
 * The plan for a goal as the user set it up: the price grows by [inflationPct] a year until the deadline, a purchase
 * loan (a fixed amount) covers part of it, and the savings grow at the expected return. The Goals screen and the
 * insights both use this, so they always show the same numbers.
 */
fun Goals.planGoal(
    target: Paise,
    saved: Paise,
    deadline: LocalDate,
    today: LocalDate,
    expectedAnnualReturnPct: Double,
    loanAmount: Paise = 0,
    inflationPct: Double = 0.0,
): GoalPlan {
    val price = grown(target, inflationPct, monthsBetween(today, deadline))
    return plan(GoalLoan.savingsTarget(price, loanAmount), saved, deadline, today, expectedAnnualReturnPct)
}

/** Value after [months] of saving [monthly] at month end on top of [saved], growing at [annualReturnPct]. */
fun Goals.futureValue(saved: Paise, monthly: Paise, months: Int, annualReturnPct: Double): Paise {
    if (months <= 0) return saved
    val i = monthlyRate(annualReturnPct)
    if (i == 0.0) return saved + monthly * months
    val g = (1 + i).pow(months)
    return (saved * g + monthly * (g - 1) / i).roundToLong()
}

/** Months needed to reach [target] saving [monthly] a month, or null if it can't be reached within [maxMonths]. */
fun Goals.monthsToReach(target: Paise, saved: Paise, monthly: Paise, annualReturnPct: Double, maxMonths: Int = 600): Int? {
    if (saved >= target) return 0
    for (n in 1..maxMonths) if (futureValue(saved, monthly, n, annualReturnPct) >= target) return n
    return null
}

/** Where a goal stands against what the user usually has left over each month. */
enum class GoalStanding {
    REACHED, OVERDUE,
    /** Due before the next month-end: the gap is needed in one go. */
    DUE_NOW,
    ON_TRACK,
    /** Fits the surplus, with little room to spare. */
    TIGHT,
    OFF_TRACK,
    /** Not enough history to know the usual surplus yet. */
    UNKNOWN,
}

data class GoalAssessment(
    val plan: GoalPlan,
    val standing: GoalStanding,
    /**
     * The usual monthly surplus left for this goal over its whole run, after earlier goals take theirs while they
     * last; null without history.
     */
    val capacity: Paise?,
    /** What's free for it right now, while every earlier goal is still being saved for; null without history. */
    val freeNow: Paise? = capacity,
)

/** One judgement of every goal, used by the Goals screen and the insights alike. */
object GoalTracker {
    /** A goal is comfortably on track when it needs at most this share of the surplus left for it. */
    const val COMFORT_SHARE = 0.8

    /**
     * Shares the usual monthly [surplus] between [goals], earliest deadline first. An earlier goal holds back its
     * monthly saving only for the months it overlaps a later one, so a short goal doesn't starve a long goal for
     * years after it's done. A goal is on track when its monthly saving fits comfortably in what is free right now,
     * tight when it fits only just, or only once earlier goals finish, and off track when it doesn't fit even then.
     * Results are in deadline order. [deadlineOf] lets the insights pass shifted dates.
     */
    fun assess(
        goals: List<GoalInput>,
        surplus: Paise?,
        today: LocalDate,
        deadlineOf: (GoalInput) -> LocalDate = { it.deadline },
    ): List<Pair<GoalInput, GoalAssessment>> {
        val plans = goals.map { g ->
            g to Goals.planGoal(g.target, g.saved, deadlineOf(g), today, g.expectedReturnPct, g.loanAmount, g.inflationPct)
        }.sortedBy { deadlineOf(it.first) }
        val earlier = mutableListOf<GoalPlan>()
        return plans.map { (g, plan) ->
            val months = plan.monthsLeft
            val held = earlier.sumOf { e ->
                if (months <= 0) e.requiredMonthly else (e.requiredMonthly.toDouble() * minOf(e.monthsLeft, months) / months).roundToLong()
            }
            val capacity = surplus?.let { it - held }
            val freeNow = surplus?.let { it - earlier.sumOf { e -> e.requiredMonthly } }
            val standing = when {
                plan.reached -> GoalStanding.REACHED
                plan.overdue -> GoalStanding.OVERDUE
                plan.dueNow -> GoalStanding.DUE_NOW
                plan.requiredMonthly <= 0 -> GoalStanding.ON_TRACK
                capacity == null || freeNow == null -> GoalStanding.UNKNOWN
                freeNow > 0 && plan.requiredMonthly <= freeNow * COMFORT_SHARE -> GoalStanding.ON_TRACK
                capacity > 0 && plan.requiredMonthly <= capacity -> GoalStanding.TIGHT
                else -> GoalStanding.OFF_TRACK
            }
            if (!plan.reached && !plan.overdue && months > 0 && plan.requiredMonthly > 0) earlier += plan
            g to GoalAssessment(plan, standing, capacity, freeNow)
        }
    }
}
