package com.amitjha.kosh.sms

/** A number in the SMS inbox, not linked to anyone, that sent a link code or forwarded bank alerts. */
data class FamilyCandidate(
    /** The last 10 digits, as members are matched. */
    val phone: String,
    /** The number as the inbox shows it. */
    val number: String,
    /** Messages from it that read like bank alerts. */
    val forwards: Int,
    val lastAt: Long,
    /** The link code it sent, if any. */
    val linkCode: String?,
)
