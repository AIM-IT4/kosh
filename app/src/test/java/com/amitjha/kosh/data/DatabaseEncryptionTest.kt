package com.amitjha.kosh.data

import com.amitjha.kosh.security.KeyHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseEncryptionTest {

    @Test
    fun plaintextSqliteHeaderIsRecognised() {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(84)
        assertEquals(FileHeader.Kind.PLAINTEXT, FileHeader.classify(header, header.size))
    }

    @Test
    fun randomBytesCountAsEncrypted() {
        val header = ByteArray(16) { (it * 37 + 11).toByte() }
        assertEquals(FileHeader.Kind.ENCRYPTED, FileHeader.classify(header, 16))
    }

    @Test
    fun shortOrEmptyFiles() {
        assertEquals(FileHeader.Kind.EMPTY, FileHeader.classify(ByteArray(16), 0))
        // A truncated file can't be a valid plaintext database; never try to open it as one.
        assertEquals(FileHeader.Kind.ENCRYPTED, FileHeader.classify("SQLite".toByteArray(), 6))
    }

    @Test
    fun fileKinds() {
        val dir = createTempDirectory()
        val missing = File(dir, "none.db")
        assertEquals(FileHeader.Kind.MISSING, FileHeader.kindOf(missing))
        val empty = File(dir, "empty.db").apply { writeBytes(ByteArray(0)) }
        assertEquals(FileHeader.Kind.EMPTY, FileHeader.kindOf(empty))
        val plain = File(dir, "plain.db").apply { writeBytes("SQLite format 3\u0000".toByteArray() + ByteArray(100)) }
        assertEquals(FileHeader.Kind.PLAINTEXT, FileHeader.kindOf(plain))
        val enc = File(dir, "enc.db").apply { writeBytes(ByteArray(4096) { 7 }) }
        assertEquals(FileHeader.Kind.ENCRYPTED, FileHeader.kindOf(enc))
        assertTrue(FileHeader.isEncryptedOrEmpty(enc))
    }

    @Test
    fun passphraseHexIsLowercaseAndFullLength() {
        val hex = KeyHex.encode(ByteArray(32) { it.toByte() } + byteArrayOf(-1, 0x0A))
        assertEquals(68, hex.length)
        assertTrue(hex.startsWith("000102"))
        assertTrue(hex.endsWith("ff0a"))
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("kosh-enc").toFile().apply { deleteOnExit() }
}
