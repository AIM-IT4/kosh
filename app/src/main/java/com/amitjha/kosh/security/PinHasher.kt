package com.amitjha.kosh.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Which profile a PIN opens. */
enum class PinMatch { REAL, DECOY, NONE }

/**
 * Salted PBKDF2-HMAC-SHA256 hashes for the app PIN and the decoy PIN, stored as
 * `pbkdf2$<iterations>$<salt>$<hash>` (Base64, no padding).
 */
object PinHasher {
    const val ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    fun hash(pin: String, iterations: Int = ITERATIONS, salt: ByteArray = randomSalt()): String {
        val b64 = Base64.getEncoder().withoutPadding()
        return "pbkdf2\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(pin, salt, iterations))}"
    }

    fun verify(pin: String, stored: String?): Boolean {
        if (stored == null) return false
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in MIN_ITERATIONS..MAX_ITERATIONS } ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    /**
     * Checks [pin] against both hashes. Both are always computed, so the time taken doesn't reveal whether a
     * decoy PIN exists or which one matched.
     */
    fun match(pin: String, realHash: String?, decoyHash: String?): PinMatch {
        val real = verify(pin, realHash ?: DUMMY)
        val decoy = verify(pin, decoyHash ?: DUMMY)
        return when {
            realHash != null && real -> PinMatch.REAL
            decoyHash != null && decoy -> PinMatch.DECOY
            else -> PinMatch.NONE
        }
    }

    /**
     * Wait after [failures] wrong PINs in a row: none for the first 4, then 30 s doubling to at most 15 min.
     */
    fun lockoutMillis(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(5)
        return (30_000L shl doublings).coerceAtMost(15 * 60_000L)
    }

    const val FREE_ATTEMPTS = 5

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomSalt() = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** A well-formed hash nobody's PIN matches, used to keep timing equal when one hash is missing. */
    private val DUMMY: String by lazy { hash("never-a-pin", ITERATIONS, ByteArray(SALT_BYTES)) }
}
