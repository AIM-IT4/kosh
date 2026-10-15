package com.amitjha.kosh.sync

import org.json.JSONArray
import org.json.JSONObject

/** A household record as exchanged over the wire. */
data class SyncItem(
    val uuid: String,
    val kind: String,
    val ownerId: String,
    val ownerName: String,
    val payload: String,
    val updatedAt: Long,
    val deleted: Boolean,
)

data class MergeResult(
    /** Items to write locally (new or newer versions of the partner's records). */
    val upserts: List<SyncItem>,
    /** Incoming items refused (wrong owner, too large, malformed kind). */
    val rejected: Int,
)

object HouseholdMerge {
    val KINDS = setOf("TXN", "BUDGET", "GOAL")
    const val MAX_ITEMS = 20_000
    const val MAX_PAYLOAD = 16 * 1024

    /**
     * Merges [incoming] items from the paired device [peerId] into [local].
     *
     * - Every record has one owner (the phone that shared it); only the owner can change it. Incoming
     *   items must be owned by the peer, so a partner (or an attacker with the pair key) can't overwrite
     *   records this phone owns or inject records claiming a third owner.
     * - Last writer wins on `updatedAt`; on a tie the deleted version wins, then the lexically larger payload,
     *   so both phones always converge to the same state.
     * - Deletions travel as tombstones. Applying the same batch twice changes nothing (idempotent).
     */
    fun merge(local: List<SyncItem>, incoming: List<SyncItem>, peerId: String): MergeResult {
        val byUuid = local.associateBy { it.uuid }
        val upserts = LinkedHashMap<String, SyncItem>()
        var rejected = 0
        for (item in incoming.take(MAX_ITEMS)) {
            if (item.ownerId != peerId || item.kind !in KINDS || item.payload.length > MAX_PAYLOAD || item.uuid.isBlank()) {
                rejected++
                continue
            }
            val current = upserts[item.uuid] ?: byUuid[item.uuid]
            if (current != null && current.ownerId != peerId) {
                rejected++
                continue
            }
            if (current == null || wins(item, current)) upserts[item.uuid] = item
        }
        rejected += (incoming.size - MAX_ITEMS).coerceAtLeast(0)
        return MergeResult(upserts.values.toList(), rejected)
    }

    fun wins(a: SyncItem, b: SyncItem): Boolean = when {
        a.updatedAt != b.updatedAt -> a.updatedAt > b.updatedAt
        a.deleted != b.deleted -> a.deleted
        else -> a.payload > b.payload
    }

    fun encode(items: List<SyncItem>): String = JSONObject()
        .put("type", "items")
        .put("items", JSONArray().apply {
            items.forEach {
                put(JSONObject().put("uuid", it.uuid).put("kind", it.kind).put("owner", it.ownerId)
                    .put("ownerName", it.ownerName).put("payload", it.payload)
                    .put("updatedAt", it.updatedAt).put("deleted", it.deleted))
            }
        })
        .toString()

    fun decode(text: String): List<SyncItem> {
        val root = JSONObject(text)
        if (root.optString("type") != "items") throw SyncProtocolException("unexpected message")
        val arr = root.getJSONArray("items")
        return (0 until minOf(arr.length(), MAX_ITEMS + 1)).map { i ->
            val o = arr.getJSONObject(i)
            SyncItem(
                uuid = o.getString("uuid"),
                kind = o.getString("kind"),
                ownerId = o.getString("owner"),
                ownerName = o.optString("ownerName").take(64),
                payload = o.optString("payload"),
                updatedAt = o.getLong("updatedAt"),
                deleted = o.optBoolean("deleted"),
            )
        }
    }
}

/**
 * The exchange after a successful handshake: the client sends all its household items, the server merges
 * them and answers with all of its own, then the client merges those.
 */
object SyncExchange {
    data class Outcome(val sent: Int, val received: Int, val rejected: Int, val merged: List<SyncItem>)

    fun asClient(channel: SecureChannel, mine: List<SyncItem>, local: List<SyncItem>, peerId: String): Outcome {
        channel.send(HouseholdMerge.encode(mine).toByteArray())
        val theirs = HouseholdMerge.decode(String(channel.receive()))
        val result = HouseholdMerge.merge(local, theirs, peerId)
        return Outcome(mine.size, result.upserts.size, result.rejected, result.upserts)
    }

    fun asServer(channel: SecureChannel, mine: List<SyncItem>, local: List<SyncItem>, peerId: String): Outcome {
        val theirs = HouseholdMerge.decode(String(channel.receive()))
        val result = HouseholdMerge.merge(local, theirs, peerId)
        channel.send(HouseholdMerge.encode(mine).toByteArray())
        return Outcome(mine.size, result.upserts.size, result.rejected, result.upserts)
    }
}
