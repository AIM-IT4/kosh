package com.amitjha.kosh.domain

import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.CategoryGuesser

/**
 * What a credit counts as in summaries.
 *
 * - Earned: salary, interest and dividends, business and rent received, and any income category the user creates.
 *   Only these count as income.
 * - Given back: refunds and cashback return money already spent, so they come off spending instead.
 * - Received: everything else that comes in (friends settling up, family, credits Kosh can't place). It shows in
 *   the account and its balance, but is neither income nor spending.
 *
 * Transfers between the user's own accounts are neither; see [SmsMapping.countsInStats].
 */
object MoneyIn {

    const val RECEIVED = "other_income"
    val GIVEN_BACK = setOf("refund", "cashback")

    fun isEarned(t: TransactionEntity): Boolean =
        t.type == TxnType.INCOME && t.categoryKey != RECEIVED && t.categoryKey !in GIVEN_BACK

    fun isGivenBack(t: TransactionEntity): Boolean = t.type == TxnType.INCOME && t.categoryKey in GIVEN_BACK

    fun isReceived(t: TransactionEntity): Boolean = t.type == TxnType.INCOME && t.categoryKey == RECEIVED

    /** Spending after refunds and cashback, never below zero. */
    fun netSpent(expenses: List<TransactionEntity>, givenBack: List<TransactionEntity>): Paise =
        (expenses.sumOf { it.amount } - givenBack.sumOf { it.amount }).coerceAtLeast(0)

    /**
     * Spending per category with refunds and cashback taken off. Each one comes off the category of the latest
     * purchase from the same merchant in [history], else the category that merchant usually falls in. What can't be
     * placed, or is more than that category's spending, is shared across the other categories in proportion.
     * No category goes below zero and the totals add up to [netSpent].
     */
    fun netByCategory(
        expenses: List<TransactionEntity>,
        givenBack: List<TransactionEntity>,
        history: List<TransactionEntity> = expenses,
    ): Map<String, Paise> {
        val totals = LinkedHashMap<String, Paise>()
        expenses.forEach { totals[it.categoryKey] = (totals[it.categoryKey] ?: 0L) + it.amount }
        if (givenBack.isEmpty()) return totals
        val purchases = history.filter { it.type == TxnType.EXPENSE && it.merchant != null }
        var unplaced = 0L
        givenBack.forEach { g ->
            val target = categoryFor(g, purchases)
            val available = target?.let { totals[it] } ?: 0L
            val taken = minOf(available, g.amount)
            if (target != null && taken > 0) totals[target] = available - taken
            unplaced += g.amount - taken
        }
        if (unplaced > 0) share(totals, unplaced)
        return totals
    }

    /** The category a refund or cashback belongs to: the latest purchase from its merchant, else the usual one. */
    private fun categoryFor(g: TransactionEntity, purchases: List<TransactionEntity>): String? {
        val merchant = g.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = merchant.lowercase()
        val same = purchases.filter { it.merchant!!.trim().lowercase() == key }
        val match = same.filter { it.timestamp <= g.timestamp }.maxByOrNull { it.timestamp } ?: same.maxByOrNull { it.timestamp }
        if (match != null) return match.categoryKey
        return CategoryGuesser.guess(merchant, "", TxnType.EXPENSE).takeIf { it != "other" }
    }

    /** Takes [amount] off the totals in proportion to what is left in each, to the paisa. */
    private fun share(totals: MutableMap<String, Paise>, amount: Paise) {
        val left = totals.values.sum()
        if (left <= amount) {
            totals.keys.forEach { totals[it] = 0L }
            return
        }
        val cuts = totals.mapValues { (_, v) -> (v.toBigInteger() * amount.toBigInteger() / left.toBigInteger()).toLong() }
        var rest = amount - cuts.values.sum()
        totals.forEach { (k, v) -> totals[k] = v - cuts.getValue(k) }
        // Rounding: the last few paise come off the biggest categories.
        for (k in totals.entries.sortedByDescending { it.value }.map { it.key }) {
            if (rest == 0L) break
            if (totals.getValue(k) > 0) { totals[k] = totals.getValue(k) - 1; rest-- }
        }
    }
}
