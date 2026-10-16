package com.amitjha.kosh.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates the community dataset (kosh/merchants.json) so pull requests that break it fail CI. */
class MerchantDatasetTest {

    private val dataset = MerchantDataset.load()

    @Test
    fun bundledDatasetLoadsWithSupportedSchema() {
        assertNotNull("kosh/merchants.json must be present and valid", dataset)
        assertEquals(MerchantDataset.SUPPORTED_SCHEMA, dataset!!.schemaVersion)
        assertEquals(dataset.rules.size, CategoryGuesser.ruleCount)
    }

    @Test
    fun everyCategoryIsKnownAndEveryPatternCompiles() {
        for (r in dataset!!.rules) {
            assertTrue("unknown category ${r.category}", r.category in CategoryGuesser.CATEGORY_KEYS)
            (r.brands + r.generic).forEach { Regex(it) }
        }
    }

    @Test
    fun noDuplicateBrandPatterns() {
        val seen = HashMap<String, String>()
        val dupes = mutableListOf<String>()
        for (r in dataset!!.rules) for (b in r.brands) {
            val key = b.lowercase()
            seen[key]?.let { dupes += "'$b' in ${r.category} and $it" }
            seen.putIfAbsent(key, r.category)
        }
        assertTrue("duplicate brand patterns: $dupes", dupes.isEmpty())
    }

    @Test
    fun noDuplicateCategoriesOrBlankPatterns() {
        val cats = dataset!!.rules.map { it.category }
        assertEquals("each category appears once (order decides precedence)", cats.size, cats.toSet().size)
        assertTrue(dataset.rules.all { r -> (r.brands + r.generic).all { it.isNotBlank() } })
    }

    @Test
    fun parserRejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) { MerchantDataset.parse("""{"schemaVersion": 2, "rules": []}""") }
        assertThrows(IllegalArgumentException::class.java) {
            MerchantDataset.parse("""{"schemaVersion": 1, "rules": [{"category": "nope", "brands": ["x"]}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MerchantDataset.parse("""{"schemaVersion": 1, "rules": [{"category": "food", "brands": ["(unclosed"]}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) { MerchantDataset.parse("""{"a": 1, "a": 2}""") }
    }

    @Test
    fun datasetDrivesCategorisation() {
        assertEquals("food", CategoryGuesser.guess("Swiggy", "", com.amitjha.kosh.model.TxnType.EXPENSE))
        assertEquals("groceries", CategoryGuesser.guess("Swiggy Instamart", "", com.amitjha.kosh.model.TxnType.EXPENSE))
    }

    @Test
    fun datasetCoversAWideRangeOfIndianMerchants() {
        assertTrue("dataset has ${dataset!!.rules.sumOf { it.brands.size }} brand patterns", dataset.rules.sumOf { it.brands.size } >= 1000)
    }

    /** A sample of the merchants added in the October 2026 dataset update, one or more per category. */
    @Test
    fun addedMerchantsAreCategorised() {
        val cases = listOf(
            "La Pinoz" to "food", "Sagar Ratna" to "food", "Goli Vada Pav" to "food", "Monginis" to "food", "Popeyes" to "food",
            "Flipkart Minutes" to "groceries", "Akshayakalpa" to "groceries", "Metro Cash and Carry" to "groceries", "Big Basket" to "groceries",
            "Tata Neu" to "shopping", "Joyalukkas" to "shopping", "Meena Bazaar" to "shopping", "Kama Ayurveda" to "shopping",
            "Star Air" to "travel", "Qatar Airways" to "travel", "Crowne Plaza" to "travel", "ConfirmTkt" to "travel", "HRTC" to "travel",
            "Maruti Suzuki" to "transport", "Royal Enfield" to "transport", "Cars24" to "transport", "IHMCL" to "transport",
            "ElectricPe" to "fuel", "Kazam" to "fuel",
            "JDVVNL" to "bills", "MGVCL" to "bills", "TPCODL" to "bills", "HESCOM" to "bills", "HMWSSB" to "bills",
            "Kerala Vision" to "bills", "BEST Undertaking" to "bills", "MNGL" to "bills",
            "ShemarooMe" to "subscriptions", "Apple Music" to "subscriptions", "Pocket FM" to "subscriptions",
            "Times of India" to "subscriptions", "Jio Saavn" to "subscriptions",
            "Canara HSBC Life" to "insurance", "Ageas Federal Life" to "insurance", "Bandhan Life" to "insurance",
            "Mirae Asset" to "investment", "PPFAS" to "investment", "HDFC MF" to "investment", "Bandhan MF" to "investment",
            "CoinDCX" to "investment",
            "Hero FinCorp" to "emi", "Bajaj Housing Finance" to "emi", "HDFC Credila" to "emi",
            "Cuemath" to "education", "Sri Chaitanya" to "education", "Testbook" to "education",
            "Davaindia" to "health", "Kokilaben" to "health", "Agilus" to "health", "Jan Aushadhi Kendra" to "health",
            "Fitternity" to "fitness", "Playo" to "fitness",
            "VLCC" to "personal_care", "Kaya Skin Clinic" to "personal_care",
            "The Sleep Company" to "household", "Furlenco" to "household",
            "PM CARES Fund" to "gifts", "TTD" to "gifts",
            "EsselWorld" to "entertainment", "Zupee" to "entertainment",
            "Parivahan" to "taxes", "Isthara" to "rent",
        )
        val wrong = cases.mapNotNull { (merchant, expected) ->
            val got = CategoryGuesser.guess(merchant, "", com.amitjha.kosh.model.TxnType.EXPENSE)
            if (got != expected) "$merchant: expected $expected, got $got" else null
        }
        assertTrue("Miscategorised:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun brokerDhanIsNotMatchedInsideOtherWords() {
        assertEquals("investment", CategoryGuesser.guess("Dhan", "", com.amitjha.kosh.model.TxnType.EXPENSE))
        // "Bandhan" ends in "dhan"
        assertEquals("insurance", CategoryGuesser.guess("Bandhan Life", "", com.amitjha.kosh.model.TxnType.EXPENSE))
    }

    @Test
    fun subBrandsKeepTheirOwnCategoryThroughAFullMessage() {
        fun parse(body: String) = txn("AD-HDFCBK", body)
        parse("Spent Rs.649.00 On HDFC Bank Card 5678 At AMAZON FRESH On 2025-01-05:14:23:11.").let {
            assertEquals("Amazon Fresh", it.merchant)
            assertEquals("groceries", it.suggestedCategory)
        }
        parse("Spent Rs.299.00 On HDFC Bank Card 5678 At TIMES OF INDIA On 2025-01-05:14:23:11.").let {
            // "India" is no longer dropped as if it were a legal suffix ("Times Of")
            assertTrue(it.merchant, it.merchant.equals("Times of India", ignoreCase = true))
            assertEquals("subscriptions", it.suggestedCategory)
        }
        parse("Spent Rs.1,200.00 On HDFC Bank Card 5678 At NEUBERG DIAGNOSTICS PVT LTD On 2025-01-05:14:23:11.").let {
            assertEquals("Neuberg Diagnostics", it.merchant)
            assertEquals("health", it.suggestedCategory)
        }
    }
}
