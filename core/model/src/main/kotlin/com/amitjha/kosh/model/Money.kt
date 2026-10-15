package com.amitjha.kosh.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Amounts are stored as whole paise (1 rupee = 100 paise) to avoid floating-point drift. */
typealias Paise = Long

object Money {
    fun fromRupees(rupees: Double): Paise =
        BigDecimal.valueOf(rupees).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    /** Parses amounts as they appear in Indian bank messages: "1,23,456.70", "Rs.500", "INR 2,000". */
    fun parse(text: String): Paise? {
        val cleaned = text.replace(Regex("(?i)(rs\\.?|inr|₹)"), "").replace(",", "").trim()
        val value = cleaned.toBigDecimalOrNull() ?: return null
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun toRupees(paise: Paise): Double = paise / 100.0

    /** Formats with Indian digit grouping: 12345678.9 -> "₹1,23,45,678.90". */
    fun format(paise: Paise, showPaise: Boolean = true, symbol: String = "₹"): String {
        val negative = paise < 0
        // Without paise the amount is rounded to the nearest rupee (₹1,999.99 shows as ₹2,000).
        val absPaise = if (showPaise) abs(paise) else (abs(paise) + 50) / 100 * 100
        val rupees = absPaise / 100
        val fraction = absPaise % 100
        val grouped = groupIndian(rupees.toString())
        val body = if (showPaise) "$grouped.${fraction.toString().padStart(2, '0')}" else grouped
        return (if (negative) "-" else "") + symbol + body
    }

    /** Compact form for charts and chips: ₹950, ₹12.5K, ₹3.4L, ₹1.2Cr. */
    fun compact(paise: Paise, symbol: String = "₹"): String {
        val r = abs(paise) / 100.0
        val sign = if (paise < 0) "-" else ""
        // Pick the unit after rounding, so ₹99,950 reads "₹1L" rather than "₹100K".
        val units = listOf(1e7 to "Cr", 1e5 to "L", 1e3 to "K")
        val rupees = Math.round(r)
        var body = rupees.toString()
        if (rupees >= 1_000) {
            for ((i, unit) in units.withIndex()) {
                if (r < unit.first) continue
                val rounded = BigDecimal.valueOf(r / unit.first).setScale(1, RoundingMode.HALF_UP).toDouble()
                val bigger = units.getOrNull(i - 1)
                body = if (bigger != null && rounded * unit.first >= bigger.first) {
                    trim(r / bigger.first) + bigger.second
                } else {
                    trim(r / unit.first) + unit.second
                }
                break
            }
            if (r < 1e3) body = "1K"
        }
        return sign + symbol + body
    }

    private fun trim(v: Double): String {
        val s = BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return s
    }

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val groups = rest.reversed().chunked(2).map { it.reversed() }.reversed()
        return groups.joinToString(",") + "," + last3
    }
}
