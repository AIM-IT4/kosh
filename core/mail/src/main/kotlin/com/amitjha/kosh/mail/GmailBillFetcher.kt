package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import java.io.IOException
import java.util.concurrent.CancellationException

/** Result of one Gmail sync: parsed statements, payment acknowledgements and per-message failures. */
data class BillFetchResult(
    val bills: List<ParsedEmailBill>,
    val payments: List<PaymentReceived>,
    val scannedMessages: Int,
    val failedMessageIds: List<String>,
    /** Card spends and debits parsed from transaction-alert e-mails. */
    val transactions: List<com.amitjha.kosh.sms.ParsedTransaction> = emptyList(),
    /** Every message ID that was downloaded (so callers can skip them next time). */
    val processedIds: List<String> = emptyList(),
    val moreAvailable: Boolean = false,
    val error: String? = null,
)

/**
 * Searches Gmail for statement and payment e-mails from [banks] and parses them.
 * Messages whose IDs are in [skipIds] (already processed) are not downloaded again.
 * [GmailException.AuthExpired] propagates so the caller can refresh the token and retry.
 */
class GmailBillFetcher(private val client: GmailClient) {
    fun fetch(
        banks: Set<Bank>,
        sinceDays: Int = 180,
        maxMessages: Int = 50,
        skipIds: Set<String> = emptySet(),
        includeAlerts: Boolean = true,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BillFetchResult {
        require(maxMessages > 0)
        val billIds = client.searchMessageIds(BillQueries.forBanks(banks, sinceDays), maxMessages + 1, skipIds)
        val paymentIds = client.searchMessageIds(BillQueries.paymentsForBanks(banks, sinceDays), maxMessages + 1, skipIds)
        val alertIds = if (includeAlerts) client.searchMessageIds(BillQueries.alertsForBanks(banks, sinceDays), maxMessages + 1, skipIds) else emptyList()
        val pendingIds = (billIds + paymentIds + alertIds).distinct()
        val ids = pendingIds.take(maxMessages)
        var attempted = 0
        var error: String? = null
        onProgress(0, ids.size)
        val transactions = ArrayList<com.amitjha.kosh.sms.ParsedTransaction>()
        val processed = ArrayList<String>()

        val bills = ArrayList<ParsedEmailBill>()
        val payments = ArrayList<PaymentReceived>()
        val failed = ArrayList<String>()
        for (id in ids) {
            try {
                attempted++
                val email = client.getMessage(id)
                val bill = BillEmailParser.parse(email)
                val payment = if (bill == null) BillEmailParser.parsePaymentReceived(email) else null
                when {
                    bill != null -> bills.add(bill)
                    payment != null -> payments.add(payment)
                    else -> AlertEmailParser.parse(email)?.let(transactions::add)
                }
                processed.add(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GmailException.AuthExpired) {
                throw e
            } catch (e: GmailException.ScanTimedOut) {
                error = e.message
                break
            } catch (e: IOException) {
                failed.add(id)
                error = "Connection interrupted. Check your internet connection and sync again."
                break
            } catch (e: GmailException.Http) {
                failed.add(id)
                if (e.code != 404) {
                    error = e.message
                    break
                }
            } catch (e: Exception) {
                failed.add(id)
            }
            onProgress(attempted, ids.size)
        }
        return BillFetchResult(
            bills = bills.distinctBy { it.fingerprint },
            payments = payments,
            scannedMessages = attempted,
            failedMessageIds = failed,
            transactions = transactions.distinctBy { it.fingerprint },
            processedIds = processed,
            moreAvailable = pendingIds.size > ids.size || error != null,
            error = error,
        )
    }
}

