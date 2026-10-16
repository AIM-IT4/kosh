package com.amitjha.kosh.ui.home

import com.amitjha.kosh.data.NetWorthSnapshotEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.designsystem.components.BarGroup
import com.amitjha.kosh.designsystem.components.ChartPoint
import com.amitjha.kosh.domain.MoneyIn
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/** Everything the Home charts draw, computed off the main thread from transactions and history. */
data class HomeCharts(
    /** Cumulative spend per elapsed day of the current period. */
    val paceCurrent: List<Paise> = emptyList(),
    /** Cumulative spend per day of the previous period. */
    val pacePrevious: List<Paise> = emptyList(),
    val periodDays: Int = 30,
    val projected: Paise? = null,
    val sixMonths: List<BarGroup> = emptyList(),
    val savingsRates: List<String> = emptyList(),
    /** Weeks (oldest first) × 7 days (Monday first); null for days after today. */
    val heatWeeks: List<List<Paise?>> = emptyList(),
    val heatLabels: List<List<String>> = emptyList(),
    /** Net worth by day, oldest first. */
    val netWorth: List<Pair<LocalDate, Paise>> = emptyList(),
) {
    val hasPace: Boolean get() = paceCurrent.any { it > 0 } || pacePrevious.any { it > 0 }
    val hasSixMonths: Boolean get() = sixMonths.count { it.income > 0 || it.expense > 0 } >= 2
    val hasHeat: Boolean get() = heatWeeks.flatten().any { (it ?: 0L) > 0 }

    /** Net worth points within the last [days] days, as chart points. */
    fun netWorthPoints(days: Int, today: LocalDate = Time.today()): List<ChartPoint> {
        val from = today.minusDays(days.toLong())
        return netWorth.filter { !it.first.isBefore(from) }.map { (d, v) ->
            ChartPoint("${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}", v)
        }
    }

    companion object {
        const val HEAT_WEEKS = 5

        fun build(
            transactions: List<TransactionEntity>,
            period: Period,
            today: LocalDate,
            history: List<NetWorthSnapshotEntity> = emptyList(),
        ): HomeCharts {
            val counted = transactions.filter { SmsMapping.countsInStats(it) }
            val expenseByDay = counted.filter { it.type == TxnType.EXPENSE }
                .groupBy { Time.toDate(it.timestamp) }.mapValues { (_, t) -> t.sumOf { it.amount } }
            val backByDay = counted.filter(MoneyIn::isGivenBack)
                .groupBy { Time.toDate(it.timestamp) }.mapValues { (_, t) -> t.sumOf { it.amount } }

            // Running spending for the period, with refunds and cashback taken off on the day they arrive.
            fun cumulative(p: Period, upTo: LocalDate): List<Paise> {
                var acc = 0L
                val days = generateSequence(p.start) { it.plusDays(1) }.takeWhile { !it.isAfter(upTo) && it.isBefore(p.endExclusive) }
                return days.map { d -> acc += (expenseByDay[d] ?: 0L) - (backByDay[d] ?: 0L); acc.coerceAtLeast(0) }.toList()
            }

            val current = cumulative(period, today)
            val previousPeriod = period.previous()
            val previous = cumulative(previousPeriod, previousPeriod.lastDay)
            val projected = current.lastOrNull()?.takeIf { current.size >= 3 }?.let { spent ->
                (spent.toDouble() / current.size * period.lengthDays).toLong()
            }

            val periods = (5 downTo 0).map { back -> (0 until back).fold(period) { p, _ -> p.previous() } }
            val bars = periods.map { p ->
                val inP = counted.filter { Time.toDate(it.timestamp) in p }
                BarGroup(
                    p.label.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    inP.filter(MoneyIn::isEarned).sumOf { it.amount },
                    MoneyIn.netSpent(inP.filter { it.type == TxnType.EXPENSE }, inP.filter(MoneyIn::isGivenBack)),
                )
            }
            val rates = bars.map { b -> if (b.income > 0) "${((b.income - b.expense) * 100.0 / b.income).roundToInt()}%" else "–" }

            val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((HEAT_WEEKS - 1).toLong())
            val weeks = (0 until HEAT_WEEKS).map { w ->
                (0 until 7).map { d -> firstMonday.plusDays(w * 7L + d) }
            }
            val heat = weeks.map { week -> week.map { d -> if (d.isAfter(today)) null else expenseByDay[d] ?: 0L } }
            val labels = weeks.map { week ->
                week.map { d ->
                    "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
                }
            }

            return HomeCharts(
                paceCurrent = current,
                pacePrevious = previous,
                periodDays = period.lengthDays,
                projected = projected,
                sixMonths = bars,
                savingsRates = rates,
                heatWeeks = heat,
                heatLabels = labels,
                netWorth = history.sortedBy { it.epochDay }.map { LocalDate.ofEpochDay(it.epochDay) to it.total },
            )
        }
    }
}
