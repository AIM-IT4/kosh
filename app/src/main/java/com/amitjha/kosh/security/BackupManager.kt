package com.amitjha.kosh.security

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.AttachmentEntity
import com.amitjha.kosh.data.AttachmentStore
import com.amitjha.kosh.data.DebtEntity
import com.amitjha.kosh.data.LoanEntity
import com.amitjha.kosh.data.RecurringRuleEntity
import com.amitjha.kosh.data.SplitEntity
import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.FamilyMemberEntity
import com.amitjha.kosh.data.DeletedFingerprintEntity
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupStats(val accounts: Int, val transactions: Int, val bills: Int, val investments: Int)

/** What [BackupManager.restore] restored, plus the file's metadata the caller uses to finish the restore. */
data class RestoreResult(val stats: BackupStats, val meta: BackupMeta)

/** Header fields of a backup file. Files from format 1 have no [repairVersion] (null). */
data class BackupMeta(val format: Int, val exportedAt: Long?, val repairVersion: Int?)

/**
 * Encrypted export/import of everything in the database. Files are written and read through Storage Access
 * Framework URIs (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT), so the user picks where the backup lives.
 */
class BackupManager(private val context: Context, private val db: KoshDatabase, private val files: AttachmentStore) {

    suspend fun export(uri: Uri, password: CharArray, repairVersion: Int? = null): BackupStats = withContext(Dispatchers.IO) {
        val (bytes, stats) = encrypted(password, repairVersion)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Can't write the backup file")
        stats
    }

    /** The whole backup file, encrypted with [password], and what it holds. */
    suspend fun encrypted(password: CharArray, repairVersion: Int? = null): Pair<ByteArray, BackupStats> = withContext(Dispatchers.IO) {
        val data = snapshot()
        val json = BackupJson.encode(data, repairVersion = repairVersion)
        BackupCrypto.encrypt(json.toByteArray(Charsets.UTF_8), password) to statsOf(data)
    }

    /**
     * Replaces all data with the backup's contents. Throws [BackupCrypto.WrongPasswordException] on a bad password.
     *
     * The file is fully read, decrypted and parsed before anything is touched. The delete and the re-insert then
     * run in one transaction that is not cancelled with the caller, so leaving the screen, rotating the phone or an
     * SMS arriving mid-restore can't leave the database empty or half-restored.
     */
    suspend fun restore(uri: Uri, password: CharArray): RestoreResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't read the backup file")
        val json = BackupCrypto.decrypt(bytes, password).toString(Charsets.UTF_8)
        val data = BackupJson.decode(json)
        val meta = BackupJson.meta(json)
        withContext(NonCancellable) {
            db.withTransaction {
                val r = db.restore()
                r.clearAttachments(); r.clearSplits(); r.clearRecurring(); r.clearDebts(); r.clearLoans(); r.clearFamilyMembers()
                r.clearTransactions(); r.clearBills(); r.clearSnapshots()
                r.clearAccounts(); r.clearInvestments(); r.clearCategories(); r.clearBudgets()
                r.clearGoals(); r.clearRules(); r.clearNetWorth(); r.clearTombstones()
                r.insertCategories(data.categories)
                r.insertFamilyMembers(data.familyMembers)
                r.insertAccounts(data.accounts)
                r.insertTransactions(data.transactions)
                r.insertBills(data.bills)
                r.insertBudgets(data.budgets)
                r.insertGoals(data.goals)
                r.insertInvestments(data.investments)
                r.insertSnapshots(data.snapshots)
                r.insertRules(data.rules)
                r.insertNetWorth(data.netWorth)
                r.insertTombstones(data.tombstones)
                r.insertSplits(data.splits)
                r.insertDebts(data.debts)
                r.insertRecurring(data.recurring)
                r.insertLoans(data.loans)
                r.insertAttachments(data.attachments)
            }
            // Files follow the committed rows: the old ones go, the backup's are written back encrypted.
            files.deleteAll()
            data.attachmentFiles.forEach { (name, bytes) ->
                val mime = data.attachments.firstOrNull { it.fileName == name }?.mimeType ?: AttachmentStore.JPEG
                runCatching { files.saveBytes(bytes, mime, name) }
            }
        }
        RestoreResult(statsOf(data), meta)
    }

    /** Reads every table in one transaction, so an SMS imported mid-export can't produce an inconsistent file. */
    private suspend fun snapshot(): BackupData = db.withTransaction {
        val r = db.restore()
        BackupData(
            accounts = r.accounts(),
            categories = r.categories(),
            transactions = r.transactions(),
            bills = r.bills(),
            budgets = r.budgets(),
            goals = r.goals(),
            investments = r.investments(),
            snapshots = r.snapshots(),
            rules = r.rules(),
            netWorth = r.netWorth(),
            tombstones = r.tombstones(),
            splits = r.splits(),
            debts = r.debts(),
            recurring = r.recurring(),
            loans = r.loans(),
            attachments = r.attachments(),
            familyMembers = r.familyMembers(),
        ).let { d -> d.copy(attachmentFiles = d.attachments.mapNotNull { a -> runCatching { a.fileName to files.read(a.fileName) }.getOrNull() }.toMap()) }
    }

    private fun statsOf(d: BackupData) = BackupStats(d.accounts.size, d.transactions.size, d.bills.size, d.investments.size)
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
    val tombstones: List<DeletedFingerprintEntity> = emptyList(),
    val splits: List<SplitEntity> = emptyList(),
    val debts: List<DebtEntity> = emptyList(),
    val recurring: List<RecurringRuleEntity> = emptyList(),
    val loans: List<LoanEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val familyMembers: List<FamilyMemberEntity> = emptyList(),
    /** Decrypted receipt files by [AttachmentEntity.fileName]. */
    val attachmentFiles: Map<String, ByteArray> = emptyMap(),
)

/** JSON form of [BackupData]; nullable fields are omitted when null. */
object BackupJson {
    /**
     * 1: original format. 2: adds goal loans, per-account savings interest settings, deleted-import fingerprints
     * and the data-repair version. 3: adds tags, split parts, budget rollover, money lent and borrowed, recurring
     * rules, loans and receipts (with their files). 4: adds family members and which of them owns an account. Older
     * files still restore; missing fields take the migrations' defaults.
     */
    const val FORMAT_VERSION = 4

    fun meta(json: String): BackupMeta {
        val o = JSONObject(json)
        return BackupMeta(
            format = o.optInt("format", 0),
            exportedAt = if (o.has("exportedAt")) o.optLong("exportedAt") else null,
            repairVersion = if (o.has("repairVersion")) o.optInt("repairVersion") else null,
        )
    }

    fun encode(d: BackupData, exportedAt: Long = System.currentTimeMillis(), repairVersion: Int? = null): String = JSONObject()
        .put("format", FORMAT_VERSION)
        .put("exportedAt", exportedAt)
        .putOpt("repairVersion", repairVersion)
        .put("accounts", arr(d.accounts) { a ->
            JSONObject().put("id", a.id).put("name", a.name).put("type", a.type.name).put("bank", a.bank)
                .putOpt("last4", a.last4).put("openingBalance", a.openingBalance).put("openingDate", a.openingDate)
                .putOpt("reportedBalance", a.reportedBalance).putOpt("reportedAt", a.reportedAt)
                .putOpt("creditLimit", a.creditLimit).putOpt("statementDay", a.statementDay).putOpt("dueDay", a.dueDay)
                .putOpt("colorArgb", a.colorArgb).put("archived", a.archived).put("autoCreated", a.autoCreated)
                .putOpt("interestRatePct", a.interestRatePct).put("interestCreditFrequency", a.interestCreditFrequency)
                .putOpt("otherDigits", a.otherDigits).putOpt("memberId", a.memberId)
        })
        .put("familyMembers", arr(d.familyMembers) { m ->
            JSONObject().put("id", m.id).put("name", m.name).putOpt("phone", m.phone).put("linkCode", m.linkCode)
                .putOpt("linkedAt", m.linkedAt).put("countInTotals", m.countInTotals).put("createdAt", m.createdAt)
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
                .putOpt("referenceNo", t.referenceNo).put("reviewed", t.reviewed).put("excludeFromStats", t.excludeFromStats).put("suspicious", t.suspicious)
                .put("tags", t.tags)
        })
        .put("bills", arr(d.bills) { b ->
            JSONObject().put("id", b.id).put("accountId", b.accountId).put("totalDue", b.totalDue)
                .putOpt("minimumDue", b.minimumDue).putOpt("dueDate", b.dueDate).putOpt("statementDate", b.statementDate)
                .put("source", b.source.name).put("fingerprint", b.fingerprint).put("paid", b.paid)
                .putOpt("paidAt", b.paidAt).put("createdAt", b.createdAt)
        })
        .put("budgets", arr(d.budgets) { b ->
            JSONObject().put("id", b.id).putOpt("categoryKey", b.categoryKey).put("monthlyLimit", b.monthlyLimit).put("rollover", b.rollover)
        })
        .put("goals", arr(d.goals) { g ->
            JSONObject().put("id", g.id).put("name", g.name).put("target", g.target).put("saved", g.saved)
                .put("deadline", g.deadline).put("icon", g.icon).put("expectedReturnPct", g.expectedReturnPct)
                .put("createdAt", g.createdAt).put("loanAmount", g.loanAmount).put("loanRatePct", g.loanRatePct)
                .put("loanTenureMonths", g.loanTenureMonths)
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
        .put("deletedFingerprints", arr(d.tombstones) { t -> JSONObject().put("fingerprint", t.fingerprint).put("deletedAt", t.deletedAt) })
        .put("splits", arr(d.splits) { p ->
            JSONObject().put("id", p.id).put("transactionId", p.transactionId).put("categoryKey", p.categoryKey)
                .put("amount", p.amount).putOpt("note", p.note)
        })
        .put("debts", arr(d.debts) { x ->
            JSONObject().put("id", x.id).put("person", x.person).put("direction", x.direction).put("amount", x.amount)
                .put("repaid", x.repaid).put("date", x.date).putOpt("dueDate", x.dueDate).putOpt("note", x.note)
                .put("closed", x.closed).put("createdAt", x.createdAt)
        })
        .put("recurring", arr(d.recurring) { x ->
            JSONObject().put("id", x.id).put("name", x.name).put("type", x.type.name).put("amount", x.amount)
                .put("categoryKey", x.categoryKey).put("accountId", x.accountId).putOpt("toAccountId", x.toAccountId)
                .put("cadence", x.cadence).put("anchorDay", x.anchorDay).put("nextDay", x.nextDay).put("active", x.active)
                .put("autoAdd", x.autoAdd).putOpt("note", x.note).put("createdAt", x.createdAt)
        })
        .put("loans", arr(d.loans) { x ->
            JSONObject().put("id", x.id).put("name", x.name).put("lender", x.lender).put("kind", x.kind)
                .put("principal", x.principal).put("ratePct", x.ratePct).put("tenureMonths", x.tenureMonths)
                .put("firstEmiDay", x.firstEmiDay).putOpt("emi", x.emi).put("prepaymentsJson", x.prepaymentsJson)
                .put("selfOccupied", x.selfOccupied).put("archived", x.archived).put("createdAt", x.createdAt)
        })
        .put("attachments", arr(d.attachments) { x ->
            JSONObject().put("id", x.id).put("transactionId", x.transactionId).put("fileName", x.fileName)
                .put("mimeType", x.mimeType).put("sizeBytes", x.sizeBytes).putOpt("label", x.label)
                .putOpt("warrantyUntil", x.warrantyUntil).put("createdAt", x.createdAt)
        })
        .put("attachmentFiles", JSONObject().apply {
            d.attachmentFiles.forEach { (name, bytes) -> put(name, java.util.Base64.getEncoder().encodeToString(bytes)) }
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
                    interestRatePct = a.double("interestRatePct"),
                    interestCreditFrequency = a.optString("interestCreditFrequency", "QUARTERLY"),
                    otherDigits = a.str("otherDigits"),
                    memberId = a.long("memberId"),
                )
            },
            familyMembers = list(o, "familyMembers") { m ->
                FamilyMemberEntity(
                    id = m.getLong("id"), name = m.getString("name"), phone = m.str("phone"), linkCode = m.getString("linkCode"),
                    linkedAt = m.long("linkedAt"), countInTotals = m.optBoolean("countInTotals", true), createdAt = m.optLong("createdAt"),
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
                    suspicious = t.optBoolean("suspicious"),
                    tags = t.optString("tags", ""),
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
            budgets = list(o, "budgets") { b ->
                BudgetEntity(b.getLong("id"), b.str("categoryKey"), b.getLong("monthlyLimit"), rollover = b.optBoolean("rollover"))
            },
            goals = list(o, "goals") { g ->
                GoalEntity(
                    id = g.getLong("id"), name = g.getString("name"), target = g.getLong("target"), saved = g.getLong("saved"),
                    deadline = g.getLong("deadline"), icon = g.optString("icon", "flag"),
                    expectedReturnPct = g.optDouble("expectedReturnPct", 7.0), createdAt = g.optLong("createdAt"),
                    loanAmount = g.optLong("loanAmount", 0), loanRatePct = g.optDouble("loanRatePct", 9.0),
                    loanTenureMonths = g.optInt("loanTenureMonths", 36),
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
            tombstones = list(o, "deletedFingerprints") { t ->
                DeletedFingerprintEntity(t.getString("fingerprint"), t.optLong("deletedAt"))
            },
            splits = list(o, "splits") { p ->
                SplitEntity(p.getLong("id"), p.getLong("transactionId"), p.getString("categoryKey"), p.getLong("amount"), p.str("note"))
            },
            debts = list(o, "debts") { x ->
                DebtEntity(
                    id = x.getLong("id"), person = x.getString("person"), direction = x.getString("direction"), amount = x.getLong("amount"),
                    repaid = x.optLong("repaid"), date = x.getLong("date"), dueDate = x.long("dueDate"), note = x.str("note"),
                    closed = x.optBoolean("closed"), createdAt = x.optLong("createdAt"),
                )
            },
            recurring = list(o, "recurring") { x ->
                RecurringRuleEntity(
                    id = x.getLong("id"), name = x.getString("name"), type = TxnType.valueOf(x.getString("type")), amount = x.getLong("amount"),
                    categoryKey = x.getString("categoryKey"), accountId = x.getLong("accountId"), toAccountId = x.long("toAccountId"),
                    cadence = x.getString("cadence"), anchorDay = x.getLong("anchorDay"), nextDay = x.getLong("nextDay"),
                    active = x.optBoolean("active", true), autoAdd = x.optBoolean("autoAdd", true), note = x.str("note"),
                    createdAt = x.optLong("createdAt"),
                )
            },
            loans = list(o, "loans") { x ->
                LoanEntity(
                    id = x.getLong("id"), name = x.getString("name"), lender = x.optString("lender", ""), kind = x.optString("kind", "OTHER"),
                    principal = x.getLong("principal"), ratePct = x.getDouble("ratePct"), tenureMonths = x.getInt("tenureMonths"),
                    firstEmiDay = x.getLong("firstEmiDay"), emi = x.long("emi"), prepaymentsJson = x.optString("prepaymentsJson", "[]"),
                    selfOccupied = x.optBoolean("selfOccupied", true), archived = x.optBoolean("archived"), createdAt = x.optLong("createdAt"),
                )
            },
            attachments = list(o, "attachments") { x ->
                AttachmentEntity(
                    id = x.getLong("id"), transactionId = x.getLong("transactionId"), fileName = x.getString("fileName"),
                    mimeType = x.getString("mimeType"), sizeBytes = x.optLong("sizeBytes"), label = x.str("label"),
                    warrantyUntil = x.long("warrantyUntil"), createdAt = x.optLong("createdAt"),
                )
            },
            attachmentFiles = o.optJSONObject("attachmentFiles")?.let { f ->
                f.keys().asSequence().associateWith { java.util.Base64.getDecoder().decode(f.getString(it)) }
            }.orEmpty(),
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
    private fun JSONObject.double(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
}
