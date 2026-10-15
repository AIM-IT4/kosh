package com.amitjha.kosh.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.toolsStore: DataStore<Preferences> by preferencesDataStore(
    name = "kosh_tools",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Preferences for export, widgets, the daily digest and the app PIN / decoy PIN. */
data class ToolsPrefs(
    val digestEnabled: Boolean = false,
    /** Minutes after midnight for the daily digest; default 9 PM. */
    val digestMinutes: Int = DEFAULT_DIGEST_MINUTES,
    /** Show amounts on the home-screen widget even while app lock is on. */
    val widgetAmountsWhenLocked: Boolean = false,
    val pinHash: String? = null,
    val decoyPinHash: String? = null,
    val pinFailures: Int = 0,
) {
    val pinSet: Boolean get() = pinHash != null
    val decoySet: Boolean get() = pinHash != null && decoyPinHash != null

    companion object {
        const val DEFAULT_DIGEST_MINUTES = 21 * 60
    }
}

/** Kept in its own file so the decoy profile and the main settings never share these values. */
class ToolsSettings private constructor(private val context: Context) {

    private object Keys {
        val digestEnabled = booleanPreferencesKey("digest_enabled")
        val digestMinutes = intPreferencesKey("digest_minutes")
        val widgetAmountsWhenLocked = booleanPreferencesKey("widget_amounts_when_locked")
        val pinHash = stringPreferencesKey("pin_hash")
        val decoyPinHash = stringPreferencesKey("decoy_pin_hash")
        val pinFailures = intPreferencesKey("pin_failures")
    }

    val prefs: Flow<ToolsPrefs> = context.toolsStore.data
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            ToolsPrefs(
                digestEnabled = p[Keys.digestEnabled] ?: false,
                digestMinutes = p[Keys.digestMinutes] ?: ToolsPrefs.DEFAULT_DIGEST_MINUTES,
                widgetAmountsWhenLocked = p[Keys.widgetAmountsWhenLocked] ?: false,
                pinHash = p[Keys.pinHash],
                decoyPinHash = p[Keys.decoyPinHash],
                pinFailures = p[Keys.pinFailures] ?: 0,
            )
        }

    suspend fun current(): ToolsPrefs = prefs.first()

    suspend fun setDigestEnabled(enabled: Boolean) = edit { it[Keys.digestEnabled] = enabled }
    suspend fun setDigestMinutes(minutes: Int) = edit { it[Keys.digestMinutes] = minutes.coerceIn(0, 24 * 60 - 1) }
    suspend fun setWidgetAmountsWhenLocked(show: Boolean) = edit { it[Keys.widgetAmountsWhenLocked] = show }

    suspend fun setPinHash(hash: String?) = edit {
        if (hash == null) {
            // Removing the app PIN also removes the decoy PIN, which only works alongside it.
            it.remove(Keys.pinHash); it.remove(Keys.decoyPinHash)
        } else {
            it[Keys.pinHash] = hash
        }
        it[Keys.pinFailures] = 0
    }

    suspend fun setDecoyPinHash(hash: String?) = edit { if (hash == null) it.remove(Keys.decoyPinHash) else it[Keys.decoyPinHash] = hash }

    suspend fun recordPinFailure(): Int {
        var n = 0
        edit { n = (it[Keys.pinFailures] ?: 0) + 1; it[Keys.pinFailures] = n }
        return n
    }

    suspend fun resetPinFailures() = edit { it[Keys.pinFailures] = 0 }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.toolsStore.edit { block(it) }
    }

    companion object {
        @Volatile private var instance: ToolsSettings? = null

        fun get(context: Context): ToolsSettings =
            instance ?: synchronized(this) { instance ?: ToolsSettings(context.applicationContext).also { instance = it } }
    }
}
