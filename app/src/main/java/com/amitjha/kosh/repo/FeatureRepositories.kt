package com.amitjha.kosh.repo

import android.net.Uri
import com.amitjha.kosh.data.AttachmentEntity
import com.amitjha.kosh.data.AttachmentStore
import com.amitjha.kosh.data.DebtEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.LoanEntity
import com.amitjha.kosh.finance.Loans
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Money lent to friends and family, or borrowed from them. */
class DebtRepository(private val db: KoshDatabase) {
    fun observe(): Flow<List<DebtEntity>> = db.debts().observeAll()

    suspend fun save(debt: DebtEntity): Long = db.debts().upsert(debt)

    suspend fun delete(debt: DebtEntity) = db.debts().delete(debt)

    /** Records a repayment; the debt closes once it is fully repaid. */
    suspend fun repay(id: Long, amount: Paise) {
        val d = db.debts().byId(id) ?: return
        val repaid = (d.repaid + amount).coerceIn(0, d.amount)
        db.debts().upsert(d.copy(repaid = repaid, closed = repaid >= d.amount))
    }

    companion object {
        const val LENT = "LENT"
        const val BORROWED = "BORROWED"
    }
}

/** A loan with its schedule worked out for today. */
data class LoanStatus(val loan: LoanEntity, val spec: Loans.Spec, val outstanding: Paise, val emi: Paise, val endDate: LocalDate?, val paidMonths: Int)

class LoanRepository(private val db: KoshDatabase) {
    fun observe(): Flow<List<LoanStatus>> = db.loans().observeActive().map { list -> list.map { status(it) } }

    fun observe(id: Long): Flow<LoanStatus?> = db.loans().observe(id).map { it?.let(::status) }

    suspend fun active(): List<LoanStatus> = db.loans().active().map { status(it) }

    suspend fun save(loan: LoanEntity): Long = db.loans().upsert(loan)

    suspend fun delete(loan: LoanEntity) = db.loans().delete(loan)

    /** Total still owed on every active loan today. */
    suspend fun totalOutstanding(today: LocalDate = Time.today()): Paise = active().sumOf { Loans.outstanding(it.spec, today) }

    /** Home-loan interest and principal paid in the financial year starting April [fyStartYear], for the tax planner. */
    suspend fun homeLoanYear(fyStartYear: Int): Loans.YearSplit {
        val homes = db.loans().active().filter { it.kind == HOME }
        return homes.map { Loans.financialYear(spec(it), fyStartYear) }
            .fold(Loans.YearSplit(0, 0)) { a, b -> Loans.YearSplit(a.interest + b.interest, a.principal + b.principal) }
    }

    fun status(loan: LoanEntity, today: LocalDate = Time.today()): LoanStatus {
        val spec = spec(loan)
        val rows = runCatching { Loans.schedule(spec) }.getOrDefault(emptyList())
        return LoanStatus(
            loan = loan,
            spec = spec,
            outstanding = runCatching { Loans.outstanding(spec, today) }.getOrDefault(loan.principal),
            emi = rows.firstOrNull { !it.date.isBefore(today) }?.emi ?: rows.lastOrNull()?.emi ?: 0,
            endDate = rows.lastOrNull()?.date,
            paidMonths = rows.count { !it.date.isAfter(today) },
        )
    }

    companion object {
        const val HOME = "HOME"
        val KINDS = listOf("HOME", "CAR", "PERSONAL", "EDUCATION", "OTHER")

        fun spec(loan: LoanEntity): Loans.Spec = Loans.Spec(
            principal = loan.principal,
            annualRatePct = loan.ratePct,
            tenureMonths = loan.tenureMonths,
            firstEmiDate = LocalDate.ofEpochDay(loan.firstEmiDay),
            emi = loan.emi,
            prepayments = decodePrepayments(loan.prepaymentsJson),
        )

        fun decodePrepayments(json: String): List<Loans.Prepayment> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Loans.Prepayment(
                    LocalDate.ofEpochDay(o.getLong("day")),
                    o.getLong("amount"),
                    runCatching { Loans.Reduce.valueOf(o.optString("reduce", "TENURE")) }.getOrDefault(Loans.Reduce.TENURE),
                )
            }
        }.getOrDefault(emptyList())

        fun encodePrepayments(list: List<Loans.Prepayment>): String = JSONArray().apply {
            list.sortedBy { it.date }.forEach { put(JSONObject().put("day", it.date.toEpochDay()).put("amount", it.amount).put("reduce", it.reduce.name)) }
        }.toString()
    }
}

/** Receipt and warranty files linked to transactions. */
class AttachmentRepository(private val db: KoshDatabase, private val store: AttachmentStore) {
    fun observeFor(txnId: Long): Flow<List<AttachmentEntity>> = db.attachments().observeFor(txnId)

    fun observeWarranties(): Flow<List<AttachmentEntity>> = db.attachments().observeWarranties()

    suspend fun add(txnId: Long, uri: Uri, label: String? = null, warrantyUntil: LocalDate? = null): Long {
        val saved = store.save(uri)
        return runCatching {
            db.attachments().insert(
                AttachmentEntity(
                    transactionId = txnId, fileName = saved.fileName, mimeType = saved.mimeType, sizeBytes = saved.sizeBytes,
                    label = label, warrantyUntil = warrantyUntil?.toEpochDay(),
                ),
            )
        }.getOrElse { store.delete(saved.fileName); throw it }
    }

    suspend fun update(a: AttachmentEntity) = db.attachments().update(a)

    suspend fun delete(a: AttachmentEntity) {
        db.attachments().delete(a)
        store.delete(a.fileName)
    }

    fun bytes(a: AttachmentEntity): ByteArray = store.read(a.fileName)

    fun exportToCache(a: AttachmentEntity) = store.exportToCache(a.fileName, a.mimeType)

    suspend fun warranties(): List<AttachmentEntity> = db.attachments().warranties()
}
