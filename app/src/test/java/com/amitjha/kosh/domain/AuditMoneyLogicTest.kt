package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Regressions from the October 2026 audit of the SMS import and money logic. */
class AuditMoneyLogicTest {

    private val day = 86_400_000L
    private val t0 = 1_760_000_000_000L

    private val hdfcA = AccountEntity(id = 1, name = "HDFC Savings", type = AccountType.BANK, bank = "HDFC", last4 = "4521")
    private val hdfcB = AccountEntity(id = 2, name = "HDFC Salary", type = AccountType.BANK, bank = "HDFC", last4 = "1111")
    private val sbi = AccountEntity(id = 3, name = "SBI", type = AccountType.BANK, bank = "SBI", last4 = "9999")
    private val cash = AccountEntity(id = 4, name = "Cash", type = AccountType.CASH, bank = "OTHER")

    private fun imported(id: Long, account: Long, type: TxnType, amount: Long, at: Long, category: String = "other") =
        TransactionEntity(id = id, accountId = account, type = type, amount = amount, categoryKey = category, timestamp = at,
            source = TxnSource.SMS, reviewed = false)

    // ---- item 5: digitless messages must not create a new account every time
    @Test
    fun digitlessMessagesReuseOnePendingAccount() {
        assertNull("two real accounts and no pending one: create once", SmsMapping.matchAccount(listOf(hdfcA, hdfcB), Bank.HDFC, null, false))
        val pending = SmsMapping.newAccountFor(Bank.HDFC, null, false, t0).copy(id = 9)
        assertSame(pending, SmsMapping.matchAccount(listOf(hdfcA, hdfcB, pending), Bank.HDFC, null, false))
        assertSame("still reused with more accounts", pending,
            SmsMapping.matchAccount(listOf(hdfcA, hdfcB, pending, hdfcA.copy(id = 10, last4 = "2222")), Bank.HDFC, null, false))
    }

    // ---- item 6: pairing must not rewrite manual or reviewed rows, nor refunds/interest/cashback
    @Test
    fun manualAndReviewedRowsAreNeverPaired() {
        val manualRent = TransactionEntity(id = 1, accountId = cash.id, type = TxnType.EXPENSE, amount = 15_000_00,
            categoryKey = "rent", timestamp = t0, source = TxnSource.MANUAL, reviewed = true)
        val sbiCredit = imported(2, sbi.id, TxnType.INCOME, 15_000_00, t0 + day / 2)
        assertTrue(TransferPairing.findPairs(listOf(manualRent, sbiCredit), listOf(cash, sbi)).isEmpty())

        val reviewedDebit = imported(3, hdfcA.id, TxnType.EXPENSE, 500_00, t0).copy(reviewed = true)
        val credit = imported(4, sbi.id, TxnType.INCOME, 500_00, t0 + 3_600_000)
        assertTrue(TransferPairing.findPairs(listOf(reviewedDebit, credit), listOf(hdfcA, sbi)).isEmpty())
    }

    @Test
    fun refundsInterestAndCashbackAreNeverTransferLegs() {
        val debit = imported(1, hdfcA.id, TxnType.EXPENSE, 1_299_00, t0)
        for (cat in listOf("refund", "interest", "cashback", "salary")) {
            val credit = imported(2, sbi.id, TxnType.INCOME, 1_299_00, t0 + 3_600_000, category = cat)
            assertTrue("$cat must not pair", TransferPairing.findPairs(listOf(debit, credit), listOf(hdfcA, sbi)).isEmpty())
        }
        val plain = imported(3, sbi.id, TxnType.INCOME, 1_299_00, t0 + 3_600_000)
        assertEquals(1, TransferPairing.findPairs(listOf(debit, plain), listOf(hdfcA, sbi)).size)
    }

    // ---- item 4: manual transfers cover SMS by direction, once per side
    @Test
    fun manualTransferCoversOnlyTheMatchingDirectionOncePerSide() {
        val transfer = TransactionEntity(id = 1, accountId = hdfcA.id, toAccountId = sbi.id, type = TxnType.TRANSFER,
            amount = 5_000_00, categoryKey = "transfer", timestamp = t0, source = TxnSource.MANUAL, excludeFromStats = true)
        // SBI rent debit the next day is NOT covered (money left SBI, the transfer went HDFC -> SBI)
        assertNull(SmsMapping.manualTransferFor(listOf(transfer), sbi.id, debitSide = true, at = t0 + day))
        // SBI credit for the transfer IS covered
        assertSame(transfer, SmsMapping.manualTransferFor(listOf(transfer), sbi.id, debitSide = false, at = t0 + 600_000))
        // HDFC debit IS covered
        assertSame(transfer, SmsMapping.manualTransferFor(listOf(transfer), hdfcA.id, debitSide = true, at = t0))
        // once matched (fingerprint / referenceNo set), a second same-amount SMS is not swallowed
        assertNull(SmsMapping.manualTransferFor(listOf(transfer.copy(fingerprint = "abc")), hdfcA.id, true, t0))
        assertNull(SmsMapping.manualTransferFor(listOf(transfer.copy(referenceNo = "sms:abc")), sbi.id, false, t0))
    }

    // ---- minor: one month-label rule, consecutive and distinct, shared with the insights engine
    @Test
    fun periodLabelsAreConsecutiveForEveryStartDay() {
        for (startDay in listOf(1, 5, 15, 16, 17, 25, 28)) {
            var p = Period.containing(LocalDate.of(2026, 1, 20), startDay)
            val labels = (0 until 14).map { p.label.also { p = p.next() } }
            labels.zipWithNext().forEach { (a, b) ->
                assertEquals("start day $startDay: $labels", a.plusMonths(1), b)
            }
            // of() is the inverse of label
            for (m in labels) assertEquals("start day $startDay", m, Period.of(m, startDay).label)
        }
        assertEquals(YearMonth.of(2026, 10), Period.containing(LocalDate.of(2026, 10, 15), 25).label) // 25 Sep – 24 Oct
        assertEquals(YearMonth.of(2026, 10), Period.containing(LocalDate.of(2026, 10, 20), 5).label)  // 5 Oct – 4 Nov
    }
}
