package com.amitjha.kosh.sms

/** How one recent bank SMS fared, for More › SMS check-up. */
data class CheckupItem(
    /** When the message was sent (as Kosh dates it). */
    val at: Long,
    val verdict: Verdict,
    /** "₹250 spent · Swiggy", "Card bill · ₹12,000", or what was skipped. */
    val title: String,
    /** Why it isn't counted or was skipped; null when it is counted. */
    val reason: String? = null,
    val accountName: String? = null,
    /** The date of the stored transaction, which can differ from the message's. */
    val datedAt: Long? = null,
    val transactionId: Long? = null,
    /** The message with account, card, reference and phone numbers masked, for sharing. */
    val masked: String,
) {
    enum class Verdict { COUNTED, NOT_COUNTED, MISSING, OTHER, SKIPPED }
}
