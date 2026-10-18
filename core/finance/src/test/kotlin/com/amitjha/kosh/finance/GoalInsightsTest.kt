package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Goal advice follows the goal exactly as planned: loan, expected return, deadline and the user's real surplus. */
class GoalInsightsTest {

    private val today = LocalDate.of(2026, 10, 15)
    private fun rs(x: Double) = Money.fromRupees(x)

    /** Three complete months, each with [income] in and [spend] out. */
    private fun history(income: Double, spend: Double) = (1..3).flatMap { m ->
        val d = today.minusMonths(m.toLong()).withDayOfMonth(10)
        listOf(
            InsightTxn(d, rs(income), TxnType.INCOME, "salary", "Employer", 1, false),
            InsightTxn(d.plusDays(2), rs(spend), TxnType.EXPENSE, "rent", "Landlord", 1, false),
        )
    }

    private fun goal(
        name: String,
        target: Double,
        saved: Double,
        months: Long,
        ret: Double = 0.0,
        loan: Double = 0.0,
    ) = GoalInput(name, rs(target), rs(saved), today.plusMonths(months), expectedReturnPct = ret, loanAmount = rs(loan), id = 7)

    private fun run(goals: List<GoalInput>, txns: List<InsightTxn> = history(100_000.0, 60_000.0)) =
        InsightEngine.generate(InsightInput(today = today, transactions = txns, goals = goals))

    // Ids carry the goal and its standing ("goal_7_on_track"), so a dismissal doesn't hide a later change.
    private fun List<Insight>.goal(name: String) = first { it.id.startsWith("goal_") && name in it.title }

    @Test
    fun loanReducesWhatHasToBeSaved() {
        val noLoan = goal("Car", 1_000_000.0, 0.0, 10)
        val withLoan = goal("Car", 1_000_000.0, 0.0, 10, loan = 600_000.0)
        val need = Goals.planGoal(noLoan.target, 0, noLoan.deadline, today, 0.0, 0).requiredMonthly
        val needLoan = Goals.planGoal(withLoan.target, 0, withLoan.deadline, today, 0.0, withLoan.loanAmount).requiredMonthly
        assertEquals(rs(100_000.0), need)
        assertEquals(rs(40_000.0), needLoan)
        val out = run(listOf(withLoan)).goal("Car")
        assertTrue(out.message, out.message.contains("₹40,000"))
        assertTrue(out.message, out.message.contains("₹6,00,000 loan"))
    }

    @Test
    fun expectedReturnLowersTheMonthlyNeed() {
        val flat = Goals.planGoal(rs(500_000.0), rs(100_000.0), today.plusMonths(24), today, 0.0)
        val growing = Goals.planGoal(rs(500_000.0), rs(100_000.0), today.plusMonths(24), today, 12.0)
        assertTrue(growing.requiredMonthly < flat.requiredMonthly)
    }

    @Test
    fun insightUsesTheSameNumbersAsTheGoalsScreen() {
        val g = goal("House", 2_000_000.0, 300_000.0, 30, ret = 8.0, loan = 1_200_000.0)
        val plan = Goals.planGoal(g.target, g.saved, g.deadline, today, g.expectedReturnPct, g.loanAmount)
        val insight = run(listOf(g)).goal("House")
        val needed = insight.metrics.first { it.label == "Needed/month" }.value
        assertEquals(Money.compact(plan.requiredMonthly), needed)
        assertEquals(Money.compact(plan.target), insight.metrics.first { it.label == "To save" }.value)
        assertEquals("8.0%", insight.metrics.first { it.label == "Expected return" }.value)
        assertNotNull(insight.chart)
        assertEquals(InsightRoute.GOALS, insight.action?.route)
        assertEquals("7", insight.action?.argument)
    }

    @Test
    fun withoutHistoryItSaysWhatIsNeededInsteadOfOffTrack() {
        val out = run(listOf(goal("Trip", 120_000.0, 0.0, 12)), txns = emptyList()).goal("Trip")
        assertEquals(Severity.TIP, out.severity)
        assertTrue(out.title, out.title.contains("save ₹10,000 a month"))
        assertFalse(out.title.contains("off track"))
    }

    @Test
    fun onTrackTightAndOffTrackFollowTheSurplus() {
        // Surplus ₹40,000 a month.
        assertEquals(Severity.GOOD, run(listOf(goal("A", 120_000.0, 0.0, 12))).goal("A").severity) // ₹10K
        assertEquals(Severity.TIP, run(listOf(goal("B", 420_000.0, 0.0, 12))).goal("B").severity) // ₹35K, tight
        val off = run(listOf(goal("C", 1_200_000.0, 0.0, 12))).goal("C") // ₹1L
        assertEquals(Severity.WARNING, off.severity)
        assertTrue(off.message, off.message.contains("move the date to"))
        assertTrue(off.message, off.message.contains("loan"))
    }

    @Test
    fun earlierGoalsGetFirstCallOnTheSurplus() {
        // Surplus ₹40,000. The earlier goal needs ₹30,000, leaving ₹10,000 for the later one, which needs ₹20,000.
        val first = goal("First", 180_000.0, 0.0, 6)
        val second = goal("Second", 480_000.0, 0.0, 24)
        val out = run(listOf(second, first))
        assertEquals(Severity.GOOD, out.goal("First").severity) // ₹30K of ₹40K
        assertEquals(Severity.WARNING, out.goal("Second").severity)
        assertTrue(out.goal("Second").message.contains("₹10,000"))
    }

    @Test
    fun reachedAndOverdue() {
        assertEquals("Goal reached: Phone", run(listOf(goal("Phone", 50_000.0, 60_000.0, 2))).goal("Phone").title)
        val overdue = GoalInput("Bike", rs(100_000.0), rs(40_000.0), today.minusDays(3))
        val out = run(listOf(overdue)).goal("Bike")
        assertEquals(Severity.ALERT, out.severity)
        assertTrue(out.message.contains("₹60,000"))
        assertTrue(out.message, out.message.contains("finish around"))
    }
}
