package com.amitjha.kosh.repo

import androidx.room.withTransaction
import com.amitjha.kosh.data.DeletedFingerprintEntity
import com.amitjha.kosh.data.FamilyMemberEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.MemberSpend
import com.amitjha.kosh.sms.FamilyForward
import kotlinx.coroutines.flow.Flow

/**
 * Family members whose bank SMS reach Kosh by being forwarded from their phones (see [FamilyForward]): adding them,
 * linking their number, whether their money counts in the user's totals, and removing them.
 */
class FamilyRepository(private val db: KoshDatabase) {

    private val dao get() = db.family()

    fun observeMembers(): Flow<List<FamilyMemberEntity>> = dao.observeAll()

    fun observeSpending(from: Long, to: Long): Flow<List<MemberSpend>> = dao.observeSpending(from, to)

    suspend fun members(): List<FamilyMemberEntity> = dao.all()

    /** A new member waiting for their link message. */
    suspend fun add(name: String): FamilyMemberEntity {
        val member = FamilyMemberEntity(name = name.trim().take(MAX_NAME), linkCode = FamilyForward.newCode())
        return member.copy(id = dao.insert(member))
    }

    suspend fun rename(id: Long, name: String) {
        val member = dao.byId(id) ?: return
        val clean = name.trim().take(MAX_NAME).ifEmpty { return }
        db.withTransaction {
            dao.update(member.copy(name = clean))
            // Accounts Kosh named after them follow the new name.
            db.accounts().ofMember(id).filter { it.name.startsWith("${member.name} · ") }.forEach {
                db.accounts().update(it.copy(name = clean + it.name.removePrefix(member.name)))
            }
        }
    }

    /** Links [phone] typed in by the user, for a phone that can't scan the QR code. False when it isn't a mobile number. */
    suspend fun linkNumber(id: Long, phone: String): Boolean {
        val key = FamilyForward.phoneKey(phone) ?: return false
        val member = dao.byId(id) ?: return false
        db.withTransaction {
            dao.releasePhone(key, id)
            dao.update(member.copy(phone = key, linkedAt = System.currentTimeMillis()))
        }
        return true
    }

    /**
     * Stops reading their forwards. The code changes too, so their old link message found by a later inbox scan
     * doesn't link the phone again; their accounts and history stay.
     */
    suspend fun unlink(id: Long) {
        val member = dao.byId(id) ?: return
        dao.update(member.copy(phone = null, linkedAt = null, linkCode = FamilyForward.newCode()))
    }

    /**
     * Whether their spending, income and balances count in the user's totals. Their stored transactions follow:
     * counted again (transfers aside) or left out, as for an ignored account.
     */
    suspend fun setCountInTotals(id: Long, count: Boolean) {
        val member = dao.byId(id) ?: return
        if (member.countInTotals == count) return
        db.withTransaction {
            dao.update(member.copy(countInTotals = count))
            val theirs = db.accounts().ofMember(id)
            theirs.forEach { a ->
                if (count && !a.archived) db.transactions().includeAccountInStats(a.id) else db.transactions().excludeAccountFromStats(a.id)
            }
            // Kept apart again: what the user sent them is spending once more, not a transfer within the family.
            if (!count && theirs.isNotEmpty()) {
                val undone = com.amitjha.kosh.domain.TransferPairing.unpairFromMember(
                    db.transactions().all(), theirs.map { it.id }.toSet(), db.accounts().all(),
                )
                undone.forEach { db.transactions().update(it) }
            }
        }
        // Money between the user and a member who now counts can be a transfer within the family.
        if (count) runCatching { TransferPairer.run(db) }
    }

    /** Gives an account to [memberId] (null: the user's own). Its transactions count as the new owner's do. */
    suspend fun moveAccount(accountId: Long, memberId: Long?) {
        db.withTransaction {
            val account = db.accounts().byId(accountId) ?: return@withTransaction
            if (account.memberId == memberId) return@withTransaction
            db.accounts().update(account.copy(memberId = memberId))
            val apart = memberId?.let { dao.byId(it) }?.countInTotals == false
            if (apart) {
                db.transactions().excludeAccountFromStats(accountId)
            } else if (!account.archived) {
                db.transactions().includeAccountInStats(accountId)
            }
        }
    }

    /**
     * Removes the member with their accounts and transactions. Their imported messages are remembered as deleted, so
     * an inbox scan doesn't bring them back.
     */
    suspend fun remove(id: Long) {
        db.withTransaction {
            val accounts = db.accounts().ofMember(id)
            val fingerprints = accounts.flatMap { db.transactions().fingerprintsOf(it.id) }
            db.tombstones().insertAll(fingerprints.map { DeletedFingerprintEntity(it) })
            accounts.forEach { db.accounts().delete(it) }
            dao.delete(id)
        }
    }

    companion object {
        const val MAX_NAME = 24
    }
}
