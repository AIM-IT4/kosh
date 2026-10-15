package com.amitjha.kosh.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    // Fewer iterations keep the tests fast; the format and checks are the same.
    private val fast = 10_000
    private val real = PinHasher.hash("2580", fast)
    private val decoy = PinHasher.hash("1397", fast)

    @Test fun hashFormatAndSalt() {
        val parts = real.split('$')
        assertEquals(4, parts.size)
        assertEquals("pbkdf2", parts[0])
        assertEquals("10000", parts[1])
        // A fresh random salt each time, so equal PINs don't produce equal hashes.
        assertNotEquals(real, PinHasher.hash("2580", fast))
        assertTrue(PinHasher.hash("2580").startsWith("pbkdf2\$200000\$"))
    }

    @Test fun verify() {
        assertTrue(PinHasher.verify("2580", real))
        assertFalse(PinHasher.verify("2581", real))
        assertFalse(PinHasher.verify("2580", null))
        assertFalse(PinHasher.verify("2580", "garbage"))
        assertFalse(PinHasher.verify("2580", "pbkdf2\$1\$AAAA\$AAAA")) // too few iterations is rejected
    }

    @Test fun deterministicWithFixedSalt() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHasher.hash("123456", fast, salt), PinHasher.hash("123456", fast, salt))
    }

    @Test fun modeSelection() {
        assertEquals(PinMatch.REAL, PinHasher.match("2580", real, decoy))
        assertEquals(PinMatch.DECOY, PinHasher.match("1397", real, decoy))
        assertEquals(PinMatch.NONE, PinHasher.match("0000", real, decoy))
        assertEquals(PinMatch.REAL, PinHasher.match("2580", real, null))
        assertEquals(PinMatch.NONE, PinHasher.match("1397", real, null))
        // With no PIN set, nothing matches.
        assertEquals(PinMatch.NONE, PinHasher.match("2580", null, null))
    }

    @Test fun pinValidation() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertTrue(PinHasher.isValidPin("12345678"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("123456789"))
        assertFalse(PinHasher.isValidPin("12a4"))
    }

    @Test fun lockoutGrows() {
        repeat(PinHasher.FREE_ATTEMPTS) { assertEquals(0L, PinHasher.lockoutMillis(it)) }
        assertEquals(30_000L, PinHasher.lockoutMillis(5))
        assertEquals(60_000L, PinHasher.lockoutMillis(6))
        assertEquals(120_000L, PinHasher.lockoutMillis(7))
        assertEquals(15 * 60_000L, PinHasher.lockoutMillis(10))
        assertEquals(15 * 60_000L, PinHasher.lockoutMillis(1_000))
    }
}
