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

    private val cardPayment = Regex(
        "(?:payment|amount) of\\s*$CUR\\s*$NUM.{0,80}?(?:received|credited|has been received|successfully)|" +
            "(?:received|thank you for)(?: your)? payment of\\s*$CUR\\s*$NUM",
    )

    fun parse(msg: SmsMessage): SmsParseResult {
        val body = msg.body.replace(Regex("\\s+"), " ").trim()
        val lower = body.lowercase()
        val bank = BankDetector.detect(msg.sender, body) ?: return SmsParseResult.Ignored("not a bank sender")

        Filters.hardReject(lower)?.let { return SmsParseResult.Ignored(it) }

        val mentionsCard = Regex("\\bcard\\b").containsMatchIn(lower)
        val creditCard = lower.contains("credit card") ||
            (mentionsCard && !lower.contains("debit card") && Extract.creditLimit(body) != null)

        // credit card payment received (before bills, which also mention "payment")
        if (creditCard || lower.contains("card")) {
            cardPayment.find(lower)?.let { m ->
                val amt = Money.parse(m.groupValues[1].ifEmpty { m.groupValues[2] }) ?: return@let
                if (!lower.contains("credit card") && !lower.contains("card account")) return@let
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
        val isCc = creditCard || isCardSpend(lower)
        return SmsParseResult.Transaction(buildTxn(msg, body, lower, bank, type, amount, isCc, false))
    }

    /** Spends phrased like "Spent Rs.X On HDFC Bank Card 5678 At ..." (no "debit card", no account). */
    private fun isCardSpend(lower: String): Boolean =
        Regex("\\bcard\\b").containsMatchIn(lower) && !lower.contains("debit card") &&
            !Regex("\\ba/?c\\b|\\bacct\\b|\\baccount\\b").containsMatchIn(lower) &&
            Regex("\\bspent\\b|\\bused\\b").containsMatchIn(lower)

    /** EXPENSE or INCOME, decided by whichever verb appears first ("debited ...; Zomato credited" is a debit). */
    private fun transactionType(lower: String): TxnType? {
        val d = debitWords.find(lower)?.range?.first
        val c = creditWords.find(lower)?.range?.first
        return when {
            d == null && c == null -> null
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
        val category = if (isCardPayment) "transfer" else CategoryGuesser.guess(merchant, body, type)
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
        val key = if (ref != null) {
            "T|$bank|${last4.orEmpty()}|$amount|$type|$ref"
        } else {
            val minute = msg.timestampMillis / 60_000
            val normalized = msg.body.lowercase().replace(Regex("\\s+"), " ").trim()
            "T|$bank|${last4.orEmpty()}|$amount|$type|$minute|${normalized.hashCode()}"
        }
        return sha(key)
    }

    fun bill(bank: Bank, last4: String?, total: Paise, due: Long?, statement: Long?): String =
        sha("B|$bank|${last4.orEmpty()}|$total|${due ?: statement ?: 0}")

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
