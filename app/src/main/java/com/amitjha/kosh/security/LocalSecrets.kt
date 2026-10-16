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
import javax.crypto.spec.SecretKeySpec

/**
 * Small secrets kept on this phone only (the receipts key, the automatic-backup password), each stored in its own
 * file wrapped with an AES-256-GCM key that lives in the Android Keystore, the same way as [DatabaseKey].
 */
object LocalSecrets {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kosh_local_wrap_v1"
    private const val FORMAT_VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** A random 32-byte key named [name], created on first use. */
    @Synchronized
    fun key(context: Context, name: String): ByteArray =
        read(context, name) ?: ByteArray(32).also { SecureRandom().nextBytes(it); write(context, name, it) }

    @Synchronized
    fun read(context: Context, name: String): ByteArray? {
        val file = file(context, name)
        if (!file.exists()) return null
        return unwrap(file.readBytes())
    }

    @Synchronized
    fun write(context: Context, name: String, value: ByteArray) {
        val file = file(context, name)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(wrap(value))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not store $name" }
        }
    }

    @Synchronized
    fun delete(context: Context, name: String) {
        file(context, name).delete()
    }

    /** AES-256-GCM with a fresh IV, for data encrypted under a key from [key]: `iv || ciphertext+tag`. */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "Damaged data" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun file(context: Context, name: String) = File(context.filesDir, "secret_$name.bin")

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
        require(blob.size > 1 + IV_BYTES && blob[0] == FORMAT_VERSION) { "Unknown secret file format" }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = ks.getKey(ALIAS, null) as? SecretKey ?: error("Keystore key missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }
}
