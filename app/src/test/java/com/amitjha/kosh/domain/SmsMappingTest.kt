package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.SmsMessage
import com.amitjha.kosh.sms.SmsParseResult
import com.amitjha.kosh.sms.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsMappingTest {

    private val hdfcSavings = AccountEntity(id = 1, name = "HDFC Savings", type = AccountType.BANK, bank = "HDFC", last4 = "4521")
    private val hdfcCard = AccountEntity(id = 2, name = "HDFC Card", type = AccountType.CREDIT_CARD, bank = "HDFC", last4 = "5678")
    private val iciciNoDigits = AccountEntity(id = 3, name = "ICICI", type = AccountType.BANK, bank = "ICICI", last4 = null)
    private val accounts = listOf(hdfcSavings, hdfcCard, iciciNoDigits)

    @Test
    fun matchesBankKindAndDigits() {
        assertSame(hdfcCard, SmsMapping.matchAccount(accounts, Bank.HDFC, "5678", isCard = true))
        assertSame(hdfcSavings, SmsMapping.matchAccount(accounts, Bank.HDFC, "4521", isCard = false))
        // Card digits never match a bank account.
        assertNull(SmsMapping.matchAccount(accounts, Bank.HDFC, "5678", isCard = false))
        // Different digits of the same bank: no match, so a new account is created.
        assertNull(SmsMapping.matchAccount(accounts, Bank.HDFC, "9999", isCard = true))
    }

    @Test
    fun matchesSingleAccountWhenDigitsMissing() {
        assertSame(hdfcSavings, SmsMapping.matchAccount(accounts, Bank.HDFC, null, isCard = false))
        // An account saved without digits takes messages with digits.
        assertSame(iciciNoDigits, SmsMapping.matchAccount(accounts, Bank.ICICI, "7788", isCard = false))
        val two = accounts + hdfcSavings.copy(id = 9, last4 = "1111")
        assertNull(SmsMapping.matchAccount(two, Bank.HDFC, null, isCard = false))
    }

    @Test
    fun ignoredAccountKeepsItsMessagesSoItIsNotRecreated() {
        val ignored = hdfcCard.copy(archived = true)
        assertSame(ignored, SmsMapping.matchAccount(listOf(ignored), Bank.HDFC, "5678", isCard = true))
        // Only an exact digit match reaches an ignored account.
        assertNull(SmsMapping.matchAccount(listOf(ignored), Bank.HDFC, "9999", isCard = true))
        assertNull(SmsMapping.matchAccount(listOf(ignored), Bank.HDFC, null, isCard = true))
        // A live account with the same digits wins over an ignored one.
        assertSame(hdfcCard, SmsMapping.matchAccount(listOf(ignored.copy(id = 7), hdfcCard), Bank.HDFC, "5678", isCard = true))
    }

    @Test
    fun accountWithoutDigitsMatchesOnlyWhenItIsTheOnlyOne() {
        val noDigits = hdfcSavings.copy(id = 5, last4 = null)
        assertSame(noDigits, SmsMapping.matchAccount(listOf(noDigits), Bank.HDFC, "4521", isCard = false))
        // With another HDFC account present, the message can't be safely assigned.
        assertNull(SmsMapping.matchAccount(listOf(noDigits, hdfcSavings.copy(id = 6, last4 = "1111")), Bank.HDFC, "4521", isCard = false))
    }

    @Test
    fun bankFormerlyFiledUnderOtherKeepsUsingTheOtherAccountWithItsDigits() {
        // Before Yes Bank was recognised its messages created "Other" accounts; the user may have renamed one.
        val other = AccountEntity(id = 4, name = "Yes Savings", type = AccountType.BANK, bank = "OTHER", last4 = "1234")
        assertSame(other, SmsMapping.matchAccount(listOf(other), Bank.YES, "1234", isCard = false))
        // Only the same digits and kind, and only for those banks.
        assertNull(SmsMapping.matchAccount(listOf(other), Bank.YES, "9999", isCard = false))
        assertNull(SmsMapping.matchAccount(listOf(other), Bank.YES, "1234", isCard = true))
        assertNull(SmsMapping.matchAccount(listOf(other), Bank.YES, null, isCard = false))
        assertNull(SmsMapping.matchAccount(listOf(other), Bank.HDFC, "1234", isCard = false))
        // An account of the bank itself wins, and a live Other account wins over an ignored one.
        val yes = other.copy(id = 5, bank = "YES")
        assertSame(yes, SmsMapping.matchAccount(listOf(other, yes), Bank.YES, "1234", isCard = false))
        val ignored = other.copy(id = 6, archived = true)
        assertSame(other, SmsMapping.matchAccount(listOf(ignored, other), Bank.YES, "1234", isCard = false))
        assertSame(ignored, SmsMapping.matchAccount(listOf(ignored), Bank.YES, "1234", isCard = false))
    }

    @Test
    fun newAccountNaming() {
        val card = SmsMapping.newAccountFor(Bank.KOTAK, "1234", isCard = true, now = 0)
        assertEquals("Kotak Card ••1234", card.name)
        assertEquals(AccountType.CREDIT_CARD, card.type)
        assertTrue(card.autoCreated)
        assertEquals(0L, card.openingDate)
        assertEquals("DBS Account", SmsMapping.newAccountFor(Bank.DBS, null, isCard = false, now = 0).name)
    }

    @Test
    fun mapsRealCardSpend() {
        val parsed = SmsParser.parse(
            SmsMessage(
                "AD-HDFCBK",
                "Spent Rs.1,499.00 On HDFC Bank Card 5678 At AMAZON PAY INDIA PRIVATE LI On 2025-01-05:14:23:11." +
                    "Not You? To Block+Reissue Call 18002323232/SMS BLOCK CC 5678 to 7308080808",
                1_736_000_000_000,
            ),
        ) as SmsParseResult.Transaction
        val account = SmsMapping.matchAccount(accounts, parsed.txn.bank, parsed.txn.accountLast4, parsed.txn.isCreditCard)
        assertSame(hdfcCard, account)
        val entity = SmsMapping.toTransaction(parsed.txn, account!!.id, categoryOverride = null)
        assertEquals(TxnType.EXPENSE, entity.type)
        assertEquals(149_900L, entity.amount)
        assertEquals("Amazon", entity.merchant)
        assertEquals(TxnSource.SMS, entity.source)
        assertFalse(entity.reviewed)
        assertFalse(entity.excludeFromStats)
        assertEquals(parsed.txn.fingerprint, entity.fingerprint)
        // A learned rule overrides the guessed category.
        assertEquals("entertainment", SmsMapping.toTransaction(parsed.txn, 2, "entertainment").categoryKey)
    }

    @Test
    fun cardPaymentIsATransfer() {
        val parsed = SmsParser.parse(
            SmsMessage(
                "VM-HDFCBK",
                "Payment of Rs.12,345.67 received towards your HDFC Bank Credit Card ending 5678 on 20-01-2025. Thank you.",
                1_737_000_000_000,
            ),
        ) as SmsParseResult.Transaction
        assertTrue(parsed.txn.isCardPayment)
        val entity = SmsMapping.toTransaction(parsed.txn, 2, categoryOverride = "salary")
        assertEquals(SmsMapping.TRANSFER_CATEGORY, entity.categoryKey)
        assertTrue(entity.excludeFromStats)
        assertFalse(SmsMapping.countsInStats(entity))
    }
}
