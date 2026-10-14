package com.amitjha.kosh.mail

import java.net.HttpURLConnection
import java.net.URL

/** A response from [HttpTransport]. */
data class HttpResponse(val code: Int, val body: String)

/** The HTTP layer used by [GmailClient]; replace it in tests. */
fun interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** Default transport on top of [HttpURLConnection]. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
