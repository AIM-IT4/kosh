package com.amitjha.kosh.repo

import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.domain.SmsMapping

/** Gives uncategorised imported transactions a real category; see [SmsMapping.recategorised]. */
object AutoCategoriser {
    /** Returns how many transactions changed category. */
    suspend fun run(db: KoshDatabase): Int {
        val rules = db.merchantRules().all().associate { it.merchant to it.categoryKey }
        var changed = 0
        for (txn in db.transactions().recategoriseCandidates()) {
            val updated = SmsMapping.recategorised(txn, rules) ?: continue
            db.transactions().update(updated)
            changed++
        }
        return changed
    }
}
