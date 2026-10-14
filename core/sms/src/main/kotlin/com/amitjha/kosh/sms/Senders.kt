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

private fun headerCore(sender: String): String {
    val parts = sender.trim().uppercase().split('-').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> ""
        parts.size == 1 -> parts[0]
        // "AD-HDFCBK" or "VK-HDFCBK-S": the core is the second token
        else -> parts[1]
    }
}

/** True when [sender] looks like a bank / card issuer DLT header rather than a person or a brand. */
fun isLikelyFinancialSender(sender: String): Boolean {
    val core = headerCore(sender)
    if (core.isEmpty() || core.all { it.isDigit() || it == '+' }) return false
    if (core in BANK_HEADERS || core in OTHER_FINANCIAL_HEADERS) return true
    return Regex("BNK|BANK|BK$|CARD|CRD").containsMatchIn(core) &&
        !Regex("OFFER|PROMO|SALE|LOAN").containsMatchIn(core)
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
        BANK_HEADERS[headerCore(sender)]?.let { return it }
        val core = headerCore(sender)
        BANK_HEADERS.entries.firstOrNull { core.startsWith(it.key.take(5)) }?.let { return it.value }
        val financial = isLikelyFinancialSender(sender)
        if (!financial) return null
        bodyHints.firstOrNull { it.first.containsMatchIn(body) }?.let { return it.second }
        return Bank.OTHER
    }
}
