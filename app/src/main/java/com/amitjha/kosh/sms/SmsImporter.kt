package com.amitjha.kosh.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ImportResult(
    val scanned: Int = 0,
    val financial: Int = 0,
    val inserted: Int = 0,
    val duplicates: Int = 0,
    val bills: Int = 0,
    val balancesUpdated: Int = 0,
    val accountsCreated: Int = 0,
    val ignored: Int = 0,
    val permissionMissing: Boolean = false,
    val insertedTransactions: List<TransactionEntity> = emptyList(),
) {
    operator fun plus(o: ImportResult) = ImportResult(
        scanned + o.scanned, financial + o.financial, inserted + o.inserted, duplicates + o.duplicates,
        bills + o.bills, balancesUpdated + o.balancesUpdated, accountsCreated + o.accountsCreated, ignored + o.ignored,
        permissionMissing || o.permissionMissing, insertedTransactions + o.insertedTransactions,
    )
}

/**
 * Reads bank SMS from the inbox (or a live broadcast), parses them with the core SMS engine and stores
 * transactions, card bills and reported balances. Re-importing is safe: fingerprints de-duplicate.
 */
class SmsImporter(
    private val context: Context,
    private val db: KoshDatabase,
    private val settings: SettingsStore,
) {
    private val lock = Mutex()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Imports inbox messages received after [sinceMillis]; by default since the last scan, or the last
     * [FIRST_SCAN_DAYS] days on the first run.
     */
    suspend fun importInbox(sinceMillis: Long? = null): ImportResult {
        if (!hasPermission()) return ImportResult(permissionMissing = true)
        val lastScan = settings.current().smsLastScanMillis
        val since = sinceMillis ?: if (lastScan > 0) lastScan - OVERLAP_MILLIS else System.currentTimeMillis() - FIRST_SCAN_DAYS * DAY_MILLIS
        val started = System.currentTimeMillis()
        val messages = withContext(Dispatchers.IO) { readInbox(since) }
        val result = importMessages(messages)
        settings.setSmsLastScan(started)
        return result.copy(scanned = messages.size)
    }

    /** Imports already-read messages (used by the live SMS receiver and by tests). */
    suspend fun importMessages(messages: List<SmsMessage>): ImportResult = lock.withLock {
        withContext(Dispatchers.IO) {
            var result = ImportResult(scanned = messages.size)
            val rules = db.merchantRules().all().associate { it.merchant to it.categoryKey }
            for (msg in messages.sortedBy { it.timestampMillis }) {
                if (!isLikelyFinancialSender(msg.sender)) continue
                result = result.copy(financial = result.financial + 1)
                result = when (val parsed = runCatching { SmsParser.parse(msg) }.getOrNull()) {
                    is SmsParseResult.Transaction -> result + importTransaction(parsed.txn, rules)
                    is SmsParseResult.CardBill -> result + importBill(parsed.bill)
                    is SmsParseResult.BalanceUpdate -> result + importBalance(parsed.update)
                    is SmsParseResult.Ignored, null -> result.copy(ignored = result.ignored + 1)
                }
            }
            result
        }
    }

    private suspend fun importTransaction(p: ParsedTransaction, rules: Map<String, String>): ImportResult {
        var created = 0
        val account = resolveAccount(p.bank, p.accountLast4, p.isCreditCard) { created++ }
        val txnDao = db.transactions()

        // A transfer the user already recorded (e.g. "mark bill paid") covers this card payment or bank debit.
        if (coveredByManualTransfer(account, p)) {
            return ImportResult(duplicates = 1, accountsCreated = created)
        }

        val entity = SmsMapping.toTransaction(p, account.id, p.merchant?.let(rules::get))
        val id = txnDao.insertIgnore(entity)
        var balances = 0
        if (id > 0) {
            val limit = p.availableCreditLimit
            val balance = p.availableBalance
            if (p.isCreditCard && limit != null) {
                updateReported(account, limit, p.timestampMillis); balances++
            } else if (!p.isCreditCard && balance != null) {
                updateReported(account, balance, p.timestampMillis); balances++
            }
            if (p.isCardPayment) {
                pairBankDebitForCardPayment(account, p)
                settleBills(account, p.amount, p.timestampMillis)
            }
        }
        return if (id > 0) {
            ImportResult(inserted = 1, balancesUpdated = balances, accountsCreated = created, insertedTransactions = listOf(entity.copy(id = id)))
        } else {
            ImportResult(duplicates = 1, accountsCreated = created)
        }
    }

    private suspend fun importBill(b: ParsedCardBill): ImportResult {
        var created = 0
        val card = resolveAccount(b.bank, b.cardLast4, isCard = true) { created++ }
        if (db.bills().countSame(card.id, b.dueDateEpochDay, b.totalDue) > 0) return ImportResult(duplicates = 1, accountsCreated = created)
        val id = db.bills().insertIgnore(SmsMapping.toBill(b, card.id))
        return ImportResult(bills = if (id > 0) 1 else 0, duplicates = if (id > 0) 0 else 1, accountsCreated = created)
    }

    private suspend fun importBalance(b: ParsedBalance): ImportResult {
        val isCard = b.availableBalance == null && b.availableCreditLimit != null
        val accounts = db.accounts().all()
        val account = SmsMapping.matchAccount(accounts, b.bank, b.accountLast4, isCard) ?: return ImportResult(ignored = 1)
        val value = if (isCard) b.availableCreditLimit else b.availableBalance
        value ?: return ImportResult(ignored = 1)
        updateReported(account, value, b.timestampMillis)
        return ImportResult(balancesUpdated = 1)
    }

    private suspend fun resolveAccount(bank: Bank, last4: String?, isCard: Boolean, onCreate: () -> Unit): AccountEntity {
        val accounts = db.accounts().all()
        SmsMapping.matchAccount(accounts, bank, last4, isCard)?.let { return it }
        val fresh = SmsMapping.newAccountFor(bank, last4, isCard, System.currentTimeMillis())
        val id = db.accounts().insert(fresh)
        onCreate()
        return fresh.copy(id = id)
    }

    /** Only newer reports replace the stored balance. */
    private suspend fun updateReported(account: AccountEntity, value: Long, at: Long) {
        val current = db.accounts().byId(account.id) ?: return
        if (current.reportedAt == null || at >= current.reportedAt) {
            db.accounts().update(current.copy(reportedBalance = value, reportedAt = at))
        }
    }

    private suspend fun coveredByManualTransfer(account: AccountEntity, p: ParsedTransaction): Boolean {
        val isCardPaymentSide = p.isCardPayment || (!p.isCreditCard && p.type == TxnType.EXPENSE)
        if (!isCardPaymentSide) return false
        val window = 3 * DAY_MILLIS
        return db.transactions().findAmountNear(account.id, p.amount, p.timestampMillis - window, p.timestampMillis + window)
            .any { it.type == TxnType.TRANSFER && it.source == TxnSource.MANUAL }
    }

    /**
     * A card payment usually also produces a bank debit SMS. When a bank-account expense of the same amount
     * appears within a day, mark it as a transfer so the payment isn't counted as spending twice.
     */
    private suspend fun pairBankDebitForCardPayment(card: AccountEntity, p: ParsedTransaction) {
        val banks = db.accounts().all().filter { it.type == AccountType.BANK }
        for (bank in banks) {
            val match = db.transactions().findAmountNear(bank.id, p.amount, p.timestampMillis - DAY_MILLIS, p.timestampMillis + DAY_MILLIS)
                .firstOrNull { it.type == TxnType.EXPENSE && it.accountId == bank.id && !it.excludeFromStats }
            if (match != null) {
                db.transactions().update(match.copy(categoryKey = SmsMapping.TRANSFER_CATEGORY, excludeFromStats = true, note = match.note ?: "Paid ${card.name}"))
                return
            }
        }
    }

    /** Marks the card's bills paid when a payment covers them. */
    private suspend fun settleBills(card: AccountEntity, amount: Long, at: Long) {
        var remaining = amount
        for (bill in db.bills().unpaidForCard(card.id)) {
            if (remaining >= bill.totalDue) {
                db.bills().update(bill.copy(paid = true, paidAt = at))
                remaining -= bill.totalDue
            }
        }
    }

    private fun readInbox(sinceMillis: Long): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "${Telephony.Sms.DATE} > ?",
            arrayOf(sinceMillis.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val sender = c.getString(iAddr) ?: continue
                val body = c.getString(iBody) ?: continue
                out += SmsMessage(sender, body, c.getLong(iDate))
            }
        }
        return out
    }

    companion object {
        const val FIRST_SCAN_DAYS = 180L
        private const val DAY_MILLIS = 86_400_000L
        /** Re-read a little before the last scan so messages delivered late are not missed. */
        private const val OVERLAP_MILLIS = 2 * DAY_MILLIS
    }
}
