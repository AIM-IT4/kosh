package com.amitjha.kosh.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.amitjha.kosh.security.LocalSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Receipt and warranty files, encrypted (AES-256-GCM under a key wrapped by the Android Keystore) in the app's
 * private storage. Photos are scaled to at most [MAX_SIDE] pixels and saved as JPEG to keep backups small; PDFs are
 * kept as they are, up to [MAX_BYTES].
 */
class AttachmentStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val key: ByteArray by lazy { LocalSecrets.key(context, KEY_NAME) }

    data class Saved(val fileName: String, val mimeType: String, val sizeBytes: Long)

    /** Copies [uri] (a photo or PDF the user picked) into encrypted storage. */
    suspend fun save(uri: Uri): Saved = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri) ?: "image/jpeg"
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't read the file")
        val (bytes, mime) = if (type == PDF) {
            require(raw.size <= MAX_BYTES) { "The file is larger than ${MAX_BYTES / 1_000_000} MB" }
            raw to PDF
        } else {
            shrink(raw) to JPEG
        }
        saveBytes(bytes, mime)
    }

    /** Stores already-prepared [bytes] (used when restoring a backup). */
    fun saveBytes(bytes: ByteArray, mimeType: String, fileName: String = UUID.randomUUID().toString() + ".bin"): Saved {
        File(dir, fileName).writeBytes(LocalSecrets.seal(key, bytes))
        return Saved(fileName, mimeType, bytes.size.toLong())
    }

    fun read(fileName: String): ByteArray = LocalSecrets.open(key, File(dir, safe(fileName)).readBytes())

    fun delete(fileName: String) {
        File(dir, safe(fileName)).delete()
    }

    /** Removes every stored file (erase all data, or before restoring a backup). */
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Writes the decrypted file to the app cache, for sharing or opening in another app; the caller deletes it. */
    fun exportToCache(fileName: String, mimeType: String): File {
        val ext = if (mimeType == PDF) "pdf" else "jpg"
        return File(context.cacheDir, "receipt-${fileName.substringBefore('.')}.$ext").apply { writeBytes(read(fileName)) }
    }

    private fun safe(name: String): String {
        require(name.isNotBlank() && !name.contains('/') && !name.contains("..")) { "Bad file name" }
        return name
    }

    private fun shrink(raw: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file isn't a photo or a PDF" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Can't read the photo")
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    companion object {
        const val DIR = "attachments"
        const val KEY_NAME = "attachments"
        const val PDF = "application/pdf"
        const val JPEG = "image/jpeg"
        const val MAX_SIDE = 1600
        const val JPEG_QUALITY = 82
        const val MAX_BYTES = 8_000_000
    }
}
