package com.amitjha.kosh.domain

import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSettlingTest {

    private val dayMs = 86_400_000L
    private val dayOf: (Long) -> Long = { it / dayMs }
    private val card = 7L
    private val bank = 1L
    private val sep5 = 20_336L
    private val oct5 = sep5 + 30

    private fun rupees(r: Long) = r * 100

    private fun bill(id: Long, total: Long, statement: Long?, due: Long? = statement?.plus(20)) = CardBillEntity(
        id = id, accountId = card, totalDue = total, dueDate = due, statementDate = statement,
        source = TxnSource.SMS, fingerprint = "b$id", createdAt = (statement ?: due ?: 0) * dayMs,
    )

    private fun cardPayment(day: Long, amount: Long) = TransactionEntity(
        accountId = card, type = TxnType.INCOME, amount = amount, categoryKey = SmsMapping.TRANSFER_CATEGORY,
        timestamp = day * dayMs + 3_600_000, source = TxnSource.SMS,
    )

    private fun settled(bills: List<CardBillEntity>, rows: List<TransactionEntity>) =
        BillSettling.settled(bills, BillSettling.payments(rows, card), dayOf).map { it.id }.toSet()

    @Test
    fun aBillPaidInTwoPartsIsPaid() {
        val b = bill(1, rupees(10_000), sep5)
        assertEquals(emptySet<Long>(), settled(listOf(b), listOf(cardPayment(sep5 + 3, rupees(5_000)))))
        assertEquals(setOf(1L), settled(listOf(b), listOf(cardPayment(sep5 + 3, rupees(5_000)), cardPayment(sep5 + 12, rupees(5_000)))))
    }

    @Test
    fun paymentsBeforeTheStatementDoNotCount() {
        val b = bill(1, rupees(8_000), oct5)
        assertEquals(emptySet<Long>(), settled(listOf(b), listOf(cardPayment(oct5 - 10, rupees(10_000)))))
    }

    @Test
    fun roundingDownByAFewPaiseStillSettles() {
        val b = bill(1, 10_234_56, sep5)
        assertEquals(setOf(1L), settled(listOf(b), listOf(cardPayment(sep5 + 2, rupees(10_234)))))
        assertEquals(emptySet<Long>(), settled(listOf(b), listOf(cardPayment(sep5 + 2, rupees(10_000)))))
    }

    @Test
    fun payingTheNewerStatementSettlesTheOlderOne() {
        // September's ₹10,000 was never paid, so October's statement carries it: ₹18,000 in all.
        val sept = bill(1, rupees(10_000), sep5)
        val oct = bill(2, rupees(18_000), oct5)
        assertEquals(setOf(1L, 2L), settled(listOf(sept, oct), listOf(cardPayment(oct5 + 4, rupees(18_000)))))
        // Paying only September's amount late settles September, not October.
        assertEquals(setOf(1L), settled(listOf(sept, oct), listOf(cardPayment(oct5 + 4, rupees(10_000)))))
    }

    @Test
    fun aTransferToTheCardCountsButRefundsAndSpendsDoNot() {
        val b = bill(1, rupees(6_000), sep5)
        val transfer = TransactionEntity(accountId = bank, toAccountId = card, type = TxnType.TRANSFER, amount = rupees(3_000),
            categoryKey = SmsMapping.TRANSFER_CATEGORY, timestamp = (sep5 + 5) * dayMs)
        val refund = cardPayment(sep5 + 6, rupees(3_000)).copy(categoryKey = "shopping")
        val spend = cardPayment(sep5 + 6, rupees(3_000)).copy(type = TxnType.EXPENSE)
        assertEquals(emptySet<Long>(), settled(listOf(b), listOf(transfer, refund, spend)))
        assertEquals(setOf(1L), settled(listOf(b), listOf(transfer, cardPayment(sep5 + 7, rupees(3_000)))))
    }

    @Test
    fun withoutAStatementDatePaymentsCountFromTwentyDaysBeforeTheDueDate() {
        val b = bill(1, rupees(4_000), statement = null, due = oct5)
        assertEquals(oct5 - 20, BillSettling.countsFrom(b, oct5))
        assertEquals(setOf(1L), settled(listOf(b), listOf(cardPayment(oct5 - 15, rupees(4_000)))))
        assertEquals(emptySet<Long>(), settled(listOf(b), listOf(cardPayment(oct5 - 25, rupees(4_000)))))
    }

    @Test
    fun onlyTheNewestUnpaidBillIsOwedAndLongOverdueOnesAreDropped() {
        val sept = bill(1, rupees(10_000), sep5)
        val oct = bill(2, rupees(18_000), oct5)
        assertEquals(2L, BillSettling.owed(listOf(sept, oct), today = oct5 + 5)?.id)
        // Due on Sep 25 and never seen paid: by Nov 1 a newer statement must have replaced it.
        assertEquals(1L, BillSettling.owed(listOf(sept), today = sep5 + 30)?.id)
        assertNull(BillSettling.owed(listOf(sept), today = sep5 + 20 + BillSettling.STALE_DAYS + 1))
        assertTrue(BillSettling.settled(emptyList(), emptyList(), dayOf).isEmpty())
    }
}
