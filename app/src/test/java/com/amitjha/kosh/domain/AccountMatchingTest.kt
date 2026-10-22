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

    @Test
    fun aForwardThatDoesntNameItsBankFindsTheMembersAccountByDigits() {
        val momHdfc = AccountEntity(id = 10, name = "Mom · HDFC Savings", type = AccountType.BANK, bank = "HDFC", last4 = "1234", memberId = 5)
        val momSbi = AccountEntity(id = 11, name = "Mom · SBI", type = AccountType.BANK, bank = "SBI", last4 = "7788", memberId = 5)
        val theirs = listOf(momHdfc, momSbi)
        // "Rs 250.00 debited from a/c **1234": no bank in the text, so it reads as Other.
        assertSame(momHdfc, SmsMapping.matchForwarded(theirs, Bank.OTHER, "1234", isCard = false))
        assertSame(momSbi, SmsMapping.matchForwarded(theirs, Bank.OTHER, "788", isCard = false))
        assertNull("no account of theirs has these digits", SmsMapping.matchForwarded(theirs, Bank.OTHER, "5555", isCard = false))
        assertNull("a card message never lands on a bank account", SmsMapping.matchForwarded(theirs, Bank.OTHER, "1234", isCard = true))
        // An "Other ••1234" an earlier forward made gives way to their real account with the same digits.
        val madeEarlier = AccountEntity(id = 12, name = "Mom · Account ••1234", type = AccountType.BANK, bank = "OTHER", last4 = "1234",
            memberId = 5, autoCreated = true)
        assertSame(momHdfc, SmsMapping.matchForwarded(theirs + madeEarlier, Bank.OTHER, "1234", isCard = false))
        // With no digits, their only bank account.
        assertSame(momHdfc, SmsMapping.matchForwarded(listOf(momHdfc), Bank.OTHER, null, isCard = false))
        // A forward that names its bank matches as before.
        assertSame(momSbi, SmsMapping.matchForwarded(theirs, Bank.SBI, "7788", isCard = false))
        // HDFC's alert "to VPA swiggy@icici" reads as ICICI: an ICICI account Kosh made from it gives way to her HDFC one.
        val madeAsIcici = madeEarlier.copy(id = 13, bank = "ICICI")
        assertSame(momHdfc, SmsMapping.matchForwarded(theirs + madeAsIcici, Bank.ICICI, "1234", isCard = false))
        // Until she has a confirmed account with those digits, the one Kosh made keeps them.
        assertSame(madeAsIcici, SmsMapping.matchForwarded(listOf(momSbi, madeAsIcici), Bank.ICICI, "1234", isCard = false))
    }
}
