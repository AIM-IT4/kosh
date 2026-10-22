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
    const val CURRENT = 4

    /** [sms] re-reads linked family members' forwards (null where SMS can't be read, e.g. in tests). */
    suspend fun run(db: KoshDatabase, settings: SettingsStore, netWorth: NetWorthRepository, sms: com.amitjha.kosh.sms.SmsImporter? = null) {
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
        // v2.6.1: family forwards that didn't name their bank went to an "Other" account instead of the member's own,
        // and some formats (SBI UPI, Bank of Baroda) were skipped altogether.
        if (done < 4) {
            runCatching { mergeForwardedOther(db) }
            if (sms != null) {
                db.family().all().filter { it.phone != null }.forEach { m -> runCatching { sms.adoptForwards(m.id) } }
            }
        }
        settings.setRepairVersion(CURRENT)
    }

    /**
     * Moves what a family member's forwards put on an "Other" account Kosh made for them onto their real account with
     * the same digits (see [SmsMapping.matchForwarded]), then removes the empty "Other" account.
     */
    private suspend fun mergeForwardedOther(db: KoshDatabase) {
        val accounts = db.accounts().all()
        val repo = AccountRepository(db)
        accounts.filter { it.memberId != null && it.autoCreated && it.bank == com.amitjha.kosh.model.Bank.OTHER.name }.forEach { other ->
            val theirs = accounts.filter { it.memberId == other.memberId && it.id != other.id }
            val isCard = other.type == com.amitjha.kosh.model.AccountType.CREDIT_CARD
            val target = SmsMapping.matchForwarded(theirs, com.amitjha.kosh.model.Bank.OTHER, other.last4, isCard)
                ?.takeIf { it.bank != com.amitjha.kosh.model.Bank.OTHER.name && other.last4 != null }
                ?: return@forEach
            repo.mergePending(other.id, target.id)
        }
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
