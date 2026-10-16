package com.amitjha.kosh.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SplitDao {
    @Query("SELECT * FROM txn_splits WHERE transactionId = :transactionId ORDER BY id")
    abstract fun observeFor(transactionId: Long): Flow<List<SplitEntity>>

    @Query("SELECT * FROM txn_splits WHERE transactionId = :transactionId ORDER BY id")
    abstract suspend fun forTransaction(transactionId: Long): List<SplitEntity>

    @Query("SELECT * FROM txn_splits")
    abstract suspend fun all(): List<SplitEntity>

    @Query("SELECT * FROM txn_splits")
    abstract fun observeAll(): Flow<List<SplitEntity>>

    @Query("DELETE FROM txn_splits WHERE transactionId = :transactionId")
    abstract suspend fun clear(transactionId: Long)

    @Insert
    abstract suspend fun insertAll(splits: List<SplitEntity>)

    /** Replaces the transaction's parts; an empty list removes the split. */
    @Transaction
    open suspend fun replace(transactionId: Long, splits: List<SplitEntity>) {
        clear(transactionId)
        if (splits.isNotEmpty()) insertAll(splits.map { it.copy(id = 0, transactionId = transactionId) })
    }
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY closed, date DESC")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts")
    suspend fun all(): List<DebtEntity>

    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun byId(id: Long): DebtEntity?

    @Upsert
    suspend fun upsert(debt: DebtEntity): Long

    @Delete
    suspend fun delete(debt: DebtEntity)
}

@Dao
interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rules ORDER BY active DESC, nextDay")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules")
    suspend fun all(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE active = 1 AND autoAdd = 1 AND nextDay <= :epochDay")
    suspend fun due(epochDay: Long): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun byId(id: Long): RecurringRuleEntity?

    @Upsert
    suspend fun upsert(rule: RecurringRuleEntity): Long

    @Update
    suspend fun update(rule: RecurringRuleEntity)

    @Delete
    suspend fun delete(rule: RecurringRuleEntity)
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM loans WHERE archived = 0 ORDER BY firstEmiDay")
    fun observeActive(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE archived = 0")
    suspend fun active(): List<LoanEntity>

    @Query("SELECT * FROM loans")
    suspend fun all(): List<LoanEntity>

    @Query("SELECT * FROM loans WHERE id = :id")
    fun observe(id: Long): Flow<LoanEntity?>

    @Query("SELECT * FROM loans WHERE id = :id")
    suspend fun byId(id: Long): LoanEntity?

    @Upsert
    suspend fun upsert(loan: LoanEntity): Long

    @Delete
    suspend fun delete(loan: LoanEntity)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE transactionId = :transactionId ORDER BY createdAt")
    fun observeFor(transactionId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE warrantyUntil IS NOT NULL ORDER BY warrantyUntil")
    fun observeWarranties(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE warrantyUntil IS NOT NULL")
    suspend fun warranties(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments")
    suspend fun all(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun byId(id: Long): AttachmentEntity?

    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    @Update
    suspend fun update(attachment: AttachmentEntity)

    @Delete
    suspend fun delete(attachment: AttachmentEntity)
}
