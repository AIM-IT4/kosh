package com.amitjha.kosh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A family member whose bank SMS reach Kosh by being forwarded from their phone (see `sms.FamilyForward`). Their
 * accounts carry [AccountEntity.memberId]; a forwarded message only ever matches those, and the user's own messages
 * never do.
 */
@Entity(tableName = "family_members", indices = [Index(value = ["phone"], unique = true)])
data class FamilyMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** The last 10 digits of their mobile number, once linked; messages from it are read as theirs. */
    val phone: String? = null,
    /** The code of the link message Kosh expects from their phone. */
    val linkCode: String,
    val linkedAt: Long? = null,
    /** Whether their spending, income and balances count in the user's totals, or are only shown on their own. */
    val countInTotals: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val linked: Boolean get() = phone != null
}

@Dao
interface FamilyDao {
    @Query("SELECT * FROM family_members ORDER BY createdAt")
    fun observeAll(): Flow<List<FamilyMemberEntity>>

    @Query("SELECT * FROM family_members ORDER BY createdAt")
    suspend fun all(): List<FamilyMemberEntity>

    @Query("SELECT * FROM family_members WHERE id = :id")
    suspend fun byId(id: Long): FamilyMemberEntity?

    @Insert
    suspend fun insert(member: FamilyMemberEntity): Long

    @Update
    suspend fun update(member: FamilyMemberEntity)

    @Query("DELETE FROM family_members WHERE id = :id")
    suspend fun delete(id: Long)

    /** Frees [phone] from any other member, so one phone links to one person. */
    @Query("UPDATE family_members SET phone = NULL, linkedAt = NULL WHERE phone = :phone AND id != :keep")
    suspend fun releasePhone(phone: String, keep: Long)

    /** Each member's spending from [from] to [to]: expenses on their accounts, transfers and doubtful amounts aside. */
    @Query(
        """SELECT a.memberId AS memberId, COALESCE(SUM(t.amount), 0) AS spent, COUNT(*) AS count
           FROM transactions t JOIN accounts a ON t.accountId = a.id
           WHERE a.memberId IS NOT NULL AND a.archived = 0 AND t.type = 'EXPENSE' AND t.categoryKey != 'transfer'
             AND t.suspicious = 0 AND t.timestamp >= :from AND t.timestamp <= :to
           GROUP BY a.memberId""",
    )
    fun observeSpending(from: Long, to: Long): Flow<List<MemberSpend>>
}

/** What one family member spent in a period. */
data class MemberSpend(val memberId: Long, val spent: Long, val count: Int)
