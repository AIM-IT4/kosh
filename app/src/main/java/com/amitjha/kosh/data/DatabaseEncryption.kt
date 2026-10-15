package com.amitjha.kosh.data

import android.content.Context
import java.io.File

/**
 * Encrypts the database at rest with SQLCipher.
 *
 * New installs create an encrypted database directly. For people upgrading, the existing plaintext
 * database is copied into an encrypted one (`sqlcipher_export`), row counts are verified table by table,
 * and only then are the files swapped. Any failure leaves the plaintext database in place and working,
 * and the copy is retried on the next launch, so no data can be lost.
 */
object DatabaseEncryption {
    const val NAME = "kosh.db"
    private const val TMP_NAME = "kosh.db.enc-tmp"
    private const val BACKUP_NAME = "kosh.db.plain-bak"
    private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

    enum class Status { ENCRYPTED, PLAINTEXT_FALLBACK }

    /** What the last [prepare] call found; shown in Settings. */
    @Volatile
    var status: Status = Status.PLAINTEXT_FALLBACK
        private set

    @Volatile
    private var libraryLoaded = false

    fun loadLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("sqlcipher")
            libraryLoaded = true
        }
    }

    /**
     * Makes sure the database file at [NAME] is encrypted with [passphrase] (or absent, so Room creates it
     * encrypted). Returns true when Room should open it with SQLCipher, false to keep the plaintext file.
     */
    fun prepare(context: Context, passphrase: ByteArray): Boolean {
        loadLibrary()
        val db = context.getDatabasePath(NAME)
        val tmp = context.getDatabasePath(TMP_NAME)
        val backup = context.getDatabasePath(BACKUP_NAME)
        db.parentFile?.mkdirs()

        // Recover from a crash in the middle of a previous swap.
        if (!db.exists() && backup.exists()) {
            if (tmp.exists() && FileHeader.isEncryptedOrEmpty(tmp)) tmp.renameTo(db) else backup.renameTo(db)
        }
        deleteWithSideFiles(tmp)
        if (db.exists() && backup.exists()) deleteWithSideFiles(backup)

        val result = when (FileHeader.kindOf(db)) {
            FileHeader.Kind.MISSING -> true
            FileHeader.Kind.EMPTY -> { deleteWithSideFiles(db); true }
            FileHeader.Kind.ENCRYPTED -> true
            FileHeader.Kind.PLAINTEXT -> runCatching { migrate(db, tmp, backup, passphrase) }.getOrElse {
                deleteWithSideFiles(tmp)
                if (!db.exists() && backup.exists()) backup.renameTo(db)
                false
            }
        }
        status = if (result) Status.ENCRYPTED else Status.PLAINTEXT_FALLBACK
        return result
    }

    /**
     * The key can't be read (e.g. the Keystore was reset): an encrypted database can never be opened again.
     * Move it aside instead of crashing on every launch; a plaintext database is left alone.
     */
    fun quarantineUnreadable(context: Context) {
        val db = context.getDatabasePath(NAME)
        if (FileHeader.kindOf(db) == FileHeader.Kind.ENCRYPTED) {
            val aside = File(db.path + ".unreadable-" + System.currentTimeMillis())
            db.renameTo(aside)
            for (suffix in SIDE_FILES) File(db.path + suffix).delete()
        }
        status = Status.PLAINTEXT_FALLBACK
    }

    private fun migrate(db: File, tmp: File, backup: File, passphrase: ByteArray): Boolean {
        val key = String(passphrase, Charsets.US_ASCII)
        require(key.all { it in '0'..'9' || it in 'a'..'f' }) { "Unexpected passphrase format" }
        val plain = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            db.path, "", null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null,
        )
        try {
            val version = plain.version
            val tables = mutableListOf<String>()
            plain.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", emptyArray<String>()).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            val counts = tables.associateWith { t -> countRows(plain, "\"$t\"") }
            plain.rawExecSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS enc KEY '$key'")
            plain.rawExecSQL("SELECT sqlcipher_export('enc')")
            plain.rawExecSQL("PRAGMA enc.user_version = $version")
            for ((t, n) in counts) {
                check(countRows(plain, "enc.\"$t\"") == n) { "Row count mismatch in $t" }
            }
            plain.rawExecSQL("DETACH DATABASE enc")
        } finally {
            plain.close()
        }
        // Swap: plaintext → backup, encrypted copy → live name, then drop the plaintext files.
        check(db.renameTo(backup)) { "Could not move the plaintext database aside" }
        if (!tmp.renameTo(db)) {
            backup.renameTo(db)
            error("Could not move the encrypted database into place")
        }
        for (suffix in SIDE_FILES) File(db.path + suffix).delete()
        deleteWithSideFiles(backup)
        return true
    }

    private fun countRows(db: net.zetetic.database.sqlcipher.SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT count(*) FROM $table", emptyArray<String>()).use { c -> if (c.moveToFirst()) c.getLong(0) else -1 }

    private fun deleteWithSideFiles(file: File) {
        file.delete()
        for (suffix in SIDE_FILES) File(file.path + suffix).delete()
    }
}

/** Distinguishes plaintext SQLite files from encrypted ones by their 16-byte header (pure, unit-tested). */
object FileHeader {
    enum class Kind { MISSING, EMPTY, PLAINTEXT, ENCRYPTED }

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun kindOf(file: File): Kind {
        if (!file.exists()) return Kind.MISSING
        if (file.length() == 0L) return Kind.EMPTY
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        return classify(header, read)
    }

    fun classify(header: ByteArray, length: Int): Kind = when {
        length <= 0 -> Kind.EMPTY
        length >= 16 && header.copyOf(16).contentEquals(SQLITE_MAGIC) -> Kind.PLAINTEXT
        else -> Kind.ENCRYPTED
    }

    fun isEncryptedOrEmpty(file: File): Boolean = kindOf(file).let { it == Kind.ENCRYPTED || it == Kind.EMPTY }
}
