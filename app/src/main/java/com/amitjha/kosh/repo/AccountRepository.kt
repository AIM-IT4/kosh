package com.amitjha.kosh.repo

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.domain.AccountBalance
import com.amitjha.kosh.domain.Balances
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Paise
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/** An account with its current balance (see [AccountBalance] for card semantics). */
data class AccountWithBalance(
    val account: AccountEntity,
    val balance: Paise,
    val outstanding: Paise,
    val availableLimit: Paise?,
) {
    val id: Long get() = account.id
    val bank: Bank get() = Bank.fromName(account.bank)
    val isCard: Boolean get() = account.type == AccountType.CREDIT_CARD
    /** Share of the credit limit in use, for cards with a known limit. */
    val utilisation: Double? get() = account.creditLimit?.takeIf { it > 0 }?.let { outstanding.toDouble() / it }
}

class AccountRepository(private val db: KoshDatabase) {

    private val dao get() = db.accounts()
    private val txns get() = db.transactions()

    /** Active accounts with balances, recomputed whenever accounts or transactions change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAccounts(): Flow<List<AccountWithBalance>> =
        combine(dao.observeActive(), txns.observeChanges()) { accounts, _ -> accounts }
            .mapLatest { accounts -> accounts.map { withBalance(it) } }

    fun observeAccount(id: Long): Flow<AccountWithBalance?> =
        observeAccounts().map { list -> list.firstOrNull { it.id == id } }

    suspend fun accounts(): List<AccountWithBalance> = dao.all().filter { !it.archived }.map { withBalance(it) }

    suspend fun withBalance(account: AccountEntity): AccountWithBalance {
        val sinceOpening = txns.netFlow(account.id, account.openingDate)
        val sinceReported = account.reportedAt?.let { txns.netFlow(account.id, it + 1) }
        val b = Balances.compute(account, sinceOpening, sinceReported)
        return AccountWithBalance(account, b.balance, b.outstanding, b.availableLimit)
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
     * Sets the balance the user sees in their bank app today, making it the new anchor:
     * later transactions move it from here.
     */
    suspend fun setCurrentBalance(id: Long, balance: Paise, at: Long = System.currentTimeMillis()) {
        val account = dao.byId(id) ?: return
        val anchored = if (account.type == AccountType.CREDIT_CARD) {
            // For cards the anchor is the available limit; without a limit, re-base the opening amount owed.
            val limit = account.creditLimit
            if (limit != null) account.copy(reportedBalance = limit - balance, reportedAt = at)
            else account.copy(openingBalance = balance, openingDate = at, reportedBalance = null, reportedAt = null)
        } else {
            account.copy(reportedBalance = balance, reportedAt = at)
        }
        dao.update(anchored.copy(autoCreated = false))
    }

    suspend fun confirmAutoCreated(id: Long, name: String) {
        dao.byId(id)?.let { dao.update(it.copy(name = name, autoCreated = false)) }
    }
}
