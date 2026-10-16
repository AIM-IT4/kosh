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
     * Reads the inbox since the last scan. Returns what was imported, or null when import is off, SMS access is
     * missing, the first scan hasn't been done yet (onboarding does that long one), or it took too long.
     */
    suspend fun run(container: AppContainer, alert: Boolean, timeoutMillis: Long = 30_000): ImportResult? {
        val settings = container.settings.current()
        if (!settings.smsAutoImport || settings.smsLastScanMillis <= 0 || !container.smsImporter.hasPermission()) return null
        val result = withTimeoutOrNull(timeoutMillis) { runCatching { container.smsImporter.importInbox() }.getOrNull() } ?: return null
        if (alert) postAlerts(container, settings, result.insertedTransactions)
        return result
    }

    /** "₹X spent at Y" notifications, as the live receiver posts them; newest first, at most [MAX_ALERTS]. */
    fun postAlerts(container: AppContainer, settings: Settings, txns: List<TransactionEntity>) {
        if (!settings.notifyTransactions) return
        for (txn in txns.sortedByDescending { it.timestamp }.take(MAX_ALERTS)) {
            val verb = if (txn.type == TxnType.INCOME) "received" else "spent"
            // Privacy mode keeps both the amount and the merchant off the notification shade.
            val text = if (settings.hideAmounts) {
                "New transaction $verb"
            } else {
                val where = txn.merchant?.let { if (txn.type == TxnType.INCOME) " from $it" else " at $it" } ?: ""
                "${Money.format(txn.amount)} $verb$where"
            }
            container.notifications.transaction(txn.id, text, "Tap to review and categorise")
        }
    }

    /** The live receiver needs RECEIVE_SMS as well as READ_SMS. */
    fun canReceive(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
