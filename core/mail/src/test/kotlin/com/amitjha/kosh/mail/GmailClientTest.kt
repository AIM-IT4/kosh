package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class GmailClientTest {
    private fun b64(s: String, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(charset))

    private val htmlBody = "<table><tr><td>Total Amount Due</td><td>&#8377;9,876.50</td></tr>" +
        "<tr><td>Payment Due Date</td><td>12-Feb-2025</td></tr></table>" +
        "<p>Your ICICI Bank Credit Card XX2468 statement.</p>"

    /** A `messages.get?format=full` response shaped like Gmail's, with nested multipart and a PDF. */
    private fun messageJson(id: String) = """
        {
          "id": "$id",
          "threadId": "t-$id",
          "labelIds": ["INBOX"],
          "snippet": "Your statement",
          "internalDate": "1737014400000",
          "payload": {
            "mimeType": "multipart/mixed",
            "filename": "",
            "headers": [
              {"name": "From", "value": "ICICI Bank <credit_cards@icicibank.com>"},
              {"name": "Subject", "value": "Credit Card Statement – Jan 2025"},
              {"name": "Date", "value": "Thu, 16 Jan 2025 08:00:00 +0000"}
            ],
            "body": {"size": 0},
            "parts": [
              {
                "mimeType": "multipart/alternative",
                "filename": "",
                "headers": [{"name": "Content-Type", "value": "multipart/alternative; boundary=\"b2\""}],
                "body": {"size": 0},
                "parts": [
                  {
                    "partId": "0.0",
                    "mimeType": "text/plain",
                    "filename": "",
                    "headers": [{"name": "Content-Type", "value": "text/plain; charset=\"UTF-8\""}],
                    "body": {"size": 10, "data": "${b64("Total Amount Due: ₹9,876.50\nPayment Due Date: 12-Feb-2025\nCard XX2468 statement")}"}
                  },
                  {
                    "partId": "0.1",
                    "mimeType": "text/html",
                    "filename": "",
                    "headers": [{"name": "Content-Type", "value": "text/html; charset=UTF-8"}],
                    "body": {"size": 10, "data": "${b64(htmlBody)}"}
                  }
                ]
              },
              {
                "partId": "1",
                "mimeType": "application/pdf",
                "filename": "CCStatement_Jan2025.pdf",
                "headers": [{"name": "Content-Type", "value": "application/pdf"}],
                "body": {"attachmentId": "ANGjdJ8", "size": 102400}
              }
            ]
          }
        }
    """.trimIndent()

    private class FakeTransport(val handler: (String, Map<String, String>) -> HttpResponse) : HttpTransport {
        val urls = ArrayList<String>()
        val headers = ArrayList<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls.add(url)
            this.headers.add(headers)
            return handler(url, headers)
        }
    }

    @Test
    fun parsesNestedMultipartMessage() {
        val transport = FakeTransport { _, _ -> HttpResponse(200, messageJson("abc")) }
        val msg = GmailClient({ "tok" }, transport).getMessage("abc")
        assertEquals("abc", msg.id)
        assertEquals("ICICI Bank <credit_cards@icicibank.com>", msg.from)
        assertEquals("Credit Card Statement – Jan 2025", msg.subject)
        assertEquals(1737014400000L, msg.dateMillis)
        assertTrue(msg.textBody!!.contains("₹9,876.50"))
        assertTrue(msg.htmlBody!!.contains("<table>"))
        assertEquals(listOf("CCStatement_Jan2025.pdf"), msg.attachmentNames)
        assertEquals("Bearer tok", transport.headers.single()["Authorization"])
        assertTrue(transport.urls.single().endsWith("/messages/abc?format=full"))

        val bill = BillEmailParser.parse(msg)!!
        assertEquals(Bank.ICICI, bill.bank)
        assertEquals("2468", bill.cardLast4)
        assertEquals(987650L, bill.totalDue)
        assertTrue(bill.hasPdfStatement)
    }

    @Test
    fun htmlOnlyMessageFallsBackToText() {
        val json = """
            {"id":"h1","internalDate":"1700000000000","payload":{"mimeType":"text/html","filename":"",
             "headers":[{"name":"From","value":"x@y.com"},{"name":"Subject","value":"S"}],
             "body":{"data":"${b64("<p>Hello&nbsp;there<br>Line&amp;two</p>")}"}}}
        """.trimIndent()
        val msg = GmailClient({ "t" }, FakeTransport { _, _ -> HttpResponse(200, json) }).getMessage("h1")
        assertEquals(null, msg.textBody)
        assertEquals("Hello there\nLine&two", msg.bodyText)
    }

    @Test
    fun decodesLatin1Charset() {
        val json = """
            {"id":"l1","payload":{"mimeType":"text/plain","filename":"",
             "headers":[{"name":"Content-Type","value":"text/plain; charset=ISO-8859-1"}],
             "body":{"data":"${b64("Café", Charsets.ISO_8859_1)}"}}}
        """.trimIndent()
        val msg = GmailClient({ "t" }, FakeTransport { _, _ -> HttpResponse(200, json) }).getMessage("l1")
        assertEquals("Café", msg.textBody)
    }

    @Test
    fun searchPaginatesAndEncodesQuery() {
        val transport = FakeTransport { url, _ ->
            if ("pageToken" in url) {
                HttpResponse(200, """{"messages":[{"id":"3","threadId":"x"}],"resultSizeEstimate":1}""")
            } else {
                HttpResponse(200, """{"messages":[{"id":"1"},{"id":"2"}],"nextPageToken":"p2"}""")
            }
        }
        val ids = GmailClient({ "t" }, transport).searchMessageIds("from:(a.com) \"amount due\"", max = 10)
        assertEquals(listOf("1", "2", "3"), ids)
        assertTrue(transport.urls[0].contains("q=from%3A%28a.com%29%20%22amount%20due%22"))
        assertTrue(transport.urls[0].contains("maxResults=10"))
        assertTrue(transport.urls[1].contains("pageToken=p2"))
    }

    @Test
    fun emptySearchResult() {
        val ids = GmailClient({ "t" }, FakeTransport { _, _ -> HttpResponse(200, """{"resultSizeEstimate":0}""") })
            .searchMessageIds("x")
        assertTrue(ids.isEmpty())
    }

    @Test
    fun unauthorizedThrowsAuthExpired() {
        val client = GmailClient({ "t" }, FakeTransport { _, _ -> HttpResponse(401, "{}") })
        try {
            client.getMessage("x")
            fail("expected AuthExpired")
        } catch (e: GmailException.AuthExpired) {
            // expected
        }
    }

    @Test
    fun retriesOnceOnServerError() {
        var calls = 0
        val sleeps = ArrayList<Long>()
        val transport = FakeTransport { _, _ ->
            calls++
            if (calls == 1) HttpResponse(503, "busy") else HttpResponse(200, """{"messages":[{"id":"9"}]}""")
        }
        val ids = GmailClient({ "t" }, transport, sleeper = { sleeps.add(it) }).searchMessageIds("q")
        assertEquals(listOf("9"), ids)
        assertEquals(2, calls)
        assertEquals(1, sleeps.size)
    }

    @Test
    fun givesUpAfterSecondFailure() {
        val transport = FakeTransport { _, _ -> HttpResponse(429, "rate") }
        try {
            GmailClient({ "t" }, transport, sleeper = {}).searchMessageIds("q")
            fail("expected Http")
        } catch (e: GmailException.Http) {
            assertEquals(429, e.code)
            assertTrue(e.retryable)
            assertEquals(2, transport.urls.size)
        }
    }

    @Test
    fun clientErrorIsNotRetried() {
        val transport = FakeTransport { _, _ -> HttpResponse(400, "bad") }
        try {
            GmailClient({ "t" }, transport, sleeper = {}).getMessage("x")
            fail("expected Http")
        } catch (e: GmailException.Http) {
            assertFalse(e.retryable)
            assertEquals(1, transport.urls.size)
        }
    }

    @Test
    fun tokenProviderCalledPerRequest() {
        var n = 0
        val transport = FakeTransport { _, _ -> HttpResponse(200, """{"messages":[]}""") }
        val client = GmailClient({ "tok${++n}" }, transport)
        client.searchMessageIds("a")
        client.searchMessageIds("b")
        assertEquals("Bearer tok2", transport.headers[1]["Authorization"])
    }

    @Test
    fun fetcherSkipsKnownIdsAndSplitsBillsAndPayments() {
        val payment = """
            {"id":"p1","internalDate":"1737504000000","payload":{"mimeType":"text/plain","filename":"",
             "headers":[{"name":"From","value":"alerts@hdfcbank.net"},{"name":"Subject","value":"Payment received"}],
             "body":{"data":"${b64("Payment of Rs. 2,000.00 has been received towards your HDFC Bank Credit Card ending 4321.")}"}}}
        """.trimIndent()
        val transport = FakeTransport { url, _ ->
            when {
                "/messages?q=" in url && "received" in url.lowercase() ->
                    HttpResponse(200, """{"messages":[{"id":"p1"},{"id":"old"}]}""")
                "/messages?q=" in url -> HttpResponse(200, """{"messages":[{"id":"b1"},{"id":"old"}]}""")
                "/messages/b1" in url -> HttpResponse(200, messageJson("b1"))
                "/messages/p1" in url -> HttpResponse(200, payment)
                else -> HttpResponse(404, "missing")
            }
        }
        val result = GmailBillFetcher(GmailClient({ "t" }, transport, sleeper = {}))
            .fetch(setOf(Bank.ICICI, Bank.HDFC), sinceDays = 60, skipIds = setOf("old"))
        assertEquals(2, result.scannedMessages)
        assertEquals(1, result.bills.size)
        assertEquals(987650L, result.bills.single().totalDue)
        assertEquals(1, result.payments.size)
        assertEquals(200000L, result.payments.single().amount)
        assertTrue(result.failedMessageIds.isEmpty())
        assertFalse(transport.urls.any { it.contains("/messages/old") })
    }
}
