package com.amitjha.kosh.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.amitjha.kosh.model.Paise
import kotlinx.coroutines.flow.Flow

data class CategoryTotal(val categoryKey: String, val total: Paise, val count: Int)
data class DayTotal(val day: Long, val income: Paise, val expense: Paise)
data class MerchantTotal(val merchant: String, val total: Paise, val count: Int)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY type, name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY type, name")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: Long): Flow<AccountEntity?>

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("SELECT * FROM accounts WHERE archived = 0 AND bank = :bank AND type = :type")
    suspend fun byBankAndType(bank: String, type: com.amitjha.kosh.model.AccountType): List<AccountEntity>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY type, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Upsert
    suspend fun upsert(category: CategoryEntity)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    suspend fun between(from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId ORDER BY timestamp DESC")
    fun observeForAccount(accountId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE reviewed = 0 ORDER BY timestamp DESC")
    fun observeUnreviewed(): Flow<List<TransactionEntity>>

    @Query(
        """SELECT * FROM transactions
           WHERE (:query = '' OR merchant LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%')
           ORDER BY timestamp DESC LIMIT 500""",
    )
    fun search(query: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: Long): Flow<TransactionEntity?>

    @Query(
        """SELECT categoryKey, SUM(amount) AS total, COUNT(*) AS count FROM transactions
           WHERE type = 'EXPENSE' AND excludeFromStats = 0 AND timestamp BETWEEN :from AND :to
           GROUP BY categoryKey ORDER BY total DESC""",
    )
    fun observeExpenseByCategory(from: Long, to: Long): Flow<List<CategoryTotal>>

    @Query(
        """SELECT merchant, SUM(amount) AS total, COUNT(*) AS count FROM transactions
           WHERE type = 'EXPENSE' AND merchant IS NOT NULL AND excludeFromStats = 0 AND timestamp BETWEEN :from AND :to
           GROUP BY merchant ORDER BY total DESC LIMIT :limit""",
    )
    fun observeTopMerchants(from: Long, to: Long, limit: Int): Flow<List<MerchantTotal>>

    /** Net movement of an account: income in, expense out, transfers out/in. */
    @Query(
        """SELECT
             COALESCE(SUM(CASE WHEN accountId = :accountId AND type = 'INCOME' THEN amount ELSE 0 END), 0)
           - COALESCE(SUM(CASE WHEN accountId = :accountId AND type IN ('EXPENSE','TRANSFER') THEN amount ELSE 0 END), 0)
           + COALESCE(SUM(CASE WHEN toAccountId = :accountId AND type = 'TRANSFER' THEN amount ELSE 0 END), 0)
           FROM transactions WHERE (accountId = :accountId OR toAccountId = :accountId) AND timestamp >= :since""",
    )
    fun observeNetFlow(accountId: Long, since: Long): Flow<Paise>

    @Query(
        """SELECT
             COALESCE(SUM(CASE WHEN accountId = :accountId AND type = 'INCOME' THEN amount ELSE 0 END), 0)
           - COALESCE(SUM(CASE WHEN accountId = :accountId AND type IN ('EXPENSE','TRANSFER') THEN amount ELSE 0 END), 0)
           + COALESCE(SUM(CASE WHEN toAccountId = :accountId AND type = 'TRANSFER' THEN amount ELSE 0 END), 0)
           FROM transactions WHERE (accountId = :accountId OR toAccountId = :accountId) AND timestamp >= :since""",
    )
    suspend fun netFlow(accountId: Long, since: Long): Paise

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(txn: TransactionEntity): Long

    @Insert
    suspend fun insert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Delete
    suspend fun delete(txn: TransactionEntity)

    @Query("UPDATE transactions SET categoryKey = :categoryKey WHERE merchant = :merchant AND source != 'MANUAL'")
    suspend fun recategoriseMerchant(merchant: String, categoryKey: String)

    @Query("UPDATE transactions SET reviewed = 1 WHERE reviewed = 0")
    suspend fun markAllReviewed()

    @Query("SELECT COUNT(*) FROM transactions WHERE fingerprint = :fingerprint")
    suspend fun countFingerprint(fingerprint: String): Int

    /** Emits whenever the transactions table changes (Room re-runs the query on invalidation). */
    @Query("SELECT COUNT(*) FROM transactions")
    fun observeChanges(): Flow<Int>

    @Query("SELECT MAX(timestamp) FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId")
    suspend fun lastTimestamp(accountId: Long): Long?

    @Query(
        """SELECT * FROM transactions WHERE amount = :amount AND timestamp BETWEEN :from AND :to
           AND (accountId = :accountId OR toAccountId = :accountId)""",
    )
    suspend fun findAmountNear(accountId: Long, amount: Paise, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE referenceNo = :ref")
    suspend fun countReference(ref: String): Int

    @Query("SELECT * FROM transactions WHERE timestamp >= :from ORDER BY timestamp")
    suspend fun since(from: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions")
    suspend fun all(): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface CardBillDao {
    @Query("SELECT * FROM card_bills WHERE paid = 0 ORDER BY dueDate")
    fun observeUnpaid(): Flow<List<CardBillEntity>>

    @Query("SELECT * FROM card_bills WHERE accountId = :accountId ORDER BY createdAt DESC")
    fun observeForCard(accountId: Long): Flow<List<CardBillEntity>>

    @Query("SELECT * FROM card_bills WHERE paid = 0")
    suspend fun unpaid(): List<CardBillEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(bill: CardBillEntity): Long

    @Update
    suspend fun update(bill: CardBillEntity)

    @Delete
    suspend fun delete(bill: CardBillEntity)

    @Query("SELECT * FROM card_bills WHERE id = :id")
    suspend fun byId(id: Long): CardBillEntity?

    @Query("SELECT * FROM card_bills WHERE accountId = :accountId AND paid = 0 ORDER BY dueDate")
    suspend fun unpaidForCard(accountId: Long): List<CardBillEntity>

    @Query("SELECT * FROM card_bills")
    suspend fun all(): List<CardBillEntity>

    /** Same bill already stored from another source (SMS vs e-mail use different fingerprints). */
    @Query("SELECT COUNT(*) FROM card_bills WHERE accountId = :accountId AND dueDate IS :dueDate AND totalDue = :totalDue")
    suspend fun countSame(accountId: Long, dueDate: Long?, totalDue: Paise): Int
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets")
    suspend fun all(): List<BudgetEntity>

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Delete
    suspend fun delete(budget: BudgetEntity)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY deadline")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals")
    suspend fun all(): List<GoalEntity>

    @Upsert
    suspend fun upsert(goal: GoalEntity): Long

    @Delete
    suspend fun delete(goal: GoalEntity)
}

@Dao
interface InvestmentDao {
    @Query("SELECT * FROM investments WHERE archived = 0 ORDER BY type, name")
    fun observeActive(): Flow<List<InvestmentEntity>>

    @Query("SELECT * FROM investments WHERE archived = 0")
    suspend fun active(): List<InvestmentEntity>

    @Query("SELECT * FROM investments")
    suspend fun all(): List<InvestmentEntity>

    @Query("SELECT * FROM investment_history")
    suspend fun allSnapshots(): List<InvestmentSnapshotEntity>

    @Query("SELECT * FROM investments WHERE id = :id")
    suspend fun byId(id: Long): InvestmentEntity?

    @Insert
    suspend fun insert(investment: InvestmentEntity): Long

    @Update
    suspend fun update(investment: InvestmentEntity)

    @Delete
    suspend fun delete(investment: InvestmentEntity)

    @Upsert
    suspend fun upsertSnapshot(snapshot: InvestmentSnapshotEntity)

    @Query("SELECT * FROM investment_history WHERE investmentId = :investmentId ORDER BY epochDay")
    fun observeHistory(investmentId: Long): Flow<List<InvestmentSnapshotEntity>>

    @Query(
        """SELECT epochDay AS day, SUM(value) AS income, 0 AS expense FROM investment_history
           GROUP BY epochDay ORDER BY epochDay""",
    )
    fun observeTotalHistory(): Flow<List<DayTotal>>
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT categoryKey FROM merchant_rules WHERE merchant = :merchant")
    suspend fun categoryFor(merchant: String): String?

    @Upsert
    suspend fun upsert(rule: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rules")
    suspend fun all(): List<MerchantRuleEntity>
}

@Dao
interface NetWorthDao {
    @Upsert
    suspend fun upsert(snapshot: NetWorthSnapshotEntity)

    @Query("SELECT * FROM net_worth_history ORDER BY epochDay")
    fun observeAll(): Flow<List<NetWorthSnapshotEntity>>

    @Query("SELECT * FROM net_worth_history")
    suspend fun all(): List<NetWorthSnapshotEntity>
}
