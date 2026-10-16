package com.amitjha.kosh.repo

import androidx.room.withTransaction
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.TxnSource

/** One-time fixes applied to existing data after an update. Each repair runs once, in order. */
object DataRepairs {

    /** Bump when adding a repair below. */
    const val CURRENT = 3

    suspend fun run(db: KoshDatabase, settings: SettingsStore, netWorth: NetWorthRepository) {
        val done = settings.repairVersion()
        if (done >= CURRENT) return
        if (done < 1) repairBalancesV1(db, netWorth)
        // v1.5: money moved between own accounts was counted as expense and income.
        if (done < 2) TransferPairer.run(db)
        // v2.1.4: only earnings count as income, so "Other Income" is now "Money received".
        if (done < 3) {
            db.categories().renameDefault("other_income", "Other Income", "Money received")
            db.categories().renameDefault("interest", "Interest", "Interest & dividends")
        }
        settings.setRepairVersion(CURRENT)
    }

    /**
     * v1.4 balance rules. Before this, accounts created from SMS counted their whole imported history
     * (from a zero starting balance) towards net worth, which could show crores that never existed.
     *
     * - Accounts created from SMS that were never given a balance become pending again, so they stop counting.
     * - Existing imports with implausible amounts are flagged suspicious, so they stop moving balances.
     * - The recorded net worth history was computed with the old rules, so it restarts from today.
     */
    private suspend fun repairBalancesV1(db: KoshDatabase, netWorth: NetWorthRepository) {
        db.withTransaction {
            db.accounts().all()
                .filter { !it.autoCreated && it.openingDate == 0L && it.reportedBalance == null }
                .forEach { db.accounts().update(it.copy(autoCreated = true)) }

            val imported = db.transactions().all().filter { it.source != TxnSource.MANUAL && !it.suspicious }
            imported.groupBy { it.accountId }.forEach { (_, rows) ->
                // Back into Review so the user can confirm or delete them.
                flagSuspicious(rows).forEach { db.transactions().update(it.copy(suspicious = true, reviewed = false)) }
            }
        }
        netWorth.resetHistory()
    }

    /** Rows of one account whose amount looks implausible next to the rest. */
    internal fun flagSuspicious(rows: List<TransactionEntity>): List<TransactionEntity> {
        val amounts = rows.map { it.amount }
        return rows.filter { row ->
            // Compare each row against the others, so one huge amount doesn't raise its own median.
            val others = amounts.filterIndexed { i, _ -> rows[i].id != row.id }
            SmsMapping.isSuspicious(row.amount, row.categoryKey, others)
        }
    }
}
