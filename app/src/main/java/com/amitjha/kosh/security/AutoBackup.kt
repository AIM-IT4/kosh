package com.amitjha.kosh.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.amitjha.kosh.data.prefs.AutoBackupPrefs
import com.amitjha.kosh.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Automatic backups: the same encrypted file as a manual backup, written on a schedule to a folder the user chose
 * (phone storage, an SD card, or a cloud folder exposed through the Files app), keeping the newest few.
 */
object AutoBackup {
    private const val PASSWORD = "auto_backup_password"
    const val PREFIX = "kosh-auto-"
    const val SUFFIX = ".kosh"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    fun hasPassword(context: Context): Boolean = runCatching { LocalSecrets.read(context, PASSWORD) != null }.getOrDefault(false)

    fun setPassword(context: Context, password: CharArray) {
        LocalSecrets.write(context, PASSWORD, String(password).toByteArray(Charsets.UTF_8))
    }

    fun clearPassword(context: Context) = LocalSecrets.delete(context, PASSWORD)

    /** Keeps read/write access to the picked folder across restarts. */
    fun rememberFolder(context: Context, folder: Uri) {
        context.contentResolver.takePersistableUriPermission(
            folder,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** Whether a backup is due under [prefs] at [nowMillis]. */
    fun isDue(prefs: AutoBackupPrefs, nowMillis: Long): Boolean =
        prefs.enabled && prefs.folderUri != null && nowMillis - prefs.lastMillis >= prefs.everyDays * DAY_MILLIS - SLACK_MILLIS

    /**
     * Writes one backup now and prunes old ones. Returns the new file's name; the outcome is also recorded in the
     * feature preferences so the settings screen can show it.
     */
    suspend fun run(context: Context, c: AppContainer, prefs: AutoBackupPrefs): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val folder = Uri.parse(prefs.folderUri ?: error("No backup folder chosen"))
            val password = LocalSecrets.read(context, PASSWORD) ?: error("Set the backup password again")
            val (bytes, _) = c.backup.encrypted(String(password, Charsets.UTF_8).toCharArray(), c.settings.repairVersion())
            val name = PREFIX + LocalDateTime.now().format(STAMP) + SUFFIX
            val tree = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val file = DocumentsContract.createDocument(context.contentResolver, tree, "application/octet-stream", name)
                ?: error("Can't create a file in the backup folder")
            context.contentResolver.openOutputStream(file, "wt")?.use { it.write(bytes) } ?: error("Can't write the backup file")
            prune(context, folder, prefs.keep)
            c.featurePrefs.recordAutoBackup(now, name, null)
            name
        } catch (e: Exception) {
            c.featurePrefs.recordAutoBackup(now, null, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Deletes this app's automatic backups in [folder] beyond the newest [keep]; other files are never touched. */
    private fun prune(context: Context, folder: Uri, keep: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val ours = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)) ours += id to name
            }
        }
        // Names sort by time ("kosh-auto-2026-10-16-0215.kosh"), so the oldest come first.
        ours.sortedBy { it.second }.dropLast(keep).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(folder, id)) }
        }
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    /** Runs that drift a little earlier than exactly 24 h still count. */
    private const val SLACK_MILLIS = 2L * 60 * 60 * 1000
}
