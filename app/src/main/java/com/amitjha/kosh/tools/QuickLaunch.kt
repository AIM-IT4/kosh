package com.amitjha.kosh.tools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.amitjha.kosh.MainActivity
import com.amitjha.kosh.notify.Notifications

/** Intents from the home-screen widgets and the quick-settings tile into the app. */
object QuickLaunch {
    /** Values of [Notifications.EXTRA_ROUTE] handled by the main shell. */
    const val ROUTE_ADD_EXPENSE = "add_expense"
    const val ROUTE_ADD_INCOME = "add_income"

    fun intent(context: Context, route: String?): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (route != null) putExtra(Notifications.EXTRA_ROUTE, route) }

    /** An immutable PendingIntent; [requestCode] keeps the different buttons distinct. */
    fun pending(context: Context, route: String?, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent(context, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
