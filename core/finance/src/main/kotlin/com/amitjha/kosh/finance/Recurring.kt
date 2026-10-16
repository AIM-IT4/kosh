package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Repeating payments (subscriptions, SIPs, rent, premiums) and their schedules.
 *
 * A broader cousin of the subscription insight in [InsightEngine]: besides monthly payments it finds weekly,
 * quarterly and yearly ones, accepts a price change on the latest payment, and returns the series themselves.
 */
object Recurring {

    enum class Cadence(val months: Int, val days: Int) { WEEKLY(0, 7), MONTHLY(1, 0), QUARTERLY(3, 0), YEARLY(12, 0) }

    data class Payment(
        val date: LocalDate,
        val amount: Paise,
        val merchant: String,
        val category: String,
        val accountId: Long? = null,
        val autoDebit: Boolean = false,
    )

    data class Series(
        /** Stable across runs: the normalised merchant and the cadence name, e.g. "netflix:MONTHLY". */
        val key: String,
        /** Display name from the latest payment. */
        val merchant: String,
        val category: String,
        val cadence: Cadence,
        val occurrences: Int,
        val lastDate: LocalDate,
        val nextDate: LocalDate,
        val lastAmount: Paise,
        /** The payment before the last. */
        val previousAmount: Paise?,
        /** Median of the series. */
        val typicalAmount: Paise,
        /** [typicalAmount] as a per-month cost (weekly ×52/12, quarterly /3, yearly /12), rounded. */
        val monthlyEquivalent: Paise,
        /** (last − previous) / previous × 100 when the change is at least 1% either way, else null. */
        val priceChangePct: Double?,
        /** Any payment in the series was an auto-debit / mandate. */
        val autoDebit: Boolean,
        /** [nextDate] is more than a grace period (3 days weekly, 7 otherwise) before today. */
        val overdue: Boolean,
    )

    /** Payments dated more than this many days before today are ignored. */
    private const val MAX_AGE_DAYS = 400L
    /** Every payment of a series but the last must be within this share of the series median. */
    private const val AMOUNT_TOLERANCE = 0.20
    /** A smaller move between the last two payments is not reported as a price change. */
    private const val PRICE_CHANGE_MIN_PCT = 1.0
    private val WHITESPACE = Regex("\\s+")

    /**
     * Finds repeating payments: same merchant (case/space-insensitive), regular gaps, similar amounts.
     *
     * - Payments dated more than 400 days before [today], with a blank merchant or a non-positive amount are ignored.
     * - The rest are grouped by merchant, trimmed and lowercased with inner whitespace collapsed.
     * - Within a merchant, consecutive payments form a run of a cadence while every gap between them fits it:
     *   WEEKLY 6–8 days, MONTHLY 26–35, QUARTERLY 85–97, YEARLY 350–380. Any other gap ends the run, so payments
     *   at irregular intervals never form a series, while a one-off payment to the same merchant only splits it.
     * - A run needs 3 payments for WEEKLY and MONTHLY and 2 for QUARTERLY and YEARLY. A MONTHLY run of 2 counts
     *   only when one of them was an auto-debit, since a mandate shows the payment is meant to repeat.
     * - Every payment but the last must be within 20% of the run's median amount. The last may differ by more:
     *   that is a price change, reported in [Series.priceChangePct].
     * - A merchant yields at most one series: the valid run with the most fitting gaps, the most recent on a tie.
     *
     * The next date keeps the series' day of month (or weekday). A last payment on the final day of a short
     * month after one on a later day (28 Feb after 31 Jan) is taken as clamped, so the series stays on the 31st.
     * Results are sorted by [Series.nextDate].
     */
    fun detect(payments: List<Payment>, today: LocalDate): List<Series> {
        val cutoff = today.minusDays(MAX_AGE_DAYS)
        return payments
            .filter { !it.date.isBefore(cutoff) && it.amount > 0 && it.merchant.isNotBlank() }
            .groupBy { normalize(it.merchant) }
            .mapNotNull { (name, group) -> bestSeries(name, group.sortedBy { it.date }, today) }
            .sortedWith(compareBy<Series> { it.nextDate }.thenBy { it.key })
    }

    /**
     * Next date after [after] for a schedule anchored on [anchor] (day of month / weekday kept; 31st clamps to
     * month end). The schedule starts on [anchor], so the anchor itself is returned when [after] is before it.
     */
    fun nextOccurrence(anchor: LocalDate, cadence: Cadence, after: LocalDate): LocalDate {
        if (after.isBefore(anchor)) return anchor
        // Jump to the last whole step at or before [after], then walk forward. Every date is computed from the
        // anchor rather than the previous date, so a clamped 28 Feb doesn't pull March back to the 28th.
        var k = if (cadence.months > 0) {
            ChronoUnit.MONTHS.between(anchor, after) / cadence.months
        } else {
            ChronoUnit.DAYS.between(anchor, after) / cadence.days
        }
        var date = step(anchor, cadence, k)
        while (!date.isAfter(after)) date = step(anchor, cadence, ++k)
        return date
    }

    /** Every scheduled date in [from]..[to] inclusive for a schedule anchored on [anchor] (none before the anchor). */
    fun occurrences(anchor: LocalDate, cadence: Cadence, from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var date = nextOccurrence(anchor, cadence, from.minusDays(1))
        while (!date.isAfter(to)) {
            out.add(date)
            date = nextOccurrence(anchor, cadence, date)
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun normalize(merchant: String): String = merchant.trim().lowercase().replace(WHITESPACE, " ")

    private fun step(anchor: LocalDate, cadence: Cadence, k: Long): LocalDate =
        if (cadence.months > 0) anchor.plusMonths(k * cadence.months) else anchor.plusDays(k * cadence.days)

    private fun gapDays(cadence: Cadence): LongRange = when (cadence) {
        Cadence.WEEKLY -> 6L..8L
        Cadence.MONTHLY -> 26L..35L
        Cadence.QUARTERLY -> 85L..97L
        Cadence.YEARLY -> 350L..380L
    }

    private fun minOccurrences(cadence: Cadence): Int = when (cadence) {
        Cadence.WEEKLY, Cadence.MONTHLY -> 3
        Cadence.QUARTERLY, Cadence.YEARLY -> 2
    }

    private fun graceDays(cadence: Cadence): Long = if (cadence == Cadence.WEEKLY) 3L else 7L

    /** The valid run with the most fitting gaps (the most recent on a tie), as a series. */
    private fun bestSeries(name: String, sorted: List<Payment>, today: LocalDate): Series? =
        Cadence.entries.flatMap { cadence -> runs(sorted, cadence).map { cadence to it } }
            .sortedWith(
                compareByDescending<Pair<Cadence, List<Payment>>> { it.second.size }.thenByDescending { it.second.last().date },
            )
            .firstNotNullOfOrNull { (cadence, run) -> toSeries(name, cadence, run, today) }

    /** Maximal stretches of two or more consecutive payments whose every gap fits [cadence]. */
    private fun runs(sorted: List<Payment>, cadence: Cadence): List<List<Payment>> {
        val gaps = gapDays(cadence)
        val out = mutableListOf<List<Payment>>()
        var start = 0
        for (i in 1..sorted.size) {
            if (i < sorted.size && ChronoUnit.DAYS.between(sorted[i - 1].date, sorted[i].date) in gaps) continue
            if (i - start >= 2) out.add(sorted.subList(start, i))
            start = i
        }
        return out
    }

    /** The run as a series, or null when it is too short or its amounts are too far apart. */
    private fun toSeries(name: String, cadence: Cadence, run: List<Payment>, today: LocalDate): Series? {
        val autoDebit = run.any { it.autoDebit }
        val needed = if (cadence == Cadence.MONTHLY && autoDebit) 2 else minOccurrences(cadence)
        if (run.size < needed) return null
        val median = median(run.map { it.amount })
        // The last payment is exempt: a deviation there is a price change, not a different payment.
        if (run.dropLast(1).any { abs(it.amount - median) > median * AMOUNT_TOLERANCE }) return null
        val last = run.last()
        val previous = run[run.size - 2]
        val typical = median.roundToLong()
        val changePct = (last.amount - previous.amount) * 100.0 / previous.amount
        val next = nextOccurrence(anchor(run, cadence), cadence, last.date)
        return Series(
            key = "$name:${cadence.name}",
            merchant = last.merchant.trim(),
            category = last.category,
            cadence = cadence,
            occurrences = run.size,
            lastDate = last.date,
            nextDate = next,
            lastAmount = last.amount,
            previousAmount = previous.amount,
            typicalAmount = typical,
            monthlyEquivalent = monthlyEquivalent(typical, cadence),
            priceChangePct = changePct.takeIf { abs(it) >= PRICE_CHANGE_MIN_PCT },
            autoDebit = autoDebit,
            overdue = next.plusDays(graceDays(cadence)).isBefore(today),
        )
    }

    /**
     * The date whose day of month the schedule keeps: normally the last payment. A last payment on the final day
     * of its month after one on a later day of month was clamped, so that later day (via its payment) anchors it.
     */
    private fun anchor(run: List<Payment>, cadence: Cadence): LocalDate {
        val last = run.last().date
        if (cadence.months == 0 || last.dayOfMonth != last.lengthOfMonth()) return last
        return run.lastOrNull { it.date.dayOfMonth > last.dayOfMonth }?.date ?: last
    }

    private fun monthlyEquivalent(amount: Paise, cadence: Cadence): Paise = when (cadence) {
        Cadence.WEEKLY -> amount * 52.0 / 12
        Cadence.MONTHLY -> amount.toDouble()
        Cadence.QUARTERLY -> amount / 3.0
        Cadence.YEARLY -> amount / 12.0
    }.roundToLong()

    private fun median(values: List<Paise>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
