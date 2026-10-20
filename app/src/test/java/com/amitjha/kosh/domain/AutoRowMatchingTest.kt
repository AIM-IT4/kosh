package com.amitjha.kosh.domain

import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.ParsedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A bank SMS confirming a payment Kosh already added by itself (a schedule or a statement row) is not counted twice. */
class AutoRowMatchingTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val t0 = 1_760_000_000_000L
    private val bank = 1L
    private val other = 2L
    private val rent = 20_000_00L

    private fun scheduled(id: Long, type: TxnType = TxnType.EXPENSE, at: Long = t0, to: Long? = null) = TransactionEntity(
        id = id, accountId = bank, toAccountId = to, type = type, amount = rent,
        categoryKey = if (type == TxnType.TRANSFER) SmsMapping.TRANSFER_CATEGORY else "rent", merchant = "Rent", timestamp = at,
        source = TxnSource.AUTO, fingerprint = "rec:5:${at / day}", reviewed = true,
    )

    private fun statementRow(id: Long, at: Long, ref: String? = null) = TransactionEntity(
        id = id, accountId = bank, type = TxnType.EXPENSE, amount = rent, categoryKey = "rent", timestamp = at,
        source = TxnSource.AUTO, fingerprint = "csv:$bank:2025-10-01:EXPENSE:$rent:123", referenceNo = ref, reviewed = false,
    )

    private fun sms(type: TxnType = TxnType.EXPENSE, at: Long = t0, ref: String? = null) = ParsedTransaction(
        bank = Bank.HDFC, accountLast4 = "4521", isCreditCard = false, isCardPayment = false, type = type, amount = rent,
        merchant = "LANDLORD", channel = TxnChannel.UPI, referenceNo = ref, timestampMillis = at, availableBalance = null,
        availableCreditLimit = null, suggestedCategory = "other", fingerprint = "fp-sms",
    )

    @Test
    fun theRentSmsTwoDaysLateConfirmsTheScheduledRent() {
        val row = scheduled(1)
        val p = sms(at = t0 + 2 * day)
        assertEquals(row, SmsMapping.autoRowFor(listOf(row), bank, p.type, null, p.timestampMillis))
        val claimed = SmsMapping.claim(row, p, bank)
        assertEquals("sms:fp-sms", claimed.referenceNo)
        assertEquals("the schedule keeps its fingerprint", row.fingerprint, claimed.fingerprint)
        assertEquals(p.timestampMillis, claimed.timestamp)
        assertNull("claimed once, never again", SmsMapping.autoRowFor(listOf(claimed), bank, p.type, null, p.timestampMillis))
    }

    @Test
    fun aPaymentFourDaysAwayOrTheOtherWayIsNotTheSchedule() {
        val row = scheduled(1)
        assertNull(SmsMapping.autoRowFor(listOf(row), bank, TxnType.EXPENSE, null, t0 + 4 * day))
        assertNull(SmsMapping.autoRowFor(listOf(row), bank, TxnType.INCOME, null, t0))
        assertNull("a manual entry is the user's, not Kosh's", SmsMapping.autoRowFor(listOf(row.copy(source = TxnSource.MANUAL)), bank, TxnType.EXPENSE, null, t0))
        assertNull("another account", SmsMapping.autoRowFor(listOf(row.copy(accountId = other)), bank, TxnType.EXPENSE, null, t0))
    }

    @Test
    fun aStatementRowBecomesTheSmsTransactionKeepingTheUsersCategory() {
        val row = statementRow(1, t0 + 12 * hour).copy(categoryKey = "housing", note = "Oct rent")
        val p = sms(at = t0 + 9 * hour, ref = "512345678901")
        assertEquals(row, SmsMapping.autoRowFor(listOf(row), bank, p.type, p.referenceNo, p.timestampMillis))
        val claimed = SmsMapping.claim(row, p, bank)
        assertEquals("fp-sms", claimed.fingerprint)
        assertEquals(TxnSource.SMS, claimed.source)
        assertEquals(p.timestampMillis, claimed.timestamp)
        assertEquals("housing", claimed.categoryKey)
        assertEquals("Oct rent", claimed.note)
        assertEquals("512345678901", claimed.referenceNo)
        assertNull(SmsMapping.autoRowFor(listOf(claimed), bank, p.type, p.referenceNo, p.timestampMillis))
    }

    @Test
    fun aStatementRowOnlyMatchesWithinADayAndTheSameReference() {
        val row = statementRow(1, t0 + 12 * hour, ref = "512345678901")
        assertNull(SmsMapping.autoRowFor(listOf(row), bank, TxnType.EXPENSE, null, t0 + 12 * hour + 2 * day))
        assertNull("another UPI reference is another payment",
            SmsMapping.autoRowFor(listOf(row), bank, TxnType.EXPENSE, "599999999999", t0 + 12 * hour))
        assertEquals(row, SmsMapping.autoRowFor(listOf(row), bank, TxnType.EXPENSE, "UPI/512345678901", t0 + 12 * hour))
    }

    @Test
    fun aScheduledTransferIsConfirmedByTheDebitAndThenTheCredit() {
        val row = scheduled(1, TxnType.TRANSFER, to = other)
        val debit = sms(TxnType.EXPENSE)
        assertEquals(row, SmsMapping.autoRowFor(listOf(row), bank, TxnType.EXPENSE, null, t0))
        val afterDebit = SmsMapping.claim(row, debit, bank)
        assertEquals("fp-sms", afterDebit.fingerprint)
        assertNull("a second debit is a different payment", SmsMapping.autoRowFor(listOf(afterDebit), bank, TxnType.EXPENSE, null, t0))

        val credit = sms(TxnType.INCOME).copy(fingerprint = "fp-credit")
        assertEquals(afterDebit, SmsMapping.autoRowFor(listOf(afterDebit), other, TxnType.INCOME, null, t0))
        val afterCredit = SmsMapping.claim(afterDebit, credit, other)
        assertEquals("sms:fp-credit", afterCredit.referenceNo)
        assertEquals("fp-sms", afterCredit.fingerprint)
        assertNull(SmsMapping.autoRowFor(listOf(afterCredit), other, TxnType.INCOME, null, t0))
    }

    @Test
    fun aScheduleIsNotRecordedWhenItsSmsCameFirst() {
        val smsRow = TransactionEntity(id = 9, accountId = bank, type = TxnType.EXPENSE, amount = rent, categoryKey = "rent",
            timestamp = t0 - 8 * hour, source = TxnSource.SMS, fingerprint = "fp-sms", reviewed = false)
        assertTrue(SmsMapping.scheduledAlreadyIn(listOf(smsRow), bank, TxnType.EXPENSE, false, rent, t0))
        assertTrue("a transfer schedule is paid by a debit too", SmsMapping.scheduledAlreadyIn(listOf(smsRow), bank, TxnType.TRANSFER, true, rent, t0))
        assertFalse(SmsMapping.scheduledAlreadyIn(listOf(smsRow), bank, TxnType.EXPENSE, false, rent, t0 + 5 * day))
        assertFalse(SmsMapping.scheduledAlreadyIn(listOf(smsRow), bank, TxnType.INCOME, false, rent, t0))
        assertFalse("another schedule's row doesn't stand in", SmsMapping.scheduledAlreadyIn(listOf(scheduled(2)), bank, TxnType.EXPENSE, false, rent, t0))
    }

    @Test
    fun aLateSmsAnchorsItsBalanceWhenItWasSent() {
        val p = sms(at = t0)
        assertEquals("sent two days after the debit", t0 + 2 * day, SmsMapping.balanceAnchor(p, t0 + 2 * day))
        assertEquals(t0, SmsMapping.balanceAnchor(p, t0 - 60_000))
        assertEquals(t0, SmsMapping.balanceAnchor(p, null))
    }
}
