package com.amitjha.kosh.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Today's common bank alerts, forwarded by a linked family member as plain SMS (Android "Forward" or an iPhone
 * automation sends the text alone, without the bank's sender). Every one must come through as a transaction.
 */
class FamilyForwardFormatsTest {

    private val member = "+919876543210"

    private val debits250 = listOf(
        "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY LIMITED\nOn 17/10/26\nRef 529012345678\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
        "Dear UPI user A/C X1234 debited by 250.0 on date 17Oct26 trf to SWIGGY Refno 529012345678 If not u? call-1800111109 for other services-18001234-SBI",
        "Your A/C XXXXX1234 has a debit by transfer of Rs 250.00 on 17/10/26. Avl Bal Rs 5,000.00.-SBI",
        "ICICI Bank Acct XX123 debited for Rs 250.00 on 17-Oct-26; SWIGGY credited. UPI:529012345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.",
        "INR 250.00 debited\nA/c no. XX1234\n17-10-26, 10:15:22\nUPI/P2M/529012345678/SWIGGY\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank",
        "Sent Rs.250.00 from Kotak Bank AC X1234 to swiggy@icici on 17-10-26.UPI Ref 529012345678. Not you, https://kotak.com/KBANKT/Fraud",
        "Rs.250.00 Dr. from A/C XXXXXX1234 and Cr. to swiggy@icici. Ref:529012345678. AvlBal:Rs5000.00(2026:10:17 10:15:22). Not you? Call 18005700-BOB",
        "Your A/C XXXXX1234 is debited by INR 250.00 on 17/10/26 10:15:22 via UPI to SWIGGY. Ref 529012345678. Not you? Call 18001080 - IDFC FIRST Bank",
        "Ac XXXXXXXX1234 debited INR 250.00 dt 17-10-26 10:15:22 thru UPI:529012345678.Bal INR 5,000.00 Not u?Fwd this SMS to 9264092640 to block UPI.-PNB",
        "An amount of INR 250.00 has been DEBITED to your account XXX1234 on 17/10/2026 towards UPI/529012345678. Total Avail.bal INR 5,000.00. - Canara Bank",
        "A/c *1234 Debited for Rs:250.00 on 17-10-2026 10:15:22 by Mob Bk ref no 529012345678 Avl Bal Rs:5000.00.If not you, Call 1800222243 -Union Bank of India",
        "UPDATE: INR 250.00 debited from HDFC Bank XX1234 on 17-OCT-26. Info: UPI-SWIGGY-swiggy@icici-529012345678. Avl bal:INR 5,000.00",
        "Rs.250.00 debited from AU Bank A/c XX1234 via UPI on 17-10-2026 to SWIGGY. UPI Ref:529012345678. Avl Bal Rs.5000.00",
        "Rs 250.00 debited from your A/c XX1234 to VPA swiggy@icici on 17-10-2026 10:15:22. Ref No 529012345678. Not you? Call 18004251199 -Federal Bank",
        "INR 250.00 debited from YES BANK A/c XX1234 on 17-10-2026 towards UPI P2M swiggy@icici. RRN 529012345678. Avl Bal INR 5000.00",
        "Your IndusInd A/c 1234XXXX1234 is debited for INR 250.00 towards UPI/529012345678/SWIGGY on 17/10/2026. Avl Bal INR 5000.00",
        "Rs 250.00 debited from a/c **1234 on 17-10-26 to VPA swiggy@icici(UPI Ref No 529012345678). Not you? Call on 18002586161 to report",
    )

    private val cardSpends1500 = listOf(
        "Spent Rs.1500 On HDFC Bank Card 1234 At AMAZON On 2026-10-17:10:15:22.Not You? To Block+Reissue Call 18002323232/SMS BLOCK CC 1234 to 7308080808",
        "INR 1,500.00 spent using ICICI Bank Card XX1234 on 17-Oct-26 on AMAZON. Avl Limit: INR 98,500.00. If not you, call 1800 2662/SMS BLOCK 1234 to 9215676766",
        "Rs.1,500.00 spent on your SBI Credit Card ending 1234 at AMAZON on 17/10/26. Trxn. Not done by you? Report at https://sbicard.com/Dispute",
    )

    private fun throughFamilyPath(body: String): ParsedTransaction? {
        val msg = SmsMessage(member, body, SMS_TIME)
        if (!FamilyForward.mayBeFamily(msg.sender, msg.body)) return null
        val inner = FamilyForward.inner(msg)
        if (!isFinancialMessage(inner.sender, inner.body)) return null
        return (SmsParser.parse(inner) as? SmsParseResult.Transaction)?.txn
    }

    @Test
    fun everyCommonDebitForwardedByAMemberIsImported() {
        val missed = debits250.filter { throughFamilyPath(it)?.amount != rupees(250) }
        assertTrue("missed: ${missed.map { it.take(48) }}", missed.isEmpty())
    }

    @Test
    fun cardSpendsForwardedByAMemberAreImported() {
        val missed = cardSpends1500.filter { throughFamilyPath(it)?.amount != rupees(1500) }
        assertTrue("missed: ${missed.map { it.take(48) }}", missed.isEmpty())
    }

    @Test
    fun aKeptBankHeaderStillNamesTheBank() {
        val t = throughFamilyPath("Fwd: VM-SBIUPI: Dear UPI user A/C X1234 debited by 250.0 on date 17Oct26 trf to SWIGGY Refno 529012345678")
        assertEquals(com.amitjha.kosh.model.Bank.SBI, t?.bank)
    }

    @Test
    fun chatsFromAMemberAreNotTransactions() {
        listOf("Reached home, call you at 8", "Can you send 500 for the gas cylinder?", "KOSH LINK 123456").forEach {
            assertEquals(it, null, throughFamilyPath(it))
        }
    }
}
