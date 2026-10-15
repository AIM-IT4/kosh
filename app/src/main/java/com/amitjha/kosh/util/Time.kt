package com.amitjha.kosh.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Time conversions in the device's time zone. */
object Time {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now(zone)

    fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate): Long = startOfDay(date.plusDays(1)) - 1

    fun toDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun epochDayOf(millis: Long): Long = toDate(millis).toEpochDay()
}

/**
 * A budgeting period. With `monthStartDay = 1` it is a calendar month; with e.g. 25 it runs from the
 * 25th to the 24th (handy when salary arrives near month end). `start` is inclusive, `endExclusive` exclusive.
 */
data class Period(val start: LocalDate, val endExclusive: LocalDate) {
    val startMillis: Long get() = Time.startOfDay(start)
    val endMillis: Long get() = Time.startOfDay(endExclusive) - 1
    val lastDay: LocalDate get() = endExclusive.minusDays(1)
    val lengthDays: Int get() = (endExclusive.toEpochDay() - start.toEpochDay()).toInt()

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    fun previous(): Period {
        val prevStart = start.minusMonths(1)
        return Period(prevStart, start)
    }

    fun next(): Period = Period(endExclusive, endExclusive.plusMonths(1))

    /**
     * The month this period is labelled with. One rule everywhere (Activity, Home charts, insights):
     * a period starting on day 1–15 is labelled with its starting month, one starting on day 16–28 with
     * the next month (25 Sep – 24 Oct is "October"). It depends only on the start day, so every period of
     * a given setup gets a distinct, consecutive label.
     */
    val label: YearMonth get() = labelFor(start)

    companion object {
        fun containing(date: LocalDate, monthStartDay: Int = 1): Period {
            val day = monthStartDay.coerceIn(1, 28)
            val thisMonthStart = date.withDayOfMonth(day)
            val start = if (date.isBefore(thisMonthStart)) thisMonthStart.minusMonths(1) else thisMonthStart
            return Period(start, start.plusMonths(1))
        }

        /** The period labelled [month] (the inverse of [label]). */
        fun of(month: YearMonth, monthStartDay: Int = 1): Period {
            val day = monthStartDay.coerceIn(1, 28)
            val start = if (day > LABEL_NEXT_MONTH_AFTER_DAY) month.minusMonths(1).atDay(day) else month.atDay(day)
            return Period(start, start.plusMonths(1))
        }

        /** Start days above this label the period with the following month. Insights use the same rule. */
        const val LABEL_NEXT_MONTH_AFTER_DAY = 15

        fun labelFor(start: LocalDate): YearMonth =
            YearMonth.from(start).let { if (start.dayOfMonth > LABEL_NEXT_MONTH_AFTER_DAY) it.plusMonths(1) else it }
    }
}
