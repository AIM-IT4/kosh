package com.amitjha.kosh.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.amitjha.kosh.model.Paise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.planningStore: DataStore<Preferences> by preferencesDataStore(
    name = "planning_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The user's own tax inputs. Null means "use the value Kosh worked out from your data"; a number overrides it.
 * Amounts are annual, in paise.
 */
data class TaxOverrides(
    val grossSalary: Paise? = null,
    val savingsInterest: Paise? = null,
    val fdInterest: Paise? = null,
    val otherIncome: Paise? = null,
    val sec80C: Paise? = null,
    val sec80CCD1B: Paise? = null,
    val sec80D: Paise? = null,
    val homeLoanPrincipal: Paise = 0,
    val homeLoanInterest: Paise = 0,
    val basicMonthly: Paise = 0,
    val hraMonthly: Paise = 0,
    val rentAnnual: Paise? = null,
    val metro: Boolean = true,
    val senior: Boolean = false,
)

/** Small planning preferences kept apart from the main settings: tax inputs and per-goal inflation. */
class PlanningPrefs(private val context: Context) {

    private object Keys {
        val tax = stringPreferencesKey("tax_overrides_json")
        val goalInflation = stringPreferencesKey("goal_inflation_json")
    }

    private val data: Flow<Preferences> = context.planningStore.data.catch { emit(emptyPreferences()) }

    val taxOverrides: Flow<TaxOverrides> = data.map { decodeTax(it[Keys.tax]) }

    suspend fun setTaxOverrides(o: TaxOverrides) {
        context.planningStore.edit { it[Keys.tax] = encodeTax(o) }
    }

    /** Inflation (% a year) applied to each goal's target in the probability view; missing = 0 (fixed target). */
    val goalInflation: Flow<Map<Long, Double>> = data.map { p ->
        val json = p[Keys.goalInflation] ?: return@map emptyMap()
        runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associate { it.toLong() to o.getDouble(it) }
        }.getOrDefault(emptyMap())
    }

    /** Read and written in one edit, so two quick changes can't overwrite each other. */
    suspend fun setGoalInflation(goalId: Long, pct: Double) {
        context.planningStore.edit { p ->
            val current = p[Keys.goalInflation]?.let { json ->
                runCatching { JSONObject(json).let { o -> o.keys().asSequence().associate { it.toLong() to o.getDouble(it) } } }.getOrNull()
            }.orEmpty().toMutableMap()
            if (pct <= 0.0) current.remove(goalId) else current[goalId] = pct
            p[Keys.goalInflation] = JSONObject().apply { current.forEach { (k, v) -> put(k.toString(), v) } }.toString()
        }
    }

    companion object {
        fun encodeTax(o: TaxOverrides): String = JSONObject().apply {
            o.grossSalary?.let { put("grossSalary", it) }
            o.savingsInterest?.let { put("savingsInterest", it) }
            o.fdInterest?.let { put("fdInterest", it) }
            o.otherIncome?.let { put("otherIncome", it) }
            o.sec80C?.let { put("sec80C", it) }
            o.sec80CCD1B?.let { put("sec80CCD1B", it) }
            o.sec80D?.let { put("sec80D", it) }
            o.rentAnnual?.let { put("rentAnnual", it) }
            put("homeLoanPrincipal", o.homeLoanPrincipal)
            put("homeLoanInterest", o.homeLoanInterest)
            put("basicMonthly", o.basicMonthly)
            put("hraMonthly", o.hraMonthly)
            put("metro", o.metro)
            put("senior", o.senior)
        }.toString()

        fun decodeTax(json: String?): TaxOverrides {
            if (json.isNullOrBlank()) return TaxOverrides()
            return runCatching {
                val o = JSONObject(json)
                fun opt(k: String): Long? = if (o.has(k)) o.getLong(k) else null
                TaxOverrides(
                    grossSalary = opt("grossSalary"),
                    savingsInterest = opt("savingsInterest"),
                    fdInterest = opt("fdInterest"),
                    otherIncome = opt("otherIncome"),
                    sec80C = opt("sec80C"),
                    sec80CCD1B = opt("sec80CCD1B"),
                    sec80D = opt("sec80D"),
                    homeLoanPrincipal = o.optLong("homeLoanPrincipal", 0),
                    homeLoanInterest = o.optLong("homeLoanInterest", 0),
                    basicMonthly = o.optLong("basicMonthly", 0),
                    hraMonthly = o.optLong("hraMonthly", 0),
                    rentAnnual = opt("rentAnnual"),
                    metro = o.optBoolean("metro", true),
                    senior = o.optBoolean("senior", false),
                )
            }.getOrDefault(TaxOverrides())
        }
    }
}
