package com.amitjha.kosh.sync

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

enum class SyncMode { PAIR, SYNC }

data class Identity(val id: String, val name: String)

class HandshakeResult(
    val peer: Identity,
    val channel: SecureChannel,
    /** Long-term key to remember for the peer; only set in [SyncMode.PAIR]. */
    val pairKey: ByteArray?,
)

/**
 * Authenticated key exchange for household sync (see docs/SYNC_PROTOCOL.md).
 *
 * Both sides generate a fresh P-256 key pair per connection. The exchange is authenticated with a
 * symmetric key both already hold: the 32-byte secret from the pairing QR code ([SyncMode.PAIR]) or the
 * long-term pair key derived at pairing time ([SyncMode.SYNC]). Each side proves knowledge of that key
 * with an HMAC over the hash of the whole transcript (protocol version, mode, ids, names, public keys and
 * nonces), so a same-Wi-Fi attacker can't impersonate either side, tamper with the exchange or force an
 * older version. Session keys come from HKDF over the ECDH secret, salted with the authentication key,
 * so recorded traffic stays private even if the pair key leaks later (forward secrecy per session).
 */
object Handshake {
    const val VERSION = 1
    private const val MAX_HELLO = 16 * 1024
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun client(
        input: InputStream,
        output: OutputStream,
        me: Identity,
        mode: SyncMode,
        authKey: ByteArray,
        expectedPeerId: String? = null,
    ): HandshakeResult {
        val kp = SyncCrypto.newKeyPair()
        val pub = SyncCrypto.encodePublic(kp.public)
        val nc = SyncCrypto.randomBytes(32)
        Frames.write(
            output,
            JSONObject()
                .put("v", VERSION).put("mode", mode.name).put("id", me.id).put("name", me.name)
                .put("pub", b64e.encodeToString(pub)).put("nonce", b64e.encodeToString(nc))
                .toString().toByteArray(),
        )
        val hello = parse(Frames.read(input, MAX_HELLO))
        if (hello.optInt("v", -1) != VERSION) throw SyncProtocolException("unsupported protocol version")
        val peer = Identity(hello.getString("id"), hello.getString("name").take(64))
        if (expectedPeerId != null && peer.id != expectedPeerId) throw SyncProtocolException("unexpected device")
        val spub = b64d.decode(hello.getString("pub"))
        val ns = b64d.decode(hello.getString("nonce"))
        val th = transcript(mode, me, pub, nc, peer, spub, ns)
        val serverMac = b64d.decode(hello.getString("mac"))
        if (!SyncCrypto.constantTimeEquals(serverMac, SyncCrypto.hmac(authKey, SERVER, th))) {
            throw SyncProtocolException("the other phone could not prove it is paired")
        }
        Frames.write(output, JSONObject().put("mac", b64e.encodeToString(SyncCrypto.hmac(authKey, CLIENT, th))).toString().toByteArray())
        val secret = SyncCrypto.ecdh(kp, SyncCrypto.decodePublic(spub))
        return derive(input, output, secret, authKey, th, mode, isClient = true, peer)
    }

    /**
     * @param authKeyFor returns the authentication key for a connecting device in a mode, or null to refuse
     *   (e.g. pairing isn't open, or the device isn't paired).
     */
    fun server(
        input: InputStream,
        output: OutputStream,
        me: Identity,
        authKeyFor: (SyncMode, String) -> ByteArray?,
    ): HandshakeResult {
        val hello = parse(Frames.read(input, MAX_HELLO))
        if (hello.optInt("v", -1) != VERSION) throw SyncProtocolException("unsupported protocol version")
        val mode = runCatching { SyncMode.valueOf(hello.getString("mode")) }.getOrNull()
            ?: throw SyncProtocolException("bad mode")
        val peer = Identity(hello.getString("id"), hello.getString("name").take(64))
        val authKey = authKeyFor(mode, peer.id) ?: throw SyncProtocolException("not allowed")
        val cpub = b64d.decode(hello.getString("pub"))
        val nc = b64d.decode(hello.getString("nonce"))
        val kp = SyncCrypto.newKeyPair()
        val pub = SyncCrypto.encodePublic(kp.public)
        val ns = SyncCrypto.randomBytes(32)
        val th = transcript(mode, peer, cpub, nc, me, pub, ns)
        Frames.write(
            output,
            JSONObject()
                .put("v", VERSION).put("id", me.id).put("name", me.name)
                .put("pub", b64e.encodeToString(pub)).put("nonce", b64e.encodeToString(ns))
                .put("mac", b64e.encodeToString(SyncCrypto.hmac(authKey, SERVER, th)))
                .toString().toByteArray(),
        )
        val finish = parse(Frames.read(input, MAX_HELLO))
        val clientMac = b64d.decode(finish.getString("mac"))
        if (!SyncCrypto.constantTimeEquals(clientMac, SyncCrypto.hmac(authKey, CLIENT, th))) {
            throw SyncProtocolException("the other phone could not prove it is paired")
        }
        val secret = SyncCrypto.ecdh(kp, SyncCrypto.decodePublic(cpub))
        return derive(input, output, secret, authKey, th, mode, isClient = false, peer)
    }

    private val SERVER = "kosh-sync server".toByteArray()
    private val CLIENT = "kosh-sync client".toByteArray()

    private fun derive(
        input: InputStream,
        output: OutputStream,
        secret: ByteArray,
        authKey: ByteArray,
        th: ByteArray,
        mode: SyncMode,
        isClient: Boolean,
        peer: Identity,
    ): HandshakeResult {
        val c2s = SyncCrypto.hkdf(secret, authKey, "kosh c2s".toByteArray() + th)
        val s2c = SyncCrypto.hkdf(secret, authKey, "kosh s2c".toByteArray() + th)
        val pairKey = if (mode == SyncMode.PAIR) SyncCrypto.hkdf(secret, authKey, "kosh pair".toByteArray() + th) else null
        val channel = if (isClient) {
            SecureChannel(input, output, c2s, s2c, SecureChannel.CLIENT_TO_SERVER, SecureChannel.SERVER_TO_CLIENT)
        } else {
            SecureChannel(input, output, s2c, c2s, SecureChannel.SERVER_TO_CLIENT, SecureChannel.CLIENT_TO_SERVER)
        }
        return HandshakeResult(peer, channel, pairKey)
    }

    /** Hash of everything both sides agreed on, in a fixed order with length prefixes. */
    private fun transcript(
        mode: SyncMode,
        client: Identity,
        clientPub: ByteArray,
        clientNonce: ByteArray,
        server: Identity,
        serverPub: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray {
        val parts = listOf(
            "kosh-sync".toByteArray(), byteArrayOf(VERSION.toByte()), mode.name.toByteArray(),
            client.id.toByteArray(), client.name.toByteArray(), clientPub, clientNonce,
            server.id.toByteArray(), server.name.toByteArray(), serverPub, serverNonce,
        )
        val buf = java.io.ByteArrayOutputStream()
        parts.forEach { p ->
            buf.write(java.nio.ByteBuffer.allocate(4).putInt(p.size).array())
            buf.write(p)
        }
        return SyncCrypto.sha256(buf.toByteArray())
    }

    private fun parse(bytes: ByteArray): JSONObject = try {
        JSONObject(String(bytes))
    } catch (e: Exception) {
        throw SyncProtocolException("malformed message", e)
    }
}
