package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Paise
import java.time.LocalDate

/** How much a built-in rate can be trusted. */
enum class RateConfidence(val label: String) {
    /** Taken from the bank's own rate page. */
    OFFICIAL("official"),

    /** Reported consistently by recent comparison sites. */
    REPORTED("reported"),

    /** Sources conflict or none is current; the user should confirm it. */
    UNVERIFIED("unverified"),
}

/** One savings slab: balances from [minBalance] (inclusive) up to the next slab earn [ratePct] a year. */
data class RateSlab(val minBalance: Paise, val ratePct: Double)

/** A bank's savings-account rate card. Slabs are sorted by [RateSlab.minBalance], the first starting at 0. */
data class SavingsRateCard(
    val bank: Bank,
    val slabs: List<RateSlab>,
    val asOf: LocalDate,
    val confidence: RateConfidence,
    val source: String,
) {
    init {
        require(slabs.isNotEmpty() && slabs.first().minBalance == 0L) { "slabs must start at a balance of 0" }
        require(slabs.zipWithNext().all { (a, b) -> b.minBalance > a.minBalance }) { "slabs must be increasing" }
    }

    /** Rate of the slab the whole balance falls in (the headline rate for display). */
    fun headlineRatePct(balance: Paise = 0): Double = slabs.last { balance >= it.minBalance }.ratePct

    /** True when every balance earns the same rate. */
    val isFlat: Boolean get() = slabs.map { it.ratePct }.distinct().size == 1
}

/**
 * Built-in savings rates for the banks Kosh knows. Banks publish no API for these, so the table ships with
 * the app (with an as-of date and a confidence level) and the user can override the rate per account.
 */
object SavingsRates {

    private fun flat(bank: Bank, pct: Double, asOf: LocalDate, confidence: RateConfidence, source: String) =
        SavingsRateCard(bank, listOf(RateSlab(0, pct)), asOf, confidence, source)

    val table: Map<Bank, SavingsRateCard> = listOf(
        flat(Bank.HDFC, 2.50, LocalDate.of(2025, 6, 24), RateConfidence.OFFICIAL, "HDFC Bank rate page: 2.50% on all balances"),
        flat(Bank.ICICI, 2.50, LocalDate.of(2026, 8, 1), RateConfidence.REPORTED, "Comparison sites, Aug 2026"),
        flat(Bank.KOTAK, 2.50, LocalDate.of(2026, 8, 1), RateConfidence.REPORTED, "Comparison sites, Aug 2026"),
        flat(Bank.SBI, 2.50, LocalDate.of(2026, 8, 1), RateConfidence.REPORTED, "Comparison sites, Aug 2026"),
        flat(Bank.AXIS, 3.00, LocalDate.of(2026, 10, 1), RateConfidence.UNVERIFIED, "Sources conflict; confirm with Axis Bank"),
        flat(Bank.DBS, 2.50, LocalDate.of(2026, 10, 1), RateConfidence.UNVERIFIED, "No current official figure; confirm with DBS"),
        flat(Bank.OTHER, 2.50, LocalDate.of(2026, 10, 1), RateConfidence.UNVERIFIED, "Typical savings rate; set your bank's rate"),
    ).associateBy { it.bank }

    fun forBank(bank: Bank): SavingsRateCard = table[bank] ?: table.getValue(Bank.OTHER)

    /** A user-entered flat rate in place of the table. */
    fun overridden(bank: Bank, ratePct: Double): SavingsRateCard =
        SavingsRateCard(bank, listOf(RateSlab(0, ratePct)), LocalDate.now(), RateConfidence.OFFICIAL, "Your rate")

    /**
     * Interest for one day on a closing [balance], in paise (fractional), tiered by portion: each band of the
     * balance earns its own slab's rate, as Indian banks apply slabs. Negative balances earn nothing.
     */
    fun dailyInterest(balance: Paise, slabs: List<RateSlab>): Double {
        if (balance <= 0) return 0.0
        var total = 0.0
        slabs.forEachIndexed { i, slab ->
            val upper = slabs.getOrNull(i + 1)?.minBalance ?: Long.MAX_VALUE
            if (balance > slab.minBalance) {
                val portion = minOf(balance, upper) - slab.minBalance
                total += portion * slab.ratePct / 100.0 / 365.0
            }
        }
        return total
    }
}
