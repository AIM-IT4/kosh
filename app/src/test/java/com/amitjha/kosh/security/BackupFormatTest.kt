package com.amitjha.kosh.security

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.DeletedFingerprintEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.model.AccountType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class BackupFormatTest {

    private val data = BackupData(
        accounts = listOf(
            AccountEntity(1, "HDFC Savings", AccountType.BANK, "HDFC", "4521", openingDate = 100, interestRatePct = 3.5, interestCreditFrequency = "MONTHLY"),
        ),
        categories = emptyList(),
        transactions = emptyList(),
        bills = emptyList(),
        budgets = emptyList(),
        goals = listOf(
            GoalEntity(1, "Car", 8_00_000_00, 1_00_000_00, 21_000, "car", 7.0, 5, loanAmount = 4_00_000_00, loanRatePct = 8.75, loanTenureMonths = 60),
        ),
        investments = emptyList(),
        snapshots = emptyList(),
        rules = emptyList(),
        netWorth = emptyList(),
        tombstones = listOf(DeletedFingerprintEntity("fp-deleted", 42)),
    )

    @Test
    fun newFieldsRoundTrip() {
        assertEquals(data, BackupJson.decode(BackupJson.encode(data)))
    }

    @Test
    fun metaRecordsFormatExportTimeAndRepairVersion() {
        val meta = BackupJson.meta(BackupJson.encode(data, exportedAt = 1_234, repairVersion = 3))
        assertEquals(BackupMeta(BackupJson.FORMAT_VERSION, 1_234, 3), meta)
    }

    @Test
    fun formatOneFilesStillRestoreWithDefaults() {
        val o = JSONObject(BackupJson.encode(data))
        o.put("format", 1)
        o.remove("repairVersion")
        o.remove("deletedFingerprints")
        o.getJSONArray("accounts").getJSONObject(0).apply {
            remove("interestRatePct")
            remove("interestCreditFrequency")
        }
        o.getJSONArray("goals").getJSONObject(0).apply {
            remove("loanAmount")
            remove("loanRatePct")
            remove("loanTenureMonths")
        }
        val old = BackupJson.decode(o.toString())
        val account = old.accounts.single()
        assertNull(account.interestRatePct)
        assertEquals("QUARTERLY", account.interestCreditFrequency)
        val goal = old.goals.single()
        assertEquals(0L, goal.loanAmount)
        assertEquals(9.0, goal.loanRatePct, 0.0)
        assertEquals(36, goal.loanTenureMonths)
        assertEquals(emptyList<DeletedFingerprintEntity>(), old.tombstones)
        assertNull(BackupJson.meta(o.toString()).repairVersion)
    }

    @Test
    fun newerFormatIsRejected() {
        val o = JSONObject(BackupJson.encode(data)).put("format", BackupJson.FORMAT_VERSION + 1)
        try {
            BackupJson.decode(o.toString())
            fail("expected the unknown format to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun outOfRangeIterationCountIsRejectedBeforeKeyDerivation() {
        val enc = BackupCrypto.encrypt("{}".toByteArray(), "password1".toCharArray(), iterations = BackupCrypto.MIN_ITERATIONS)
        // Header: "KOSH" magic, a version byte, then the iteration count as a big-endian int.
        listOf(1, BackupCrypto.MIN_ITERATIONS - 1, BackupCrypto.MAX_ITERATIONS + 1, Int.MAX_VALUE, -1).forEach { n ->
            val forged = enc.copyOf()
            ByteBuffer.wrap(forged, 5, 4).putInt(n)
            try {
                BackupCrypto.decrypt(forged, "password1".toCharArray())
                fail("expected $n iterations to be rejected")
            } catch (e: IllegalArgumentException) {
                assertEquals("Damaged backup file", e.message)
            }
        }
    }
}
