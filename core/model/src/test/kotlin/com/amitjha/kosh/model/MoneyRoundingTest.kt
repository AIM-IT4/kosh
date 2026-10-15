package com.amitjha.kosh.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyRoundingTest {
    @Test
    fun compactPromotesToTheNextUnitAfterRounding() {
        assertEquals("₹1L", Money.compact(99_950_00))
        assertEquals("₹1Cr", Money.compact(99_96_000_00))
        assertEquals("₹1K", Money.compact(999_60))
        assertEquals("₹99.9K", Money.compact(99_900_00))
        assertEquals("-₹1L", Money.compact(-99_950_00))
    }

    @Test
    fun formatWithoutPaiseRoundsToTheNearestRupee() {
        assertEquals("₹2,000", Money.format(1_999_99, showPaise = false))
        assertEquals("₹1,999", Money.format(1_999_49, showPaise = false))
        assertEquals("-₹2,000", Money.format(-1_999_99, showPaise = false))
        assertEquals("₹1,999.99", Money.format(1_999_99))
    }
}
