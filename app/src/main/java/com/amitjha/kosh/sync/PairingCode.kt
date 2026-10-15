package com.amitjha.kosh.sync

import org.json.JSONObject
import java.util.Base64

/**
 * The content of the pairing QR code: who to connect to and the one-time secret that authenticates the
 * first exchange. The secret is only valid while the host's pairing screen is open.
 */
data class PairingCode(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val secret: ByteArray,
) {
    fun encode(): String {
        val json = JSONObject()
            .put("v", Handshake.VERSION).put("id", deviceId).put("name", deviceName)
            .put("host", host).put("port", port)
            .put("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    }

    companion object {
        const val PREFIX = "kosh-sync:"

        /** Returns null for anything that isn't a valid Kosh pairing code of this protocol version. */
        fun decode(text: String): PairingCode? = runCatching {
            if (!text.startsWith(PREFIX)) return null
            val json = JSONObject(String(Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))))
            if (json.getInt("v") != Handshake.VERSION) return null
            val secret = Base64.getUrlDecoder().decode(json.getString("secret"))
            val port = json.getInt("port")
            if (secret.size != 32 || port !in 1..65535) return null
            PairingCode(json.getString("id"), json.getString("name").take(64), json.getString("host"), port, secret)
        }.getOrNull()
    }
}
