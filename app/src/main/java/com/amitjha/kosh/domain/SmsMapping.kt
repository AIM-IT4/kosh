package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.ParsedCardBill
import com.amitjha.kosh.sms.ParsedTransaction

/** Pure mapping rules from parsed SMS/e-mail data to database entities. */
object SmsMapping {

    /** Category key used for money moved between the user's own accounts; excluded from income/expense stats. */
    const val TRANSFER_CATEGORY = "transfer"

    /**
     * Finds the account a message belongs to: same bank and kind (card vs account), and the same last 4 digits
     * when both are known. If the message has no digits and the user has exactly one such account, use it.
     */
    fun matchAccount(accounts: List<AccountEntity>, bank: Bank, last4: String?, isCard: Boolean): AccountEntity? {
        val type = if (isCard) AccountType.CREDIT_CARD else AccountType.BANK
        val sameKind = accounts.filter { it.bank == bank.name && it.type == type }
        val candidates = sameKind.filter { !it.archived }
        if (last4 != null) {
            candidates.firstOrNull { it.last4 == last4 }?.let { return it }
            // A message with digits never matches an account with different digits; an account the user added
            // without digits matches only when it is the sole account of this bank and kind.
            candidates.filter { it.last4 == null }.takeIf { it.size == 1 && candidates.size == 1 }?.let { return it.single() }
            // An account the user chose to ignore keeps receiving its messages, so it isn't recreated.
            return sameKind.firstOrNull { it.archived && it.last4 == last4 }
        }
        return candidates.singleOrNull()
    }

    /** Above this, an imported amount (other than salary) is held for review instead of moving balances. */
    const val SUSPICIOUS_ABSOLUTE: Paise = 10_00_000L * 100

    /** An amount this many times the account's median is suspicious, provided it is also above [SUSPICIOUS_FLOOR]. */
    const val SUSPICIOUS_MEDIAN_MULTIPLE = 20

    const val SUSPICIOUS_FLOOR: Paise = 50_000L * 100

    /**
     * Whether an imported amount looks implausible, typically a reference or account number read as an amount.
     * Salary credits are trusted. [recentAmounts] are the account's latest amounts (at least 5 are needed
     * for the median rule).
     */
    fun isSuspicious(amount: Paise, categoryKey: String, recentAmounts: List<Paise>): Boolean {
        if (categoryKey == "salary") return false
        if (amount > SUSPICIOUS_ABSOLUTE) return true
        if (recentAmounts.size < 5 || amount <= SUSPICIOUS_FLOOR) return false
        val sorted = recentAmounts.sorted()
        val median = sorted[sorted.size / 2]
        return median > 0 && amount > median * SUSPICIOUS_MEDIAN_MULTIPLE
    }

    /** The account to create when no existing account matches. */
    fun newAccountFor(bank: Bank, last4: String?, isCard: Boolean, now: Long): AccountEntity {
        val suffix = last4?.let { " ••$it" } ?: ""
        val kind = if (isCard) "Card" else "Account"
        return AccountEntity(
            name = "${bank.shortName} $kind$suffix",
            type = if (isCard) AccountType.CREDIT_CARD else AccountType.BANK,
            bank = bank.name,
            last4 = last4,
            openingBalance = 0,
            // Pending until the user confirms it; until then its history never counts towards net worth.
            openingDate = 0L,
            colorArgb = bank.brandColor,
            autoCreated = true,
        )
    }

    /** Lower-case letters and digits only, so "Swiggy Ltd", "SWIGGY" and "swiggy" share one rule. */
    fun merchantKey(merchant: String): String =
        merchant.lowercase().replace(Regex("^(upi|vpa|pos)[ :/-]+"), "").replace(Regex("[^a-z0-9]"), "")

    /**
     * The user's learned category for [merchant]: an exact rule, then a rule for the same merchant spelled
     * differently, then a rule whose first word (4+ letters) matches, e.g. "Sharma Store" → "Sharma Stores".
     */
    fun ruleFor(rules: Map<String, String>, merchant: String?): String? {
        if (merchant.isNullOrBlank()) return null
        rules[merchant]?.let { return it }
        val key = merchantKey(merchant)
        if (key.isEmpty()) return null
        rules.entries.firstOrNull { merchantKey(it.key) == key }?.let { return it.value }
        val first = firstWord(merchant)
        if (first.length < 4) return null
        return rules.entries.firstOrNull { firstWord(it.key) == first }?.value
    }

    private fun firstWord(s: String): String =
        s.trim().substringBefore(' ').lowercase().filter { it.isLetterOrDigit() }

    /**
     * Picks a better category for an imported transaction stuck in "other" (or in the old "transfer"
     * bucket for payments to a person), using the user's rules first and the category guesser second.
     * Returns null when nothing should change. Manual entries are never touched.
     */
    fun recategorised(txn: TransactionEntity, rules: Map<String, String>): TransactionEntity? {
        if (txn.source == TxnSource.MANUAL || txn.type != TxnType.EXPENSE) return null
        val legacyPersonTransfer = txn.categoryKey == TRANSFER_CATEGORY && !txn.reviewed && txn.toAccountId == null &&
            txn.merchant?.startsWith("UPI X", ignoreCase = true) == true
        if (txn.categoryKey != "other" && !legacyPersonTransfer) return null
        val next = ruleFor(rules, txn.merchant)
            ?: com.amitjha.kosh.sms.CategoryGuesser.guess(txn.merchant, txn.note.orEmpty(), txn.type, txn.channel)
        if (next == txn.categoryKey || next == "other") return null
        return txn.copy(categoryKey = next, excludeFromStats = next == TRANSFER_CATEGORY)
    }

    fun toTransaction(parsed: ParsedTransaction, accountId: Long, categoryOverride: String?): TransactionEntity {
        val cardPayment = parsed.isCardPayment
        return TransactionEntity(
            accountId = accountId,
            type = parsed.type,
            amount = parsed.amount,
            categoryKey = when {
                cardPayment -> TRANSFER_CATEGORY
                else -> categoryOverride ?: parsed.suggestedCategory
            },
            merchant = parsed.merchant,
            timestamp = parsed.timestampMillis,
            channel = parsed.channel,
            source = TxnSource.SMS,
            fingerprint = parsed.fingerprint,
            referenceNo = parsed.referenceNo,
            reviewed = false,
            // A card payment moves money between the user's own accounts.
            excludeFromStats = cardPayment || (categoryOverride ?: parsed.suggestedCategory) == TRANSFER_CATEGORY,
        )
    }

    fun toBill(parsed: ParsedCardBill, accountId: Long, source: TxnSource = TxnSource.SMS): CardBillEntity =
        CardBillEntity(
            accountId = accountId,
            totalDue = parsed.totalDue,
            minimumDue = parsed.minimumDue,
            dueDate = parsed.dueDateEpochDay,
            statementDate = parsed.statementDateEpochDay,
            source = source,
            fingerprint = parsed.fingerprint,
        )

    /** Whether [txn] counts as income or expense in summaries. */
    fun countsInStats(txn: TransactionEntity): Boolean =
        !txn.excludeFromStats && !txn.suspicious && txn.type != TxnType.TRANSFER && txn.categoryKey != TRANSFER_CATEGORY
}
