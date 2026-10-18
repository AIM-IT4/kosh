package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnChannel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal val IST: ZoneId = ZoneId.of("Asia/Kolkata")

/** Number as written in bank SMS: "1,23,456.70", "500", "120.0". */
internal const val NUM = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
/** Currency marker: "Rs.", "Rs", "Rs:" (Union Bank), "INR", "₹". */
internal const val CUR = "(?:rs[.:]?|inr|₹)"

internal object Extract {

    private val moneyRe = Regex("(?i)$CUR\\s*$NUM")
    private val bareAmountRe = Regex("(?i)\\b(?:debited|credited)\\s+(?:by|for|with)\\s+$NUM\\b")

    /** Words that, when right before an amount, mark it as a balance/limit rather than the txn amount. */
    private val nonTxnContext = Regex("(?i)(bal(ance)?|limit|lmt|due|outstanding)\\W*(is|of|:)?\\W*$")

    /** The transaction amount: the first currency amount that is not a balance or limit. */
    fun amount(body: String): Paise? {
        for (m in moneyRe.findAll(body)) {
            val before = body.substring(maxOf(0, m.range.first - 22), m.range.first)
            if (nonTxnContext.containsMatchIn(before)) continue
            if (!plausible(m.groupValues[1], MAX_RUPEE_DIGITS)) continue
            val v = Money.parse(m.groupValues[1]) ?: continue
            if (v > 0) return v
        }
        // Without a currency marker, only a modest number right after "debited by/for" counts, so a
        // reference or account number can't be read as the amount.
        bareAmountRe.find(body)?.let { if (plausible(it.groupValues[1], MAX_BARE_DIGITS)) return Money.parse(it.groupValues[1]) }
        return null
    }

    /** More than 9 rupee digits (₹100 crore) is a reference or account number, not an amount. */
    private const val MAX_RUPEE_DIGITS = 9

    private const val MAX_BARE_DIGITS = 7

    private fun plausible(number: String, maxDigits: Int): Boolean =
        number.substringBefore('.').count { it.isDigit() } <= maxDigits

    private val balanceRes = listOf(
        Regex("(?i)\\b(?:avl|avbl|available|avail)\\.?\\s*bal(?:ance)?\\.?\\s*(?:is|:|-)?\\s*(?:$CUR)?\\s*:?\\s*$NUM"),
        Regex("(?i)\\bbal(?:ance)?\\s*[:\\-]\\s*(?:$CUR)?\\s*$NUM"),
        Regex("(?i)\\bbal(?:ance)?\\s+(?:is\\s+)?$CUR\\s*$NUM"),
    )

    fun balance(body: String): Paise? =
        balanceRes.firstNotNullOfOrNull { it.find(body) }?.let { Money.parse(it.groupValues[1]) }

    /** Looser form for balance-only messages: "Available balance in A/c XX1234 as on .. is Rs.X". */
    private val balanceStatementRe = Regex("(?i)\\bbal(?:ance)?\\b[^.]{0,80}?\\bis\\s*:?\\s*$CUR\\s*$NUM")

    fun balanceStatement(body: String): Paise? =
        balance(body) ?: balanceStatementRe.find(body)?.let { Money.parse(it.groupValues[1]) }

    private val limitRe = Regex(
        "(?i)\\b(?:avl|avbl|available|avail)\\.?\\s*(?:credit\\s*)?(?:limit|lmt)\\.?\\s*(?:is|:|-)?\\s*(?:$CUR)?\\s*:?\\s*$NUM",
    )

    fun creditLimit(body: String): Paise? = limitRe.find(body)?.let { Money.parse(it.groupValues[1]) }

    private val maskedRe = Regex("(?i)(?<![A-Za-z0-9])\\d{0,4}[x*]{1,12}\\s?(\\d{3,4})\\b")
    private val unmaskedRes = listOf(
        Regex("(?i)\\b(?:a/?c|acct)\\s*(?:no\\.?)?\\s*[x*]+\\s?(\\d{3,4})\\b"),
        // "A/c ...1234" (Bank of Baroda)
        Regex("(?i)\\b(?:a/?c|acct|account)(?:\\s*no\\.?)?\\s*\\.{2,}\\s?(\\d{3,4})\\b"),
        Regex("(?i)\\bending(?:\\s+(?:with|in))?\\s*(?:no\\.?)?\\s*:?\\s*(\\d{4})\\b"),
        Regex("(?i)\\bcard(?:\\s+no\\.?)?\\s+(\\d{4})\\b"),
        Regex("(?i)\\b(?:a/?c|acct|account)(?:\\s+no\\.?)?\\s+(\\d{4})\\b"),
    )

    /** Last 3-4 digits of the account or card, from masks like XX1234, *1234, x4321, 4xxx9012. */
    fun last4(body: String): String? {
        maskedRe.find(body)?.let { return it.groupValues[1] }
        return unmaskedRes.firstNotNullOfOrNull { it.find(body) }?.groupValues?.get(1)
    }

    private val refRe = Regex(
        "(?i)\\b(?:upi\\s*ref(?:erence)?(?:\\s*no)?|ref(?:erence)?\\s*no|refno|ref|rrn|utr(?:\\s*no)?|txn\\s*id|upi)" +
            "\\s*[:#.\\-]?\\s*(?:no\\.?\\s*)?([A-Za-z0-9]*[0-9][A-Za-z0-9]{5,})",
    )

    fun reference(body: String): String? = refRe.find(body)?.groupValues?.get(1)

    /**
     * Recurring debits the bank runs on its own: UPI AutoPay, e-mandates/NACH/ECS, standing instructions
     * ("SI executed"), auto-debits and EMIs.
     */
    private val autoDebit = Regex(
        "\\bnach\\b|\\be-?nach\\b|\\bach\\b|\\becs\\b|auto[- ]?debit|auto[- ]?pay|\\bsi\\b|standing instr|\\bemi\\b|mandate|" +
            "recurring (?:payment|debit|mandate)",
    )

    private val atmWords = Regex("\\batm\\b|withdrawn|cash withdrawal")
    private val neftWord = Regex("\\bneft\\b")
    private val impsWord = Regex("\\bimps\\b")
    private val rtgsWord = Regex("\\brtgs\\b")
    private val upiWords = Regex("\\bupi\\b|\\bvpa\\b|[a-z0-9.\\-_]+@[a-z]+")
    private val cardWords = Regex("\\bcard\\b|\\bpos\\b")
    private val netbankingWords = Regex("net ?banking|\\bnetbanking\\b|\\bbil/")
    private val chequeWords = Regex("\\bcheque\\b|\\bchq\\b")

    fun channel(lower: String): TxnChannel = when {
        atmWords.containsMatchIn(lower) -> TxnChannel.ATM
        autoDebit.containsMatchIn(lower) -> TxnChannel.AUTO_DEBIT
        neftWord.containsMatchIn(lower) -> TxnChannel.NEFT
        impsWord.containsMatchIn(lower) -> TxnChannel.IMPS
        rtgsWord.containsMatchIn(lower) -> TxnChannel.RTGS
        upiWords.containsMatchIn(lower) -> TxnChannel.UPI
        cardWords.containsMatchIn(lower) -> TxnChannel.CARD
        netbankingWords.containsMatchIn(lower) -> TxnChannel.NETBANKING
        chequeWords.containsMatchIn(lower) -> TxnChannel.CHEQUE
        else -> TxnChannel.OTHER
    }
}

/** Date parsing for every format Indian banks use in SMS. */
internal object Dates {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
    private const val TIME = "(?:[:,]?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?"

    private val iso = Regex("(?i)\\b(\\d{4})-(\\d{2})-(\\d{2})$TIME")
    /** "2026:10:12 14:20:11" (Bank of Baroda). */
    private val isoColon = Regex("(?i)\\b(\\d{4}):(\\d{2}):(\\d{2})$TIME")
    private val numeric = Regex("(?i)\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})(?![0-9])$TIME")
    private val named = Regex("(?i)\\b(\\d{1,2})[- ]?$MON[-, ]?\\s?(\\d{4}|\\d{2})(?![0-9])$TIME")

    data class Found(val date: LocalDate, val time: LocalTime?, val start: Int)

    /** All dates in [text], in order of appearance. */
    fun findAll(text: String): List<Found> {
        val out = mutableListOf<Found>()
        (iso.findAll(text) + isoColon.findAll(text)).forEach { m ->
            build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m, 4)
                ?.let { out += Found(it.first, it.second, m.range.first) }
        }
        numeric.findAll(text).forEach { m ->
            build(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt(), m, 4)
                ?.let { out += Found(it.first, it.second, m.range.first) }
        }
        named.findAll(text).forEach { m ->
            val month = months.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            build(year(m.groupValues[3]), month, m.groupValues[1].toInt(), m, 4)
                ?.let { out += Found(it.first, it.second, m.range.first) }
        }
        return out.sortedBy { it.start }.distinctBy { it.start }
    }

    fun first(text: String): Found? = findAll(text).firstOrNull()

    private fun year(s: String): Int = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    private fun build(y: Int, mo: Int, d: Int, m: MatchResult, timeGroup: Int): Pair<LocalDate, LocalTime?>? {
        if (mo !in 1..12 || d !in 1..31 || y !in 2000..2099) return null
        val date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: return null
        val h = m.groupValues.getOrNull(timeGroup)?.toIntOrNull()
        val min = m.groupValues.getOrNull(timeGroup + 1)?.toIntOrNull()
        val sec = m.groupValues.getOrNull(timeGroup + 2)?.toIntOrNull() ?: 0
        val time = if (h != null && min != null && h in 0..23 && min in 0..59 && sec in 0..59) {
            LocalTime.of(h, min, sec)
        } else {
            null
        }
        return date to time
    }

    /**
     * Transaction time: the date in the SMS (with its time if given), falling back to the SMS
     * timestamp when the SMS has no plausible date or the date is the same day as the SMS.
     */
    fun transactionTime(found: Found?, smsMillis: Long): Long {
        if (found == null) return smsMillis
        val smsDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(smsMillis), IST)
        val smsDate = smsDateTime.toLocalDate()
        if (found.date.isAfter(smsDate.plusDays(1)) || found.date.isBefore(smsDate.minusDays(400))) return smsMillis
        if (found.time == null && found.date == smsDate) return smsMillis
        val t = found.time ?: LocalTime.NOON
        return found.date.atTime(t).atZone(IST).toInstant().toEpochMilli()
    }
}
