package com.amitjha.kosh.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class HouseholdSyncTest {

    private val key = ByteArray(32) { it.toByte() }

    private fun channelPair(): Pair<ByteArrayOutputStream, (ByteArray) -> SecureChannel> {
        val wire = ByteArrayOutputStream()
        val reader = { bytes: ByteArray ->
            SecureChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, key,
                SecureChannel.SERVER_TO_CLIENT, SecureChannel.CLIENT_TO_SERVER)
        }
        return wire to reader
    }

    @Test
    fun envelopeRoundTrip() {
        val (wire, reader) = channelPair()
        val sender = SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, key,
            SecureChannel.CLIENT_TO_SERVER, SecureChannel.SERVER_TO_CLIENT)
        sender.send("hello".toByteArray())
        sender.send("world".toByteArray())
        val r = reader(wire.toByteArray())
        assertEquals("hello", String(r.receive()))
        assertEquals("world", String(r.receive()))
    }

    @Test
    fun tamperedFrameIsRejected() {
        val (wire, reader) = channelPair()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, key,
            SecureChannel.CLIENT_TO_SERVER, SecureChannel.SERVER_TO_CLIENT).send("pay 500".toByteArray())
        val bytes = wire.toByteArray()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        try {
            reader(bytes).receive()
            fail("tampered frame accepted")
        } catch (e: SyncProtocolException) {
            assertTrue(e.message!!.contains("authentication"))
        }
    }

    @Test
    fun replayedFrameIsRejected() {
        val (wire, reader) = channelPair()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, key,
            SecureChannel.CLIENT_TO_SERVER, SecureChannel.SERVER_TO_CLIENT).send("once".toByteArray())
        val frame = wire.toByteArray()
        val r = reader(frame + frame)
        assertEquals("once", String(r.receive()))
        try {
            r.receive()
            fail("replayed frame accepted")
        } catch (e: SyncProtocolException) {
            assertTrue(e.message!!.contains("counter"))
        }
    }

    @Test
    fun reflectedFrameIsRejected() {
        // A frame sent client→server can't be bounced back to the client: the direction is authenticated.
        val wire = ByteArrayOutputStream()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, key,
            SecureChannel.CLIENT_TO_SERVER, SecureChannel.SERVER_TO_CLIENT).send("x".toByteArray())
        val client = SecureChannel(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(), key, key,
            SecureChannel.CLIENT_TO_SERVER, SecureChannel.SERVER_TO_CLIENT)
        try {
            client.receive()
            fail("reflected frame accepted")
        } catch (_: SyncProtocolException) {
        }
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = SyncCrypto.hkdf(ikm, salt, info, 42)
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
    }

    // ---------------------------------------------------------------- handshake

    private val alice = Identity("alice-id", "Alice's phone")
    private val bob = Identity("bob-id", "Bob's phone")

    private fun runHandshake(
        clientAuth: ByteArray,
        serverAuth: ByteArray?,
        mode: SyncMode = SyncMode.PAIR,
        expectedPeer: String? = null,
    ): Pair<Result<HandshakeResult>, Result<HandshakeResult>> {
        val c2s = PipedInputStream(1 shl 16)
        val c2sOut = PipedOutputStream(c2s)
        val s2c = PipedInputStream(1 shl 16)
        val s2cOut = PipedOutputStream(s2c)
        var serverResult: Result<HandshakeResult>? = null
        val t = thread {
            serverResult = runCatching { Handshake.server(c2s, s2cOut, bob) { _, _ -> serverAuth } }
            if (serverResult!!.isFailure) runCatching { s2cOut.close() }
        }
        val clientResult = runCatching { Handshake.client(s2c, c2sOut, alice, mode, clientAuth, expectedPeer) }
        if (clientResult.isFailure) runCatching { c2sOut.close() }
        t.join(5_000)
        return clientResult to serverResult!!
    }

    @Test
    fun pairingDerivesTheSameKeysAndChannelWorks() {
        val secret = SyncCrypto.randomBytes(32)
        val c2s = PipedInputStream(1 shl 16)
        val c2sOut = PipedOutputStream(c2s)
        val s2c = PipedInputStream(1 shl 16)
        val s2cOut = PipedOutputStream(s2c)
        var server: HandshakeResult? = null
        var received: String? = null
        val t = thread {
            server = Handshake.server(c2s, s2cOut, bob) { mode, _ -> if (mode == SyncMode.PAIR) secret else null }
            received = String(server!!.channel.receive())
            server!!.channel.send("ack".toByteArray())
        }
        val client = Handshake.client(s2c, c2sOut, alice, SyncMode.PAIR, secret, expectedPeerId = "bob-id")
        client.channel.send("hi".toByteArray())
        assertEquals("ack", String(client.channel.receive()))
        t.join(5_000)
        assertEquals("hi", received)
        assertEquals(bob, client.peer)
        assertEquals(alice, server!!.peer)
        assertNotNull(client.pairKey)
        assertArrayEquals(client.pairKey, server!!.pairKey)
    }

    @Test
    fun wrongSecretFailsBothSides() {
        val (client, server) = runHandshake(SyncCrypto.randomBytes(32), SyncCrypto.randomBytes(32))
        assertTrue(client.isFailure)
        assertTrue(server.isFailure)
    }

    @Test
    fun serverRefusesUnknownDevices() {
        val (client, server) = runHandshake(SyncCrypto.randomBytes(32), null, SyncMode.SYNC)
        assertTrue(client.isFailure)
        assertTrue(server.isFailure)
    }

    @Test
    fun clientRejectsUnexpectedPeer() {
        val k = SyncCrypto.randomBytes(32)
        val (client, _) = runHandshake(k, k, SyncMode.SYNC, expectedPeer = "someone-else")
        assertTrue(client.isFailure)
    }

    @Test
    fun loopbackSocketSync() {
        val pairKey = SyncCrypto.randomBytes(32)
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val bobItems = listOf(item("b1", "bob-id", 10), item("b2", "bob-id", 20))
        val aliceItems = listOf(item("a1", "alice-id", 15))
        var serverOutcome: SyncExchange.Outcome? = null
        val t = thread {
            serverSocket.accept().use { s ->
                val hs = Handshake.server(s.getInputStream(), s.getOutputStream(), bob) { _, id -> if (id == "alice-id") pairKey else null }
                serverOutcome = SyncExchange.asServer(hs.channel, bobItems, bobItems, hs.peer.id)
            }
        }
        Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort).use { s ->
            s.soTimeout = 5_000
            val hs = Handshake.client(s.getInputStream(), s.getOutputStream(), alice, SyncMode.SYNC, pairKey, "bob-id")
            val outcome = SyncExchange.asClient(hs.channel, aliceItems, aliceItems, hs.peer.id)
            assertEquals(1, outcome.sent)
            assertEquals(setOf("b1", "b2"), outcome.merged.map { it.uuid }.toSet())
        }
        t.join(5_000)
        serverSocket.close()
        assertEquals(listOf("a1"), serverOutcome!!.merged.map { it.uuid })
    }

    // ---------------------------------------------------------------- merge

    private fun item(uuid: String, owner: String, updatedAt: Long, payload: String = "{\"amount\":1}", deleted: Boolean = false, kind: String = "TXN") =
        SyncItem(uuid, kind, owner, owner, payload, updatedAt, deleted)

    @Test
    fun lastWriterWins() {
        val local = listOf(item("x", "bob-id", 100, "{\"v\":1}"))
        val newer = HouseholdMerge.merge(local, listOf(item("x", "bob-id", 200, "{\"v\":2}")), "bob-id")
        assertEquals("{\"v\":2}", newer.upserts.single().payload)
        val older = HouseholdMerge.merge(local, listOf(item("x", "bob-id", 50, "{\"v\":0}")), "bob-id")
        assertTrue(older.upserts.isEmpty())
    }

    @Test
    fun tombstoneWinsTieAndPropagates() {
        val local = listOf(item("x", "bob-id", 100))
        val r = HouseholdMerge.merge(local, listOf(item("x", "bob-id", 100, "", deleted = true)), "bob-id")
        assertTrue(r.upserts.single().deleted)
    }

    @Test
    fun mergeIsIdempotent() {
        val incoming = listOf(item("x", "bob-id", 100), item("y", "bob-id", 120))
        val first = HouseholdMerge.merge(emptyList(), incoming, "bob-id")
        val second = HouseholdMerge.merge(first.upserts, incoming, "bob-id")
        assertEquals(2, first.upserts.size)
        assertTrue(second.upserts.isEmpty())
    }

    @Test
    fun peerCannotOverwriteMyRecordsOrSpoofOwners() {
        val local = listOf(item("mine", "alice-id", 100))
        val r = HouseholdMerge.merge(
            local,
            listOf(item("mine", "alice-id", 999), item("mine", "bob-id", 999), item("ghost", "mallory", 5), item("ok", "bob-id", 5, kind = "SECRET")),
            peerId = "bob-id",
        )
        assertTrue(r.upserts.isEmpty())
        assertEquals(4, r.rejected)
    }

    @Test
    fun wireCodecRoundTrip() {
        val items = listOf(item("x", "bob-id", 1, "{\"n\":\"₹ chai\"}"), item("y", "bob-id", 2, "", deleted = true))
        assertEquals(items, HouseholdMerge.decode(HouseholdMerge.encode(items)))
    }

    @Test
    fun pairingCodeRoundTripAndRejection() {
        val code = PairingCode("id1", "Pixel", "192.168.1.5", 40123, SyncCrypto.randomBytes(32))
        val back = PairingCode.decode(code.encode())!!
        assertEquals(code.host, back.host)
        assertEquals(code.port, back.port)
        assertArrayEquals(code.secret, back.secret)
        assertNull(PairingCode.decode("https://evil.example"))
        assertNull(PairingCode.decode("kosh-sync:not-base64!!"))
    }
}
