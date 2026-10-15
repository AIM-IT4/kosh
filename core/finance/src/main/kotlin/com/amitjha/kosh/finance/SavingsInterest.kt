package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** How often the bank credits savings interest. Quarterly is the Indian norm. */
enum class CreditFrequency { QUARTERLY, MONTHLY;

    companion object {
        fun fromName(name: String?): CreditFrequency = entries.firstOrNull { it.name == name } ?: QUARTERLY
    }
}

/** A credit period: interest accrues from [start] to [end] (inclusive) and is credited at its end. */
data class CreditPeriod(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    fun previous(frequency: CreditFrequency): CreditPeriod = SavingsInterest.periodContaining(start.minusDays(1), frequency)

    companion object {
        /** Calendar quarters (Jan–Mar, Apr–Jun, Jul–Sep, Oct–Dec) or calendar months. */
        fun containing(date: LocalDate, frequency: CreditFrequency): CreditPeriod = when (frequency) {
            CreditFrequency.MONTHLY -> CreditPeriod(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()))
            CreditFrequency.QUARTERLY -> {
                val firstMonth = (date.monthValue - 1) / 3 * 3 + 1
                val start = LocalDate.of(date.year, firstMonth, 1)
                CreditPeriod(start, start.plusMonths(3).minusDays(1))
            }
        }
    }
}

/** A dated movement of the account (positive in, negative out). */
data class BalanceFlow(val date: LocalDate, val amount: Paise)

/** Interest the bank actually credited for a past period, next to Kosh's estimate for it. */
data class PastCredit(val period: CreditPeriod, val estimated: Paise, val credited: Paise, val creditedOn: LocalDate)

/** Savings interest for one account. Estimates only: never added to balances or net worth. */
data class InterestEstimate(
    val period: CreditPeriod,
    /** Interest earned on daily closing balances from the period start (or the entry date) through today. */
    val accrued: Paise,
    /** [accrued] plus the remaining days at today's balance. */
    val projected: Paise,
    val nextCreditDate: LocalDate,
    /** Headline annual rate at today's balance. */
    val ratePct: Double,
    /** A year's interest at today's balance. */
    val annualEstimate: Paise,
    /** Days of the current period that counted (after the user's entry date). */
    val daysCounted: Int,
    val lastCredit: PastCredit? = null,
)

object SavingsInterest {

    fun periodContaining(date: LocalDate, frequency: CreditFrequency): CreditPeriod = CreditPeriod.containing(date, frequency)

    /** Days after a period ends during which an interest credit is treated as that period's payout. */
    const val CREDIT_WINDOW_DAYS = 20L

    /**
     * Accrued interest on daily closing balances.
     *
     * Closing balances are rebuilt backwards from [currentBalance] (today's closing balance): the closing balance
     * of day d is today's balance minus every flow dated after d. Days before [entryDate] (when the user
     * started tracking) are not counted, since their balance isn't known.
     *
     * @param flows movements of the account from at least the start of the previous period, interest credits included.
     * @param interestCredits interest credited by the bank (INCOME, category interest), for the last-period comparison.
     */
    fun estimate(
        currentBalance: Paise,
        flows: List<BalanceFlow>,
        today: LocalDate,
        entryDate: LocalDate,
        card: SavingsRateCard,
        frequency: CreditFrequency = CreditFrequency.QUARTERLY,
        interestCredits: List<BalanceFlow> = emptyList(),
    ): InterestEstimate {
        val period = periodContaining(today, frequency)
        val closing = closingBalances(currentBalance, flows)
        val (accrued, days) = accrue(closing, maxOf(period.start, entryDate), today, card)
        val remaining = ChronoUnit.DAYS.between(today, period.end).coerceAtLeast(0)
        val daily = SavingsRates.dailyInterest(currentBalance, card.slabs)
        val projected = accrued + daily * remaining

        val previous = period.previous(frequency)
        val lastCredit = run {
            val window = interestCredits.filter {
                !it.date.isBefore(previous.end.minusDays(3)) && !it.date.isAfter(previous.end.plusDays(CREDIT_WINDOW_DAYS))
            }
            if (window.isEmpty()) return@run null
            val (prevAccrued, prevDays) = accrue(closing, maxOf(previous.start, entryDate), previous.end, card)
            if (prevDays == 0) return@run null
            PastCredit(previous, prevAccrued.roundToLong(), window.sumOf { it.amount }, window.maxOf { it.date })
        }
        return InterestEstimate(
            period = period,
            accrued = accrued.roundToLong(),
            projected = projected.roundToLong(),
            nextCreditDate = period.end,
            ratePct = card.headlineRatePct(currentBalance),
            annualEstimate = (daily * 365).roundToLong(),
            daysCounted = days,
            lastCredit = lastCredit,
        )
    }

    /** Closing balance of any day, rebuilt backwards from today's [currentBalance]. */
    private fun closingBalances(currentBalance: Paise, flows: List<BalanceFlow>): (LocalDate) -> Paise {
        val byDate = flows.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.amount } }.toSortedMap()
        return { day -> currentBalance - byDate.tailMap(day.plusDays(1)).values.sum() }
    }

    /** Interest in fractional paise from [from] to [to] inclusive, and the number of days counted. */
    private fun accrue(closing: (LocalDate) -> Paise, from: LocalDate, to: LocalDate, card: SavingsRateCard): Pair<Double, Int> {
        if (from.isAfter(to)) return 0.0 to 0
        var total = 0.0
        var days = 0
        var d = from
        while (!d.isAfter(to)) {
            total += SavingsRates.dailyInterest(closing(d), card.slabs)
            days++
            d = d.plusDays(1)
        }
        return total to days
    }
}
