package com.amitjha.kosh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.amitjha.kosh.di.AppContainer

/**
 * Fingerprint of an imported transaction the user deleted. The SMS importer skips messages whose fingerprint is
 * here, so "Scan inbox now" (180 days) and the daily catch-up scan don't bring deleted entries back.
 */
@Entity(tableName = "deleted_fingerprints")
data class DeletedFingerprintEntity(
    @PrimaryKey val fingerprint: String,
    val deletedAt: Long = System.currentTimeMillis(),
)

@Dao
interface TombstoneDao {
    @Query("SELECT EXISTS(SELECT 1 FROM deleted_fingerprints WHERE fingerprint = :fingerprint)")
    suspend fun contains(fingerprint: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: DeletedFingerprintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tombstones: List<DeletedFingerprintEntity>)

    @Query("DELETE FROM deleted_fingerprints WHERE fingerprint = :fingerprint")
    suspend fun remove(fingerprint: String)

    @Query("SELECT * FROM deleted_fingerprints")
    suspend fun all(): List<DeletedFingerprintEntity>
}

/** Deletes transactions the user removed while remembering imported ones, so they aren't re-imported. */
class TombstoneRepository(private val container: AppContainer) {
    private val dao get() = container.db.tombstones()

    /** Deletes the transaction; an imported one leaves its fingerprint behind. Returns the deleted row, if any. */
    suspend fun deleteTransaction(id: Long): TransactionEntity? {
        val txn = container.transactions.byId(id) ?: return null
        txn.fingerprint?.let { dao.insert(DeletedFingerprintEntity(it)) }
        container.transactions.delete(id)
        return txn
    }

    /** Puts back a transaction removed by [deleteTransaction] (Undo), forgetting its tombstone. */
    suspend fun undoDelete(txn: TransactionEntity): Long {
        txn.fingerprint?.let { dao.remove(it) }
        return container.transactions.add(txn.copy(id = 0))
    }
}

/** Shortcut so screens can reach the tombstone-aware delete without a new container field. */
val AppContainer.tombstones: TombstoneRepository get() = TombstoneRepository(this)
