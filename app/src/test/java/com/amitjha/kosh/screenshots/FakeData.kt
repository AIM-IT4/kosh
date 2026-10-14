package com.amitjha.kosh.screenshots

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.DefaultCategories
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.finance.Insight
import com.amitjha.kosh.finance.Severity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.AccountWithBalance
import com.amitjha.kosh.repo.BillItem
import com.amitjha.kosh.repo.CategorySpend
import com.amitjha.kosh.repo.MonthSummary
import com.amitjha.kosh.repo.NetWorth
import com.amitjha.kosh.repo.TransactionItem
import com.amitjha.kosh.util.Period
import java.time.LocalDate
import java.time.ZoneId

/** Deterministic sample data for screenshot tests. Amounts in paise. */
object FakeData {
    private fun rupees(r: Long) = r * 100
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private fun at(day: Int, hour: Int) =
        today.withDayOfMonth(day).atTime(hour, 15).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    val categories = DefaultCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    fun cat(key: String) = categories.first { it.key == key }

    val hdfc = AccountEntity(id = 1, name = "HDFC Savings", type = AccountType.BANK, bank = Bank.HDFC.name, last4 = "4821", openingBalance = rupees(184_500))
    val icici = AccountEntity(id = 2, name = "ICICI Salary", type = AccountType.BANK, bank = Bank.ICICI.name, last4 = "1290", openingBalance = rupees(92_300))
    val kotakCard = AccountEntity(
        id = 3, name = "Kotak Myntra", type = AccountType.CREDIT_CARD, bank = Bank.KOTAK.name, last4 = "7731",
        creditLimit = rupees(200_000), statementDay = 5, dueDay = 24,
    )
    val dbsCard = AccountEntity(
        id = 4, name = "DBS Vantage", type = AccountType.CREDIT_CARD, bank = Bank.DBS.name, last4 = "0042",
        creditLimit = rupees(300_000), statementDay = 1, dueDay = 18,
    )

    val accounts = listOf(
        AccountWithBalance(hdfc, rupees(184_500), 0, null),
        AccountWithBalance(icici, rupees(92_300), 0, null),
        AccountWithBalance(kotakCard, 0, rupees(18_450), rupees(181_550)),
        AccountWithBalance(dbsCard, 0, rupees(42_900), rupees(257_100)),
    )

    private fun txn(id: Long, account: AccountEntity, type: TxnType, amount: Long, cat: String, merchant: String, day: Int, hour: Int, source: TxnSource = TxnSource.SMS, reviewed: Boolean = true) =
        TransactionItem(
            TransactionEntity(id = id, accountId = account.id, type = type, amount = rupees(amount), categoryKey = cat, merchant = merchant, timestamp = at(day, hour), source = source, reviewed = reviewed),
            cat(cat), account, null,
        )

    val recent = listOf(
        txn(1, kotakCard, TxnType.EXPENSE, 642, "food", "Swiggy", 14, 20, reviewed = false),
        txn(2, hdfc, TxnType.EXPENSE, 2_340, "groceries", "BigBasket", 14, 11),
        txn(3, icici, TxnType.INCOME, 185_000, "salary", "Acme Corp salary", 13, 9, source = TxnSource.SMS),
        txn(4, dbsCard, TxnType.EXPENSE, 499, "subscriptions", "Netflix", 12, 7),
        txn(5, hdfc, TxnType.EXPENSE, 320, "transport", "Uber", 12, 19, source = TxnSource.MANUAL),
        txn(6, kotakCard, TxnType.EXPENSE, 3_899, "shopping", "Myntra", 11, 22),
    )

    private val period = Period(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1))
    private fun spend(key: String, total: Long, share: Double) = CategorySpend(cat(key), key, rupees(total), 4, share)
    val summary = MonthSummary(
        period = period,
        income = rupees(185_000),
        expense = rupees(61_240),
        byCategory = listOf(
            spend("rent", 25_000, 0.41), spend("food", 9_820, 0.16), spend("shopping", 8_450, 0.14),
            spend("groceries", 7_310, 0.12), spend("transport", 4_120, 0.07), spend("subscriptions", 2_140, 0.03),
        ),
        incomeByCategory = emptyList(),
        daily = emptyList(),
        topMerchants = emptyList(),
        transactionCount = 54,
    )

    val netWorth = NetWorth(
        liquid = rupees(276_800),
        cardDebt = rupees(61_350),
        investments = rupees(1_342_000),
        investmentsByType = mapOf(InvestmentType.FD to rupees(500_000), InvestmentType.EPF to rupees(612_000), InvestmentType.NPS to rupees(230_000)),
    )

    val bills = listOf(
        BillItem(CardBillEntity(id = 1, accountId = 4, totalDue = rupees(42_900), minimumDue = rupees(2_145), dueDate = today.plusDays(2).toEpochDay(), source = TxnSource.EMAIL, fingerprint = "a"), dbsCard, 2),
        BillItem(CardBillEntity(id = 2, accountId = 3, totalDue = rupees(18_450), minimumDue = rupees(920), dueDate = today.plusDays(10).toEpochDay(), source = TxnSource.SMS, fingerprint = "b"), kotakCard, 10),
    )

    val insights = listOf(
        Insight("budget-food", Severity.WARNING, "Food is running ahead of budget", "At this pace you'll spend ₹13.2K on food this month, ₹3.2K over your ₹10K budget.", "food", 80),
        Insight("subs", Severity.TIP, "3 subscriptions found", "Netflix, Spotify and iCloud cost about ₹14.3K a year.", "subscriptions", 50),
    )

    val trend = listOf(15.1f, 15.2f, 15.0f, 15.4f, 15.5f, 15.3f, 15.6f, 15.8f, 15.7f, 15.9f)
}
