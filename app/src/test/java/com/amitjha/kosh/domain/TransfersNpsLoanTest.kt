package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.pow

class TransfersNpsLoanTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val t0 = 1_760_000_000_000L

    private val hdfc = AccountEntity(id = 1, name = "HDFC Savings", type = AccountType.BANK, bank = "HDFC", last4 = "4821")
    private val icici = AccountEntity(id = 2, name = "ICICI Salary", type = AccountType.BANK, bank = "ICICI", last4 = "1290")
    private val card = AccountEntity(id = 3, name = "Kotak Card", type = AccountType.CREDIT_CARD, bank = "KOTAK", last4 = "7731")
    private val accounts = listOf(hdfc, icici, card)

    // Pairing only rewrites unreviewed imports, so the fixtures are fresh SMS imports.
    private fun txn(id: Long, account: Long, type: TxnType, amount: Long, at: Long, category: String = "other") =
        TransactionEntity(
            id = id, accountId = account, type = type, amount = amount, categoryKey = category, timestamp = at,
            source = TxnSource.SMS, reviewed = false,
        )

    @Test
    fun pairsSameAmountAcrossOwnAccounts() {
        val debit = txn(1, hdfc.id, TxnType.EXPENSE, 5_000_00, t0, "people")
        val credit = txn(2, icici.id, TxnType.INCOME, 5_000_00, t0 + 2 * hour, "other_income")
        val pairs = TransferPairing.findPairs(listOf(debit, credit), accounts)
        assertEquals(1, pairs.size)
        val (d, c) = TransferPairing.asTransfer(pairs.single(), accounts)
        assertEquals(SmsMapping.TRANSFER_CATEGORY, d.categoryKey)
        assertEquals(SmsMapping.TRANSFER_CATEGORY, c.categoryKey)
        assertFalse(SmsMapping.countsInStats(d))
        assertFalse(SmsMapping.countsInStats(c))
        assertEquals("Transfer to ICICI Salary", d.note)
        assertEquals("Transfer from HDFC Savings", c.note)
    }

    @Test
    fun doesNotPairWithinTheSameAccount() {
        val debit = txn(1, hdfc.id, TxnType.EXPENSE, 1_000_00, t0)
        val credit = txn(2, hdfc.id, TxnType.INCOME, 1_000_00, t0 + hour)
        assertTrue(TransferPairing.findPairs(listOf(debit, credit), accounts).isEmpty())
    }

    @Test
    fun respectsTwoDayWindowAndPicksClosest() {
        val debit = txn(1, hdfc.id, TxnType.EXPENSE, 2_500_00, t0)
        val far = txn(2, icici.id, TxnType.INCOME, 2_500_00, t0 + 3 * day)
        assertTrue(TransferPairing.findPairs(listOf(debit, far), accounts).isEmpty())
        val near = txn(3, icici.id, TxnType.INCOME, 2_500_00, t0 + 30 * 60 * 1000L)
        val alsoNear = txn(4, icici.id, TxnType.INCOME, 2_500_00, t0 + day)
        val pairs = TransferPairing.findPairs(listOf(debit, far, near, alsoNear), accounts)
        assertEquals(3L, pairs.single().credit.id)
    }

    @Test
    fun differentAmountsAndSalaryAreNotPaired() {
        val debit = txn(1, hdfc.id, TxnType.EXPENSE, 1_000_00, t0)
        val credit = txn(2, icici.id, TxnType.INCOME, 1_001_00, t0)
        val salary = txn(3, icici.id, TxnType.INCOME, 1_000_00, t0, "salary")
        assertTrue(TransferPairing.findPairs(listOf(debit, credit, salary), accounts).isEmpty())
    }

    @Test
    fun cardPaymentPairsWithBankDebitOnce() {
        val payment = txn(1, card.id, TxnType.INCOME, 18_450_00, t0, SmsMapping.TRANSFER_CATEGORY)
        val debit = txn(2, hdfc.id, TxnType.EXPENSE, 18_450_00, t0 - hour, "bills")
        val pairs = TransferPairing.findPairs(listOf(payment, debit), accounts)
        assertEquals(1, pairs.size)
        assertEquals(2L, pairs.single().debit.id)
        // Once the debit is a transfer, the payment is consumed and nothing pairs again.
        val (d, c) = TransferPairing.asTransfer(pairs.single(), accounts)
        assertTrue(TransferPairing.findPairs(listOf(c, d), accounts).isEmpty())
    }

    @Test
    fun cardSpendIsNeverTreatedAsTransfer() {
        val spend = txn(1, card.id, TxnType.EXPENSE, 999_00, t0, "shopping")
        val credit = txn(2, hdfc.id, TxnType.INCOME, 999_00, t0)
        assertTrue(TransferPairing.findPairs(listOf(spend, credit), accounts).isEmpty())
    }

    @Test
    fun selfTransferKeywords() {
        val own = setOf("4821", "1290")
        assertTrue(TransferPairing.looksLikeSelfTransfer("Rs 5000 debited from a/c XX4821 to self via IMPS", own, "4821"))
        assertTrue(TransferPairing.looksLikeSelfTransfer("INR 2,000 transferred to your own account", own, "4821"))
        assertTrue(TransferPairing.looksLikeSelfTransfer("Rs.10000 debited from A/c XX4821; transferred to A/c XX1290", own, "4821"))
        assertFalse(TransferPairing.looksLikeSelfTransfer("Rs.10000 debited from A/c XX4821; transferred to A/c XX9999", own, "4821"))
        assertFalse(TransferPairing.looksLikeSelfTransfer("Rs 450 debited for UPI to swiggy@icici", own, "4821"))
    }

    @Test
    fun simpleNpsGrowsWithContributionsAndReturn() {
        val spec = NpsSimple(
            valueAsOf = 1_00_000_00,
            asOfDate = LocalDate.of(2025, 1, 1),
            monthlyContribution = 5_000_00,
            contributionDay = 1,
            annualReturnPct = 10.0,
        )
        val (value, invested) = spec.valueOn(LocalDate.of(2026, 1, 1))
        // The entered return is the effective yearly rate: monthly growth of 1.10^(1/12).
        val r = 1.10.pow(1.0 / 12) - 1
        val expected = 1_00_000_00 * (1 + r).pow(12) + 5_000_00 * ((1 + r).pow(12) - 1) / r
        assertEquals(expected, value.toDouble(), 2.0) // within 2 paise
        assertEquals(1_60_000_00L, invested)
        assertEquals(1_00_000_00L to 1_00_000_00L, spec.valueOn(LocalDate.of(2025, 1, 1)))
    }

    @Test
    fun simpleNpsCodecRoundTrip() {
        val spec = NpsSimple(2_50_000_00, LocalDate.of(2026, 10, 15), 7_500_00, 5, 12.0, 2_00_000_00)
        assertEquals(spec, SpecCodec.decode(SpecCodec.encode(spec)))
    }

    @Test
    fun loanEmiAndReducedSavingsTarget() {
        // ₹5,00,000 at 9% for 36 months: EMI ≈ ₹15,900
        val emi = GoalLoan.emi(5_00_000_00, 9.0, 36)
        assertEquals(15_899_86.0, emi.toDouble(), 100.0)
        assertEquals(emi * 36 - 5_00_000_00, GoalLoan.totalInterest(5_00_000_00, 9.0, 36))
        assertEquals(5_00_000_00L, GoalLoan.savingsTarget(10_00_000_00, 5_00_000_00))
        assertEquals(0L, GoalLoan.savingsTarget(3_00_000_00, 5_00_000_00))
        assertEquals(1_000_00L, GoalLoan.emi(12_000_00, 0.0, 12))
    }
}
