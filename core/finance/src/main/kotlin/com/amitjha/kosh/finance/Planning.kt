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

/**
 * One pay period's money, as goals see it: [earned] income, everything [spent], the part of that [invested] (SIPs and
 * the like, which is saved, not spent) and refunds and cashback [givenBack].
 */
data class PeriodMoney(
    val label: String,
    val earned: Paise,
    val spent: Paise,
    val invested: Paise = 0,
    val givenBack: Paise = 0,
) {
    /** Income less real spending: what was left to save. */
    val free: Paise get() = earned - (spent - invested - givenBack).coerceAtLeast(0)

    val hasData: Boolean get() = earned != 0L || spent != 0L
}

/** How much the user usually has free each month for goals. */
object GoalSurplus {
    /**
     * The usual monthly surplus: the median of the periods' free money, so one unusual period (a big purchase, a salary
     * credited a day early into the period before) doesn't decide it the way an average would. When the user entered
     * their take-home [statedSalary], a period counts at least that much income: salary is what they plan with, and an
     * SMS Kosh didn't read as salary shouldn't make every goal look out of reach. Periods with no data are skipped;
     * null when none has any.
     */
    fun usual(periods: List<PeriodMoney>, statedSalary: Paise? = null): Paise? {
        val free = adjusted(periods, statedSalary).filter { it.hasData }.map { it.free }.sorted()
        if (free.isEmpty()) return null
        val mid = free.size / 2
        return if (free.size % 2 == 1) free[mid] else (free[mid - 1] + free[mid]) / 2
    }

    /** The periods as [usual] counts them: income raised to [statedSalary] where it was less. */
    fun adjusted(periods: List<PeriodMoney>, statedSalary: Paise?): List<PeriodMoney> {
        val salary = statedSalary?.takeIf { it > 0 } ?: return periods
        return periods.map { if (it.hasData && it.earned < salary) it.copy(earned = salary) else it }
    }
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
    /**
     * Where its savings should be by now on an even path from when it was set up to its deadline; null when it's too
     * new to say. Saved at least this much, it is on track whatever the surplus says.
     */
    val scheduledByNow: Paise? = null,
) {
    /** Saved at or past where the plan says it should be by now. */
    val ahead: Boolean get() = scheduledByNow != null && plan.saved >= scheduledByNow
}

/** One judgement of every goal, used by the Goals screen and the insights alike. */
object GoalTracker {
    /** A goal is comfortably on track when it needs at most this share of the surplus left for it. */
    const val COMFORT_SHARE = 0.8

    /**
     * Shares the usual monthly [surplus] between [goals], earliest deadline first. An earlier goal holds back its
     * monthly saving only for the months it overlaps a later one, so a short goal doesn't starve a long goal for
     * years after it's done. A goal is on track when its monthly saving fits comfortably in what is free right now,
     * tight when it fits only just, or only once earlier goals finish, and off track when it doesn't fit even then.
     *
     * What the user says they save each month ([plannedSaving], e.g. ₹30K out of every salary) counts when it's more
     * than the surplus Kosh sees, as savings that leave the tracked accounts (an RD, PPF, a bank Kosh doesn't track)
     * look like spending. And a goal whose savings are already where an even plan says they should be by now is on
     * track, whatever the surplus. Results are in deadline order. [deadlineOf] lets the insights pass shifted dates.
     */
    fun assess(
        goals: List<GoalInput>,
        surplus: Paise?,
        today: LocalDate,
        deadlineOf: (GoalInput) -> LocalDate = { it.deadline },
        plannedSaving: Paise? = null,
    ): List<Pair<GoalInput, GoalAssessment>> {
        val available = listOfNotNull(surplus, plannedSaving?.takeIf { it > 0 }).maxOrNull()
        val plans = goals.map { g ->
            g to Goals.planGoal(g.target, g.saved, deadlineOf(g), today, g.expectedReturnPct, g.loanAmount, g.inflationPct)
        }.sortedBy { deadlineOf(it.first) }
        val earlier = mutableListOf<GoalPlan>()
        return plans.map { (g, plan) ->
            val months = plan.monthsLeft
            val held = earlier.sumOf { e ->
                if (months <= 0) e.requiredMonthly else (e.requiredMonthly.toDouble() * minOf(e.monthsLeft, months) / months).roundToLong()
            }
            val capacity = available?.let { it - held }
            val freeNow = available?.let { it - earlier.sumOf { e -> e.requiredMonthly } }
            val scheduled = scheduledByNow(g, plan, deadlineOf(g), today)
            val standing = when {
                plan.reached -> GoalStanding.REACHED
                plan.overdue -> GoalStanding.OVERDUE
                plan.dueNow -> GoalStanding.DUE_NOW
                plan.requiredMonthly <= 0 -> GoalStanding.ON_TRACK
                scheduled != null && plan.saved >= scheduled -> GoalStanding.ON_TRACK
                capacity == null || freeNow == null -> GoalStanding.UNKNOWN
                freeNow > 0 && plan.requiredMonthly <= freeNow * COMFORT_SHARE -> GoalStanding.ON_TRACK
                capacity > 0 && plan.requiredMonthly <= capacity -> GoalStanding.TIGHT
                else -> GoalStanding.OFF_TRACK
            }
            if (!plan.reached && !plan.overdue && months > 0 && plan.requiredMonthly > 0) earlier += plan
            g to GoalAssessment(plan, standing, capacity, freeNow, scheduled)
        }
    }

    /**
     * Where [g]'s savings should be by [today] on an even path from when it was set up to [deadline]: the amount to save
     * times the share of its time gone. Null for a goal less than a month old (or without a start date), or with nothing
     * saved yet, where "ahead" means nothing.
     */
    internal fun scheduledByNow(g: GoalInput, plan: GoalPlan, deadline: LocalDate, today: LocalDate): Paise? {
        val start = g.createdAt ?: return null
        if (plan.saved <= 0) return null
        val total = Goals.monthsBetween(start, deadline)
        val gone = Goals.monthsBetween(start, today)
        if (total <= 0 || gone < 1) return null
        return (plan.target.toDouble() * minOf(gone, total) / total).roundToLong()
    }
}
