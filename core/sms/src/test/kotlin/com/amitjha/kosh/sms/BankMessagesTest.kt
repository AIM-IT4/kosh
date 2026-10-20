package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdfcMessagesTest {

    @Test
    fun upiDebitNewFormat() {
        val t = txn(
            "AD-HDFCBK",
            "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 05/01/25\nRef 412345678901\n" +
                "Not You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
        )
        assertEquals(Bank.HDFC, t.bank)
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(25000L, t.amount)
        assertEquals("1234", t.accountLast4)
        assertEquals("Swiggy", t.merchant)
        assertEquals("412345678901", t.referenceNo)
        assertEquals(TxnChannel.UPI, t.channel)
        assertFalse(t.isCreditCard)
        assertEquals("food", t.suggestedCategory)
        assertEquals(LocalDate.of(2025, 1, 5), localDate(t.timestampMillis))
    }

    @Test
    fun upiDebitVpaFormat() {
        val t = txn(
            "VM-HDFCBK",
            "Rs.1,299.00 debited from a/c **1234 on 03-01-25 to VPA blinkit@hdfcbank(UPI Ref No 501234567890). " +
                "Not you? Call on 18002586161 to report",
        )
        assertEquals(129900L, t.amount)
        assertEquals("Blinkit", t.merchant)
        assertEquals("501234567890", t.referenceNo)
        assertEquals("groceries", t.suggestedCategory)
        assertEquals(LocalDate.of(2025, 1, 3), localDate(t.timestampMillis))
    }

    @Test
    fun creditCardSpend() {
        val t = txn(
            "AD-HDFCBK",
            "Spent Rs.1,499.00 On HDFC Bank Card 5678 At AMAZON PAY INDIA PRIVATE LI On 2025-01-05:14:23:11." +
                "Not You? To Block+Reissue Call 18002323232/SMS BLOCK CC 5678 to 7308080808",
        )
        assertTrue(t.isCreditCard)
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(149900L, t.amount)
        assertEquals("5678", t.accountLast4)
        assertEquals("Amazon", t.merchant)
        assertEquals(TxnChannel.CARD, t.channel)
        assertEquals(LocalDateTime.of(2025, 1, 5, 14, 23, 11).atZone(ZONE).toInstant().toEpochMilli(), t.timestampMillis)
    }

    @Test
    fun creditCardSpendWithLimit() {
        val t = txn(
            "JD-HDFCBK-S",
            "Rs.499.00 spent on HDFC Bank Credit Card xx5678 at NETFLIX on 2025-01-04:09:00:00. Avl Lmt: Rs.85,000.00",
        )
        assertTrue(t.isCreditCard)
        assertEquals(49900L, t.amount)
        assertEquals(8500000L, t.availableCreditLimit)
        assertNull(t.availableBalance)
        assertEquals("Netflix", t.merchant)
        assertEquals("subscriptions", t.suggestedCategory)
    }

    @Test
    fun upiCredit() {
        val t = txn(
            "AD-HDFCBK",
            "Rs.5,000.00 credited to HDFC Bank A/c XX1234 on 02-01-25 by a/c linked to VPA 9876543210@ybl " +
                "(UPI Ref No 412345678912).",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(500000L, t.amount)
        assertEquals("1234", t.accountLast4)
        assertEquals("UPI XXXXXX3210", t.merchant)
        assertEquals("412345678912", t.referenceNo)
    }

    @Test
    fun neftSalaryCredit() {
        val t = txn(
            "VK-HDFCBK",
            "Update! INR 1,25,000.00 deposited in HDFC Bank A/c XX1234 on 31-DEC-24 for NEFT Cr-CITI0000001-" +
                "ACME TECHNOLOGIES PVT LTD-SALARY DEC 2024-CITIN24365123456.Avl bal INR 2,45,678.90. " +
                "Cheque deposits in A/C are subject to clearing",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(12500000L, t.amount)
        assertEquals(24567890L, t.availableBalance)
        assertEquals("Acme Technologies", t.merchant)
        assertEquals(TxnChannel.NEFT, t.channel)
        assertEquals("salary", t.suggestedCategory)
        assertEquals(LocalDate.of(2024, 12, 31), localDate(t.timestampMillis))
    }

    @Test
    fun atmWithdrawal() {
        val t = txn(
            "AD-HDFCBK",
            "Rs.5,000.00 withdrawn from HDFC Bank A/c XX1234 at ATM-MUMBAI ANDHERI on 04-01-25. Avl bal: Rs.20,000.00",
        )
        assertEquals(TxnChannel.ATM, t.channel)
        assertEquals("ATM withdrawal", t.merchant)
        assertEquals(2000000L, t.availableBalance)
        assertEquals(TxnType.EXPENSE, t.type)
    }

    @Test
    fun nachEmiDebit() {
        val t = txn(
            "AD-HDFCBK",
            "Rs.15,000.00 debited from A/c XX1234 on 05-01-25 towards NACH-DR- BAJAJ FINANCE LTD EMI. " +
                "Avl Bal Rs.10,234.00 -HDFC Bank",
        )
        assertEquals(TxnChannel.AUTO_DEBIT, t.channel)
        assertEquals("emi", t.suggestedCategory)
        assertEquals(1023400L, t.availableBalance)
        assertEquals(1500000L, t.amount)
    }

    @Test
    fun statementBill() {
        val b = bill(
            "AD-HDFCBK",
            "HDFC Bank Credit Card XX5678 Statement: Total due: Rs.12,345.67, Minimum due: Rs.620.00, Due by 25-01-2025.",
        )
        assertEquals(Bank.HDFC, b.bank)
        assertEquals("5678", b.cardLast4)
        assertEquals(1234567L, b.totalDue)
        assertEquals(62000L, b.minimumDue)
        assertEquals(epochDay(2025, 1, 25), b.dueDateEpochDay)
    }

    @Test
    fun cardPaymentReceived() {
        val t = txn(
            "AD-HDFCBK",
            "Payment of Rs.12,345.67 received towards your HDFC Bank Credit Card ending 5678 on 20-01-2025. Thank you.",
            time = LocalDateTime.of(2025, 1, 20, 10, 0).atZone(ZONE).toInstant().toEpochMilli(),
        )
        assertTrue(t.isCardPayment)
        assertTrue(t.isCreditCard)
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(1234567L, t.amount)
        assertEquals("5678", t.accountLast4)
        assertEquals("transfer", t.suggestedCategory)
    }
}

class IciciMessagesTest {

    @Test
    fun creditCardSpend() {
        val t = txn(
            "VM-ICICIT",
            "INR 2,350.00 spent using ICICI Bank Card XX9012 on 05-Jan-25 on AMAZON. Avl Limit: INR 1,45,000.00. " +
                "If not you, call 1800 2662/SMS BLOCK 9012 to 9215676766.",
        )
        assertEquals(Bank.ICICI, t.bank)
        assertTrue(t.isCreditCard)
        assertEquals(235000L, t.amount)
        assertEquals("9012", t.accountLast4)
        assertEquals("Amazon", t.merchant)
        assertEquals(14500000L, t.availableCreditLimit)
        assertEquals("shopping", t.suggestedCategory)
    }

    @Test
    fun upiDebitMerchantCredited() {
        val t = txn(
            "AX-ICICIB",
            "ICICI Bank Acct XX345 debited for Rs 450.00 on 04-Jan-25; Zomato credited. UPI:412345678901. " +
                "Call 18002662 for dispute. SMS BLOCK 345 to 9215676766.",
        )
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(45000L, t.amount)
        assertEquals("345", t.accountLast4)
        assertEquals("Zomato", t.merchant)
        assertEquals("412345678901", t.referenceNo)
        assertEquals(TxnChannel.UPI, t.channel)
        assertFalse(t.isCreditCard)
    }

    @Test
    fun upiCreditFromPerson() {
        val t = txn(
            "VM-ICICIB",
            "Dear Customer, Acct XX345 is credited with Rs 25,000.00 on 04-Jan-25 from RAHUL SHARMA. UPI:412345678999-ICICI Bank.",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(2500000L, t.amount)
        assertEquals("Rahul Sharma", t.merchant)
        assertEquals("412345678999", t.referenceNo)
    }

    @Test
    fun neftSalary() {
        val t = txn(
            "VM-ICICIB",
            "ICICI Bank Account XX345 credited:Rs. 1,10,000.00 on 31-Dec-24. Info NEFT-HSBCN24365987654-ACME CORP SALARY. " +
                "Available Balance is Rs. 2,10,500.00.",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(11000000L, t.amount)
        assertEquals(21050000L, t.availableBalance)
        assertEquals("salary", t.suggestedCategory)
        assertEquals(TxnChannel.NEFT, t.channel)
    }

    @Test
    fun refundToCard() {
        val t = txn(
            "VM-ICICIT",
            "Dear Customer, a refund of INR 499.00 from AMAZON has been credited to your ICICI Bank Credit Card XX9012 on 04-Jan-25.",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertTrue(t.isCreditCard)
        assertFalse(t.isCardPayment)
        assertEquals("Amazon", t.merchant)
        assertEquals("refund", t.suggestedCategory)
    }

    @Test
    fun statementBill() {
        val b = bill(
            "VM-ICICIT",
            "ICICI Bank Credit Card XX9012 statement for Jan 2025: Total Amount Due INR 18,500.00, " +
                "Minimum Amount Due INR 925.00. Due date: 22-Feb-25.",
        )
        assertEquals(1850000L, b.totalDue)
        assertEquals(92500L, b.minimumDue)
        assertEquals(epochDay(2025, 2, 22), b.dueDateEpochDay)
        assertEquals("9012", b.cardLast4)
    }

    @Test
    fun dueReminderIsABillWithSameKeyAsStatement() {
        val statement = bill(
            "VM-ICICIT",
            "ICICI Bank Credit Card XX9012 statement for Jan 2025: Total Amount Due INR 18,500.00, " +
                "Minimum Amount Due INR 925.00. Due date: 22-Feb-25.",
        )
        val reminder = bill(
            "VM-ICICIT",
            "Payment of INR 18,500.00 for ICICI Bank Credit Card XX9012 is due on 22-Feb-25. Please ignore if paid.",
        )
        assertEquals(statement.fingerprint, reminder.fingerprint)
    }

    @Test
    fun cardPaymentReceived() {
        val t = txn(
            "VM-ICICIT",
            "Dear Customer, Payment of INR 18,500.00 has been received on your ICICI Bank Credit Card Account 4xxx9012 on 20-Feb-25. Thank you.",
            time = LocalDateTime.of(2025, 2, 20, 9, 0).atZone(ZONE).toInstant().toEpochMilli(),
        )
        assertTrue(t.isCardPayment)
        assertEquals("9012", t.accountLast4)
        assertEquals(1850000L, t.amount)
    }

    @Test
    fun declinedIsIgnored() {
        ignored(
            "VM-ICICIT",
            "Your transaction of INR 2,000.00 on ICICI Bank Card XX9012 at FLIPKART has been declined due to insufficient limit.",
        )
    }
}

class KotakMessagesTest {

    @Test
    fun upiSent() {
        val t = txn(
            "JD-KOTAKB",
            "Sent Rs.320.00 from Kotak Bank AC X1234 to blinkit@okaxis on 05-01-25.UPI Ref 412345678901. " +
                "Not you, https://kotak.com/KBANKT/Fraud",
        )
        assertEquals(Bank.KOTAK, t.bank)
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(32000L, t.amount)
        assertEquals("1234", t.accountLast4)
        assertEquals("Blinkit", t.merchant)
        assertEquals("412345678901", t.referenceNo)
        assertEquals(TxnChannel.UPI, t.channel)
    }

    @Test
    fun upiReceived() {
        val t = txn(
            "AD-KOTAKB",
            "Received Rs.2,000.00 in your Kotak Bank AC X1234 from rahul.sharma@okhdfcbank on 05-01-25.UPI Ref:412345678902.",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(200000L, t.amount)
        assertEquals("Rahul Sharma", t.merchant)
    }

    @Test
    fun creditCardSpend() {
        val t = txn(
            "AD-KOTAKB",
            "Rs.899.00 spent on Kotak Credit Card x4321 at NETFLIX on 05/01/2025. Avl limit Rs.48,101.00. Not you? Call 18602662666",
        )
        assertTrue(t.isCreditCard)
        assertEquals(89900L, t.amount)
        assertEquals("4321", t.accountLast4)
        assertEquals("Netflix", t.merchant)
        assertEquals(4810100L, t.availableCreditLimit)
    }

    @Test
    fun atmWithdrawal() {
        val t = txn("AD-KOTAKB", "Rs.2,000 withdrawn from Kotak Bank AC X1234 at ATM on 05-01-25. Avl Bal Rs.18,500.00")
        assertEquals(TxnChannel.ATM, t.channel)
        assertEquals(200000L, t.amount)
        assertEquals(1850000L, t.availableBalance)
    }

    @Test
    fun neftCredit() {
        val t = txn(
            "AD-KOTAKB",
            "Your a/c X1234 is credited by Rs.75,000.00 on 31-12-24 by NEFT from ACME TECH PVT LTD. Avl Bal: Rs.93,500.00 -Kotak Bank",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(7500000L, t.amount)
        assertEquals("Acme Tech", t.merchant)
        assertEquals(9350000L, t.availableBalance)
        assertEquals(TxnChannel.NEFT, t.channel)
    }

    @Test
    fun statementBill() {
        val b = bill(
            "AD-KOTAKB",
            "Kotak Credit Card x4321 Statement: Total Amount Due Rs.5,432.10, Minimum Amount Due Rs.272.00, Due Date 15-Feb-2025.",
        )
        assertEquals(543210L, b.totalDue)
        assertEquals(27200L, b.minimumDue)
        assertEquals(epochDay(2025, 2, 15), b.dueDateEpochDay)
    }

    @Test
    fun collectRequestIgnored() {
        ignored(
            "AD-KOTAKB",
            "rahul@okaxis has requested money of Rs.500.00 from your Kotak Bank AC X1234 via UPI. " +
                "On approving, money will be debited from your account.",
        )
    }

    @Test
    fun mandateRegistrationIgnored() {
        ignored(
            "AD-KOTAKB",
            "UPI Mandate for Rs.649.00 is set up successfully for NETFLIX on Kotak Bank AC X1234. Ref 512345678900.",
        )
    }
}

class DbsMessagesTest {

    @Test
    fun upiDebitAccountFormat() {
        val t = txn(
            "BZ-DBSBNK",
            "Your account no. XXXXXX1234 has been debited with INR 650.00 on 05-01-2025 for UPI txn to UBER INDIA. " +
                "Avl Bal: INR 32,100.50 - DBS Bank",
        )
        assertEquals(Bank.DBS, t.bank)
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(65000L, t.amount)
        assertEquals("1234", t.accountLast4)
        assertEquals("Uber", t.merchant)
        assertEquals(3210050L, t.availableBalance)
        assertEquals("transport", t.suggestedCategory)
    }

    @Test
    fun digibankUpiFormat() {
        val t = txn(
            "VM-DBSBNK",
            "Rs. 650.00 debited from a/c ending 1234 on 05 Jan 2025 towards UPI/uber@axisbank/Ref 412345678903. -digibank by DBS",
        )
        assertEquals("Uber", t.merchant)
        assertEquals("412345678903", t.referenceNo)
        assertEquals("1234", t.accountLast4)
    }

    @Test
    fun salaryCredit() {
        val t = txn(
            "BZ-DBSBNK",
            "Your account no. XXXXXX1234 has been credited with INR 1,00,000.00 on 31-12-2024. Info: NEFT/ACME LTD SALARY. " +
                "Avl Bal: INR 1,32,100.50 - DBS Bank",
        )
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(10000000L, t.amount)
        assertEquals("salary", t.suggestedCategory)
        assertEquals(13210050L, t.availableBalance)
    }

    @Test
    fun creditCardSpend() {
        val t = txn(
            "BZ-DBSBNK",
            "INR 1,250.00 spent on DBS Bank Credit Card ending 7788 at SPOTIFY on 05-01-2025. Avl limit: INR 98,750.00",
        )
        assertTrue(t.isCreditCard)
        assertEquals("7788", t.accountLast4)
        assertEquals("Spotify", t.merchant)
        assertEquals(9875000L, t.availableCreditLimit)
    }

    @Test
    fun debitCardPos() {
        val t = txn(
            "BZ-DBSBNK",
            "Your DBS Debit Card ending 1234 has been used for INR 3,200.00 at BIGBASKET on 05-Jan-2025 13:20. Avl Bal INR 28,900.50",
        )
        assertFalse(t.isCreditCard)
        assertEquals(TxnChannel.CARD, t.channel)
        assertEquals("BigBasket", t.merchant)
        assertEquals("groceries", t.suggestedCategory)
        assertEquals(2890050L, t.availableBalance)
        assertEquals(LocalDateTime.of(2025, 1, 5, 13, 20).atZone(ZONE).toInstant().toEpochMilli(), t.timestampMillis)
    }

    @Test
    fun statementBill() {
        val b = bill(
            "BZ-DBSBNK",
            "DBS Bank Credit Card ending 7788: Statement generated. Total Amount Due INR 9,876.00. " +
                "Minimum Amount Due INR 494.00. Payment due by 18-Feb-2025.",
        )
        assertEquals(987600L, b.totalDue)
        assertEquals(49400L, b.minimumDue)
        assertEquals(epochDay(2025, 2, 18), b.dueDateEpochDay)
        assertEquals("7788", b.cardLast4)
    }
}

class OtherBanksTest {

    @Test
    fun sbiUpiDebit() {
        val t = txn(
            "AD-SBIUPI",
            "Dear UPI user A/C X1234 debited by 120.0 on date 05Jan25 trf to ZEPTO Refno 412345678904. " +
                "If not u? call 1800111109. -SBI",
        )
        assertEquals(Bank.SBI, t.bank)
        assertEquals(12000L, t.amount)
        assertEquals("Zepto", t.merchant)
        assertEquals("412345678904", t.referenceNo)
        assertEquals(LocalDate.of(2025, 1, 5), localDate(t.timestampMillis))
    }

    @Test
    fun axisUpiDebitMultiline() {
        val t = txn(
            "VM-AXISBK",
            "INR 1,000.00 debited\nA/c no. XX1234\n05-01-25, 10:15:20\nUPI/P2M/412345678906/SWIGGY\n" +
                "Not you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank",
        )
        assertEquals(Bank.AXIS, t.bank)
        assertEquals(100000L, t.amount)
        assertEquals("Swiggy", t.merchant)
        assertEquals(LocalDateTime.of(2025, 1, 5, 10, 15, 20).atZone(ZONE).toInstant().toEpochMilli(), t.timestampMillis)
    }

    @Test
    fun genericBankFallback() {
        // RBL Bank isn't modelled individually (Yes Bank, used here before, now is: see MoreBanksTest).
        val t = txn(
            "AD-RBLBNK",
            "Rs.2,500.00 debited from your A/c XX9876 on 05-01-2025 for UPI txn to cred.club@axisbank. Avl Bal Rs.12,000.00",
        )
        assertEquals(Bank.OTHER, t.bank)
        assertEquals(250000L, t.amount)
        assertEquals("CRED", t.merchant)
        assertEquals("9876", t.accountLast4)
    }

    @Test
    fun balanceStatementIsBalanceUpdate() {
        val r = SmsParser.parse(
            sms("AD-HDFCBK", "Available balance in your HDFC Bank A/c XX1234 as on 05-01-25 is Rs.20,345.67. Thank you."),
        )
        assertTrue(r is SmsParseResult.BalanceUpdate)
        val u = (r as SmsParseResult.BalanceUpdate).update
        assertEquals(2034567L, u.availableBalance)
        assertEquals("1234", u.accountLast4)
    }

    @Test
    fun aBalanceAsOnAnEarlierDayHoldsAtTheEndOfThatDay() {
        // SMS_TIME is 5 Jan 2025 18:30 IST; the balance is as on 2 Jan.
        val u = (SmsParser.parse(
            sms("AD-HDFCBK", "Available balance in your HDFC Bank A/c XX1234 as on 02-01-25 is Rs.20,345.67. Thank you."),
        ) as SmsParseResult.BalanceUpdate).update
        val endOfJan2 = java.time.LocalDate.of(2025, 1, 3).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1
        assertEquals(endOfJan2, u.timestampMillis)
        // As on today: the message's own time.
        val today = (SmsParser.parse(
            sms("AD-HDFCBK", "Available balance in your HDFC Bank A/c XX1234 as on 05-01-25 is Rs.20,345.67. Thank you."),
        ) as SmsParseResult.BalanceUpdate).update
        assertEquals(SMS_TIME, today.timestampMillis)
    }
}
