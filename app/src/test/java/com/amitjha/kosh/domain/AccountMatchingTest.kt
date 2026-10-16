package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spends must land in the account the user sees, so its balance moves. */
class AccountMatchingTest {

    private val savings = AccountEntity(id = 1, name = "HDFC Savings", type = AccountType.BANK, bank = "HDFC", last4 = "4521", openingDate = 1_000)
    private val card = AccountEntity(id = 2, name = "HDFC Regalia", type = AccountType.CREDIT_CARD, bank = "HDFC", last4 = "0789", openingDate = 1_000)

    @Test
    fun aThreeDigitMaskMatchesTheSameFourDigitNumber() {
        val accounts = listOf(savings, card)
        assertSame(savings, SmsMapping.matchAccount(accounts, Bank.HDFC, "521", isCard = false))
        assertSame(card, SmsMapping.matchAccount(accounts, Bank.HDFC, "789", isCard = true))
        // Exact digits win; an ambiguous short mask matches nothing.
        val two = listOf(savings, savings.copy(id = 3, last4 = "9521"))
        assertSame(savings, SmsMapping.matchAccount(two, Bank.HDFC, "4521", isCard = false))
        assertNull(SmsMapping.matchAccount(two, Bank.HDFC, "521", isCard = false))
        assertTrue(!SmsMapping.sameDigits("12", "512"))
    }

    @Test
    fun aDebitCardsNumberIsRememberedOnItsAccount() {
        val withCard = savings.plusOtherDigits("9876")
        assertEquals(listOf("4521", "9876"), withCard.allDigits)
        assertSame(withCard, withCard.plusOtherDigits("9876"))
        assertEquals("9876,1111", withCard.plusOtherDigits("1111").otherDigits)
        assertSame(withCard, SmsMapping.matchAccount(listOf(withCard, card), Bank.HDFC, "9876", isCard = false))
        // An account added without digits still takes the account number from a later message.
        val noDigits = savings.copy(last4 = null).plusOtherDigits("9876")
        assertSame(noDigits, SmsMapping.matchAccount(listOf(noDigits), Bank.HDFC, "4521", isCard = false))
    }

    @Test
    fun aDebitCardMessageGoesToTheOnlyConfirmedAccountAtThatBank() {
        val pending = savings.copy(id = 7, last4 = "3333", autoCreated = true)
        assertSame(savings, SmsMapping.debitCardAccount(listOf(savings, card, pending), Bank.HDFC))
        assertNull(SmsMapping.debitCardAccount(listOf(savings, savings.copy(id = 8, last4 = "2222")), Bank.HDFC))
        assertNull(SmsMapping.debitCardAccount(listOf(savings), Bank.ICICI))
        assertTrue(SmsMapping.isDebitCardMessage("Rs.500 spent on HDFC Bank Debit Card XX9876 at SWIGGY"))
        assertTrue(!SmsMapping.isDebitCardMessage("Rs.500 debited from A/c XX4521 via UPI"))
    }

    private fun txn(at: Long, suspicious: Boolean = false) = TransactionEntity(
        accountId = 1, type = TxnType.EXPENSE, amount = 500_00, categoryKey = "food", timestamp = at, suspicious = suspicious,
    )

    @Test
    fun tellsWhetherASpendMovedTheBalance() {
        assertEquals(BalanceEffect.Moves, Balances.effectOf(txn(2_000), savings))
        assertEquals(BalanceEffect.BeforeEnteredBalance(1_000), Balances.effectOf(txn(500), savings))
        assertEquals(BalanceEffect.HeldForReview, Balances.effectOf(txn(2_000, suspicious = true), savings))
        assertEquals(BalanceEffect.PendingAccount, Balances.effectOf(txn(2_000), savings.copy(autoCreated = true)))

        val reported = savings.copy(reportedBalance = 40_000_00, reportedAt = 3_000)
        assertEquals(BalanceEffect.InReportedBalance(3_000), Balances.effectOf(txn(3_000), reported))
        assertEquals(BalanceEffect.Moves, Balances.effectOf(txn(3_001), reported))
        // A card's reported available limit is used only with a known limit, like the balance rules.
        val cardReport = card.copy(reportedBalance = 90_000_00, reportedAt = 3_000)
        assertEquals(BalanceEffect.Moves, Balances.effectOf(txn(2_000), cardReport))
        assertEquals(BalanceEffect.InReportedBalance(3_000), Balances.effectOf(txn(2_000), cardReport.copy(creditLimit = 1_00_000_00)))
    }
}
