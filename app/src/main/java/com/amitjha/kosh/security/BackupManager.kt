package com.amitjha.kosh.security

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.InvestmentEntity
import com.amitjha.kosh.data.InvestmentSnapshotEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.MerchantRuleEntity
import com.amitjha.kosh.data.NetWorthSnapshotEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupStats(val accounts: Int, val transactions: Int, val bills: Int, val investments: Int)

/**
 * Encrypted export/import of everything in the database. Files are written and read through Storage Access
 * Framework URIs (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT), so the user picks where the backup lives.
 */
class BackupManager(private val context: Context, private val db: KoshDatabase) {

    suspend fun export(uri: Uri, password: CharArray): BackupStats = withContext(Dispatchers.IO) {
        val json = BackupJson.encode(snapshot())
        val bytes = BackupCrypto.encrypt(json.toByteArray(Charsets.UTF_8), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Can't write the backup file")
        statsOf(json)
    }

    /** Replaces all data with the backup's contents. Throws [BackupCrypto.WrongPasswordException] on a bad password. */
    suspend fun restore(uri: Uri, password: CharArray): BackupStats = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't read the backup file")
        val json = BackupCrypto.decrypt(bytes, password).toString(Charsets.UTF_8)
        val data = BackupJson.decode(json)
        // clearAllTables runs its own transaction, so it can't be nested inside withTransaction.
        db.clearAllTables()
        db.withTransaction {
            db.categories().insertAll(data.categories)
            data.accounts.forEach { db.accounts().insert(it) }
            data.transactions.forEach { db.transactions().insert(it) }
            data.bills.forEach { db.bills().insertIgnore(it) }
            data.budgets.forEach { db.budgets().upsert(it) }
            data.goals.forEach { db.goals().upsert(it) }
            data.investments.forEach { db.investments().insert(it) }
            data.snapshots.forEach { db.investments().upsertSnapshot(it) }
            data.rules.forEach { db.merchantRules().upsert(it) }
            data.netWorth.forEach { db.netWorth().upsert(it) }
        }
        statsOf(json)
    }

    private suspend fun snapshot() = BackupData(
        accounts = db.accounts().all(),
        categories = db.categories().all(),
        transactions = db.transactions().all(),
        bills = db.bills().all(),
        budgets = db.budgets().all(),
        goals = db.goals().all(),
        investments = db.investments().all(),
        snapshots = db.investments().allSnapshots(),
        rules = db.merchantRules().all(),
        netWorth = db.netWorth().all(),
    )

    private fun statsOf(json: String): BackupStats {
        val d = BackupJson.decode(json)
        return BackupStats(d.accounts.size, d.transactions.size, d.bills.size, d.investments.size)
    }
}

data class BackupData(
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val bills: List<CardBillEntity>,
    val budgets: List<BudgetEntity>,
    val goals: List<GoalEntity>,
    val investments: List<InvestmentEntity>,
    val snapshots: List<InvestmentSnapshotEntity>,
    val rules: List<MerchantRuleEntity>,
    val netWorth: List<NetWorthSnapshotEntity>,
)

/** JSON form of [BackupData]; nullable fields are omitted when null. */
object BackupJson {
    const val FORMAT_VERSION = 1

    fun encode(d: BackupData): String = JSONObject()
        .put("format", FORMAT_VERSION)
        .put("exportedAt", System.currentTimeMillis())
        .put("accounts", arr(d.accounts) { a ->
            JSONObject().put("id", a.id).put("name", a.name).put("type", a.type.name).put("bank", a.bank)
                .putOpt("last4", a.last4).put("openingBalance", a.openingBalance).put("openingDate", a.openingDate)
                .putOpt("reportedBalance", a.reportedBalance).putOpt("reportedAt", a.reportedAt)
                .putOpt("creditLimit", a.creditLimit).putOpt("statementDay", a.statementDay).putOpt("dueDay", a.dueDay)
                .putOpt("colorArgb", a.colorArgb).put("archived", a.archived).put("autoCreated", a.autoCreated)
        })
        .put("categories", arr(d.categories) { c ->
            JSONObject().put("id", c.id).put("key", c.key).put("name", c.name).put("type", c.type.name)
                .put("icon", c.icon).put("colorArgb", c.colorArgb).put("isDefault", c.isDefault)
        })
        .put("transactions", arr(d.transactions) { t ->
            JSONObject().put("id", t.id).put("accountId", t.accountId).putOpt("toAccountId", t.toAccountId)
                .put("type", t.type.name).put("amount", t.amount).put("categoryKey", t.categoryKey)
                .putOpt("merchant", t.merchant).putOpt("note", t.note).put("timestamp", t.timestamp)
                .put("channel", t.channel.name).put("source", t.source.name).putOpt("fingerprint", t.fingerprint)
                .putOpt("referenceNo", t.referenceNo).put("reviewed", t.reviewed).put("excludeFromStats", t.excludeFromStats)
        })
        .put("bills", arr(d.bills) { b ->
            JSONObject().put("id", b.id).put("accountId", b.accountId).put("totalDue", b.totalDue)
                .putOpt("minimumDue", b.minimumDue).putOpt("dueDate", b.dueDate).putOpt("statementDate", b.statementDate)
                .put("source", b.source.name).put("fingerprint", b.fingerprint).put("paid", b.paid)
                .putOpt("paidAt", b.paidAt).put("createdAt", b.createdAt)
        })
        .put("budgets", arr(d.budgets) { b ->
            JSONObject().put("id", b.id).putOpt("categoryKey", b.categoryKey).put("monthlyLimit", b.monthlyLimit)
        })
        .put("goals", arr(d.goals) { g ->
            JSONObject().put("id", g.id).put("name", g.name).put("target", g.target).put("saved", g.saved)
                .put("deadline", g.deadline).put("icon", g.icon).put("expectedReturnPct", g.expectedReturnPct)
                .put("createdAt", g.createdAt)
        })
        .put("investments", arr(d.investments) { i ->
            JSONObject().put("id", i.id).put("type", i.type.name).put("name", i.name).put("institution", i.institution)
                .put("specJson", i.specJson).put("investedAmount", i.investedAmount).put("currentValue", i.currentValue)
                .put("lastUpdated", i.lastUpdated).putOpt("maturityDate", i.maturityDate).put("archived", i.archived)
        })
        .put("snapshots", arr(d.snapshots) { s ->
            JSONObject().put("id", s.id).put("investmentId", s.investmentId).put("epochDay", s.epochDay).put("value", s.value)
        })
        .put("rules", arr(d.rules) { r -> JSONObject().put("id", r.id).put("merchant", r.merchant).put("categoryKey", r.categoryKey) })
        .put("netWorth", arr(d.netWorth) { n ->
            JSONObject().put("epochDay", n.epochDay).put("liquid", n.liquid).put("cardDebt", n.cardDebt)
                .put("investments", n.investments).put("total", n.total)
        })
        .toString()

    fun decode(json: String): BackupData {
        val o = JSONObject(json)
        require(o.optInt("format", 0) in 1..FORMAT_VERSION) { "Unsupported backup format" }
        return BackupData(
            accounts = list(o, "accounts") { a ->
                AccountEntity(
                    id = a.getLong("id"), name = a.getString("name"), type = AccountType.valueOf(a.getString("type")),
                    bank = a.getString("bank"), last4 = a.str("last4"), openingBalance = a.getLong("openingBalance"),
                    openingDate = a.getLong("openingDate"), reportedBalance = a.long("reportedBalance"), reportedAt = a.long("reportedAt"),
                    creditLimit = a.long("creditLimit"), statementDay = a.int("statementDay"), dueDay = a.int("dueDay"),
                    colorArgb = a.long("colorArgb"), archived = a.optBoolean("archived"), autoCreated = a.optBoolean("autoCreated"),
                )
            },
            categories = list(o, "categories") { c ->
                CategoryEntity(
                    id = c.getLong("id"), key = c.getString("key"), name = c.getString("name"), type = TxnType.valueOf(c.getString("type")),
                    icon = c.getString("icon"), colorArgb = c.getLong("colorArgb"), isDefault = c.optBoolean("isDefault", true),
                )
            },
            transactions = list(o, "transactions") { t ->
                TransactionEntity(
                    id = t.getLong("id"), accountId = t.getLong("accountId"), toAccountId = t.long("toAccountId"),
                    type = TxnType.valueOf(t.getString("type")), amount = t.getLong("amount"), categoryKey = t.getString("categoryKey"),
                    merchant = t.str("merchant"), note = t.str("note"), timestamp = t.getLong("timestamp"),
                    channel = TxnChannel.valueOf(t.optString("channel", TxnChannel.OTHER.name)),
                    source = TxnSource.valueOf(t.optString("source", TxnSource.MANUAL.name)), fingerprint = t.str("fingerprint"),
                    referenceNo = t.str("referenceNo"), reviewed = t.optBoolean("reviewed", true),
                    excludeFromStats = t.optBoolean("excludeFromStats"),
                )
            },
            bills = list(o, "bills") { b ->
                CardBillEntity(
                    id = b.getLong("id"), accountId = b.getLong("accountId"), totalDue = b.getLong("totalDue"),
                    minimumDue = b.long("minimumDue"), dueDate = b.long("dueDate"), statementDate = b.long("statementDate"),
                    source = TxnSource.valueOf(b.getString("source")), fingerprint = b.getString("fingerprint"),
                    paid = b.optBoolean("paid"), paidAt = b.long("paidAt"), createdAt = b.optLong("createdAt"),
                )
            },
            budgets = list(o, "budgets") { b -> BudgetEntity(b.getLong("id"), b.str("categoryKey"), b.getLong("monthlyLimit")) },
            goals = list(o, "goals") { g ->
                GoalEntity(
                    id = g.getLong("id"), name = g.getString("name"), target = g.getLong("target"), saved = g.getLong("saved"),
                    deadline = g.getLong("deadline"), icon = g.optString("icon", "flag"),
                    expectedReturnPct = g.optDouble("expectedReturnPct", 7.0), createdAt = g.optLong("createdAt"),
                )
            },
            investments = list(o, "investments") { i ->
                InvestmentEntity(
                    id = i.getLong("id"), type = InvestmentType.valueOf(i.getString("type")), name = i.getString("name"),
                    institution = i.getString("institution"), specJson = i.getString("specJson"),
                    investedAmount = i.getLong("investedAmount"), currentValue = i.getLong("currentValue"),
                    lastUpdated = i.getLong("lastUpdated"), maturityDate = i.long("maturityDate"), archived = i.optBoolean("archived"),
                )
            },
            snapshots = list(o, "snapshots") { s ->
                InvestmentSnapshotEntity(s.getLong("id"), s.getLong("investmentId"), s.getLong("epochDay"), s.getLong("value"))
            },
            rules = list(o, "rules") { r -> MerchantRuleEntity(r.getLong("id"), r.getString("merchant"), r.getString("categoryKey")) },
            netWorth = list(o, "netWorth") { n ->
                NetWorthSnapshotEntity(n.getLong("epochDay"), n.getLong("liquid"), n.getLong("cardDebt"), n.getLong("investments"), n.getLong("total"))
            },
        )
    }

    private fun <T> arr(items: List<T>, map: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(map(it)) } }

    private fun <T> list(o: JSONObject, key: String, map: (JSONObject) -> T): List<T> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { map(a.getJSONObject(it)) }
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
}
