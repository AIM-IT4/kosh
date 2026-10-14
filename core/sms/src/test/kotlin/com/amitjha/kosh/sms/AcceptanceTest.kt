package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceTest {
    private val ts = 1_736_000_000_000L

    @Test
    fun headersWithSuffixesAndCase() {
        listOf("JX-HDFCBK-S", "AX-KOTAKB-T", "VM-DBSBNK-P", "BP-ICICIT", "hdfcbk", "jd-icicib-s", "VM-KOTAKB-G")
            .forEach { assertTrue(it, isLikelyFinancialSender(it)) }
    }

    @Test
    fun rcsBusinessNames() {
        listOf("HDFC Bank", "Kotak Mahindra Bank", "ICICI Bank", "DBS Bank India")
            .forEach { assertTrue(it, isLikelyFinancialSender(it)) }
        assertEquals(Bank.KOTAK, bankFromSenderName("Kotak Mahindra Bank"))
        assertEquals(Bank.DBS, bankFromSenderName("DBS Bank India"))
        assertEquals(Bank.HDFC, bankFromSenderName("JX-HDFCBK-S"))
    }

    @Test
    fun unknownSenderPassesWhenBodyIsTransactional() {
        val body = "Rs.450.00 debited from A/c XX4821 on 05-01-25 to VPA swiggy@icici UPI Ref 512345678901"
        assertTrue(isFinancialMessage("AD-XYZBNQ", body))
        assertTrue(isFinancialMessage("56767", body))
        assertTrue(isFinancialMessage("+919876543210", body))
        assertFalse(isFinancialMessage("AD-SWIGGY", "Your order of Rs.450 is on the way!"))
        assertFalse(isFinancialMessage("+919876543210", "Hey, can you pay me back 500 today?"))
    }

    @Test
    fun parserAcceptsRcsSenderAndShortCode() {
        val kotak = SmsParser.parse(
            SmsMessage(
                "Kotak Mahindra Bank",
                "Sent Rs.250.00 from Kotak Bank AC X4821 to zomato@kotak on 05-01-25.UPI Ref 512345678901. Not you, https://kotak.com/fraud",
                ts,
            ),
        )
        assertTrue(kotak.toString(), kotak is SmsParseResult.Transaction)
        kotak as SmsParseResult.Transaction
        assertEquals(Bank.KOTAK, kotak.txn.bank)
        assertEquals(25000L, kotak.txn.amount)
        assertEquals(TxnType.EXPENSE, kotak.txn.type)

        val shortCode = SmsParser.parse(
            SmsMessage("56161", "INR 1,200.00 spent using ICICI Bank Card XX9012 on 05-Jan-25 on AMAZON. Avl Limit: INR 98,800.00", ts),
        )
        assertTrue(shortCode.toString(), shortCode is SmsParseResult.Transaction)
        assertEquals(Bank.ICICI, (shortCode as SmsParseResult.Transaction).txn.bank)
    }

    @Test
    fun personalMessagesStayIgnored() {
        val r = SmsParser.parse(SmsMessage("+919876543210", "Reached home, call you later", ts))
        assertTrue(r is SmsParseResult.Ignored)
    }
}
