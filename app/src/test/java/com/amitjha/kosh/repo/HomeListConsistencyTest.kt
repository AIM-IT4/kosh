package com.amitjha.kosh.repo

import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * A figure on Home and the list it opens must agree: Activity totals are computed from exactly the rows
 * shown, with the same summarise() rules (transfers, excluded and held-for-review rows don't count).
 */
class HomeListConsistencyTest {

    private fun txn(day: LocalDate, type: TxnType, amount: Long, cat: String, reviewed: Boolean = true,
                    suspicious: Boolean = false, exclude: Boolean = false) =
        TransactionEntity(
            accountId = 1, type = type, amount = amount, categoryKey = cat, timestamp = Time.startOfDay(day) + 3_600_000,
            reviewed = reviewed, suspicious = suspicious, excludeFromStats = exclude,
        )

    // A salary-day month: 25 Sep – 24 Oct.
    private val period = Period.containing(LocalDate.of(2026, 10, 15), monthStartDay = 25)
    private val txns = listOf(
        txn(LocalDate.of(2026, 9, 25), TxnType.INCOME, 171_000_00, "salary"),
        txn(LocalDate.of(2026, 9, 28), TxnType.EXPENSE, 120_00, "groceries"),
        txn(LocalDate.of(2026, 10, 3), TxnType.EXPENSE, 111_00, "groceries", reviewed = false),
        txn(LocalDate.of(2026, 10, 4), TxnType.EXPENSE, 50_000_00, "groceries", suspicious = true),
        txn(LocalDate.of(2026, 10, 5), TxnType.EXPENSE, 900_00, "groceries", exclude = true),
        txn(LocalDate.of(2026, 10, 6), TxnType.TRANSFER, 10_000_00, "groceries"),
        txn(LocalDate.of(2026, 10, 7), TxnType.EXPENSE, 2_000_00, "food"),
        txn(LocalDate.of(2026, 9, 20), TxnType.EXPENSE, 700_00, "groceries"), // previous month
    )

    @Test
    fun categoryFigureEqualsTheTotalOfItsList() {
        val home = TransactionRepository.summarise(period, txns, emptyMap())
        val homeGroceries = home.byCategory.first { it.categoryKey == "groceries" }.total
        // What Activity shows when Groceries is tapped: the same period, filtered by category.
        val listed = txns.filter { Time.toDate(it.timestamp) in period && it.categoryKey == "groceries" }
        val listTotals = TransactionRepository.summarise(period, listed, emptyMap())
        assertEquals(231_00L, homeGroceries) // reviewed and unreviewed count; held, excluded and transfers don't
        assertEquals(homeGroceries, listTotals.expense)
    }

    @Test
    fun monthTotalsMatchHome() {
        val home = TransactionRepository.summarise(period, txns, emptyMap())
        val listed = txns.filter { Time.toDate(it.timestamp) in period }
        val list = TransactionRepository.summarise(period, listed, emptyMap())
        assertEquals(home.income, list.income)
        assertEquals(home.expense, list.expense)
        assertEquals(171_000_00L, home.income)
        assertEquals(2_231_00L, home.expense)
    }

    @Test
    fun salaryDayPeriodBoundaries() {
        assertEquals(LocalDate.of(2026, 9, 25), period.start)
        assertEquals(LocalDate.of(2026, 10, 25), period.endExclusive)
    }
}
