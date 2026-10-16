package com.amitjha.kosh.sms

/** Messages that look financial but must not become transactions. */
internal object Filters {

    private val txnVerb = Regex(
        "\\b(debited|credited|spent|withdrawn|deposited|sent rs|received rs|has been used for|transferred)\\b",
    )

    private class Check(val reason: String, val matches: (String) -> Boolean)

    // Clear OTP deliveries only. "Avl Bal 12345.67. Never share your OTP" is a real debit, see [weakOtp].
    private val otp = Regex(
        "\\botp\\b.{0,20}(?:is|:)\\s*\\d{4,8}|\\botp\\b.{0,60}\\bis\\s*\\d{4,8}\\b|" +
            "\\b\\d{4,8}\\b\\s*(?:is|as)\\s*(?:your|the)?\\s*(?:otp|one[- ]time password)|" +
            "one[- ]time password|verification code|\\bcode is\\s*\\d",
    )

    private val failed = Regex(
        "declined|\\bfailed\\b|unsuccessful|not successful|could not be (?:processed|completed)|" +
            "\\brejected\\b|insufficient (?:funds|balance|limit)",
    )

    private val collect = Regex("requested money|has requested|collect request|is requesting|request(?:ed)? (?:for )?rs")

    /** Advance notices: UPI AutoPay pre-debit notifications, scheduled SI/NACH/ECS debits. */
    private val upcoming = Regex(
        "will be (?:auto[- ]?)?(?:debited|executed|presented)|is scheduled|scheduled (?:for|on)|due for (?:debit|execution)|" +
            "will be (?:deducted|charged)|upcoming (?:debit|payment|autopay)|pre-?debit (?:notification|intimation|alert)",
    )

    private val always: List<Check> = listOf(
        Check("otp") { otp.containsMatchIn(it) },
        Check("failed or declined") { failed.containsMatchIn(it) },
        Check("upi collect request") { collect.containsMatchIn(it) },
        Check("mandate registration") { isMandateSetup(it) },
        Check("upcoming debit") { upcoming.containsMatchIn(it) },
    )

    /** Boilerplate such as "SMS BLOCK from your registered mobile" says nothing about a mandate. */
    private val registeredContact = Regex("registered (?:mobile|email|e-mail|number|no\\b|phone|device)")

    /** "will be debited", "to be executed": future money movement, not a completed one. */
    private val future = Regex("(?:will|to|shall) be (?:auto[- ]?)?\\w+")

    /** Setting up, registering or approving a mandate / AutoPay / standing instruction. */
    private val mandateSetup = Regex(
        "mandate.{0,120}(?:set up|setup|created|registered|registration|activated|approved|authori[sz]ed)|" +
            "(?:set up|setup|created|registered|activated|approved|authori[sz]ed).{0,40}(?:mandate|auto ?pay)|" +
            "(?:auto ?pay|standing instruction)\\s+(?:is\\s+|has been\\s+)?(?:successfully\\s+)?" +
            "(?:set up|setup|activated|enabled|created|registered)",
    )

    /** "Mandate ... successfully" alone: a setup, revoke or pause notice unless money moved. */
    private val mandateSuccess = Regex("mandate.{0,80}successfully|auto ?pay.{0,80}successfully")

    /** Money that has moved: an executed mandate or a completed debit. */
    private val moneyMoved = Regex("\\b(?:executed|debited|deducted|paid|charged)\\b")
    private val processed = Regex("\\bprocessed\\b")

    /**
     * A mandate / AutoPay being created or registered, which is not a payment. A message that also reports
     * a completed debit ("UPI AutoPay: Rs.199 debited ... Mandate executed successfully") is a payment.
     */
    private fun isMandateSetup(lower: String): Boolean {
        val s = lower.replace(registeredContact, " ")
        val now = s.replace(future, " ")
        val moved = moneyMoved.containsMatchIn(now)
        if (mandateSetup.containsMatchIn(s)) return !moved
        return mandateSuccess.containsMatchIn(s) && !moved && !processed.containsMatchIn(now)
    }

    private val autoDebitContext = Regex("auto ?pay|auto[- ]?debit|mandate|\\be?-?nach\\b|\\becs\\b|standing instr|\\bsi\\b")
    private val executed = Regex("\\b(?:executed|processed)\\b")
    private val mandateLifecycle = Regex(
        "set ?up|creat|regist|activat|approv|authori[sz]|enabl|cancel|revok|paus|resum|modif|request|declin|fail",
    )

    /**
     * An executed AutoPay / mandate / standing instruction worded without "debited":
     * "SI executed: INR 5,000.00 transferred from A/c XX1234", "UPI Autopay Txn of Rs 299 executed successfully".
     */
    fun isExecutedAutoDebit(lower: String): Boolean {
        val s = lower.replace(registeredContact, " ").replace(future, " ")
        return autoDebitContext.containsMatchIn(s) && executed.containsMatchIn(s) && !mandateLifecycle.containsMatchIn(s)
    }

    private val promo = Regex(
        "pre-?approved|apply now|\\boffers?\\b|click (?:here )?to (?:apply|avail|know)|eligible for (?:an? )?(?:instant )?(?:loan|credit|limit)|" +
            "instant loan|personal loan|upgrade your|limited period|t&c|avail now|exclusive|congratulations|" +
            "\\bwin\\b|lucky|voucher|get up to|enjoy|convert (?:it|this|your).{0,30}emi",
    )

    private val balanceSpam = Regex("missed call|to know your (?:a/c )?balance|check (?:your )?balance|balance enquiry")

    private val reminder = Regex("\\breminder\\b|\\bis due\\b|\\bdue (?:on|by|date)\\b|please pay|pay now|ignore if (?:already )?paid")

    private val refundOfFailed = Regex("(?:refund|reversed|reversal).{0,80}credited|credited.{0,80}(?:refund|reversed|reversal)")

    /** "1234 ... OTP" without delivery wording: only an OTP message when there is no transaction verb. */
    private val weakOtp = Regex("\\b\\d{4,8}\\b.{0,25}\\botp\\b")

    /** Hard rejections, applied before bill detection. */
    fun hardReject(lower: String): String? {
        val hit = always.firstOrNull { it.matches(lower) }?.reason
            ?: (if (weakOtp.containsMatchIn(lower) && !txnVerb.containsMatchIn(lower)) "otp" else null)
            ?: return null
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
