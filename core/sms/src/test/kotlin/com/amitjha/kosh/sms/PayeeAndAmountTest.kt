package com.amitjha.kosh.sms

import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payee extraction when bank-specific phrasing doesn't match, and amounts next to reference numbers. */
class PayeeAndAmountTest {

    @Test
    fun upiP2mInfoGivesMerchant() {
        val t = txn("AD-YESBNK", "Rs.349.00 debited from A/c XX4521 on 05-01-25. Info: UPI/P2M/412345678901/BLINKIT COMMERCE/Payment. Avl Bal Rs 12,000.00")
        assertEquals(rupees(349), t.amount)
        assertNotNull(t.merchant)
        assertTrue(t.merchant!!, t.merchant!!.contains("Blinkit", ignoreCase = true))
        assertEquals("groceries", t.suggestedCategory)
    }

    @Test
    fun upiNumericInfoGivesPayeeName() {
        val t = txn("VM-FEDBNK", "Your A/c XX7788 is debited for Rs.1,500.00 on 05/01/2025 UPI/412398765432/Rahul Kumar/okaxis. Avl Bal Rs.8,200.00")
        assertEquals(rupees(1500), t.amount)
        assertTrue(t.merchant!!, t.merchant!!.contains("Rahul", ignoreCase = true))
    }

    @Test
    fun paidToPhrase() {
        val t = txn("AD-INDUSB", "Rs 799 paid to Croma Retail via UPI from A/c XX3344 on 05-01-25. Ref 401234567890")
        assertEquals(rupees(799), t.amount)
        assertTrue(t.merchant!!, t.merchant!!.contains("Croma", ignoreCase = true))
        assertEquals("shopping", t.suggestedCategory)
    }

    @Test
    fun forMerchantLastResort() {
        val t = txn("AD-RBLBNK", "Rs.649.00 debited from your card XX9001 for NETFLIX on 05-01-25.")
        assertEquals(rupees(649), t.amount)
        assertTrue(t.merchant!!, t.merchant!!.contains("Netflix", ignoreCase = true))
        assertEquals("subscriptions", t.suggestedCategory)
    }

    @Test
    fun forRupeesIsNotAMerchant() {
        val t = txn("AD-HDFCBK", "Your A/c XX1234 debited for Rs 450.00 on 05-01-25. Avl Bal Rs 9,000.00")
        assertTrue(t.merchant == null || !t.merchant!!.startsWith("Rs", ignoreCase = true))
    }

    @Test
    fun referenceNumberNextToAmountIsNotTheAmount() {
        val t = txn("AD-HDFCBK", "UPI Ref 412345678901 Rs.250.00 debited from A/c XX1234 to swiggy@icici on 05-01-25")
        assertEquals(rupees(250), t.amount)
    }

    @Test
    fun currencyFollowedByLongDigitRunIsSkipped() {
        // "INR 401234567890123" is a reference glued to a currency marker; the real amount comes later.
        val t = txn("AD-ICICIB", "Txn INR 401234567890123 ref. INR 1,200.00 spent using ICICI Bank Card XX9012 on 05-Jan-25 on AMAZON. Avl Limit: INR 50,000.00")
        assertEquals(rupees(1200), t.amount)
    }

    @Test
    fun bareAmountWithHugeNumberIsRejected() {
        val r = SmsParser.parse(sms("AD-SBIINB", "Your A/c X1234 debited by 412345678901 on 05Jan25 trf to Rahul"))
        if (r is SmsParseResult.Transaction) {
            assertTrue("amount ${r.txn.amount}", r.txn.amount < rupees(10_000_000))
        }
    }

    @Test
    fun phoneNumberIsNotAnAmount() {
        val t = txn("JD-KOTAKB", "Sent Rs.120.00 from Kotak Bank AC X4321 to 9876543210@ybl on 05-01-25.UPI Ref 401234567890")
        assertEquals(rupees(120), t.amount)
        assertEquals(TxnType.EXPENSE, t.type)
    }

    @Test
    fun normalBalanceStillParsed() {
        val t = txn("AD-HDFCBK", "Rs.500.00 debited from A/c XX1234 on 05-01-25 to VPA zomato@hdfcbank. Avl Bal Rs 1,23,456.70")
        assertEquals(rupees(500), t.amount)
        assertEquals(12345670L, t.availableBalance)
        assertNull(null)
    }
}
