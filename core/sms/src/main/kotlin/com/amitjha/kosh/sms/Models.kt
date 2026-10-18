package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType

/** A raw SMS as read from the device inbox or a broadcast. */
data class SmsMessage(
    val sender: String,
    val body: String,
    /** When the message was sent (the network timestamp); used for fingerprints and as the fallback date. */
    val timestampMillis: Long,
    /**
     * When the phone stored the message, if known and different. Versions before 1.8 fingerprinted inbox
     * messages with this time, so the importer also checks it to avoid importing the same SMS twice.
     */
    val receivedMillis: Long? = null,
    /**
     * The network's send time when [timestampMillis] doesn't use it because it was implausible (see [SmsTime]).
     * Versions before 2.1.8 dated and fingerprinted the message with it, so the importer can find and redate those rows.
     */
    val implausibleSentMillis: Long? = null,
)

/** Outcome of parsing one SMS. */
sealed interface SmsParseResult {
    data class Transaction(val txn: ParsedTransaction) : SmsParseResult
    data class CardBill(val bill: ParsedCardBill) : SmsParseResult
    data class BalanceUpdate(val update: ParsedBalance) : SmsParseResult
    data class Ignored(val reason: String) : SmsParseResult
}

/**
 * A money movement detected in an SMS.
 *
 * [type] is [TxnType.EXPENSE] for debits and card spends, [TxnType.INCOME] for credits, refunds and
 * payments received on a credit card ([isCardPayment] = true in that case).
 */
data class ParsedTransaction(
    val bank: Bank,
    val accountLast4: String?,
    val isCreditCard: Boolean,
    val isCardPayment: Boolean,
    val type: TxnType,
    val amount: Paise,
    val merchant: String?,
    val channel: TxnChannel,
    val referenceNo: String?,
    val timestampMillis: Long,
    val availableBalance: Paise?,
    val availableCreditLimit: Paise?,
    val suggestedCategory: String,
    /** Stable dedupe key: re-scanning the same inbox yields the same value. */
    val fingerprint: String,
)

/** A credit card statement or bill-due message. */
data class ParsedCardBill(
    val bank: Bank,
    val cardLast4: String?,
    val totalDue: Paise,
    val minimumDue: Paise?,
    val dueDateEpochDay: Long?,
    val statementDateEpochDay: Long?,
    /** Same bill announced twice (statement + reminder) shares this key. */
    val fingerprint: String,
)

/** A balance statement without a transaction (e.g. "Available balance ... is Rs.X"). */
data class ParsedBalance(
    val bank: Bank,
    val accountLast4: String?,
    val availableBalance: Paise?,
    val availableCreditLimit: Paise?,
    val timestampMillis: Long,
)
