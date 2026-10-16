package com.amitjha.kosh.security

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.AttachmentEntity
import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.DebtEntity
import com.amitjha.kosh.data.LoanEntity
import com.amitjha.kosh.data.RecurringRuleEntity
import com.amitjha.kosh.data.SplitEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.TxnType
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

    private val v3 = data.copy(
        transactions = listOf(
            TransactionEntity(7, 1, type = TxnType.EXPENSE, amount = 2_500_00, categoryKey = "shopping", timestamp = 1_000, tags = "|Goa trip|Reimbursable|"),
        ),
        budgets = listOf(BudgetEntity(3, "food", 8_000_00, rollover = true)),
        splits = listOf(SplitEntity(1, 7, "groceries", 1_500_00, "veggies"), SplitEntity(2, 7, "shopping", 1_000_00)),
        debts = listOf(DebtEntity(1, "Ravi", "LENT", 5_000_00, repaid = 1_000_00, date = 20_000, dueDate = 20_030, note = "trip", createdAt = 9)),
        recurring = listOf(
            RecurringRuleEntity(1, "Rent", TxnType.EXPENSE, 25_000_00, "rent", 1, cadence = "MONTHLY", anchorDay = 20_000, nextDay = 20_031, createdAt = 9),
        ),
        loans = listOf(
            LoanEntity(1, "Home loan", "SBI", "HOME", 50_00_000_00, 8.5, 240, 20_000, emi = 43_391_00,
                prepaymentsJson = """[{"day":20100,"amount":100000,"reduce":"TENURE"}]""", createdAt = 9),
        ),
        attachments = listOf(AttachmentEntity(1, 7, "a.bin", "image/jpeg", 3, "Bill", warrantyUntil = 20_365, createdAt = 9)),
        attachmentFiles = mapOf("a.bin" to byteArrayOf(1, 2, 3)),
    )

    @Test
    fun formatThreeRoundTripsEveryNewTableAndTheReceiptFiles() {
        val back = BackupJson.decode(BackupJson.encode(v3))
        assertEquals(v3.copy(attachmentFiles = emptyMap()), back.copy(attachmentFiles = emptyMap()))
        assertEquals(v3.attachmentFiles.keys, back.attachmentFiles.keys)
        assert(back.attachmentFiles.getValue("a.bin").contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun formatTwoFilesRestoreWithoutTheNewData() {
        val o = JSONObject(BackupJson.encode(v3)).put("format", 2)
        listOf("splits", "debts", "recurring", "loans", "attachments", "attachmentFiles").forEach { o.remove(it) }
        o.getJSONArray("transactions").getJSONObject(0).remove("tags")
        o.getJSONArray("budgets").getJSONObject(0).remove("rollover")
        val old = BackupJson.decode(o.toString())
        assertEquals("", old.transactions.single().tags)
        assertEquals(false, old.budgets.single().rollover)
        assertEquals(emptyList<SplitEntity>(), old.splits)
        assertEquals(emptyList<LoanEntity>(), old.loans)
        assertEquals(emptyMap<String, ByteArray>(), old.attachmentFiles)
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
