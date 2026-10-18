package com.amitjha.kosh.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.featureStore: DataStore<Preferences> by preferencesDataStore(
    name = "feature_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The decoy profile's own copy, so it never shows or changes the real backup folder or other choices. */
private val Context.decoyFeatureStore: DataStore<Preferences> by preferencesDataStore(
    name = "feature_prefs_decoy",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Automatic backups to a folder the user picked. The password itself is kept in `security.LocalSecrets`. */
data class AutoBackupPrefs(
    val enabled: Boolean = false,
    /** Storage Access Framework tree URI of the folder. */
    val folderUri: String? = null,
    /** 1 = daily, 7 = weekly. */
    val everyDays: Int = 1,
    /** How many automatic backups to keep in the folder; older ones are deleted. */
    val keep: Int = 7,
    val lastMillis: Long = 0L,
    val lastFile: String? = null,
    val lastError: String? = null,
)

data class FeatureSettings(
    val autoBackup: AutoBackupPrefs = AutoBackupPrefs(),
    /** Budgets screen shows the zero-based view: income of the month minus what budgets assign. */
    val envelopeMode: Boolean = false,
    /** Recurring payments the user said aren't recurring (by `Recurring.Series.key`). */
    val hiddenRecurring: Set<String> = emptySet(),
    /** Budget id → epoch day of the first period that rolls over (when rollover was turned on). */
    val rolloverSince: Map<Long, Long> = emptyMap(),
    /** UI language code ("en" or "hi"). */
    val language: String = "en",
    /** Monthly take-home salary the user entered; null means use the salary credits Kosh sees. */
    val salary: Long? = null,
    /** What the user wants to save out of each month's salary; null until they set it. */
    val savingsTarget: Long? = null,
)

/** The real profile's preferences or, for the decoy profile, its own separate ones. */
fun featurePrefsFor(context: Context, decoy: Boolean): FeaturePrefs =
    FeaturePrefs(context, if (decoy) context.decoyFeatureStore else context.featureStore)

/** Preferences of the features added in 2.1, kept apart from the main settings. */
class FeaturePrefs(context: Context, private val store: DataStore<Preferences> = context.featureStore) {

    private object Keys {
        val autoEnabled = booleanPreferencesKey("auto_backup_enabled")
        val autoFolder = stringPreferencesKey("auto_backup_folder")
        val autoEvery = intPreferencesKey("auto_backup_every_days")
        val autoKeep = intPreferencesKey("auto_backup_keep")
        val autoLast = longPreferencesKey("auto_backup_last")
        val autoLastFile = stringPreferencesKey("auto_backup_last_file")
        val autoLastError = stringPreferencesKey("auto_backup_last_error")
        val envelope = booleanPreferencesKey("budget_envelope_mode")
        val hiddenRecurring = stringSetPreferencesKey("hidden_recurring")
        val notifiedWarranties = stringSetPreferencesKey("notified_warranties")
        val rolloverSince = stringSetPreferencesKey("rollover_since")
        val language = stringPreferencesKey("app_language")
        val salary = longPreferencesKey("plan_salary")
        val savingsTarget = longPreferencesKey("plan_savings_target")
    }

    val settings: Flow<FeatureSettings> = store.data.catch { emit(emptyPreferences()) }.map { p ->
        FeatureSettings(
            autoBackup = AutoBackupPrefs(
                enabled = p[Keys.autoEnabled] ?: false,
                folderUri = p[Keys.autoFolder],
                everyDays = p[Keys.autoEvery] ?: 1,
                keep = p[Keys.autoKeep] ?: 7,
                lastMillis = p[Keys.autoLast] ?: 0L,
                lastFile = p[Keys.autoLastFile],
                lastError = p[Keys.autoLastError],
            ),
            envelopeMode = p[Keys.envelope] ?: false,
            hiddenRecurring = p[Keys.hiddenRecurring] ?: emptySet(),
            rolloverSince = (p[Keys.rolloverSince] ?: emptySet()).mapNotNull { e ->
                val (id, day) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                (id.toLongOrNull() ?: return@mapNotNull null) to (day.toLongOrNull() ?: return@mapNotNull null)
            }.toMap(),
            language = p[Keys.language] ?: "en",
            salary = p[Keys.salary],
            savingsTarget = p[Keys.savingsTarget],
        )
    }
        .distinctUntilChanged()

    suspend fun current(): FeatureSettings = settings.first()

    suspend fun setAutoBackup(enabled: Boolean, folderUri: String?, everyDays: Int, keep: Int) = edit {
        it[Keys.autoEnabled] = enabled
        if (folderUri != null) it[Keys.autoFolder] = folderUri else it.remove(Keys.autoFolder)
        it[Keys.autoEvery] = everyDays.coerceIn(1, 30)
        it[Keys.autoKeep] = keep.coerceIn(1, 60)
    }

    suspend fun recordAutoBackup(millis: Long, file: String?, error: String?) = edit {
        if (error == null) {
            it[Keys.autoLast] = millis
            if (file != null) it[Keys.autoLastFile] = file
            it.remove(Keys.autoLastError)
        } else {
            it[Keys.autoLastError] = error
        }
    }

    suspend fun setEnvelopeMode(on: Boolean) = edit { it[Keys.envelope] = on }

    suspend fun setLanguage(code: String) = edit { it[Keys.language] = code }

    /** Salary plan for "left to spend": null clears a value. */
    suspend fun setSalaryPlan(salary: Long?, savingsTarget: Long?) = edit {
        if (salary != null && salary > 0) it[Keys.salary] = salary else it.remove(Keys.salary)
        if (savingsTarget != null && savingsTarget >= 0) it[Keys.savingsTarget] = savingsTarget else it.remove(Keys.savingsTarget)
    }

    /** Records (or clears) the first rolling-over period of budget [budgetId]. */
    suspend fun setRolloverSince(budgetId: Long, epochDay: Long?) = edit {
        val kept = (it[Keys.rolloverSince] ?: emptySet()).filterNot { e -> e.startsWith("$budgetId:") }.toSet()
        it[Keys.rolloverSince] = if (epochDay == null) kept else kept + "$budgetId:$epochDay"
    }

    suspend fun setRecurringHidden(key: String, hidden: Boolean) = edit {
        val current = it[Keys.hiddenRecurring] ?: emptySet()
        it[Keys.hiddenRecurring] = if (hidden) current + key else current - key
    }

    /** Returns the ids from [ids] not yet notified, and records them as notified. */
    suspend fun claimWarrantyNotifications(ids: Collection<String>): Set<String> {
        var fresh: Set<String> = emptySet()
        edit {
            val current = it[Keys.notifiedWarranties] ?: emptySet()
            fresh = ids.filterNot { id -> id in current }.toSet()
            it[Keys.notifiedWarranties] = (current.toList() + fresh).distinct().takeLast(500).toSet()
        }
        return fresh
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }
}
