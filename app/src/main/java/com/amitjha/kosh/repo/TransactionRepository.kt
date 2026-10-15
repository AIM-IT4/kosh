package com.amitjha.kosh.repo

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.MerchantRuleEntity
import com.amitjha.kosh.data.TransactionEntity
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
)

data class CategorySpend(val category: CategoryEntity?, val categoryKey: String, val total: Paise, val count: Int, val share: Double)
data class DailyPoint(val date: LocalDate, val income: Paise, val expense: Paise)
data class MerchantSpend(val merchant: String, val total: Paise, val count: Int)

/** Income, expense and breakdowns for one budgeting period. Transfers between own accounts are excluded. */
data class MonthSummary(
    val period: Period,
    val income: Paise,
    val expense: Paise,
    val byCategory: List<CategorySpend>,
    val incomeByCategory: List<CategorySpend>,
    val daily: List<DailyPoint>,
    val topMerchants: List<MerchantSpend>,
    val transactionCount: Int,
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
        val source = when {
            filter.onlyUnreviewed -> dao.observeUnreviewed()
            filter.period != null -> dao.observeBetween(filter.period.startMillis, filter.period.endMillis)
            filter.accountId != null -> dao.observeForAccount(filter.accountId)
            else -> dao.search(filter.query)
        }
        return withRefs(source).map { list ->
            list.filter { item ->
                val t = item.txn
                (filter.accountId == null || t.accountId == filter.accountId || t.toAccountId == filter.accountId) &&
                    (filter.categoryKey == null || t.categoryKey == filter.categoryKey) &&
                    (filter.type == null || t.type == filter.type) &&
                    (filter.query.isBlank() || listOfNotNull(t.merchant, t.note, item.category?.name)
                        .any { it.contains(filter.query.trim(), ignoreCase = true) })
            }
        }
    }

    fun observeSummary(period: Period): Flow<MonthSummary> =
        combine(dao.observeBetween(period.startMillis, period.endMillis), db.categories().observeAll()) { txns, cats ->
            summarise(period, txns, cats.associateBy { it.key })
        }

    suspend fun summary(period: Period): MonthSummary =
        summarise(period, dao.between(period.startMillis, period.endMillis), db.categories().all().associateBy { it.key })

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
    suspend fun recategorise(txnId: Long, categoryKey: String, applyToSimilar: Boolean) {
        val txn = dao.byId(txnId) ?: return
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

    private fun withRefs(source: Flow<List<TransactionEntity>>): Flow<List<TransactionItem>> =
        combine(source, db.categories().observeAll(), db.accounts().observeActive()) { txns, cats, accounts ->
            val catByKey = cats.associateBy { it.key }
            val accById = accounts.associateBy { it.id }
            txns.map { TransactionItem(it, catByKey[it.categoryKey], accById[it.accountId], it.toAccountId?.let(accById::get)) }
        }

    companion object {
        fun summarise(period: Period, txns: List<TransactionEntity>, categories: Map<String, CategoryEntity>): MonthSummary {
            val counted = txns.filter { SmsMapping.countsInStats(it) && Time.toDate(it.timestamp) in period }
            val expenses = counted.filter { it.type == TxnType.EXPENSE }
            val incomes = counted.filter { it.type == TxnType.INCOME }
            val expense = expenses.sumOf { it.amount }
            val income = incomes.sumOf { it.amount }

            fun breakdown(list: List<TransactionEntity>, total: Paise) = list.groupBy { it.categoryKey }
                .map { (key, items) ->
                    val sum = items.sumOf { it.amount }
                    CategorySpend(categories[key], key, sum, items.size, if (total > 0) sum.toDouble() / total else 0.0)
                }
                .sortedByDescending { it.total }

            val byDay = counted.groupBy { Time.toDate(it.timestamp) }
            val daily = generateSequence(period.start) { it.plusDays(1) }
                .takeWhile { it.isBefore(period.endExclusive) }
                .map { day ->
                    val items = byDay[day].orEmpty()
                    DailyPoint(
                        day,
                        items.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
                        items.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
                    )
                }
                .toList()

            val merchants = expenses.filter { it.merchant != null }
                .groupBy { it.merchant!! }
                .map { (m, items) -> MerchantSpend(m, items.sumOf { it.amount }, items.size) }
                .sortedByDescending { it.total }
                .take(10)

            return MonthSummary(
                period = period,
                income = income,
                expense = expense,
                byCategory = breakdown(expenses, expense),
                incomeByCategory = breakdown(incomes, income),
                daily = daily,
                topMerchants = merchants,
                transactionCount = counted.size,
            )
        }
    }
}
