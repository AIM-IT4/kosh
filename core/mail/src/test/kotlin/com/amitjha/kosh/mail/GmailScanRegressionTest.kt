package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class GmailScanRegressionTest {
    private fun listing(vararg ids: String) = HttpResponse(200,
        ids.joinToString(prefix = "{\"messages\":[", postfix = "]}") { "{\"id\":\"$it\"}" })
    private fun message(id: String) = HttpResponse(200, """{"id":"$id","payload":{}}""")

    @Test fun skipsProcessedPagesToReachOlderHistory() {
        var pages = 0
        val client = GmailClient({ "token" }, HttpTransport { url, _ ->
            pages++
            if ("pageToken" !in url) HttpResponse(200,
                """{"messages":[{"id":"old"}],"nextPageToken":"next"}""")
            else listing("new")
        })
        assertEquals(listOf("new"), client.searchMessageIds("q", 1, setOf("old")))
        assertEquals(2, pages)
    }

    @Test fun limitsBatchAndReportsProgressAndContinuation() {
        val downloaded = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val client = GmailClient({ "token" }, HttpTransport { url, _ ->
            if ("?q=" in url) listing("1", "2", "3")
            else { downloaded += url; message("ok") }
        })
        val result = GmailBillFetcher(client).fetch(setOf(Bank.ICICI), maxMessages = 2,
            onProgress = { done, total -> progress += done to total })
        assertEquals(2, downloaded.size)
        assertEquals(listOf("1", "2"), result.processedIds)
        assertTrue(result.moreAvailable)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
    }

    @Test fun networkFailureStopsQueueAndPreservesCompletedMessages() {
        var downloads = 0
        val client = GmailClient({ "token" }, HttpTransport { url, _ ->
            if ("?q=" in url) listing("1", "2", "3")
            else if (++downloads == 1) message("1") else throw IOException("offline")
        })
        val result = GmailBillFetcher(client).fetch(setOf(Bank.ICICI))
        assertEquals(2, downloads)
        assertEquals(listOf("1"), result.processedIds)
        assertEquals(listOf("2"), result.failedMessageIds)
        assertNotNull(result.error)
    }

    @Test fun persistentServerErrorDoesNotRetryEveryMessage() {
        var downloads = 0
        val client = GmailClient({ "token" }, HttpTransport { url, _ ->
            if ("?q=" in url) listing("1", "2", "3")
            else { downloads++; HttpResponse(503, "unavailable") }
        }, sleeper = {})
        val result = GmailBillFetcher(client).fetch(setOf(Bank.ICICI))
        assertEquals(2, downloads) // one retry, then stop the scan
        assertTrue(result.processedIds.isEmpty())
        assertNotNull(result.error)
    }

    @Test fun deletedMessageDoesNotBlockOtherMessages() {
        val client = GmailClient({ "token" }, HttpTransport { url, _ ->
            when {
                "?q=" in url -> listing("1", "2")
                "/messages/1?" in url -> HttpResponse(404, "deleted")
                else -> message("2")
            }
        })
        val result = GmailBillFetcher(client).fetch(setOf(Bank.ICICI))
        assertEquals(listOf("2"), result.processedIds)
        assertEquals(listOf("1"), result.failedMessageIds)
        assertNull(result.error)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverConvertedToMessageFailure() {
        val client = GmailClient({ "token" }, HttpTransport { url, _ ->
            if ("?q=" in url) listing("1") else throw CancellationException("cancel")
        })
        GmailBillFetcher(client).fetch(setOf(Bank.ICICI))
    }

    @Test fun deadlinePreservesPartialBatchAndStartsNoMoreRequests() {
        var calls = 0
        val client = GmailClient({ "token" }, HttpTransport { url, _ ->
            calls++
            if ("?q=" in url) listing("1", "2") else message("1")
        }, checkActive = { if (calls == 4) throw GmailException.ScanTimedOut() })
        val result = GmailBillFetcher(client).fetch(setOf(Bank.ICICI))
        assertEquals(4, calls)
        assertEquals(listOf("1"), result.processedIds)
        assertTrue(result.moreAvailable)
        assertNotNull(result.error)
    }

    @Test(expected = GmailException.Http::class)
    fun repeatedPageTokenCannotLoopForever() {
        val client = GmailClient({ "token" }, HttpTransport { _, _ ->
            HttpResponse(200, """{"messages":[],"nextPageToken":"same"}""")
        })
        client.searchMessageIds("q")
    }
}
