package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.CategoryGuesser
import com.amitjha.kosh.sms.MerchantNormalizer
import com.amitjha.kosh.sms.ParsedTransaction
import com.amitjha.kosh.sms.SmsMessage
import com.amitjha.kosh.sms.SmsParseResult
import com.amitjha.kosh.sms.SmsParser
import java.security.MessageDigest

/**
 * Turns bank transaction-alert e-mails ("Thank you for using your HDFC Bank Credit Card ending 1234 for
 * Rs 500.00 at AMAZON ...") into transactions.
 *
 * Alert e-mails use the same wording as the banks' SMS, so the core sentence is handed to the SMS parser
 * with a synthetic sender for the issuer; e-mail-only phrasings are handled by patterns here. Footers
 * ("never share your OTP", offers) are cut away first so they cannot cause a rejection.
 */
object AlertEmailParser {

    private val syntheticSender = mapOf(
        Bank.HDFC to "AD-HDFCBK", Bank.ICICI to "AD-ICICIB", Bank.KOTAK to "AD-KOTAKB",
        Bank.DBS to "AD-DBSBNK", Bank.SBI to "AD-SBICRD", Bank.AXIS to "AD-AXISBK", Bank.OTHER to "AD-BANKAL",
    )

    private const val CUR = "(?:rs\\.?|inr|₹)"
    private const val NUM = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    private val amountRx = Regex("(?i)$CUR\\s*$NUM")
    private val verbRx = Regex(
        "(?i)\\b(debited|credited|spent|withdrawn|sent|received|paid|transferred|transaction|purchase|used|using|deducted|refund)",
    )
    private val last4Rx = Regex("(?i)(?:ending(?:\\s*(?:with|in))?|xx+|x|\\*{2,})\\s*(\\d{4})\\b")

    /** E-mail phrasings the SMS parser does not cover. Group 1 = amount, merchant group named "m". */
    private val emailPatterns = listOf(
        // "... Credit Card ending 1234 for Rs 500.00 at AMAZON on 05-01-2025 10:10:10"
        Regex("(?i)card\\s+ending\\s*\\d{4}\\s+for\\s+$CUR\\s*$NUM\\s+at\\s+(?<m>.+?)(?:\\s+on\\s|\\.\\s|\\.$|$)"),
        // "... Credit Card XX1234 has been used for a transaction of INR 1,234.00 on ... Info: AMAZON"
        Regex("(?i)used for a transaction of\\s+$CUR\\s*$NUM.*?info\\s*:?\\s*(?<m>[^.]+)"),
        // "Rs.499.00 spent on your Kotak Credit Card x1234 at NETFLIX on 05-Jan-2025"
        Regex("(?i)$CUR\\s*$NUM\\s+spent\\s+on\\s+your\\s+.*?card.*?\\s+at\\s+(?<m>.+?)(?:\\s+on\\s|\\.\\s|\\.$|$)"),
        // "... Credit Card ending 1234 was used for INR 899 at SWIGGY"
        Regex("(?i)was used for\\s+$CUR\\s*$NUM\\s+at\\s+(?<m>.+?)(?:\\s+on\\s|\\.\\s|\\.$|$)"),
    )

    /** The transaction in an alert e-mail, or null when the e-mail is not a transaction alert. */
    fun parse(email: EmailMessage): ParsedTransaction? {
        val text = email.bodyText.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        val bank = IssuerDirectory.bankForSender(email.from) ?: IssuerDirectory.bankInText("${email.subject} $text") ?: Bank.OTHER
        val core = coreSentences(text) ?: return null

        val sms = SmsMessage(syntheticSender.getValue(bank), core, email.dateMillis)
        val parsed = runCatching { SmsParser.parse(sms) }.getOrNull()
        if (parsed is SmsParseResult.Transaction) {
            return parsed.txn.copy(fingerprint = fingerprint(email.id), bank = if (parsed.txn.bank == Bank.OTHER) bank else parsed.txn.bank)
        }
        return emailPattern(core, bank, email)
    }

    /** The sentence carrying the amount and a transaction verb, plus the one after it (merchant/info). */
    internal fun coreSentences(text: String): String? {
        val sentences = text.split(Regex("(?<=[.!?])\\s+(?=[A-Z])"))
        val i = sentences.indexOfFirst { amountRx.containsMatchIn(it) && verbRx.containsMatchIn(it) }
        if (i < 0) return null
        return sentences.drop(i).take(2).joinToString(" ").take(400)
    }

    private fun emailPattern(core: String, bank: Bank, email: EmailMessage): ParsedTransaction? {
        for (rx in emailPatterns) {
            val m = rx.find(core) ?: continue
            val amount = Money.parse(m.groupValues[1]) ?: continue
            val merchantRaw = m.groups["m"]?.value?.trim()?.trimEnd('.', ',')
            val merchant = merchantRaw?.let(MerchantNormalizer::normalize)?.takeIf { it.isNotBlank() }
            val isCredit = core.contains("credit card", ignoreCase = true) || !core.contains("debit card", ignoreCase = true)
            return ParsedTransaction(
                bank = bank,
                accountLast4 = last4Rx.find(core)?.groupValues?.get(1),
                isCreditCard = isCredit,
                isCardPayment = false,
                type = TxnType.EXPENSE,
                amount = amount,
                merchant = merchant,
                channel = TxnChannel.CARD,
                referenceNo = null,
                timestampMillis = email.dateMillis,
                availableBalance = null,
                availableCreditLimit = null,
                suggestedCategory = CategoryGuesser.guess(merchant, core, TxnType.EXPENSE),
                fingerprint = fingerprint(email.id),
            )
        }
        return null
    }

    private fun fingerprint(emailId: String): String =
        MessageDigest.getInstance("SHA-256").digest("E|$emailId".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
