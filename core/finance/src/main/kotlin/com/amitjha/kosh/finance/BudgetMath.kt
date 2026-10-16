package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Budget arithmetic beyond a single month: rollover, safe-to-spend until payday and zero-based assignment. */
object BudgetMath {

    /**
     * Rollover: unspent budget carries into the next period, overspending reduces it. [history] holds (limit, spent) for
     * each completed period since rollover was turned on, oldest first. The carry is cumulative but clamped after each
     * period to −limit..+limit of that period, so it never exceeds one period's budget either way.
     */
    fun rolloverCarry(history: List<Pair<Paise, Paise>>): Paise =
        history.fold(0L) { carry, (limit, spent) ->
            val cap = limit.coerceAtLeast(0)
            (carry + limit - spent).coerceIn(-cap, cap)
        }

    /** Next pay date: [payDay] of this month if still ahead (or today), else of next month; clamped to month end. */
    fun nextPayday(today: LocalDate, payDay: Int): LocalDate {
        val month = YearMonth.from(today)
        val thisMonth = dayOf(month, payDay)
        return if (thisMonth.isBefore(today)) dayOf(month.plusMonths(1), payDay) else thisMonth
    }

    data class Due(val date: LocalDate, val amount: Paise, val label: String)

    data class SafeToSpend(
        /** Money in bank, cash and wallet accounts. */
        val spendable: Paise,
        /** Bills and recurring payments dated today..payday − 1. */
        val dueBeforePayday: Paise,
        /** Savings the user still means to set aside this period. */
        val reserved: Paise,
        /** spendable − dueBeforePayday − reserved (may be negative). */
        val available: Paise,
        /** Days from today until payday, at least 1. */
        val days: Int,
        /** available / days, rounded down; 0 when available < 0. */
        val perDay: Paise,
        val payday: LocalDate,
        /** The dues counted, by date. */
        val dues: List<Due>,
    )

    /**
     * What can be spent each day until the next payday once the dues before it and [reserved] savings are set
     * aside. Dues dated before today, or on or after payday (the salary covers those), are not counted.
     */
    fun safeToSpend(today: LocalDate, payDay: Int, spendable: Paise, dues: List<Due>, reserved: Paise = 0): SafeToSpend {
        val payday = nextPayday(today, payDay)
        val counted = dues.filter { !it.date.isBefore(today) && it.date.isBefore(payday) }.sortedBy { it.date }
        val due = counted.sumOf { it.amount }
        val available = spendable - due - reserved
        val days = ChronoUnit.DAYS.between(today, payday).toInt().coerceAtLeast(1)
        return SafeToSpend(
            spendable = spendable,
            dueBeforePayday = due,
            reserved = reserved,
            available = available,
            days = days,
            // Rounded down, so the daily amounts never add up to more than is available.
            perDay = if (available > 0) available / days else 0L,
            payday = payday,
            dues = counted,
        )
    }

    data class Envelope(val income: Paise, val assigned: Paise, val toAssign: Paise)

    /** Zero-based view: income of the period minus what budgets assign (toAssign < 0 means over-assigned). */
    fun envelope(income: Paise, budgetLimits: List<Paise>): Envelope {
        val assigned = budgetLimits.sum()
        return Envelope(income, assigned, income - assigned)
    }

    private fun dayOf(month: YearMonth, day: Int): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))
}
