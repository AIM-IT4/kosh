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
import kotlinx.coroutines.CancellationException
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
    /** Diagnostics: transactions and bills the parser recognised (new or already known). */
    val parsedTransactions: Int = 0,
    val parsedBills: Int = 0,
    val ignoredByReason: Map<String, Int> = emptyMap(),
    /** Messages that looked like transactions but did not parse, masked (sender: start of body). */
    val unparsedSamples: List<String> = emptyList(),
    /** Set when the SMS store could not be read (for example a SecurityException). */
    val readError: String? = null,
    /** Bank messages that were read but could not be stored, and why (masked). */
    val failed: Int = 0,
    val failures: List<String> = emptyList(),
    /** Earlier imports moved to the right date (their send time was implausible, see [SmsTime]). */
    val redated: Int = 0,
) {
    /** Number of SMS read from the phone. */
    val messagesRead: Int get() = scanned

    /** Messages that passed the financial gate. */
    val financialCandidates: Int get() = financial

    /** What went wrong, in words for the user; null when everything could be read and stored. */
    val problem: String?
        get() = when {
            permissionMissing -> "SMS access is off"
            readError != null -> readError
            failed > 0 -> "$failed bank SMS couldn't be saved"
            else -> null
        }

    operator fun plus(o: ImportResult) = ImportResult(
        scanned = scanned + o.scanned,
        financial = financial + o.financial,
        inserted = inserted + o.inserted,
        duplicates = duplicates + o.duplicates,
        bills = bills + o.bills,
        balancesUpdated = balancesUpdated + o.balancesUpdated,
        accountsCreated = accountsCreated + o.accountsCreated,
        ignored = ignored + o.ignored,
        permissionMissing = permissionMissing || o.permissionMissing,
        insertedTransactions = insertedTransactions + o.insertedTransactions,
        parsedTransactions = parsedTransactions + o.parsedTransactions,
        parsedBills = parsedBills + o.parsedBills,
        ignoredByReason = (ignoredByReason.keys + o.ignoredByReason.keys).associateWith { (ignoredByReason[it] ?: 0) + (o.ignoredByReason[it] ?: 0) },
        unparsedSamples = (unparsedSamples + o.unparsedSamples).take(MAX_SAMPLES),
        readError = readError ?: o.readError,
        failed = failed + o.failed,
        failures = (failures + o.failures).take(MAX_SAMPLES),
        redated = redated + o.redated,
    )

    companion object {
        const val MAX_SAMPLES = 10

        private val amountToken = Regex("(?i)(?:rs\\.?|inr|₹)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
        private val longDigits = Regex("\\d{4,}")

        /**
         * Full message body with account, card, reference and phone digits (runs of 4+) replaced by X,
         * keeping amounts (next to Rs/INR/₹) so the wording can be used to tune the parser.
         */
        fun maskForSharing(sender: String, body: String): String {
            val text = body.replace(Regex("\\s+"), " ").trim()
            val out = StringBuilder()
            var last = 0
            for (m in amountToken.findAll(text)) {
                out.append(longDigits.replace(text.substring(last, m.range.first)) { "X".repeat(it.value.length) })
                out.append(m.value)
                last = m.range.last + 1
            }
            out.append(longDigits.replace(text.substring(last)) { "X".repeat(it.value.length) })
            return "$sender: $out"
        }

        /** Masks long digit runs (account/card/phone numbers) so samples are safe to share. */
        fun mask(sender: String, body: String): String {
            val masked = body.replace(Regex("\\s+"), " ").trim().take(80)
                .replace(Regex("\\d{4,}")) { m -> "X".repeat(m.value.length - 2) + m.value.takeLast(2) }
            return "$sender: $masked"
        }
    }
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
        val read = withContext(Dispatchers.IO) { readAll(since) }
        val result = importMessages(read.first).copy(scanned = read.first.size, readError = read.second)
        // Only move the scan cursor forward when something could actually be read.
        if (read.second == null && read.first.isNotEmpty()) settings.setSmsLastScan(started)
        settings.setSmsProblem(result.problem, started)
        return result
    }

    /** "Scan inbox now": always re-reads the last [FIRST_SCAN_DAYS] days; fingerprints prevent duplicates. */
    suspend fun rescanAll(): ImportResult =
        importInbox(System.currentTimeMillis() - FIRST_SCAN_DAYS * DAY_MILLIS)

    /** Imports already-read messages (used by the live SMS receiver and by tests). */
    suspend fun importMessages(messages: List<SmsMessage>): ImportResult = lock.withLock {
        withContext(Dispatchers.IO) {
            var result = ImportResult(scanned = messages.size)
            val rules = db.merchantRules().all().associate { it.merchant to it.categoryKey }
            for (msg in messages.sortedBy { it.timestampMillis }) {
                // One message that can't be stored must not hold up the ones after it (or the scan cursor).
                result += try {
                    if (isFinancialMessage(msg.sender, msg.body)) importOne(msg, rules).copy(financial = 1) else ImportResult()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ImportResult(financial = 1, failed = 1, failures = listOf("${e.javaClass.simpleName} · ${ImportResult.mask(msg.sender, msg.body)}"))
                }
            }
            runCatching { com.amitjha.kosh.repo.AutoCategoriser.run(db) }
            // Only the window around these messages can gain new pairs; a live SMS shouldn't rescan everything.
            val earliest = messages.minOfOrNull { it.timestampMillis }
            if (earliest != null) {
                val since = earliest - com.amitjha.kosh.domain.TransferPairing.WINDOW_MILLIS - DAY_MILLIS
                runCatching { com.amitjha.kosh.repo.TransferPairer.run(db, since) }
            }
            result
        }
    }

    private suspend fun importOne(msg: SmsMessage, rules: Map<String, String>): ImportResult =
        when (val parsed = runCatching { SmsParser.parse(msg) }.getOrNull()) {
            is SmsParseResult.Transaction -> importTransaction(parsed.txn, rules, msg).copy(parsedTransactions = 1)
            is SmsParseResult.CardBill -> importBill(parsed.bill).copy(parsedBills = 1)
            is SmsParseResult.BalanceUpdate -> importBalance(parsed.update)
            is SmsParseResult.Ignored, null -> {
                val reason = (parsed as? SmsParseResult.Ignored)?.reason ?: "parse error"
                val sample = if (looksTransactional(msg.body) && reason in UNPARSED_REASONS) listOf(ImportResult.mask(msg.sender, msg.body)) else emptyList()
                ImportResult(ignored = 1, ignoredByReason = mapOf(reason to 1), unparsedSamples = sample)
            }
        }

    private suspend fun importTransaction(p: ParsedTransaction, rules: Map<String, String>, msg: SmsMessage? = null): ImportResult {
        val body = msg?.body.orEmpty()
        val txnDao = db.transactions()
        // Same SMS imported before 1.8, when inbox scans fingerprinted with the phone's storage time.
        if (msg != null && p.referenceNo == null && isKnownUnderReceivedTime(msg, p)) return ImportResult(duplicates = 1)
        // Imported before 2.1.8 under a send time the phone got wrong.
        if (msg != null) redateMisdated(msg, p)?.let { moved -> return ImportResult(duplicates = 1, redated = if (moved) 1 else 0) }
        // Already imported, or deleted by the user. Stopping here also keeps a re-read message from claiming a
        // transfer the user recorded by hand after the import: its fingerprint is taken, so storing it would fail.
        if (txnDao.countFingerprint(p.fingerprint) > 0 || db.tombstones().contains(p.fingerprint)) return ImportResult(duplicates = 1)

        var created = 0
        val debitCard = !p.isCreditCard && SmsMapping.isDebitCardMessage(body)
        val account = resolveAccount(p.bank, p.accountLast4, p.isCreditCard, debitCard) { created++ }

        // Without a reference number the fingerprint depends on the minute; the live receiver and an inbox
        // scan can see slightly different times for one message, so treat a near-identical import as the same.
        if (p.referenceNo == null && isNearDuplicate(account.id, p)) {
            return ImportResult(duplicates = 1, accountsCreated = created)
        }

        // A transfer the user already recorded (e.g. "mark bill paid") covers this card payment or bank debit.
        if (coverWithManualTransfer(account, p)) {
            return ImportResult(duplicates = 1, accountsCreated = created)
        }

        // "self", "own account" or "to a/c ..1234" (another own account) is a transfer, not spending.
        val ownLast4s = db.accounts().all().flatMap { it.allDigits }.toSet()
        val selfTransfer = p.type == TxnType.EXPENSE && !p.isCreditCard &&
            com.amitjha.kosh.domain.TransferPairing.looksLikeSelfTransfer(body, ownLast4s, account.last4)
        val parsedBase = SmsMapping.toTransaction(p, account.id, SmsMapping.ruleFor(rules, p.merchant))
        val base = if (selfTransfer) parsedBase.copy(categoryKey = SmsMapping.TRANSFER_CATEGORY, excludeFromStats = true) else parsedBase
        val entity = base.copy(
            suspicious = SmsMapping.isSuspicious(base.amount, base.categoryKey, txnDao.recentAmounts(account.id)),
            // Messages for an account the user ignored are kept but never counted.
            excludeFromStats = base.excludeFromStats || account.archived,
        )
        val id = txnDao.insertIgnore(entity)
        var balances = 0
        if (id > 0) {
            val limit = p.availableCreditLimit
            val balance = p.availableBalance
            if (entity.suspicious) {
                // A doubtful message shouldn't anchor a balance either.
            } else if (p.isCreditCard && limit != null) {
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

    private suspend fun resolveAccount(bank: Bank, last4: String?, isCard: Boolean, debitCard: Boolean = false, onCreate: () -> Unit): AccountEntity {
        val accounts = db.accounts().all()
        SmsMapping.matchAccount(accounts, bank, last4, isCard)?.let { return fillDigits(it, last4, debitCard) }
        // A debit card's number isn't the account's: file it under the user's account at that bank and remember it.
        if (debitCard && last4 != null) SmsMapping.debitCardAccount(accounts, bank)?.let { return fillDigits(it, last4, debitCard = true) }
        val fresh = SmsMapping.newAccountFor(bank, last4, isCard, System.currentTimeMillis())
        val id = db.accounts().insert(fresh)
        onCreate()
        return fresh.copy(id = id)
    }

    /**
     * Remembers the digits from a message on an account the user added without them. A debit card's digits are
     * kept apart from the account number, so messages quoting the account still match.
     */
    private suspend fun fillDigits(account: AccountEntity, last4: String?, debitCard: Boolean = false): AccountEntity {
        if (last4 == null || account.archived || last4 in account.allDigits) return account
        val updated = when {
            debitCard -> account.plusOtherDigits(last4)
            account.last4 == null -> account.copy(last4 = last4)
            else -> return account
        }
        db.accounts().update(updated)
        return updated
    }

    /** Only newer reports replace the stored balance. */
    private suspend fun updateReported(account: AccountEntity, value: Long, at: Long) {
        val current = db.accounts().byId(account.id) ?: return
        if (current.reportedAt == null || at >= current.reportedAt) {
            db.accounts().update(current.copy(reportedBalance = value, reportedAt = at))
        }
    }

    private suspend fun isKnownUnderReceivedTime(msg: SmsMessage, p: ParsedTransaction): Boolean {
        val received = msg.receivedMillis ?: return false
        if (received == msg.timestampMillis) return false
        val legacy = runCatching { SmsParser.parse(msg.copy(timestampMillis = received, receivedMillis = null)) }
            .getOrNull() as? SmsParseResult.Transaction ?: return false
        return legacy.txn.fingerprint != p.fingerprint && db.transactions().countFingerprint(legacy.txn.fingerprint) > 0
    }

    /**
     * Finds the row an earlier version imported under the message's implausible send time and moves it to the
     * corrected time, unless the user has changed its date since. Null when there is no such row; otherwise
     * whether its date moved.
     */
    private suspend fun redateMisdated(msg: SmsMessage, p: ParsedTransaction): Boolean? {
        val wrong = msg.implausibleSentMillis ?: return null
        val old = runCatching { SmsParser.parse(msg.copy(timestampMillis = wrong, receivedMillis = null, implausibleSentMillis = null)) }
            .getOrNull() as? SmsParseResult.Transaction ?: return null
        val dao = db.transactions()
        val row = dao.byFingerprint(old.txn.fingerprint) ?: return null
        if (row.timestamp != old.txn.timestampMillis) return false
        val fingerprint = if (old.txn.fingerprint == p.fingerprint || dao.countFingerprint(p.fingerprint) == 0) p.fingerprint else row.fingerprint
        if (row.timestamp == p.timestampMillis && fingerprint == row.fingerprint) return false
        dao.update(row.copy(timestamp = p.timestampMillis, fingerprint = fingerprint))
        return row.timestamp != p.timestampMillis
    }

    private suspend fun isNearDuplicate(accountId: Long, p: ParsedTransaction): Boolean {
        val nearby = db.transactions().findAmountNear(
            accountId, p.amount, p.timestampMillis - NEAR_DUPLICATE_MILLIS, p.timestampMillis + NEAR_DUPLICATE_MILLIS,
        )
        return SmsMapping.isNearDuplicate(nearby, accountId, p.type, p.amount, p.merchant, p.fingerprint)
    }

    /**
     * Matches an SMS to a transfer the user recorded by hand, so the money isn't moved twice. Direction
     * matters: a bank debit is covered only by a transfer *from* that account, a credit (bank income or a
     * card payment) only by a transfer *to* it, and each manual transfer covers at most one SMS per side.
     * The debit's fingerprint is stored on the manual transfer (so re-scans dedupe), the credit's as
     * `referenceNo = "sms:<fingerprint>"`.
     */
    private suspend fun coverWithManualTransfer(account: AccountEntity, p: ParsedTransaction): Boolean {
        val dao = db.transactions()
        val marker = MANUAL_COVER_PREFIX + p.fingerprint
        if (dao.countReference(marker) > 0) return true
        val debitSide = p.type == TxnType.EXPENSE && !p.isCreditCard
        val creditSide = p.type == TxnType.INCOME
        if (!debitSide && !creditSide) return false
        val window = 3 * DAY_MILLIS
        val nearby = dao.findAmountNear(account.id, p.amount, p.timestampMillis - window, p.timestampMillis + window)
        val match = SmsMapping.manualTransferFor(nearby, account.id, debitSide, p.timestampMillis) ?: return false
        dao.update(if (debitSide) match.copy(fingerprint = p.fingerprint) else match.copy(referenceNo = marker))
        return true
    }

    /**
     * A card payment usually also produces a bank debit SMS. When a bank-account expense of the same amount
     * appears within a day, mark it as a transfer so the payment isn't counted as spending twice.
     */
    private suspend fun pairBankDebitForCardPayment(card: AccountEntity, p: ParsedTransaction) {
        val banks = db.accounts().all().filter { it.type == AccountType.BANK }
        for (bank in banks) {
            val match = db.transactions().findAmountNear(bank.id, p.amount, p.timestampMillis - DAY_MILLIS, p.timestampMillis + DAY_MILLIS)
                .firstOrNull {
                    it.type == TxnType.EXPENSE && it.accountId == bank.id && !it.excludeFromStats &&
                        it.source != TxnSource.MANUAL && !it.reviewed
                }
            if (match != null) {
                db.transactions().update(match.copy(categoryKey = SmsMapping.TRANSFER_CATEGORY, excludeFromStats = true, note = match.note ?: "Paid ${card.name}"))
                return
            }
        }
    }

    /**
     * Finds the original messages behind imported transactions (matched by fingerprint, within the last
     * [FIRST_SCAN_DAYS] days) and returns up to [limit] of them masked for sharing: account, card and phone
     * digits become X, amounts and merchant names stay. Nothing leaves the phone unless the user shares it.
     */
    suspend fun samplesFor(fingerprints: Set<String>, limit: Int = 15): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission() || fingerprints.isEmpty()) return@withContext emptyList()
        val (messages, _) = readAll(System.currentTimeMillis() - FIRST_SCAN_DAYS * DAY_MILLIS)
        val out = ArrayList<String>()
        for (msg in messages.asReversed()) {
            val r = runCatching { SmsParser.parse(msg) }.getOrNull() as? SmsParseResult.Transaction ?: continue
            if (r.txn.fingerprint in fingerprints || legacyFingerprints(msg).any { it in fingerprints }) {
                out += ImportResult.maskForSharing(msg.sender, msg.body)
                if (out.size >= limit) break
            }
        }
        out
    }

    /**
     * A read-only look at the last [days] days of bank SMS: which are counted, which are in Kosh but not counted
     * (and why), which are missing and which were skipped. Nothing is written. Also returns how many SMS were read.
     */
    suspend fun checkup(days: Long = CHECKUP_DAYS): Pair<List<CheckupItem>, Int> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList<CheckupItem>() to 0
        val (messages, _) = readAll(System.currentTimeMillis() - days * DAY_MILLIS)
        val accounts = db.accounts().all()
        val items = messages.filter { isFinancialMessage(it.sender, it.body) }
            .sortedByDescending { it.timestampMillis }
            .map { msg -> runCatching { checkOne(msg, accounts) }.getOrElse { e ->
                CheckupItem(msg.timestampMillis, CheckupItem.Verdict.SKIPPED, "Couldn't be checked", e.javaClass.simpleName,
                    masked = ImportResult.maskForSharing(msg.sender, msg.body))
            } }
        items to messages.size
    }

    private suspend fun checkOne(msg: SmsMessage, accounts: List<AccountEntity>): CheckupItem {
        val masked = ImportResult.maskForSharing(msg.sender, msg.body)
        val skipped = CheckupItem(msg.timestampMillis, CheckupItem.Verdict.SKIPPED, "Skipped", masked = masked)
        val p = when (val parsed = runCatching { SmsParser.parse(msg) }.getOrNull()) {
            null -> return skipped.copy(reason = "the message couldn't be read")
            is SmsParseResult.Ignored -> return skipped.copy(reason = parsed.reason)
            is SmsParseResult.CardBill -> return skipped.copy(
                verdict = CheckupItem.Verdict.OTHER, title = "Card bill · ${com.amitjha.kosh.model.Money.format(parsed.bill.totalDue)}",
                reason = "a card bill, not a spend",
            )
            is SmsParseResult.BalanceUpdate -> return skipped.copy(
                verdict = CheckupItem.Verdict.OTHER, title = "Balance update", reason = "used for the account's balance",
            )
            is SmsParseResult.Transaction -> parsed.txn
        }
        val verb = if (p.type == TxnType.INCOME) "received" else "spent"
        val title = "${com.amitjha.kosh.model.Money.format(p.amount)} $verb${p.merchant?.let { " · $it" } ?: ""}"
        val base = CheckupItem(p.timestampMillis, CheckupItem.Verdict.MISSING, title, reason = "not in Kosh yet", masked = masked)
        val dao = db.transactions()
        val account = SmsMapping.matchAccount(accounts, p.bank, p.accountLast4, p.isCreditCard)
            ?: SmsMapping.debitCardAccount(accounts, p.bank).takeIf { !p.isCreditCard && SmsMapping.isDebitCardMessage(msg.body) }
        val row = (listOf(p.fingerprint) + legacyFingerprints(msg)).firstNotNullOfOrNull { dao.byFingerprint(it) }
            // The live receiver may have dated a message without a reference a minute apart.
            ?: account?.let { a ->
                dao.findAmountNear(a.id, p.amount, p.timestampMillis - NEAR_DUPLICATE_MILLIS, p.timestampMillis + NEAR_DUPLICATE_MILLIS)
                    .firstOrNull { it.type == p.type && it.source != TxnSource.MANUAL && it.merchant == p.merchant }
            }
        if (row == null) {
            return when {
                dao.countReference(MANUAL_COVER_PREFIX + p.fingerprint) > 0 ->
                    base.copy(verdict = CheckupItem.Verdict.NOT_COUNTED, reason = "matched to a transfer you added")
                db.tombstones().contains(p.fingerprint) -> base.copy(verdict = CheckupItem.Verdict.NOT_COUNTED, reason = "you deleted it")
                else -> base
            }
        }
        val owner = accounts.firstOrNull { it.id == row.accountId }
        // The same rule every total uses; the reason only explains it.
        val counted = SmsMapping.countsInStats(row)
        val reason = when {
            counted -> null
            row.type == TxnType.TRANSFER || row.categoryKey == SmsMapping.TRANSFER_CATEGORY -> "a transfer between your own accounts"
            owner?.archived == true -> "you ignored its account"
            row.suspicious -> "held for review: the amount looks unusual"
            else -> "excluded from totals"
        }
        return base.copy(
            verdict = if (counted) CheckupItem.Verdict.COUNTED else CheckupItem.Verdict.NOT_COUNTED,
            reason = reason,
            accountName = owner?.name,
            accountIgnored = owner?.archived == true,
            datedAt = row.timestamp,
            transactionId = row.id,
        )
    }

    /**
     * Fingerprints earlier versions gave this message: before 1.8 inbox scans used the storage time, before 2.1.8
     * an implausible send time was used as is.
     */
    private fun legacyFingerprints(msg: SmsMessage): List<String> =
        listOfNotNull(msg.receivedMillis?.takeIf { it != msg.timestampMillis }, msg.implausibleSentMillis).mapNotNull { at ->
            (runCatching { SmsParser.parse(msg.copy(timestampMillis = at, receivedMillis = null, implausibleSentMillis = null)) }.getOrNull()
                as? SmsParseResult.Transaction)?.txn?.fingerprint
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

    /**
     * Reads received messages from the inbox and, as a fallback, from the whole SMS store (some phones file
     * bank SMS under other folders). Returns the messages and an error description if reading failed.
     */
    private fun readAll(sinceMillis: Long): Pair<List<SmsMessage>, String?> {
        val seen = HashSet<String>()
        val out = ArrayList<SmsMessage>()
        var error: String? = null
        val sources = listOf(
            Telephony.Sms.Inbox.CONTENT_URI to "${Telephony.Sms.DATE} > ?",
            // all received messages (type 1 = inbox) wherever the phone stored them
            Telephony.Sms.CONTENT_URI to "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}",
        )
        for ((uri, selection) in sources) {
            try {
                readFrom(uri, selection, sinceMillis) { msg ->
                    if (seen.add("${msg.sender}|${msg.timestampMillis}|${msg.body.hashCode()}")) out += msg
                }
            } catch (e: SecurityException) {
                error = "SMS access was denied by the system"
            } catch (e: Exception) {
                if (error == null) error = "Could not read SMS (${e.javaClass.simpleName})"
            }
        }
        return out to (if (out.isEmpty()) error else null)
    }

    private inline fun readFrom(uri: android.net.Uri, selection: String, sinceMillis: Long, sink: (SmsMessage) -> Unit) {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        context.contentResolver.query(uri, projection, selection, arrayOf(sinceMillis.toString()), "${Telephony.Sms.DATE} ASC")?.use { c ->
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iSent = c.getColumnIndex(Telephony.Sms.DATE_SENT)
            while (c.moveToNext()) {
                val sender = c.getString(iAddr) ?: continue
                val body = c.getString(iBody) ?: continue
                val stored = c.getLong(iDate)
                // The send time equals the live receiver's PDU timestamp, so both paths fingerprint alike.
                val sent = if (iSent >= 0) c.getLong(iSent) else 0L
                sink(SmsTime.message(sender, body, sent, stored, keepReceived = true))
            }
        }
    }

    companion object {
        const val FIRST_SCAN_DAYS = 180L
        /** Days of messages the SMS check-up looks at. */
        const val CHECKUP_DAYS = 7L
        private const val DAY_MILLIS = 86_400_000L
        /** Re-read a little before the last scan so messages delivered late are not missed. */
        private const val OVERLAP_MILLIS = 2 * DAY_MILLIS

        /** Two reference-less imports this close, same account, amount, type and merchant, are one message. */
        const val NEAR_DUPLICATE_MILLIS = 3 * 60_000L

        /** Marks a manual transfer whose credit side was matched to an SMS. */
        const val MANUAL_COVER_PREFIX = "sms:"

        /** Ignore reasons that mean "we could not understand it" rather than "deliberately skipped". */
        private val UNPARSED_REASONS = setOf("no transaction found", "not a bank sender", "parse error")
    }
}
