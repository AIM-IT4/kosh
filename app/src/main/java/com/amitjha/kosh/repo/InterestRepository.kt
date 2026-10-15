package com.amitjha.kosh.repo

import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.finance.BalanceFlow
import com.amitjha.kosh.finance.CreditFrequency
import com.amitjha.kosh.finance.InterestEstimate
import com.amitjha.kosh.finance.RateConfidence
import com.amitjha.kosh.finance.SavingsInterest
import com.amitjha.kosh.finance.SavingsRateCard
import com.amitjha.kosh.finance.SavingsRates
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate

/** Savings interest of one bank account: the rate in use and Kosh's estimate on daily balances. */
data class AccountInterest(
    val account: AccountWithBalance,
    val rateCard: SavingsRateCard,
    /** True when the user set the rate; false when it comes from the built-in table. */
    val userRate: Boolean,
    val frequency: CreditFrequency,
    val estimate: InterestEstimate,
) {
    val needsConfirmation: Boolean get() = !userRate && rateCard.confidence == RateConfidence.UNVERIFIED
}

/** Savings interest across all counted bank accounts. Estimates only; never part of balances or net worth. */
data class InterestSummary(val items: List<AccountInterest>) {
    val accrued: Paise get() = items.sumOf { it.estimate.accrued }
    val projected: Paise get() = items.sumOf { it.estimate.projected }
    val nextCreditDate: LocalDate? get() = items.minOfOrNull { it.estimate.nextCreditDate }
}

/**
 * Estimates savings interest the way Indian banks compute it: on each day's closing balance, credited at the
 * end of each quarter (or month). Computed on read, so it is always current.
 */
class InterestRepository(private val db: KoshDatabase, private val accounts: AccountRepository) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<InterestSummary> =
        accounts.observeAccounts().mapLatest { list -> InterestSummary(list.mapNotNull { forAccount(it) }) }

    fun observeAccount(id: Long): Flow<AccountInterest?> = observe().map { s -> s.items.firstOrNull { it.account.id == id } }

    /** The rate card that applies to an account: the user's rate if set, else the bank's built-in rate. */
    fun rateCardFor(a: AccountWithBalance): Pair<SavingsRateCard, Boolean> {
        val user = a.account.interestRatePct
        return if (user != null) SavingsRates.overridden(a.bank, user) to true else SavingsRates.forBank(a.bank) to false
    }

    /** Only confirmed bank accounts with a known balance earn interest here. */
    suspend fun forAccount(a: AccountWithBalance, today: LocalDate = Time.today()): AccountInterest? {
        if (a.account.type != AccountType.BANK || !a.counted || !a.balanceKnown) return null
        val frequency = CreditFrequency.fromName(a.account.interestCreditFrequency)
        val (card, userRate) = rateCardFor(a)
        val since = SavingsInterest.periodContaining(today, frequency).previous(frequency).start
        val txns = db.transactions().forAccountSince(a.id, Time.startOfDay(since))
        val flows = txns.mapNotNull { flowFor(a.id, it) }
        val credits = txns.filter { it.accountId == a.id && it.type == TxnType.INCOME && it.categoryKey == "interest" }
            .map { BalanceFlow(Time.toDate(it.timestamp), it.amount) }
        val estimate = SavingsInterest.estimate(
            currentBalance = a.balance,
            flows = flows,
            today = today,
            entryDate = Time.toDate(a.account.openingDate),
            card = card,
            frequency = frequency,
            interestCredits = credits,
        )
        return AccountInterest(a, card, userRate, frequency, estimate)
    }

    /** Sets (or clears, with null) the user's rate and how often the bank credits interest. */
    suspend fun setRate(accountId: Long, ratePct: Double?, frequency: CreditFrequency) {
        val acc = db.accounts().byId(accountId) ?: return
        db.accounts().update(acc.copy(interestRatePct = ratePct, interestCreditFrequency = frequency.name))
    }

    private fun flowFor(accountId: Long, t: TransactionEntity): BalanceFlow? {
        val amount = when {
            t.accountId == accountId && t.type == TxnType.INCOME -> t.amount
            t.accountId == accountId && (t.type == TxnType.EXPENSE || t.type == TxnType.TRANSFER) -> -t.amount
            t.toAccountId == accountId && t.type == TxnType.TRANSFER -> t.amount
            else -> return null
        }
        return BalanceFlow(Time.toDate(t.timestamp), amount)
    }
}
