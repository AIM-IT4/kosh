package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.FamilyMemberEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.BANKS_FORMERLY_OTHER
import com.amitjha.kosh.sms.ParsedCardBill
import com.amitjha.kosh.sms.ParsedTransaction

/** Pure mapping rules from parsed SMS/e-mail data to database entities. */
object SmsMapping {

    /** Category key used for money moved between the user's own accounts; excluded from income/expense stats. */
    const val TRANSFER_CATEGORY = "transfer"

    /**
     * Finds the account a message belongs to: same bank and kind (card vs account), and the same last 4 digits
     * when both are known. If the message has no digits and the user has exactly one such account, use it;
     * with several, reuse the account already created for digitless messages so only one is ever made.
     * A bank in [BANKS_FORMERLY_OTHER] with no account of its own falls back to an "Other" account with the
     * same digits, which is where its messages went before Kosh recognised the bank.
     */
    fun matchAccount(accounts: List<AccountEntity>, bank: Bank, last4: String?, isCard: Boolean): AccountEntity? {
        matchSameBank(accounts, bank, last4, isCard)?.let { return it }
        // Banks Kosh used to file under "Other": keep using the Other account that already has these digits,
        // so an existing user's history and balance don't split into a second account.
        if (last4 == null || bank !in BANKS_FORMERLY_OTHER) return null
        val type = if (isCard) AccountType.CREDIT_CARD else AccountType.BANK
        val other = accounts.filter { it.bank == Bank.OTHER.name && it.type == type && last4 in it.allDigits }
        return other.firstOrNull { !it.archived } ?: other.firstOrNull()
    }

    /**
     * [matchAccount] for a family member's forward. A forward loses the bank's sender, so an alert that doesn't name
     * its bank ("Rs 250 debited from a/c **1234") reads as Other: it belongs to the member's account with those digits
     * whatever its bank (when only one has them), or to their only account of that kind when it gives no digits.
     */
    fun matchForwarded(accounts: List<AccountEntity>, bank: Bank, last4: String?, isCard: Boolean): AccountEntity? {
        val type = if (isCard) AccountType.CREDIT_CARD else AccountType.BANK
        val own = accounts.filter { it.type == type && !it.archived }
        if (bank == Bank.OTHER) {
            // A real (confirmed or named) account of theirs wins over an "Other" one an earlier forward made.
            val named = own.filter { it.bank != Bank.OTHER.name }
            val byDigits = if (last4 != null) named.filter { a -> a.allDigits.any { sameDigits(it, last4) } } else emptyList()
            byDigits.singleOrNull()?.let { return it }
            if (last4 == null) named.singleOrNull()?.let { return it }
        }
        return matchAccount(accounts, bank, last4, isCard)
    }

    /**
     * Whether two masked numbers are the same: banks show the last 3 or 4 digits ("XX123", "XX0123"), so a
     * 3-digit number matches the end of a 4-digit one.
     */
    fun sameDigits(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 3 && (a.endsWith(b) || b.endsWith(a)))

    private fun matchSameBank(accounts: List<AccountEntity>, bank: Bank, last4: String?, isCard: Boolean): AccountEntity? {
        val type = if (isCard) AccountType.CREDIT_CARD else AccountType.BANK
        val sameKind = accounts.filter { it.bank == bank.name && it.type == type }
        val candidates = sameKind.filter { !it.archived }
        if (last4 != null) {
            candidates.firstOrNull { last4 in it.allDigits }?.let { return it }
            // The same number masked to a different length, when only one account fits.
            candidates.filter { a -> a.allDigits.any { sameDigits(it, last4) } }.singleOrNull()?.let { return it }
            // A message with digits never matches an account with different digits; an account the user added
            // without digits matches only when it is the sole account of this bank and kind.
            candidates.filter { it.last4 == null }.takeIf { it.size == 1 && candidates.size == 1 }?.let { return it.single() }
            // An account the user chose to ignore keeps receiving its messages, so it isn't recreated.
            return sameKind.firstOrNull { it.archived && last4 in it.allDigits }
        }
        candidates.singleOrNull()?.let { return it }
        return candidates.firstOrNull { it.last4 == null && it.autoCreated }
            ?: sameKind.firstOrNull { it.archived && it.last4 == null }
    }

    /** Above this, an imported amount (other than salary) is held for review instead of moving balances. */
    const val SUSPICIOUS_ABSOLUTE: Paise = 10_00_000L * 100

    /** An amount this many times the account's median is suspicious, provided it is also above [SUSPICIOUS_FLOOR]. */
    const val SUSPICIOUS_MEDIAN_MULTIPLE = 20

    const val SUSPICIOUS_FLOOR: Paise = 50_000L * 100

    /**
     * Whether an imported amount looks implausible, typically a reference or account number read as an amount.
     * Salary credits are trusted. [recentAmounts] are the account's latest amounts (at least 5 are needed
     * for the median rule).
     */
    fun isSuspicious(amount: Paise, categoryKey: String, recentAmounts: List<Paise>): Boolean {
        if (categoryKey == "salary") return false
        if (amount > SUSPICIOUS_ABSOLUTE) return true
        if (recentAmounts.size < 5 || amount <= SUSPICIOUS_FLOOR) return false
        val sorted = recentAmounts.sorted()
        val median = sorted[sorted.size / 2]
        return median > 0 && amount > median * SUSPICIOUS_MEDIAN_MULTIPLE
    }

    /**
     * A debit card has its own number, different from its account's. A debit-card message whose digits match no
     * account belongs to the user's account at that bank when they have exactly one (confirmed) there.
     */
    fun debitCardAccount(accounts: List<AccountEntity>, bank: Bank): AccountEntity? =
        accounts.filter { it.bank == bank.name && it.type == AccountType.BANK && !it.archived && !it.autoCreated }.singleOrNull()

    fun isDebitCardMessage(body: String): Boolean = debitCard.containsMatchIn(body)

    // Compiled once: these run for every SMS and, through ruleFor, for every rule of every transaction.
    private val debitCard = Regex("(?i)debit ?card")
    private val keyPrefix = Regex("^(upi|vpa|pos)[ :/-]+")
    private val nonKey = Regex("[^a-z0-9]")

    /** The account to create when no existing account matches; a family member's is named after them ("Mom · SBI Account ••1234"). */
    fun newAccountFor(bank: Bank, last4: String?, isCard: Boolean, now: Long, owner: FamilyMemberEntity? = null): AccountEntity {
        val suffix = last4?.let { " ••$it" } ?: ""
        val kind = if (isCard) "Card" else "Account"
        return AccountEntity(
            name = (owner?.let { "${it.name} · " } ?: "") + "${bank.shortName} $kind$suffix",
            memberId = owner?.id,
            type = if (isCard) AccountType.CREDIT_CARD else AccountType.BANK,
            bank = bank.name,
            last4 = last4,
            openingBalance = 0,
            // Pending until the user confirms it; until then its history never counts towards net worth.
            openingDate = 0L,
            colorArgb = bank.brandColor,
            autoCreated = true,
        )
    }

    /** Lower-case letters and digits only, so "Swiggy Ltd", "SWIGGY" and "swiggy" share one rule. */
    fun merchantKey(merchant: String): String =
        merchant.lowercase().replace(keyPrefix, "").replace(nonKey, "")

    /**
     * The user's learned category for [merchant]: an exact rule, then a rule for the same merchant spelled
     * differently, then a rule whose first word (4+ letters) matches, e.g. "Sharma Store" → "Sharma Stores".
     */
    fun ruleFor(rules: Map<String, String>, merchant: String?): String? {
        if (merchant.isNullOrBlank()) return null
        rules[merchant]?.let { return it }
        val key = merchantKey(merchant)
        if (key.isEmpty()) return null
        rules.entries.firstOrNull { merchantKey(it.key) == key }?.let { return it.value }
        val first = firstWord(merchant)
        if (first.length < 4) return null
        return rules.entries.firstOrNull { firstWord(it.key) == first }?.value
    }

    private fun firstWord(s: String): String =
        s.trim().substringBefore(' ').lowercase().filter { it.isLetterOrDigit() }

    /**
     * Picks a better category for an imported, unreviewed transaction stuck in "other", using the user's rules
     * first and the category guesser second. Returns null when nothing should change. Manual entries, reviewed
     * rows and transfers are never touched, and a row hidden from stats (an ignored account) stays hidden.
     */
    fun recategorised(txn: TransactionEntity, rules: Map<String, String>): TransactionEntity? {
        if (txn.source == TxnSource.MANUAL || txn.type != TxnType.EXPENSE) return null
        if (txn.categoryKey != "other" || txn.reviewed) return null
        val next = ruleFor(rules, txn.merchant)
            ?: com.amitjha.kosh.sms.CategoryGuesser.guess(txn.merchant, txn.note.orEmpty(), txn.type, txn.channel)
        if (next == txn.categoryKey || next == "other") return null
        return txn.copy(categoryKey = next, excludeFromStats = txn.excludeFromStats || next == TRANSFER_CATEGORY)
    }

    /**
     * Stats flag after a user picks [newCategory] for a row: transfers are hidden; a row hidden for another
     * reason (an ignored account) stays hidden; a former transfer becomes visible.
     */
    fun excludeAfterRecategorise(txn: TransactionEntity, newCategory: String): Boolean =
        newCategory == TRANSFER_CATEGORY || (txn.excludeFromStats && txn.categoryKey != TRANSFER_CATEGORY)

    fun toTransaction(parsed: ParsedTransaction, accountId: Long, categoryOverride: String?): TransactionEntity {
        val cardPayment = parsed.isCardPayment
        return TransactionEntity(
            accountId = accountId,
            type = parsed.type,
            amount = parsed.amount,
            categoryKey = when {
                cardPayment -> TRANSFER_CATEGORY
                else -> categoryOverride ?: parsed.suggestedCategory
            },
            merchant = parsed.merchant,
            timestamp = parsed.timestampMillis,
            channel = parsed.channel,
            source = TxnSource.SMS,
            fingerprint = parsed.fingerprint,
            referenceNo = parsed.referenceNo,
            reviewed = false,
            // A card payment moves money between the user's own accounts.
            excludeFromStats = cardPayment || (categoryOverride ?: parsed.suggestedCategory) == TRANSFER_CATEGORY,
        )
    }

    fun toBill(parsed: ParsedCardBill, accountId: Long, source: TxnSource = TxnSource.SMS): CardBillEntity =
        CardBillEntity(
            accountId = accountId,
            totalDue = parsed.totalDue,
            minimumDue = parsed.minimumDue,
            dueDate = parsed.dueDateEpochDay,
            statementDate = parsed.statementDateEpochDay,
            source = source,
            fingerprint = parsed.fingerprint,
        )

    /**
     * The manual transfer an imported SMS belongs to, if any. Direction matters: a bank debit ([debitSide])
     * is covered only by a transfer *from* [accountId] not yet matched to an SMS (no fingerprint); a credit
     * only by a transfer *to* it not yet matched (no referenceNo). Closest in time wins.
     */
    fun manualTransferFor(candidates: List<TransactionEntity>, accountId: Long, debitSide: Boolean, at: Long): TransactionEntity? =
        candidates
            .filter { it.type == TxnType.TRANSFER && it.source == TxnSource.MANUAL }
            .filter { if (debitSide) it.accountId == accountId && it.fingerprint == null else it.toAccountId == accountId && it.referenceNo == null }
            .minByOrNull { kotlin.math.abs(it.timestamp - at) }

    /** Fingerprint prefixes of the rows Kosh adds by itself: scheduled payments and bank-statement (CSV) rows. */
    const val SCHEDULED_PREFIX = "rec:"
    const val STATEMENT_PREFIX = "csv:"

    /** How far a scheduled payment's SMS can be from its scheduled day. */
    const val SCHEDULED_WINDOW_MILLIS = 3 * 86_400_000L

    /** How far a statement row (dated noon of its day) can be from the SMS for the same payment. */
    const val STATEMENT_WINDOW_MILLIS = 36 * 3_600_000L

    /**
     * A row Kosh added by itself that an imported SMS ([type], [reference], at [at]) confirms, so the money isn't
     * counted twice: a scheduled payment (within 3 days) or a bank-statement row (within a day) for the same amount on
     * the same side of [accountId], not yet matched to a message. A debit can also confirm a scheduled transfer out of
     * the account, a credit one into it. A statement row with a different reference number is another payment.
     * Closest in time wins. [candidates] are the rows of that amount around [at].
     */
    fun autoRowFor(candidates: List<TransactionEntity>, accountId: Long, type: TxnType, reference: String?, at: Long): TransactionEntity? =
        candidates.filter { row ->
            if (row.source != TxnSource.AUTO) return@filter false
            val fp = row.fingerprint.orEmpty()
            val scheduled = fp.startsWith(SCHEDULED_PREFIX)
            val statement = fp.startsWith(STATEMENT_PREFIX)
            val sameSide = when {
                // Matched transfers keep no "rec:" fingerprint (the debit's is stored instead), so a credit can still claim them.
                row.type == TxnType.TRANSFER && type == TxnType.EXPENSE -> scheduled && row.accountId == accountId
                row.type == TxnType.TRANSFER && type == TxnType.INCOME -> row.toAccountId == accountId && row.referenceNo == null
                else -> row.accountId == accountId && row.type == type && row.referenceNo?.startsWith(SMS_MARKER) != true &&
                    (scheduled || statement)
            }
            val refs = row.referenceNo?.takeIf { statement }
            val sameReference = reference == null || refs == null || refs.contains(reference) || reference.contains(refs)
            val window = if (statement) STATEMENT_WINDOW_MILLIS else SCHEDULED_WINDOW_MILLIS
            sameSide && sameReference && kotlin.math.abs(row.timestamp - at) <= window
        }.minByOrNull { kotlin.math.abs(it.timestamp - at) }

    /**
     * [row] (from [autoRowFor]) once [p]'s message on [accountId] is matched to it. A statement row becomes the SMS
     * transaction, keeping what the user set; a scheduled one keeps its schedule fingerprint and records the message
     * as `referenceNo = "sms:<fingerprint>"`; a scheduled transfer stores the debit's fingerprint and the credit's
     * marker, like a transfer entered by hand.
     */
    fun claim(row: TransactionEntity, p: ParsedTransaction, accountId: Long): TransactionEntity {
        val marker = SMS_MARKER + p.fingerprint
        return when {
            row.type == TxnType.TRANSFER && row.toAccountId == accountId && p.type == TxnType.INCOME -> row.copy(referenceNo = marker)
            row.type == TxnType.TRANSFER -> row.copy(fingerprint = p.fingerprint, timestamp = p.timestampMillis)
            row.fingerprint.orEmpty().startsWith(SCHEDULED_PREFIX) -> row.copy(referenceNo = marker, timestamp = p.timestampMillis)
            else -> row.copy(
                fingerprint = p.fingerprint,
                timestamp = p.timestampMillis,
                source = TxnSource.SMS,
                referenceNo = row.referenceNo ?: p.referenceNo,
                merchant = p.merchant ?: row.merchant,
                channel = if (row.channel == com.amitjha.kosh.model.TxnChannel.OTHER) p.channel else row.channel,
            )
        }
    }

    /**
     * Whether a payment already in Kosh stands for a scheduled occurrence on [day] (millis): an SMS that came before
     * the schedule was recorded, a statement row or one entered by hand, for the same amount on the same side of
     * [accountId] within 3 days. Another schedule's rows don't count.
     */
    fun scheduledAlreadyIn(candidates: List<TransactionEntity>, accountId: Long, type: TxnType, transfer: Boolean, amount: Paise, day: Long): Boolean =
        candidates.any { t ->
            t.amount == amount && t.accountId == accountId && kotlin.math.abs(t.timestamp - day) <= SCHEDULED_WINDOW_MILLIS &&
                !t.fingerprint.orEmpty().startsWith(SCHEDULED_PREFIX) &&
                (if (transfer) t.type == TxnType.TRANSFER || t.type == TxnType.EXPENSE else t.type == type)
        }

    /**
     * When the balance in a transaction SMS held: the message's own time when it arrived after the transaction's date
     * (a delayed "debited on 03-Oct ... Avl Bal" message reports the balance when it was sent, including everything
     * since). Anchoring at the transaction's date would count the transactions in between a second time.
     */
    fun balanceAnchor(p: ParsedTransaction, messageMillis: Long?): Long = maxOf(p.timestampMillis, messageMillis ?: p.timestampMillis)

    /** Marks a row an SMS was matched to without being stored itself: `referenceNo = "sms:<fingerprint>"`. */
    const val SMS_MARKER = "sms:"

    /** Whether [txn] counts as income or expense in summaries. */
    fun countsInStats(txn: TransactionEntity): Boolean =
        !txn.excludeFromStats && !txn.suspicious && txn.type != TxnType.TRANSFER && txn.categoryKey != TRANSFER_CATEGORY
}
