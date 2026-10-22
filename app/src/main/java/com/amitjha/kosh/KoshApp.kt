package com.amitjha.kosh

import android.app.Application
import com.amitjha.kosh.data.DefaultCategories
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KoshApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Repositories and services; see [AppContainer]. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Channels need only the context: building the container here would open the database on the main thread.
        com.amitjha.kosh.notify.Notifications(this).createChannels()
        appScope.launch {
            com.amitjha.kosh.repo.PaydayLearner.start(appScope, container.db, container.settings)
            // Seeding with IGNORE adds categories introduced by an update without touching existing ones.
            container.db.categories().insertAll(DefaultCategories.all)
            runCatching {
                com.amitjha.kosh.repo.DataRepairs.run(container.db, container.settings, container.netWorth, container.smsImporter)
            }
            runCatching { com.amitjha.kosh.repo.AutoCategoriser.run(container.db) }
            runCatching { container.settings.clearLegacyGmail() }
        }
        // No SMS catch-up here: this also runs when an SMS wakes the app, and a silent catch-up could import that
        // message before the receiver, which would then skip its alert. Opening the app catches up (MainActivity).
        WorkScheduler.schedule(this)
        com.amitjha.kosh.tools.WidgetUpdater.start(this)
        appScope.launch { runCatching { com.amitjha.kosh.tools.DigestSchedule.sync(this@KoshApp) } }
    }

    private val lastCatchUp = java.util.concurrent.atomic.AtomicLong(0)

    /** Catches up on bank SMS the live receiver missed (see [com.amitjha.kosh.sms.SmsCatchUp]), at most once a minute. */
    fun catchUpSms() {
        val now = System.currentTimeMillis()
        val last = lastCatchUp.get()
        if (now - last < CATCH_UP_EVERY_MILLIS || !lastCatchUp.compareAndSet(last, now)) return
        // The user is opening the app, so no "spent at" alerts: the new transactions are on screen.
        appScope.launch { com.amitjha.kosh.sms.SmsCatchUp.run(container, alert = false) }
    }

    private companion object {
        const val CATCH_UP_EVERY_MILLIS = 60_000L
    }
}
