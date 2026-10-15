package com.amitjha.kosh.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.amitjha.kosh.KoshApp
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import kotlinx.coroutines.launch

/** Imports bank SMS the moment they arrive and posts a "tap to review" notification. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // Malformed PDUs come back as null entries; a bad broadcast must never crash the app.
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
            ?.filterNotNull()
            .orEmpty()
        if (parts.isEmpty()) return
        // Multi-part messages arrive as several PDUs from the same sender.
        val messages = parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
            .filterKeys { it.isNotEmpty() }
            .map { (sender, pdus) ->
                // The PDU timestamp is the send time, the same value inbox scans read from DATE_SENT.
                SmsMessage(sender, pdus.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }, pdus.first().timestampMillis)
            }
            .filter { it.body.isNotBlank() && isFinancialMessage(it.sender, it.body) }
        if (messages.isEmpty()) return

        val app = context.applicationContext as KoshApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val container = app.container
                val settings = container.settings.current()
                if (!settings.smsAutoImport) return@launch
                val result = container.smsImporter.importMessages(messages)
                if (settings.notifyTransactions) {
                    for (txn in result.insertedTransactions) {
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
            } catch (e: Exception) {
                // A failed live import is retried by the next inbox scan; never take the app down for it.
            } finally {
                pending.finish()
            }
        }
    }
}
