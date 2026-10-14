package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
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
        val candidates = accounts.filter { !it.archived && it.bank == bank.name && it.type == type }
        if (candidates.isEmpty()) return null
        if (last4 != null) {
            candidates.firstOrNull { it.last4 == last4 }?.let { return it }
            // A message with digits never matches an account with different digits.
            val withoutDigits = candidates.filter { it.last4 == null }
            return withoutDigits.singleOrNull()
        }
        return candidates.singleOrNull()
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
            // Opening date far in the past so imported history counts towards the balance.
            openingDate = 0L,
            colorArgb = bank.brandColor,
            autoCreated = true,
        )
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
        !txn.excludeFromStats && txn.type != TxnType.TRANSFER && txn.categoryKey != TRANSFER_CATEGORY
}
