package com.amitjha.kosh.sms

import java.security.SecureRandom

/**
 * Bank SMS a family member forwards from their own phone, so their spending shows in Kosh without them installing it.
 *
 * Linking: Kosh shows a QR code holding an `SMSTO:` link. Scanning it with the phone's camera opens the member's
 * Messages app with "KOSH LINK 123456" addressed to the user; when that message arrives, Kosh knows the member's
 * number. From then on, a transaction alert forwarded from that number is read as the member's bank SMS: forwarding
 * keeps the original text, and some forwards (an iPhone automation, say) put the bank's sender in front of it.
 */
object FamilyForward {

    /** Sender given to a forwarded message whose bank sender isn't known; the parser then reads the bank from the text. */
    const val SENDER = "FORWARDED"

    private val link = Regex("(?i)\\bkosh[ -]?link\\s*:?\\s*(\\d{6})\\b")
    private val forwardPrefix = Regex("(?i)^\\s*(?:fwd?|fw|forwarded(?: message)?)\\s*:\\s*")

    /** "AD-HDFCBK: Rs 500 debited..." or "From VM-SBIUPI - ...": the bank's DLT header, kept by the forward. */
    private val senderPrefix = Regex("(?i)^\\s*(?:from\\s*:?\\s*)?([a-z]{2}-[a-z0-9]{3,9}(?:-[a-z])?)\\s*[:\\-–]\\s+")

    /** The 6-digit code of a link message, or null. */
    fun linkCode(body: String): String? = link.find(body)?.groupValues?.get(1)

    /** The text a link message carries. */
    fun linkText(code: String): String = "KOSH LINK $code"

    /**
     * The QR code's content: an `SMSTO:` link both iPhone and Android cameras open as a new message to [toNumber]
     * with the link text filled in.
     */
    fun qrPayload(toNumber: String, code: String): String = "SMSTO:${toNumber.filter { it.isDigit() || it == '+' }}:${linkText(code)}"

    /** A new random link code. */
    fun newCode(random: SecureRandom = SecureRandom()): String = (100_000 + random.nextInt(900_000)).toString()

    /**
     * The last 10 digits of a phone number, which is how members are matched ("+91 98765 43210", "09876543210" and
     * "9876543210" are the same phone). Null for bank headers, short codes and anything else that isn't a number.
     */
    fun phoneKey(sender: String): String? {
        val s = sender.trim()
        if (s.isEmpty() || !s.all { it.isDigit() || it in "+ -()" }) return null
        val digits = s.filter(Char::isDigit)
        return if (digits.length >= 10) digits.takeLast(10) else null
    }

    /** Whether a message from [sender] could be a link message or a family member's forward, for the live receiver's first filter. */
    fun mayBeFamily(sender: String, body: String): Boolean =
        phoneKey(sender) != null && (linkCode(body) != null || isBankAlert(body))

    private val moneyMoved = Regex(
        "(?i)\\b(debited|credited|debit|credit|spent|withdrawn|sent|received|paid|transferred|deducted|deposited|refund(?:ed)?|dr|cr)\\b",
    )
    private val digit = Regex("\\d")

    /**
     * Whether the text of a linked member's forward is a bank alert. Their number is already trusted and the forward
     * has lost the bank's sender, so this is looser than the check for an unknown number: SBI's UPI alert ("A/C X1234
     * debited by 250.0") has no currency sign and Bank of Baroda's ("Rs.250.00 Dr. from A/C") no "debited". The
     * parser still decides what the message is; a chat without an account, a money word and a number never passes.
     */
    fun isBankAlert(body: String): Boolean {
        val text = unwrap(body).body
        if (looksTransactional(text)) return true
        return accountMention.containsMatchIn(text) && moneyMoved.containsMatchIn(text) && digit.containsMatchIn(text)
    }

    /** What arrived from one linked number: its latest SMS, its latest bank alert and how many alerts. */
    data class Status(val lastAt: Long, val lastAlertAt: Long?, val alerts: Int)

    /**
     * For each of [phones] (phone keys) that sent anything among [messages]: when, and whether bank alerts came. It
     * tells "linked, but nothing reaches this phone" (forwards sent as chat, or not sent at all) from "alerts arrive".
     */
    fun status(messages: List<SmsMessage>, phones: Set<String>): Map<String, Status> =
        messages.mapNotNull { m -> phoneKey(m.sender)?.takeIf { it in phones }?.let { it to m } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, msgs) ->
                val alerts = msgs.filter { linkCode(it.body) == null && isBankAlert(it.body) }
                Status(msgs.maxOf { it.timestampMillis }, alerts.maxOfOrNull { it.timestampMillis }, alerts.size)
            }

    /** A forwarded message's original sender ([SENDER] when it wasn't kept) and text. */
    data class Unwrapped(val sender: String, val body: String)

    /** Takes off "Fwd:"-style prefixes and reads a leading bank header as the original sender. */
    fun unwrap(body: String): Unwrapped {
        var text = body.trim()
        repeat(3) { text = text.replace(forwardPrefix, "") }
        val m = senderPrefix.find(text)
        return if (m != null) {
            Unwrapped(m.groupValues[1].uppercase(), text.substring(m.range.last + 1).trim())
        } else {
            Unwrapped(SENDER, text)
        }
    }

    /** The bank SMS inside a forward, dated when it was forwarded (the parser prefers a date written in the text). */
    fun inner(msg: SmsMessage): SmsMessage {
        val u = unwrap(msg.body)
        return msg.copy(sender = u.sender, body = u.body)
    }
}
