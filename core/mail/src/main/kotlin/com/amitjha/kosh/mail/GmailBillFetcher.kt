package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank

/** Result of one Gmail sync: parsed statements, payment acknowledgements and per-message failures. */
data class BillFetchResult(
    val bills: List<ParsedEmailBill>,
    val payments: List<PaymentReceived>,
    val scannedMessages: Int,
    val failedMessageIds: List<String>,
)

/**
 * Searches Gmail for statement and payment e-mails from [banks] and parses them.
 * Messages whose IDs are in [skipIds] (already processed) are not downloaded again.
 * [GmailException.AuthExpired] propagates so the caller can refresh the token and retry.
 */
class GmailBillFetcher(private val client: GmailClient) {
    fun fetch(
        banks: Set<Bank>,
        sinceDays: Int = 90,
        maxMessages: Int = 60,
        skipIds: Set<String> = emptySet(),
    ): BillFetchResult {
        val billIds = client.searchMessageIds(BillQueries.forBanks(banks, sinceDays), maxMessages)
        val paymentIds = client.searchMessageIds(BillQueries.paymentsForBanks(banks, sinceDays), maxMessages)
        val ids = (billIds + paymentIds).distinct().filterNot { it in skipIds }

        val bills = ArrayList<ParsedEmailBill>()
        val payments = ArrayList<PaymentReceived>()
        val failed = ArrayList<String>()
        for (id in ids) {
            try {
                val email = client.getMessage(id)
                val bill = BillEmailParser.parse(email)
                if (bill != null) {
                    bills.add(bill)
                } else {
                    BillEmailParser.parsePaymentReceived(email)?.let(payments::add)
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
        )
    }
}
