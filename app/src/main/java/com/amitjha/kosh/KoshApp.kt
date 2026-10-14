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
        appScope.launch { container.db.categories().insertAll(DefaultCategories.all) }
        WorkScheduler.schedule(this)
    }
}
