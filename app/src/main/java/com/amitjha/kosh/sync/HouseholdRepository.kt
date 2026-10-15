package com.amitjha.kosh.sync

import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.HouseholdItemEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

/** What is shared in the household space: transactions, budgets and goals, each owned by one phone. */
enum class HouseholdKind { TXN, BUDGET, GOAL }

data class HouseholdTxn(
    val amount: Paise,
    val type: TxnType,
    val categoryKey: String,
    val categoryName: String,
    val merchant: String?,
    val note: String?,
    val timestamp: Long,
    val ownerName: String,
    val mine: Boolean,
)

data class HouseholdBudget(val categoryKey: String?, val categoryName: String, val monthlyLimit: Paise, val ownerName: String, val mine: Boolean)

data class HouseholdGoal(val name: String, val target: Paise, val saved: Paise, val deadlineEpochDay: Long, val ownerName: String, val mine: Boolean)

data class HouseholdData(val txns: List<HouseholdTxn>, val budgets: List<HouseholdBudget>, val goals: List<HouseholdGoal>)

/** Payload JSON for each kind. Only the fields needed to show the record are shared (no account names). */
object HouseholdCodec {
    fun txn(t: TransactionEntity, categoryName: String): String = JSONObject()
        .put("amount", t.amount).put("type", t.type.name).put("category", t.categoryKey)
        .put("categoryName", categoryName).put("merchant", t.merchant ?: JSONObject.NULL)
        .put("note", t.note ?: JSONObject.NULL).put("timestamp", t.timestamp)
        .toString()

    fun budget(b: BudgetEntity, categoryName: String): String = JSONObject()
        .put("category", b.categoryKey ?: JSONObject.NULL).put("categoryName", categoryName)
        .put("monthlyLimit", b.monthlyLimit)
        .toString()

    fun goal(g: GoalEntity): String = JSONObject()
        .put("name", g.name).put("target", g.target).put("saved", g.saved)
        .put("deadline", g.deadline).put("expectedReturnPct", g.expectedReturnPct)
        .toString()

    fun decode(items: List<HouseholdItemEntity>, myId: String): HouseholdData {
        val txns = mutableListOf<HouseholdTxn>()
        val budgets = mutableListOf<HouseholdBudget>()
        val goals = mutableListOf<HouseholdGoal>()
        for (i in items) {
            if (i.deleted) continue
            val o = runCatching { JSONObject(i.payload) }.getOrNull() ?: continue
            val mine = i.ownerId == myId
            when (i.kind) {
                HouseholdKind.TXN.name -> txns += HouseholdTxn(
                    amount = o.optLong("amount"),
                    type = runCatching { TxnType.valueOf(o.optString("type")) }.getOrDefault(TxnType.EXPENSE),
                    categoryKey = o.optString("category", "other"),
                    categoryName = o.optString("categoryName", "Other"),
                    merchant = o.optString("merchant").takeIf { !o.isNull("merchant") && it.isNotBlank() },
                    note = o.optString("note").takeIf { !o.isNull("note") && it.isNotBlank() },
                    timestamp = o.optLong("timestamp"),
                    ownerName = i.ownerName,
                    mine = mine,
                )
                HouseholdKind.BUDGET.name -> budgets += HouseholdBudget(
                    categoryKey = o.optString("category").takeIf { !o.isNull("category") },
                    categoryName = o.optString("categoryName", "Overall"),
                    monthlyLimit = o.optLong("monthlyLimit"),
                    ownerName = i.ownerName,
                    mine = mine,
                )
                HouseholdKind.GOAL.name -> goals += HouseholdGoal(
                    name = o.optString("name"),
                    target = o.optLong("target"),
                    saved = o.optLong("saved"),
                    deadlineEpochDay = o.optLong("deadline"),
                    ownerName = i.ownerName,
                    mine = mine,
                )
            }
        }
        return HouseholdData(txns.sortedByDescending { it.timestamp }, budgets, goals)
    }
}

/**
 * The household space on this phone: records the user marked as household (owned here) plus records
 * received from the paired phone.
 */
class HouseholdRepository(private val db: KoshDatabase, private val store: HouseholdStore) {
    private val dao get() = db.household()

    fun observeSharedIds(kind: HouseholdKind): Flow<Set<Long>> = dao.observeSharedIds(kind.name).map { it.toSet() }

    fun observeData(): Flow<HouseholdData> = dao.observeLive().map { HouseholdCodec.decode(it, store.me.id) }

    /** Shares a local record with the household, or stops sharing it (which removes it on the partner's phone). */
    suspend fun setShared(kind: HouseholdKind, localId: Long, shared: Boolean) {
        val existing = dao.ownFor(kind.name, localId)
        val me = store.me
        val now = System.currentTimeMillis()
        if (shared) {
            val payload = payloadFor(kind, localId) ?: return
            dao.upsert(
                HouseholdItemEntity(
                    uuid = existing?.uuid ?: UUID.randomUUID().toString(),
                    kind = kind.name, ownerId = me.id, ownerName = me.name, localId = localId,
                    payload = payload, updatedAt = maxOf(now, (existing?.updatedAt ?: 0) + 1), deleted = false,
                ),
            )
        } else if (existing != null && !existing.deleted) {
            dao.upsert(existing.copy(payload = "", deleted = true, updatedAt = maxOf(now, existing.updatedAt + 1)))
        }
    }

    /**
     * Brings own shared records up to date with their local source before a sync: edited records get a new
     * payload and timestamp, deleted ones become tombstones, and a device rename updates the owner name.
     */
    suspend fun refreshOwn() {
        val me = store.me
        val now = System.currentTimeMillis()
        for (item in dao.all().filter { it.localId != null && it.ownerId == me.id && !it.deleted }) {
            val kind = runCatching { HouseholdKind.valueOf(item.kind) }.getOrNull() ?: continue
            val payload = payloadFor(kind, item.localId!!)
            val next = when {
                payload == null -> item.copy(payload = "", deleted = true, updatedAt = maxOf(now, item.updatedAt + 1))
                payload != item.payload || item.ownerName != me.name ->
                    item.copy(payload = payload, ownerName = me.name, updatedAt = maxOf(now, item.updatedAt + 1))
                else -> continue
            }
            dao.upsert(next)
        }
    }

    suspend fun ownItems(): List<SyncItem> = dao.all().filter { it.ownerId == store.me.id }.map { it.toSync() }

    suspend fun allItems(): List<SyncItem> = dao.all().map { it.toSync() }

    /** Writes the partner's records produced by [HouseholdMerge.merge]. */
    suspend fun apply(items: List<SyncItem>) {
        if (items.isEmpty()) return
        dao.upsertAll(
            items.map {
                HouseholdItemEntity(it.uuid, it.kind, it.ownerId, it.ownerName, null, it.payload, it.updatedAt, it.deleted)
            },
        )
    }

    /** Forgets everything received from the partner (own shared records stay, ready for a new pairing). */
    suspend fun clearReceived() = dao.deleteReceived()

    private suspend fun payloadFor(kind: HouseholdKind, localId: Long): String? {
        val names = db.categories().all().associate { it.key to it.name }
        return when (kind) {
            HouseholdKind.TXN -> db.transactions().byId(localId)?.let { HouseholdCodec.txn(it, names[it.categoryKey] ?: it.categoryKey) }
            HouseholdKind.BUDGET -> db.budgets().all().firstOrNull { it.id == localId }
                ?.let { HouseholdCodec.budget(it, it.categoryKey?.let { k -> names[k] ?: k } ?: "Overall") }
            HouseholdKind.GOAL -> db.goals().all().firstOrNull { it.id == localId }?.let { HouseholdCodec.goal(it) }
        }
    }

    private fun HouseholdItemEntity.toSync() = SyncItem(uuid, kind, ownerId, ownerName, payload, updatedAt, deleted)
}
