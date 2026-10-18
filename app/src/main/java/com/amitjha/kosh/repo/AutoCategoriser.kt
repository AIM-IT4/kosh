package com.amitjha.kosh.repo

import androidx.room.withTransaction
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.domain.SmsMapping

/** Gives uncategorised imported transactions a real category; see [SmsMapping.recategorised]. */
object AutoCategoriser {
    /** Returns how many transactions changed category. */
    suspend fun run(db: KoshDatabase): Int {
        val rules = db.merchantRules().all().associate { it.merchant to it.categoryKey }
        val updates = db.transactions().recategoriseCandidates().mapNotNull { SmsMapping.recategorised(it, rules) }
        if (updates.isEmpty()) return 0
        // One transaction, so screens recompute once rather than once per row.
        db.withTransaction { updates.forEach { db.transactions().update(it) } }
        return updates.size
    }
}
