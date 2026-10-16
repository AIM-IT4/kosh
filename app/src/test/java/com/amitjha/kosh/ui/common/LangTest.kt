package com.amitjha.kosh.ui.common

import com.amitjha.kosh.ui.nav.Tabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LangTest {

    @Test
    fun hindiTranslatesEveryTabAndPassesUnknownTextThrough() {
        val hi = AppLanguage.HINDI.translator()
        Tabs.forEach { assertNotEquals("tab ${it.label}", it.label, hi(it.label)) }
        assertEquals("होम", hi("Home"))
        assertEquals("Netflix · ₹649", hi("Netflix · ₹649"))
    }

    @Test
    fun englishLeavesTextAloneAndUnknownCodesFallBackToEnglish() {
        assertEquals("Home", AppLanguage.ENGLISH.translator()("Home"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of("fr"))
        assertEquals(AppLanguage.HINDI, AppLanguage.of("hi"))
    }
}
