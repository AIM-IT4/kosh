package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType
import java.security.MessageDigest

/** Entry point: turns one SMS into a transaction, a card bill, a balance update, or an ignore reason. */
object SmsParser {

    private val debitWords = Regex(
        "\\b(debited|spent|sent|withdrawn|paid|purchase|has been used for|used for|deducted|charged|" +
            "debit(?:ed)? (?:for|with|by)|transferred to|dr\\.?)\\b",
    )
    private val creditWords = Regex("\\b(credited|received|deposited|refund(?:ed)?|reversed|reversal|cashback|added to|cr\\.?)\\b")

    // "amount of ... credited" is deliberately not here: refunds and reversals to a card are phrased that way.
    private val cardPayment = Regex(
        "payment of\\s*$CUR\\s*$NUM.{0,80}?(?:received|credited|has been received|successfully)|" +
            "(?:received|thank you for)(?: your)? payment of\\s*$CUR\\s*$NUM",
    )

    private val refundWords = Regex("\\brefund|\\breversal|\\breversed|cashback|cash back")

    /**
     * A bank account was debited ("Acct XX123 debited", "debited from A/c XX4521"). When such a message also
     * mentions a credit card it is the bank side of a card bill payment, not a card spend.
     */
    private val accountDebited = Regex(
        "(?:a/?c|acct|account)\\s*(?:no\\.?\\s*)?[x*.\\d]{0,12}\\s*(?:is\\s+|has been\\s+)?debited|" +
            "debited\\s+(?:from|in)\\s+(?:your\\s+)?(?:a/?c|acct|account)",
    )

    fun parse(msg: SmsMessage): SmsParseResult {
        val body = msg.body.replace(Regex("\\s+"), " ").trim()
        val lower = body.lowercase()
        val bank = BankDetector.detect(msg.sender, body) ?: return SmsParseResult.Ignored("not a bank sender")

        Filters.hardReject(lower)?.let { return SmsParseResult.Ignored(it) }

        val mentionsCard = Regex("\\bcard\\b").containsMatchIn(lower)
        val bankPaysCard = lower.contains("credit card") && accountDebited.containsMatchIn(lower)
        val creditCard = !bankPaysCard && (
            lower.contains("credit card") ||
                (mentionsCard && !lower.contains("debit card") && Extract.creditLimit(body) != null)
            )

        // credit card payment received (before bills, which also mention "payment")
        if (!bankPaysCard && (creditCard || lower.contains("card"))) {
            cardPayment.find(lower)?.let { m ->
                val amt = Money.parse(m.groupValues[1].ifEmpty { m.groupValues[2] }) ?: return@let
                if (!lower.contains("credit card") && !lower.contains("card account")) return@let
                if (refundWords.containsMatchIn(lower)) return@let
                return SmsParseResult.Transaction(buildTxn(msg, body, lower, bank, TxnType.INCOME, amt, true, true))
            }
        }

        if (mentionsCard) BillParser.parse(bank, body, lower)?.let { return SmsParseResult.CardBill(it) }

        Filters.softReject(lower)?.let { return SmsParseResult.Ignored(it) }

        val type = transactionType(lower)
        val amount = Extract.amount(body)
        if (type == null || amount == null) {
            val bal = Extract.balanceStatement(body)
            val lim = Extract.creditLimit(body)
            if (bal != null || lim != null) {
                return SmsParseResult.BalanceUpdate(
                    ParsedBalance(bank, Extract.last4(body), bal, lim, msg.timestampMillis),
                )
            }
            return SmsParseResult.Ignored("no transaction found")
        }
        if (bankPaysCard) {
            // The bank side of a card bill payment: money moves between the user's own accounts.
            return SmsParseResult.Transaction(
                buildTxn(msg, body, lower, bank, type, amount, false, false, forcedCategory = "transfer"),
            )
        }
        val isCc = creditCard || isCardSpend(lower)
        return SmsParseResult.Transaction(buildTxn(msg, body, lower, bank, type, amount, isCc, false))
    }

    /** Spends phrased like "Spent Rs.X On HDFC Bank Card 5678 At ..." (no "debit card", no account). */
    private fun isCardSpend(lower: String): Boolean =
        Regex("\\bcard\\b").containsMatchIn(lower) && !lower.contains("debit card") &&
            !Regex("\\ba/?c\\b|\\bacct\\b|\\baccount\\b").containsMatchIn(lower) &&
            Regex("\\bspent\\b|\\bused\\b").containsMatchIn(lower)

    /**
     * EXPENSE or INCOME, decided by whichever verb appears first ("debited ...; Zomato credited" is a debit).
     * Without either, an executed AutoPay / mandate / standing instruction ("SI executed: INR 5,000 ...") is a debit.
     */
    private fun transactionType(lower: String): TxnType? {
        val d = debitWords.find(lower)?.range?.first
        val c = creditWords.find(lower)?.range?.first
        return when {
            d == null && c == null -> if (Filters.isExecutedAutoDebit(lower)) TxnType.EXPENSE else null
            c == null -> TxnType.EXPENSE
            d == null -> TxnType.INCOME
            d < c -> TxnType.EXPENSE
            else -> TxnType.INCOME
        }
    }

    private fun buildTxn(
        msg: SmsMessage,
        body: String,
        lower: String,
        bank: Bank,
        type: TxnType,
        amount: Paise,
        isCreditCard: Boolean,
        isCardPayment: Boolean,
        forcedCategory: String? = null,
    ): ParsedTransaction {
        val channel = if (isCardPayment) TxnChannel.OTHER else Extract.channel(lower).let {
            if (isCreditCard && it == TxnChannel.OTHER) TxnChannel.CARD else it
        }
        val merchant = when {
            isCardPayment -> "Credit card payment"
            channel == TxnChannel.ATM -> "ATM withdrawal"
            else -> extractMerchant(bank, body)
        }
        val last4 = Extract.last4(body)
        val ref = Extract.reference(body)
        val time = Dates.transactionTime(Dates.first(body), msg.timestampMillis)
        val category = forcedCategory ?: if (isCardPayment) "transfer" else CategoryGuesser.guess(merchant, body, type, channel)
        return ParsedTransaction(
            bank = bank,
            accountLast4 = last4,
            isCreditCard = isCreditCard,
            isCardPayment = isCardPayment,
            type = type,
            amount = amount,
            merchant = merchant,
            channel = channel,
            referenceNo = ref,
            timestampMillis = time,
            availableBalance = if (isCreditCard) null else Extract.balance(body),
            availableCreditLimit = Extract.creditLimit(body),
            suggestedCategory = category,
            fingerprint = Fingerprint.transaction(bank, last4, amount, type, ref, msg),
        )
    }
}

internal object Fingerprint {
    fun transaction(bank: Bank, last4: String?, amount: Paise, type: TxnType, ref: String?, msg: SmsMessage): String {
        val b = BankDetector.fingerprintBank(bank, msg.body.replace(Regex("\\s+"), " "))
        val key = if (ref != null) {
            "T|$b|${last4.orEmpty()}|$amount|$type|$ref"
        } else {
            val minute = msg.timestampMillis / 60_000
            val normalized = msg.body.lowercase().replace(Regex("\\s+"), " ").trim()
            "T|$b|${last4.orEmpty()}|$amount|$type|$minute|${normalized.hashCode()}"
        }
        return sha(key)
    }

    /** [body] is the (whitespace-normalised) message, used only to keep fingerprints of [BANKS_FORMERLY_OTHER] stable. */
    fun bill(bank: Bank, body: String, last4: String?, total: Paise, due: Long?, statement: Long?): String =
        sha("B|${BankDetector.fingerprintBank(bank, body)}|${last4.orEmpty()}|$total|${due ?: statement ?: 0}")

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
