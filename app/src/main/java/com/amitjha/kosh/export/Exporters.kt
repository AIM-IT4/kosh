package com.amitjha.kosh.export

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** File formats Kosh can export to. */
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    CSV("CSV", "csv", "text/csv"),
    EXCEL("Excel (CSV)", "csv", "text/csv"),
    BEANCOUNT("Beancount", "beancount", "text/plain"),
    LEDGER("Ledger / hledger", "ledger", "text/plain"),
}

/** Everything an exporter needs; [transactions] are filtered to [from]..[to] (inclusive dates) by [Exporters]. */
data class ExportData(
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val from: LocalDate,
    val to: LocalDate,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** Plain-Kotlin exporters. Amounts are written in rupees with two decimals and no grouping. */
object Exporters {

    /** UTF-8 byte order mark: makes Excel read ₹ and other non-ASCII text correctly. */
    const val BOM = "﻿"

    fun export(format: ExportFormat, data: ExportData): String = when (format) {
        ExportFormat.CSV -> csv(data, lineEnd = "\n")
        ExportFormat.EXCEL -> csv(data, lineEnd = "\r\n")
        ExportFormat.BEANCOUNT -> beancount(data)
        ExportFormat.LEDGER -> ledger(data)
    }

    fun fileName(format: ExportFormat, from: LocalDate, to: LocalDate): String = "kosh-$from-to-$to.${format.extension}"

    // ------------------------------------------------------------------ CSV

    private val CSV_HEADER = listOf("date", "account", "type", "category", "merchant", "note", "amount", "source", "reference")
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Expenses are negative, income positive; a transfer's account column reads "From → To". */
    fun csv(data: ExportData, lineEnd: String = "\n"): String {
        val accounts = data.accounts.associateBy { it.id }
        val categories = data.categories.associateBy { it.key }
        val sb = StringBuilder(BOM)
        sb.append(CSV_HEADER.joinToString(",")).append(lineEnd)
        for (t in rows(data)) {
            val account = accountLabel(accounts[t.accountId]) +
                (t.toAccountId?.let { " → " + accountLabel(accounts[it]) } ?: "")
            val signed = when (t.type) {
                TxnType.EXPENSE -> -t.amount
                else -> t.amount
            }
            val fields = listOf(
                dateTime.format(Instant.ofEpochMilli(t.timestamp).atZone(data.zone)),
                account,
                t.type.name.lowercase().replaceFirstChar { it.uppercase() },
                categories[t.categoryKey]?.name ?: t.categoryKey,
                t.merchant.orEmpty(),
                t.note.orEmpty(),
                rupees(signed),
                sourceLabel(t.source),
                t.referenceNo.orEmpty(),
            )
            sb.append(fields.joinToString(",") { csvField(it) }).append(lineEnd)
        }
        return sb.toString()
    }

    fun csvField(value: String): String {
        // A leading = + @ would be evaluated as a formula by spreadsheets; prefix with an apostrophe. (Minus is left
        // alone so negative amounts stay numbers.)
        val safe = if (value.isNotEmpty() && value[0] in "=+@") "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || safe != safe.trim()
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    // ------------------------------------------------------------------ Beancount

    private const val EQUITY_OPENING = "Equity:Opening-Balances"

    fun beancount(data: ExportData): String {
        val book = Book(data)
        val sb = StringBuilder()
        sb.append("; Exported from Kosh, ${data.from} to ${data.to}\n")
        sb.append("option \"title\" \"Kosh\"\n")
        sb.append("option \"operating_currency\" \"INR\"\n\n")
        sb.append("${book.firstDate} commodity INR\n\n")
        for ((name, date) in book.openings) sb.append("$date open $name INR\n")
        sb.append('\n')
        for (e in book.entries) {
            sb.append("${e.date} * ${quote(e.payee)}")
            if (e.narration != null) sb.append(' ').append(quote(e.narration))
            sb.append('\n')
            for (p in e.postings) sb.append("  ").append(p.account.padEnd(44)).append(' ').append(rupees(p.amount).padStart(12)).append(" INR\n")
            sb.append('\n')
        }
        for (b in book.balances) {
            if (b.verified) {
                sb.append("${b.date} balance ${b.account}  ${rupees(b.amount)} INR\n")
            } else {
                sb.append("; ${b.date} reported by bank SMS: ${b.account} ${rupees(b.amount)} INR\n")
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Ledger (hledger-compatible)

    fun ledger(data: ExportData): String {
        val book = Book(data)
        val sb = StringBuilder()
        sb.append("; Exported from Kosh, ${data.from} to ${data.to}\n\n")
        for ((name, _) in book.openings) sb.append("account $name\n")
        sb.append('\n')
        for (e in book.entries) {
            sb.append("${e.date.toString().replace('-', '/')} * ${e.payee.replace(';', ',')}")
            if (e.narration != null) sb.append("  ; ").append(e.narration.replace('\n', ' '))
            sb.append('\n')
            for (p in e.postings) sb.append("    ").append(p.account.padEnd(44)).append("  INR ").append(rupees(p.amount)).append('\n')
            sb.append('\n')
        }
        for (b in book.balances) {
            if (b.verified) {
                sb.append("${b.date.toString().replace('-', '/')} * Balance reported by bank SMS\n")
                sb.append("    ").append(b.account.padEnd(44)).append("  INR 0.00 = INR ").append(rupees(b.amount)).append("\n\n")
            } else {
                sb.append("; ${b.date} reported by bank SMS: ${b.account} INR ${rupees(b.amount)}\n")
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ shared double-entry model

    internal data class Posting(val account: String, val amount: Paise)
    internal data class Entry(val date: LocalDate, val payee: String, val narration: String?, val postings: List<Posting>)
    internal data class BalanceAssertion(val date: LocalDate, val account: String, val amount: Paise, val verified: Boolean)

    /** Converts Kosh transactions into balanced double-entry postings. */
    internal class Book(data: ExportData) {
        private val accounts = data.accounts.associateBy { it.id }
        private val categories = data.categories.associateBy { it.key }
        private val openingDates = linkedMapOf<String, LocalDate>()
        val entries = mutableListOf<Entry>()
        val balances = mutableListOf<BalanceAssertion>()
        val firstDate: LocalDate

        val openings: List<Pair<String, LocalDate>> get() = openingDates.entries.map { it.key to it.value }

        init {
            val txns = rows(data)
            val dates = txns.map { day(it.timestamp, data.zone) }
            firstDate = (dates + data.from).min()
            fun open(name: String, date: LocalDate) {
                val existing = openingDates[name]
                if (existing == null || date < existing) openingDates[name] = date
            }

            // Opening balances the user entered, when they fall inside the export.
            for (a in data.accounts.sortedBy { it.id }) {
                if (a.openingBalance == 0L || a.openingDate == 0L) continue
                val date = day(a.openingDate, data.zone)
                if (date < data.from || date > data.to) continue
                val name = ledgerAccount(a)
                val amount = if (a.type == AccountType.CREDIT_CARD) -a.openingBalance else a.openingBalance
                open(name, firstDate); open(EQUITY_OPENING, firstDate)
                entries += Entry(date, "Opening balance", null, listOf(Posting(name, amount), Posting(EQUITY_OPENING, -amount)))
            }

            for (t in txns) {
                val date = day(t.timestamp, data.zone)
                val from = ledgerAccount(accounts[t.accountId])
                val payee = t.merchant?.takeIf { it.isNotBlank() }
                    ?: if (t.type == TxnType.TRANSFER) "Transfer" else categoryName(t.categoryKey)
                val narration = t.note?.takeIf { it.isNotBlank() }
                val postings = when (t.type) {
                    TxnType.EXPENSE -> listOf(Posting(categoryAccount("Expenses", t.categoryKey), t.amount), Posting(from, -t.amount))
                    TxnType.INCOME -> listOf(Posting(from, t.amount), Posting(categoryAccount("Income", t.categoryKey), -t.amount))
                    TxnType.TRANSFER -> {
                        val to = t.toAccountId?.let { ledgerAccount(accounts[it]) } ?: "Expenses:Transfers"
                        listOf(Posting(to, t.amount), Posting(from, -t.amount))
                    }
                }
                postings.forEach { open(it.account, firstDate) }
                entries += Entry(date, payee, narration, postings)
            }
            entries.sortBy { it.date }

            // Balances a bank SMS reported, asserted the day after (a balance holds at the start of its date).
            // An assertion is written only when the exported postings reproduce it; otherwise it is a comment,
            // so the file always passes `bean-check` / `hledger check`.
            for (a in data.accounts.sortedBy { it.id }) {
                val reported = a.reportedBalance ?: continue
                val at = a.reportedAt ?: continue
                val reportedDay = day(at, data.zone)
                if (reportedDay < data.from || reportedDay > data.to) continue
                val name = ledgerAccount(a)
                val sameDayLater = txns.any { (it.accountId == a.id || it.toAccountId == a.id) && day(it.timestamp, data.zone) == reportedDay && it.timestamp > at }
                val expected = if (a.type == AccountType.CREDIT_CARD) {
                    val limit = a.creditLimit ?: continue
                    -(limit - reported)
                } else {
                    reported
                }
                val computed = entries.filter { it.date <= reportedDay }.flatMap { it.postings }.filter { it.account == name }.sumOf { it.amount }
                open(name, firstDate)
                balances += BalanceAssertion(reportedDay.plusDays(1), name, expected, verified = !sameDayLater && computed == expected)
            }
        }

        private fun categoryName(key: String) = categories[key]?.name ?: key

        private fun categoryAccount(root: String, key: String) = "$root:" + component(categoryName(key))
    }

    // ------------------------------------------------------------------ helpers

    internal fun rows(data: ExportData): List<TransactionEntity> {
        val from = data.from.atStartOfDay(data.zone).toInstant().toEpochMilli()
        val to = data.to.plusDays(1).atStartOfDay(data.zone).toInstant().toEpochMilli()
        return data.transactions.filter { it.timestamp in from until to }.sortedWith(compareBy({ it.timestamp }, { it.id }))
    }

    internal fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Beancount/ledger account for a Kosh account, e.g. `Assets:Bank:HDFC-Savings-4821`. */
    fun ledgerAccount(a: AccountEntity?): String {
        if (a == null) return "Assets:Unknown"
        val label = if (a.last4 != null && !a.name.contains(a.last4)) "${a.name} ${a.last4}" else a.name
        val root = when (a.type) {
            AccountType.BANK -> "Assets:Bank"
            AccountType.CASH -> "Assets:Cash"
            AccountType.WALLET -> "Assets:Wallet"
            AccountType.CREDIT_CARD -> "Liabilities:CreditCard"
        }
        return "$root:" + component(label)
    }

    /** A valid account component: starts with an uppercase letter or digit, then letters, digits and hyphens. */
    fun component(name: String): String {
        val words = name.replace("&", " And ").split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val joined = words.joinToString("-") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        return if (joined.isEmpty()) "Other" else joined
    }

    fun rupees(paise: Paise): String = BigDecimal.valueOf(paise).movePointLeft(2).setScale(2).toPlainString()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ') + "\""

    private fun accountLabel(a: AccountEntity?): String = when {
        a == null -> "Unknown"
        a.last4 != null && !a.name.contains(a.last4) -> "${a.name} ••${a.last4}"
        else -> a.name
    }

    private fun sourceLabel(s: TxnSource): String = when (s) {
        TxnSource.MANUAL -> "Manual"
        TxnSource.SMS -> "SMS"
        TxnSource.EMAIL -> "Email"
        TxnSource.AUTO -> "Auto"
    }
}
