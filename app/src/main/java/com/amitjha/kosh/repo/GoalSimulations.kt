package com.amitjha.kosh.repo

import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.prefs.PlanningPrefs
import com.amitjha.kosh.finance.GoalPlan
import com.amitjha.kosh.finance.GoalSimulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

/** A goal's plan plus how its savings could turn out with uncertain returns. */
data class GoalOutlook(
    val goalId: Long,
    val plan: GoalPlan,
    /** Monthly saving the outlook is simulated at: the plan's required amount. */
    val monthlySaving: Long,
    val inflationPct: Double,
    val result: GoalSimulation.Result,
)

/** Runs (and caches) the Monte Carlo outlook for every goal, with the same paths and seed as the insights. */
class GoalSimulations(private val goals: GoalRepository, private val prefs: PlanningPrefs) {

    private val cache = ConcurrentHashMap<List<Any?>, GoalSimulation.Result>()

    fun observe(): Flow<Map<Long, GoalOutlook>> =
        goals.observeStatuses()
            .mapLatest { statuses ->
                statuses.filter { !it.plan.reached && !it.plan.overdue && it.plan.monthsLeft > 0 }
                    .associate { s -> s.goal.id to outlook(s.goal, s.plan, s.inflationPct) }
            }
            .flowOn(Dispatchers.Default)

    suspend fun setInflation(goalId: Long, pct: Double) = prefs.setGoalInflation(goalId, pct)

    fun outlook(goal: GoalEntity, plan: GoalPlan, inflationPct: Double): GoalOutlook {
        val input = goal.toInput(inflationPct)
        val key = listOf(goal.id, goal.target, goal.saved, goal.loanAmount, plan.monthsLeft, plan.requiredMonthly, goal.expectedReturnPct, inflationPct)
        val result = cache.getOrPut(key) { GoalSimulation.forGoal(input, plan) }
        if (cache.size > 200) cache.clear()
        return GoalOutlook(goal.id, plan, plan.requiredMonthly, inflationPct, result)
    }
}
