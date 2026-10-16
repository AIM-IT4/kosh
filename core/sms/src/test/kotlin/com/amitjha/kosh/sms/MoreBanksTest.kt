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

/** SMS received at 2026-10-12 18:30 IST, so the dates in these messages are within range. */
private val OCT_12: Long = LocalDateTime.of(2026, 10, 12, 18, 30).atZone(ZONE).toInstant().toEpochMilli()

private fun at(h: Int, m: Int, s: Int = 0): Long = LocalDateTime.of(2026, 10, 12, h, m, s).atZone(ZONE).toInstant().toEpochMilli()

/**
 * Alerts from the banks identified by sender and parsed with the generic rules: PNB, Bank of Baroda, Canara,
 * Union Bank, IDFC FIRST, IndusInd, Yes Bank, AU Small Finance Bank and Federal Bank.
 */
class MoreBanksTest {

    private fun t(sender: String, body: String) = txn(sender, body, OCT_12)

    private fun assertTxn(
        t: ParsedTransaction,
        bank: Bank,
        rupees: Long,
        type: TxnType,
        last4: String,
        merchant: String?,
    ) {
        assertEquals(bank, t.bank)
        assertEquals(rupees(rupees), t.amount)
        assertEquals(type, t.type)
        assertEquals(last4, t.accountLast4)
        assertEquals(merchant, t.merchant)
    }

    // ---- Punjab National Bank ----

    @Test
    fun pnbUpiDebitToVpa() {
        val t = t(
            "VM-PNBSMS",
            "Rs.250.00 debited from A/c XX1234 on 12-10-26 to VPA swiggy@icici (UPI Ref No 628512345678). " +
                "If not you, call 18001802222 -PNB",
        )
        assertTxn(t, Bank.PNB, 250, TxnType.EXPENSE, "1234", "Swiggy")
        assertEquals("628512345678", t.referenceNo)
        assertEquals(TxnChannel.UPI, t.channel)
        assertEquals("food", t.suggestedCategory)
        assertEquals(LocalDate.of(2026, 10, 12), localDate(t.timestampMillis))
    }

    @Test
    fun pnbUpiDebitWithoutPayeeIgnoresFraudBoilerplate() {
        val t = t(
            "AD-PUNBNK",
            "Ac XXXXXXXX1234 debited with INR 500.00 on 12-10-2026 14:20:11 thru UPI:628512345678. Aval Bal INR 5,000.00 " +
                "Not you? Fwd this SMS to 9264092640 to block UPI.-PNB",
        )
        // "to block UPI" is not a payee
        assertTxn(t, Bank.PNB, 500, TxnType.EXPENSE, "1234", null)
        assertEquals(rupees(5_000), t.availableBalance)
        assertEquals("628512345678", t.referenceNo)
        assertEquals(at(14, 20, 11), t.timestampMillis)
    }

    @Test
    fun pnbNeftCredit() {
        val t = t(
            "VM-PNBSMS",
            "A/c XX1234 credited INR 25,000.00 Dt 12-10-26 15:30:00 thru NEFT:CITIN26285123456 from ACME TECHNOLOGIES PVT LTD. " +
                "Bal INR 30,000.00 CR -PNB",
        )
        assertTxn(t, Bank.PNB, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
        assertEquals(TxnChannel.NEFT, t.channel)
        assertEquals(rupees(30_000), t.availableBalance)
    }

    // ---- Bank of Baroda ----

    @Test
    fun bobUpiDrCrFormat() {
        val t = t(
            "VM-BOBTXN",
            "Rs.500.00 Dr. from A/C XXXXXX1234 and Cr. to zomato@hdfcbank. Ref:628512345678. AvlBal:Rs4500.00" +
                "(2026:10:12 14:20:11). Not you? Call 18005700/5000-Bank of Baroda",
        )
        assertTxn(t, Bank.BOB, 500, TxnType.EXPENSE, "1234", "Zomato")
        assertEquals("628512345678", t.referenceNo)
        assertEquals(rupees(4_500), t.availableBalance)
        assertEquals(at(14, 20, 11), t.timestampMillis)
        assertEquals("food", t.suggestedCategory)
    }

    @Test
    fun bobCreditCardSpend() {
        val t = t(
            "AD-BOBCRD",
            "INR 2,499.00 spent on your Bank of Baroda Credit Card XX4321 at FLIPKART on 12-10-2026. " +
                "Avl Limit: INR 47,501.00. Not you? Call 18002251-BOBCARD",
        )
        assertTxn(t, Bank.BOB, 2_499, TxnType.EXPENSE, "4321", "Flipkart")
        assertTrue(t.isCreditCard)
        assertEquals(TxnChannel.CARD, t.channel)
        assertEquals(rupees(47_501), t.availableCreditLimit)
    }

    @Test
    fun bobUpiCreditWithDottedAccount() {
        val t = t(
            "VM-BOBSMS",
            "Rs.25,000.00 Credited to A/c ...1234 thru UPI/628512345678 by 9876543210@ybl. Total Bal:Rs.30,000.00CR. " +
                "Avlbl Amt:Rs.30,000.00(12-10-2026 15:30:00) - Bank of Baroda",
        )
        assertTxn(t, Bank.BOB, 25_000, TxnType.INCOME, "1234", "UPI XXXXXX3210")
        assertEquals(rupees(30_000), t.availableBalance)
        assertEquals(at(15, 30), t.timestampMillis)
    }

    // ---- Canara Bank ----

    @Test
    fun canaraUpiDebitToAccount() {
        val t = t(
            "AD-CANBNK",
            "Your a/c no. XXXXXXXX1234 is debited for Rs.500.00 on 12-10-2026 and credited to a/c no. XXXXXXXX5678 " +
                "(UPI Ref no 628512345678)-Canara Bank",
        )
        // the payee is only an account number, so there is no merchant name
        assertTxn(t, Bank.CANARA, 500, TxnType.EXPENSE, "1234", null)
        assertEquals("628512345678", t.referenceNo)
        assertEquals(TxnChannel.UPI, t.channel)
    }

    @Test
    fun canaraUpiDebitTowardsVpa() {
        val t = t(
            "VM-CANBKS",
            "An amount of INR 250.00 has been DEBITED to your account XXX1234 on 12/10/2026 towards UPI/swiggy@icici. " +
                "Total Avail.bal INR 5,000.00. - Canara Bank",
        )
        assertTxn(t, Bank.CANARA, 250, TxnType.EXPENSE, "1234", "Swiggy")
        assertEquals(rupees(5_000), t.availableBalance)
    }

    @Test
    fun canaraNeftCredit() {
        val t = t(
            "AD-CANBNK",
            "An amount of INR 25,000.00 has been CREDITED to your account XXX1234 on 12/10/2026 towards " +
                "NEFT/CITIN26285123456/ACME TECHNOLOGIES PVT LTD. Total Avail.bal INR 30,000.00. - Canara Bank",
        )
        assertTxn(t, Bank.CANARA, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
        assertEquals(TxnChannel.NEFT, t.channel)
    }

    // ---- Union Bank of India ----

    @Test
    fun unionDebitWithRupeeColonFormat() {
        val t = t(
            "AD-UNIONB",
            "A/c *1234 Debited for Rs:500.00 on 12-10-2026 14:20:11 by Mob Bk ref no 628512345678 Avl Bal Rs:5,000.00." +
                "If not you, Fwd this SMS to 9223008486 to block UPI or Call 18002082244-Union Bank of India",
        )
        assertTxn(t, Bank.UNION, 500, TxnType.EXPENSE, "1234", null)
        assertEquals(rupees(5_000), t.availableBalance)
        assertEquals("628512345678", t.referenceNo)
        assertEquals(at(14, 20, 11), t.timestampMillis)
    }

    @Test
    fun unionUpiDebitWithPayee() {
        val t = t(
            "VM-UBOIND",
            "Your A/c XX1234 is debited for Rs.1,250.00 on 12-10-26 towards UPI/628512345678/ZOMATO LTD. " +
                "Avl Bal Rs.3,750.00 -Union Bank of India",
        )
        assertTxn(t, Bank.UNION, 1_250, TxnType.EXPENSE, "1234", "Zomato")
        assertEquals(rupees(3_750), t.availableBalance)
    }

    @Test
    fun unionNeftCredit() {
        val t = t(
            "AD-UNIONB",
            "A/c *1234 Credited for Rs:25,000.00 on 12-10-2026 10:15:00 by NEFT ref no CITIN26285123456 from " +
                "ACME TECHNOLOGIES PVT LTD Avl Bal Rs:30,000.00 -Union Bank of India",
        )
        assertTxn(t, Bank.UNION, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
        assertEquals(rupees(30_000), t.availableBalance)
        assertEquals(TxnChannel.NEFT, t.channel)
    }

    // ---- IDFC FIRST Bank ----

    @Test
    fun idfcCreditCardSpend() {
        val t = t(
            "VM-IDFCFB",
            "INR 1,250.00 spent on your IDFC FIRST Bank Credit Card ending XX4321 at AMAZON on 12-OCT-26. Avl Limit: INR 98,750.00",
        )
        assertTxn(t, Bank.IDFC, 1_250, TxnType.EXPENSE, "4321", "Amazon")
        assertTrue(t.isCreditCard)
        assertEquals(rupees(98_750), t.availableCreditLimit)
        assertNull(t.availableBalance)
        assertEquals("shopping", t.suggestedCategory)
    }

    @Test
    fun idfcUpiDebit() {
        val t = t(
            "AD-IDFCBK",
            "Your A/C XXXXXXX1234 has been debited with INR 499.00 on 12/10/2026 14:20 towards UPI/628512345678/NETFLIX. " +
                "New Bal: INR 5,000.00. Not you? Call 18001080888 -IDFC FIRST Bank",
        )
        assertTxn(t, Bank.IDFC, 499, TxnType.EXPENSE, "1234", "Netflix")
        assertFalse(t.isCreditCard)
        assertEquals(rupees(5_000), t.availableBalance)
        assertEquals("subscriptions", t.suggestedCategory)
    }

    @Test
    fun idfcNeftCredit() {
        val t = t(
            "VM-IDFCFB",
            "Your A/C XXXXXXX1234 has been credited with INR 25,000.00 on 12/10/2026 10:15 by NEFT from " +
                "ACME TECHNOLOGIES PVT LTD. New Bal: INR 30,000.00. -IDFC FIRST Bank",
        )
        assertTxn(t, Bank.IDFC, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
        assertEquals(at(10, 15), t.timestampMillis)
    }

    // ---- IndusInd Bank ----

    @Test
    fun indusIndCardSpend() {
        val t = t(
            "AD-INDUSB",
            "INR 2,500.00 spent on IndusInd Card XX1234 on 12-10-26 at MYNTRA. Avl Lmt: INR 47,500.00. Not you? Call 18602677777",
        )
        assertTxn(t, Bank.INDUSIND, 2_500, TxnType.EXPENSE, "1234", "Myntra")
        assertTrue(t.isCreditCard)
        assertEquals(rupees(47_500), t.availableCreditLimit)
    }

    @Test
    fun indusIndUpiP2mDebit() {
        val t = t(
            "VM-INDUSN",
            "A/C *XX1234 debited by Rs 350.00 towards UPI/P2M/628512345678/BLINKIT. Avl Bal: Rs 12,000.00. " +
                "Not you? Call 18602677777 - IndusInd Bank",
        )
        assertTxn(t, Bank.INDUSIND, 350, TxnType.EXPENSE, "1234", "Blinkit")
        assertEquals("groceries", t.suggestedCategory)
    }

    @Test
    fun indusIndNeftCredit() {
        val t = t(
            "AD-INDUSB",
            "Your A/C 2001XXXX1234 has been credited with INR 25,000.00 on 12-Oct-26 towards " +
                "NEFT/CITIN26285123456/ACME TECHNOLOGIES. Avl Bal INR 30,000.00 -IndusInd Bank",
        )
        assertTxn(t, Bank.INDUSIND, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
    }

    // ---- Yes Bank ----

    @Test
    fun yesUpiDebit() {
        val t = t(
            "AD-YESBNK",
            "INR 500.00 debited from A/c XX1234 on 12-OCT-2026 14:20:11 to VPA swiggy@icici (UPI Ref No 628512345678). " +
                "Avl Bal INR 5,000.00. -YES BANK",
        )
        assertTxn(t, Bank.YES, 500, TxnType.EXPENSE, "1234", "Swiggy")
        assertEquals(at(14, 20, 11), t.timestampMillis)
    }

    @Test
    fun yesCreditCardSpend() {
        val t = t(
            "VM-YESBK",
            "INR 1,299.00 spent on YES BANK Credit Card XX5678 at FLIPKART on 12-10-2026 14:20:11. Avl Lmt INR 48,701.00",
        )
        assertTxn(t, Bank.YES, 1_299, TxnType.EXPENSE, "5678", "Flipkart")
        assertTrue(t.isCreditCard)
        assertEquals(rupees(48_701), t.availableCreditLimit)
    }

    @Test
    fun yesNeftCredit() {
        val t = t(
            "AD-YESBNK",
            "INR 25,000.00 credited to A/c XX1234 on 12-10-2026 by NEFT from ACME TECHNOLOGIES PVT LTD. " +
                "Avl Bal INR 30,000.00 -YES BANK",
        )
        assertTxn(t, Bank.YES, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
    }

    // ---- AU Small Finance Bank ----

    @Test
    fun auUpiDebit() {
        val t = t(
            "AD-AUBANK",
            "Debited INR 650.00 from A/c XX1234 on 12-OCT-26 to uber@axisbank. UPI Ref 628512345678. " +
                "Avl Bal INR 4,350.00 - AU Small Finance Bank",
        )
        assertTxn(t, Bank.AU, 650, TxnType.EXPENSE, "1234", "Uber")
        assertEquals("transport", t.suggestedCategory)
    }

    @Test
    fun auCreditCardSpend() {
        val t = t("VM-AUBNKS", "INR 3,200.00 spent at BIGBASKET on AU Bank Credit Card XX9876 on 12-10-2026. Avl Limit INR 96,800.00")
        assertTxn(t, Bank.AU, 3_200, TxnType.EXPENSE, "9876", "BigBasket")
        assertTrue(t.isCreditCard)
        assertEquals(rupees(96_800), t.availableCreditLimit)
    }

    @Test
    fun auNeftCredit() {
        val t = t(
            "AD-AUBANK",
            "Credited INR 25,000.00 to your A/c XX1234 on 12-10-2026 via NEFT from ACME TECHNOLOGIES PVT LTD. " +
                "Avl Bal INR 30,000.00 - AU Small Finance Bank",
        )
        assertTxn(t, Bank.AU, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
    }

    // ---- Federal Bank ----

    @Test
    fun federalUpiDebitNameFirst() {
        val t = t(
            "AD-FEDBNK",
            "Dear Customer, A/C X1234 debited by INR 799.00 on 12-Oct-26 for UPI/NETFLIX/628512345678. Avl Bal INR 4,201.00 -Federal Bank",
        )
        assertTxn(t, Bank.FEDERAL, 799, TxnType.EXPENSE, "1234", "Netflix")
        assertEquals("subscriptions", t.suggestedCategory)
        assertEquals(rupees(4_201), t.availableBalance)
    }

    @Test
    fun federalUpiDebitToVpa() {
        val t = t(
            "VM-FEDFIB",
            "Rs 450.00 debited from your A/c XX1234 on 12-10-2026 14:20:11 to VPA zomato@hdfcbank (UPI Ref No 628512345678). " +
                "Not you? Call 18004251199 -Federal Bank",
        )
        assertTxn(t, Bank.FEDERAL, 450, TxnType.EXPENSE, "1234", "Zomato")
    }

    @Test
    fun federalNeftCredit() {
        val t = t(
            "AD-FEDBNK",
            "Dear Customer, A/C X1234 credited with INR 25,000.00 on 12-Oct-26 by NEFT from ACME TECHNOLOGIES PVT LTD. " +
                "Avl Bal INR 30,000.00 -Federal Bank",
        )
        assertTxn(t, Bank.FEDERAL, 25_000, TxnType.INCOME, "1234", "Acme Technologies")
        assertEquals(rupees(30_000), t.availableBalance)
    }

    // ---- Senders ----

    @Test
    fun dltHeadersMapToTheirBank() {
        val headers = mapOf(
            "PNBSMS" to Bank.PNB, "PUNBNK" to Bank.PNB,
            "BOBTXN" to Bank.BOB, "BOBSMS" to Bank.BOB, "BOBCRD" to Bank.BOB,
            "CANBNK" to Bank.CANARA, "CANBKS" to Bank.CANARA,
            "UNIONB" to Bank.UNION, "UBOIND" to Bank.UNION,
            "IDFCFB" to Bank.IDFC, "IDFCBK" to Bank.IDFC,
            "INDUSB" to Bank.INDUSIND, "INDUSN" to Bank.INDUSIND,
            "YESBNK" to Bank.YES, "YESBK" to Bank.YES,
            "AUBANK" to Bank.AU, "AUBNKS" to Bank.AU,
            "FEDBNK" to Bank.FEDERAL, "FEDFIB" to Bank.FEDERAL,
        )
        for ((h, bank) in headers) {
            for (sender in listOf("AD-$h", "VM-$h-S", h.lowercase())) {
                assertEquals(sender, bank, bankFromSenderName(sender))
                assertTrue(sender, isLikelyFinancialSender(sender))
            }
        }
    }

    @Test
    fun rcsBusinessNames() {
        mapOf(
            "Punjab National Bank" to Bank.PNB, "Bank of Baroda" to Bank.BOB, "Canara Bank" to Bank.CANARA,
            "Union Bank of India" to Bank.UNION, "IDFC FIRST Bank" to Bank.IDFC, "IndusInd Bank" to Bank.INDUSIND,
            "Yes Bank" to Bank.YES, "AU Small Finance Bank" to Bank.AU, "Federal Bank" to Bank.FEDERAL,
        ).forEach { (name, bank) -> assertEquals(name, bank, bankFromSenderName(name)) }
    }

    @Test
    fun relatedBrandsAndGenericWordsAreNotBanks() {
        listOf("AD-UNIONMF", "VM-INDUSTOWER", "Canara HSBC Life", "Canara Robeco MF", "AD-YESMDM", "Union Mutual Fund", "PNB MetLife")
            .forEach { assertNull(it, bankFromSenderName(it)) }
    }

    @Test
    fun shortCodeUsesTheBankNamedInTheTextNotThePayeesUpiHandle() {
        val t = t(
            "57575",
            "Rs.250.00 debited from A/c XX1234 on 12-10-26 to VPA swiggy@icici (UPI Ref No 628512345678). -Punjab National Bank",
        )
        assertEquals(Bank.PNB, t.bank)
        // the payee's handle alone still decides when no bank is named
        assertEquals(Bank.ICICI, t("57575", "Rs.250.00 debited from A/c XX1234 on 12-10-26 to VPA swiggy@icici (UPI Ref No 628512345678).").bank)
    }

    @Test
    fun unmodelledBanksStayOther() {
        val t = t("AD-RBLBNK", "Rs.2,500.00 debited from your A/c XX9876 on 12-10-2026 for UPI txn to cred.club@axisbank. Avl Bal Rs.12,000.00")
        assertEquals(Bank.OTHER, t.bank)
    }

    /**
     * These banks were filed under OTHER (or under a bank matched by a UPI handle) before they were modelled.
     * The fingerprints below were produced by that older version, so a re-scan of the inbox must reproduce them
     * exactly or every message would be imported a second time.
     */
    @Test
    fun fingerprintsMatchTheOnesImportedBeforeTheseBanksWereModelled() {
        assertEquals(
            "9e10147398d48140438c6ffb",
            t(
                "AD-YESBNK",
                "INR 500.00 debited from A/c XX1234 on 12-OCT-2026 14:20:11 to VPA swiggy@icici (UPI Ref No 628512345678). " +
                    "Avl Bal INR 5,000.00. -YES BANK",
            ).fingerprint,
        )
        assertEquals(
            "7466e15df0a2b79abb0cc6fc",
            t(
                "AD-YESBNK",
                "INR 25,000.00 credited to A/c XX1234 on 12-10-2026 by NEFT from ACME TECHNOLOGIES PVT LTD. Avl Bal INR 30,000.00 -YES BANK",
            ).fingerprint,
        )
        assertEquals(
            "3c33efe090759be990efe2d5",
            t(
                "VM-FEDFIB",
                "Rs 450.00 debited from your A/c XX1234 on 12-10-2026 14:20:11 to VPA zomato@hdfcbank (UPI Ref No 628512345678). " +
                    "Not you? Call 18004251199 -Federal Bank",
            ).fingerprint,
        )
        val r = SmsParser.parse(
            sms(
                "VM-IDFCFB",
                "IDFC FIRST Bank Credit Card XX4321 Statement: Total Amount Due INR 12,345.00, Minimum Amount Due INR 620.00, " +
                    "Due Date 02-11-2026.",
                OCT_12,
            ),
        )
        assertTrue(r.toString(), r is SmsParseResult.CardBill)
        r as SmsParseResult.CardBill
        assertEquals(Bank.IDFC, r.bill.bank)
        assertEquals("c2944f3b4ca7991287dea4bb", r.bill.fingerprint)
    }
}
