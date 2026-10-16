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
        container.notifications.createChannels()
        appScope.launch {
            // Seeding with IGNORE adds categories introduced by an update without touching existing ones.
            container.db.categories().insertAll(DefaultCategories.all)
            runCatching {
                com.amitjha.kosh.repo.DataRepairs.run(container.db, container.settings, container.netWorth)
            }
            runCatching { com.amitjha.kosh.repo.AutoCategoriser.run(container.db) }
            runCatching { container.settings.clearLegacyGmail() }
        }
        WorkScheduler.schedule(this)
        catchUpSms()
        com.amitjha.kosh.tools.WidgetUpdater.start(this)
        appScope.launch { runCatching { com.amitjha.kosh.tools.DigestSchedule.sync(this@KoshApp) } }
    }

    private val lastCatchUp = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Imports bank messages the live receiver missed. Many phones (battery savers, "autostart" settings) stop
     * background SMS delivery to apps, so Kosh also catches up whenever it is opened, at most once a minute.
     * Fingerprints keep messages the receiver already imported from being added twice.
     */
    fun catchUpSms() {
        val now = System.currentTimeMillis()
        val last = lastCatchUp.get()
        if (now - last < CATCH_UP_EVERY_MILLIS || !lastCatchUp.compareAndSet(last, now)) return
        appScope.launch {
            runCatching {
                val s = container.settings.current()
                // Before the first scan (onboarding), leave the long first import to the user.
                if (s.smsAutoImport && s.smsLastScanMillis > 0 && container.smsImporter.hasPermission()) {
                    container.smsImporter.importInbox()
                }
            }
        }
    }

    private companion object {
        const val CATCH_UP_EVERY_MILLIS = 60_000L
    }
}
