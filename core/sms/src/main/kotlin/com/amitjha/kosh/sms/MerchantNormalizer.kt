package com.amitjha.kosh.sms

/** Turns raw merchant strings from SMS ("swiggy@icici", "UPI-ZOMATO-zomato@hdfcbank", "POS 4412 AMAZON PAY INDIA") into readable names. */
object MerchantNormalizer {

    /** Ordered: specific brands before generic wrappers (Paytm/PhonePe/GPay pass merchants through). */
    private val brands: List<Pair<Regex, String>> = listOf(
        "swiggy|instamart" to "Swiggy",
        "zomato" to "Zomato",
        "blinkit|grofers" to "Blinkit",
        "amazon ?prime|primevideo|prime video" to "Amazon Prime",
        "amazon|amzn" to "Amazon",
        "flipkart|fkrt" to "Flipkart",
        "myntra" to "Myntra",
        "nykaa" to "Nykaa",
        "ajio" to "Ajio",
        "meesho" to "Meesho",
        "uber" to "Uber",
        "olacabs|ola ?cabs|\\bola\\b|ani ?technologies" to "Ola",
        "rapido" to "Rapido",
        "bigbasket|bbnow|innovative retail" to "BigBasket",
        "zepto|kiranakart" to "Zepto",
        "dmart|avenue supermarts" to "DMart",
        "jiomart" to "JioMart",
        "netflix" to "Netflix",
        "spotify" to "Spotify",
        "hotstar|jiocinema|jio ?hotstar" to "JioHotstar",
        "youtube" to "YouTube",
        "bookmyshow|bigtree" to "BookMyShow",
        "airtel" to "Airtel",
        "\\bjio\\b|reliance jio|jio ?prepaid|jiopostpaid" to "Jio",
        "vodafone|\\bvil\\b|vi postpaid|vi prepaid" to "Vi",
        "irctc" to "IRCTC",
        "makemytrip|mmt" to "MakeMyTrip",
        "goibibo" to "Goibibo",
        "indigo|interglobe" to "IndiGo",
        "air ?india" to "Air India",
        "\\boyo\\b|oravel" to "OYO",
        "starbucks|tata starbucks" to "Starbucks",
        "domino|jubilant" to "Domino's",
        "mcdonald|hardcastle|westlife" to "McDonald's",
        "\\bkfc\\b|devyani" to "KFC",
        "apollo" to "Apollo",
        "pharmeasy" to "PharmEasy",
        "\\b1mg\\b|tata 1mg" to "Tata 1mg",
        "medplus" to "MedPlus",
        "zerodha" to "Zerodha",
        "groww|nextbillion" to "Groww",
        "\\bcred\\b|dreamplug" to "CRED",
        "bharat ?petroleum|\\bbpcl\\b" to "Bharat Petroleum",
        "hindustan ?petroleum|\\bhpcl\\b" to "HP Petrol",
        "indian ?oil|\\biocl?\\b" to "Indian Oil",
        "bescom" to "BESCOM",
        "tata ?power" to "Tata Power",
        "adani ?electricity" to "Adani Electricity",
        "\\blic\\b|life insurance corp" to "LIC",
        "apple\\.com|apple services|itunes" to "Apple",
        "phonepe" to "PhonePe",
        "paytm" to "Paytm",
        "gpay|google ?pay" to "Google Pay",
        "google ?play|google" to "Google",
    ).map { Regex("(?i)" + it.first) to it.second }

    private val wrapperOnly = setOf("PhonePe", "Paytm", "Google Pay")

    private val prefixes = Regex(
        "(?i)^(vpa|upi|pos|nach[- ]?dr|nach|ach d|ach|ecs|bil|imps|neft|rtgs|p2m|p2a|mmt|ref|info|ind\\*|payu\\*|razp\\*|rzp\\*)[\\s:/\\-*]+",
    )
    private val legal = Regex("(?i)\\b(pvt|private|ltd|limited|llp|inc|corp|corporation|co|india|ind)\\b\\.?")
    private val idToken = Regex("(?i)\\b(?=[a-z0-9]*\\d)[a-z0-9]{6,}\\b")

    fun normalize(raw: String): String {
        var s = raw.trim().trim('.', ',', ';', ':', '-', '/', '(', ')')
        if (s.isEmpty()) return s

        // keep the readable part of a UPI handle
        val vpa = Regex("([A-Za-z0-9.\\-_]+)@[A-Za-z]+").find(s)
        brands.firstOrNull { it.first.containsMatchIn(s) && it.second !in wrapperOnly }?.let { return it.second }
        if (vpa != null) {
            val handle = vpa.groupValues[1]
            brands.firstOrNull { it.first.containsMatchIn(handle) }?.let { return it.second }
            s = if (handle.all { it.isDigit() }) "UPI ${handle.takeLast(4).padStart(handle.length.coerceAtMost(10), 'X')}" else handle
            if (s.startsWith("UPI ")) return s
            s = s.replace(Regex("[._\\-]+"), " ")
        }
        repeat(3) { s = s.replace(prefixes, "") }
        // "UPI-ZOMATO-zomato" style: split on separators and keep the first meaningful chunk
        val chunks = s.split(Regex("[/\\-*|]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (chunks.size > 1) {
            s = chunks.firstOrNull { c -> c.any { it.isLetter() } && !idToken.matches(c) } ?: chunks.first()
        }
        brands.firstOrNull { it.first.containsMatchIn(s) }?.let { return it.second }
        s = s.replace(idToken, " ").replace(legal, " ").replace(Regex("\\s+"), " ").trim(' ', '.', ',', '-')
        if (s.isEmpty()) return raw.trim()
        return titleCase(s)
    }

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { w ->
        if (w.length <= 3 && w.all { it.isUpperCase() || it.isDigit() } && w.any { it.isLetter() } && w.length > 1) {
            w // keep acronyms like "ATM", "SBI"
        } else {
            w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
