package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Money
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
    fun headlineRatePct(balance: Paise = 0): Double =
        (slabs.lastOrNull { balance >= it.minBalance } ?: slabs.first()).ratePct

    /** True when every balance earns the same rate. */
    val isFlat: Boolean get() = slabs.map { it.ratePct }.distinct().size == 1

    /** Short description of the rate card: "2.50%" or "2.50% up to ₹2L · 5.00% to ₹50L · 4.00% above". */
    fun describe(): String {
        fun r(x: Double) = "%.2f%%".format(x)
        if (isFlat) return r(slabs.first().ratePct)
        return slabs.mapIndexed { i, slab ->
            val next = slabs.getOrNull(i + 1)
            when {
                next == null -> "${r(slab.ratePct)} above ${Money.compact(slab.minBalance)}"
                i == 0 -> "${r(slab.ratePct)} up to ${Money.compact(next.minBalance)}"
                else -> "${r(slab.ratePct)} to ${Money.compact(next.minBalance)}"
            }
        }.joinToString(" · ")
    }
}

/**
 * Built-in savings rates for the banks Kosh knows. Banks publish no API for these, so the table ships with
 * the app (with an as-of date and a confidence level) and the user can override the rate per account.
 */
object SavingsRates {

    private fun flat(bank: Bank, pct: Double, asOf: LocalDate, confidence: RateConfidence, source: String) =
        SavingsRateCard(bank, listOf(RateSlab(0, pct)), asOf, confidence, source)

    val table: Map<Bank, SavingsRateCard> = listOf(
        flat(Bank.HDFC, 2.50, LocalDate.of(2025, 6, 24), RateConfidence.OFFICIAL, "hdfc.bank.in: 2.50% on all balances"),
        flat(Bank.ICICI, 2.50, LocalDate.of(2025, 6, 26), RateConfidence.OFFICIAL, "icici.bank.in: 2.50% on all balances"),
        flat(Bank.KOTAK, 2.50, LocalDate.of(2025, 7, 9), RateConfidence.OFFICIAL, "kotak.bank.in: 2.50% on all balances"),
        flat(Bank.SBI, 2.50, LocalDate.of(2026, 8, 20), RateConfidence.REPORTED, "Reported from SBI's rate page: 2.50% on all balances"),
        flat(Bank.AXIS, 2.50, LocalDate.of(2026, 4, 1), RateConfidence.OFFICIAL, "axis.bank.in: 2.50% on all balances"),
        SavingsRateCard(
            Bank.DBS,
            listOf(RateSlab(0, 2.50), RateSlab(200_000_00, 5.00), RateSlab(50_00_000_00, 4.00)),
            LocalDate.of(2025, 10, 24),
            RateConfidence.REPORTED,
            "Paisabazaar/Policybazaar: 2.50% up to ₹2L, 5.00% ₹2L–₹50L, 4.00% above ₹50L (DBS: \"up to 5%\")",
        ),
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
