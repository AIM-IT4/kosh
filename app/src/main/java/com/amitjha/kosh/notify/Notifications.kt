package com.amitjha.kosh.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.amitjha.kosh.MainActivity
import com.amitjha.kosh.R

/** Notification channels and helpers. Taps open [MainActivity] with [EXTRA_ROUTE] set. */
class Notifications(private val context: Context) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_TRANSACTIONS, "Transactions", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "New transactions detected from bank SMS" },
                NotificationChannel(CH_BILLS, "Card bills", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Credit card bills and due-date reminders" },
                NotificationChannel(CH_INSIGHTS, "Insights", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Budget and goal alerts" },
                NotificationChannel(CH_MATURITY, "Investments", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "FD maturities and investment updates" },
            ),
        )
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun transaction(id: Long, title: String, text: String) =
        post(CH_TRANSACTIONS, (NOTIF_TXN_BASE + id % 100_000).toInt(), title, text, ROUTE_REVIEW)

    fun bill(key: Int, title: String, text: String) = post(CH_BILLS, NOTIF_BILL_BASE + key, title, text, ROUTE_BILLS)

    fun insight(key: Int, title: String, text: String) = post(CH_INSIGHTS, NOTIF_INSIGHT_BASE + key, title, text, ROUTE_INSIGHTS)

    fun maturity(key: Int, title: String, text: String) = post(CH_MATURITY, NOTIF_MATURITY_BASE + key, title, text, ROUTE_INVESTMENTS)

    private fun post(channel: String, id: Int, title: String, text: String, route: String) {
        if (!canNotify()) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ROUTE, route)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setColor(0xFF4C3BE0.toInt())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        const val CH_TRANSACTIONS = "transactions"
        const val CH_BILLS = "bills"
        const val CH_INSIGHTS = "insights"
        const val CH_MATURITY = "maturity"

        /** Intent extra naming the screen to open: one of the ROUTE_ constants. */
        const val EXTRA_ROUTE = "kosh.route"
        const val ROUTE_REVIEW = "review"
        const val ROUTE_BILLS = "bills"
        const val ROUTE_INSIGHTS = "insights"
        const val ROUTE_INVESTMENTS = "investments"

        private const val NOTIF_TXN_BASE = 100_000L
        private const val NOTIF_BILL_BASE = 300_000
        private const val NOTIF_INSIGHT_BASE = 400_000
        private const val NOTIF_MATURITY_BASE = 500_000
    }
}
