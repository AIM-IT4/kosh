package com.amitjha.kosh.sms

/**
 * A number in the SMS inbox, not linked to anyone, that sent a link code, forwarded bank alerts, or simply texted
 * recently (a QR scan that filled in the number but not the code).
 */
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
    /** The start of its latest message, so the user can tell who it is. */
    val snippet: String = "",
)

/** What a search of the SMS inbox for family members found, and whether Kosh can read SMS at all. */
data class FamilyScan(
    val candidates: List<FamilyCandidate>,
    /** SMS Kosh could read in the period searched. */
    val smsRead: Int,
    /** When the newest of them arrived; shows SMS reading works even when nothing from family turned up. */
    val newestAt: Long?,
)
