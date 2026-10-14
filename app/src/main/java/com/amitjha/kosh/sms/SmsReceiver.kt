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
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        // Multi-part messages arrive as several PDUs from the same sender.
        val messages = parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
            .map { (sender, pdus) ->
                SmsMessage(sender, pdus.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }, pdus.first().timestampMillis)
            }
            .filter { isFinancialMessage(it.sender, it.body) }
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
                        val amount = if (settings.hideAmounts) "Money" else Money.format(txn.amount)
                        val where = txn.merchant?.let { if (txn.type == TxnType.INCOME) " from $it" else " at $it" } ?: ""
                        container.notifications.transaction(txn.id, "$amount $verb$where", "Tap to review and categorise")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
