package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank

/**
 * Indian commercial SMS arrive from DLT headers such as "AD-HDFCBK", "VM-ICICIB", "JD-KOTAKB-S".
 * The 6-character core identifies the sender; the 2-letter prefix is the operator/circle.
 */
private val BANK_HEADERS: Map<String, Bank> = mapOf(
    "HDFCBK" to Bank.HDFC, "HDFCBN" to Bank.HDFC, "HDFCCC" to Bank.HDFC, "HDFCBA" to Bank.HDFC,
    "ICICIB" to Bank.ICICI, "ICICIT" to Bank.ICICI, "ICICIO" to Bank.ICICI, "ICICIC" to Bank.ICICI,
    "KOTAKB" to Bank.KOTAK, "KOTAK" to Bank.KOTAK, "KMBANK" to Bank.KOTAK, "KOTAKC" to Bank.KOTAK,
    "KMBLCC" to Bank.KOTAK,
    "DBSBNK" to Bank.DBS, "DBSBANK" to Bank.DBS, "DIGIBK" to Bank.DBS, "DBSIND" to Bank.DBS,
    "SBIINB" to Bank.SBI, "SBIUPI" to Bank.SBI, "SBICRD" to Bank.SBI, "ATMSBI" to Bank.SBI,
    "SBIPSG" to Bank.SBI, "CBSSBI" to Bank.SBI, "SBMSMS" to Bank.SBI,
    "AXISBK" to Bank.AXIS, "AXISBN" to Bank.AXIS, "AXISCC" to Bank.AXIS, "AXISMR" to Bank.AXIS,
)

/** Headers of banks/cards/payment banks not modelled individually but clearly financial. */
private val OTHER_FINANCIAL_HEADERS = setOf(
    "YESBNK", "INDUSB", "IDFCFB", "PNBSMS", "BOBTXN", "BOBSMS", "CANBNK", "UNIONB", "IOBCHN",
    "AUBANK", "FEDBNK", "RBLBNK", "SCBANK", "CITIBK", "HSBCIN", "AMEXIN", "ONECRD", "SLCEIT",
    "PAYTMB", "AIRBNK", "JIOPBK", "IDBIBK", "BOIIND", "CENTBK",
)

/**
 * Reduces a sender to its identifying core: "VK-HDFCBK-S" -> "HDFCBK", "hdfcbk" -> "HDFCBK",
 * RCS business names such as "Kotak Mahindra Bank" -> "KOTAKMAHINDRABANK".
 */
internal fun headerCore(sender: String): String {
    val up = sender.trim().uppercase()
    if (up.contains(' ')) return up.filter { it.isLetterOrDigit() }
    val parts = up.split('-', '_', '.').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> ""
        parts.size == 1 -> parts[0]
        // "AD-HDFCBK", "VK-HDFCBK-S": the longest token is the brand (prefix = operator, suffix = S/T/P/G)
        else -> parts.maxBy { it.length }
    }.filter { it.isLetterOrDigit() || it == '+' }
}

/** Bank names that may appear inside an RCS sender name or a longer header. */
private val BANK_NAME_HINTS: List<Pair<String, Bank>> = listOf(
    "HDFC" to Bank.HDFC, "ICICI" to Bank.ICICI, "KOTAK" to Bank.KOTAK, "DBS" to Bank.DBS,
    "DIGIBANK" to Bank.DBS, "SBI" to Bank.SBI, "STATEBANK" to Bank.SBI, "AXIS" to Bank.AXIS,
)

internal fun bankFromSenderName(sender: String): Bank? {
    val core = headerCore(sender)
    BANK_HEADERS[core]?.let { return it }
    BANK_HEADERS.entries.firstOrNull { core.startsWith(it.key.take(5)) }?.let { return it.value }
    return BANK_NAME_HINTS.firstOrNull { core.contains(it.first) }?.second
}

/** True when [sender] looks like a bank / card issuer DLT header (or RCS name) rather than a person or a brand. */
fun isLikelyFinancialSender(sender: String): Boolean {
    val core = headerCore(sender)
    if (core.isEmpty() || core.all { it.isDigit() || it == '+' }) return false
    if (core in BANK_HEADERS || core in OTHER_FINANCIAL_HEADERS) return true
    if (bankFromSenderName(sender) != null) return true
    return Regex("BNK|BANK|BK$|CARD|CRD").containsMatchIn(core) &&
        !Regex("OFFER|PROMO|SALE|LOAN").containsMatchIn(core)
}

private val amountPattern = Regex("(?i)(?:rs\\.?|inr|₹)\\s*[0-9][0-9,]*(?:\\.\\d{1,2})?")
private val txnVerbs = Regex(
    "(?i)\\b(debited|credited|spent|withdrawn|sent|received|paid|transferred|txn|transaction|purchase|" +
        "used for|deducted|deposited|refund(?:ed)?)\\b",
)
private val accountMention = Regex(
    "(?i)\\b(a/?c|ac|acct|account|card)\\b|\\bx{2,}\\s?\\d{3,4}\\b|\\*{2,}\\d{3,4}\\b|ending\\s*(?:with\\s*)?\\d{4}|\\bupi\\b",
)

/** True when the body reads like a bank transaction alert: an amount, a transaction verb, and an account/card/UPI. */
fun looksTransactional(body: String): Boolean =
    amountPattern.containsMatchIn(body) && txnVerbs.containsMatchIn(body) && accountMention.containsMatchIn(body)

/**
 * Permissive gate used by the importer: known bank senders always pass; any other sender (DLT header,
 * RCS name, short code or number) passes when the body itself looks like a transaction alert.
 * The parser then decides what the message is.
 */
fun isFinancialMessage(sender: String, body: String): Boolean {
    if (isLikelyFinancialSender(sender)) return true
    if (!looksTransactional(body)) return false
    // Personal mobile numbers need stronger evidence (a masked account/card number or a reference),
    // so chats like "sent you Rs.500, debited from my a/c" are not imported.
    return !isPersonalNumber(sender) || maskedOrReference.containsMatchIn(body)
}

private val maskedOrReference = Regex(
    "(?i)\\bx{1,}\\s?\\d{3,4}\\b|\\*{2,}\\d{3,4}\\b|ending\\s*(?:with\\s*)?\\d{4}|\\bref(?:\\s*no)?\\.?\\s*:?\\s*\\d{6,}",
)

private fun isPersonalNumber(sender: String): Boolean {
    val digits = sender.filter { it.isDigit() }
    return sender.trim().all { it.isDigit() || it == '+' || it == ' ' || it == '-' } && digits.length >= 10
}

internal object BankDetector {
    private val bodyHints = listOf(
        Regex("(?i)\\bhdfc\\b") to Bank.HDFC,
        Regex("(?i)\\bicici\\b") to Bank.ICICI,
        Regex("(?i)\\bkotak\\b") to Bank.KOTAK,
        Regex("(?i)\\bdbs\\b|digibank") to Bank.DBS,
        Regex("(?i)\\bsbi\\b|state bank") to Bank.SBI,
        Regex("(?i)\\baxis\\b") to Bank.AXIS,
    )

    /** Returns the bank, [Bank.OTHER] for unmodelled financial senders, or null when not financial. */
    fun detect(sender: String, body: String): Bank? {
        bankFromSenderName(sender)?.let { return it }
        if (!isFinancialMessage(sender, body)) return null
        bodyHints.firstOrNull { it.first.containsMatchIn(body) }?.let { return it.second }
        return Bank.OTHER
    }
}
