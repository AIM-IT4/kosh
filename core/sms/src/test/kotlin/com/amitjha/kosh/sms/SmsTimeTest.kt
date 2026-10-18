package com.amitjha.kosh.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A wrong network clock must not move a spend to another day (or to 1970). */
class SmsTimeTest {

    private val stored = 1_792_218_600_000L // 17 Oct 2026, noon in India
    private val body = "Rs.250.00 debited from A/c XX4521 via UPI to SWIGGY"

    @Test
    fun aPlausibleSendTimeIsUsed() {
        val sent = stored - 40_000
        val m = SmsTime.message("VM-HDFCBK-S", body, sent, stored, keepReceived = true)
        assertEquals(sent, m.timestampMillis)
        assertEquals(stored, m.receivedMillis)
        assertNull(m.implausibleSentMillis)
        // Held by the network for a few days while the phone was off: still the send time.
        assertEquals(stored - 3 * 86_400_000L, SmsTime.message("VM-HDFCBK", body, stored - 3 * 86_400_000L, stored, true).timestampMillis)
    }

    @Test
    fun anImplausibleSendTimeFallsBackToWhenThePhoneGotIt() {
        for (sent in listOf(stored / 1000, stored - 30 * 86_400_000L, stored + 3 * 3_600_000L)) {
            val m = SmsTime.message("VM-HDFCBK", body, sent, stored, keepReceived = true)
            assertEquals(stored, m.timestampMillis)
            assertEquals(sent, m.implausibleSentMillis)
        }
        // No send time at all is not "implausible": there is nothing older to look for.
        val none = SmsTime.message("VM-HDFCBK", body, 0, stored, keepReceived = false)
        assertEquals(stored, none.timestampMillis)
        assertNull(none.receivedMillis)
        assertNull(none.implausibleSentMillis)
    }

    @Test
    fun aMisdatedMessageGetsTheBodysDateWhenItHasOne() {
        val withDate = "Sent Rs.250.00 From HDFC Bank A/C *4521 To SWIGGY On 16/10/26 Ref 629012345678"
        val m = SmsTime.message("VM-HDFCBK", withDate, stored / 1000, stored, keepReceived = true)
        val t = (SmsParser.parse(m) as SmsParseResult.Transaction).txn
        assertEquals(java.time.LocalDate.of(2026, 10, 16), java.time.Instant.ofEpochMilli(t.timestampMillis).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate())
    }
}
