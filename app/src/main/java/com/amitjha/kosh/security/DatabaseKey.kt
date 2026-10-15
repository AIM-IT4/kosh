package com.amitjha.kosh.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database passphrase: 32 random bytes (as 64 hex characters), stored on disk only in encrypted
 * form, wrapped with an AES-256-GCM key that lives in the Android Keystore and never leaves it.
 */
object DatabaseKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kosh_db_key_wrap_v1"
    private const val FILE_NAME = "db_key.bin"
    private const val FORMAT_VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Thrown when a stored passphrase exists but can no longer be unwrapped (e.g. the Keystore was reset). */
    class KeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** The passphrase bytes for SQLCipher, creating and storing a new one on first use. */
    @Synchronized
    fun passphrase(context: Context): ByteArray {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            return runCatching { unwrap(file.readBytes()) }
                .getOrElse { throw KeyUnavailableException("Stored database key cannot be unwrapped", it) }
        }
        val hex = KeyHex.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val wrapped = wrap(hex.toByteArray(Charsets.US_ASCII))
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not store the database key" }
        }
        return hex.toByteArray(Charsets.US_ASCII)
    }

    /** Whether a passphrase has been created on this device. */
    fun exists(context: Context): Boolean = File(context.filesDir, FILE_NAME).exists()

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV size" }
        return byteArrayOf(FORMAT_VERSION) + iv + cipher.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_BYTES && blob[0] == FORMAT_VERSION) { "Unknown key file format" }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = ks.getKey(ALIAS, null) as? SecretKey ?: error("Keystore key missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }
}

/** Lower-case hex encoding for the passphrase (pure, unit-tested). */
object KeyHex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
    }
}
