package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Money

/** Credit card statement and bill-due messages. */
internal object BillParser {

    private val billSignal = Regex(
        "statement|total (?:amt|amount)\\.? due|total due|min(?:imum)?\\.? (?:amt|amount)?\\.? ?due|" +
            "due date|due by|due on|is due|payment due",
    )
    private val spendSignal = Regex("\\bspent\\b|\\bdebited\\b|\\bused\\b|\\bwithdrawn\\b|\\bcredited\\b|\\breceived\\b")

    private val totalRe = Regex(
        "(?i)total\\s*(?:amt|amount)?\\.?\\s*due\\s*(?:is|of|:|-)?\\s*(?:$CUR)?\\s*:?\\s*$NUM|" +
            "total\\s*(?:outstanding|bill)\\s*(?:amount)?\\s*(?:is|of|:|-)?\\s*(?:$CUR)?\\s*$NUM",
    )
    private val minRe = Regex("(?i)min(?:imum)?\\.?\\s*(?:amt|amount)?\\.?\\s*due\\s*(?:is|of|:|-)?\\s*(?:$CUR)?\\s*:?\\s*$NUM")
    private val firstMoney = Regex("(?i)$CUR\\s*$NUM")

    private val dueRe = Regex("(?i)(?:payment\\s+)?due\\s*(?:date)?\\s*(?:is|by|on|:|-)?\\s*(?:by|on)?\\s*:?\\s*")
    private val statementRe = Regex("(?i)statement\\s*(?:date|dated|generated on|for period ending|as on)\\s*:?\\s*")

    fun parse(bank: Bank, body: String, lower: String): ParsedCardBill? {
        if (!lower.contains("card")) return null
        if (!billSignal.containsMatchIn(lower)) return null
        // a spend that merely mentions a due date isn't a bill
        if (spendSignal.containsMatchIn(lower) && !Regex("statement|total (?:amt|amount)\\.? due|total due").containsMatchIn(lower)) {
            return null
        }
        val total = totalRe.find(body)?.let { m -> (1..m.groupValues.lastIndex).firstNotNullOfOrNull { Money.parse(m.groupValues[it]) } }
            ?: firstMoney.findAll(body).firstOrNull { m ->
                val before = body.substring(maxOf(0, m.range.first - 20), m.range.first).lowercase()
                !before.contains("min")
            }?.let { Money.parse(it.groupValues[1]) }
            ?: return null
        val min = minRe.find(body)?.let { Money.parse(it.groupValues[1]) }

        val dates = Dates.findAll(body)
        val dueDate = dueRe.findAll(body).firstNotNullOfOrNull { m ->
            Dates.first(body.substring(m.range.last + 1).take(30))
        }?.date ?: dates.lastOrNull()?.date
        val statementDate = statementRe.find(body)?.let { m -> Dates.first(body.substring(m.range.last + 1).take(30)) }?.date
            ?.takeIf { it != dueDate }

        val last4 = Extract.last4(body)
        val dueDay = dueDate?.toEpochDay()
        val stDay = statementDate?.toEpochDay()
        return ParsedCardBill(
            bank = bank,
            cardLast4 = last4,
            totalDue = total,
            minimumDue = min,
            dueDateEpochDay = dueDay,
            statementDateEpochDay = stDay,
            fingerprint = Fingerprint.bill(bank, last4, total, dueDay, stDay),
        )
    }
}
