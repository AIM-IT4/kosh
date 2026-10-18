package com.amitjha.kosh.repo

import androidx.room.withTransaction
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.domain.AccountBalance
import com.amitjha.kosh.domain.BalanceSource
import com.amitjha.kosh.domain.Balances
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Paise
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/** An account with its current balance (see [AccountBalance] for card semantics and [Balances] for the rules). */
data class AccountWithBalance(
    val account: AccountEntity,
    val balance: Paise,
    val outstanding: Paise,
    val availableLimit: Paise?,
    val creditBalance: Paise = 0,
    val source: BalanceSource = BalanceSource.Entered(account.openingBalance, account.openingDate),
    /** Transactions that moved the balance since the [source] anchor. */
    val transactionsSinceAnchor: Int = 0,
) {
    val id: Long get() = account.id
    val bank: Bank get() = Bank.fromName(account.bank)
    val isCard: Boolean get() = account.type == AccountType.CREDIT_CARD

    /** Found in SMS or e-mail and not yet confirmed by the user; never counted in net worth. */
    val isPending: Boolean get() = account.autoCreated

    /** Whether this account contributes to net worth and balance-based insights. */
    val counted: Boolean get() = !isPending

    val balanceKnown: Boolean get() = source !is BalanceSource.Unknown

    /** Share of the credit limit in use, for cards with a known limit. */
    val utilisation: Double? get() = account.creditLimit?.takeIf { it > 0 }?.let { outstanding.toDouble() / it }
}

class AccountRepository(private val db: KoshDatabase) {

    private val dao get() = db.accounts()
    private val txns get() = db.transactions()

    /** Active accounts with balances (pending ones included, flagged), recomputed on any change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAccounts(): Flow<List<AccountWithBalance>> =
        combine(dao.observeActive(), txns.observeChanges()) { accounts, _ -> accounts }
            .mapLatest { accounts -> accounts.map { withBalance(it) } }

    /** Accounts found in SMS/e-mail that the user still has to confirm, merge or ignore. */
    fun observePending(): Flow<List<AccountWithBalance>> = observeAccounts().map { list -> list.filter { it.isPending } }

    fun observeAccount(id: Long): Flow<AccountWithBalance?> =
        observeAccounts().map { list -> list.firstOrNull { it.id == id } }

    suspend fun accounts(): List<AccountWithBalance> = dao.all().filter { !it.archived }.map { withBalance(it) }

    suspend fun withBalance(account: AccountEntity): AccountWithBalance {
        val sinceOpening = txns.netFlow(account.id, account.openingDate)
        val sinceReported = account.reportedAt?.let { txns.netFlow(account.id, it + 1) }
        val b = Balances.compute(account, sinceOpening, sinceReported)
        val anchorTime = when (val s = b.source) {
            is BalanceSource.Entered -> s.at
            is BalanceSource.Reported -> s.at + 1
            BalanceSource.Unknown -> null
        }
        val count = anchorTime?.let { txns.countSince(account.id, it) } ?: 0
        return AccountWithBalance(account, b.balance, b.outstanding, b.availableLimit, b.creditBalance, b.source, count)
    }

    suspend fun byId(id: Long): AccountEntity? = dao.byId(id)

    suspend fun add(account: AccountEntity): Long = dao.insert(account)

    suspend fun update(account: AccountEntity) = dao.update(account)

    /** Archiving keeps the history; deleting removes the account and its transactions. */
    suspend fun archive(id: Long) {
        dao.byId(id)?.let { dao.update(it.copy(archived = true)) }
    }

    suspend fun delete(id: Long) {
        dao.byId(id)?.let { dao.delete(it) }
    }

    /**
     * Sets the balance the user sees in their bank app today (for a card: the amount owed), making it the
     * new anchor: only later transactions move it. An older SMS-reported balance no longer applies.
     */
    suspend fun setCurrentBalance(id: Long, balance: Paise, at: Long = System.currentTimeMillis()) {
        val account = dao.byId(id) ?: return
        dao.update(
            account.copy(
                openingBalance = balance,
                openingDate = at,
                reportedBalance = null,
                reportedAt = null,
                autoCreated = false,
            ),
        )
    }

    suspend fun confirmAutoCreated(id: Long, name: String) {
        dao.byId(id)?.let { dao.update(it.copy(name = name, autoCreated = false)) }
    }

    /**
     * "Add as new account": the user confirms a pending account and tells us its balance today
     * (for a card, the amount owed). History before today stays visible but no longer moves the balance.
     */
    suspend fun confirmPendingAsNew(id: Long, name: String, currentBalance: Paise, at: Long = System.currentTimeMillis()) {
        val account = dao.byId(id) ?: return
        dao.update(
            account.copy(
                name = name,
                autoCreated = false,
                openingBalance = currentBalance,
                openingDate = at,
                reportedBalance = null,
                reportedAt = null,
            ),
        )
    }

    /**
     * "This is my <account>": moves the pending account's transactions and bills into [targetId], remembers the
     * pending account's digits on the target (so later messages with them go straight there), keeps the newer SMS
     * balance report, and deletes the pending account.
     */
    suspend fun mergePending(pendingId: Long, targetId: Long) {
        if (pendingId == targetId) return
        db.withTransaction {
            val pending = dao.byId(pendingId) ?: return@withTransaction
            val target = dao.byId(targetId) ?: return@withTransaction
            txns.moveAccount(pendingId, targetId)
            txns.moveToAccount(pendingId, targetId)
            db.bills().moveAccount(pendingId, targetId)
            val newerReport = pending.reportedAt != null && pending.reportedAt > (target.reportedAt ?: Long.MIN_VALUE)
            var merged = target.copy(
                last4 = target.last4 ?: pending.last4,
                reportedBalance = if (newerReport) pending.reportedBalance else target.reportedBalance,
                reportedAt = if (newerReport) pending.reportedAt else target.reportedAt,
                creditLimit = target.creditLimit ?: pending.creditLimit,
            )
            // Later messages with the pending account's digits (a debit card, another mask) go straight to the target.
            pending.allDigits.forEach { merged = merged.plusOtherDigits(it) }
            dao.update(merged)
            dao.delete(pending)
        }
    }

    /**
     * "Ignore": hides a pending account (for example someone else's account mentioned in an SMS). It stays out
     * of balances and lists, and its transactions stop counting as income or spending.
     */
    suspend fun ignorePending(id: Long) {
        txns.excludeAccountFromStats(id)
        archive(id)
    }

    fun observeIgnored(): Flow<List<AccountEntity>> = dao.observeIgnored()

    /**
     * Stops ignoring an account: it shows again and its transactions count (transfers aside). One Kosh found in SMS
     * goes back to the accounts to confirm or merge.
     */
    suspend fun restore(id: Long) {
        db.withTransaction {
            val account = dao.byId(id) ?: return@withTransaction
            dao.update(account.copy(archived = false))
            txns.includeAccountInStats(id)
        }
    }
}
