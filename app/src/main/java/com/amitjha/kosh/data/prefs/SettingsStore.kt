package com.amitjha.kosh.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A corrupt settings file is replaced with defaults instead of crashing every launch.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kosh_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val onboardingDone: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val smsAutoImport: Boolean = true,
    val smsLastScanMillis: Long = 0L,
    /** What went wrong in the last inbox scan (see `ImportResult.problem`), and when; null after a clean scan. */
    val smsProblem: String? = null,
    val smsProblemAt: Long = 0L,
    /** When the user dismissed the Home notice about SMS going to ignored accounts; only newer ones show. */
    val ignoredNoticeAt: Long = 0L,
    val monthStartDay: Int = 1,
    val notifyTransactions: Boolean = true,
    val notifyBills: Boolean = true,
    val notifyInsights: Boolean = true,
    val hideAmounts: Boolean = false,
    val dismissedInsightIds: Set<String> = emptySet(),
    /** User's explicit choice for blocking screenshots and the recents preview; null follows [blockScreenshots]. */
    val blockScreenshotsChoice: Boolean? = null,
) {
    val currencySymbol: String get() = "₹"

    /** Screenshots and the recents preview are blocked by default while app lock or privacy mode is on. */
    val blockScreenshots: Boolean get() = blockScreenshotsChoice ?: (biometricLockEnabled || hideAmounts)
}

/** App preferences backed by DataStore. */
class SettingsStore(
    private val context: Context,
    /** The decoy profile passes its own store so it never reads or changes the real settings. */
    private val store: DataStore<Preferences> = context.dataStore,
) {

    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val biometricLock = booleanPreferencesKey("biometric_lock")
        val themeMode = stringPreferencesKey("theme_mode")
        val smsAutoImport = booleanPreferencesKey("sms_auto_import")
        val smsLastScan = longPreferencesKey("sms_last_scan")
        val smsProblem = stringPreferencesKey("sms_problem")
        val smsProblemAt = longPreferencesKey("sms_problem_at")
        val ignoredNoticeAt = longPreferencesKey("ignored_notice_at")
        // Left by the removed Gmail import; cleared on start.
        val legacyGmail = listOf(
            stringPreferencesKey("gmail_email"), longPreferencesKey("gmail_last_sync"), booleanPreferencesKey("gmail_reconnect"),
            stringSetPreferencesKey("processed_email_ids"), stringPreferencesKey("gmail_last_summary"),
        )
        val monthStartDay = intPreferencesKey("month_start_day")
        val notifyTransactions = booleanPreferencesKey("notify_transactions")
        val notifyBills = booleanPreferencesKey("notify_bills")
        val notifyInsights = booleanPreferencesKey("notify_insights")
        val hideAmounts = booleanPreferencesKey("hide_amounts")
        val dismissedInsights = stringSetPreferencesKey("dismissed_insights")
        val notifiedInsights = stringSetPreferencesKey("notified_insights")
        val notifiedBills = stringSetPreferencesKey("notified_bills")
        val repairVersion = intPreferencesKey("repair_version")
        val blockScreenshots = booleanPreferencesKey("block_screenshots")
    }

    val settings: Flow<Settings> = store.data.catch { e ->
        // An unreadable file reads as defaults rather than crashing the collectors.
        if (e is java.io.IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        Settings(
            onboardingDone = p[Keys.onboardingDone] ?: false,
            biometricLockEnabled = p[Keys.biometricLock] ?: false,
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
            smsAutoImport = p[Keys.smsAutoImport] ?: true,
            smsLastScanMillis = p[Keys.smsLastScan] ?: 0L,
            smsProblem = p[Keys.smsProblem],
            smsProblemAt = p[Keys.smsProblemAt] ?: 0L,
            ignoredNoticeAt = p[Keys.ignoredNoticeAt] ?: 0L,
            monthStartDay = p[Keys.monthStartDay] ?: 1,
            notifyTransactions = p[Keys.notifyTransactions] ?: true,
            notifyBills = p[Keys.notifyBills] ?: true,
            notifyInsights = p[Keys.notifyInsights] ?: true,
            hideAmounts = p[Keys.hideAmounts] ?: false,
            dismissedInsightIds = p[Keys.dismissedInsights] ?: emptySet(),
            blockScreenshotsChoice = p[Keys.blockScreenshots],
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setOnboardingDone(done: Boolean = true) = edit { it[Keys.onboardingDone] = done }
    suspend fun setBiometricLock(enabled: Boolean) = edit { it[Keys.biometricLock] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }
    suspend fun setSmsAutoImport(enabled: Boolean) = edit { it[Keys.smsAutoImport] = enabled }
    suspend fun setSmsLastScan(millis: Long) = edit { it[Keys.smsLastScan] = millis }
    suspend fun dismissIgnoredNotice(at: Long = System.currentTimeMillis()) = edit { it[Keys.ignoredNoticeAt] = at }
    suspend fun setSmsProblem(problem: String?, at: Long = System.currentTimeMillis()) = edit {
        if (problem == null) {
            it.remove(Keys.smsProblem)
            it.remove(Keys.smsProblemAt)
        } else {
            it[Keys.smsProblem] = problem.take(200)
            it[Keys.smsProblemAt] = at
        }
    }
    suspend fun setMonthStartDay(day: Int) = edit { it[Keys.monthStartDay] = day.coerceIn(1, 28) }
    suspend fun setNotifyTransactions(enabled: Boolean) = edit { it[Keys.notifyTransactions] = enabled }
    suspend fun setNotifyBills(enabled: Boolean) = edit { it[Keys.notifyBills] = enabled }
    suspend fun setNotifyInsights(enabled: Boolean) = edit { it[Keys.notifyInsights] = enabled }
    suspend fun setHideAmounts(hidden: Boolean) = edit { it[Keys.hideAmounts] = hidden }
    suspend fun setBlockScreenshots(blocked: Boolean) = edit { it[Keys.blockScreenshots] = blocked }

    /** Clears preferences left by the removed Gmail import. */
    suspend fun clearLegacyGmail() = edit { prefs -> Keys.legacyGmail.forEach { prefs.remove(it) } }

    /** Version of the last one-time data repair applied after an update (see `repo.DataRepairs`). */
    suspend fun repairVersion(): Int = runCatching { store.data.first()[Keys.repairVersion] }.getOrNull() ?: 0

    suspend fun setRepairVersion(version: Int) = edit { it[Keys.repairVersion] = version }

    suspend fun dismissInsight(id: String) = edit {
        val current = (it[Keys.dismissedInsights] ?: emptySet()).toList()
        it[Keys.dismissedInsights] = (current + id).distinct().takeLast(MAX_IDS).toSet()
    }

    suspend fun clearDismissedInsights() = edit { it.remove(Keys.dismissedInsights) }

    /** Returns the ids from [ids] not yet notified, and records them as notified. */
    suspend fun claimNewInsightNotifications(ids: Collection<String>): Set<String> = claim(Keys.notifiedInsights, ids)

    suspend fun claimNewBillNotifications(ids: Collection<String>): Set<String> = claim(Keys.notifiedBills, ids)

    private suspend fun claim(key: Preferences.Key<Set<String>>, ids: Collection<String>): Set<String> {
        var fresh: Set<String> = emptySet()
        edit {
            val current = it[key] ?: emptySet()
            fresh = ids.filterNot { id -> id in current }.toSet()
            it[key] = (current.toList() + fresh).distinct().takeLast(MAX_IDS).toSet()
        }
        return fresh
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    companion object {
        const val MAX_IDS = 500
    }
}
