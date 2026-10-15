package com.amitjha.kosh.mail

import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64

/** One e-mail as needed by the bill parser. */
data class EmailMessage(
    val id: String,
    val from: String,
    val subject: String,
    val dateMillis: Long,
    val textBody: String?,
    val htmlBody: String?,
    val attachmentNames: List<String> = emptyList(),
) {
    /** Plain-text body, falling back to the HTML body converted to text. */
    val bodyText: String by lazy {
        when {
            !textBody.isNullOrBlank() -> textBody
            !htmlBody.isNullOrBlank() -> HtmlText.toText(htmlBody)
            else -> ""
        }
    }
}

/** Errors raised by [GmailClient]. */
sealed class GmailException(message: String) : Exception(message) {
    class ScanTimedOut : GmailException("Gmail scan paused after one minute. Tap sync again to continue.")
    /** HTTP 401: the access token expired or was revoked; refresh it and retry. */
    class AuthExpired(message: String = "Gmail access token expired or invalid") : GmailException(message)

    /** Any other non-success response, after the single retry for 429/5xx. */
    class Http(val code: Int, val body: String) : GmailException("Gmail API returned HTTP $code") {
        val retryable: Boolean get() = code == 429 || code in 500..599
    }
}

/**
 * Read-only Gmail REST v1 client. [tokenProvider] returns a current OAuth access token with the
 * `gmail.readonly` scope; it is called for every request so a refreshed token is picked up.
 */
class GmailClient(
    private val tokenProvider: () -> String,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me",
    private val retryDelayMs: Long = 1_500,
    private val checkActive: () -> Unit = {},
) {
    /** IDs of messages matching a Gmail search [query], newest first, at most [max]. */
    fun searchMessageIds(query: String, max: Int = 50, skipIds: Set<String> = emptySet()): List<String> {
        require(max > 0)
        val ids = ArrayList<String>()
        var pageToken: String? = null
        val visitedPages = HashSet<String>()
        do {
            val pageSize = if (skipIds.isEmpty()) minOf(500, max - ids.size) else 500
            val url = buildString {
                append(baseUrl).append("/messages?q=").append(encode(query))
                append("&maxResults=").append(pageSize)
                if (pageToken != null) append("&pageToken=").append(encode(pageToken!!))
            }
            val json = MiniJson.parse(call(url)).obj()
            json["messages"].arr().forEach { m ->
                m.obj().str("id")?.takeIf { it !in skipIds && it !in ids }?.let(ids::add)
            }
            pageToken = json.str("nextPageToken")
            if (pageToken != null && !visitedPages.add(pageToken!!)) {
                throw GmailException.Http(502, "Gmail repeated a page token")
            }
        } while (pageToken != null && ids.size < max)
        return ids.take(max)
    }

    /** The full message with its decoded bodies. */
    fun getMessage(id: String): EmailMessage {
        val json = MiniJson.parse(call("$baseUrl/messages/${encode(id)}?format=full")).obj()
        return parseMessage(json)
    }

    private fun call(url: String): String {
        var attempt = 0
        while (true) {
            checkActive()
            val response = transport.get(
                url,
                mapOf("Authorization" to "Bearer ${tokenProvider()}", "Accept" to "application/json"),
            )
            when {
                response.code in 200..299 -> return response.body
                response.code == 401 -> throw GmailException.AuthExpired()
                (response.code == 429 || response.code in 500..599) && attempt == 0 -> {
                    attempt++
                    sleeper(retryDelayMs)
                }
                else -> throw GmailException.Http(response.code, response.body)
            }
        }
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** Builds an [EmailMessage] from a `users.messages.get?format=full` JSON response. */
        fun parseMessage(json: Map<String, Any?>): EmailMessage {
            val payload = json["payload"].obj()
            val headers = headerMap(payload)
            val parts = MimeParts()
            walk(payload, parts)
            val date = json.str("internalDate")?.toLongOrNull()
                ?: headers["date"]?.let { parseRfc822(it) } ?: 0L
            return EmailMessage(
                id = json.str("id") ?: "",
                from = headers["from"] ?: "",
                subject = headers["subject"] ?: "",
                dateMillis = date,
                textBody = parts.text.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                htmlBody = parts.html.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                attachmentNames = parts.attachments,
            )
        }

        private class MimeParts {
            val text = ArrayList<String>()
            val html = ArrayList<String>()
            val attachments = ArrayList<String>()
        }

        private fun headerMap(part: Map<String, Any?>): Map<String, String> =
            part["headers"].arr().mapNotNull { h ->
                val o = h.obj()
                val name = o.str("name") ?: return@mapNotNull null
                name.lowercase() to (o.str("value") ?: "")
            }.toMap()

        private fun walk(part: Map<String, Any?>, out: MimeParts) {
            val mime = (part.str("mimeType") ?: "").lowercase()
            val filename = part.str("filename") ?: ""
            val children = part["parts"].arr()
            if (children.isNotEmpty()) {
                children.forEach { walk(it.obj(), out) }
                return
            }
            if (filename.isNotBlank()) {
                out.attachments.add(filename)
                return
            }
            val data = part["body"].obj().str("data") ?: return
            val charset = charsetOf(headerMap(part)["content-type"])
            val decoded = decodeBase64Url(data, charset)
            when {
                mime == "text/plain" -> out.text.add(decoded)
                mime == "text/html" -> out.html.add(decoded)
            }
        }

        private fun charsetOf(contentType: String?): Charset {
            val name = contentType?.let { Regex("(?i)charset=\"?([^\";\\s]+)").find(it)?.groupValues?.get(1) }
            return runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        }

        /** Decodes Gmail's base64url (padding optional) body data. */
        fun decodeBase64Url(data: String, charset: Charset = Charsets.UTF_8): String {
            val clean = data.filterNot { it.isWhitespace() }
            val bytes = Base64.getUrlDecoder().decode(clean.replace('+', '-').replace('/', '_'))
            return String(bytes, charset)
        }

        private fun parseRfc822(value: String): Long? = runCatching {
            java.time.ZonedDateTime.parse(
                value.replace(Regex("\\s*\\(.*\\)$"), "").trim(),
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            ).toInstant().toEpochMilli()
        }.getOrNull()
    }
}

