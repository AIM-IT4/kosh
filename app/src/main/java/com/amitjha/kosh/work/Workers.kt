package com.amitjha.kosh.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amitjha.kosh.KoshApp
import com.amitjha.kosh.finance.Severity
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.util.Time
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Daily auto-update: revalues FD/EPF/NPS (fetching NAVs and applying NPS contributions), catches up on SMS,
 * records net worth and posts alert-level insights and FD maturity reminders.
 */
class DailyRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as KoshApp).container
        val settings = c.settings.current()
        val today = Time.today()

        runCatching { c.investments.revalueAll(today) }
        if (settings.smsAutoImport && c.smsImporter.hasPermission()) runCatching { c.smsImporter.importInbox() }
        runCatching { c.netWorth.snapshot(today) }

        if (settings.notifyInsights) {
            runCatching {
                val alerts = c.insights.generate().filter { it.severity == Severity.ALERT }
                val fresh = c.settings.claimNewInsightNotifications(alerts.map { it.id })
                alerts.filter { it.id in fresh }.take(3).forEach { c.notifications.insight(it.id.hashCode() and 0xFFFF, it.title, it.message) }
            }
            runCatching {
                val maturing = c.investments.items().filter { it.type == InvestmentType.FD && it.daysToMaturity(today) in 0L..7L }
                val fresh = c.settings.claimNewInsightNotifications(maturing.map { "fd-maturity-${it.id}-${it.entity.maturityDate}" })
                maturing.filter { "fd-maturity-${it.id}-${it.entity.maturityDate}" in fresh }.forEach { fd ->
                    c.notifications.maturity(
                        fd.id.toInt(),
                        "${fd.entity.name} matures ${if (fd.daysToMaturity(today) == 0L) "today" else "in ${fd.daysToMaturity(today)} days"}",
                        "Maturity value about ${Money.format(fd.value, showPaise = false)}. Decide whether to renew or move it.",
                    )
                }
            }
        }
        return Result.success()
    }
}

/** Card bill reminders 3 days and 1 day before the due date, on the due date, and when overdue. */
class BillReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as KoshApp).container
        val settings = c.settings.current()
        if (!settings.notifyBills) return Result.success()
        val due = c.bills.unpaid().filter { it.daysLeft != null && (it.daysLeft in REMIND_DAYS || it.daysLeft < 0) }
        val keys = due.associateBy { "bill-${it.bill.id}-${if (it.daysLeft!! < 0) "overdue" else it.daysLeft}" }
        val fresh = c.settings.claimNewBillNotifications(keys.keys)
        for ((key, item) in keys) {
            if (key !in fresh) continue
            val card = item.card?.name ?: "Card"
            val amount = if (settings.hideAmounts) "Your bill" else Money.format(item.bill.totalDue, showPaise = false)
            val title = when (val d = item.daysLeft!!) {
                0L -> "$card bill due today"
                1L -> "$card bill due tomorrow"
                in 2L..Long.MAX_VALUE -> "$card bill due in $d days"
                else -> "$card bill is overdue"
            }
            val min = item.bill.minimumDue?.takeIf { !settings.hideAmounts }?.let { " (minimum ${Money.format(it, showPaise = false)})" } ?: ""
            c.notifications.bill(item.bill.id.toInt(), title, "$amount$min. Pay on time to avoid interest and late fees.")
        }
        return Result.success()
    }

    companion object {
        val REMIND_DAYS = setOf(3L, 1L, 0L)
    }
}

object WorkScheduler {
    private const val DAILY = "kosh-daily-refresh"
    private const val BILLS = "kosh-bill-reminders"
    private const val SYNC_NOW = "kosh-sync-now"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            DAILY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyRefreshWorker>(Duration.ofHours(12))
                .setInitialDelay(Duration.ofMinutes(15))
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            BILLS,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BillReminderWorker>(Duration.ofDays(1))
                .setInitialDelay(delayUntil(LocalTime.of(9, 0)))
                .build(),
        )
    }

    /** Runs the full refresh once, now. */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DailyRefreshWorker>().build(),
        )
    }

    private fun delayUntil(time: LocalTime): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
