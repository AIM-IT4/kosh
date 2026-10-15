package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Indian income tax for a resident individual, old vs new regime, FY 2026-27 (AY 2027-28).
 *
 * Rules as published for FY 2025-26 and left unchanged by Budget 2026 (new Income Tax Act, 2025 effective
 * 1 April 2026 with the same slab rates). Sources (checked Oct 2026): Income Tax Department regime calculator
 * and the Budget 2025/2026 summaries reported by ClearTax, 5paisa and Bajaj Finserv.
 * - New regime slabs: 0–4L nil, 4–8L 5%, 8–12L 10%, 12–16L 15%, 16–20L 20%, 20–24L 25%, above 24L 30%.
 *   Standard deduction ₹75,000. Rebate u/s 87A up to ₹60,000 when taxable income ≤ ₹12L, with marginal
 *   relief just above ₹12L (tax capped at the income above ₹12L).
 * - Old regime slabs (below 60): 0–2.5L nil, 2.5–5L 5%, 5–10L 20%, above 10L 30%. Standard deduction ₹50,000.
 *   Rebate u/s 87A up to ₹12,500 when taxable income ≤ ₹5L. Seniors (60–80): nil up to 3L.
 * - Surcharge: 10% above ₹50L, 15% above ₹1Cr, 25% above ₹2Cr, 37% above ₹5Cr (new regime capped at 25%),
 *   with marginal relief. Health and education cess 4%.
 * - Deduction caps: 80C ₹1.5L, 80CCD(1B) ₹50,000, 80D ₹25,000 (₹50,000 senior), 80TTA ₹10,000 on savings
 *   interest (80TTB ₹50,000 on all deposit interest for seniors), section 24(b) home-loan interest ₹2L.
 *
 * Not tax advice: estimates for planning; verify with a tax professional.
 */
object Tax {

    const val RULES_LABEL = "Rules as of FY 2026-27 · verify with a tax professional"

    enum class Regime { OLD, NEW }

    private const val L = 100_000L * 100 // one lakh, in paise
    private const val CESS = 0.04

    val CAP_80C: Paise = 150_000L * 100
    val CAP_80CCD1B: Paise = 50_000L * 100
    val CAP_80D: Paise = 25_000L * 100
    val CAP_80D_SENIOR: Paise = 50_000L * 100
    val CAP_80TTA: Paise = 10_000L * 100
    val CAP_80TTB: Paise = 50_000L * 100
    val CAP_24B: Paise = 200_000L * 100
    val STD_NEW: Paise = 75_000L * 100
    val STD_OLD: Paise = 50_000L * 100

    private data class Slab(val upTo: Paise, val ratePct: Double)

    private val NEW_SLABS = listOf(
        Slab(4 * L, 0.0), Slab(8 * L, 5.0), Slab(12 * L, 10.0), Slab(16 * L, 15.0),
        Slab(20 * L, 20.0), Slab(24 * L, 25.0), Slab(Long.MAX_VALUE, 30.0),
    )
    private fun oldSlabs(senior: Boolean) = listOf(
        Slab(if (senior) 3 * L else (2.5 * L).toLong(), 0.0), Slab(5 * L, 5.0), Slab(10 * L, 20.0), Slab(Long.MAX_VALUE, 30.0),
    )

    /** Everything the calculation needs, annual amounts in paise. Deductions are amounts claimed (caps applied here). */
    data class Inputs(
        val grossSalary: Paise,
        /** Other taxable income: FD interest, savings interest, etc. */
        val savingsInterest: Paise = 0,
        val fdInterest: Paise = 0,
        val otherIncome: Paise = 0,
        val sec80C: Paise = 0,
        val sec80CCD1B: Paise = 0,
        val sec80D: Paise = 0,
        val hraExemption: Paise = 0,
        val homeLoanInterest: Paise = 0,
        val senior: Boolean = false,
    ) {
        val totalIncome: Paise get() = grossSalary + savingsInterest + fdInterest + otherIncome
    }

    data class Breakdown(
        val regime: Regime,
        val grossIncome: Paise,
        val deductions: Paise,
        val taxableIncome: Paise,
        val slabTax: Paise,
        val rebate: Paise,
        val surcharge: Paise,
        val cess: Paise,
        val total: Paise,
        /** Marginal slab rate on the last rupee, % (before cess). */
        val marginalRatePct: Double,
    )

    data class Comparison(val old: Breakdown, val new: Breakdown) {
        val better: Regime get() = if (new.total <= old.total) Regime.NEW else Regime.OLD
        val saving: Paise get() = kotlin.math.abs(old.total - new.total)
    }

    /** HRA exemption: least of HRA received, rent paid − 10% of basic, and 50% (metro) / 40% of basic. */
    fun hraExemption(basicAnnual: Paise, hraReceivedAnnual: Paise, rentPaidAnnual: Paise, metro: Boolean): Paise {
        if (hraReceivedAnnual <= 0 || rentPaidAnnual <= 0) return 0
        val rentLess = rentPaidAnnual - basicAnnual / 10
        val pctOfBasic = (basicAnnual * if (metro) 0.5 else 0.4).roundToLong()
        return max(0, minOf(hraReceivedAnnual, rentLess, pctOfBasic))
    }

    fun compute(regime: Regime, input: Inputs): Breakdown {
        val gross = input.totalIncome
        val deductions = when (regime) {
            Regime.NEW -> min(STD_NEW, input.grossSalary)
            Regime.OLD -> {
                val std = min(STD_OLD, input.grossSalary)
                val interestDed = if (input.senior) min(CAP_80TTB, input.savingsInterest + input.fdInterest) else min(CAP_80TTA, input.savingsInterest)
                std + min(CAP_80C, input.sec80C) + min(CAP_80CCD1B, input.sec80CCD1B) +
                    min(if (input.senior) CAP_80D_SENIOR else CAP_80D, input.sec80D) + input.hraExemption +
                    min(CAP_24B, input.homeLoanInterest) + interestDed
            }
        }
        val taxable = max(0, gross - deductions).let { it / 1000 * 1000 } // nearest ₹10 (paise ×1000 = ₹10)
        val slabs = if (regime == Regime.NEW) NEW_SLABS else oldSlabs(input.senior)
        val slabTax = slabTax(taxable, slabs)
        val rebate = when (regime) {
            Regime.NEW -> when {
                taxable <= 12 * L -> min(slabTax, 60_000L * 100)
                // Marginal relief: tax may not exceed the income above ₹12L.
                slabTax > taxable - 12 * L -> slabTax - (taxable - 12 * L)
                else -> 0
            }
            Regime.OLD -> if (taxable <= 5 * L) min(slabTax, 12_500L * 100) else 0
        }
        val afterRebate = slabTax - rebate
        val surcharge = surcharge(taxable, afterRebate, regime, slabs)
        val cess = ((afterRebate + surcharge) * CESS).roundToLong()
        val total = (afterRebate + surcharge + cess + 50) / 100 * 100 // nearest rupee
        val marginal = slabs.first { taxable < it.upTo || it.upTo == Long.MAX_VALUE }.ratePct
        return Breakdown(regime, gross, deductions, taxable, slabTax, rebate, surcharge, cess, total, if (afterRebate == 0L) 0.0 else marginal)
    }

    fun compare(input: Inputs): Comparison = Comparison(compute(Regime.OLD, input), compute(Regime.NEW, input))

    /** Old-regime tax saved by claiming [extra] more under a capped section (e.g. topping 80C up to the cap). */
    fun oldRegimeSavingFrom(input: Inputs, extra80C: Paise = 0, extra80CCD1B: Paise = 0): Paise {
        val base = compute(Regime.OLD, input).total
        val more = compute(Regime.OLD, input.copy(sec80C = input.sec80C + extra80C, sec80CCD1B = input.sec80CCD1B + extra80CCD1B)).total
        return max(0, base - more)
    }

    private fun slabTax(taxable: Paise, slabs: List<Slab>): Paise {
        var tax = 0.0
        var lower = 0L
        for (s in slabs) {
            if (taxable <= lower) break
            val top = min(taxable, s.upTo)
            tax += (top - lower) * s.ratePct / 100.0
            lower = s.upTo
        }
        return tax.roundToLong()
    }

    private fun surcharge(taxable: Paise, tax: Paise, regime: Regime, slabs: List<Slab>): Paise {
        val tiers = buildList {
            add(50 * L to 10.0); add(100 * L to 15.0); add(200 * L to 25.0)
            if (regime == Regime.OLD) add(500 * L to 37.0)
        }
        val tier = tiers.lastOrNull { taxable > it.first } ?: return 0
        val raw = (tax * tier.second / 100.0).roundToLong()
        // Marginal relief: tax + surcharge may exceed the tax at the threshold by at most the income above it.
        val prevRate = tiers.lastOrNull { it.first < tier.first }?.second ?: 0.0
        val taxAtThreshold = slabTax(tier.first, slabs)
        val atThreshold = taxAtThreshold + (taxAtThreshold * prevRate / 100.0).roundToLong()
        val capped = atThreshold + (taxable - tier.first) - tax
        return max(0, min(raw, capped))
    }
}
