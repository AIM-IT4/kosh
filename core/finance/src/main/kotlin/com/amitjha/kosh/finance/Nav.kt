package com.amitjha.kosh.finance

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A published NPS scheme NAV. */
data class NavQuote(val schemeCode: String, val schemeName: String, val nav: Double, val date: LocalDate?)

interface NavSource {
    /** Latest NAV of one scheme, or null on any failure. */
    fun latestNav(schemeCode: String): NavQuote?

    /** Latest NAVs of all schemes (empty on failure). */
    fun allSchemes(): List<NavQuote>
}

/**
 * NAVs from the public npsnav.in API (`/api/latest` and `/api/detailed/{code}`).
 * Dates are published as dd-MM-yyyy.
 */
class NpsNavApi(
    private val baseUrl: String = "https://npsnav.in/api",
    private val timeoutMillis: Int = 10_000,
) : NavSource {

    override fun latestNav(schemeCode: String): NavQuote? =
        fetch("$baseUrl/detailed/$schemeCode")?.let { NavJson.parseObjects(it).firstOrNull() }

    override fun allSchemes(): List<NavQuote> = fetch("$baseUrl/latest")?.let { NavJson.parseObjects(it) } ?: emptyList()

    private fun fetch(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMillis
        conn.readTimeout = timeoutMillis
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Kosh/1.0")
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

/** Minimal parser for the flat JSON objects npsnav.in returns (string values only). */
object NavJson {
    private val objectRegex = Regex("\\{[^{}]*\\}")
    private val fieldRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val dateFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun parseObjects(json: String): List<NavQuote> =
        objectRegex.findAll(json).mapNotNull { parseObject(it.value) }.toList()

    private fun parseObject(obj: String): NavQuote? {
        val fields = fieldRegex.findAll(obj).associate { it.groupValues[1] to it.groupValues[2] }
        val code = fields["Scheme Code"] ?: return null
        // A zero or garbled NAV would turn a contribution into infinite units.
        val nav = fields["NAV"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val date = fields["Last Updated"]?.let { runCatching { LocalDate.parse(it, dateFormat) }.getOrNull() }
        return NavQuote(code, fields["Scheme Name"] ?: code, nav, date)
    }
}
