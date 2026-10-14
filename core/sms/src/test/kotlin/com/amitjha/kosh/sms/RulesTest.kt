package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectionTest {

    @Test
    fun otp() {
        assertEquals(
            "otp",
            ignored(
                "AD-HDFCBK",
                "OTP is 123456 for txn of INR 1,499.00 at AMAZON on HDFC Bank card ending 5678. Valid till 14:30. " +
                    "Do not share OTP for security reasons",
            ),
        )
        ignored("VM-ICICIT", "482913 is the OTP for your ICICI Bank Credit Card XX9012 transaction of INR 2,350.00 at AMAZON.")
    }

    @Test
    fun promotionalLoanOffer() {
        assertEquals(
            "promotional",
            ignored(
                "AD-HDFCBK",
                "Congratulations! You are pre-approved for a Personal Loan of Rs.5,00,000 at 10.5% p.a. Apply now: hdfcbk.io/x",
            ),
        )
        ignored("JD-KOTAKB", "Exclusive offer! Get up to Rs.2,000 cashback on your Kotak Credit Card. T&C apply.")
    }

    @Test
    fun upcomingAutoDebitReminder() {
        ignored(
            "AD-HDFCBK",
            "Dear Customer, Rs.649.00 will be debited from your A/c XX1234 on 07-01-25 towards NETFLIX mandate. -HDFC Bank",
        )
    }

    @Test
    fun genericReminderWithoutCardIsIgnored() {
        assertEquals(
            "payment reminder",
            ignored("VM-ICICIB", "Reminder: Your electricity bill of Rs.1,240.00 is due on 10-Jan-25. Pay via iMobile."),
        )
    }

    @Test
    fun balanceEnquirySpam() {
        ignored("AD-SBIINB", "Give a missed call to 9223766666 to know your balance. -SBI")
    }

    @Test
    fun personalSmsIgnored() {
        assertEquals("not a bank sender", ignored("+919876543210", "Sent you Rs.500 for dinner, debited from my a/c"))
    }

    @Test
    fun refundForFailedTxnIsKept() {
        val t = txn(
            "AD-HDFCBK",
            "Refund of Rs.799.00 for failed UPI txn credited to HDFC Bank A/c XX1234 on 05-01-25 (UPI Ref No 412345670000).",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals("refund", t.suggestedCategory)
    }
}

class FingerprintTest {

    private val body = "Sent Rs.320.00 from Kotak Bank AC X1234 to blinkit@okaxis on 05-01-25.UPI Ref 412345678901."

    @Test
    fun sameMessageSameKey() {
        assertEquals(txn("JD-KOTAKB", body).fingerprint, txn("JD-KOTAKB", body).fingerprint)
    }

    @Test
    fun differentReferenceDifferentKey() {
        val other = body.replace("412345678901", "412345678999")
        assertNotEquals(txn("JD-KOTAKB", body).fingerprint, txn("JD-KOTAKB", other).fingerprint)
    }

    @Test
    fun sameRefFromDifferentSenderHeaderIsSameTxn() {
        assertEquals(txn("JD-KOTAKB", body).fingerprint, txn("AD-KOTAKB", body, SMS_TIME + 5_000).fingerprint)
    }

    @Test
    fun withoutReferenceUsesTimeAndBody() {
        val atm = "Rs.2,000 withdrawn from Kotak Bank AC X1234 at ATM on 05-01-25. Avl Bal Rs.18,500.00"
        assertEquals(txn("AD-KOTAKB", atm).fingerprint, txn("AD-KOTAKB", atm).fingerprint)
        assertNotEquals(txn("AD-KOTAKB", atm).fingerprint, txn("AD-KOTAKB", atm, SMS_TIME + 3_600_000).fingerprint)
    }
}

class MerchantNormalizerTest {
    @Test
    fun normalizes() {
        assertEquals("Swiggy", MerchantNormalizer.normalize("swiggy@icici"))
        assertEquals("Zomato", MerchantNormalizer.normalize("UPI-ZOMATO-zomato@hdfcbank"))
        assertEquals("Amazon", MerchantNormalizer.normalize("POS 4412 AMAZON PAY INDIA"))
        assertEquals("Swiggy", MerchantNormalizer.normalize("PAYTM*SWIGGY"))
        assertEquals("Paytm", MerchantNormalizer.normalize("paytmqr2810050501011abcd@paytm"))
        assertEquals("Rahul Sharma", MerchantNormalizer.normalize("rahul.sharma@okhdfcbank"))
        assertEquals("UPI XXXXXX3210", MerchantNormalizer.normalize("9876543210@ybl"))
        assertEquals("Sharma General Stores", MerchantNormalizer.normalize("SHARMA GENERAL STORES PVT LTD"))
        assertEquals("IRCTC", MerchantNormalizer.normalize("IRCTC WEB UPI"))
        assertEquals("Uber", MerchantNormalizer.normalize("UBER INDIA"))
    }
}

class CategoryGuesserTest {
    @Test
    fun guesses() {
        assertEquals("food", CategoryGuesser.guess("Swiggy", "", TxnType.EXPENSE))
        assertEquals("subscriptions", CategoryGuesser.guess("Amazon Prime", "", TxnType.EXPENSE))
        assertEquals("shopping", CategoryGuesser.guess("Flipkart", "", TxnType.EXPENSE))
        assertEquals("fuel", CategoryGuesser.guess("Indian Oil", "", TxnType.EXPENSE))
        assertEquals("travel", CategoryGuesser.guess("IRCTC", "", TxnType.EXPENSE))
        assertEquals("bills", CategoryGuesser.guess("Airtel", "", TxnType.EXPENSE))
        assertEquals("investment", CategoryGuesser.guess("Zerodha", "", TxnType.EXPENSE))
        assertEquals("transfer", CategoryGuesser.guess("UPI XXXXXX3210", "Sent Rs.500", TxnType.EXPENSE))
        assertEquals("salary", CategoryGuesser.guess("Acme", "NEFT SALARY DEC", TxnType.INCOME))
        assertEquals("interest", CategoryGuesser.guess(null, "Interest of Rs 120 credited to your a/c", TxnType.INCOME))
        assertEquals("cashback", CategoryGuesser.guess(null, "Cashback of Rs 50 credited", TxnType.INCOME))
        assertEquals("other_income", CategoryGuesser.guess("Rahul", "credited", TxnType.INCOME))
        assertEquals("other", CategoryGuesser.guess("Sharma General Stores", "", TxnType.EXPENSE))
        assertTrue(CategoryGuesser.CATEGORY_KEYS.containsAll(listOf("food", "other", "emi")))
    }
}

class SenderTest {
    @Test
    fun financialSenders() {
        listOf("AD-HDFCBK", "VM-ICICIB", "JD-KOTAKB", "BZ-DBSBNK", "AX-ICICIT", "VK-HDFCBK-S", "AD-SBIUPI", "VM-AXISBK", "AD-YESBNK")
            .forEach { assertTrue(it, isLikelyFinancialSender(it)) }
        listOf("+919876543210", "9876543210", "AD-SWIGGY", "VM-AMAZON", "JD-LOANOF")
            .forEach { assertFalse(it, isLikelyFinancialSender(it)) }
    }
}

class DatesAndMoneyTest {
    @Test
    fun dateFormats() {
        assertEquals(java.time.LocalDate.of(2025, 1, 5), Dates.first("on 05-01-25 at")!!.date)
        assertEquals(java.time.LocalDate.of(2025, 1, 5), Dates.first("on 05-Jan-25 on")!!.date)
        assertEquals(java.time.LocalDate.of(2025, 1, 5), Dates.first("on 05/01/2025.")!!.date)
        assertEquals(java.time.LocalDate.of(2025, 1, 5), Dates.first("On 2025-01-05:14:23:11.")!!.date)
        assertEquals(java.time.LocalDate.of(2025, 1, 5), Dates.first("date 05Jan25 trf")!!.date)
        assertEquals(java.time.LocalDate.of(2025, 1, 5), Dates.first("on 05 Jan 2025 towards")!!.date)
        assertEquals(java.time.LocalDate.of(2024, 12, 31), Dates.first("on 31-DEC-24 for")!!.date)
        assertEquals(null, Dates.first("Call 18002586161/SMS BLOCK"))
    }

    @Test
    fun implausibleDateFallsBackToSmsTime() {
        val t = txn("AD-HDFCBK", "Rs.100.00 debited from a/c **1234 on 05-01-29 to VPA abc@okaxis(UPI Ref No 999999999999).")
        assertEquals(SMS_TIME, t.timestampMillis)
    }

    @Test
    fun amountsSkipBalances() {
        assertEquals(50000L, Extract.amount("Avl Bal Rs.20,000.00. Rs.500 debited"))
        assertEquals(12000L, Extract.amount("A/C X1234 debited by 120.0 on date"))
        assertEquals(Money.parse("1,23,456.70"), Extract.amount("INR 1,23,456.70 credited"))
        assertEquals("1234", Extract.last4("A/cX1234 debited"))
        assertEquals("9012", Extract.last4("Credit Card Account 4xxx9012 on"))
    }
}
