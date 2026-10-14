package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank

/**
 * Bank-specific knowledge: merchant phrasing differs per bank ("; Zomato credited" at ICICI,
 * "To SWIGGY On" at HDFC, "for UPI txn to" at DBS ...). Each parser contributes ordered merchant
 * patterns tried before the generic ones; group 1 is the merchant.
 */
internal interface BankParser {
    val bank: Bank
    val merchantPatterns: List<Regex>
}

private fun rx(vararg p: String) = p.map { Regex("(?i)$it") }

internal object HdfcParser : BankParser {
    override val bank = Bank.HDFC
    override val merchantPatterns = rx(
        "\\bto\\s+vpa\\s+(\\S+?)\\s*\\(",
        "\\bto\\s+vpa\\s+(\\S+)",
        // new multi-line UPI format: "Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 05/01/25"
        "\\bto\\s+(.+?)\\s+on\\s+\\d",
        "\\bat\\s+(.+?)\\s+on\\s+\\d",
        "neft\\s*cr-[a-z0-9]+-(.+?)-",
        "by\\s+a/c\\s+linked\\s+to\\s+vpa\\s+(\\S+?)\\s*\\(",
        "by\\s+a/c\\s+linked\\s+to\\s+vpa\\s+(\\S+)",
        "towards\\s+(.+?)(?:\\.\\s|\\.?\\s+avl|$)",
    )
}

internal object IciciParser : BankParser {
    override val bank = Bank.ICICI
    override val merchantPatterns = rx(
        // "debited for Rs 450.00 on 05-Jan-25; Zomato credited."
        ";\\s*(.+?)\\s+credited",
        // "spent using ICICI Bank Card XX9012 on 05-Jan-25 on AMAZON. Avl Limit"
        "\\bon\\s+\\d{1,2}-[a-z]{3}-\\d{2,4}\\s+on\\s+(.+?)\\.(?:\\s|$)",
        "refund of .*? from\\s+(.+?)\\s+has been",
        "\\bfrom\\s+(.+?)\\.\\s*upi",
        "info:?\\s*(?:neft|imps|rtgs)-[a-z0-9]+-(.+?)\\.(?:\\s|$)",
        "\\bat\\s+(.+?)\\s+on\\s+\\d",
    )
}

internal object KotakParser : BankParser {
    override val bank = Bank.KOTAK
    override val merchantPatterns = rx(
        "\\bto\\s+(\\S+@\\S+?)\\s+on\\b",
        "\\bfrom\\s+(\\S+@\\S+?)\\s+on\\b",
        "by\\s+neft\\s+from\\s+(.+?)\\.(?:\\s|$)",
        "\\bat\\s+(.+?)\\s+on\\s+\\d",
    )
}

internal object DbsParser : BankParser {
    override val bank = Bank.DBS
    override val merchantPatterns = rx(
        "for\\s+upi\\s+txn\\s+to\\s+(.+?)\\.(?:\\s|$)",
        "towards\\s+upi/([^/]+)/",
        "info:?\\s*(?:neft|imps|rtgs)/(.+?)\\.(?:\\s|$)",
        "\\bat\\s+(.+?)\\s+on\\s+\\d",
    )
}

internal object SbiParser : BankParser {
    override val bank = Bank.SBI
    override val merchantPatterns = rx(
        "trf\\s+to\\s+(.+?)\\s+ref",
        "transfer\\s+from\\s+(.+?)\\s+ref",
        "\\bat\\s+(.+?)\\s+on\\s+\\d",
    )
}

internal object AxisParser : BankParser {
    override val bank = Bank.AXIS
    override val merchantPatterns = rx(
        "upi/p2[ma]/\\d+/([^\\s/]+)",
        "\\bist\\s+(.+?)\\s+avl",
        "\\bat\\s+(.+?)\\s+on\\s+\\d",
    )
}

/** Fallback for any bank: phrasing common across Indian bank SMS. */
internal object GenericParser : BankParser {
    override val bank = Bank.OTHER
    override val merchantPatterns = rx(
        "\\bat\\s+(.+?)(?=\\s+on\\s|\\.\\s|\\.$|\\s+avl|\\s+avbl|\\s+ref|$)",
        "\\bto\\s+vpa\\s+(\\S+)",
        "\\bto\\s+([a-z0-9.\\-_]+@[a-z]+)",
        "\\bfrom\\s+([a-z0-9.\\-_]+@[a-z]+)",
        "linked\\s+to\\s+vpa\\s+([a-z0-9.\\-_]+@[a-z]+)",
        "\\btrf\\s+to\\s+(.+?)(?=\\s+ref|\\.|$)",
        "\\btowards\\s+(.+?)(?=\\.\\s|\\s+avl|$)",
        "\\bfrom\\s+([a-z][a-z .&']{2,40}?)(?=\\.|\\s+on\\s|\\s+upi|\\s+has\\s|\\s+ref|$)",
        "\\bto\\s+([a-z][a-z .&']{2,40}?)(?=\\s+on\\s|\\.|\\s+ref|$)",
        "info:?\\s*(?:upi|neft|imps|rtgs)?[/\\-]?(.+?)(?=\\.\\s|\\s+avl|$)",
    )

    fun forBank(bank: Bank): BankParser = when (bank) {
        Bank.HDFC -> HdfcParser
        Bank.ICICI -> IciciParser
        Bank.KOTAK -> KotakParser
        Bank.DBS -> DbsParser
        Bank.SBI -> SbiParser
        Bank.AXIS -> AxisParser
        Bank.OTHER -> GenericParser
    }
}

private val notAMerchant = Regex("(?i)\\b(a/?c|acct|account|card|bank|your|you|ur|self|customer|mobile|number)\\b")

/** Runs bank patterns, then generic ones, keeping the first plausible merchant. */
internal fun extractMerchant(bank: Bank, body: String): String? {
    val parsers = listOf(GenericParser.forBank(bank)).let { if (bank == Bank.OTHER) it else it + GenericParser }
    for (p in parsers) {
        for (re in p.merchantPatterns) {
            val raw = re.find(body)?.groupValues?.get(1)?.trim() ?: continue
            if (raw.length < 2 || raw.length > 60) continue
            if (!raw.contains('@') && notAMerchant.containsMatchIn(raw)) continue
            if (raw.all { it.isDigit() || it == ' ' }) continue
            val name = MerchantNormalizer.normalize(raw)
            if (name.isNotBlank()) return name
        }
    }
    return null
}
