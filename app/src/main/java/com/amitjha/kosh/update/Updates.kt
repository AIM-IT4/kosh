package com.amitjha.kosh.update

import android.content.Context
import android.os.Build
import com.amitjha.kosh.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A published Kosh release. */
data class Release(
    /** "2.3.0" (the tag without its "v"). */
    val version: String,
    /** The changelog lines, without the bullets. */
    val notes: List<String>,
    /** Direct download of the standard edition's APK, when the release has one. */
    val apkUrl: String?,
    val pageUrl: String,
)

/** Reading GitHub's "latest release" answer and comparing versions; no Android here, so it is unit tested. */
object ReleaseInfo {
    const val LATEST_URL = "https://api.github.com/repos/AIM-IT4/kosh/releases/latest"
    private const val APK_NAME = "Kosh.apk"

    fun parse(json: String): Release? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val version = o.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty() || o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        // The notes are the fastlane changelog: one "- " line per change, between a title and the install notes.
        val notes = o.optString("body").lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
        val assets = o.optJSONArray("assets")
        val apk = assets?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name") == APK_NAME } }
        val page = o.optString("html_url").takeIf { it.startsWith("https://") } ?: Updates.RELEASES_PAGE
        return Release(version, notes, apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }, page)
    }

    /** Whether [latest] is a later version than [current]: "2.10.0" > "2.9.1" > "2.9". Unreadable parts count as 0. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * Tells the user when a newer Kosh is out. Once a day it asks GitHub for the latest release number (nothing about the
 * user is sent), and the app offers the download, which installs over the current version and keeps the data.
 * The offline edition has no internet access and never checks; installs from F-Droid or another store are left to
 * that store unless the user turns the check on.
 */
class Updates(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("kosh_updates", Context.MODE_PRIVATE)

    /** Whether this build can check at all. */
    val available: Boolean get() = BuildConfig.NETWORK_ENABLED

    /** Installed by an app store that keeps Kosh up to date itself. */
    val fromStore: Boolean get() = installer()?.let { it in STORES } == true

    var enabled: Boolean
        get() = available && prefs.getBoolean(KEY_ENABLED, !fromStore)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The newest release seen by the last check, if any. */
    val latest: Release?
        get() = prefs.getString(KEY_VERSION, null)?.let { v ->
            Release(v, prefs.getString(KEY_NOTES, "").orEmpty().lines().filter { it.isNotBlank() }, prefs.getString(KEY_APK, null), prefs.getString(KEY_PAGE, null) ?: RELEASES_PAGE)
        }

    /** Asks GitHub when the last check is a day old (or [force]); returns the latest release, or null if it couldn't. */
    fun check(force: Boolean = false, now: Long = System.currentTimeMillis()): Release? {
        if (!available || (!force && !enabled)) return null
        if (!force && now - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MILLIS) return latest
        val release = fetch()?.let(ReleaseInfo::parse) ?: return null
        prefs.edit()
            .putLong(KEY_CHECKED, now)
            .putString(KEY_VERSION, release.version)
            .putString(KEY_NOTES, release.notes.joinToString("\n"))
            .putString(KEY_APK, release.apkUrl)
            .putString(KEY_PAGE, release.pageUrl)
            .apply()
        return release
    }

    /** The release to offer now: newer than this version, not skipped, and not put off until later. */
    fun toOffer(now: Long = System.currentTimeMillis()): Release? {
        val r = latest ?: return null
        if (!enabled || !ReleaseInfo.isNewer(r.version, BuildConfig.VERSION_NAME)) return null
        if (prefs.getString(KEY_SKIPPED, null) == r.version) return null
        if (now < prefs.getLong(KEY_LATER_UNTIL, 0)) return null
        return r
    }

    fun isNewer(r: Release): Boolean = ReleaseInfo.isNewer(r.version, BuildConfig.VERSION_NAME)

    fun later(now: Long = System.currentTimeMillis()) = prefs.edit().putLong(KEY_LATER_UNTIL, now + LATER_MILLIS).apply()

    fun skip(version: String) = prefs.edit().putString(KEY_SKIPPED, version).apply()

    private fun fetch(): String? = try {
        val conn = URL(ReleaseInfo.LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MILLIS
        conn.readTimeout = TIMEOUT_MILLIS
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Kosh/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun installer(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getInstallerPackageName(app.packageName)
        }
    }.getOrNull()

    companion object {
        const val RELEASES_PAGE = "https://github.com/AIM-IT4/kosh/releases/latest"
        private const val CHECK_EVERY_MILLIS = 24 * 60 * 60_000L
        /** "Later" waits this long before asking again. */
        private const val LATER_MILLIS = 3 * 24 * 60 * 60_000L
        private const val TIMEOUT_MILLIS = 10_000

        /** Stores and updaters that install Kosh's updates themselves. */
        private val STORES = setOf(
            "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid", "in.sunilpaulmathew.izzyondroid",
            "dev.imranr.obtainium", "com.aurora.store", "com.android.vending",
        )

        private const val KEY_ENABLED = "enabled"
        private const val KEY_CHECKED = "checked_at"
        private const val KEY_VERSION = "latest_version"
        private const val KEY_NOTES = "latest_notes"
        private const val KEY_APK = "latest_apk"
        private const val KEY_PAGE = "latest_page"
        private const val KEY_SKIPPED = "skipped_version"
        private const val KEY_LATER_UNTIL = "later_until"
    }
}
