package com.amitjha.kosh.tools

import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DigestAndWidgetTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test fun nextRunLaterToday() {
        val now = ZonedDateTime.of(2026, 10, 15, 18, 30, 0, 0, kolkata)
        assertEquals(ZonedDateTime.of(2026, 10, 15, 21, 0, 0, 0, kolkata), DigestSchedule.nextRun(now, 21 * 60))
    }

    @Test fun nextRunTomorrowWhenPassedOrExact() {
        val at9 = ZonedDateTime.of(2026, 10, 15, 21, 0, 0, 0, kolkata)
        assertEquals(at9.plusDays(1), DigestSchedule.nextRun(at9, 21 * 60))
        val late = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, kolkata)
        assertEquals(ZonedDateTime.of(2027, 1, 1, 7, 15, 0, 0, kolkata), DigestSchedule.nextRun(late, 7 * 60 + 15))
    }

    @Test fun nextRunAcrossDstGap() {
        // 02:30 doesn't exist on 8 Mar 2026 in New York; it resolves to a real instant after now.
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny)
        val next = DigestSchedule.nextRun(now, 2 * 60 + 30)
        assertTrue(next.isAfter(now))
        assertEquals(LocalDate.of(2026, 3, 8), next.toLocalDate())
    }

    private fun txn(type: TxnType, amount: Long, day: Int, hour: Int = 12, exclude: Boolean = false) = TransactionEntity(
        accountId = 1, type = type, amount = amount, categoryKey = "x", excludeFromStats = exclude,
        timestamp = LocalDateTime.of(2026, 10, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    @Test fun digestFiguresAndText() {
        val txns = listOf(
            txn(TxnType.EXPENSE, 250_00, 15),
            txn(TxnType.EXPENSE, 1_000_00, 10),
            txn(TxnType.INCOME, 50_000_00, 1),
            txn(TxnType.TRANSFER, 5_000_00, 15),
            txn(TxnType.EXPENSE, 700_00, 15, exclude = true),
        )
        val f = DigestText.figures(LocalDate.of(2026, 10, 15), txns, 20_000_00, 3, ZoneOffset.UTC)
        assertEquals(DigestFigures(250_00, 18_750_00, 3), f)
        assertEquals("Today ₹250 spent · ₹18,750 left in budget · 3 to review", DigestText.build(f, hideAmounts = false))
        assertEquals("Today ₹ •••• spent · ₹ •••• left in budget · 3 to review", DigestText.build(f, hideAmounts = true))
        assertEquals("Today ₹0 spent · 0 to review", DigestText.build(DigestFigures(0, null, 0), false))
        assertEquals("Today ₹0 spent · ₹500 over budget · 1 to review", DigestText.build(DigestFigures(0, -500_00, 1), false))
    }

    @Test fun widgetFigures() {
        val period = Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
        val txns = listOf(txn(TxnType.INCOME, 1_000_00, 1), txn(TxnType.EXPENSE, 250_00, 2), txn(TxnType.TRANSFER, 900_00, 3))
        val noBudget = WidgetFigures.of(period, txns, null)
        assertEquals(1_000_00, noBudget.income)
        assertEquals(250_00, noBudget.spent)
        assertEquals(750_00, noBudget.saved)
        assertEquals(25, noBudget.barPercent)
        assertEquals(50, WidgetFigures.of(period, txns, 500_00).barPercent)
        assertEquals(0, WidgetFigures.of(period, emptyList(), null).barPercent)
    }

    @Test fun widgetPrivacy() {
        assertTrue(WidgetUpdater.showAmounts(hideAmounts = false, lockEnabled = false, amountsWhenLocked = false))
        assertFalse(WidgetUpdater.showAmounts(hideAmounts = true, lockEnabled = false, amountsWhenLocked = true))
        assertFalse(WidgetUpdater.showAmounts(hideAmounts = false, lockEnabled = true, amountsWhenLocked = false))
        assertTrue(WidgetUpdater.showAmounts(hideAmounts = false, lockEnabled = true, amountsWhenLocked = true))
    }
}
