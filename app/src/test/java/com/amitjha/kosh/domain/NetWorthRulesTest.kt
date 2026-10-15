package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.DataRepairs
import com.amitjha.kosh.sms.ImportResult
import com.amitjha.kosh.ui.transactions.groupByPayee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v1.4 balance rules: pending accounts never count, card dues never go negative, implausible amounts are held. */
class NetWorthRulesTest {

    private fun rupees(r: Long) = r * 100

    @Test
    fun pendingAccountWithoutSmsBalanceIsUnknownAndNotCounted() {
        // An account created from SMS with 180 days of credits: before v1.4 this summed into a fake balance.
        val pending = AccountEntity(id = 1, name = "HDFC Account", type = AccountType.BANK, bank = "HDFC", openingDate = 0, autoCreated = true)
        val b = Balances.compute(pending, netFlowSinceOpening = rupees(1_20_00_000), netFlowSinceReported = null)
        assertFalse(b.counted)
        assertFalse(b.known)
        assertEquals(0L, b.balance)
        assertTrue(b.source is BalanceSource.Unknown)
    }

    @Test
    fun pendingAccountShowsSmsReportedBalanceButIsNotCounted() {
        val pending = AccountEntity(
            id = 1, name = "HDFC Account", type = AccountType.BANK, bank = "HDFC", openingDate = 0, autoCreated = true,
            reportedBalance = rupees(42_000), reportedAt = 5_000,
        )
        val b = Balances.compute(pending, netFlowSinceOpening = rupees(9_99_999), netFlowSinceReported = -rupees(2_000))
        assertFalse(b.counted)
        assertEquals(rupees(40_000), b.balance)
        assertEquals(BalanceSource.Reported(rupees(42_000), 5_000), b.source)
    }

    @Test
    fun confirmedAccountStartsFromEnteredBalance() {
        val acc = AccountEntity(id = 1, name = "HDFC", type = AccountType.BANK, bank = "HDFC", openingBalance = rupees(50_000), openingDate = 1_000)
        val b = Balances.compute(acc, netFlowSinceOpening = -rupees(3_000), netFlowSinceReported = null)
        assertTrue(b.counted)
        assertEquals(rupees(47_000), b.balance)
        assertEquals(BalanceSource.Entered(rupees(50_000), 1_000), b.source)
    }

    @Test
    fun cardDuesAreFlooredAtZeroWithCreditBalanceSeparate() {
        val card = AccountEntity(id = 2, name = "Card", type = AccountType.CREDIT_CARD, bank = "ICICI", openingBalance = rupees(5_000), openingDate = 1_000)
        // Payments of 8,000 against 5,000 owed: an overpayment, not negative debt.
        val b = Balances.compute(card, netFlowSinceOpening = rupees(8_000), netFlowSinceReported = null)
        assertEquals(0L, b.outstanding)
        assertEquals(rupees(3_000), b.creditBalance)
        assertEquals(0L, b.balance)
    }

    @Test
    fun suspiciousAmounts() {
        val normal = listOf(500L, 1_200L, 300L, 2_500L, 800L, 650L).map { rupees(it) }
        assertTrue(SmsMapping.isSuspicious(rupees(12_00_000), "shopping", normal))
        assertFalse(SmsMapping.isSuspicious(rupees(12_00_000), "salary", normal))
        assertTrue(SmsMapping.isSuspicious(rupees(90_000), "other", normal))
        assertFalse(SmsMapping.isSuspicious(rupees(9_000), "other", normal))
        // Too little history for the median rule.
        assertFalse(SmsMapping.isSuspicious(rupees(90_000), "other", normal.take(3)))
    }

    @Test
    fun repairFlagsOnlyTheOutlier() {
        val rows = (1..8).map { txn(it.toLong(), rupees(400L + it * 50)) } + txn(99, rupees(4_12_34_567))
        val flagged = DataRepairs.flagSuspicious(rows)
        assertEquals(listOf(99L), flagged.map { it.id })
    }

    @Test
    fun maskingKeepsAmountsAndHidesNumbers() {
        val masked = ImportResult.maskForSharing(
            "AD-HDFCBK",
            "Rs.1,250.00 debited from A/c XX4521 to VPA 9876543210@ybl on 05-01-25. UPI Ref 412345678901. Avl Bal Rs 12345.00",
        )
        assertTrue(masked, masked.contains("Rs.1,250.00"))
        assertTrue(masked, masked.contains("Rs 12345.00"))
        assertFalse(masked, masked.contains("9876543210"))
        assertFalse(masked, masked.contains("412345678901"))
        assertFalse(masked, masked.contains("4521"))
    }

    @Test
    fun payeeGroupsMergeSpellingsAndPutUnknownLast() {
        val rows = listOf(
            txn(1, rupees(100), merchant = "Sharma Store"),
            txn(2, rupees(200), merchant = "SHARMA STORE"),
            txn(3, rupees(50), merchant = null),
            txn(4, rupees(900), merchant = "Ravi Traders"),
        )
        val groups = groupByPayee(rows)
        assertEquals(listOf("Ravi Traders", "Sharma Store", "Unknown payee"), groups.map { it.label })
        assertEquals(2, groups[1].count)
        assertEquals(rupees(300), groups[1].total)
    }

    private fun txn(id: Long, amount: Long, merchant: String? = "Shop") = TransactionEntity(
        id = id, accountId = 1, type = TxnType.EXPENSE, amount = amount, categoryKey = "other",
        merchant = merchant, timestamp = id * 1_000, source = TxnSource.SMS,
    )
}
