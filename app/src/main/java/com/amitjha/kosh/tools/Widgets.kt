package com.amitjha.kosh.tools

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.amitjha.kosh.KoshApp
import com.amitjha.kosh.R
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the "This month" widget shows. [barPercent] is spending against the budget, or against income without one. */
data class WidgetFigures(val income: Paise, val spent: Paise, val barPercent: Int) {
    val saved: Paise get() = income - spent

    companion object {
        fun of(period: Period, txns: List<TransactionEntity>, overallBudget: Paise?): WidgetFigures {
            val counted = txns.filter { SmsMapping.countsInStats(it) && Time.toDate(it.timestamp) in period }
            val spent = counted.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
            val income = counted.filter { it.type == TxnType.INCOME }.sumOf { it.amount }
            val base = overallBudget?.takeIf { it > 0 } ?: income.takeIf { it > 0 }
            val pct = if (base == null) 0 else ((spent * 100) / base).toInt().coerceIn(0, 100)
            return WidgetFigures(income, spent, pct)
        }
    }
}

object WidgetUpdater {
    private const val HIDDEN = "₹ ••••"

    /** Amounts appear only without privacy mode, and with app lock on only if the user opted in. */
    fun showAmounts(hideAmounts: Boolean, lockEnabled: Boolean, amountsWhenLocked: Boolean): Boolean =
        !hideAmounts && (!lockEnabled || amountsWhenLocked)

    /** Refreshes the "This month" widgets whenever transactions, settings or widget prefs change. */
    @OptIn(FlowPreview::class)
    fun start(app: KoshApp) {
        app.appScope.launch {
            combine(
                app.container.db.transactions().observeChanges(),
                app.container.settings.settings,
                ToolsSettings.get(app).prefs,
            ) { _, _, _ -> Unit }
                .debounce(1_500)
                .collect { runCatching { updateAll(app) } }
        }
    }

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MonthWidgetProvider::class.java))
        if (ids.isNotEmpty()) updateMonth(context, manager, ids)
    }

    suspend fun updateMonth(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as KoshApp
        val c = app.container
        val settings = c.settings.current()
        val tools = ToolsSettings.get(context).current()
        val period = Period.containing(Time.today(), settings.monthStartDay)
        val txns = c.db.transactions().between(period.startMillis, period.endMillis)
        val budget = c.db.budgets().all().firstOrNull { it.categoryKey == null }?.monthlyLimit
        val f = WidgetFigures.of(period, txns, budget)
        val show = showAmounts(settings.hideAmounts, settings.biometricLockEnabled, tools.widgetAmountsWhenLocked)

        val views = RemoteViews(context.packageName, R.layout.widget_month)
        views.setTextViewText(R.id.widget_title, title(period, settings.monthStartDay))
        views.setTextViewText(R.id.widget_spent, if (show) Money.format(f.spent, showPaise = false) else HIDDEN)
        views.setTextViewText(R.id.widget_income, "Income\n${if (show) Money.compact(f.income) else HIDDEN}")
        views.setTextViewText(R.id.widget_saved, "Saved\n${if (show) Money.compact(f.saved) else HIDDEN}")
        views.setProgressBar(R.id.widget_bar, 100, if (show) f.barPercent else 0, false)
        views.setOnClickPendingIntent(R.id.widget_root, QuickLaunch.pending(context, null, 7_100))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun title(period: Period, monthStartDay: Int): String {
        if (monthStartDay == 1) return "This month"
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        return "${period.start.format(fmt)} – ${period.lastDay.format(fmt)}"
    }
}

/** "This month": spent, income and saved for the current budgeting period. */
class MonthWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { WidgetUpdater.updateMonth(context, manager, ids) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Quick add": one tap to the add-expense or add-income screen. */
class QuickAddWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_add)
        views.setOnClickPendingIntent(R.id.widget_add_expense, QuickLaunch.pending(context, QuickLaunch.ROUTE_ADD_EXPENSE, 7_201))
        views.setOnClickPendingIntent(R.id.widget_add_income, QuickLaunch.pending(context, QuickLaunch.ROUTE_ADD_INCOME, 7_202))
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
