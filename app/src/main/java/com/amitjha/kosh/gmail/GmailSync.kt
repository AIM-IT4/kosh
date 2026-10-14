package com.amitjha.kosh.gmail

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.mail.GmailBillFetcher
import com.amitjha.kosh.mail.GmailClient
import com.amitjha.kosh.mail.GmailException
import com.amitjha.kosh.mail.ParsedEmailBill
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GmailSyncResult(
    val scanned: Int = 0,
    val billsAdded: Int = 0,
    val billsSettled: Int = 0,
    val failed: Int = 0,
    val error: String? = null,
    val needsReconnect: Boolean = false,
)

/** Pulls credit card statements from Gmail into card bills. */
class GmailSync(
    private val db: KoshDatabase,
    private val settings: SettingsStore,
    private val auth: GmailAuth,
) {

    suspend fun sync(sinceDays: Int = 90): GmailSyncResult {
        val s = settings.current()
        if (!s.gmailConnected) return GmailSyncResult(error = "Gmail is not connected")
        var token = auth.accessTokenSilently() ?: return GmailSyncResult(error = "Reconnect Gmail", needsReconnect = true)

        val banks = cardBanks()
        val skip = settings.processedEmailIds()
        val result = try {
            fetch(token, banks, sinceDays, skip)
        } catch (e: GmailException.AuthExpired) {
            auth.invalidateToken(token)
            token = auth.accessTokenSilently() ?: return GmailSyncResult(error = "Reconnect Gmail", needsReconnect = true)
            try {
                fetch(token, banks, sinceDays, skip)
            } catch (e2: Exception) {
                return GmailSyncResult(error = e2.message)
            }
        } catch (e: Exception) {
            return GmailSyncResult(error = e.message ?: "Gmail sync failed")
        }

        var added = 0
        var settled = 0
        for (bill in result.bills) {
            if (bill.isCreditBalance || bill.totalDue <= 0) continue
            val card = resolveCard(bill.bank, bill.cardLast4)
            if (db.bills().countSame(card.id, bill.dueDate?.toEpochDay(), bill.totalDue) > 0) continue
            if (db.bills().insertIgnore(toEntity(bill, card.id)) > 0) added++
        }
        for (payment in result.payments) {
            val card = SmsMapping.matchAccount(db.accounts().all(), payment.bank, payment.cardLast4, isCard = true) ?: continue
            var remaining = payment.amount
            for (bill in db.bills().unpaidForCard(card.id)) {
                if (remaining >= bill.totalDue) {
                    db.bills().update(bill.copy(paid = true, paidAt = System.currentTimeMillis()))
                    remaining -= bill.totalDue
                    settled++
                }
            }
        }
        val processed = (result.bills.map { it.emailId } + result.payments.map { it.emailId })
        settings.addProcessedEmailIds(processed)
        settings.setGmailLastSync(System.currentTimeMillis())
        settings.setGmailReconnectNeeded(false)
        return GmailSyncResult(result.scannedMessages, added, settled, result.failedMessageIds.size)
    }

    private suspend fun fetch(token: String, banks: Set<Bank>, sinceDays: Int, skip: Set<String>) = withContext(Dispatchers.IO) {
        GmailBillFetcher(GmailClient(tokenProvider = { token })).fetch(banks, sinceDays, skipIds = skip)
    }

    /** Banks of the user's cards, or the four supported issuers if no cards are set up yet. */
    private suspend fun cardBanks(): Set<Bank> {
        val fromCards = db.accounts().all().filter { it.type == AccountType.CREDIT_CARD && !it.archived }
            .map { Bank.fromName(it.bank) }.filter { it != Bank.OTHER }.toSet()
        return fromCards.ifEmpty { DEFAULT_BANKS }
    }

    private suspend fun resolveCard(bank: Bank, last4: String?): AccountEntity {
        val accounts = db.accounts().all()
        SmsMapping.matchAccount(accounts, bank, last4, isCard = true)?.let { return it }
        val fresh = SmsMapping.newAccountFor(bank, last4, isCard = true, now = System.currentTimeMillis())
        return fresh.copy(id = db.accounts().insert(fresh))
    }

    private fun toEntity(bill: ParsedEmailBill, cardId: Long) = CardBillEntity(
        accountId = cardId,
        totalDue = bill.totalDue,
        minimumDue = bill.minimumDue,
        dueDate = bill.dueDate?.toEpochDay(),
        statementDate = bill.statementDate?.toEpochDay(),
        source = TxnSource.EMAIL,
        fingerprint = bill.fingerprint,
        createdAt = Time.startOfDay(Time.today()),
    )

    companion object {
        val DEFAULT_BANKS = setOf(Bank.ICICI, Bank.HDFC, Bank.KOTAK, Bank.DBS)
    }
}
