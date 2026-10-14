package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Paise

/**
 * Balance of an account.
 *
 * - Bank, cash and wallet: [balance] is money held. [outstanding] is 0.
 * - Credit card: [outstanding] is what is owed (positive), [balance] = -outstanding, and
 *   [availableLimit] = limit - outstanding when a limit is known.
 */
data class AccountBalance(
    val balance: Paise,
    val outstanding: Paise,
    val availableLimit: Paise?,
)

object Balances {

    /**
     * @param netFlowSinceOpening income - expense - transfers out + transfers in since `openingDate`.
     * @param netFlowSinceReported the same net flow but only after `reportedAt`, or null if no SMS balance.
     *
     * When a bank SMS reported a balance, that balance is the anchor and only later transactions move it.
     * For credit cards the SMS reports the available limit, so outstanding = limit - available.
     */
    fun compute(account: AccountEntity, netFlowSinceOpening: Paise, netFlowSinceReported: Paise?): AccountBalance {
        val reported = account.reportedBalance
        val useReported = reported != null && account.reportedAt != null && netFlowSinceReported != null &&
            account.reportedAt >= account.openingDate
        return when (account.type) {
            AccountType.CREDIT_CARD -> {
                val limit = account.creditLimit
                val outstanding = if (useReported && limit != null) {
                    (limit - reported!!) - netFlowSinceReported!!
                } else {
                    // openingBalance of a card is the amount owed when it was added.
                    account.openingBalance - netFlowSinceOpening
                }
                AccountBalance(balance = -outstanding, outstanding = outstanding, availableLimit = limit?.let { it - outstanding })
            }
            else -> {
                val balance = if (useReported) reported!! + netFlowSinceReported!! else account.openingBalance + netFlowSinceOpening
                AccountBalance(balance = balance, outstanding = 0, availableLimit = null)
            }
        }
    }
}
