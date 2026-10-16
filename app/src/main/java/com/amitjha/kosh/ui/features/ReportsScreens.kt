package com.amitjha.kosh.ui.features

import com.amitjha.kosh.ui.common.NO_FILE_PICKER
import com.amitjha.kosh.ui.common.launchOrFalse
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.MonthSwitcher
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.reports.ReportPdf
import com.amitjha.kosh.repo.Report
import com.amitjha.kosh.repo.TxnFilter
import com.amitjha.kosh.repo.YearReview
import com.amitjha.kosh.ui.common.ActionRow
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.home.TxnRow
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.ui.settings.Section
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Reports: PDF exports, the year in review and the spending calendar. */
@Composable
fun ReportsScreen(nav: NavController) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val today = Time.today()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var fy by remember { mutableStateOf(false) }
    var year by remember { mutableIntStateOf(today.year) }
    var pending by remember { mutableStateOf<Report?>(null) }
    var busy by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val report = pending
        pending = null
        if (uri == null || report == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { ReportPdf.write(report, it) } ?: error("Can't write the file")
                }
            }
            snackbar.showSnackbar(r.map { "Report saved" }.getOrElse { "Couldn't save the report: ${it.message}" })
        }
    }

    fun export(name: String, load: suspend () -> Report) {
        if (busy) return
        busy = true
        scope.launch {
            val r = runCatching { load() }
            busy = false
            r.onSuccess {
                pending = it
                if (!save.launchOrFalse(name)) {
                    pending = null
                    snackbar.showSnackbar(NO_FILE_PICKER)
                }
            }.onFailure { snackbar.showSnackbar("Couldn't build the report: ${it.message}") }
        }
    }

    KoshScreen(title = "Reports", onBack = { nav.popBackSafely() }, snackbar = snackbar) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)) {
            item {
                Section("Monthly PDF") {
                    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MonthSwitcher(
                            label = Fmt.month(month),
                            onPrevious = { month = month.minusMonths(1) },
                            onNext = { month = month.plusMonths(1) },
                            canGoNext = month.isBefore(YearMonth.from(today)),
                        )
                        Text(
                            "Income, spending by category, top merchants and every transaction of the month.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText,
                        )
                        PrimaryButton(if (busy) "Preparing…" else "Save PDF", enabled = !busy, icon = Icons.Outlined.PictureAsPdf, modifier = Modifier.fillMaxWidth(), onClick = {
                            export("kosh-$month.pdf") { c.reports.monthly(month) }
                        })
                    }
                }
            }
            item {
                Section("Yearly PDF") {
                    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SegmentedTabs(listOf("Calendar year", "Financial year"), if (fy) 1 else 0, onSelect = { fy = it == 1 })
                        val maxYear = if (fy) (if (today.monthValue >= 4) today.year else today.year - 1) else today.year
                        if (year > maxYear) year = maxYear
                        MonthSwitcher(
                            label = if (fy) "FY $year-${(year + 1) % 100}" else "$year",
                            onPrevious = { year-- },
                            onNext = { year++ },
                            canGoNext = year < maxYear,
                        )
                        PrimaryButton(if (busy) "Preparing…" else "Save PDF", enabled = !busy, icon = Icons.Outlined.PictureAsPdf, modifier = Modifier.fillMaxWidth(), onClick = {
                            export(if (fy) "kosh-fy-$year.pdf" else "kosh-$year.pdf") { c.reports.yearly(year, fy) }
                        })
                    }
                }
            }
            item {
                Section("Explore") {
                    ActionRow("Year in review", "Your ${today.year} in numbers", Icons.Outlined.Celebration) { nav.navigate(Routes.yearReview(today.year)) }
                    ActionRow("Calendar", "Spending by day and what's coming up", Icons.Outlined.CalendarMonth) { nav.navigate(Routes.CALENDAR) }
                }
            }
        }
    }
}

@Composable
fun YearReviewScreen(nav: NavController, initialYear: Int) {
    val c = LocalContainer.current
    val hidden = LocalHideAmounts.current
    var year by remember { mutableIntStateOf(initialYear) }
    var review by remember { mutableStateOf<YearReview?>(null) }
    LaunchedEffect(year) {
        review = null
        review = runCatching { c.reports.yearInReview(year) }.getOrNull()
    }
    KoshScreen(title = "Year in review", onBack = { nav.popBackSafely() }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MonthSwitcher("$year", onPrevious = { year-- }, onNext = { year++ }, canGoNext = year < Time.today().year)
                }
            }
            val r = review
            if (r == null) {
                item { Text("Adding it up…", color = KoshTheme.colors.subtleText, modifier = Modifier.padding(16.dp)) }
                return@LazyColumn
            }
            if (r.transactions == 0) {
                item { Text("No transactions in $year.", color = KoshTheme.colors.subtleText, modifier = Modifier.padding(16.dp)) }
                return@LazyColumn
            }
            item {
                KoshCard {
                    Text("You saved", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    AmountText(r.saved, style = AmountStyles.hero, hidden = hidden)
                    r.savingsRate?.let {
                        Text("${Fmt.pct(it * 100, 0)} of what came in", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    }
                    Spacer(Modifier.height(12.dp))
                    Fact("Came in", money(r.income, hidden))
                    Fact("Went out", money(r.expense, hidden))
                    Fact("Transactions", r.transactions.toString())
                }
            }
            item {
                KoshCard {
                    SectionHeader("Highlights")
                    r.bestMonth?.let { Fact("Best month for saving", Fmt.month(it.period.label) + " · " + money(it.savings, hidden)) }
                    r.costliestMonth?.let { Fact("Biggest spending month", Fmt.month(it.period.label) + " · " + money(it.expense, hidden)) }
                    r.biggestExpense?.let { Fact("Largest payment", "${it.title} · ${money(it.amount, hidden)} on ${Fmt.date(it.date)}") }
                    r.busiestWeekday?.let { Fact("You spend most on", it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s") }
                    if (r.daysCounted > 0) Fact("No-spend days", "${r.noSpendDays} of ${r.daysCounted}")
                    if (r.subscriptionsMonthly > 0) Fact("Subscriptions & repeats", money(r.subscriptionsMonthly, hidden) + " a month")
                }
            }
            if (r.topCategories.isNotEmpty()) {
                item {
                    KoshCard {
                        SectionHeader("Where it went")
                        r.topCategories.forEach { Fact(it.category?.name ?: it.categoryKey, money(it.total, hidden) + " · " + Fmt.pct(it.share * 100, 0)) }
                    }
                }
            }
            if (r.topMerchants.isNotEmpty()) {
                item {
                    KoshCard {
                        SectionHeader("Favourite places")
                        r.topMerchants.forEach { Fact(it.merchant, "${money(it.total, hidden)} · ${it.count}×") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
        Text(value, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

private fun money(p: Paise, hidden: Boolean) = if (hidden) "₹ ••••" else Money.format(p, showPaise = false)

/** A month grid of daily spending, with scheduled and expected payments on future days. */
@Composable
fun CalendarScreen(nav: NavController) {
    val c = LocalContainer.current
    val hidden = LocalHideAmounts.current
    val today = Time.today()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    var spend by remember { mutableStateOf<Map<LocalDate, Paise>>(emptyMap()) }
    var dues by remember { mutableStateOf<Map<LocalDate, List<com.amitjha.kosh.finance.BudgetMath.Due>>>(emptyMap()) }
    LaunchedEffect(month) {
        val from = month.atDay(1)
        val to = month.plusMonths(1).atDay(1)
        spend = runCatching { c.reports.dailySpend(from, to) }.getOrDefault(emptyMap())
        dues = runCatching {
            if (to.isAfter(today)) c.recurring.upcoming(maxOf(today, from), to).groupBy { it.date } else emptyMap()
        }.getOrDefault(emptyMap())
    }
    val dayItems by remember(selected) {
        c.transactions.observe(TxnFilter(period = Period(selected, selected.plusDays(1))))
    }.collectAsState(initial = emptyList())

    KoshScreen(title = "Calendar", onBack = { nav.popBackSafely() }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
            item {
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    MonthSwitcher(Fmt.month(month), onPrevious = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })
                }
            }
            item { MonthGrid(month, today, selected, spend, dues.keys, hidden) { selected = it } }
            item {
                val total = spend.values.sum()
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Spent in ${Fmt.shortMonth(month)}", Modifier.weight(1f), color = KoshTheme.colors.subtleText)
                    AmountText(total, style = AmountStyles.medium, hidden = hidden)
                }
            }
            item { Spacer(Modifier.height(16.dp)); SectionHeader(Fmt.dayLabel(selected, today)) }
            val dayDues = dues[selected].orEmpty()
            items(dayDues) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.label, style = MaterialTheme.typography.bodyLarge)
                        Text("Expected", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.warning)
                    }
                    AmountText(d.amount, style = AmountStyles.medium, hidden = hidden)
                }
            }
            items(dayItems, key = { it.txn.id }) { t -> TxnRow(t, hidden) { nav.navigate(Routes.txn(t.txn.id)) } }
            if (dayItems.isEmpty() && dayDues.isEmpty()) {
                item { Text("Nothing on this day.", color = KoshTheme.colors.subtleText, modifier = Modifier.padding(vertical = 12.dp)) }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    spend: Map<LocalDate, Paise>,
    dueDays: Set<LocalDate>,
    hidden: Boolean,
    onDay: (LocalDate) -> Unit,
) {
    val colors = KoshTheme.colors
    val max = spend.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = colors.subtleText)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until 7).forEach { i ->
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(0.8f)) {
                        if (day != null) {
                            val amount = spend[day] ?: 0
                            val level = amount.toFloat() / max
                            val bg = when {
                                day == selected -> colors.ink
                                amount > 0 -> colors.expense.copy(alpha = 0.10f + 0.45f * level)
                                else -> colors.fill
                            }
                            val fg = if (day == selected) colors.onInk else MaterialTheme.colorScheme.onSurface
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(bg).clickable { onDay(day) }.padding(3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    day.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                    color = fg,
                                )
                                Spacer(Modifier.weight(1f))
                                if (amount > 0 && !hidden) {
                                    Text(Money.compact(amount), style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
                                }
                                if (day in dueDays) {
                                    Box(Modifier.padding(top = 2.dp).height(4.dp).fillMaxWidth(0.4f).clip(RoundedCornerShape(2.dp)).background(colors.warning))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
