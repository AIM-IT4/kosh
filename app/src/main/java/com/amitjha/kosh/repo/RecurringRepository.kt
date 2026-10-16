package com.amitjha.kosh.repo

import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.RecurringRuleEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.data.prefs.FeaturePrefs
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.finance.BudgetMath
import com.amitjha.kosh.finance.Recurring
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/**
 * Recurring payments: the ones Kosh spots in past spending (subscriptions, EMIs, UPI AutoPay) and the ones the user
 * schedules, which the daily refresh records as transactions when they fall due.
 */
class RecurringRepository(private val db: KoshDatabase, private val features: FeaturePrefs) {

    fun observeRules(): Flow<List<RecurringRuleEntity>> = db.recurringRules().observeAll()

    suspend fun rule(id: Long): RecurringRuleEntity? = db.recurringRules().byId(id)

    suspend fun save(rule: RecurringRuleEntity): Long = db.recurringRules().upsert(rule)

    suspend fun delete(rule: RecurringRuleEntity) = db.recurringRules().delete(rule)

    /**
     * Records every occurrence of the scheduled payments due on or before [today] (at most [MAX_CATCH_UP] per rule
     * per run) and moves each rule to its next date. The `rec:<rule>:<day>` fingerprint keeps an occurrence from
     * ever being recorded twice. Returns how many transactions were added.
     */
    suspend fun recordDue(today: LocalDate = Time.today()): Int {
        var added = 0
        for (rule in db.recurringRules().due(today.toEpochDay())) {
            val cadence = cadenceOf(rule) ?: continue
            val anchor = LocalDate.ofEpochDay(rule.anchorDay)
            var day = LocalDate.ofEpochDay(rule.nextDay)
            var n = 0
            while (!day.isAfter(today) && n < MAX_CATCH_UP) {
                val transfer = rule.type == TxnType.TRANSFER && rule.toAccountId != null
                val id = db.transactions().insertIgnore(
                    TransactionEntity(
                        accountId = rule.accountId,
                        toAccountId = if (transfer) rule.toAccountId else null,
                        type = if (rule.type == TxnType.TRANSFER && !transfer) TxnType.EXPENSE else rule.type,
                        amount = rule.amount,
                        categoryKey = if (transfer) SmsMapping.TRANSFER_CATEGORY else rule.categoryKey,
                        merchant = rule.name,
                        note = rule.note,
                        timestamp = Time.startOfDay(day) + RECORD_HOUR_MILLIS,
                        channel = TxnChannel.OTHER,
                        source = TxnSource.AUTO,
                        fingerprint = "rec:${rule.id}:${day.toEpochDay()}",
                        reviewed = true,
                        excludeFromStats = transfer,
                    ),
                )
                if (id > 0) added++
                day = Recurring.nextOccurrence(anchor, cadence, day)
                n++
            }
            db.recurringRules().update(rule.copy(nextDay = day.toEpochDay()))
        }
        return added
    }

    /** Reminder-only schedules due on or before [today]; [advance] moves each past today once it has been shown. */
    suspend fun remindersDue(today: LocalDate = Time.today()): List<RecurringRuleEntity> =
        db.recurringRules().all().filter { it.active && !it.autoAdd && it.nextDay <= today.toEpochDay() }

    suspend fun advance(rule: RecurringRuleEntity, today: LocalDate = Time.today()) {
        val cadence = cadenceOf(rule) ?: return
        db.recurringRules().update(rule.copy(nextDay = Recurring.nextOccurrence(LocalDate.ofEpochDay(rule.anchorDay), cadence, today).toEpochDay()))
    }

    /** Repeating payments found in the last ~13 months of spending, without the ones the user hid. */
    fun observeDetected(): Flow<List<Recurring.Series>> =
        combine(db.transactions().observeBetween(Time.startOfDay(Time.today().minusDays(LOOKBACK_DAYS)), Long.MAX_VALUE), features.settings) { txns, f ->
            detect(txns, Time.today()).filterNot { it.key in f.hiddenRecurring }
        }

    suspend fun detected(today: LocalDate = Time.today()): List<Recurring.Series> {
        val hidden = features.current().hiddenRecurring
        val txns = db.transactions().since(Time.startOfDay(today.minusDays(LOOKBACK_DAYS)))
        return detect(txns, today).filterNot { it.key in hidden }
    }

    suspend fun setHidden(key: String, hidden: Boolean) = features.setRecurringHidden(key, hidden)

    /**
     * Payments expected from [today] until [until] (exclusive): scheduled expenses not yet recorded, and detected
     * repeating payments by their next expected date. A detected payment with the same name as a schedule is left
     * out so nothing counts twice.
     */
    suspend fun upcoming(today: LocalDate, until: LocalDate): List<BudgetMath.Due> {
        val rules = db.recurringRules().all().filter { it.active && it.type == TxnType.EXPENSE }
        val fromRules = rules.flatMap { r ->
            val cadence = cadenceOf(r) ?: return@flatMap emptyList()
            val from = maxOf(today, LocalDate.ofEpochDay(r.nextDay))
            if (!from.isBefore(until)) return@flatMap emptyList()
            Recurring.occurrences(LocalDate.ofEpochDay(r.anchorDay), cadence, from, until.minusDays(1))
                .map { BudgetMath.Due(it, r.amount, r.name) }
        }
        val scheduledNames = rules.map { it.name.trim().lowercase() }.toSet()
        val fromDetected = detected(today)
            .filter { !it.nextDate.isBefore(today) && it.nextDate.isBefore(until) && it.merchant.trim().lowercase() !in scheduledNames }
            .map { BudgetMath.Due(it.nextDate, it.lastAmount, it.merchant) }
        return (fromRules + fromDetected).sortedBy { it.date }
    }

    private fun detect(txns: List<TransactionEntity>, today: LocalDate): List<Recurring.Series> =
        Recurring.detect(
            txns.filter { it.type == TxnType.EXPENSE && SmsMapping.countsInStats(it) && !it.merchant.isNullOrBlank() && !isScheduled(it) }
                .map {
                    Recurring.Payment(
                        date = Time.toDate(it.timestamp),
                        amount = it.amount,
                        merchant = it.merchant!!,
                        category = it.categoryKey,
                        accountId = it.accountId,
                        autoDebit = it.channel == TxnChannel.AUTO_DEBIT,
                    )
                },
            today,
        )

    /** Occurrences Kosh recorded from a schedule are already known; they aren't "detected" again. */
    private fun isScheduled(t: TransactionEntity) = t.fingerprint?.startsWith("rec:") == true

    private fun cadenceOf(rule: RecurringRuleEntity): Recurring.Cadence? =
        runCatching { Recurring.Cadence.valueOf(rule.cadence) }.getOrNull()

    companion object {
        const val MAX_CATCH_UP = 60
        const val LOOKBACK_DAYS = 400L
        private const val RECORD_HOUR_MILLIS = 9L * 60 * 60 * 1000
    }
}
