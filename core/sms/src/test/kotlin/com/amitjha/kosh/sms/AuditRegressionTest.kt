package com.amitjha.kosh.sms

import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions from the October 2026 audit of the SMS pipeline. */
class AuditRegressionTest {

    @Test
    fun bankDebitForCardBillIsABankTransferNotACardSpend() {
        val a = txn(
            "VM-ICICIB",
            "ICICI Bank Acct XX123 debited for Rs 15,000.00 on 05-Jan-25; ICICI Bank Credit Card XX5678 credited. UPI:123456789012",
        )
        assertFalse("bank side of a card bill payment is not a card spend", a.isCreditCard)
        assertFalse(a.isCardPayment)
        assertEquals(TxnType.EXPENSE, a.type)
        assertEquals("transfer", a.suggestedCategory)
        assertEquals(rupees(15_000), a.amount)

        val b = txn("AD-HDFCBK", "Amt of INR 15,000.00 debited from A/c XX4521 for Credit Card XX5678 payment on 05-01-25")
        assertFalse(b.isCreditCard)
        assertEquals("transfer", b.suggestedCategory)
    }

    @Test
    fun cardRefundIsNotABillPayment() {
        val t = txn(
            "AX-AXISBK",
            "An amount of INR 1,299.00 has been credited to your Axis Bank Credit Card XX1234 as refund from AMAZON on 05-01-25",
        )
        assertFalse("a refund must not be treated as a card payment", t.isCardPayment)
        assertEquals(TxnType.INCOME, t.type)
    }

    @Test
    fun genuineCardPaymentStillDetected() {
        val t = txn(
            "AD-HDFCBK",
            "Payment of Rs 25,000.00 has been received on your HDFC Bank Credit Card ending 5678 on 05-01-25. Thank you",
        )
        assertTrue(t.isCardPayment)
        assertEquals("transfer", t.suggestedCategory)
    }

    @Test
    fun otpWarningAfterBalanceDoesNotDropARealDebit() {
        val t = txn(
            "AD-HDFCBK",
            "Rs 450.00 debited from A/c XX4521 on 05-01-25 to VPA swiggy@icici. Avl Bal INR 12345.67. Never share OTP with anyone",
        )
        assertEquals(rupees(450), t.amount)
    }

    @Test
    fun otpDeliveriesAreStillIgnored() {
        assertEquals("otp", ignored("AD-HDFCBK", "OTP for txn of Rs 500.00 at AMAZON on HDFC Bank Card 5678 is 123456. Do not share"))
        assertEquals("otp", ignored("VM-ICICIB", "123456 is your OTP for login. Valid for 5 mins"))
        assertEquals("otp", ignored("VM-ICICIB", "Use 482913 as your one-time password"))
    }

    @Test
    fun fingerprintUsesSendTimeNotReceivedTime() {
        val body = "Spent Rs.500 On HDFC Bank Card 5678 At AMAZON on 2025-01-05:10:10:10"
        val sent = SmsParser.parse(SmsMessage("AD-HDFCBK", body, SMS_TIME)) as SmsParseResult.Transaction
        val withReceived = SmsParser.parse(SmsMessage("AD-HDFCBK", body, SMS_TIME, receivedMillis = SMS_TIME + 61_000)) as SmsParseResult.Transaction
        assertEquals(sent.txn.fingerprint, withReceived.txn.fingerprint)
        val other = SmsParser.parse(SmsMessage("AD-HDFCBK", body, SMS_TIME + 61_000)) as SmsParseResult.Transaction
        assertTrue("a different send minute changes the reference-less fingerprint", other.txn.fingerprint != sent.txn.fingerprint)
    }
}
