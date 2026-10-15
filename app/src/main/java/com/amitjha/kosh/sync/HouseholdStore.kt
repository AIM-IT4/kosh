package com.amitjha.kosh.sync

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairedPeer(val id: String, val name: String)

data class HouseholdState(
    val me: Identity,
    val peer: PairedPeer?,
    val lastSyncMillis: Long?,
    val lastSummary: String?,
)

/**
 * This device's household identity and the paired phone.
 *
 * The pair key never touches disk in the clear: it is wrapped with an AES-256-GCM key that lives in the
 * Android Keystore (hardware-backed where available) and can't be exported. Kept in its own preferences
 * file, which is excluded from backups, so restoring a backup on another phone doesn't carry the pairing.
 */
class HouseholdStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("household", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<HouseholdState> = _state.asStateFlow()

    val me: Identity get() = _state.value.me

    private fun load(): HouseholdState {
        var id = prefs.getString(K_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(K_ID, id).apply()
        }
        val name = prefs.getString(K_NAME, null) ?: defaultName()
        val peerId = prefs.getString(K_PEER_ID, null)
        val peer = if (peerId != null && prefs.contains(K_PEER_KEY)) PairedPeer(peerId, prefs.getString(K_PEER_NAME, "Partner")!!) else null
        val last = prefs.getLong(K_LAST_SYNC, 0L).takeIf { it > 0 }
        return HouseholdState(Identity(id, name), peer, last, prefs.getString(K_SUMMARY, null))
    }

    fun setDeviceName(name: String) {
        val clean = name.trim().take(40).ifBlank { defaultName() }
        prefs.edit().putString(K_NAME, clean).apply()
        _state.value = load()
    }

    fun savePeer(peer: Identity, pairKey: ByteArray) {
        prefs.edit()
            .putString(K_PEER_ID, peer.id)
            .putString(K_PEER_NAME, peer.name)
            .putString(K_PEER_KEY, wrap(pairKey))
            .remove(K_LAST_SYNC).remove(K_SUMMARY)
            .apply()
        _state.value = load()
    }

    fun pairKey(): ByteArray? = prefs.getString(K_PEER_KEY, null)?.let { runCatching { unwrap(it) }.getOrNull() }

    fun lastAddress(): Pair<String, Int>? {
        val host = prefs.getString(K_PEER_HOST, null) ?: return null
        val port = prefs.getInt(K_PEER_PORT, 0).takeIf { it > 0 } ?: return null
        return host to port
    }

    fun rememberAddress(host: String, port: Int) {
        prefs.edit().putString(K_PEER_HOST, host).putInt(K_PEER_PORT, port).apply()
    }

    fun recordSync(summary: String) {
        prefs.edit().putLong(K_LAST_SYNC, System.currentTimeMillis()).putString(K_SUMMARY, summary).apply()
        _state.value = load()
    }

    fun unpair() {
        prefs.edit()
            .remove(K_PEER_ID).remove(K_PEER_NAME).remove(K_PEER_KEY)
            .remove(K_PEER_HOST).remove(K_PEER_PORT).remove(K_LAST_SYNC).remove(K_SUMMARY)
            .apply()
        _state.value = load()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(secret: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(secret))
    }

    private fun unwrap(blob: String): ByteArray {
        val bytes = Base64.getDecoder().decode(blob)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    private fun defaultName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim().take(40)

    private companion object {
        const val ALIAS = "kosh_household_v1"
        const val K_ID = "device_id"
        const val K_NAME = "device_name"
        const val K_PEER_ID = "peer_id"
        const val K_PEER_NAME = "peer_name"
        const val K_PEER_KEY = "peer_key"
        const val K_PEER_HOST = "peer_host"
        const val K_PEER_PORT = "peer_port"
        const val K_LAST_SYNC = "last_sync"
        const val K_SUMMARY = "last_summary"
    }
}
