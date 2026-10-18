package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** The days of the week salary isn't paid on. */
enum class PayWeekend(val label: String) {
    SAT_SUN("Saturday and Sunday"),
    SUN("Sunday only"),

    /** Sundays and the 2nd and 4th Saturdays, when Indian banks are closed. */
    BANK("Sundays, 2nd and 4th Saturdays");

    fun isOff(d: LocalDate): Boolean = when (this) {
        SAT_SUN -> d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        SUN -> d.dayOfWeek == DayOfWeek.SUNDAY
        BANK -> d.dayOfWeek == DayOfWeek.SUNDAY || (d.dayOfWeek == DayOfWeek.SATURDAY && (d.dayOfMonth - 1) / 7 + 1 in setOf(2, 4))
    }
}

/** One pay period: from [start] (a payday) up to the next payday. [month] is the month whose salary starts it. */
data class PayPeriod(val month: YearMonth, val start: LocalDate, val endExclusive: LocalDate)

/**
 * When salary arrives. It is due on [day] of each month; with [toWorkingDay] it comes on the last working day on or
 * before that, skipping [weekend] days and [holidays]: the 25th a Sunday and the 24th a Saturday gives Friday the 23rd,
 * and with holidays on the 23rd and 22nd, the 21st.
 *
 * [paid] are days salary actually arrived (read from bank SMS, see [learnPaydays]); one close to a month's due day
 * wins over the rule, so an early salary (before Diwali, say) or a late one still starts the right period.
 * A cycle due on the 1st is a calendar month and never moves.
 */
data class PayCycle(
    val day: Int,
    val toWorkingDay: Boolean = true,
    val weekend: PayWeekend = PayWeekend.SAT_SUN,
    val holidays: Set<LocalDate> = emptySet(),
    val paid: Set<LocalDate> = emptySet(),
) {
    /** The due day, kept to 1..28 like the setting, so every month has one. */
    val dueDay: Int get() = day.coerceIn(1, 28)

    /** Whether paydays can move off the due day. */
    val moves: Boolean get() = toWorkingDay && dueDay > 1

    fun due(month: YearMonth): LocalDate = month.atDay(dueDay)

    fun isWorkingDay(d: LocalDate): Boolean = !weekend.isOff(d) && d !in holidays

    /** The working day on or before [month]'s due day. */
    fun scheduled(month: YearMonth): LocalDate {
        var d = due(month)
        if (!moves) return d
        repeat(MAX_SHIFT_DAYS) {
            if (isWorkingDay(d)) return d
            d = d.minusDays(1)
        }
        return due(month)
    }

    /** The salary [paid] for [month], if one arrived near its due day. */
    fun arrived(month: YearMonth): LocalDate? {
        if (!moves) return null
        val due = due(month)
        return paid.filter { inWindow(it, due) }.minOrNull()
    }

    /** When [month]'s salary arrives: the day it actually came, else the scheduled working day. */
    fun payday(month: YearMonth): LocalDate = arrived(month) ?: scheduled(month)

    /** The pay period holding [date]. */
    fun periodContaining(date: LocalDate): PayPeriod {
        // A payday can move into the month before its due day, so start one month later and walk back.
        var m = YearMonth.from(date).plusMonths(1)
        while (payday(m).isAfter(date)) m = m.minusMonths(1)
        return period(m)
    }

    fun period(month: YearMonth): PayPeriod = PayPeriod(month, payday(month), payday(month.plusMonths(1)))

    /**
     * The next payday from [today]: today itself when salary is due today and hasn't arrived yet, otherwise the first
     * one after today.
     */
    fun nextPayday(today: LocalDate): LocalDate {
        var m = YearMonth.from(today).minusMonths(1)
        while (true) {
            val p = payday(m)
            if (p.isAfter(today) || (p == today && arrived(m) == null)) return p
            m = m.plusMonths(1)
        }
    }

    /** Why [month]'s payday isn't its due day, e.g. "25 Oct is a Sunday"; null when it isn't moved. */
    fun reason(month: YearMonth, holidayNames: Map<LocalDate, String> = emptyMap()): String? {
        val due = due(month)
        val pay = payday(month)
        if (pay == due) return null
        if (arrived(month) != null) return "salary arrived on ${short(pay)}"
        val skipped = generateSequence(due) { it.minusDays(1) }.takeWhile { it.isAfter(pay) }.toList()
        return skipped.joinToString(", ") { d ->
            val why = holidayNames[d] ?: if (d in holidays) "a holiday" else "a ${d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
            "${short(d)} is $why"
        }
    }

    /**
     * The days salary arrived, from salary credits ([date] and amount): for each month the largest credit near its
     * due day (a bonus or arrears on another day doesn't move the payday), earliest on a tie.
     */
    fun learnPaydays(credits: List<Pair<LocalDate, Paise>>): Set<LocalDate> {
        if (dueDay <= 1) return emptySet()
        return credits.mapNotNull { (d, amount) -> monthFor(d)?.let { Triple(it, d, amount) } }
            .groupBy { it.first }
            .values
            .map { inMonth -> inMonth.sortedWith(compareByDescending<Triple<YearMonth, LocalDate, Paise>> { it.third }.thenBy { it.second }).first().second }
            .toSet()
    }

    /** The month whose salary a credit on [date] would be, if it is near a due day. */
    private fun monthFor(date: LocalDate): YearMonth? =
        listOf(YearMonth.from(date).minusMonths(1), YearMonth.from(date), YearMonth.from(date).plusMonths(1))
            .firstOrNull { inWindow(date, due(it)) }

    private fun inWindow(d: LocalDate, due: LocalDate): Boolean =
        !d.isBefore(due.minusDays(EARLY_DAYS)) && !d.isAfter(due.plusDays(LATE_DAYS))

    private fun short(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}"

    companion object {
        /** A salary this many days before its due day still counts as that month's. */
        const val EARLY_DAYS = 10L

        /** ...and this many days after. */
        const val LATE_DAYS = 5L

        /** Never move a payday further back than this (a long run of holidays). */
        const val MAX_SHIFT_DAYS = 10
    }
}

/**
 * Public holidays salary isn't paid on: the 17 central government gazetted holidays for 2026 and 2027 (DoPT lists),
 * and the fixed national days every year. State and company holidays are added by the user.
 */
object IndianHolidays {
    private val listed: Map<LocalDate, String> = buildMap {
        fun add(y: Int, m: Int, d: Int, name: String) = put(LocalDate.of(y, m, d), name)
        add(2026, 3, 4, "Holi"); add(2026, 3, 21, "Id-ul-Fitr"); add(2026, 3, 26, "Ram Navami")
        add(2026, 3, 31, "Mahavir Jayanti"); add(2026, 4, 3, "Good Friday"); add(2026, 5, 1, "Buddha Purnima")
        add(2026, 5, 27, "Bakrid"); add(2026, 6, 26, "Muharram"); add(2026, 8, 26, "Milad-un-Nabi")
        add(2026, 9, 4, "Janmashtami"); add(2026, 10, 20, "Dussehra"); add(2026, 11, 8, "Diwali")
        add(2026, 11, 24, "Guru Nanak Jayanti")
        add(2027, 3, 10, "Id-ul-Fitr"); add(2027, 3, 23, "Holi"); add(2027, 3, 26, "Good Friday")
        add(2027, 4, 15, "Ram Navami"); add(2027, 4, 19, "Mahavir Jayanti"); add(2027, 5, 17, "Bakrid")
        add(2027, 5, 20, "Buddha Purnima"); add(2027, 6, 16, "Muharram"); add(2027, 8, 25, "Janmashtami")
        add(2027, 10, 9, "Dussehra"); add(2027, 10, 29, "Diwali"); add(2027, 11, 14, "Guru Nanak Jayanti")
    }

    private fun fixed(year: Int): Map<LocalDate, String> = mapOf(
        LocalDate.of(year, 1, 26) to "Republic Day",
        LocalDate.of(year, 8, 15) to "Independence Day",
        LocalDate.of(year, 10, 2) to "Gandhi Jayanti",
        LocalDate.of(year, 12, 25) to "Christmas",
    )

    /** Every listed holiday from 2020 to 2040, with its name. */
    val named: Map<LocalDate, String> by lazy { (2020..2040).flatMap { fixed(it).entries }.associate { it.toPair() } + listed }

    val dates: Set<LocalDate> by lazy { named.keys }
}
