package com.amitjha.kosh.sms.statement

import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import kotlin.math.abs

/**
 * Reads transactions from a bank statement downloaded as CSV (or semicolon, tab or pipe separated text).
 *
 * Indian bank exports wrap the transaction table in a preamble (holder, address, period) and a footer
 * (summary, legends), so the table is found by its header row, and any later line without a date and an
 * amount is skipped and counted rather than failing the import.
 */
object StatementCsv {

    /**
     * Zero-based cell positions. Amounts come from [debit]/[credit] when either is set, otherwise from [amount],
     * whose direction is read from [drCr], then a Dr/Cr tag on the amount itself, then its sign (negative = debit).
     */
    data class Columns(
        val date: Int,
        val description: Int,
        val debit: Int? = null,
        val credit: Int? = null,
        val amount: Int? = null,
        val drCr: Int? = null,
        val balance: Int? = null,
        val reference: Int? = null,
    )

    /**
     * One transaction. [line] is 1-based in the input; [amount] is always positive, and [type] is EXPENSE for a
     * debit and INCOME for a credit. [balance] is negative when the statement tags it Dr (overdrawn).
     */
    data class Row(
        val line: Int,
        val date: LocalDate,
        val description: String,
        val amount: Paise,
        val type: TxnType,
        val balance: Paise?,
        val reference: String?,
    )

    /**
     * [headerLine] is 1-based, or 0 when no header was found. [skippedLines] counts the non-blank lines after the
     * header (all of them when there is none) that did not give a row.
     */
    data class Parsed(
        val header: List<String>,
        val headerLine: Int,
        val columns: Columns?,
        val rows: List<Row>,
        val skippedLines: Int,
    )

    /** Parses [text]; finds the header and maps columns automatically unless [columns] is given. */
    fun parse(text: String, columns: Columns? = null): Parsed {
        val lines = text.removePrefix(BOM).split(LINE_BREAK)
        val records = records(lines, detectDelimiter(lines))
        var headerAt = -1
        var detected: Columns? = null
        for ((i, record) in records.withIndex()) {
            detected = mapHeader(record.cells) ?: continue
            headerAt = i
            break
        }
        val mapping = columns ?: detected
        val rows = ArrayList<Row>()
        var skipped = 0
        for (record in records.subList(headerAt + 1, records.size)) {
            if (record.isBlank) continue
            val row = mapping?.let { row(record, it) }
            if (row == null) skipped++ else rows += row
        }
        val header = records.getOrNull(headerAt)
        return Parsed(
            header = header?.cells?.map { it.trim() }.orEmpty(),
            headerLine = header?.line ?: 0,
            columns = mapping,
            rows = rows,
            skippedLines = skipped,
        )
    }

    /** Splits one CSV line (quotes, escaped quotes, the given delimiter). */
    fun splitLine(line: String, delimiter: Char): List<String> = scan(line, delimiter).cells

    /** Comma, tab, semicolon or pipe: the one that splits a line into a known header, else the most consistent. */
    fun detectDelimiter(lines: List<String>): Char {
        val sample = lines.map { it.removePrefix(BOM) }.filter { it.isNotBlank() }
        for (line in sample) {
            val match = DELIMITERS.map { it to splitLine(line, it) }
                .filter { (_, cells) -> mapHeader(cells) != null }
                .maxByOrNull { (_, cells) -> cells.size }
            if (match != null) return match.first
        }
        // No header: pick the delimiter that cuts the most lines into the same number (more than one) of cells.
        var best = DELIMITERS.first()
        var bestScore = 0
        for (d in DELIMITERS) {
            val widths = sample.take(DELIMITER_SAMPLE).map { splitLine(it, d).size }.filter { it > 1 }
            val score = widths.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (score > bestScore) {
                best = d
                bestScore = score
            }
        }
        return best
    }

    /**
     * Day-first dates as Indian banks write them: 01/10/2026, 1/10/26, 01-10-2026, 01.10.2026, 01-Oct-2026,
     * 1 Oct 26, plus ISO 2026-10-01 and Oct 1, 2026, each optionally followed by a time. A date that is invalid
     * read day-first (10/25/2026) is rejected rather than guessed month-first. Two-digit years are 20xx.
     */
    fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().lowercase().replace(TIME, "").trim()
        YMD.matchEntire(s)?.let {
            val (y, m, d) = it.destructured
            return date(y, m.toInt(), d)
        }
        DMY.matchEntire(s)?.let {
            val (d, m, y) = it.destructured
            return date(y, m.toInt(), d)
        }
        D_MON_Y.matchEntire(s)?.let {
            val (d, m, y) = it.destructured
            return date(y, month(m), d)
        }
        MON_D_Y.matchEntire(s)?.let {
            val (m, d, y) = it.destructured
            return date(y, month(m), d)
        }
        return null
    }

    /**
     * The absolute amount in paise: "1,23,456.78", "Rs. 500", "INR 500", "₹500", "500.00 Cr", "Dr 500", "-500",
     * "(500.00)". Blank, "-" and zero mean no amount and give null.
     */
    fun parseAmount(raw: String): Paise? = signed(raw)?.let { abs(it.paise) }?.takeIf { it > 0 }

    // ---- Lines and cells ----

    private const val BOM = "﻿"
    private const val DELIMITER_SAMPLE = 200

    /** A quoted cell may span lines; give up joining after this many and read the line as it is. */
    private const val MAX_JOINED_LINES = 5

    private val LINE_BREAK = Regex("\r\n|\r|\n")
    private val DELIMITERS = listOf(',', '\t', ';', '|')

    private class Record(val line: Int, val cells: List<String>) {
        val isBlank: Boolean get() = cells.all { it.isBlank() }

        /** The trimmed cell at [index]; null when absent, blank or a lone "-". */
        fun cell(index: Int?): String? =
            index?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
    }

    private fun records(lines: List<String>, delimiter: Char): List<Record> {
        val records = ArrayList<Record>(lines.size)
        var i = 0
        while (i < lines.size) {
            var text = lines[i]
            var last = i
            if (scan(text, delimiter).open) {
                var joined = text
                for (j in i + 1..minOf(i + MAX_JOINED_LINES, lines.lastIndex)) {
                    joined += "\n" + lines[j]
                    if (!scan(joined, delimiter).open) {
                        text = joined
                        last = j
                        break
                    }
                }
            }
            records += Record(i + 1, splitLine(text, delimiter))
            i = last + 1
        }
        return records
    }

    private class Scan(val cells: List<String>, val open: Boolean)

    /** Splits [line] into cells, noting whether it ends inside a quoted cell (one holding a line break). */
    private fun scan(line: String, delimiter: Char): Scan {
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> cell.append(c)
                c == delimiter -> {
                    cells += cell.toString()
                    cell.setLength(0)
                    quoted = false
                }
                // A quote opens a quoted cell only at its start (padding before it is dropped); elsewhere it's text.
                c == '"' && !quoted && cell.isBlank() -> {
                    cell.setLength(0)
                    inQuotes = true
                    quoted = true
                }
                quoted && c.isWhitespace() -> Unit // padding after the closing quote
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString()
        return Scan(cells, inQuotes)
    }

    // ---- Header names ----

    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val CURRENCY_WORD = Regex("""\b(?:in )?(?:inr|rs|rupees)\b""")
    private val SPACES = Regex("""[\s\p{Z}]+""")

    /** "Withdrawal Amount (INR )" -> "withdrawal amount", "Chq./Ref.No." -> "chq ref no". */
    private fun normalize(name: String): String =
        name.lowercase().replace(NON_ALNUM, " ").replace(CURRENCY_WORD, " ").replace(SPACES, " ").trim()

    private fun names(vararg raw: String): List<String> = raw.map { normalize(it) }

    // Dates in order of preference: the transaction date wins over the value date.
    private val DATE_NAMES = names(
        "txn date", "transaction date", "tran date", "trans date", "txn dt", "tran dt", "date", "posting date",
        "value date", "value dt", "value dat",
    )
    private val DESCRIPTION_NAMES = names(
        "narration", "description", "particulars", "remarks", "transaction remarks", "details",
        "transaction details", "transaction description", "transaction particulars",
    )
    private val DEBIT_NAMES = names(
        "debit", "withdrawal", "withdrawal amt", "withdrawal amount", "withdrawals", "dr", "debit amount",
        "debit amt", "amount debited", "debits",
    )
    private val CREDIT_NAMES = names(
        "credit", "deposit", "deposit amt", "deposit amount", "deposits", "cr", "credit amount", "credit amt",
        "amount credited", "credits",
    )
    private val AMOUNT_NAMES = names("amount", "txn amount", "transaction amount", "amt", "txn amt")
    private val DR_CR_NAMES = names("dr/cr", "cr/dr", "type", "debit/credit", "credit/debit")
    private val BALANCE_NAMES = names(
        "balance", "closing balance", "available balance", "running balance", "bal", "balance amt", "balance amount",
    )
    private val REFERENCE_NAMES = names(
        "chq/ref no", "ref no", "reference", "reference no", "cheque no", "chq no", "chqno", "cheque number",
        "ref no./cheque no.", "chq/ref number", "utr", "utr no",
    )

    /** Columns for a header row: needs a date, a description and at least one amount column. */
    private fun mapHeader(cells: List<String>): Columns? {
        if (cells.size < 3) return null
        val names = cells.map { normalize(it) }
        val taken = HashSet<Int>()
        fun find(synonyms: List<String>): Int? {
            for (name in synonyms) {
                val i = names.indices.firstOrNull { it !in taken && names[it] == name } ?: continue
                taken += i
                return i
            }
            return null
        }
        val date = find(DATE_NAMES) ?: return null
        val description = find(DESCRIPTION_NAMES) ?: return null
        val debit = find(DEBIT_NAMES)
        val credit = find(CREDIT_NAMES)
        val amount = find(AMOUNT_NAMES)
        if (debit == null && credit == null && amount == null) return null
        return Columns(
            date = date,
            description = description,
            debit = debit,
            credit = credit,
            amount = amount,
            drCr = find(DR_CR_NAMES),
            balance = find(BALANCE_NAMES),
            reference = find(REFERENCE_NAMES),
        )
    }

    // ---- Rows ----

    /** Summary lines that can carry a date and an amount but are not transactions. */
    private val SUMMARY = Regex(
        """(?:opening|closing) bal(?:ance)?\b.*|(?:balance )?(?:b/f|c/f|brought forward|carried forward)\b.*""" +
            """|(?:grand |sub ?)?totals?(?: (?:debits?|credits?|withdrawals?|deposits?|amount))?\s*:?[\d\s,.]*""",
    )

    private fun row(record: Record, c: Columns): Row? {
        val date = record.cell(c.date)?.let { parseDate(it) } ?: return null
        val description = record.cell(c.description)?.replace(SPACES, " ").orEmpty()
        if (SUMMARY.matches(description.lowercase())) return null
        val (amount, type) = amountOf(record, c) ?: return null
        val balance = record.cell(c.balance)?.let { signed(it) }
            ?.let { if (it.tag == TxnType.EXPENSE) -abs(it.paise) else it.paise }
        return Row(record.line, date, description, amount, type, balance, record.cell(c.reference))
    }

    private fun amountOf(record: Record, c: Columns): Pair<Paise, TxnType>? {
        if (c.debit != null || c.credit != null) {
            val debit = record.cell(c.debit)?.let { parseAmount(it) }
            val credit = record.cell(c.credit)?.let { parseAmount(it) }
            return when {
                debit != null && credit == null -> debit to TxnType.EXPENSE
                credit != null && debit == null -> credit to TxnType.INCOME
                else -> null // neither filled in, or both and there's no telling which way it went
            }
        }
        val value = record.cell(c.amount)?.let { signed(it) }?.takeIf { it.paise != 0L } ?: return null
        val type = direction(record.cell(c.drCr)) ?: value.tag
            ?: if (value.paise < 0) TxnType.EXPENSE else TxnType.INCOME
        return abs(value.paise) to type
    }

    /** A Dr/Cr column value: "DR", "Dr.", "D", "Debit" or "CR", "Cr.", "C", "Credit". */
    private fun direction(raw: String?): TxnType? {
        val v = raw?.lowercase() ?: return null
        return when {
            v == "d" || v.startsWith("dr") || v.startsWith("debit") || v.startsWith("withdraw") -> TxnType.EXPENSE
            v == "c" || v.startsWith("cr") || v.startsWith("credit") || v.startsWith("deposit") -> TxnType.INCOME
            else -> null
        }
    }

    // ---- Amounts ----

    private val AMOUNT_NOISE = Regex("""[\s\p{Z},]|rs\.?|inr|₹""")
    private val LEADING_TAG = Regex("""^(dr|cr)\.?""")
    private val TRAILING_TAG = Regex("""\(?(dr|cr)\.?\)?$""")

    /** At most 13 rupee digits, so the paise still fit in a Long. */
    private val NUMBER = Regex("""\d{1,13}(?:\.\d*)?|\.\d+""")

    /** An amount keeping its sign, plus the direction when it's tagged Dr or Cr. */
    private class Signed(val paise: Paise, val tag: TxnType?)

    private fun signed(raw: String): Signed? {
        var s = raw.lowercase().replace(AMOUNT_NOISE, "")
        val tag = (LEADING_TAG.find(s) ?: TRAILING_TAG.find(s))?.also { s = s.removeRange(it.range) }
        var negative = false
        when {
            s.startsWith("-") -> {
                negative = true
                s = s.drop(1)
            }
            s.startsWith("+") -> s = s.drop(1)
            s.endsWith("-") -> {
                negative = true
                s = s.dropLast(1)
            }
        }
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        if (!NUMBER.matches(s)) return null
        val paise = BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        val direction = when (tag?.groupValues?.get(1)) {
            "dr" -> TxnType.EXPENSE
            "cr" -> TxnType.INCOME
            else -> null
        }
        return Signed(if (negative) -paise else paise, direction)
    }

    // ---- Dates ----

    private val TIME = Regex(
        """(?:t|\s+|\s*,\s*)\d{1,2}:\d{2}(?::\d{2}(?:\.\d+)?)?\s*(?:am|pm)?\s*(?:z|[+-]\d{2}:?\d{2})?$""",
    )
    private val YMD = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val DMY = Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})""")
    private val D_MON_Y = Regex("""(\d{1,2})(?:st|nd|rd|th)?[-/. ]*([a-z]{3,9})[-/., ]*(\d{4}|\d{2})""")
    private val MON_D_Y = Regex("""([a-z]{3,9})[-/. ]*(\d{1,2})(?:st|nd|rd|th)?[-/., ]*(\d{4})""")

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    /** "oct", "OCT", "October", "Sept" -> 10/10/10/9. */
    private fun month(name: String): Int? = MONTHS.indexOfFirst { it.startsWith(name) }.takeIf { it >= 0 }?.plus(1)

    private fun date(year: String, month: Int?, day: String): LocalDate? {
        if (month == null) return null
        val y = if (year.length == 2) 2000 + year.toInt() else year.toInt()
        return try {
            LocalDate.of(y, month, day.toInt())
        } catch (e: DateTimeException) {
            null
        }
    }
}
