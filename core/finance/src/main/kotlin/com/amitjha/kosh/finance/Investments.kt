package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Compounding(val periodsPerYear: Int) {
    MONTHLY(12), QUARTERLY(4), HALF_YEARLY(2), YEARLY(1), SIMPLE(0);

    val monthsPerPeriod: Int get() = if (periodsPerYear == 0) 0 else 12 / periodsPerYear
}

enum class FdPayout { CUMULATIVE, INTEREST_PAYOUT }

private const val DAYS_IN_YEAR = 365.0

/**
 * A fixed deposit.
 *
 * Valuation convention (Indian banks, cumulative FDs): interest compounds at the end of each full
 * compounding period counted in calendar months from [startDate]; for the last, incomplete period simple
 * interest is accrued on the compounded amount for the elapsed days (actual/365). Values are capped at
 * [maturityDate]. With [FdPayout.INTEREST_PAYOUT] the interest is paid out, so the deposit's value stays at
 * the principal and [interestEarnedTill] reports the simple interest paid so far.
 */
data class FixedDeposit(
    val principal: Paise,
    val annualRatePct: Double,
    val startDate: LocalDate,
    val maturityDate: LocalDate,
    val compounding: Compounding = Compounding.QUARTERLY,
    val payout: FdPayout = FdPayout.CUMULATIVE,
    val bank: String = "",
    val name: String = "",
) {
    init {
        require(principal >= 0) { "principal must be non-negative" }
        require(!maturityDate.isBefore(startDate)) { "maturity must not be before start" }
    }

    private val rate get() = annualRatePct / 100.0

    /** Value of the deposit on [date] (principal before the start date). */
    fun valueOn(date: LocalDate): Paise {
        if (!date.isAfter(startDate)) return principal
        if (payout == FdPayout.INTEREST_PAYOUT) return principal
        val d = if (date.isAfter(maturityDate)) maturityDate else date
        return accumulated(d).roundToLong()
    }

    fun maturityValue(): Paise = valueOn(maturityDate)

    /** Interest earned (accrued for cumulative FDs, paid out for payout FDs) up to [date]. */
    fun interestEarnedTill(date: LocalDate): Paise {
        if (!date.isAfter(startDate)) return 0
        val d = if (date.isAfter(maturityDate)) maturityDate else date
        return if (payout == FdPayout.INTEREST_PAYOUT) {
            val days = ChronoUnit.DAYS.between(startDate, d)
            (principal * rate * days / DAYS_IN_YEAR).roundToLong()
        } else {
            accumulated(d).roundToLong() - principal
        }
    }

    fun daysToMaturity(date: LocalDate): Long = maxOf(0L, ChronoUnit.DAYS.between(date, maturityDate))

    fun isMatured(date: LocalDate): Boolean = !date.isBefore(maturityDate)

    private fun accumulated(date: LocalDate): Double {
        if (compounding == Compounding.SIMPLE) {
            val days = ChronoUnit.DAYS.between(startDate, date)
            return principal * (1 + rate * days / DAYS_IN_YEAR)
        }
        val m = compounding.monthsPerPeriod
        val n = compounding.periodsPerYear
        var periods = 0
        while (!startDate.plusMonths(((periods + 1) * m).toLong()).isAfter(date)) periods++
        val lastPeriodStart = startDate.plusMonths((periods * m).toLong())
        val amount = principal * (1 + rate / n).pow(periods)
        val partialDays = ChronoUnit.DAYS.between(lastPeriodStart, date)
        return amount * (1 + rate * partialDays / DAYS_IN_YEAR)
    }
}

/** One recurring contribution, so the app can add it automatically as it falls due. */
data class ContributionEvent(val date: LocalDate, val amount: Paise, val label: String)

data class EpfProjection(
    /** Balance including all interest credited at fiscal-year ends up to the date. */
    val creditedBalance: Paise,
    /** Interest accrued in the current fiscal year, credited on the next 31 March. */
    val accruedInterestNotYetCredited: Paise,
    /** Contributions added since the as-of date. */
    val contributionsSinceAsOf: Paise,
) {
    val totalIncludingAccrued: Paise get() = creditedBalance + accruedInterestNotYetCredited
}

/**
 * An EPF account.
 *
 * EPFO convention: interest is computed monthly on the running balance at each month end
 * (rate / 12 per month) and credited once a year at the fiscal-year end (31 March). [balanceAsOf] is the
 * credited balance on [asOfDate]; interest accrues for every month that ends after [asOfDate]. Contributions
 * (employee + employer EPF share; the EPS part is excluded) arrive on [contributionDayOfMonth].
 */
data class EpfAccount(
    val balanceAsOf: Paise,
    val asOfDate: LocalDate,
    val monthlyEmployeeContribution: Paise,
    val monthlyEmployerEpfContribution: Paise,
    val annualRatePct: Double = DEFAULT_EPF_RATE_PCT,
    val contributionDayOfMonth: Int = 15,
) {
    init {
        require(contributionDayOfMonth in 1..31) { "contribution day must be 1-31" }
    }

    val monthlyContribution: Paise get() = monthlyEmployeeContribution + monthlyEmployerEpfContribution

    fun projectBalance(onDate: LocalDate): EpfProjection {
        if (!onDate.isAfter(asOfDate)) return EpfProjection(balanceAsOf, 0, 0)
        var credited = balanceAsOf.toDouble()
        var accrued = 0.0
        var contributions = 0L
        var month = YearMonth.from(asOfDate)
        val last = YearMonth.from(onDate)
        while (!month.isAfter(last)) {
            val payDay = month.atDay(min(contributionDayOfMonth, month.lengthOfMonth()))
            if (payDay.isAfter(asOfDate) && !payDay.isAfter(onDate)) {
                credited += monthlyContribution
                contributions += monthlyContribution
            }
            val monthEnd = month.atEndOfMonth()
            if (monthEnd.isAfter(asOfDate) && !monthEnd.isAfter(onDate)) {
                accrued += credited * annualRatePct / 100.0 / 12.0
                if (month.monthValue == 3) {
                    credited += accrued
                    accrued = 0.0
                }
            }
            month = month.plusMonths(1)
        }
        return EpfProjection(credited.roundToLong(), accrued.roundToLong(), contributions)
    }

    /** Monthly contributions falling in (from, to]. */
    fun contributionsDue(from: LocalDate, to: LocalDate): List<ContributionEvent> =
        monthlyDates(contributionDayOfMonth, from, to).map { ContributionEvent(it, monthlyContribution, "EPF contribution") }

    companion object {
        /** Rate declared by EPFO for FY 2024-25. */
        const val DEFAULT_EPF_RATE_PCT = 8.25
    }
}

data class NpsHolding(
    val schemeCode: String,
    val schemeName: String,
    val units: Double,
    val lastNav: Double,
    val lastNavDate: LocalDate? = null,
) {
    fun value(nav: Double = lastNav): Paise = (units * nav * 100).roundToLong()
}

data class NpsContributionResult(val account: NpsAccount, val unitsBought: Map<String, Double>)

/** An NPS account: scheme holdings in units, plus a recurring contribution split by [allocation]. */
data class NpsAccount(
    val holdings: List<NpsHolding>,
    val monthlyContribution: Paise = 0,
    val allocation: Map<String, Double> = emptyMap(),
    val contributionDayOfMonth: Int = 1,
    /** Total amount contributed so far (cost basis), if known. */
    val investedAmount: Paise = 0,
) {
    init {
        if (allocation.isNotEmpty()) {
            require(kotlin.math.abs(allocation.values.sum() - 1.0) < 1e-6) { "allocation fractions must sum to 1" }
        }
    }

    /** Market value using [navs] where available, else each holding's last known NAV. */
    fun value(navs: Map<String, Double> = emptyMap()): Paise =
        holdings.sumOf { it.value(navs[it.schemeCode] ?: it.lastNav) }

    /** Buys units for [amount] split by [allocation] at [navs] (falling back to last NAVs). */
    fun applyContribution(amount: Paise, navs: Map<String, Double>, date: LocalDate? = null): NpsContributionResult {
        val split = allocation.ifEmpty { holdings.associate { it.schemeCode to 1.0 / holdings.size } }
        require(split.isNotEmpty()) { "no schemes to contribute to" }
        val bought = mutableMapOf<String, Double>()
        val byCode = holdings.associateBy { it.schemeCode }.toMutableMap()
        for ((code, fraction) in split) {
            val nav = navs[code] ?: byCode[code]?.lastNav ?: error("no NAV for $code")
            val units = amount / 100.0 * fraction / nav
            bought[code] = units
            val h = byCode[code]
            byCode[code] = if (h == null) NpsHolding(code, code, units, nav, date)
            else h.copy(units = h.units + units, lastNav = nav, lastNavDate = date ?: h.lastNavDate)
        }
        val ordered = holdings.map { byCode.getValue(it.schemeCode) } +
            byCode.values.filter { n -> holdings.none { it.schemeCode == n.schemeCode } }
        return NpsContributionResult(copy(holdings = ordered, investedAmount = investedAmount + amount), bought)
    }

    /** Updates every holding's last NAV from [navs]. */
    fun withNavs(navs: Map<String, Double>, date: LocalDate): NpsAccount = copy(
        holdings = holdings.map { h -> navs[h.schemeCode]?.let { h.copy(lastNav = it, lastNavDate = date) } ?: h },
    )

    fun contributionsDue(from: LocalDate, to: LocalDate): List<ContributionEvent> =
        if (monthlyContribution <= 0) emptyList()
        else monthlyDates(contributionDayOfMonth, from, to).map { ContributionEvent(it, monthlyContribution, "NPS contribution") }
}

/** Dates on [day] of each month (clamped to month length) in (from, to]. */
internal fun monthlyDates(day: Int, from: LocalDate, to: LocalDate): List<LocalDate> {
    val out = mutableListOf<LocalDate>()
    var m = YearMonth.from(from)
    val last = YearMonth.from(to)
    while (!m.isAfter(last)) {
        val d = m.atDay(min(day, m.lengthOfMonth()))
        if (d.isAfter(from) && !d.isAfter(to)) out += d
        m = m.plusMonths(1)
    }
    return out
}
