package com.amitjha.kosh.mail

import java.time.LocalDate

/** A date found in text, with its character range. */
internal data class FoundDate(val date: LocalDate, val range: IntRange)

/** Finds the date formats Indian card issuers use in e-mails. Day-first is assumed for numeric dates. */
internal object TextDates {
    private val months = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9, "oct" to 10,
        "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
    )
    private const val MON =
        "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

    // 2025-01-31
    private val iso = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")
    // 31/01/2025, 31-01-2025, 31.01.2025, 31/01/25
    private val numeric = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})\\b")
    // 31-Jan-2025, 31 Jan 2025, 31st January, 2025, 31-Jan-25, 31 Jan
    private val dayMonth = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?[\\s-]*$MON\\.?,?[\\s-]*(\\d{4}|\\d{2}(?!\\d))?\\b")
    // January 31, 2025 / Jan 31 2025
    private val monthDay = Regex("(?i)\\b$MON\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b")

    /** All dates in [text] in order of appearance; [reference] supplies the year when it is missing. */
    fun findAll(text: String, reference: LocalDate?): List<FoundDate> {
        val found = ArrayList<FoundDate>()
        fun add(range: IntRange, date: LocalDate?) {
            if (date != null && found.none { it.range.first <= range.last && range.first <= it.range.last }) {
                found.add(FoundDate(date, range))
            }
        }
        iso.findAll(text).forEach { m ->
            add(m.range, make(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))
        }
        monthDay.findAll(text).forEach { m ->
            val month = months[m.groupValues[1].lowercase()] ?: return@forEach
            add(m.range, make(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()))
        }
        dayMonth.findAll(text).forEach { m ->
            val month = months[m.groupValues[2].lowercase()] ?: return@forEach
            val day = m.groupValues[1].toInt()
            val yearText = m.groupValues[3]
            val date = if (yearText.isNotEmpty()) {
                make(year(yearText), month, day)
            } else {
                inferYear(day, month, reference)
            }
            add(m.range, date)
        }
        numeric.findAll(text).forEach { m ->
            var day = m.groupValues[1].toInt()
            var month = m.groupValues[2].toInt()
            if (month > 12 && day <= 12) day = month.also { month = day }
            add(m.range, make(year(m.groupValues[3]), month, day))
        }
        return found.sortedBy { it.range.first }
    }

    private fun year(text: String): Int = text.toInt().let { if (text.length == 2) 2000 + it else it }

    private fun inferYear(day: Int, month: Int, reference: LocalDate?): LocalDate? {
        val ref = reference ?: return null
        val candidate = make(ref.year, month, day) ?: return null
        return if (candidate.isBefore(ref.minusDays(60))) candidate.plusYears(1) else candidate
    }

    private fun make(year: Int, month: Int, day: Int): LocalDate? =
        if (year in 2000..2100) runCatching { LocalDate.of(year, month, day) }.getOrNull() else null
}
