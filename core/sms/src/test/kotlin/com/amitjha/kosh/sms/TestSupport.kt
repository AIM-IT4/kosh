package com.amitjha.kosh.sms

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

internal val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

/** SMS received at 2025-01-05 18:30 IST unless stated otherwise. */
internal val SMS_TIME: Long = LocalDateTime.of(2025, 1, 5, 18, 30).atZone(ZONE).toInstant().toEpochMilli()

internal fun sms(sender: String, body: String, time: Long = SMS_TIME) = SmsMessage(sender, body, time)

internal fun txn(sender: String, body: String, time: Long = SMS_TIME): ParsedTransaction =
    when (val r = SmsParser.parse(sms(sender, body, time))) {
        is SmsParseResult.Transaction -> r.txn
        else -> {
            fail("expected a transaction but got $r for: $body")
            error("unreachable")
        }
    }

internal fun bill(sender: String, body: String): ParsedCardBill =
    when (val r = SmsParser.parse(sms(sender, body))) {
        is SmsParseResult.CardBill -> r.bill
        else -> {
            fail("expected a card bill but got $r for: $body")
            error("unreachable")
        }
    }

internal fun ignored(sender: String, body: String): String {
    val r = SmsParser.parse(sms(sender, body))
    assertTrue("expected Ignored but got $r for: $body", r is SmsParseResult.Ignored)
    return (r as SmsParseResult.Ignored).reason
}

internal fun rupees(r: Long) = r * 100

internal fun localDate(millis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate()

internal fun epochDay(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
