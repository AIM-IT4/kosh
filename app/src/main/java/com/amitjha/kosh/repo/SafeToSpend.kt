package com.amitjha.kosh.repo

import com.amitjha.kosh.finance.BudgetMath
import com.amitjha.kosh.model.AccountType
import java.time.LocalDate

/** "Safe to spend until payday": bank, cash and wallet money minus card bills and repeating payments due before payday. */
object SafeToSpend {

    suspend fun compute(
        accounts: List<AccountWithBalance>,
        bills: List<BillItem>,
        payDay: Int,
        recurring: RecurringRepository,
        today: LocalDate,
    ): BudgetMath.SafeToSpend? {
        val spendable = accounts.filter { !it.isPending && it.account.type != AccountType.CREDIT_CARD }
        if (spendable.isEmpty()) return null
        val payday = BudgetMath.nextPayday(today, payDay)
        val cardDues = bills.filter { !it.bill.paid && it.bill.totalDue > 0 }.mapNotNull { b ->
            val due = b.dueDate ?: return@mapNotNull null
            BudgetMath.Due(maxOf(due, today), b.bill.totalDue, "${b.card?.name ?: "Card"} bill")
        }
        val repeats = runCatching { recurring.upcoming(today, payday) }.getOrDefault(emptyList())
        return BudgetMath.safeToSpend(today, payDay, spendable.sumOf { it.balance }, cardDues + repeats)
    }
}
