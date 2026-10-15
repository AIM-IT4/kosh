package com.amitjha.kosh.repo

import androidx.room.withTransaction
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.domain.TransferPairing

/** Applies [TransferPairing] to the stored transactions; run after imports and once on upgrade. */
object TransferPairer {
    /** Returns how many self-transfers were found. */
    suspend fun run(db: KoshDatabase): Int {
        val accounts = db.accounts().all()
        val pairs = TransferPairing.findPairs(db.transactions().all(), accounts)
        if (pairs.isEmpty()) return 0
        db.withTransaction {
            for (pair in pairs) {
                val (debit, credit) = TransferPairing.asTransfer(pair, accounts)
                db.transactions().update(debit)
                db.transactions().update(credit)
            }
        }
        return pairs.size
    }
}
