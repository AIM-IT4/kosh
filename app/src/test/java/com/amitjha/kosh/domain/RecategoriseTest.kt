package com.amitjha.kosh.domain

import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecategoriseTest {

    private fun txn(merchant: String?, category: String = "other", source: TxnSource = TxnSource.SMS, reviewed: Boolean = false) =
        TransactionEntity(
            accountId = 1, type = TxnType.EXPENSE, amount = 500_00, categoryKey = category, merchant = merchant,
            timestamp = 0, channel = TxnChannel.UPI, source = source, reviewed = reviewed,
        )

    @Test
    fun learnedRulesMatchSpellingVariants() {
        val rules = mapOf("Sharma Stores" to "groceries", "Swiggy" to "food")
        assertEquals("food", SmsMapping.ruleFor(rules, "SWIGGY"))
        assertEquals("food", SmsMapping.ruleFor(rules, "swiggy"))
        assertEquals("groceries", SmsMapping.ruleFor(rules, "Sharma Store"))
        assertNull(SmsMapping.ruleFor(rules, "Zomato"))
        assertNull(SmsMapping.ruleFor(rules, null))
    }

    @Test
    fun otherGetsARealCategory() {
        assertEquals("food", SmsMapping.recategorised(txn("Zomato"), emptyMap())?.categoryKey)
        assertEquals("groceries", SmsMapping.recategorised(txn("Zomato"), mapOf("Zomato" to "groceries"))?.categoryKey)
    }

    @Test
    fun transfersAreNeverRecategorised() {
        // A self-transfer to a phone-number UPI ID must stay a transfer (audit: it used to revert to "people").
        assertNull(SmsMapping.recategorised(txn("UPI XXXXXX3210", category = "transfer").copy(excludeFromStats = true), emptyMap()))
    }

    @Test
    fun reviewedOtherIsAChoiceAndIgnoredAccountsStayHidden() {
        assertNull(SmsMapping.recategorised(txn("Zomato", reviewed = true), emptyMap()))
        val hidden = SmsMapping.recategorised(txn("Zomato").copy(excludeFromStats = true), emptyMap())
        assertEquals("food", hidden?.categoryKey)
        assertTrue("a row from an ignored account must stay out of stats", hidden!!.excludeFromStats)
    }

    @Test
    fun recategoriseFlagRules() {
        // moving a paired transfer leg out of "transfer" makes it count again
        assertFalse(SmsMapping.excludeAfterRecategorise(txn("X", category = "transfer").copy(excludeFromStats = true), "food"))
        // a row hidden because its account is ignored stays hidden
        assertTrue(SmsMapping.excludeAfterRecategorise(txn("X", category = "other").copy(excludeFromStats = true), "food"))
        assertTrue(SmsMapping.excludeAfterRecategorise(txn("X", category = "food"), "transfer"))
        assertFalse(SmsMapping.excludeAfterRecategorise(txn("X", category = "food"), "shopping"))
    }

    @Test
    fun manualAndUserChosenCategoriesAreNeverTouched() {
        assertNull(SmsMapping.recategorised(txn("Zomato", source = TxnSource.MANUAL), emptyMap()))
        assertNull(SmsMapping.recategorised(txn("Zomato", category = "shopping"), emptyMap()))
        assertNull(SmsMapping.recategorised(txn("UPI XXXXXX3210", category = "transfer", reviewed = true), emptyMap()))
    }
}
