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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class GmailSyncResult(
    val scanned: Int = 0,
    val billsAdded: Int = 0,
    val billsSettled: Int = 0,
    val failed: Int = 0,
    val transactionsAdded: Int = 0,
    val error: String? = null,
    val needsReconnect: Boolean = false,
    val moreAvailable: Boolean = false,
)

/** Pulls credit card statements from Gmail into card bills. */
class GmailSync(
    private val db: KoshDatabase,
    private val settings: SettingsStore,
    private val auth: GmailAuth,
) {

    private val syncMutex = Mutex()

    suspend fun sync(sinceDays: Int = 180, onProgress: (Int, Int) -> Unit = { _, _ -> }): GmailSyncResult {
        if (!syncMutex.tryLock()) return GmailSyncResult(error = "A Gmail scan is already running")
        return try {
            syncOnce(sinceDays, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GmailSyncResult(error = e.message ?: "Gmail sync failed")
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun syncOnce(sinceDays: Int, onProgress: (Int, Int) -> Unit): GmailSyncResult {
        val s = settings.current()
        if (!s.gmailConnected) return GmailSyncResult(error = "Gmail is not connected")
        var token = auth.accessTokenSilently() ?: return GmailSyncResult(error = "Reconnect Gmail", needsReconnect = true)

        val banks = cardBanks()
        val skip = settings.processedEmailIds()
        val result = try {
            fetch(token, banks, sinceDays, skip, onProgress)
        } catch (e: GmailException.AuthExpired) {
            auth.invalidateToken(token)
            token = auth.accessTokenSilently() ?: return GmailSyncResult(error = "Reconnect Gmail", needsReconnect = true)
            try {
                fetch(token, banks, sinceDays, skip, onProgress)
            } catch (e2: GmailException.AuthExpired) {
                settings.setGmailReconnectNeeded(true)
                return GmailSyncResult(error = "Reconnect Gmail", needsReconnect = true)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                return GmailSyncResult(error = e2.message)
            }
        } catch (e: CancellationException) {
            throw e
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
        var txnsAdded = 0
        val rules = db.merchantRules().all().associate { it.merchant to it.categoryKey }
        for (t in result.transactions) {
            if (importTransaction(t, rules)) txnsAdded++
        }
        runCatching { com.amitjha.kosh.repo.AutoCategoriser.run(db) }
        val processed = result.processedIds.ifEmpty { result.bills.map { it.emailId } + result.payments.map { it.emailId } }
        settings.addProcessedEmailIds(processed)
        if (result.error == null && !result.moreAvailable && result.failedMessageIds.isEmpty()) {
            settings.setGmailLastSync(System.currentTimeMillis())
        }
        settings.setGmailReconnectNeeded(false)
        settings.setGmailLastSummary(
            "Scanned ${result.scannedMessages} new email${if (result.scannedMessages == 1) "" else "s"} · " +
                "$added bill${if (added == 1) "" else "s"} · $txnsAdded transaction${if (txnsAdded == 1) "" else "s"}",
        )
        return GmailSyncResult(result.scannedMessages, added, settled, result.failedMessageIds.size, txnsAdded,
            error = result.error, moreAvailable = result.moreAvailable)
    }

    /**
     * Stores a transaction from an alert e-mail unless the same spend is already known from SMS
     * (same account and amount within [DEDUPE_WINDOW_MILLIS], or the same reference number).
     */
    private suspend fun importTransaction(p: com.amitjha.kosh.sms.ParsedTransaction, rules: Map<String, String>): Boolean {
        val account = resolveAccount(p.bank, p.accountLast4, p.isCreditCard)
        val near = db.transactions().findAmountNear(
            account.id, p.amount, p.timestampMillis - DEDUPE_WINDOW_MILLIS, p.timestampMillis + DEDUPE_WINDOW_MILLIS,
        )
        if (near.any { it.type == p.type || it.type == com.amitjha.kosh.model.TxnType.TRANSFER }) return false
        if (p.referenceNo != null && db.transactions().countReference(p.referenceNo!!) > 0) return false
        val entity = SmsMapping.toTransaction(p, account.id, SmsMapping.ruleFor(rules, p.merchant))
            .copy(source = TxnSource.EMAIL, reviewed = false)
        return db.transactions().insertIgnore(entity) > 0
    }

    private suspend fun resolveAccount(bank: Bank, last4: String?, isCard: Boolean): AccountEntity {
        val accounts = db.accounts().all()
        SmsMapping.matchAccount(accounts, bank, last4, isCard)?.let { return it }
        val fresh = SmsMapping.newAccountFor(bank, last4, isCard = isCard, now = System.currentTimeMillis())
        return fresh.copy(id = db.accounts().insert(fresh))
    }

    private suspend fun fetch(token: String, banks: Set<Bank>, sinceDays: Int, skip: Set<String>, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val started = System.nanoTime()
        val client = GmailClient(tokenProvider = { token }, checkActive = {
            context.ensureActive()
            // Stop starting requests after a minute; an in-flight request retains its socket timeout.
            if (System.nanoTime() - started >= 60_000_000_000L) throw GmailException.ScanTimedOut()
        })
        GmailBillFetcher(client).fetch(banks, sinceDays, skipIds = skip, onProgress = onProgress)
    }

    /** Banks of the user's cards, or the four supported issuers if no cards are set up yet. */
    private suspend fun cardBanks(): Set<Bank> {
        val fromAccounts = db.accounts().all().filter { !it.archived }
            .map { Bank.fromName(it.bank) }.filter { it != Bank.OTHER }.toSet()
        return fromAccounts + DEFAULT_BANKS
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
        const val DEDUPE_WINDOW_MILLIS = 10 * 60_000L
    }
}

