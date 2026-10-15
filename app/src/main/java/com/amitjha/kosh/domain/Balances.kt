package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Paise

/** Where an account's balance comes from, shown in the net worth breakdown. */
sealed interface BalanceSource {
    /** The amount the user entered at [at], moved by transactions after that moment. */
    data class Entered(val amount: Paise, val at: Long) : BalanceSource

    /** The latest balance (or available card limit) a bank SMS reported at [at], moved by later transactions. */
    data class Reported(val amount: Paise, val at: Long) : BalanceSource

    /** An account found in SMS that the user hasn't confirmed, and no SMS reported its balance. */
    data object Unknown : BalanceSource
}

/**
 * Balance of an account.
 *
 * - Bank, cash and wallet: [balance] is money held. [outstanding] is 0.
 * - Credit card: [outstanding] is what is owed (never negative), [balance] = -outstanding,
 *   [creditBalance] is any overpayment, and [availableLimit] = limit - outstanding when a limit is known.
 * - [counted] is false for accounts the user hasn't confirmed; they never enter net worth.
 */
data class AccountBalance(
    val balance: Paise,
    val outstanding: Paise,
    val availableLimit: Paise?,
    val creditBalance: Paise = 0,
    val source: BalanceSource,
    val counted: Boolean = true,
) {
    val known: Boolean get() = source !is BalanceSource.Unknown
}

/**
 * The balance rules.
 *
 * Bank, cash and wallet: if a bank SMS reported an available balance after the user entered theirs, that
 * reported balance plus later transactions; otherwise the entered balance plus transactions after the entry
 * date. Transactions dated before the entry date never change a balance; they only appear in history and stats.
 *
 * Credit card: the amount owed the user entered, plus spends minus payments after the entry date; or, when a
 * newer SMS reported the available limit and the limit is known, limit minus available limit plus later flow.
 * The amount owed is floored at 0 and any overpayment is reported separately as a credit balance.
 *
 * Accounts created automatically from SMS or e-mail (pending confirmation) are never counted: their imported
 * history has no known starting balance, so summing it would invent money.
 */
object Balances {

    /**
     * @param netFlowSinceOpening income - expense - transfers out + transfers in since `openingDate`.
     * @param netFlowSinceReported the same net flow but only after `reportedAt`, or null if no SMS balance.
     */
    fun compute(account: AccountEntity, netFlowSinceOpening: Paise, netFlowSinceReported: Paise?): AccountBalance {
        val reported = account.reportedBalance
        val reportedAt = account.reportedAt
        val hasReport = reported != null && reportedAt != null && netFlowSinceReported != null
        val pending = account.autoCreated

        return when (account.type) {
            AccountType.CREDIT_CARD -> {
                val limit = account.creditLimit
                val useReported = hasReport && limit != null && (pending || reportedAt!! >= account.openingDate)
                val (raw, source) = when {
                    useReported -> (limit!! - reported!!) - netFlowSinceReported!! to BalanceSource.Reported(reported, reportedAt!!)
                    pending -> 0L to BalanceSource.Unknown
                    // openingBalance of a card is the amount owed when it was added.
                    else -> account.openingBalance - netFlowSinceOpening to BalanceSource.Entered(account.openingBalance, account.openingDate)
                }
                val owed = raw.coerceAtLeast(0)
                AccountBalance(
                    balance = -owed,
                    outstanding = owed,
                    availableLimit = limit?.let { it - owed },
                    creditBalance = (-raw).coerceAtLeast(0),
                    source = source,
                    counted = !pending,
                )
            }
            else -> {
                val useReported = hasReport && (pending || reportedAt!! >= account.openingDate)
                val (balance, source) = when {
                    useReported -> reported!! + netFlowSinceReported!! to BalanceSource.Reported(reported, reportedAt!!)
                    pending -> 0L to BalanceSource.Unknown
                    else -> account.openingBalance + netFlowSinceOpening to BalanceSource.Entered(account.openingBalance, account.openingDate)
                }
                AccountBalance(balance = balance, outstanding = 0, availableLimit = null, source = source, counted = !pending)
            }
        }
    }
}
