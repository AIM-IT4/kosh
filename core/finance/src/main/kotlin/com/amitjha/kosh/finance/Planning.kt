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

    fun requiredMonthlySip(target: Paise, saved: Paise, months: Int, annualReturnPct: Double): Paise {
        val i = annualReturnPct / 100.0 / 12.0
        if (i == 0.0) return ceil((target - saved).toDouble() / months).toLong().coerceAtLeast(0)
        val growth = (1 + i).pow(months)
        val gap = target - saved * growth
        if (gap <= 0) return 0
        return ceil(gap * i / (growth - 1)).toLong()
    }

    /** Whole months from [from] to [to], rounding a partial month up. */
    fun monthsBetween(from: LocalDate, to: LocalDate): Int {
        if (!to.isAfter(from)) return 0
        val whole = ChronoUnit.MONTHS.between(from, to).toInt()
        return if (from.plusMonths(whole.toLong()).isBefore(to)) whole + 1 else whole
    }
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
 * The plan for a goal as the user set it up: a purchase loan reduces what has to be saved, and the
 * savings grow at the expected return. The Goals screen and the insights both use this, so they
 * always show the same numbers.
 */
fun Goals.planGoal(
    target: Paise,
    saved: Paise,
    deadline: LocalDate,
    today: LocalDate,
    expectedAnnualReturnPct: Double,
    loanAmount: Paise = 0,
): GoalPlan = plan(GoalLoan.savingsTarget(target, loanAmount), saved, deadline, today, expectedAnnualReturnPct)

/** Value after [months] of saving [monthly] at month end on top of [saved], growing at [annualReturnPct]. */
fun Goals.futureValue(saved: Paise, monthly: Paise, months: Int, annualReturnPct: Double): Paise {
    if (months <= 0) return saved
    val i = annualReturnPct / 100.0 / 12.0
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
