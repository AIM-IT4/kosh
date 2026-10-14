package com.amitjha.kosh.sms

/** Messages that look financial but must not become transactions. */
internal object Filters {

    private val txnVerb = Regex(
        "\\b(debited|credited|spent|withdrawn|deposited|sent rs|received rs|has been used for|transferred)\\b",
    )

    private val always: List<Pair<String, Regex>> = listOf(
        "otp" to Regex(
            "\\botp\\b.{0,20}(?:is|:)\\s*\\d{4,8}|\\b\\d{4,8}\\b.{0,25}\\botp\\b|one[- ]time password|" +
                "verification code|\\bcode is\\s*\\d",
        ),
        "failed or declined" to Regex(
            "declined|\\bfailed\\b|unsuccessful|not successful|could not be (?:processed|completed)|" +
                "\\brejected\\b|insufficient (?:funds|balance|limit)",
        ),
        "upi collect request" to Regex("requested money|has requested|collect request|is requesting|request(?:ed)? (?:for )?rs"),
        "mandate registration" to Regex(
            "mandate.{0,80}(?:set up|setup|created|registered|registration|activated|successfully)|" +
                "(?:set up|created|registered).{0,40}mandate|autopay (?:is )?(?:set up|activated|enabled)",
        ),
        "upcoming debit" to Regex(
            "will be (?:auto[- ]?)?debited|is scheduled|scheduled (?:for|on)|due for debit|will be (?:deducted|charged)|" +
                "upcoming (?:debit|payment|autopay)",
        ),
    )

    private val promo = Regex(
        "pre-?approved|apply now|\\boffers?\\b|click (?:here )?to (?:apply|avail|know)|eligible for (?:an? )?(?:instant )?(?:loan|credit|limit)|" +
            "instant loan|personal loan|upgrade your|limited period|t&c|avail now|exclusive|congratulations|" +
            "\\bwin\\b|lucky|voucher|get up to|enjoy|convert (?:it|this|your).{0,30}emi",
    )

    private val balanceSpam = Regex("missed call|to know your (?:a/c )?balance|check (?:your )?balance|balance enquiry")

    private val reminder = Regex("\\breminder\\b|\\bis due\\b|\\bdue (?:on|by|date)\\b|please pay|pay now|ignore if (?:already )?paid")

    private val refundOfFailed = Regex("(?:refund|reversed|reversal).{0,80}credited|credited.{0,80}(?:refund|reversed|reversal)")

    /** Hard rejections, applied before bill detection. */
    fun hardReject(lower: String): String? {
        val hit = always.firstOrNull { it.second.containsMatchIn(lower) }?.first ?: return null
        // "Refund of Rs.X for failed txn credited" is a real credit
        if (hit == "failed or declined" && refundOfFailed.containsMatchIn(lower)) return null
        return hit
    }

    /** Soft rejections, applied after bill detection: reminders, promos and balance spam without a real txn. */
    fun softReject(lower: String): String? {
        val hasTxnVerb = txnVerb.containsMatchIn(lower)
        if (!hasTxnVerb && reminder.containsMatchIn(lower)) return "payment reminder"
        if (!hasTxnVerb && promo.containsMatchIn(lower)) return "promotional"
        if (!hasTxnVerb && balanceSpam.containsMatchIn(lower)) return "balance enquiry"
        return null
    }
}
