package com.amitjha.kosh.sms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InboxScanTest {
    @Test fun emptyFirstScanEnablesCatchUpAndClearsOldError() = runBlocking {
        var lastScan = 0L
        var problem: String? = "SMS access is off"
        completeInboxScan(100L, emptyList(), null, { ImportResult() },
            { lastScan = it }, { message, _ -> problem = message })
        assertEquals(100L, lastScan)
        assertNull(problem)
    }

    @Test fun failedReadDoesNotSkipMissedMessagesOnRetry() = runBlocking {
        var lastScan = 20L
        var problem: String? = null
        val result = completeInboxScan(100L, emptyList(), "SMS access was denied by the system",
            { ImportResult() }, { lastScan = it }, { message, _ -> problem = message })
        assertEquals(20L, lastScan)
        assertEquals(result.readError, problem)
    }

    @Test fun failedWritesDoNotAdvancePastUnsavedTransactions() = runBlocking {
        var lastScan = 20L
        var problem: String? = null
        completeInboxScan(100L, emptyList(), null, { ImportResult(failed = 1) },
            { lastScan = it }, { message, _ -> problem = message })
        assertEquals(20L, lastScan)
        assertEquals("1 bank SMS couldn't be saved", problem)
    }

    @Test fun cancellationDoesNotCommitScanState() = runBlocking {
        var recorded = false
        try {
            completeInboxScan(100L, emptyList(), null, { throw CancellationException() },
                { recorded = true }, { _, _ -> recorded = true })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(recorded)
    }
}
