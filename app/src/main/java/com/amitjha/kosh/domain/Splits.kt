package com.amitjha.kosh.domain

import com.amitjha.kosh.data.SplitEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.Paise

/**
 * A split transaction counts in stats as its parts: each part is a copy of the transaction with the part's category
 * and amount. Whatever the parts don't cover stays in the transaction's own category, so the total never changes.
 * Balances always use the transaction itself, never the parts.
 */
object Splits {

    fun expand(txns: List<TransactionEntity>, splits: Map<Long, List<SplitEntity>>): List<TransactionEntity> {
        if (splits.isEmpty()) return txns
        return txns.flatMap { t -> parts(t, splits[t.id].orEmpty()) }
    }

    fun parts(t: TransactionEntity, parts: List<SplitEntity>): List<TransactionEntity> {
        val valid = parts.filter { it.amount > 0 }
        if (valid.isEmpty()) return listOf(t)
        val covered = valid.sumOf { it.amount }.coerceAtMost(t.amount)
        var left = t.amount
        val out = valid.mapNotNull { p ->
            val amount = p.amount.coerceAtMost(left)
            left -= amount
            if (amount > 0) t.copy(categoryKey = p.categoryKey, amount = amount) else null
        }
        val remainder = t.amount - covered
        return if (remainder > 0) out + t.copy(amount = remainder) else out
    }

    /** What the parts leave for the transaction's own category (0 when they cover it all). */
    fun remainder(total: Paise, parts: List<Paise>): Paise = (total - parts.filter { it > 0 }.sum()).coerceAtLeast(0)

    fun byTransaction(all: List<SplitEntity>): Map<Long, List<SplitEntity>> = all.groupBy { it.transactionId }
}
