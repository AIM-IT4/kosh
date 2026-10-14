package com.amitjha.kosh.designsystem

import com.amitjha.kosh.designsystem.components.KeypadExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeypadExpressionTest {
    private fun type(vararg keys: String) = KeypadExpression().apply { keys.forEach { press(it) } }

    @Test fun digitsAndDecimals() {
        val e = type("1", "2", "5", ".", "5", "0", "7")
        assertEquals("125.50", e.text)
        assertEquals(12550L, e.valuePaise())
    }

    @Test fun leadingZeroAndDoubleDecimal() {
        assertEquals("5", type("0", "5").text)
        assertEquals("0.5", type(".", ".", "5").text)
    }

    @Test fun operatorPrecedence() {
        val e = type("1", "0", "0", "+", "2", "0", "×", "3")
        assertEquals(16000L, e.valuePaise())
        e.press("=")
        assertEquals("160", e.text)
    }

    @Test fun replaceTrailingOperatorAndBackspace() {
        val e = type("5", "0", "+", "-")
        assertEquals("50-", e.text)
        assertEquals(5000L, e.valuePaise())
        e.press("⌫")
        assertEquals("50", e.text)
    }

    @Test fun divisionAndNeverNegative() {
        assertEquals(3333L, type("1", "0", "0", "÷", "3").valuePaise())
        assertEquals(0L, type("1", "0", "-", "2", "0").valuePaise())
        assertNull(type("5", "÷", "0").valuePaise())
        assertNull(KeypadExpression().valuePaise())
    }
}
