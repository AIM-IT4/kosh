package com.amitjha.kosh.repo

import com.amitjha.kosh.finance.BudgetMath
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "Safe to spend until payday": what is left of this month's plan once repeating payments due before payday are set
 * aside, spread over the days left. The plan is the overall budget when there is one, otherwise this month's income,
 * otherwise what is usually spent in a month. It is never more than the bank, cash and wallet money left after card
 * bills, but savings sitting in the bank are not counted as spendable on their own.
 */
object SafeToSpend {

    enum class Basis { BUDGET, INCOME, USUAL }

    data class Result(
        val perDay: Paise,
        /** Left for the rest of the period (may be negative). */
        val available: Paise,
        val days: Int,
        val payday: LocalDate,
        val basis: Basis,
        /** The budget, income or usual monthly spending the plan starts from. */
        val plan: Paise,
        val spent: Paise,
        /** Repeating payments (subscriptions, EMIs, schedules) still to come before payday. */
        val upcoming: Paise,
        val upcomingCount: Int,
        /** The bank balance after card bills, not the plan, is what limits it. */
        val cashLimited: Boolean,
    )

    /** Pure calculation; see the class comment. Returns null when there is no plan to work from. */
    fun plan(
        today: LocalDate,
        payDay: Int,
        liquid: Paise,
        cardDues: Paise,
        upcoming: List<BudgetMath.Due>,
        overallBudget: Paise?,
        incomeThisPeriod: Paise,
        usualMonthlySpend: Paise?,
        spentThisPeriod: Paise,
    ): Result? {
        val payday = BudgetMath.nextPayday(today, payDay)
        val due = upcoming.filter { !it.date.isBefore(today) && it.date.isBefore(payday) }
        val upcomingTotal = due.sumOf { it.amount }
        val (basis, base) = when {
            overallBudget != null && overallBudget > 0 -> Basis.BUDGET to overallBudget
            incomeThisPeriod > 0 -> Basis.INCOME to incomeThisPeriod
            usualMonthlySpend != null && usualMonthlySpend > 0 -> Basis.USUAL to usualMonthlySpend
            else -> return null
        }
        val fromPlan = base - spentThisPeriod - upcomingTotal
        val fromCash = liquid - cardDues - upcomingTotal
        val available = minOf(fromPlan, fromCash)
        val days = ChronoUnit.DAYS.between(today, payday).toInt().coerceAtLeast(1)
        return Result(
            perDay = if (available > 0) available / days else 0L,
            available = available,
            days = days,
            payday = payday,
            basis = basis,
            plan = base,
            spent = spentThisPeriod,
            upcoming = upcomingTotal,
            upcomingCount = due.size,
            cashLimited = fromCash < fromPlan,
        )
    }

    suspend fun compute(
        accounts: List<AccountWithBalance>,
        bills: List<BillItem>,
        payDay: Int,
        recurring: RecurringRepository,
        budgets: List<BudgetStatus>,
        summary: MonthSummary?,
        usualMonthlySpend: Paise?,
        today: LocalDate,
    ): Result? {
        val cashAccounts = accounts.filter { !it.isPending && it.account.type != AccountType.CREDIT_CARD }
        if (cashAccounts.isEmpty() || summary == null) return null
        val payday = BudgetMath.nextPayday(today, payDay)
        val cardDues = bills.filter { !it.bill.paid && it.bill.totalDue > 0 && it.dueDate?.isBefore(payday) == true }.sumOf { it.bill.totalDue }
        val repeats = runCatching { recurring.upcoming(today, payday) }.getOrDefault(emptyList())
        return plan(
            today = today,
            payDay = payDay,
            liquid = cashAccounts.sumOf { it.balance },
            cardDues = cardDues,
            upcoming = repeats,
            overallBudget = budgets.firstOrNull { it.isOverall }?.limit,
            incomeThisPeriod = summary.income,
            usualMonthlySpend = usualMonthlySpend,
            spentThisPeriod = summary.expense,
        )
    }
}
