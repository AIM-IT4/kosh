package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank

/** Sender domains and keywords used to find and attribute credit-card e-mails. */
object IssuerDirectory {
    /** Domains that card statements and alerts are sent from, per issuer. */
    val senderDomains: Map<Bank, List<String>> = mapOf(
        Bank.ICICI to listOf("icicibank.com", "icici.bank.in"),
        Bank.HDFC to listOf("hdfcbank.net", "hdfcbank.com", "hdfcbank.bank.in"),
        Bank.KOTAK to listOf("kotak.com", "kotakbank.com", "kotak.bank.in"),
        Bank.DBS to listOf("dbs.com", "dbs.in"),
        Bank.SBI to listOf("sbicard.com"),
        Bank.AXIS to listOf("axisbank.com", "axis.bank.in"),
    )

    /** Known statement sender addresses (used in the search query; domains cover the rest). */
    val statementSenders: Map<Bank, List<String>> = mapOf(
        Bank.ICICI to listOf("credit_cards@icicibank.com", "cards@icicibank.com"),
        Bank.HDFC to listOf("emailstatements.cards@hdfcbank.net", "emailstatements.cc@hdfcbank.net"),
        Bank.KOTAK to listOf("cardstatement@kotak.com", "creditcardstatement@kotak.com"),
        Bank.DBS to listOf("cardstatements@dbs.com"),
        Bank.SBI to listOf("statements@sbicard.com"),
        Bank.AXIS to listOf("cc.statements@axisbank.com"),
    )

    /** Names that identify an issuer in a message body. */
    val bodyNames: Map<Bank, List<String>> = mapOf(
        Bank.ICICI to listOf("icici bank", "icici"),
        Bank.HDFC to listOf("hdfc bank", "hdfc"),
        Bank.KOTAK to listOf("kotak mahindra", "kotak"),
        Bank.DBS to listOf("dbs bank", "dbs"),
        Bank.SBI to listOf("sbi card", "sbicard"),
        Bank.AXIS to listOf("axis bank"),
    )

    /** The issuer that sent [from] (e.g. "HDFC Bank <emailstatements.cards@hdfcbank.net>"). */
    fun bankForSender(from: String): Bank? {
        val email = Regex("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+)").find(from)?.groupValues?.get(1)?.lowercase()
            ?: return null
        return senderDomains.entries.firstOrNull { (_, domains) ->
            domains.any { email == it || email.endsWith(".$it") }
        }?.key
    }

    /** The issuer named most prominently in [text], if any. */
    fun bankInText(text: String): Bank? {
        val lower = text.lowercase()
        return bodyNames.entries
            .mapNotNull { (bank, names) ->
                names.mapNotNull { n -> Regex("\\b${Regex.escape(n)}\\b").find(lower)?.range?.first }
                    .minOrNull()?.let { bank to it }
            }
            .minByOrNull { it.second }?.first
    }
}

/** Gmail search queries for credit-card statements. */
object BillQueries {
    private val statementWords =
        "(statement OR \"e-statement\" OR estatement OR \"amount due\" OR \"payment due\")"

    /**
     * A Gmail query for statement e-mails from [banks] received in the last [sinceDays] days, plus a
     * keyword fallback that catches statements from senders not in the directory.
     */
    fun forBanks(banks: Set<Bank>, sinceDays: Int): String {
        require(sinceDays > 0) { "sinceDays must be positive" }
        val selected = banks.filter { it != Bank.OTHER }.ifEmpty { IssuerDirectory.senderDomains.keys.toList() }
        val senders = selected.flatMap {
            IssuerDirectory.statementSenders[it].orEmpty() + IssuerDirectory.senderDomains[it].orEmpty()
        }.distinct()
        val fromClause = "from:(${senders.joinToString(" OR ")})"
        val fallback = "(\"credit card statement\" OR \"total amount due\" OR \"minimum amount due\")"
        return "newer_than:${sinceDays}d (($fromClause $statementWords) OR $fallback) -in:spam -in:trash"
    }

    /** Known transaction-alert sender addresses per issuer. */
    val alertSenders: Map<Bank, List<String>> = mapOf(
        Bank.ICICI to listOf("credit_cards@icicibank.com", "customercare@icicibank.com", "alerts@icicibank.com"),
        Bank.HDFC to listOf("alerts@hdfcbank.net", "alerts@hdfcbank.bank.in"),
        Bank.KOTAK to listOf("creditcardalerts@kotak.com", "BankAlerts@kotak.com", "nach.alerts@kotak.com"),
        Bank.DBS to listOf("alerts@dbs.com", "digibank@dbs.com"),
        Bank.SBI to listOf("onlinesbicard@sbicard.com"),
        Bank.AXIS to listOf("alerts@axisbank.com"),
    )

    /**
     * A Gmail query for transaction alerts (card spends, debits, UPI) from [banks]: known alert senders and
     * issuer domains with transaction words, plus a keyword fallback for any sender.
     */
    fun alertsForBanks(banks: Set<Bank>, sinceDays: Int): String {
        require(sinceDays > 0) { "sinceDays must be positive" }
        val selected = banks.filter { it != Bank.OTHER }.ifEmpty { IssuerDirectory.senderDomains.keys.toList() }
        val senders = selected.flatMap { alertSenders[it].orEmpty() + IssuerDirectory.senderDomains[it].orEmpty() }.distinct()
        val words = "(\"transaction alert\" OR alert OR debited OR spent OR \"credit card transaction\" OR " +
            "\"used for a transaction\" OR \"UPI txn\" OR \"has been used\" OR \"thank you for using\")"
        val fallback = "(subject:\"transaction alert\" OR \"has been debited\" OR \"used for a transaction\" OR " +
            "\"thank you for using your\")"
        return "newer_than:${sinceDays}d ((from:(${senders.joinToString(" OR ")}) $words) OR $fallback) -in:spam -in:trash"
    }

    /** A Gmail query for credit-card payment acknowledgements. */
    fun paymentsForBanks(banks: Set<Bank>, sinceDays: Int): String {
        val selected = banks.filter { it != Bank.OTHER }.ifEmpty { IssuerDirectory.senderDomains.keys.toList() }
        val domains = selected.flatMap { IssuerDirectory.senderDomains[it].orEmpty() }.distinct()
        return "newer_than:${sinceDays}d from:(${domains.joinToString(" OR ")}) " +
            "(\"payment received\" OR \"received your payment\" OR \"thank you for your payment\" OR \"payment of\") " +
            "\"credit card\" -in:spam -in:trash"
    }
}
