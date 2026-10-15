package com.amitjha.kosh.data

import com.amitjha.kosh.domain.InvestmentSpec
import com.amitjha.kosh.finance.Compounding
import com.amitjha.kosh.finance.NpsHolding
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.InvestmentRepository
import com.amitjha.kosh.util.Time
import java.time.LocalDate
import kotlin.random.Random

/**
 * Realistic sample data for showcasing the app. Only loaded when the user asks for it in Settings;
 * [clear] removes everything again.
 */
class DemoData(private val db: KoshDatabase, private val investments: InvestmentRepository) {

    suspend fun load(today: LocalDate = Time.today()) {
        val rnd = Random(42)
        val start = today.withDayOfMonth(1).minusMonths(3)
        val openingDate = Time.startOfDay(start)

        val hdfc = db.accounts().insert(account("HDFC Savings", AccountType.BANK, Bank.HDFC, "4521", rupees(85_000.0), openingDate))
        val icici = db.accounts().insert(account("ICICI Salary", AccountType.BANK, Bank.ICICI, "7788", rupees(42_000.0), openingDate))
        val cash = db.accounts().insert(AccountEntity(name = "Cash", type = AccountType.CASH, bank = Bank.OTHER.name, openingBalance = rupees(3_500.0), openingDate = openingDate))
        val hdfcCard = db.accounts().insert(
            account("HDFC Regalia", AccountType.CREDIT_CARD, Bank.HDFC, "9012", 0, openingDate)
                .copy(creditLimit = rupees(3_00_000.0), statementDay = 12, dueDay = 2),
        )
        val iciciCard = db.accounts().insert(
            account("ICICI Amazon Pay", AccountType.CREDIT_CARD, Bank.ICICI, "3344", 0, openingDate)
                .copy(creditLimit = rupees(1_50_000.0), statementDay = 18, dueDay = 5),
        )

        val spends = listOf(
            Triple("Swiggy", "food", 250.0..650.0), Triple("Zomato", "food", 300.0..800.0),
            Triple("BigBasket", "groceries", 900.0..2_800.0), Triple("Blinkit", "groceries", 200.0..900.0),
            Triple("Amazon", "shopping", 500.0..4_500.0), Triple("Myntra", "shopping", 900.0..3_200.0),
            Triple("Uber", "transport", 180.0..650.0), Triple("Indian Oil", "fuel", 1_500.0..3_000.0),
            Triple("PVR Cinemas", "entertainment", 500.0..1_400.0), Triple("Apollo Pharmacy", "health", 200.0..1_800.0),
        )

        var day = start
        while (!day.isAfter(today)) {
            val ts = { hour: Int -> Time.startOfDay(day) + hour * 3_600_000L + rnd.nextLong(0, 3_000_000L) }
            // Salary on the 1st, rent on the 5th, subscriptions monthly.
            if (day.dayOfMonth == 1) {
                txn(icici, TxnType.INCOME, 1_45_000.0, "salary", "ACME Technologies", ts(10), TxnChannel.NEFT)
                txn(icici, TxnType.EXPENSE, 12_500.0, "investment", "SIP - Parag Parikh Flexi Cap", ts(11), TxnChannel.AUTO_DEBIT)
            }
            if (day.dayOfMonth == 5) txn(hdfc, TxnType.EXPENSE, 32_000.0, "rent", "Rent - Landlord", ts(9), TxnChannel.UPI)
            if (day.dayOfMonth == 7) txn(hdfcCard, TxnType.EXPENSE, 649.0, "subscriptions", "Netflix", ts(8), TxnChannel.CARD)
            if (day.dayOfMonth == 9) txn(iciciCard, TxnType.EXPENSE, 119.0, "subscriptions", "Spotify", ts(8), TxnChannel.CARD)
            if (day.dayOfMonth == 10) txn(hdfc, TxnType.EXPENSE, 2_350.0, "bills", "Tata Power", ts(19), TxnChannel.UPI)
            if (day.dayOfMonth == 14) txn(hdfc, TxnType.EXPENSE, 799.0, "bills", "Airtel", ts(20), TxnChannel.UPI)

            repeat(rnd.nextInt(0, 3)) {
                val (merchant, cat, range) = spends[rnd.nextInt(spends.size)]
                val acct = listOf(hdfcCard, iciciCard, hdfc, icici)[rnd.nextInt(4)]
                val amount = range.start + rnd.nextDouble() * (range.endInclusive - range.start)
                txn(acct, TxnType.EXPENSE, Math.round(amount).toDouble(), cat, merchant, ts(rnd.nextInt(9, 22)),
                    if (acct == hdfcCard || acct == iciciCard) TxnChannel.CARD else TxnChannel.UPI)
            }
            if (rnd.nextInt(10) == 0) txn(cash, TxnType.EXPENSE, 150.0 + rnd.nextInt(400), "food", "Chai & snacks", ts(17), TxnChannel.OTHER)
            day = day.plusDays(1)
        }
        // A refund and some cashback.
        txn(iciciCard, TxnType.INCOME, 1_299.0, "refund", "Amazon", Time.startOfDay(today.minusDays(6)) + 40_000_000, TxnChannel.CARD)
        txn(icici, TxnType.INCOME, 412.0, "cashback", "Amazon Pay cashback", Time.startOfDay(today.minusDays(12)) + 50_000_000, TxnChannel.OTHER)

        // Card bills.
        db.bills().insertIgnore(
            CardBillEntity(accountId = hdfcCard, totalDue = rupees(28_430.0), minimumDue = rupees(1_430.0),
                dueDate = today.plusDays(4).toEpochDay(), statementDate = today.minusDays(16).toEpochDay(),
                source = TxnSource.SMS, fingerprint = "demo-hdfc-bill"),
        )
        db.bills().insertIgnore(
            CardBillEntity(accountId = iciciCard, totalDue = rupees(9_870.0), minimumDue = rupees(500.0),
                dueDate = today.plusDays(11).toEpochDay(), statementDate = today.minusDays(9).toEpochDay(),
                source = TxnSource.SMS, fingerprint = "demo-icici-bill"),
        )

        // Budgets and goals.
        db.budgets().upsert(BudgetEntity(categoryKey = null, monthlyLimit = rupees(60_000.0)))
        db.budgets().upsert(BudgetEntity(categoryKey = "food", monthlyLimit = rupees(6_000.0)))
        db.budgets().upsert(BudgetEntity(categoryKey = "shopping", monthlyLimit = rupees(8_000.0)))
        db.budgets().upsert(BudgetEntity(categoryKey = "groceries", monthlyLimit = rupees(9_000.0)))
        db.goals().upsert(GoalEntity(name = "Emergency fund", target = rupees(4_00_000.0), saved = rupees(2_35_000.0), deadline = today.plusMonths(10).toEpochDay(), icon = "shield"))
        db.goals().upsert(GoalEntity(name = "Europe trip", target = rupees(3_50_000.0), saved = rupees(90_000.0), deadline = today.plusMonths(14).toEpochDay(), icon = "flight"))

        // Investments.
        investments.addFd(
            "HDFC FD 2025", Bank.HDFC.displayName,
            InvestmentSpec.Fd(rupees(2_00_000.0), 7.25, today.minusMonths(11), today.plusDays(20), Compounding.QUARTERLY),
        )
        investments.addFd(
            "ICICI Tax Saver FD", Bank.ICICI.displayName,
            InvestmentSpec.Fd(rupees(1_50_000.0), 7.0, today.minusYears(2), today.plusYears(3), Compounding.QUARTERLY),
        )
        investments.addEpf(
            "EPF - ACME", InvestmentSpec.Epf(rupees(6_42_000.0), today.withDayOfMonth(1).minusMonths(6), rupees(7_800.0), rupees(2_386.0)),
        )
        investments.addNps(
            "NPS Tier I",
            InvestmentSpec.Nps(
                holdings = listOf(
                    NpsHolding("SM008001", "HDFC Pension Fund Scheme E - Tier I", 3_850.0, 51.10, today),
                    NpsHolding("SM008002", "HDFC Pension Fund Scheme C - Tier I", 4_200.0, 27.40, today),
                    NpsHolding("SM008003", "HDFC Pension Fund Scheme G - Tier I", 3_900.0, 26.90, today),
                ),
                monthlyContribution = rupees(5_000.0),
                allocation = mapOf("SM008001" to 0.5, "SM008002" to 0.25, "SM008003" to 0.25),
                investedAmount = rupees(2_95_000.0),
            ),
        )
    }

    /** Removes all user data (categories are re-seeded). */
    suspend fun clear() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.clearAllTables() }
        db.categories().insertAll(DefaultCategories.all)
    }

    private fun account(name: String, type: AccountType, bank: Bank, last4: String, opening: Long, openingDate: Long) =
        AccountEntity(name = name, type = type, bank = bank.name, last4 = last4, openingBalance = opening, openingDate = openingDate, colorArgb = bank.brandColor)

    private suspend fun txn(account: Long, type: TxnType, rupees: Double, category: String, merchant: String, at: Long, channel: TxnChannel) {
        db.transactions().insert(
            TransactionEntity(
                accountId = account, type = type, amount = rupees(rupees), categoryKey = category, merchant = merchant,
                timestamp = at, channel = channel, source = TxnSource.MANUAL,
            ),
        )
    }

    private fun rupees(r: Double) = Money.fromRupees(r)
}
