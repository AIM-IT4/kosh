package com.amitjha.kosh.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Thrown for any protocol violation: bad framing, oversized frame, failed authentication or a replay. */
class SyncProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Length-prefixed frames (4-byte big-endian length, at most [MAX_FRAME] bytes). */
object Frames {
    const val MAX_FRAME = 4 * 1024 * 1024

    fun write(out: OutputStream, bytes: ByteArray) {
        if (bytes.size > MAX_FRAME) throw SyncProtocolException("frame too large")
        val d = DataOutputStream(out)
        d.writeInt(bytes.size)
        d.write(bytes)
        d.flush()
    }

    fun read(input: InputStream, max: Int = MAX_FRAME): ByteArray {
        val d = DataInputStream(input)
        val len = d.readInt()
        if (len < 0 || len > max) throw SyncProtocolException("bad frame length $len")
        return ByteArray(len).also { d.readFully(it) }
    }
}

/**
 * An encrypted, replay-protected channel over a byte stream.
 *
 * Each direction has its own AES-256-GCM key. Every frame carries an 8-byte counter that must be exactly
 * one more than the previous frame in that direction; the 12-byte nonce is the 4-byte direction tag plus
 * the counter, so a nonce is never reused under a key. The counter and direction are authenticated as
 * associated data, so reordered, replayed or reflected frames fail.
 */
class SecureChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendDirection: Int,
    private val receiveDirection: Int,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    fun send(plaintext: ByteArray) {
        sendCounter++
        val aad = header(sendDirection, sendCounter)
        val ct = SyncCrypto.seal(sendKey, nonce(sendDirection, sendCounter), plaintext, aad)
        Frames.write(output, ByteBuffer.allocate(8 + ct.size).putLong(sendCounter).put(ct).array())
    }

    fun receive(): ByteArray {
        val frame = Frames.read(input)
        if (frame.size < 8 + 16) throw SyncProtocolException("frame too short")
        val counter = ByteBuffer.wrap(frame, 0, 8).long
        if (counter != receiveCounter + 1) throw SyncProtocolException("unexpected counter $counter (replay or reorder)")
        val ct = frame.copyOfRange(8, frame.size)
        val pt = try {
            SyncCrypto.open(receiveKey, nonce(receiveDirection, counter), ct, header(receiveDirection, counter))
        } catch (e: Exception) {
            throw SyncProtocolException("authentication failed", e)
        }
        receiveCounter = counter
        return pt
    }

    companion object {
        const val CLIENT_TO_SERVER = 0x4B43_5331 // "KCS1"
        const val SERVER_TO_CLIENT = 0x4B53_4331 // "KSC1"

        private fun nonce(direction: Int, counter: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()

        private fun header(direction: Int, counter: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()
    }
}
