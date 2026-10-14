package com.amitjha.kosh.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test
    fun parsesBankFormats() {
        assertEquals(12345670L, Money.parse("1,23,456.70"))
        assertEquals(50000L, Money.parse("Rs.500"))
        assertEquals(200000L, Money.parse("INR 2,000.00"))
        assertEquals(null, Money.parse("abc"))
    }

    @Test
    fun formatsIndianGrouping() {
        assertEquals("₹1,23,45,678.90", Money.format(1234567890L))
        assertEquals("₹999.05", Money.format(99905L))
        assertEquals("-₹1,000", Money.format(-100000L, showPaise = false))
    }

    @Test
    fun compactForm() {
        assertEquals("₹950", Money.compact(95000L))
        assertEquals("₹12.5K", Money.compact(1250000L))
        assertEquals("₹3.4L", Money.compact(34000000L))
        assertEquals("₹1.2Cr", Money.compact(1200000000L))
    }
}
