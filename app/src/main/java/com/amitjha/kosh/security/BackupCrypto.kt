package com.amitjha.kosh.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for backup files: PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM.
 *
 * File layout: magic "KOSH" (4 bytes), version (1), iterations (4), salt (16), IV (12), ciphertext+tag.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte())
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 210_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    class WrongPasswordException : Exception("Wrong password or damaged backup file")

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        val header = header(iterations, salt, iv)
        cipher.updateAAD(header)
        val ct = cipher.doFinal(plain)
        return header + ct
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        val headerLen = MAGIC.size + 1 + 4 + SALT_LEN + IV_LEN
        if (data.size < headerLen || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a Kosh backup file")
        }
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        val version = buf.get()
        require(version == VERSION) { "Unsupported backup version $version" }
        val iterations = buf.int
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = ByteArray(IV_LEN).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(data.copyOfRange(0, headerLen))
        return try {
            cipher.doFinal(data, headerLen, data.size - headerLen)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun header(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 1 + 4 + SALT_LEN + IV_LEN)
            .put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
