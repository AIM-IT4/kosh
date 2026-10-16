package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

private val OCT_12_EVENING: Long = LocalDateTime.of(2026, 10, 12, 18, 30).atZone(ZONE).toInstant().toEpochMilli()

/**
 * UPI AutoPay, e-mandate/NACH/ECS and standing-instruction debits are AUTO_DEBIT transactions; setting up a
 * mandate and advance notices of a debit are not transactions at all.
 */
class AutoPayTest {

    private fun t(sender: String, body: String) = txn(sender, body, OCT_12_EVENING)

    private fun assertAutoDebit(t: ParsedTransaction, rupees: Long, merchant: String?, category: String? = null) {
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(TxnChannel.AUTO_DEBIT, t.channel)
        assertEquals(rupees(rupees), t.amount)
        assertEquals(merchant, t.merchant)
        if (category != null) assertEquals(category, t.suggestedCategory)
    }

    // ---- executions: imported as AUTO_DEBIT ----

    @Test
    fun upiAutoPayExecutedSuccessfullyIsADebit() {
        val t = t(
            "AD-HDFCBK",
            "UPI AutoPay: Rs.199.00 debited from A/c **1234 on 12-10-26 to VPA spotify@hdfcbank. Mandate executed successfully. " +
                "UPI Ref No 628512345678 -HDFC Bank",
        )
        assertAutoDebit(t, 199, "Spotify", "subscriptions")
        assertEquals("1234", t.accountLast4)
        assertEquals("628512345678", t.referenceNo)
    }

    @Test
    fun upiAutoPayToMerchant() {
        val t = t("VM-PNBSMS", "Rs.649.00 debited from A/c XX1234 on 12-10-26 for UPI AutoPay to NETFLIX. UPI Ref 628512345678 -PNB")
        assertEquals(Bank.PNB, t.bank)
        assertAutoDebit(t, 649, "Netflix", "subscriptions")
    }

    @Test
    fun autoPayWordedWithoutDebited() {
        val t = t(
            "AD-YESBNK",
            "UPI Autopay Txn of Rs 299.00 executed successfully for JioHotstar from A/c XX1234 on 12-10-26. UPI Ref 628512345678",
        )
        assertAutoDebit(t, 299, "JioHotstar", "subscriptions")
        assertEquals("1234", t.accountLast4)
    }

    @Test
    fun upiMandateExecutedAtIcici() {
        val t = t(
            "VM-ICICIB",
            "ICICI Bank Acct XX123 debited for Rs 649.00 on 12-Oct-26; NETFLIX credited. UPI:628512345678. UPI Mandate executed. " +
                "Call 18002662 for dispute.",
        )
        assertAutoDebit(t, 649, "Netflix")
    }

    @Test
    fun mandateDebitWithRegisteredMobileBoilerplate() {
        // "registered mobile" is fraud-reporting boilerplate, not a mandate registration
        val t = t("AD-HDFCBK", "Rs 649 debited for UPI mandate NETFLIX from A/c XX1234. If not you, SMS BLOCK from your registered mobile no.")
        assertAutoDebit(t, 649, null, "subscriptions")
    }

    @Test
    fun eMandateDebit() {
        val t = t(
            "AD-IDFCBK",
            "Your A/C XXXXXXX1234 has been debited with INR 5,000.00 on 12-10-2026 towards e-mandate of ZERODHA BROKING LTD. -IDFC FIRST Bank",
        )
        assertAutoDebit(t, 5_000, "Zerodha", "investment")
    }

    @Test
    fun eMandateDebitProcessed() {
        val t = t("AD-HDFCBK", "e-mandate debit of Rs 5,000 for GROWW processed successfully from A/c XX1234 on 12-10-26")
        assertAutoDebit(t, 5_000, "Groww", "investment")
    }

    @Test
    fun nachEmi() {
        val t = t(
            "AD-SBIINB",
            "Your a/c no. XXXXXXXX1234 is debited by Rs.15,000.00 on 12Oct26 towards NACH-DR-BAJAJ FINANCE LTD. Avl Bal Rs.10,000.00 -SBI",
        )
        assertAutoDebit(t, 15_000, "Bajaj Finance", "emi")
    }

    @Test
    fun eNachAndEcs() {
        assertEquals(
            TxnChannel.AUTO_DEBIT,
            t("AD-HDFCBK", "Rs.3,000.00 debited from A/c XX1234 on 12-10-26 towards ENACH/HDFC LIFE INSURANCE. Avl Bal Rs.7,000.00").channel,
        )
        val ecs = t("AD-HDFCBK", "Rs.2,500.00 debited from A/c XX1234 on 12-10-26 by ECS/ICICI PRU LIFE. Avl Bal Rs 7,500.00")
        assertAutoDebit(ecs, 2_500, null, "insurance")
    }

    @Test
    fun standingInstructions() {
        val si = t("AD-CANBNK", "Rs.10,000.00 debited from A/c XX1234 on 12-10-26 towards Standing Instruction to RD A/c XX5678. -Canara Bank")
        assertAutoDebit(si, 10_000, null)
        val executed = t("AD-AXISBK", "SI executed: INR 5,000.00 transferred from A/c XX1234 to A/c XX9876 on 12-10-26. Axis Bank")
        assertAutoDebit(executed, 5_000, null)
        assertEquals("1234", executed.accountLast4)
    }

    @Test
    fun autoDebitedPremium() {
        val t = t("AD-KOTAKB", "Rs.1,499.00 auto debited from your Kotak Bank AC X1234 on 12-10-26 for HDFC LIFE premium. -Kotak Bank")
        assertEquals(TxnChannel.AUTO_DEBIT, t.channel)
        assertEquals("insurance", t.suggestedCategory)
    }

    @Test
    fun ordinaryUpiDebitIsNotAutoDebit() {
        val t = t("VM-PNBSMS", "Rs.250.00 debited from A/c XX1234 on 12-10-26 to VPA swiggy@icici (UPI Ref No 628512345678). -PNB")
        assertEquals(TxnChannel.UPI, t.channel)
    }

    // ---- setup, notices and failures: not transactions ----

    @Test
    fun mandateCreationIsIgnored() {
        listOf(
            "AD-KOTAKB" to "UPI Mandate for Rs.649.00 is set up successfully for NETFLIX on Kotak Bank AC X1234. Ref 512345678900.",
            "AD-HDFCBK" to "Your UPI-Mandate for Rs.199.00 towards Spotify has been successfully created. Mandate will be executed monthly. -HDFC Bank",
            "VM-ICICIB" to "You have set up an AutoPay of Rs.649.00 for NETFLIX on your ICICI Bank account XX123 via UPI. Frequency: Monthly.",
            "AD-HDFCBK" to "E-mandate registration successful. Mandate ID HDFC7021234567 for Rs 15,000.00 towards BAJAJ FINANCE LTD " +
                "has been registered on A/c XX1234.",
            "AD-HDFCBK" to "Dear Customer, your NACH mandate (UMRN HDFC0000000012345678) for Rs.5,000.00 in favour of ICICI PRUDENTIAL MF " +
                "has been successfully registered.",
            "AD-HDFCBK" to "Your AutoPay is set up successfully for Netflix. Max amount Rs.649.00 will be debited monthly from A/c XX1234",
            "AD-AXISBK" to "UPI AutoPay mandate of Rs 499 for Amazon Prime activated successfully on your account XX1234.",
            "AD-YESBNK" to "Your e-mandate registration request for debit of Rs 10,000.00 from A/c XX1234 has been processed successfully.",
            "AD-PNBSMS" to "Your UPI mandate of Rs 649.00 for NETFLIX on A/c XX1234 has been approved. -PNB",
        ).forEach { (sender, body) -> assertEquals(body, "mandate registration", ignored(sender, body)) }
    }

    @Test
    fun mandateRevokedIsIgnored() {
        ignored("AD-HDFCBK", "Your UPI AutoPay mandate of Rs 649.00 for Netflix on A/c XX1234 has been revoked successfully.")
    }

    @Test
    fun advanceNoticesAreIgnored() {
        listOf(
            "AD-SBIINB" to "Pre-debit notification: Rs.649.00 will be auto-debited from A/c XX1234 on 13-10-26 for NETFLIX UPI AutoPay.",
            "AD-YESBNK" to "Your UPI AutoPay of Rs 649.00 for NETFLIX will be executed on 13-10-26 from A/c XX1234. To pause, visit your UPI app.",
            "AD-HDFCBK" to "Standing Instruction of Rs.10,000.00 from A/c XX1234 is scheduled for 15-10-26. Please keep sufficient balance.",
        ).forEach { (sender, body) -> assertEquals(body, "upcoming debit", ignored(sender, body)) }
    }

    @Test
    fun failedAutoPayIsIgnored() {
        assertEquals(
            "failed or declined",
            ignored("AD-HDFCBK", "UPI AutoPay of Rs 649.00 for NETFLIX failed due to insufficient balance in A/c XX1234."),
        )
    }
}
