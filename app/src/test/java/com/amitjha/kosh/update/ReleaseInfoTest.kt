package com.amitjha.kosh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {

    private val json = """
        {"tag_name":"v2.3.0","html_url":"https://github.com/AIM-IT4/kosh/releases/tag/v2.3.0","draft":false,"prerelease":false,
         "body":"Kosh 2.3.0\n- A popup when a new version is out\n- Faster Home\n\nSigned with the Kosh release key: installs over earlier versions.",
         "assets":[{"name":"Kosh-offline.apk","browser_download_url":"https://github.com/AIM-IT4/kosh/releases/download/v2.3.0/Kosh-offline.apk"},
                   {"name":"Kosh.apk","browser_download_url":"https://github.com/AIM-IT4/kosh/releases/download/v2.3.0/Kosh.apk"}]}
    """.trimIndent()

    @Test
    fun readsTheVersionNotesAndTheStandardApk() {
        val r = ReleaseInfo.parse(json)!!
        assertEquals("2.3.0", r.version)
        assertEquals(listOf("A popup when a new version is out", "Faster Home"), r.notes)
        assertEquals("https://github.com/AIM-IT4/kosh/releases/download/v2.3.0/Kosh.apk", r.apkUrl)
        assertEquals("https://github.com/AIM-IT4/kosh/releases/tag/v2.3.0", r.pageUrl)
    }

    @Test
    fun ignoresPreReleasesAndGarbage() {
        assertNull(ReleaseInfo.parse(json.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(ReleaseInfo.parse("not json"))
        assertNull(ReleaseInfo.parse("{\"message\":\"API rate limit exceeded\"}"))
        // No APK attached: the release page is offered instead.
        assertNull(ReleaseInfo.parse(json.replace("\"Kosh.apk\"", "\"Other.apk\""))!!.apkUrl)
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(ReleaseInfo.isNewer("2.3.0", "2.2.0"))
        assertTrue(ReleaseInfo.isNewer("2.10.0", "2.9.1"))
        assertTrue(ReleaseInfo.isNewer("v3", "2.99.99"))
        assertTrue(ReleaseInfo.isNewer("2.2.1", "2.2"))
        assertFalse(ReleaseInfo.isNewer("2.2.0", "2.2.0"))
        assertFalse(ReleaseInfo.isNewer("2.2", "2.2.0"))
        assertFalse(ReleaseInfo.isNewer("2.1.9", "2.2.0"))
    }
}
