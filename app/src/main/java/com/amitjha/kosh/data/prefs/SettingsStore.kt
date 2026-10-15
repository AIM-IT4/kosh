package com.amitjha.kosh.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kosh_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val onboardingDone: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val smsAutoImport: Boolean = true,
    val smsLastScanMillis: Long = 0L,
    val gmailAccountEmail: String? = null,
    val gmailLastSyncMillis: Long = 0L,
    val gmailReconnectNeeded: Boolean = false,
    /** Summary of the last Gmail sync, e.g. "Scanned 120 emails · 2 bills · 35 transactions". */
    val gmailLastSummary: String? = null,
    val monthStartDay: Int = 1,
    val notifyTransactions: Boolean = true,
    val notifyBills: Boolean = true,
    val notifyInsights: Boolean = true,
    val hideAmounts: Boolean = false,
    val dismissedInsightIds: Set<String> = emptySet(),
) {
    val currencySymbol: String get() = "₹"
    val gmailConnected: Boolean get() = gmailAccountEmail != null
}

/** App preferences backed by DataStore. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val biometricLock = booleanPreferencesKey("biometric_lock")
        val themeMode = stringPreferencesKey("theme_mode")
        val smsAutoImport = booleanPreferencesKey("sms_auto_import")
        val smsLastScan = longPreferencesKey("sms_last_scan")
        val gmailEmail = stringPreferencesKey("gmail_email")
        val gmailLastSync = longPreferencesKey("gmail_last_sync")
        val gmailReconnect = booleanPreferencesKey("gmail_reconnect")
        val processedEmailIds = stringSetPreferencesKey("processed_email_ids")
        val gmailLastSummary = stringPreferencesKey("gmail_last_summary")
        val monthStartDay = intPreferencesKey("month_start_day")
        val notifyTransactions = booleanPreferencesKey("notify_transactions")
        val notifyBills = booleanPreferencesKey("notify_bills")
        val notifyInsights = booleanPreferencesKey("notify_insights")
        val hideAmounts = booleanPreferencesKey("hide_amounts")
        val dismissedInsights = stringSetPreferencesKey("dismissed_insights")
        val notifiedInsights = stringSetPreferencesKey("notified_insights")
        val notifiedBills = stringSetPreferencesKey("notified_bills")
        val repairVersion = intPreferencesKey("repair_version")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            onboardingDone = p[Keys.onboardingDone] ?: false,
            biometricLockEnabled = p[Keys.biometricLock] ?: false,
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
            smsAutoImport = p[Keys.smsAutoImport] ?: true,
            smsLastScanMillis = p[Keys.smsLastScan] ?: 0L,
            gmailAccountEmail = p[Keys.gmailEmail],
            gmailLastSyncMillis = p[Keys.gmailLastSync] ?: 0L,
            gmailReconnectNeeded = p[Keys.gmailReconnect] ?: false,
            gmailLastSummary = p[Keys.gmailLastSummary],
            monthStartDay = p[Keys.monthStartDay] ?: 1,
            notifyTransactions = p[Keys.notifyTransactions] ?: true,
            notifyBills = p[Keys.notifyBills] ?: true,
            notifyInsights = p[Keys.notifyInsights] ?: true,
            hideAmounts = p[Keys.hideAmounts] ?: false,
            dismissedInsightIds = p[Keys.dismissedInsights] ?: emptySet(),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setOnboardingDone(done: Boolean = true) = edit { it[Keys.onboardingDone] = done }
    suspend fun setBiometricLock(enabled: Boolean) = edit { it[Keys.biometricLock] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }
    suspend fun setSmsAutoImport(enabled: Boolean) = edit { it[Keys.smsAutoImport] = enabled }
    suspend fun setSmsLastScan(millis: Long) = edit { it[Keys.smsLastScan] = millis }
    suspend fun setMonthStartDay(day: Int) = edit { it[Keys.monthStartDay] = day.coerceIn(1, 28) }
    suspend fun setNotifyTransactions(enabled: Boolean) = edit { it[Keys.notifyTransactions] = enabled }
    suspend fun setNotifyBills(enabled: Boolean) = edit { it[Keys.notifyBills] = enabled }
    suspend fun setNotifyInsights(enabled: Boolean) = edit { it[Keys.notifyInsights] = enabled }
    suspend fun setHideAmounts(hidden: Boolean) = edit { it[Keys.hideAmounts] = hidden }

    suspend fun setGmailAccount(email: String?) = edit {
        if (email == null) {
            it.remove(Keys.gmailEmail)
            it.remove(Keys.processedEmailIds)
        } else {
            it[Keys.gmailEmail] = email
        }
        it[Keys.gmailReconnect] = false
    }

    suspend fun setGmailReconnectNeeded(needed: Boolean) = edit { it[Keys.gmailReconnect] = needed }
    suspend fun setGmailLastSync(millis: Long) = edit { it[Keys.gmailLastSync] = millis }
    suspend fun setGmailLastSummary(summary: String) = edit { it[Keys.gmailLastSummary] = summary }

    /** Version of the last one-time data repair applied after an update (see `repo.DataRepairs`). */
    suspend fun repairVersion(): Int = context.dataStore.data.first()[Keys.repairVersion] ?: 0

    suspend fun setRepairVersion(version: Int) = edit { it[Keys.repairVersion] = version }

    suspend fun processedEmailIds(): Set<String> = context.dataStore.data.first()[Keys.processedEmailIds] ?: emptySet()

    /** Remembers processed Gmail message ids, keeping only the most recent [MAX_IDS]. */
    suspend fun addProcessedEmailIds(ids: Collection<String>) = edit {
        val current = (it[Keys.processedEmailIds] ?: emptySet()).toList()
        it[Keys.processedEmailIds] = (current + ids).distinct().takeLast(MAX_EMAIL_IDS).toSet()
    }

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
        context.dataStore.edit { block(it) }
    }

    companion object {
        const val MAX_IDS = 500
        const val MAX_EMAIL_IDS = 4000
    }
}
