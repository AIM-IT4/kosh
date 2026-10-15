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
        com.amitjha.kosh.tools.WidgetUpdater.start(this)
        appScope.launch { runCatching { com.amitjha.kosh.tools.DigestSchedule.sync(this@KoshApp) } }
    }
}
