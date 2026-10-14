package com.amitjha.kosh.sms

import com.amitjha.kosh.model.TxnType

/**
 * Suggests one of the app's default category keys:
 * food, groceries, shopping, transport, fuel, bills, rent, entertainment, health, education, travel,
 * subscriptions, emi, investment, transfer, salary, refund, interest, cashback, other_income, other.
 */
object CategoryGuesser {

    val CATEGORY_KEYS = listOf(
        "food", "groceries", "shopping", "transport", "fuel", "bills", "rent", "entertainment", "health",
        "education", "travel", "subscriptions", "emi", "investment", "transfer", "salary", "refund",
        "interest", "cashback", "other_income", "other",
    )

    /** Checked in order; the first match wins (subscriptions before shopping so "Amazon Prime" isn't shopping). */
    private val expenseRules: List<Pair<String, Regex>> = listOf(
        "emi" to "\\bemi\\b|\\bloan\\b|\\bnach\\b|bajaj fin|home ?loan|car ?loan|\\becs\\b",
        "investment" to "\\bsip\\b|mutual ?fund|zerodha|groww|kuvera|\\bnps\\b|\\bppf\\b|coin by|smallcase|upstox|\\bmf\\b|\\bindmoney|etmoney",
        "rent" to "\\brent\\b|nobroker|housing\\.com|magicbricks|nestaway",
        "subscriptions" to "netflix|spotify|amazon ?prime|prime ?video|hotstar|jiocinema|youtube|apple|google ?play|icloud|\\bsony ?liv|zee5|subscription|chatgpt|openai|linkedin",
        "food" to "swiggy|zomato|restaurant|cafe|café|domino|mcdonald|\\bkfc\\b|starbucks|eatsure|pizza|burger|biryani|bakery|chai|food",
        "groceries" to "bigbasket|blinkit|zepto|dmart|grofers|instamart|jiomart|grocery|supermarket|kirana|more retail|spencer|nature'?s basket|milkbasket|country delight",
        "fuel" to "petrol|\\bfuel|\\bbpcl\\b|\\bhpcl\\b|bharat petroleum|hp petrol|indian oil|\\biocl?\\b|shell|\\bnayara",
        "travel" to "irctc|makemytrip|goibibo|indigo|air ?india|vistara|akasa|spicejet|\\boyo\\b|airbnb|booking\\.com|cleartrip|yatra|redbus|ixigo|hotel",
        "transport" to "\\buber\\b|\\bola\\b|rapido|metro|fastag|parking|\\btoll\\b|namma yatri|bluesmart|\\bcab\\b",
        "bills" to "airtel|\\bjio\\b|vodafone|\\bvi\\b|electricity|bescom|tata power|adani elec|broadband|\\bgas\\b|water|\\bdth\\b|recharge|insurance|\\blic\\b|act fibernet|bill ?pay|bbps|postpaid|prepaid",
        "entertainment" to "bookmyshow|\\bpvr\\b|inox|cinepolis|movie|gaming|steam|playstation|dream11",
        "health" to "pharmacy|apollo|medplus|\\b1mg\\b|pharmeasy|hospital|clinic|practo|diagnostic|\\blab\\b|chemist|medical|healthkart|cult\\.?fit|gym",
        "education" to "udemy|coursera|byju|unacademy|school|college|university|tuition|\\bfees?\\b|upgrad|simplilearn",
        "shopping" to "amazon|flipkart|myntra|nykaa|ajio|meesho|tata ?cliq|croma|reliance digital|decathlon|ikea|lifestyle|westside|zara|h&m|shoppers stop|lenskart|mall",
    ).map { it.first to Regex("(?i)" + it.second) }

    fun guess(merchant: String?, body: String, type: TxnType): String {
        val text = listOfNotNull(merchant, body).joinToString(" ")
        val lower = text.lowercase()
        return when (type) {
            TxnType.TRANSFER -> "transfer"
            TxnType.INCOME -> when {
                Regex("salary|\\bsal\\b|payroll").containsMatchIn(lower) -> "salary"
                Regex("refund|reversal|reversed").containsMatchIn(lower) -> "refund"
                Regex("cashback|reward|cash back").containsMatchIn(lower) -> "cashback"
                Regex("\\binterest\\b|\\bint\\.? (?:pd|paid|cr)").containsMatchIn(lower) -> "interest"
                Regex("payment.*(?:received|credited).*credit card|credit card.*payment.*received").containsMatchIn(lower) -> "transfer"
                else -> "other_income"
            }
            TxnType.EXPENSE -> {
                expenseRules.firstOrNull { it.second.containsMatchIn(merchant ?: "") }?.let { return it.first }
                expenseRules.firstOrNull { it.second.containsMatchIn(text) }?.let { return it.first }
                when {
                    Regex("\\batm\\b|withdrawn|cash withdrawal").containsMatchIn(lower) -> "other"
                    merchant != null && merchant.startsWith("UPI X") -> "transfer"
                    else -> "other"
                }
            }
        }
    }
}
