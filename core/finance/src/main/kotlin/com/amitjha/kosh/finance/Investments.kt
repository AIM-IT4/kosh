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

/** How often a payout FD credits its interest. */
enum class PayoutFrequency(val months: Int) {
    MONTHLY(1), QUARTERLY(3), HALF_YEARLY(6), YEARLY(12);

    companion object {
        fun fromName(name: String?): PayoutFrequency = entries.firstOrNull { it.name == name } ?: QUARTERLY
    }
}

/** One interest credit of a payout FD; [final] is the part-period interest paid with the principal at maturity. */
data class FdPayoutCredit(val date: LocalDate, val amount: Paise, val final: Boolean = false)

private const val DAYS_IN_YEAR = 365.0

/**
 * A fixed deposit.
 *
 * Valuation convention (Indian banks, cumulative FDs): interest compounds at the end of each full
 * compounding period counted in calendar months from [startDate]; for the last, incomplete period simple
 * interest is accrued on the compounded amount for the elapsed days (actual/365). Values are capped at
 * [maturityDate]. With [FdPayout.INTEREST_PAYOUT] the interest is paid out, so the deposit's value stays at
 * the principal and [interestEarnedTill] reports the simple interest earned so far; [payoutSchedule] says when the
 * bank credits it and how much.
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
    /** How often a payout FD credits its interest. */
    val payoutFrequency: PayoutFrequency = PayoutFrequency.QUARTERLY,
    /** The bank's figure for each regular credit, when the user entered it; otherwise [computedPayout]. */
    val payoutAmount: Paise? = null,
    /** Day of the month the bank credits interest; null for the deposit's own date each period (the usual case). */
    val payoutDay: Int? = null,
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

    /**
     * The regular interest credit of a payout FD, the way Indian banks work it out. For a payout period at least as
     * long as the compounding period it is the interest compounded over the period (quarterly: P × r/4; half-yearly
     * with quarterly compounding: P × ((1 + r/4)² − 1)). For a shorter one, such as monthly payouts on a deposit that
     * compounds quarterly, it is the discounted value of the period's interest, so that the payouts reinvested at the
     * deposit's rate add up to it: Q × j / ((1 + j)^k − 1), with Q = P × r/4, j = r/12 and k = 3 (₹1 lakh at 7%:
     * ₹579.94 a month, not ₹583.33).
     */
    fun computedPayout(): Paise {
        val r = rate
        if (r <= 0.0) return 0
        val m = payoutFrequency.months
        val amount = when {
            compounding == Compounding.SIMPLE -> principal * r * m / 12.0
            m >= compounding.monthsPerPeriod ->
                principal * ((1 + r / compounding.periodsPerYear).pow(m.toDouble() / compounding.monthsPerPeriod) - 1)
            else -> {
                val periodInterest = principal * r / compounding.periodsPerYear
                val j = r * m / 12.0
                periodInterest * j / ((1 + j).pow(compounding.monthsPerPeriod / m) - 1)
            }
        }
        return amount.roundToLong()
    }

    /** Each regular credit: the bank's figure when entered, else [computedPayout]. */
    fun regularPayout(): Paise = payoutAmount ?: computedPayout()

    /**
     * When a payout FD credits interest and how much, over its whole term: every [payoutFrequency] from the start
     * date (or on [payoutDay] of those months, the first credit then covering only the days since the start), and
     * the interest for a last part-period with the principal at maturity. Empty for a cumulative FD.
     */
    fun payoutSchedule(): List<FdPayoutCredit> {
        if (payout != FdPayout.INTEREST_PAYOUT) return emptyList()
        val regular = regularPayout()
        val out = ArrayList<FdPayoutCredit>()
        var last = startDate
        var k = 1
        while (out.size < MAX_PAYOUTS) {
            val date = payoutDate(k)
            if (date.isAfter(maturityDate)) break
            val amount = if (k == 1 && payoutDay != null) {
                // Credits on a fixed day of the month: the first covers only the days since the deposit was made.
                val full = ChronoUnit.DAYS.between(startDate, startDate.plusMonths(payoutFrequency.months.toLong()))
                (regular * ChronoUnit.DAYS.between(startDate, date).toDouble() / full).roundToLong().coerceAtMost(regular)
            } else {
                regular
            }
            out += FdPayoutCredit(date, amount, final = date == maturityDate)
            last = date
            k++
        }
        if (last.isBefore(maturityDate)) {
            val days = ChronoUnit.DAYS.between(last, maturityDate)
            val part = (principal * rate * days / DAYS_IN_YEAR).roundToLong()
            if (part > 0) out += FdPayoutCredit(maturityDate, part, final = true)
        }
        return out
    }

    private fun payoutDate(k: Int): LocalDate {
        val date = startDate.plusMonths(k.toLong() * payoutFrequency.months)
        val day = payoutDay ?: return date
        val month = YearMonth.from(date)
        return month.atDay(min(day.coerceIn(1, 31), month.lengthOfMonth()))
    }

    /** Interest the bank has credited by [date] (inclusive), from [payoutSchedule]. */
    fun interestPaidTill(date: LocalDate): Paise = payoutSchedule().filter { !it.date.isAfter(date) }.sumOf { it.amount }

    /** The next credit on or after [today], or null when none is left. */
    fun nextPayout(today: LocalDate): FdPayoutCredit? = payoutSchedule().firstOrNull { !it.date.isBefore(today) }

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

    private companion object {
        /** 100 years of monthly credits: a guard against a mistyped maturity year, never reached by a real FD. */
        const val MAX_PAYOUTS = 1200
    }
}

/** A scheduled FD interest credit and the matching credit found in the bank's messages, if any. */
data class SeenPayout(val due: FdPayoutCredit, val creditedOn: LocalDate? = null, val credited: Paise? = null) {
    val seen: Boolean get() = creditedOn != null
}

/** Finds FD interest credits among a bank account's credits. */
object FdPayouts {
    /** A credit can arrive this many days before its due date (a holiday moves it earlier)... */
    const val EARLY_DAYS = 3L

    /** ...or this many after. */
    const val LATE_DAYS = 7L

    /**
     * Pairs each due credit in [schedule] with a credit from [credits] (date and amount) within [EARLY_DAYS] before
     * to [LATE_DAYS] after its date, of 85% to 105% of the amount due, so a credit net of 10% TDS still counts.
     * The closest in time wins and each credit is used once.
     */
    fun match(schedule: List<FdPayoutCredit>, credits: List<Pair<LocalDate, Paise>>): List<SeenPayout> {
        val used = HashSet<Int>()
        return schedule.map { due ->
            val best = credits.indices
                .filter { it !in used }
                .filter { i ->
                    val (date, amount) = credits[i]
                    !date.isBefore(due.date.minusDays(EARLY_DAYS)) && !date.isAfter(due.date.plusDays(LATE_DAYS)) &&
                        amount >= due.amount * 0.85 && amount <= due.amount * 1.05
                }
                .minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(due.date, credits[it].first)) }
            if (best == null) {
                SeenPayout(due)
            } else {
                used += best
                SeenPayout(due, credits[best].first, credits[best].second)
            }
        }
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
 *
 * A passbook balance taken mid-year normally excludes the current fiscal year's interest, which EPFO credits
 * only after 31 March. Unless [balanceIncludesCurrentYearInterest] is set, interest for the months of the
 * current fiscal year that ended on or before [asOfDate] is accrued on [balanceAsOf] and credited with the rest
 * at the year end.
 */
data class EpfAccount(
    val balanceAsOf: Paise,
    val asOfDate: LocalDate,
    val monthlyEmployeeContribution: Paise,
    val monthlyEmployerEpfContribution: Paise,
    val annualRatePct: Double = DEFAULT_EPF_RATE_PCT,
    val contributionDayOfMonth: Int = 15,
    val balanceIncludesCurrentYearInterest: Boolean = false,
) {
    init {
        require(contributionDayOfMonth in 1..31) { "contribution day must be 1-31" }
    }

    /** Interest earned this fiscal year up to [asOfDate] that the entered balance does not yet include. */
    val interestBeforeAsOf: Double get() {
        if (balanceIncludesCurrentYearInterest) return 0.0
        // A balance dated 31 March is the year-end figure; its year's interest is credited separately.
        if (asOfDate.monthValue == 3 && asOfDate.dayOfMonth == 31) return 0.0
        val fyStart = if (asOfDate.monthValue >= 4) YearMonth.of(asOfDate.year, 4) else YearMonth.of(asOfDate.year - 1, 4)
        var months = 0
        var m = fyStart
        while (!m.atEndOfMonth().isAfter(asOfDate)) {
            months++
            m = m.plusMonths(1)
        }
        return balanceAsOf * annualRatePct / 100.0 / 12.0 * months
    }

    val monthlyContribution: Paise get() = monthlyEmployeeContribution + monthlyEmployerEpfContribution

    fun projectBalance(onDate: LocalDate): EpfProjection {
        if (!onDate.isAfter(asOfDate)) return EpfProjection(balanceAsOf, interestBeforeAsOf.roundToLong(), 0)
        var credited = balanceAsOf.toDouble()
        var accrued = interestBeforeAsOf
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
            val nav = navs[code]?.takeIf { it > 0 } ?: byCode[code]?.lastNav?.takeIf { it > 0 } ?: error("no NAV for $code")
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

    /** Updates every holding's last NAV from [navs]; non-positive NAVs are ignored. */
    fun withNavs(navs: Map<String, Double>, date: LocalDate): NpsAccount = copy(
        holdings = holdings.map { h -> navs[h.schemeCode]?.takeIf { it > 0 }?.let { h.copy(lastNav = it, lastNavDate = date) } ?: h },
    )

    /** Updates every holding from fetched [quotes], keeping each NAV's own publish date. */
    fun withQuotes(quotes: Map<String, NavQuote>, fallbackDate: LocalDate): NpsAccount = copy(
        holdings = holdings.map { h ->
            quotes[h.schemeCode]?.takeIf { it.nav > 0 }?.let { q -> h.copy(lastNav = q.nav, lastNavDate = q.date ?: fallbackDate) } ?: h
        },
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
