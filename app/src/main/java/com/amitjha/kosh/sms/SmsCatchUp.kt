package com.amitjha.kosh.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.data.prefs.Settings
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Imports bank messages the live receiver missed. Many phones stop SMS broadcasts reaching apps in the background
 * (battery savers, "autostart" settings), so Kosh also reads the inbox when it is opened, before the daily digest,
 * and every hour in the background. Fingerprints keep messages the receiver already imported from being added twice.
 */
object SmsCatchUp {

    /** Most transaction alerts posted for one catch-up; the rest wait in "to review". */
    const val MAX_ALERTS = 5

    /**
     * Reads the inbox since the last scan. Returns what was imported (with [ImportResult.problem] set when SMS access
     * is missing or the scan failed or took too long), or null when import is off or the first scan hasn't been done
     * yet (onboarding does that long one). A failure is remembered for More › Bank SMS instead of passing silently.
     */
    suspend fun run(container: AppContainer, alert: Boolean, timeoutMillis: Long = 30_000): ImportResult? {
        val settings = container.settings.current()
        if (!settings.smsAutoImport || settings.smsLastScanMillis <= 0) return null
        if (!container.smsImporter.hasPermission()) {
            container.settings.setSmsProblem(ImportResult(permissionMissing = true).problem)
            return ImportResult(permissionMissing = true)
        }
        val result = try {
            withTimeoutOrNull(timeoutMillis) { container.smsImporter.importInbox() }
                ?: ImportResult(readError = "Reading bank SMS took too long")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Callers run this from the app scope and workers: an error here must never take the app down.
            ImportResult(readError = "Bank SMS couldn't be imported (${e.javaClass.simpleName})")
        }
        // importInbox records its own outcome; a timeout or a crash outside it is recorded here.
        if (result.scanned == 0 && result.problem != null) container.settings.setSmsProblem(result.problem)
        if (alert) postAlerts(container, settings, result.insertedTransactions)
        postLinked(container, result.linkedMembers)
        return result
    }

    /**
     * "₹X spent at Y" notifications, as the live receiver posts them; newest first, at most [MAX_ALERTS]. A family
     * member's says whose it is ("Mom: ₹X spent at Y").
     */
    suspend fun postAlerts(container: AppContainer, settings: Settings, txns: List<TransactionEntity>) {
        if (!settings.notifyTransactions || txns.isEmpty()) return
        val owners = runCatching {
            val members = container.db.family().all().associateBy { it.id }
            container.db.accounts().all().mapNotNull { a -> a.memberId?.let { members[it] }?.let { a.id to it.name } }.toMap()
        }.getOrDefault(emptyMap())
        for (txn in txns.sortedByDescending { it.timestamp }.take(MAX_ALERTS)) {
            val verb = if (txn.type == TxnType.INCOME) "received" else "spent"
            val whose = owners[txn.accountId]?.let { "$it: " } ?: ""
            // Privacy mode keeps both the amount and the merchant off the notification shade.
            val text = if (settings.hideAmounts) {
                "${whose}New transaction $verb"
            } else {
                val where = txn.merchant?.let { if (txn.type == TxnType.INCOME) " from $it" else " at $it" } ?: ""
                "$whose${Money.format(txn.amount)} $verb$where"
            }
            container.notifications.transaction(txn.id, text, "Tap to review and categorise")
        }
    }

    /** "Mom's phone is linked": the link message from a family member's phone arrived. */
    fun postLinked(container: AppContainer, names: List<String>) {
        names.distinct().forEach { name ->
            container.notifications.familyLinked(name)
        }
    }

    /** The live receiver needs RECEIVE_SMS as well as READ_SMS. */
    fun canReceive(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
