package com.amitjha.kosh.repo

import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.CategoryGuesser
import com.amitjha.kosh.sms.MerchantNormalizer
import com.amitjha.kosh.sms.statement.StatementCsv
import com.amitjha.kosh.util.Time
import kotlin.math.abs

/**
 * Imports a bank statement exported as CSV into one account. Rows already in Kosh (same account, type and amount
 * within a day, for example from SMS) are left out, and a fingerprint keeps a row from being imported twice.
 */
class StatementImporter(private val db: KoshDatabase) {

    data class Preview(
        val parsed: StatementCsv.Parsed,
        /** Rows that will be added. */
        val fresh: List<StatementCsv.Row>,
        /** Rows already in Kosh. */
        val duplicates: Int,
    )

    suspend fun preview(text: String, accountId: Long, columns: StatementCsv.Columns? = null): Preview {
        val parsed = StatementCsv.parse(text, columns)
        if (parsed.rows.isEmpty()) return Preview(parsed, emptyList(), 0)
        val from = parsed.rows.minOf { it.date }.minusDays(1)
        val existing = db.transactions().since(Time.startOfDay(from)).filter { it.accountId == accountId || it.toAccountId == accountId }
        val used = mutableSetOf<Long>()
        val fresh = parsed.rows.filter { row ->
            val day = row.date.toEpochDay()
            val match = existing.firstOrNull { t ->
                t.id !in used && t.amount == row.amount && abs(Time.epochDayOf(t.timestamp) - day) <= 1 &&
                    (t.type == row.type || t.type == TxnType.TRANSFER || t.fingerprint == fingerprint(accountId, row))
            }
            if (match != null) used += match.id
            match == null
        }
        return Preview(parsed, fresh, parsed.rows.size - fresh.size)
    }

    /** Adds [rows] to [accountId] as unreviewed transactions; returns how many were added. */
    suspend fun import(accountId: Long, rows: List<StatementCsv.Row>): Int {
        var added = 0
        for (row in rows) {
            val merchant = runCatching { MerchantNormalizer.normalize(row.description) }.getOrNull()?.takeIf { it.isNotBlank() }
            val channel = channelOf(row.description)
            val id = db.transactions().insertIgnore(
                TransactionEntity(
                    accountId = accountId,
                    type = row.type,
                    amount = row.amount,
                    categoryKey = CategoryGuesser.guess(merchant, row.description, row.type, channel),
                    merchant = merchant,
                    note = row.description.take(NOTE_MAX),
                    timestamp = Time.startOfDay(row.date) + NOON_MILLIS,
                    channel = channel,
                    source = TxnSource.AUTO,
                    fingerprint = fingerprint(accountId, row),
                    referenceNo = row.reference,
                    reviewed = false,
                ),
            )
            if (id > 0) added++
        }
        return added
    }

    companion object {
        private const val NOTE_MAX = 200
        private const val NOON_MILLIS = 12L * 60 * 60 * 1000

        fun fingerprint(accountId: Long, row: StatementCsv.Row): String =
            "${com.amitjha.kosh.domain.SmsMapping.STATEMENT_PREFIX}$accountId:${row.date}:${row.type.name}:${row.amount}:${row.description.trim().lowercase().hashCode()}"

        private fun word(vararg w: String) = Regex(w.joinToString("|") { "\\b$it\\b" })
        private val CHANNELS = listOf(
            word("UPI") to TxnChannel.UPI,
            word("NEFT") to TxnChannel.NEFT,
            word("IMPS") to TxnChannel.IMPS,
            word("RTGS") to TxnChannel.RTGS,
            word("ATM", "NWD", "CASH WDL", "ATW") to TxnChannel.ATM,
            word("ACH", "NACH", "ECS", "SI", "MANDATE", "AUTOPAY") to TxnChannel.AUTO_DEBIT,
            word("POS", "CARD", "ECOM") to TxnChannel.CARD,
            word("CHQ", "CHEQUE", "CLG") to TxnChannel.CHEQUE,
        )

        fun channelOf(description: String): TxnChannel {
            val d = description.uppercase().replace('/', ' ').replace('-', ' ')
            return CHANNELS.firstOrNull { it.first.containsMatchIn(d) }?.second ?: TxnChannel.OTHER
        }
    }
}
