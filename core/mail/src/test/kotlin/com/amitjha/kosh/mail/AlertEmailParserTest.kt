package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEmailParserTest {
    private val date = 1_736_070_000_000L

    private fun email(from: String, subject: String, body: String, id: String = "m1") =
        EmailMessage(id = id, from = from, subject = subject, dateMillis = date, textBody = body, htmlBody = null)

    private val footer = " Never share your OTP, CVV or PIN with anyone. Get pre-approved loans, apply now."

    @Test
    fun hdfcCardAlert() {
        val t = AlertEmailParser.parse(
            email(
                "HDFC Bank InstaAlerts <alerts@hdfcbank.net>", "Alert : Update on your HDFC Bank Credit Card",
                "Dear Customer, Thank you for using your HDFC Bank Credit Card ending 1234 for Rs 500.00 at AMAZON on 05-01-2025 10:10:10. " +
                    "Authorization code:- 123456." + footer,
            ),
        )
        assertNotNull(t); t!!
        assertEquals(Bank.HDFC, t.bank)
        assertEquals(50000L, t.amount)
        assertEquals("1234", t.accountLast4)
        assertEquals(TxnType.EXPENSE, t.type)
        assertTrue(t.isCreditCard)
        assertTrue(t.merchant.orEmpty().contains("Amazon", ignoreCase = true))
    }

    @Test
    fun iciciCardAlert() {
        val t = AlertEmailParser.parse(
            email(
                "ICICI Bank <credit_cards@icicibank.com>", "Transaction alert for your ICICI Bank Credit Card",
                "Dear Customer, Your ICICI Bank Credit Card XX1234 has been used for a transaction of INR 1,234.00 on Jan 05, 2025 at 10:10:10. " +
                    "Info: AMAZON PAY. The Available Credit Limit on your card is INR 98,766.00." + footer,
            ),
        )
        assertNotNull(t); t!!
        assertEquals(Bank.ICICI, t.bank)
        assertEquals(123400L, t.amount)
        assertEquals("1234", t.accountLast4)
    }

    @Test
    fun kotakCardAlert() {
        val t = AlertEmailParser.parse(
            email(
                "Kotak Credit Card <creditcardalerts@kotak.com>", "Transaction on your Kotak Credit Card",
                "Dear Customer, Rs.499.00 spent on your Kotak Credit Card x1234 at NETFLIX on 05-Jan-2025. Avl Lmt Rs.50,000." + footer,
            ),
        )
        assertNotNull(t); t!!
        assertEquals(Bank.KOTAK, t.bank)
        assertEquals(49900L, t.amount)
        assertTrue(t.merchant.orEmpty().contains("Netflix", ignoreCase = true))
    }

    @Test
    fun dbsCardAlert() {
        val t = AlertEmailParser.parse(
            email(
                "DBS Bank <alerts@dbs.com>", "DBS Card transaction alert",
                "Hi, Your DBS Bank Credit Card ending 1234 was used for INR 899 at SWIGGY on 05 Jan 2025." + footer,
            ),
        )
        assertNotNull(t); t!!
        assertEquals(Bank.DBS, t.bank)
        assertEquals(89900L, t.amount)
        assertEquals("1234", t.accountLast4)
    }

    @Test
    fun accountDebitAlertUsesSmsWording() {
        val t = AlertEmailParser.parse(
            email(
                "HDFC Bank InstaAlerts <alerts@hdfcbank.net>", "You have done a UPI txn. Check details!",
                "Dear Customer, Rs.250.00 has been debited from account 4821 to VPA zomato@hdfcbank ZOMATO on 05-01-25. " +
                    "Your UPI transaction reference number is 512345678901." + footer,
            ),
        )
        assertNotNull(t); t!!
        assertEquals(25000L, t.amount)
        assertEquals(TxnType.EXPENSE, t.type)
    }

    @Test
    fun nonTransactionEmailsIgnored() {
        assertNull(AlertEmailParser.parse(email("offers@hdfcbank.net", "Exclusive offer", "Get a pre-approved loan up to Rs 5,00,000. Apply now!")))
        assertNull(AlertEmailParser.parse(email("a@b.com", "Hi", "Lunch tomorrow?")))
    }

    @Test
    fun fingerprintIsPerEmail() {
        val body = "Your DBS Bank Credit Card ending 1234 was used for INR 899 at SWIGGY on 05 Jan 2025."
        val a = AlertEmailParser.parse(email("alerts@dbs.com", "Alert", body, id = "a"))!!
        val b = AlertEmailParser.parse(email("alerts@dbs.com", "Alert", body, id = "a"))!!
        val c = AlertEmailParser.parse(email("alerts@dbs.com", "Alert", body, id = "b"))!!
        assertEquals(a.fingerprint, b.fingerprint)
        assertTrue(a.fingerprint != c.fingerprint)
    }

    @Test
    fun alertQueryCoversIssuers() {
        val q = BillQueries.alertsForBanks(setOf(Bank.HDFC, Bank.KOTAK), 180)
        assertTrue(q.startsWith("newer_than:180d"))
        assertTrue(q.contains("alerts@hdfcbank.net"))
        assertTrue(q.contains("creditcardalerts@kotak.com"))
        assertTrue(q.contains("\"transaction alert\""))
    }
}
