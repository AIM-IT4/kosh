package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A credit-card statement read from an e-mail body. */
data class ParsedEmailBill(
    val bank: Bank,
    val cardLast4: String?,
    val totalDue: Paise,
    val minimumDue: Paise?,
    val dueDate: LocalDate?,
    val statementDate: LocalDate?,
    val emailId: String,
    /** Stable key for de-duplicating the same bill seen via e-mail and SMS: bank, card, due date and amount. */
    val fingerprint: String,
    val hasPdfStatement: Boolean = false,
    /** The statement shows a credit balance ("Cr"), so nothing is payable. */
    val isCreditBalance: Boolean = false,
)

/** A credit-card payment acknowledgement ("we have received your payment of Rs 5,000"). */
data class PaymentReceived(
    val bank: Bank,
    val cardLast4: String?,
    val amount: Paise,
    val date: LocalDate?,
    val emailId: String,
)

/** Whether an e-mail says the full statement is attached as a (usually password-protected) PDF. */
data class PdfHint(
    val hasPdfStatement: Boolean,
    val passwordProtected: Boolean,
    /** The sentence describing the password format, if the e-mail gives one. */
    val passwordHint: String?,
) {
    companion object {
        private val attachedRegex = Regex(
            "(?i)(statement|e-?statement)[^.\\n]{0,60}\\battach|\\battach(ed|ment)[^.\\n]{0,60}(statement|pdf)|please\\s+find\\s+attached|\\.pdf\\b",
        )
        private val passwordRegex = Regex("(?i)password[- ]?protected|password\\s+to\\s+open|open\\s+the\\s+(attachment|statement|pdf)[^.\\n]{0,40}password|\\bpassword\\b[^.\\n]{0,80}(name|date of birth|dob|ddmm|card)")

        fun detect(email: EmailMessage): PdfHint {
            val text = email.subject + "\n" + email.bodyText
            val pdfAttachment = email.attachmentNames.any { it.lowercase().endsWith(".pdf") }
            val passwordProtected = passwordRegex.containsMatchIn(text)
            val hint = text.split(Regex("(?<=[.!?])\\s+|\\n"))
                .firstOrNull { it.contains("password", ignoreCase = true) && it.length in 10..300 }
                ?.trim()
            return PdfHint(
                hasPdfStatement = pdfAttachment || attachedRegex.containsMatchIn(text),
                passwordProtected = passwordProtected,
                passwordHint = hint,
            )
        }
    }
}

/**
 * Reads credit-card statement e-mails. Bank-specific label variants are tried first (ICICI, HDFC,
 * Kotak, DBS, SBI Card, Axis), then generic labels, so unknown issuers still parse.
 */
object BillEmailParser {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    private data class Profile(
        val total: List<String> = emptyList(),
        val minimum: List<String> = emptyList(),
        val due: List<String> = emptyList(),
    )

    private val profiles: Map<Bank, Profile> = mapOf(
        Bank.ICICI to Profile(total = listOf("total\\s+amount\\s+due"), due = listOf("due\\s+date")),
        Bank.HDFC to Profile(total = listOf("total\\s+dues", "total\\s+amount\\s+due")),
        Bank.KOTAK to Profile(
            total = listOf("total\\s+amount\\s+due\\s*\\(tad\\)", "\\btad\\b"),
            minimum = listOf("minimum\\s+amount\\s+due\\s*\\(mad\\)", "\\bmad\\b"),
        ),
        Bank.DBS to Profile(total = listOf("total\\s+outstanding", "total\\s+amount\\s+due")),
        Bank.SBI to Profile(total = listOf("total\\s+amount\\s+due")),
        Bank.AXIS to Profile(total = listOf("total\\s+payment\\s+due", "total\\s+amount\\s+due")),
    )

    private val totalLabels = listOf(
        "total\\s+amount\\s+due", "total\\s+amt\\.?\\s+due", "total\\s+dues", "total\\s+payment\\s+due",
        "total\\s+due", "total\\s+outstanding", "outstanding\\s+amount", "amount\\s+due", "amount\\s+payable",
    )
    private val minimumLabels = listOf(
        "minimum\\s+amount\\s+due", "min(?:imum)?\\.?\\s+amt\\.?\\s+due", "min\\.?\\s+amount\\s+due",
        "minimum\\s+amount\\s+payable", "minimum\\s+payment(?:\\s+due)?", "minimum\\s+due", "min\\.?\\s+due",
    )
    private val dueLabels = listOf(
        "payment\\s+due\\s+date", "due\\s+date", "payment\\s+due\\s+by", "payment\\s+due\\s+on", "pay\\s+by",
        "payable\\s+by", "due\\s+on", "on\\s+or\\s+before",
    )
    private val statementLabels = listOf(
        "statement\\s+date", "statement\\s+generation\\s+date", "statement\\s+generated\\s+on", "bill\\s+date",
        "billing\\s+date",
    )
    private val periodLabels = listOf("statement\\s+period", "billing\\s+period", "billing\\s+cycle")
    private val otherLabels = listOf(
        "credit\\s+limit", "available\\s+(?:credit\\s+|cash\\s+)?limit", "cash\\s+limit", "previous\\s+balance",
        "opening\\s+balance", "reward\\s+points", "last\\s+payment", "purchases", "payments?\\s+received",
        "finance\\s+charges", "card\\s+number",
    )

    private fun rx(p: String) = Regex("(?i)$p")
    private val stopRegexes: List<Regex> by lazy {
        (totalLabels + minimumLabels + dueLabels + statementLabels + periodLabels + otherLabels +
            profiles.values.flatMap { it.total + it.minimum + it.due }).distinct().map(::rx)
    }

    private val currencyAmount = Regex("(?i)(?:₹|\\brs\\.?|\\binr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val bareAmount = Regex("(?<![0-9A-Za-z*])([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9A-Za-z])")
    private val creditSuffix = Regex("(?i)^\\s*(cr\\b|credit\\b)")

    private val last4Patterns = listOf(
        Regex("(?i)\\b\\d{4,6}[xX*]{4,}(\\d{4})\\b"),
        Regex("(?i)(?:ending|ends)\\s*(?:with|in)?\\s*(?:no\\.?|number)?\\s*[:\\-]?\\s*(?:[xX*]+\\s*)?(\\d{4})\\b"),
        Regex("(?i)\\b(?:[xX*]{2,}[\\s-]?){1,3}(\\d{4})\\b"),
        Regex("(?i)card\\s*(?:no\\.?|number)\\s*[:\\-]?\\s*(?:[xX*\\d]+[\\s-]?){0,3}?(\\d{4})\\b"),
    )

    private val ackPatterns = listOf(
        "(?:payment|amount)\\s+of\\s+(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s+(?:has\\s+been\\s+|was\\s+|is\\s+)?(?:successfully\\s+)?(?:received|credited|processed|posted)",
        "(?:received|credited)\\s+(?:a\\s+|your\\s+)?payment\\s+of\\s+(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        "thank\\s+you\\s+for\\s+(?:your\\s+|the\\s+|making\\s+(?:a|the)\\s+)?payment\\s+of\\s+(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        "(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s+(?:has\\s+been\\s+|was\\s+)?(?:received|credited)\\s+(?:towards|to|against|in)\\s+(?:your\\s+)?(?:[a-z]+\\s+){0,3}(?:credit\\s+)?card",
    ).map(::rx)

    /** The statement in [email], or null for anything that is not a bill (offers, alerts, payment receipts). */
    fun parse(email: EmailMessage): ParsedEmailBill? {
        val text = textFor(email)
        if (text.isBlank()) return null
        val reference = emailDate(email)
        val bank = detectBank(email, text)
        val profile = profiles[bank] ?: Profile()

        val total = findAmount(text, (profile.total + totalLabels).distinct(), skipMinimum = true) ?: return null
        val minimum = findAmount(text, (profile.minimum + minimumLabels).distinct(), skipMinimum = false)
        val due = findDate(text, (profile.due + dueLabels).distinct(), reference, last = false)
        val statement = findDate(text, statementLabels, reference, last = false)
            ?: findDate(text, periodLabels, reference, last = true)

        val looksLikeStatement = Regex("(?i)statement|bill").containsMatchIn(email.subject + " " + text)
        if (due == null && minimum == null && !looksLikeStatement) return null
        if (due == null && minimum == null && ackPatterns.any { it.containsMatchIn(text) }) return null

        val last4 = findLast4(text) ?: findLast4(email.subject)
        val totalPaise = if (total.second) 0L else total.first
        return ParsedEmailBill(
            bank = bank,
            cardLast4 = last4,
            totalDue = totalPaise,
            minimumDue = minimum?.let { if (it.second) 0L else it.first },
            dueDate = due,
            statementDate = statement,
            emailId = email.id,
            fingerprint = fingerprint(bank, last4, due, totalPaise),
            hasPdfStatement = PdfHint.detect(email).hasPdfStatement,
            isCreditBalance = total.second,
        )
    }

    /** A credit-card payment acknowledgement, or null. */
    fun parsePaymentReceived(email: EmailMessage): PaymentReceived? {
        val text = textFor(email)
        if (!Regex("(?i)\\bcard\\b").containsMatchIn(text + " " + email.subject)) return null
        for (pattern in ackPatterns) {
            val m = pattern.find(text) ?: continue
            val amount = Money.parse(m.groupValues[1]) ?: continue
            val after = text.substring(m.range.first, minOf(text.length, m.range.last + 120))
            val date = TextDates.findAll(after, emailDate(email)).firstOrNull()?.date ?: emailDate(email)
            return PaymentReceived(
                bank = detectBank(email, text),
                cardLast4 = findLast4(text) ?: findLast4(email.subject),
                amount = amount,
                date = date,
                emailId = email.id,
            )
        }
        return null
    }

    /** Body text plus label/value pairs recovered from header-row tables, which take precedence. */
    private fun textFor(email: EmailMessage): String {
        val pairs = email.htmlBody?.let { HtmlText.tablePairs(it) }.orEmpty()
            .joinToString("\n") { (k, v) -> "$k : $v" }
        return (pairs + "\n" + email.bodyText).replace(' ', ' ').trim()
    }

    private fun emailDate(email: EmailMessage): LocalDate? =
        email.dateMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(IST).toLocalDate() }

    private fun detectBank(email: EmailMessage, text: String): Bank =
        IssuerDirectory.bankForSender(email.from)
            ?: IssuerDirectory.bankInText(email.subject)
            ?: IssuerDirectory.bankInText(text)
            ?: Bank.OTHER

    /** Searches the text after each label (up to the next known label) and returns (paise, isCredit). */
    private fun findAmount(text: String, labels: List<String>, skipMinimum: Boolean): Pair<Paise, Boolean>? {
        for (label in labels) {
            for (m in rx(label).findAll(text)) {
                if (skipMinimum) {
                    val before = text.substring(maxOf(0, m.range.first - 15), m.range.first).lowercase()
                    if ("min" in before) continue
                }
                val window = windowAfter(text, m.range.last + 1, 90)
                amountIn(window)?.let { return it }
            }
        }
        return null
    }

    private fun amountIn(window: String): Pair<Paise, Boolean>? {
        currencyAmount.find(window)?.let { m ->
            val v = Money.parse(m.groupValues[1]) ?: return@let
            val credit = creditSuffix.containsMatchIn(window.substring(m.range.last + 1))
            return v to credit
        }
        val dateSpans = TextDates.findAll(window, LocalDate.of(2000, 1, 1)).map { it.range }
        val cardSpans = last4Patterns.flatMap { p -> p.findAll(window).map { it.range }.toList() }
        for (m in bareAmount.findAll(window)) {
            if (dateSpans.any { m.range.first in it } || cardSpans.any { m.range.first in it }) continue
            val token = m.groupValues[1]
            val before = window.substring(maxOf(0, m.range.first - 3), m.range.first)
            if (!token.contains(',') && !token.contains('.') && !before.contains(':') && !before.contains('|')) continue
            val v = Money.parse(token.trimEnd('.', ',')) ?: continue
            val credit = creditSuffix.containsMatchIn(window.substring(m.range.last + 1))
            return v to credit
        }
        return null
    }

    private fun findDate(text: String, labels: List<String>, reference: LocalDate?, last: Boolean): LocalDate? {
        for (label in labels) {
            for (m in rx(label).findAll(text)) {
                val window = windowAfter(text, m.range.last + 1, 70)
                val dates = TextDates.findAll(window, reference)
                val pick = if (last) dates.lastOrNull() else dates.firstOrNull()
                if (pick != null) return pick.date
            }
        }
        return null
    }

    private fun windowAfter(text: String, start: Int, size: Int): String {
        var end = minOf(text.length, start + size)
        for (stop in stopRegexes) {
            val s = stop.find(text, start) ?: continue
            if (s.range.first in (start + 1) until end) end = s.range.first
        }
        return text.substring(start, end)
    }

    private fun findLast4(text: String): String? =
        last4Patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }

    private fun fingerprint(bank: Bank, last4: String?, due: LocalDate?, total: Paise): String {
        val key = "${bank.name}|${last4 ?: "-"}|${due ?: "-"}|$total"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
