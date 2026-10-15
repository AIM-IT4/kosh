package com.amitjha.kosh.domain

import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun legacyPhonePayeeTransfersBecomePeopleAndCountInStats() {
        val updated = SmsMapping.recategorised(txn("UPI XXXXXX3210", category = "transfer").copy(excludeFromStats = true), emptyMap())
        assertEquals("people", updated?.categoryKey)
        assertFalse(updated!!.excludeFromStats)
    }

    @Test
    fun manualAndUserChosenCategoriesAreNeverTouched() {
        assertNull(SmsMapping.recategorised(txn("Zomato", source = TxnSource.MANUAL), emptyMap()))
        assertNull(SmsMapping.recategorised(txn("Zomato", category = "shopping"), emptyMap()))
        assertNull(SmsMapping.recategorised(txn("UPI XXXXXX3210", category = "transfer", reviewed = true), emptyMap()))
    }
}
