package com.amitjha.kosh.sms

/** Which clock a message is dated with. */
object SmsTime {
    /** How long a network may hold a message before the phone gets it (the phone was off or out of coverage). */
    const val MAX_DELAY_MILLIS = 7 * 86_400_000L

    /** How far the network's clock may run ahead of the phone's. */
    const val MAX_AHEAD_MILLIS = 60 * 60_000L

    /**
     * Whether the network's send time is believable next to when the phone got the message. Some phones and
     * networks report 0, seconds instead of milliseconds, or a clock that is days off.
     */
    fun plausible(sentMillis: Long, receivedMillis: Long): Boolean =
        sentMillis > 0 && sentMillis >= receivedMillis - MAX_DELAY_MILLIS && sentMillis <= receivedMillis + MAX_AHEAD_MILLIS

    /**
     * A message dated with its send time when that is plausible, else with when the phone got it. Inbox scans pass
     * the stored time as [receivedMillis] (kept as [SmsMessage.receivedMillis]); the live receiver passes the clock.
     */
    fun message(sender: String, body: String, sentMillis: Long, receivedMillis: Long, keepReceived: Boolean): SmsMessage {
        val ok = plausible(sentMillis, receivedMillis)
        return SmsMessage(
            sender = sender,
            body = body,
            timestampMillis = if (ok) sentMillis else receivedMillis,
            receivedMillis = if (keepReceived) receivedMillis else null,
            implausibleSentMillis = if (!ok && sentMillis > 0) sentMillis else null,
        )
    }
}
