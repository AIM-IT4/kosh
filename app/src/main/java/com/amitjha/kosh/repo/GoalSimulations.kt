package com.amitjha.kosh.repo

import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.prefs.PlanningPrefs
import com.amitjha.kosh.finance.GoalLoan
import com.amitjha.kosh.finance.GoalPlan
import com.amitjha.kosh.finance.GoalSimulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

/** A goal's plan plus how likely it is to succeed with uncertain returns. */
data class GoalOutlook(
    val goalId: Long,
    val plan: GoalPlan,
    /** Monthly saving the probability is computed for (the plan's required amount). */
    val monthlySaving: Long,
    val inflationPct: Double,
    val result: GoalSimulation.Result,
)

/** Runs (and caches) the Monte Carlo outlook for every goal. Seeded per goal so numbers are stable. */
class GoalSimulations(private val goals: GoalRepository, private val prefs: PlanningPrefs) {

    private val cache = ConcurrentHashMap<List<Any>, GoalSimulation.Result>()

    fun observe(): Flow<Map<Long, GoalOutlook>> =
        combine(goals.observeStatuses(), prefs.goalInflation) { statuses, inflation -> statuses to inflation }
            .mapLatest { (statuses, inflation) ->
                statuses.filter { !it.plan.reached && it.plan.monthsLeft > 0 }.associate { s ->
                    val infl = inflation[s.goal.id] ?: 0.0
                    s.goal.id to outlook(s.goal, s.plan, infl)
                }
            }
            .flowOn(Dispatchers.Default)

    suspend fun setInflation(goalId: Long, pct: Double) = prefs.setGoalInflation(goalId, pct)

    fun outlook(goal: GoalEntity, plan: GoalPlan, inflationPct: Double): GoalOutlook {
        val target = GoalLoan.savingsTarget(goal.target, goal.loanAmount)
        val monthly = plan.requiredMonthly
        val key = listOf(goal.id, target, goal.saved, plan.monthsLeft, monthly, goal.expectedReturnPct, inflationPct)
        val result = cache.getOrPut(key) {
            GoalSimulation.simulate(
                GoalSimulation.Input(target, goal.saved, plan.monthsLeft, monthly, goal.expectedReturnPct, inflationPct = inflationPct),
                seed = GoalSimulation.seedFor(goal.id, goal.createdAt),
            )
        }
        if (cache.size > 200) cache.clear()
        return GoalOutlook(goal.id, plan, monthly, inflationPct, result)
    }
}
