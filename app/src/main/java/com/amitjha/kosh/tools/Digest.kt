package com.amitjha.kosh.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amitjha.kosh.KoshApp
import com.amitjha.kosh.R
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The numbers in one daily digest. [budgetLeft] is null when no overall monthly budget is set. */
data class DigestFigures(val spentToday: Paise, val budgetLeft: Paise?, val toReview: Int)

object DigestText {
    /**
     * "Today ₹X spent · ₹Y left in budget · N to review", with amounts masked in privacy mode. [smsProblem] says why
     * new bank SMS may be missing, so a ₹0 day that isn't real doesn't pass for one.
     */
    fun build(f: DigestFigures, hideAmounts: Boolean, smsProblem: String? = null): String {
        fun money(p: Paise) = if (hideAmounts) "₹ ••••" else Money.format(p, showPaise = false)
        val parts = mutableListOf("Today ${money(f.spentToday)} spent")
        f.budgetLeft?.let { left ->
            parts += if (left >= 0) "${money(left)} left in budget" else "${money(-left)} over budget"
        }
        parts += "${f.toReview} to review"
        val text = parts.joinToString(" · ")
        return if (smsProblem == null) text else "$text\n$smsProblem. Open Kosh › More › SMS check-up."
    }

    /**
     * Today's spending, what's left of the overall budget this period and how many transactions await review.
     */
    fun figures(
        today: LocalDate,
        periodTxns: List<TransactionEntity>,
        overallBudget: Paise?,
        toReview: Int,
        zone: ZoneId = Time.zone,
    ): DigestFigures {
        val expenses = periodTxns.filter { it.type == TxnType.EXPENSE && SmsMapping.countsInStats(it) }
        fun dateOf(t: TransactionEntity) = java.time.Instant.ofEpochMilli(t.timestamp).atZone(zone).toLocalDate()
        val spentToday = expenses.filter { dateOf(it) == today }.sumOf { it.amount }
        val givenBack = periodTxns.filter { com.amitjha.kosh.domain.MoneyIn.isGivenBack(it) && SmsMapping.countsInStats(it) }
        val left = overallBudget?.let { it - com.amitjha.kosh.domain.MoneyIn.netSpent(expenses, givenBack) }
        return DigestFigures(spentToday, left, toReview)
    }
}

object DigestSchedule {
    private const val WORK = "kosh-daily-digest"

    /**
     * The next time the digest should run strictly after [now], at [minutes] after midnight. Uses zoned times
     * so DST gaps resolve to a real instant.
     */
    fun nextRun(now: ZonedDateTime, minutes: Int): ZonedDateTime {
        val time = LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))
        var next = now.toLocalDate().atTime(time).atZone(now.zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
        return next
    }

    /** Schedules (or cancels) the next digest according to the saved preferences. */
    suspend fun sync(context: Context) {
        val prefs = ToolsSettings.get(context).current()
        if (prefs.digestEnabled) schedule(context, prefs.digestMinutes) else cancel(context)
    }

    fun schedule(context: Context, minutes: Int) {
        val now = ZonedDateTime.now()
        val delay = Duration.between(now, nextRun(now, minutes)).toMillis().coerceAtLeast(0)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DigestWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

/** Posts the daily digest and schedules the next one. Always reads the real profile. */
class DigestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = ToolsSettings.get(applicationContext).current()
        if (!prefs.digestEnabled) return Result.success()
        // A worker that ran late (Doze) still posts once, then lines up the next run at the chosen time.
        runCatching { post(prefs) }
        DigestSchedule.schedule(applicationContext, prefs.digestMinutes)
        return Result.success()
    }

    private suspend fun post(prefs: ToolsPrefs) {
        val c = (applicationContext as KoshApp).container
        if (!c.notifications.canNotify()) return
        // Count today's spends even when the live SMS receiver missed them in the background.
        val sms = com.amitjha.kosh.sms.SmsCatchUp.run(c, alert = true)
        val settings = c.settings.current()
        val today = Time.today()
        val period = Period.containing(today, settings.payCycle)
        val txns = c.db.transactions().between(period.startMillis, period.endMillis)
        val budget = c.db.budgets().all().firstOrNull { it.categoryKey == null }?.monthlyLimit
        val review = c.db.transactions().observeUnreviewed().first().size
        val smsNote = sms?.problem ?: "Bank SMS import is off".takeIf { !settings.smsAutoImport && c.smsImporter.hasPermission() }
        val text = DigestText.build(DigestText.figures(today, txns, budget, review), settings.hideAmounts, smsNote)
        notify(applicationContext, text)
    }

    companion object {
        const val CHANNEL = "digest"
        private const val NOTIF_ID = 600_001

        fun notify(context: Context, text: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Daily digest", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "A short summary of the day, once a day" },
                )
            }
            val open = QuickLaunch.pending(context, null, NOTIF_ID)
            val publicVersion = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Kosh")
                .setContentText("Your daily summary is ready")
                .setColor(0xFF4C3BE0.toInt())
                .build()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Daily digest")
                .setContentText(text.substringBefore('\n'))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setColor(0xFF4C3BE0.toInt())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
            } catch (_: SecurityException) {
                // Notification permission was revoked.
            }
        }
    }
}
