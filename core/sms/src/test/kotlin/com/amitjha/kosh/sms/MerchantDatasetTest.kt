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
}
