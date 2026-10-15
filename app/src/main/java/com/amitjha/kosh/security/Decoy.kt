package com.amitjha.kosh.security

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.amitjha.kosh.data.DefaultCategories
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.decoySettingsStore: DataStore<Preferences> by preferencesDataStore(name = "kosh_profile_b")

/** What a sandboxed (decoy) [AppContainer] uses instead of the real database, settings and SMS inbox. */
class DecoySandbox(
    val db: KoshDatabase,
    val settings: SettingsStore,
    val smsContext: Context,
)

/**
 * The decoy profile opened by the decoy PIN. It lives only in memory: a throwaway in-memory database filled with
 * sample data, its own settings file and an SMS importer that never gets SMS access. The real database is never
 * opened through it, and background SMS imports keep writing to the real container regardless.
 */
object DecoySession {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile var container: AppContainer? = null
        private set

    private val mutex = Mutex()

    val isActive: Boolean get() = _active.value

    /** Opens the decoy profile, building and seeding it on first use in this process. */
    suspend fun activate(context: Context, themeOf: com.amitjha.kosh.data.prefs.ThemeMode? = null) {
        val decoy = mutex.withLock { container ?: build(context.applicationContext).also { container = it } }
        // Looks like an ordinary, set-up and locked profile.
        decoy.settings.setOnboardingDone(true)
        decoy.settings.setBiometricLock(true)
        if (themeOf != null) decoy.settings.setThemeMode(themeOf)
        _active.value = true
    }

    /** Returns to the real profile (real PIN, biometrics, or a fresh process). */
    fun deactivate() {
        _active.value = false
    }

    private suspend fun build(app: Context): AppContainer {
        val db = Room.inMemoryDatabaseBuilder(app, KoshDatabase::class.java).build()
        val sandbox = DecoySandbox(db, SettingsStore(app, app.decoySettingsStore), NoSmsContext(app))
        val c = AppContainer(app, sandbox)
        db.categories().insertAll(DefaultCategories.all)
        runCatching {
            if (c.demoData.load()) {
                c.investments.revalueAll(fetchNavs = false)
                c.netWorth.snapshot()
            }
        }
        return c
    }
}

/** A context that reports SMS permissions as denied, so the decoy profile can never read the real inbox. */
class NoSmsContext(base: Context) : ContextWrapper(base) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (permission in SMS_PERMISSIONS) PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)

    override fun checkSelfPermission(permission: String): Int =
        if (permission in SMS_PERMISSIONS) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (permission in SMS_PERMISSIONS) PackageManager.PERMISSION_DENIED else super.checkCallingOrSelfPermission(permission)

    override fun getApplicationContext(): Context = this

    private companion object {
        val SMS_PERMISSIONS = setOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
    }
}
