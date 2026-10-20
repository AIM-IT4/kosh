package com.amitjha.kosh.repo

import com.amitjha.kosh.finance.BudgetMath
import com.amitjha.kosh.finance.PayCycle
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "Left to spend until payday". With a salary and a savings target it is
 * salary − savings target − spent since payday − payments still due before the next payday.
 * Without a savings target it falls back to the overall budget, then this month's income, then what is usually
 * spent in a month. It is never more than the bank, cash and wallet money left after card bills, but savings sitting
 * in the bank are not counted as spendable on their own.
 */
object SafeToSpend {

    enum class Basis { SALARY, BUDGET, INCOME, USUAL }

    data class Result(
        /** Left for the rest of the period, until payday (may be negative). */
        val available: Paise,
        /** [available] spread over the days left, as a guide. */
        val perDay: Paise,
        val days: Int,
        val payday: LocalDate,
        val basis: Basis,
        /** What the plan starts from: the salary, budget, income or usual monthly spending. */
        val plan: Paise,
        /** Savings set aside from the salary (0 unless [basis] is SALARY). */
        val savings: Paise,
        val spent: Paise,
        /** Repeating payments (subscriptions, EMIs, schedules) still to come before payday. */
        val upcoming: Paise,
        val upcomingCount: Int,
        /** The bank balance after card bills, not the plan, is what limits it. */
        val cashLimited: Boolean,
    )

    /** Pure calculation; see the object comment. Returns null when there is no plan to work from. */
    fun plan(
        today: LocalDate,
        cycle: PayCycle,
        liquid: Paise,
        cardDues: Paise,
        upcoming: List<BudgetMath.Due>,
        overallBudget: Paise?,
        incomeThisPeriod: Paise,
        usualMonthlySpend: Paise?,
        spentThisPeriod: Paise,
        salary: Paise? = null,
        savingsTarget: Paise? = null,
    ): Result? {
        val payday = cycle.nextPayday(today)
        val due = upcoming.filter { !it.date.isBefore(today) && it.date.isBefore(payday) }
        val upcomingTotal = due.sumOf { it.amount }
        val salaryPlan = salary != null && salary > 0 && savingsTarget != null
        val basis: Basis
        val base: Paise
        when {
            salaryPlan -> { basis = Basis.SALARY; base = salary!! }
            overallBudget != null && overallBudget > 0 -> { basis = Basis.BUDGET; base = overallBudget }
            incomeThisPeriod > 0 -> { basis = Basis.INCOME; base = incomeThisPeriod }
            usualMonthlySpend != null && usualMonthlySpend > 0 -> { basis = Basis.USUAL; base = usualMonthlySpend }
            else -> return null
        }
        val savings = if (basis == Basis.SALARY) savingsTarget!!.coerceIn(0, base) else 0L
        val fromPlan = base - savings - spentThisPeriod - upcomingTotal
        val fromCash = liquid - cardDues - upcomingTotal
        val available = minOf(fromPlan, fromCash)
        val days = ChronoUnit.DAYS.between(today, payday).toInt().coerceAtLeast(1)
        return Result(
            available = available,
            perDay = if (available > 0) available / days else 0L,
            days = days,
            payday = payday,
            basis = basis,
            plan = base,
            savings = savings,
            spent = spentThisPeriod,
            upcoming = upcomingTotal,
            upcomingCount = due.size,
            cashLimited = fromCash < fromPlan,
        )
    }

    /**
     * The salary to plan with: what the user entered, else the salary credited this period, else the latest salary
     * credit Kosh has seen recently.
     */
    fun salaryFor(entered: Paise?, salaryThisPeriod: Paise, latestSalaryCredit: Paise?): Paise? =
        entered?.takeIf { it > 0 } ?: salaryThisPeriod.takeIf { it > 0 } ?: latestSalaryCredit?.takeIf { it > 0 }

    suspend fun compute(
        accounts: List<AccountWithBalance>,
        bills: List<BillItem>,
        cycle: PayCycle,
        recurring: RecurringRepository,
        budgets: List<BudgetStatus>,
        summary: MonthSummary?,
        usualMonthlySpend: Paise?,
        salary: Paise?,
        savingsTarget: Paise?,
        today: LocalDate,
    ): Result? {
        val cashAccounts = accounts.filter { it.counted && it.account.type != AccountType.CREDIT_CARD }
        if (cashAccounts.isEmpty() || summary == null) return null
        val payday = cycle.nextPayday(today)
        val cardDues = bills.filter { !it.bill.paid && it.bill.totalDue > 0 && it.dueDate?.isBefore(payday) == true }.sumOf { it.bill.totalDue }
        val repeats = runCatching { recurring.upcoming(today, payday) }.getOrDefault(emptyList())
        return plan(
            today = today,
            cycle = cycle,
            liquid = cashAccounts.sumOf { it.balance },
            cardDues = cardDues,
            upcoming = repeats,
            overallBudget = budgets.firstOrNull { it.isOverall }?.limit,
            incomeThisPeriod = summary.income,
            usualMonthlySpend = usualMonthlySpend,
            spentThisPeriod = summary.expense,
            salary = salary,
            savingsTarget = savingsTarget,
        )
    }

    const val SALARY_CATEGORY = "salary"
}
