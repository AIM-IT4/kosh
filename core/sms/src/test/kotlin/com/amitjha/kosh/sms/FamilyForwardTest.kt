package com.amitjha.kosh.sms

import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyForwardTest {

    @Test
    fun theQrCodeOpensALinkMessage() {
        assertEquals("SMSTO:+919876543210:KOSH LINK 482913", FamilyForward.qrPayload("+91 98765 43210", "482913"))
        assertEquals("482913", FamilyForward.linkCode("KOSH LINK 482913"))
        assertEquals("482913", FamilyForward.linkCode("kosh-link: 482913 "))
        assertNull(FamilyForward.linkCode("Your OTP is 482913"))
        assertEquals(6, FamilyForward.newCode().length)
    }

    @Test
    fun phonesMatchOnTheirLastTenDigits() {
        assertEquals("9876543210", FamilyForward.phoneKey("+91 98765 43210"))
        assertEquals("9876543210", FamilyForward.phoneKey("09876543210"))
        assertNull(FamilyForward.phoneKey("AD-HDFCBK"))
        assertNull(FamilyForward.phoneKey("57575"))
    }

    @Test
    fun aForwardedAlertIsReadAsTheBanksMessage() {
        val body = "Rs.450.00 debited from A/c XX1234 on 18-10-26 to VPA swiggy@ybl. UPI Ref No 529112345678. -SBI"
        val fwd = sms("+919876543210", body)
        assertTrue(FamilyForward.mayBeFamily(fwd.sender, fwd.body))
        val inner = FamilyForward.inner(fwd)
        assertEquals(FamilyForward.SENDER, inner.sender)
        val t = (SmsParser.parse(inner) as SmsParseResult.Transaction).txn
        assertEquals(Bank.SBI, t.bank)
        assertEquals("1234", t.accountLast4)
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(rupees(450), t.amount)
        // Forwarded twice, or also received straight from the bank, it is still one transaction.
        assertEquals(txn("VM-SBIUPI", body).fingerprint, t.fingerprint)
    }

    @Test
    fun aForwardThatKeptTheBankHeaderUsesIt() {
        val u = FamilyForward.unwrap("Fwd: AD-HDFCBK: Rs 1,200 spent on HDFC Bank Card x9876 at AMAZON on 2026-10-18")
        assertEquals("AD-HDFCBK", u.sender)
        assertTrue(u.body.startsWith("Rs 1,200 spent"))
        val t = txn(u.sender, u.body)
        assertEquals(Bank.HDFC, t.bank)
        assertEquals("9876", t.accountLast4)
    }

    @Test
    fun chatsAndOtpsAreNotForwards() {
        assertFalse(FamilyForward.mayBeFamily("+919876543210", "Reached home, call you later"))
        assertFalse(FamilyForward.mayBeFamily("VM-HDFCBK", "KOSH LINK 482913"))
        assertTrue(FamilyForward.mayBeFamily("+919876543210", "KOSH LINK 482913"))
    }
}
