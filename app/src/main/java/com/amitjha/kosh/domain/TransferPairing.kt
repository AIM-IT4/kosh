package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.TxnSource
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

    /**
     * Between two bank accounts money arrives within minutes (UPI, IMPS) or a few hours (NEFT batches), so a debit and a
     * credit further apart are only a pair when they carry the same reference number. Otherwise paying a friend ₹500
     * and being paid ₹500 by someone else the next day would both vanish from the totals.
     */
    const val BANK_WINDOW_MILLIS = 3 * 60 * 60 * 1000L

    /** Credits that are never one side of a self-transfer. */
    private val NEVER_TRANSFER_CREDITS = setOf("salary", "refund", "interest", "cashback")

    data class Pair(val debit: TransactionEntity, val credit: TransactionEntity)

    /**
     * Pairs an expense on one own account with an income of the same amount on a different own account: within
     * [BANK_WINDOW_MILLIS], or [WINDOW_MILLIS] when both carry the same reference number (closest in time wins; each
     * leg is used once). Card bill payments are included, within [WINDOW_MILLIS] since a card can take a day or two to
     * post a payment: the card's "payment received" credit is already a transfer but its bank debit may not be.
     *
     * Only unreviewed imports take part: a manual entry or a row the user confirmed is never rewritten, and
     * salary, refund, interest and cashback credits are never someone's transfer.
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

        fun pairable(t: TransactionEntity) = !t.suspicious && t.source != TxnSource.MANUAL && !t.reviewed
        val debits = txns.filter {
            it.type == TxnType.EXPENSE && pairable(it) && it.categoryKey != SmsMapping.TRANSFER_CATEGORY &&
                own(it.accountId) && !isCard(it.accountId)
        }.sortedBy { it.timestamp }
        val credits = txns.filter {
            it.type == TxnType.INCOME && pairable(it) && own(it.accountId) && it.id !in consumedCredits &&
                (it.categoryKey != SmsMapping.TRANSFER_CATEGORY || isCard(it.accountId)) &&
                it.categoryKey !in NEVER_TRANSFER_CREDITS
        }

        val usedCredits = HashSet<Long>()
        val out = ArrayList<Pair>()
        for (debit in debits) {
            val credit = credits
                .filter { it.id !in usedCredits && it.accountId != debit.accountId && it.amount == debit.amount &&
                    abs(it.timestamp - debit.timestamp) <= windowFor(debit, it, isCard(it.accountId)) }
                .minByOrNull { abs(it.timestamp - debit.timestamp) }
                ?: continue
            usedCredits += credit.id
            out += Pair(debit, credit)
        }
        return out
    }

    private fun windowFor(debit: TransactionEntity, credit: TransactionEntity, toCard: Boolean): Long {
        val sameReference = debit.referenceNo != null && debit.referenceNo == credit.referenceNo
        return if (toCard || sameReference) WINDOW_MILLIS else BANK_WINDOW_MILLIS
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

    /**
     * When a family member is kept apart from the user's totals again, the user's side of each transfer Kosh paired
     * with one of their accounts ([memberAccounts]) becomes real money once more: sent to them is spending ("people"),
     * received from them is money received. A leg is undone only when its partner on the member's account is there
     * too (same amount, opposite direction, within [WINDOW_MILLIS], noted as a transfer with that account); legs the
     * user entered or confirmed stay as they are. The member's leg is undone too (still left out of totals), so the
     * pair is found again if they are counted once more. [accounts] are all accounts, for names.
     */
    fun unpairFromMember(txns: List<TransactionEntity>, memberAccounts: Set<Long>, accounts: List<AccountEntity>): List<TransactionEntity> {
        val names = accounts.associate { it.id to it.name }
        fun automatic(t: TransactionEntity) = t.source != TxnSource.MANUAL && !t.reviewed && t.categoryKey == SmsMapping.TRANSFER_CATEGORY
        val memberLegs = txns.filter { it.accountId in memberAccounts && automatic(it) }
        val candidates = txns.filter { it.accountId !in memberAccounts && automatic(it) && it.excludeFromStats && it.type != TxnType.TRANSFER }
        val used = HashSet<Long>()
        val out = ArrayList<TransactionEntity>()
        for (m in memberLegs.sortedBy { it.timestamp }) {
            val memberName = names[m.accountId] ?: continue
            val wanted = if (m.type == TxnType.INCOME) TxnType.EXPENSE else TxnType.INCOME
            val note = if (wanted == TxnType.EXPENSE) "Transfer to $memberName" else "Transfer from $memberName"
            val mine = candidates
                .filter { it.id !in used && it.type == wanted && it.amount == m.amount && it.note == note && abs(it.timestamp - m.timestamp) <= WINDOW_MILLIS }
                .minByOrNull { abs(it.timestamp - m.timestamp) }
                ?: continue
            used += mine.id
            out += mine.copy(
                categoryKey = categoryFor(mine.type),
                excludeFromStats = accounts.firstOrNull { it.id == mine.accountId }?.archived == true,
                note = null,
            )
            out += m.copy(categoryKey = categoryFor(m.type), excludeFromStats = true, note = null)
        }
        return out
    }

    private fun categoryFor(type: TxnType) = if (type == TxnType.EXPENSE) "people" else "other_income"

    private val selfWords = Regex(
        "(?i)\\bself\\b|own (?:a/?c|acc(?:oun)?t)|to your (?:own )?(?:a/?c|acc(?:oun)?t)|self[- ]?transfer|sweep",
    )

    /**
     * True when a bank debit message describes moving money to the user's own account: "self", "own account",
     * or "to a/c ..1234" where 1234 is another of the user's accounts.
     */
    private val ownTarget =
        Regex("(?i)(?:to|trf to|transfer(?:red)? to|credited to)\\s+(?:your\\s+)?(?:a/?c|acct|account|card)\\s*(?:no\\.?\\s*)?[x*.\\s]*(\\d{4})\\b")

    fun looksLikeSelfTransfer(body: String, ownLast4s: Set<String>, thisLast4: String?): Boolean {
        if (selfWords.containsMatchIn(body)) return true
        val targets = ownTarget.findAll(body).map { it.groupValues[1] }
        return targets.any { it in ownLast4s && it != thisLast4 }
    }
}
