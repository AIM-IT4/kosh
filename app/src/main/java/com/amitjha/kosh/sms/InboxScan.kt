package com.amitjha.kosh.sms

/** Commit a completed inbox read. An empty inbox is a successful initial scan too. */
internal suspend fun completeInboxScan(
    started: Long,
    messages: List<SmsMessage>,
    readError: String?,
    importMessages: suspend (List<SmsMessage>) -> ImportResult,
    recordScan: suspend (Long) -> Unit,
    recordProblem: suspend (String?, Long) -> Unit,
): ImportResult {
    val result = importMessages(messages).copy(scanned = messages.size, readError = readError)
    // Enable catch-up after an empty first scan, but never skip unread or unsaved messages on retry.
    if (result.problem == null) recordScan(started)
    recordProblem(result.problem, started)
    return result
}
