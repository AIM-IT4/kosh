package com.amitjha.kosh.domain

import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.TransactionRepository
import com.amitjha.kosh.tools.WidgetFigures
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MoneyInTest {

    private val period = Period.of(YearMonth.of(2026, 10))
    private fun rupees(r: Long) = r * 100
    private fun txn(day: Int, type: TxnType, rupees: Long, cat: String, merchant: String? = null, month: Int = 10) = TransactionEntity(
        accountId = 1, type = type, amount = rupees(rupees), categoryKey = cat, merchant = merchant,
        timestamp = Time.startOfDay(LocalDate.of(2026, month, day)) + 3_600_000,
    )

    @Test
    fun onlyEarningsCountAsIncome() {
        assertTrue(MoneyIn.isEarned(txn(1, TxnType.INCOME, 1, "salary")))
        assertTrue(MoneyIn.isEarned(txn(1, TxnType.INCOME, 1, "interest")))
        assertTrue(MoneyIn.isEarned(txn(1, TxnType.INCOME, 1, "business")))
        assertTrue(MoneyIn.isEarned(txn(1, TxnType.INCOME, 1, "my_tuition_classes")))
        assertFalse(MoneyIn.isEarned(txn(1, TxnType.INCOME, 1, "refund")))
        assertFalse(MoneyIn.isEarned(txn(1, TxnType.INCOME, 1, "cashback")))
        assertFalse(MoneyIn.isEarned(txn(1, TxnType.INCOME, 1, "other_income")))
        assertFalse(MoneyIn.isEarned(txn(1, TxnType.EXPENSE, 1, "salary")))
    }

    @Test
    fun aMonthWithRefundsCashbackAndFriendsSettlingUp() {
        val txns = listOf(
            txn(1, TxnType.INCOME, 1_00_000, "salary", "ACME Technologies"),
            txn(2, TxnType.INCOME, 1_200, "interest"),
            txn(3, TxnType.EXPENSE, 5_000, "shopping", "Amazon"),
            txn(4, TxnType.EXPENSE, 3_000, "food", "Swiggy"),
            txn(6, TxnType.INCOME, 5_000, "refund", "Amazon"), // the order was returned
            txn(7, TxnType.INCOME, 300, "cashback", "Amazon Pay cashback"),
            txn(8, TxnType.INCOME, 1_500, "other_income", "Rahul Sharma"), // a friend paying back
        )
        val s = TransactionRepository.summarise(period, txns, emptyMap())
        assertEquals(rupees(1_01_200), s.income)
        assertEquals(rupees(2_700), s.expense) // 8,000 spent − 5,000 refund − 300 cashback
        assertEquals(rupees(98_500), s.savings)
        assertEquals(rupees(5_300), s.givenBack)
        assertEquals(rupees(1_500), s.received)
        // The refund comes off Shopping (fully returned, so it drops out); the cashback is shared out.
        assertEquals(listOf("food"), s.byCategory.map { it.categoryKey })
        assertEquals(rupees(2_700), s.byCategory.single().total)
        assertEquals(1.0, s.byCategory.single().share, 1e-9)
        assertEquals(listOf("Swiggy"), s.topMerchants.map { it.merchant })
        assertEquals(listOf("salary", "interest"), s.incomeByCategory.map { it.categoryKey })

        val w = WidgetFigures.of(period, txns, overallBudget = null)
        assertEquals(s.income, w.income)
        assertEquals(s.expense, w.spent)
    }

    @Test
    fun aRefundForLastMonthsPurchaseComesOffThatCategory() {
        val history = listOf(txn(28, TxnType.EXPENSE, 4_000, "travel", "IRCTC", month = 9))
        val expenses = listOf(txn(2, TxnType.EXPENSE, 6_000, "travel", "MakeMyTrip"), txn(3, TxnType.EXPENSE, 2_000, "food", "Zomato"))
        val net = MoneyIn.netByCategory(expenses, listOf(txn(5, TxnType.INCOME, 4_000, "refund", "IRCTC")), history + expenses)
        assertEquals(rupees(2_000), net["travel"])
        assertEquals(rupees(2_000), net["food"])
    }

    @Test
    fun whatCantBePlacedIsSharedAndNothingGoesNegative() {
        val expenses = listOf(txn(2, TxnType.EXPENSE, 750, "food"), txn(3, TxnType.EXPENSE, 250, "fuel"))
        val shared = MoneyIn.netByCategory(expenses, listOf(txn(4, TxnType.INCOME, 100, "cashback")))
        assertEquals(rupees(675), shared["food"])
        assertEquals(rupees(225), shared["fuel"])
        assertEquals(rupees(900), shared.values.sum())

        val tooMuch = MoneyIn.netByCategory(expenses, listOf(txn(4, TxnType.INCOME, 5_000, "refund", "Unknown shop")))
        assertTrue(tooMuch.values.all { it == 0L })
        assertEquals(0L, MoneyIn.netSpent(expenses, listOf(txn(4, TxnType.INCOME, 5_000, "refund"))))

        // Paise add up exactly: ₹1 off three equal categories.
        val thirds = MoneyIn.netByCategory(
            listOf(txn(1, TxnType.EXPENSE, 1, "a"), txn(1, TxnType.EXPENSE, 1, "b"), txn(1, TxnType.EXPENSE, 1, "c")),
            listOf(txn(2, TxnType.INCOME, 1, "cashback")),
        )
        assertEquals(rupees(2), thirds.values.sum())
    }
}
