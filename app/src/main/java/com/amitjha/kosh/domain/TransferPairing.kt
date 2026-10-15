package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.TxnType
import kotlin.math.abs

/**
 * Finds money moved between the user's own accounts so it isn't counted as spending and income.
 *
 * Both legs stay in the database (a re-scan would otherwise re-import the deleted one by fingerprint);
 * they are recategorised as "transfer" and excluded from stats. Balances stay correct because the debit
 * still leaves account A and the credit still arrives in account B.
 */
object TransferPairing {

    const val WINDOW_MILLIS = 2 * 24 * 60 * 60 * 1000L

    data class Pair(val debit: TransactionEntity, val credit: TransactionEntity)

    /**
     * Pairs an expense on one own account with an income of the same amount on a different own account
     * within [WINDOW_MILLIS] (closest in time wins; each leg is used once). Card bill payments are
     * included: the card's "payment received" credit is already a transfer but its bank debit may not be.
     */
    fun findPairs(txns: List<TransactionEntity>, accounts: List<AccountEntity>): List<Pair> {
        val accById = accounts.associateBy { it.id }
        fun isCard(id: Long) = accById[id]?.type == AccountType.CREDIT_CARD
        fun own(id: Long) = accById[id]?.let { !it.archived } == true

        val transferDebits = txns.filter { it.type == TxnType.EXPENSE && it.categoryKey == SmsMapping.TRANSFER_CATEGORY }
        val usedDebits = HashSet<Long>()
        // Card-payment credits that already have their bank debit marked as a transfer are consumed.
        val consumedCredits = HashSet<Long>()
        txns.filter { it.type == TxnType.INCOME && it.categoryKey == SmsMapping.TRANSFER_CATEGORY && isCard(it.accountId) }
            .sortedBy { it.timestamp }
            .forEach { credit ->
                val partner = transferDebits
                    .filter { it.id !in usedDebits && it.accountId != credit.accountId && it.amount == credit.amount &&
                        abs(it.timestamp - credit.timestamp) <= WINDOW_MILLIS }
                    .minByOrNull { abs(it.timestamp - credit.timestamp) }
                if (partner != null) {
                    usedDebits += partner.id
                    consumedCredits += credit.id
                }
            }

        val debits = txns.filter {
            it.type == TxnType.EXPENSE && !it.suspicious && it.categoryKey != SmsMapping.TRANSFER_CATEGORY &&
                own(it.accountId) && !isCard(it.accountId)
        }.sortedBy { it.timestamp }
        val credits = txns.filter {
            it.type == TxnType.INCOME && !it.suspicious && own(it.accountId) && it.id !in consumedCredits &&
                (it.categoryKey != SmsMapping.TRANSFER_CATEGORY || isCard(it.accountId)) &&
                it.categoryKey != "salary"
        }

        val usedCredits = HashSet<Long>()
        val out = ArrayList<Pair>()
        for (debit in debits) {
            val credit = credits
                .filter { it.id !in usedCredits && it.accountId != debit.accountId && it.amount == debit.amount &&
                    abs(it.timestamp - debit.timestamp) <= WINDOW_MILLIS }
                .minByOrNull { abs(it.timestamp - debit.timestamp) }
                ?: continue
            usedCredits += credit.id
            out += Pair(debit, credit)
        }
        return out
    }

    /** The two legs rewritten as a transfer between own accounts. */
    fun asTransfer(pair: Pair, accounts: List<AccountEntity>): kotlin.Pair<TransactionEntity, TransactionEntity> {
        val names = accounts.associate { it.id to it.name }
        val debit = pair.debit.copy(
            categoryKey = SmsMapping.TRANSFER_CATEGORY,
            excludeFromStats = true,
            note = pair.debit.note ?: names[pair.credit.accountId]?.let { "Transfer to $it" },
        )
        val credit = pair.credit.copy(
            categoryKey = SmsMapping.TRANSFER_CATEGORY,
            excludeFromStats = true,
            note = pair.credit.note ?: names[pair.debit.accountId]?.let { "Transfer from $it" },
        )
        return debit to credit
    }

    private val selfWords = Regex(
        "(?i)\\bself\\b|own (?:a/?c|acc(?:oun)?t)|to your (?:own )?(?:a/?c|acc(?:oun)?t)|self[- ]?transfer|sweep",
    )

    /**
     * True when a bank debit message describes moving money to the user's own account: "self", "own account",
     * or "to a/c ..1234" where 1234 is another of the user's accounts.
     */
    fun looksLikeSelfTransfer(body: String, ownLast4s: Set<String>, thisLast4: String?): Boolean {
        if (selfWords.containsMatchIn(body)) return true
        val targets = Regex("(?i)(?:to|trf to|transfer(?:red)? to|credited to)\\s+(?:your\\s+)?(?:a/?c|acct|account|card)\\s*(?:no\\.?\\s*)?[x*.\\s]*(\\d{4})\\b")
            .findAll(body).map { it.groupValues[1] }
        return targets.any { it in ownLast4s && it != thisLast4 }
    }
}
