package com.amitjha.kosh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A record in the shared household space, synced with the paired phone.
 *
 * Own items mirror a local transaction, budget or goal the user marked as household (`localId` set,
 * `ownerId` = this device); items received from the partner have `localId == null`. `payload` is the
 * record as JSON (see `sync/HouseholdCodec`). Deletions are kept as tombstones (`deleted`, empty payload)
 * so they propagate. Merging is last-writer-wins on `updatedAt`, ties broken by `ownerId`.
 */
@Entity(
    tableName = "household_items",
    indices = [Index(value = ["kind", "localId"])],
    primaryKeys = ["uuid"],
)
data class HouseholdItemEntity(
    val uuid: String,
    /** TXN, BUDGET or GOAL. */
    val kind: String,
    val ownerId: String,
    val ownerName: String,
    val localId: Long?,
    val payload: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Dao
interface HouseholdDao {
    @Query("SELECT * FROM household_items")
    suspend fun all(): List<HouseholdItemEntity>

    @Query("SELECT * FROM household_items WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeLive(): Flow<List<HouseholdItemEntity>>

    @Query("SELECT * FROM household_items WHERE kind = :kind AND localId = :localId LIMIT 1")
    suspend fun ownFor(kind: String, localId: Long): HouseholdItemEntity?

    @Query("SELECT localId FROM household_items WHERE kind = :kind AND localId IS NOT NULL AND deleted = 0")
    fun observeSharedIds(kind: String): Flow<List<Long>>

    @Upsert
    suspend fun upsert(item: HouseholdItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<HouseholdItemEntity>)

    /** Removes everything received from a partner (used when unpairing). */
    @Query("DELETE FROM household_items WHERE localId IS NULL")
    suspend fun deleteReceived()
}
