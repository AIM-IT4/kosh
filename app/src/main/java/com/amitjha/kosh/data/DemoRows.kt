package com.amitjha.kosh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A row created by "Load sample data", so it can be removed later without touching the user's own data.
 * [kind] is "account", "goal", "investment" or "budget"; [rowKey] is the row id (or a budget's category key,
 * with "*" for the overall budget).
 */
@Entity(tableName = "demo_rows", primaryKeys = ["kind", "rowKey"])
data class DemoRowEntity(val kind: String, val rowKey: String)

@Dao
interface DemoRowDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: DemoRowEntity)

    @Query("SELECT * FROM demo_rows")
    suspend fun all(): List<DemoRowEntity>

    @Query("SELECT COUNT(*) FROM demo_rows")
    suspend fun count(): Int

    @Query("DELETE FROM demo_rows")
    suspend fun clear()

    @Query("DELETE FROM accounts WHERE id IN (:ids)")
    suspend fun deleteAccounts(ids: List<Long>)

    @Query("DELETE FROM goals WHERE id IN (:ids)")
    suspend fun deleteGoals(ids: List<Long>)

    @Query("DELETE FROM investments WHERE id IN (:ids)")
    suspend fun deleteInvestments(ids: List<Long>)

    @Query("DELETE FROM budgets WHERE categoryKey IN (:keys)")
    suspend fun deleteBudgets(keys: List<String>)

    @Query("DELETE FROM budgets WHERE categoryKey IS NULL")
    suspend fun deleteOverallBudget()

    @Query("SELECT COUNT(*) FROM budgets WHERE categoryKey IS NULL")
    suspend fun overallBudgetCount(): Int

    @Query("SELECT COUNT(*) FROM budgets WHERE categoryKey = :key")
    suspend fun budgetCount(key: String): Int
}
