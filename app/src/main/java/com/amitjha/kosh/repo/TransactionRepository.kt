package com.amitjha.kosh.repo

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.room.withTransaction
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.MerchantRuleEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.domain.MoneyIn
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** A transaction with its category and account, ready for display. */
data class TransactionItem(
    val txn: TransactionEntity,
    val category: CategoryEntity?,
    val account: AccountEntity?,
    val toAccount: AccountEntity?,
) {
    val date: LocalDate get() = Time.toDate(txn.timestamp)
    /** Signed amount from the user's point of view: income positive, expense negative, transfer 0-net. */
    val signedAmount: Paise get() = when (txn.type) {
        TxnType.INCOME -> txn.amount
        TxnType.EXPENSE -> -txn.amount
        TxnType.TRANSFER -> 0
    }
}

data class TxnFilter(
    val period: Period? = null,
    val accountId: Long? = null,
    val categoryKey: String? = null,
    val type: TxnType? = null,
    val query: String = "",
    val onlyUnreviewed: Boolean = false,
    val tag: String? = null,
)

data class CategorySpend(val category: CategoryEntity?, val categoryKey: String, val total: Paise, val count: Int, val share: Double)
data class DailyPoint(val date: LocalDate, val income: Paise, val expense: Paise)
data class MerchantSpend(val merchant: String, val total: Paise, val count: Int)

/**
 * Income, expense and breakdowns for one budgeting period. Income is what was earned and expense is spending after
 * refunds and cashback (see [MoneyIn]); transfers between own accounts are excluded.
 */
data class MonthSummary(
    val period: Period,
    val income: Paise,
    val expense: Paise,
    val byCategory: List<CategorySpend>,
    val incomeByCategory: List<CategorySpend>,
    val daily: List<DailyPoint>,
    val topMerchants: List<MerchantSpend>,
    val transactionCount: Int,
    /** Refunds and cashback, already taken off [expense]. */
    val givenBack: Paise = 0,
    /** Money received that isn't income (friends, family, credits Kosh can't place). */
    val received: Paise = 0,
) {
    val savings: Paise get() = income - expense
    val savingsRate: Double? get() = if (income > 0) savings.toDouble() / income else null
}

class TransactionRepository(private val db: KoshDatabase) {

    private val dao get() = db.transactions()

    fun observeRecent(limit: Int = 20): Flow<List<TransactionItem>> = withRefs(dao.observeRecent(limit))

    fun observeUnreviewed(): Flow<List<TransactionItem>> = withRefs(dao.observeUnreviewed())

    /** One transaction with its references, or null once deleted. */
    fun observeItem(id: Long): Flow<TransactionItem?> =
        withRefs(dao.observeById(id).map { listOfNotNull(it) }).map { it.firstOrNull() }

    fun observe(filter: TxnFilter): Flow<List<TransactionItem>> {
        // "#goa" searches tags only; plain text also matches tags.
        val q = filter.query.trim()
        val tagQuery = q.startsWith("#") && q.length > 1
        val source = when {
            filter.onlyUnreviewed -> dao.observeUnreviewed()
            filter.period != null -> dao.observeBetween(filter.period.startMillis, filter.period.endMillis)
            filter.accountId != null -> dao.observeForAccount(filter.accountId)
            else -> dao.search(if (tagQuery) q.drop(1) else q)
        }
        return withRefs(source).map { list ->
            list.filter { item ->
                val t = item.txn
                val tags = com.amitjha.kosh.data.Tags.decode(t.tags)
                (filter.accountId == null || t.accountId == filter.accountId || t.toAccountId == filter.accountId) &&
                    (filter.categoryKey == null || t.categoryKey == filter.categoryKey) &&
                    (filter.type == null || t.type == filter.type) &&
                    (filter.tag == null || com.amitjha.kosh.data.Tags.has(t.tags, filter.tag)) &&
                    (q.isBlank() || if (tagQuery) tags.any { it.contains(q.drop(1), ignoreCase = true) }
                    else (listOfNotNull(t.merchant, t.note, item.category?.name) + tags).any { it.contains(q, ignoreCase = true) })
            }
        }
    }

    fun observeSummary(period: Period): Flow<MonthSummary> =
        combine(dao.observeBetween(period.startMillis, period.endMillis), db.categories().observeAll(), observeSplits()) { txns, cats, splits ->
            summarise(period, txns, cats.associateBy { it.key }, splits)
        }

    suspend fun summary(period: Period): MonthSummary =
        summarise(period, dao.between(period.startMillis, period.endMillis), db.categories().all().associateBy { it.key }, splitsByTransaction())

    /** Split parts of every split transaction, by transaction id. */
    fun observeSplits(): Flow<Map<Long, List<com.amitjha.kosh.data.SplitEntity>>> =
        db.splits().observeAll().map(com.amitjha.kosh.domain.Splits::byTransaction)

    suspend fun splitsByTransaction(): Map<Long, List<com.amitjha.kosh.data.SplitEntity>> =
        com.amitjha.kosh.domain.Splits.byTransaction(db.splits().all())

    fun observeSplitsFor(txnId: Long): Flow<List<com.amitjha.kosh.data.SplitEntity>> = db.splits().observeFor(txnId)

    /** Replaces a transaction's split parts; an empty list makes it a single-category transaction again. */
    suspend fun setSplits(txnId: Long, parts: List<com.amitjha.kosh.data.SplitEntity>) = db.splits().replace(txnId, parts)

    suspend fun setTags(txnId: Long, tags: List<String>) {
        dao.byId(txnId)?.let { dao.update(it.copy(tags = com.amitjha.kosh.data.Tags.encode(tags))) }
    }

    /** Every tag in use, most used first. */
    suspend fun allTags(): List<String> = dao.all().flatMap { com.amitjha.kosh.data.Tags.decode(it.tags) }
        .groupBy { it.lowercase() }.entries.sortedByDescending { it.value.size }.map { it.value.first() }

    suspend fun byId(id: Long): TransactionEntity? = dao.byId(id)

    suspend fun add(txn: TransactionEntity): Long = dao.insert(txn)

    suspend fun update(txn: TransactionEntity) = dao.update(txn)

    suspend fun delete(id: Long) {
        dao.byId(id)?.let { dao.delete(it) }
    }

    /** Moves money between two of the user's accounts (e.g. paying a card from a bank account). */
    suspend fun addTransfer(fromAccountId: Long, toAccountId: Long, amount: Paise, timestamp: Long, note: String? = null): Long =
        dao.insert(
            TransactionEntity(
                accountId = fromAccountId,
                toAccountId = toAccountId,
                type = TxnType.TRANSFER,
                amount = amount,
                categoryKey = SmsMapping.TRANSFER_CATEGORY,
                note = note,
                timestamp = timestamp,
                source = TxnSource.MANUAL,
                excludeFromStats = true,
            ),
        )

    /**
     * Changes a transaction's category. With [applyToSimilar], every imported transaction from the same
     * merchant is recategorised and future imports follow the new rule.
     */
    suspend fun recategorise(txnId: Long, categoryKey: String, applyToSimilar: Boolean) = withContext(Dispatchers.Default) {
        val txn = dao.byId(txnId) ?: return@withContext
        // One transaction: screens recompute once, and matching every row's merchant stays off the main thread.
        db.withTransaction {
            dao.update(
                txn.copy(
                    categoryKey = categoryKey,
                    reviewed = true,
                    excludeFromStats = SmsMapping.excludeAfterRecategorise(txn, categoryKey),
                ),
            )
            val merchant = txn.merchant
            if (applyToSimilar && merchant != null) {
                db.merchantRules().upsert(MerchantRuleEntity(merchant = merchant, categoryKey = categoryKey))
                // Same type only (an Amazon refund keeps its income category) and never paired transfer legs.
                dao.recategoriseMerchant(merchant, categoryKey, txn.type.name)
                // Also catch the same merchant spelled differently ("SWIGGY", "Swiggy Ltd").
                val key = SmsMapping.merchantKey(merchant)
                if (key.isNotEmpty()) {
                    dao.all().filter {
                        it.source != com.amitjha.kosh.model.TxnSource.MANUAL && it.id != txnId && it.type == txn.type &&
                            it.categoryKey != categoryKey && it.categoryKey != SmsMapping.TRANSFER_CATEGORY &&
                            it.merchant?.let(SmsMapping::merchantKey) == key
                    }.forEach { dao.update(it.copy(categoryKey = categoryKey, excludeFromStats = SmsMapping.excludeAfterRecategorise(it, categoryKey))) }
                }
            }
        }
    }

    /**
     * Marks a transaction as money moved between the user's own accounts. With [otherAccountId], the
     * opposite leg (same amount, within 2 days, on that account) is found and marked too.
     */
    suspend fun markSelfTransfer(txnId: Long, otherAccountId: Long?) {
        val txn = dao.byId(txnId) ?: return
        val accounts = db.accounts().all()
        val names = accounts.associate { it.id to it.name }
        val otherName = otherAccountId?.let(names::get)
        val note = txn.note ?: otherName?.let { if (txn.type == TxnType.INCOME) "Transfer from $it" else "Transfer to $it" }
        dao.update(txn.copy(categoryKey = SmsMapping.TRANSFER_CATEGORY, excludeFromStats = true, reviewed = true, note = note))
        if (otherAccountId == null) return
        val window = com.amitjha.kosh.domain.TransferPairing.WINDOW_MILLIS
        val opposite = if (txn.type == TxnType.INCOME) TxnType.EXPENSE else TxnType.INCOME
        val match = dao.findAmountNear(otherAccountId, txn.amount, txn.timestamp - window, txn.timestamp + window)
            .filter { it.id != txn.id && it.accountId == otherAccountId && it.type == opposite }
            .minByOrNull { kotlin.math.abs(it.timestamp - txn.timestamp) }
        if (match != null) {
            val fromName = names[txn.accountId]
            dao.update(
                match.copy(
                    categoryKey = SmsMapping.TRANSFER_CATEGORY,
                    excludeFromStats = true,
                    reviewed = true,
                    note = match.note ?: fromName?.let { if (opposite == TxnType.INCOME) "Transfer from $it" else "Transfer to $it" },
                ),
            )
        }
    }

    suspend fun markReviewed(txnId: Long) {
        // "Looks right" also confirms an amount that was held as suspicious.
        dao.byId(txnId)?.let { dao.update(it.copy(reviewed = true, suspicious = false)) }
    }

    suspend fun markAllReviewed() = dao.markAllReviewed()

    suspend fun markUnreviewed(ids: List<Long>) { if (ids.isNotEmpty()) dao.markUnreviewed(ids) }

    private fun withRefs(source: Flow<List<TransactionEntity>>): Flow<List<TransactionItem>> =
        combine(source, db.categories().observeAll(), db.accounts().observeActive()) { txns, cats, accounts ->
            val catByKey = cats.associateBy { it.key }
            val accById = accounts.associateBy { it.id }
            txns.map { TransactionItem(it, catByKey[it.categoryKey], accById[it.accountId], it.toAccountId?.let(accById::get)) }
        }

    companion object {
        fun summarise(
            period: Period,
            txns: List<TransactionEntity>,
            categories: Map<String, CategoryEntity>,
            splits: Map<Long, List<com.amitjha.kosh.data.SplitEntity>> = emptyMap(),
        ): MonthSummary {
            val counted = txns.filter { SmsMapping.countsInStats(it) && Time.toDate(it.timestamp) in period }
            val parts = com.amitjha.kosh.domain.Splits.expand(counted, splits)
            val expenses = parts.filter { it.type == TxnType.EXPENSE }
            val incomes = parts.filter(MoneyIn::isEarned)
            val givenBack = parts.filter(MoneyIn::isGivenBack)
            val expense = MoneyIn.netSpent(expenses, givenBack)
            val income = incomes.sumOf { it.amount }

            fun breakdown(list: List<TransactionEntity>, total: Paise) = list.groupBy { it.categoryKey }
                .map { (key, items) ->
                    val sum = items.sumOf { it.amount }
                    CategorySpend(categories[key], key, sum, items.size, if (total > 0) sum.toDouble() / total else 0.0)
                }
                .sortedByDescending { it.total }

            val counts = expenses.groupingBy { it.categoryKey }.eachCount()
            val history = txns.filter { it.type == TxnType.EXPENSE && SmsMapping.countsInStats(it) }
            val byCategory = MoneyIn.netByCategory(expenses, givenBack, history)
                .filter { it.value > 0 }
                .map { (key, sum) -> CategorySpend(categories[key], key, sum, counts[key] ?: 0, if (expense > 0) sum.toDouble() / expense else 0.0) }
                .sortedByDescending { it.total }

            val byDay = counted.groupBy { Time.toDate(it.timestamp) }
            val daily = generateSequence(period.start) { it.plusDays(1) }
                .takeWhile { it.isBefore(period.endExclusive) }
                .map { day ->
                    val items = byDay[day].orEmpty()
                    DailyPoint(
                        day,
                        items.filter(MoneyIn::isEarned).sumOf { it.amount },
                        items.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
                    )
                }
                .toList()

            // Refunds and cashback from a merchant come off what was spent there.
            val backFrom = givenBack.filter { it.merchant != null }.groupBy { it.merchant!!.trim().lowercase() }
                .mapValues { (_, items) -> items.sumOf { it.amount } }
            val merchants = counted.filter { it.type == TxnType.EXPENSE && it.merchant != null }
                .groupBy { it.merchant!! }
                .map { (m, items) -> MerchantSpend(m, items.sumOf { it.amount }, items.size) }
                .groupBy { it.merchant.trim().lowercase() }
                .flatMap { (key, list) ->
                    var back = backFrom[key] ?: 0L
                    list.sortedByDescending { it.total }.map { m ->
                        val cut = minOf(back, m.total)
                        back -= cut
                        m.copy(total = m.total - cut)
                    }
                }
                .filter { it.total > 0 }
                .sortedByDescending { it.total }
                .take(10)

            return MonthSummary(
                period = period,
                income = income,
                expense = expense,
                byCategory = byCategory,
                incomeByCategory = breakdown(incomes, income),
                daily = daily,
                topMerchants = merchants,
                transactionCount = counted.size,
                givenBack = givenBack.sumOf { it.amount },
                received = parts.filter(MoneyIn::isReceived).sumOf { it.amount },
            )
        }
    }
}
