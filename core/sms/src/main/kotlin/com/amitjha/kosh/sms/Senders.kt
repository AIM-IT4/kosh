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
    "PNBSMS" to Bank.PNB, "PUNBNK" to Bank.PNB,
    "BOBTXN" to Bank.BOB, "BOBSMS" to Bank.BOB, "BOBCRD" to Bank.BOB,
    "CANBNK" to Bank.CANARA, "CANBKS" to Bank.CANARA,
    "UNIONB" to Bank.UNION, "UBOIND" to Bank.UNION,
    "IDFCFB" to Bank.IDFC, "IDFCBK" to Bank.IDFC,
    "INDUSB" to Bank.INDUSIND, "INDUSN" to Bank.INDUSIND,
    "YESBNK" to Bank.YES, "YESBK" to Bank.YES,
    "AUBANK" to Bank.AU, "AUBNKS" to Bank.AU,
    "FEDBNK" to Bank.FEDERAL, "FEDFIB" to Bank.FEDERAL,
)

/** Headers of banks/cards/payment banks not modelled individually but clearly financial. */
private val OTHER_FINANCIAL_HEADERS = setOf(
    "IOBCHN", "RBLBNK", "SCBANK", "CITIBK", "HSBCIN", "AMEXIN", "ONECRD", "SLCEIT",
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

/**
 * Bank names that may appear inside an RCS sender name or a longer header. Names of the newer banks are
 * spelled out in full ("UNIONBANK", not "UNION"; "CANARABANK", not "CANARA", which is also Canara HSBC Life
 * and Canara Robeco MF) so that group companies and unrelated brands don't match.
 */
private val BANK_NAME_HINTS: List<Pair<String, Bank>> = listOf(
    "HDFC" to Bank.HDFC, "ICICI" to Bank.ICICI, "KOTAK" to Bank.KOTAK, "DBS" to Bank.DBS,
    "DIGIBANK" to Bank.DBS, "SBI" to Bank.SBI, "STATEBANK" to Bank.SBI, "AXIS" to Bank.AXIS,
    "PUNJABNATIONAL" to Bank.PNB, "BANKOFBARODA" to Bank.BOB, "BOBCARD" to Bank.BOB,
    "CANARABANK" to Bank.CANARA, "UNIONBANK" to Bank.UNION, "IDFCFIRST" to Bank.IDFC, "IDFCBANK" to Bank.IDFC,
    "INDUSIND" to Bank.INDUSIND, "YESBANK" to Bank.YES, "AUBANK" to Bank.AU, "AUSMALLFINANCE" to Bank.AU,
    "FEDERALBANK" to Bank.FEDERAL,
)

/** Header prefixes too generic to name a bank on their own ("UNIONMF", "INDUSTOWER"); only exact headers count. */
private val AMBIGUOUS_PREFIXES = setOf("UNION", "INDUS")

internal fun bankFromSenderName(sender: String): Bank? {
    val core = headerCore(sender)
    BANK_HEADERS[core]?.let { return it }
    BANK_HEADERS.entries.firstOrNull { val p = it.key.take(5); p !in AMBIGUOUS_PREFIXES && core.startsWith(p) }?.let { return it.value }
    return BANK_NAME_HINTS.firstOrNull { core.contains(it.first) }?.second
}

/** True when [sender] looks like a bank / card issuer DLT header (or RCS name) rather than a person or a brand. */
fun isLikelyFinancialSender(sender: String): Boolean {
    val core = headerCore(sender)
    if (core.isEmpty() || core.all { it.isDigit() || it == '+' }) return false
    if (core in BANK_HEADERS || core in OTHER_FINANCIAL_HEADERS) return true
    if (bankFromSenderName(sender) != null) return true
    return bankishHeader.containsMatchIn(core) && !promoHeader.containsMatchIn(core)
}

private val bankishHeader = Regex("BNK|BANK|BK$|CARD|CRD")
private val promoHeader = Regex("OFFER|PROMO|SALE|LOAN")

private val amountPattern = Regex("(?i)(?:rs[.:]?|inr|₹)\\s*[0-9][0-9,]*(?:\\.\\d{1,2})?")
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

/**
 * Banks that Kosh filed under [Bank.OTHER] before it recognised them. Their transactions keep the
 * fingerprint they had then (see [BankDetector.fingerprintBank]) so that re-scanning the inbox doesn't
 * import them a second time, and the importer may keep using an existing "Other" account with the same digits.
 */
val BANKS_FORMERLY_OTHER: Set<Bank> = setOf(
    Bank.PNB, Bank.BOB, Bank.CANARA, Bank.UNION, Bank.IDFC, Bank.INDUSIND, Bank.YES, Bank.AU, Bank.FEDERAL,
)

internal object BankDetector {
    /** Body hints of older versions, in their order; [fingerprintBank] depends on them, so don't change these. */
    private val legacyHints = listOf(
        Regex("(?i)\\bhdfc\\b") to Bank.HDFC,
        Regex("(?i)\\bicici\\b") to Bank.ICICI,
        Regex("(?i)\\bkotak\\b") to Bank.KOTAK,
        Regex("(?i)\\bdbs\\b|digibank") to Bank.DBS,
        Regex("(?i)\\bsbi\\b|state bank") to Bank.SBI,
        Regex("(?i)\\baxis\\b") to Bank.AXIS,
    )

    /** Names of the banks modelled later, matched outside UPI handles ("rahul@pnb" names the payee's bank). */
    private val newerHints = listOf(
        Regex("(?i)punjab national bank|\\bpnb\\b(?!\\s*(?:housing|metlife))") to Bank.PNB,
        Regex("(?i)bank of baroda|\\bbob ?card\\b") to Bank.BOB,
        Regex("(?i)canara bank") to Bank.CANARA,
        Regex("(?i)union bank") to Bank.UNION,
        Regex("(?i)idfc first|idfc bank") to Bank.IDFC,
        Regex("(?i)\\bindusind\\b") to Bank.INDUSIND,
        Regex("(?i)\\byes bank\\b") to Bank.YES,
        Regex("(?i)\\bau small finance|\\bau bank\\b") to Bank.AU,
        Regex("(?i)federal bank") to Bank.FEDERAL,
    )

    private val vpa = Regex("\\S+@\\S+")

    /** Returns the bank, [Bank.OTHER] for unmodelled financial senders, or null when not financial. */
    fun detect(sender: String, body: String): Bank? {
        bankFromSenderName(sender)?.let { return it }
        if (!isFinancialMessage(sender, body)) return null
        val legacy = legacyHints.firstOrNull { it.first.containsMatchIn(body) }?.second
        val text = body.replace(vpa, " ")
        // A legacy hint found only inside a UPI handle ("to VPA swiggy@icici ... -Punjab National Bank")
        // gives way to a newer bank named in the text; otherwise the result is what it always was.
        if (legacy != null && legacyHints.any { it.first.containsMatchIn(text) }) return legacy
        newerHints.firstOrNull { it.first.containsMatchIn(text) }?.let { return it.second }
        return legacy ?: Bank.OTHER
    }

    /**
     * The bank written into fingerprints. For [BANKS_FORMERLY_OTHER] it is the bank older versions detected
     * for the same message (a legacy body hint, else OTHER), so messages imported back then still de-duplicate.
     */
    fun fingerprintBank(bank: Bank, body: String): Bank {
        if (bank !in BANKS_FORMERLY_OTHER) return bank
        return legacyHints.firstOrNull { it.first.containsMatchIn(body) }?.second ?: Bank.OTHER
    }
}
