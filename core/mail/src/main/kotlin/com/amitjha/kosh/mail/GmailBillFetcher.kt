package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank

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
        maxMessages: Int = 400,
        skipIds: Set<String> = emptySet(),
        includeAlerts: Boolean = true,
    ): BillFetchResult {
        val billIds = client.searchMessageIds(BillQueries.forBanks(banks, sinceDays), maxMessages)
        val paymentIds = client.searchMessageIds(BillQueries.paymentsForBanks(banks, sinceDays), maxMessages)
        val alertIds = if (includeAlerts) client.searchMessageIds(BillQueries.alertsForBanks(banks, sinceDays), maxMessages) else emptyList()
        val ids = (billIds + paymentIds + alertIds).distinct().filterNot { it in skipIds }.take(maxMessages)
        val transactions = ArrayList<com.amitjha.kosh.sms.ParsedTransaction>()
        val processed = ArrayList<String>()

        val bills = ArrayList<ParsedEmailBill>()
        val payments = ArrayList<PaymentReceived>()
        val failed = ArrayList<String>()
        for (id in ids) {
            try {
                val email = client.getMessage(id)
                processed.add(id)
                val bill = BillEmailParser.parse(email)
                val payment = if (bill == null) BillEmailParser.parsePaymentReceived(email) else null
                when {
                    bill != null -> bills.add(bill)
                    payment != null -> payments.add(payment)
                    else -> AlertEmailParser.parse(email)?.let(transactions::add)
                }
            } catch (e: GmailException.AuthExpired) {
                throw e
            } catch (e: Exception) {
                failed.add(id)
            }
        }
        return BillFetchResult(
            bills = bills.distinctBy { it.fingerprint },
            payments = payments,
            scannedMessages = ids.size,
            failedMessageIds = failed,
            transactions = transactions.distinctBy { it.fingerprint },
            processedIds = processed,
        )
    }
}
