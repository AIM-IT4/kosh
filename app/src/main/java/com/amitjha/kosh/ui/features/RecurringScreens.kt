package com.amitjha.kosh.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.amitjha.kosh.data.RecurringRuleEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.finance.BudgetMath
import com.amitjha.kosh.finance.Recurring
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.ui.common.AmountField
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.DateField
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.FormColumn
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.Pill
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun Recurring.Cadence.title(): String = when (this) {
    Recurring.Cadence.WEEKLY -> "Weekly"
    Recurring.Cadence.MONTHLY -> "Monthly"
    Recurring.Cadence.QUARTERLY -> "Quarterly"
    Recurring.Cadence.YEARLY -> "Yearly"
}

private fun cadenceOf(name: String) = runCatching { Recurring.Cadence.valueOf(name) }.getOrDefault(Recurring.Cadence.MONTHLY)

/** Subscriptions, EMIs and UPI AutoPay mandates found in spending, the user's own schedules, and what's due soon. */
@Composable
fun RecurringScreen(nav: NavController) {
    val c = LocalContainer.current
    val hidden = LocalHideAmounts.current
    val scope = rememberCoroutineScope()
    val today = Time.today()
    val detected by c.recurring.observeDetected().collectAsState(initial = null)
    val rules by c.recurring.observeRules().collectAsState(initial = emptyList())
    val features by c.featurePrefs.settings.collectAsState(initial = null)
    var upcoming by remember { mutableStateOf<List<BudgetMath.Due>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    LaunchedEffect(detected, rules) {
        upcoming = runCatching { c.recurring.upcoming(today, today.plusDays(31)) }.getOrDefault(emptyList())
    }

    KoshScreen(
        title = "Recurring",
        onBack = { nav.popBackSafely() },
        floating = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.editRecurring()) },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Schedule") },
            )
        },
    ) { pad ->
        val series = detected
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val monthly = (series.orEmpty().filter { !it.overdue }.sumOf { it.monthlyEquivalent }) +
                    rules.filter { it.active && it.type == TxnType.EXPENSE }.sumOf { monthlyOf(it) }
                KoshCard {
                    Text("Repeating payments cost about", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    Row(verticalAlignment = Alignment.Bottom) {
                        AmountText(monthly, style = AmountStyles.large, hidden = hidden)
                        Text(" a month", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    }
                    val mandates = series.orEmpty().count { it.autoDebit }
                    if (mandates > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "$mandates paid by auto-debit (UPI AutoPay, NACH or standing instruction). Cancel unused mandates in your UPI app or bank.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText,
                        )
                    }
                }
            }
            item { SectionHeader("Next 30 days") }
            if (upcoming.isEmpty()) {
                item { Text("Nothing expected.", color = KoshTheme.colors.subtleText) }
            } else {
                items(upcoming.take(30)) { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.label, style = MaterialTheme.typography.bodyLarge)
                            val days = ChronoUnit.DAYS.between(today, d.date)
                            Text(
                                if (days == 0L) "Today" else if (days == 1L) "Tomorrow" else Fmt.dayLabel(d.date, today),
                                style = MaterialTheme.typography.bodySmall,
                                color = KoshTheme.colors.subtleText,
                            )
                        }
                        AmountText(d.amount, style = AmountStyles.medium, hidden = hidden)
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)); SectionHeader("Scheduled by you") }
            if (rules.isEmpty()) {
                item {
                    Text(
                        "Schedule rent, SIPs, EMIs or pocket money and Kosh adds them on their dates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
            items(rules, key = { "r${it.id}" }) { r ->
                KoshCard(onClick = { nav.navigate(Routes.editRecurring(r.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                "${cadenceOf(r.cadence).title()} · next ${Fmt.date(LocalDate.ofEpochDay(r.nextDay))}" +
                                    if (!r.active) " · paused" else if (!r.autoAdd) " · reminder only" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoshTheme.colors.subtleText,
                            )
                        }
                        AmountText(r.amount, style = AmountStyles.medium, type = r.type, hidden = hidden)
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)); SectionHeader("Spotted in your spending") }
            when {
                series == null -> item { Text("Looking…", color = KoshTheme.colors.subtleText) }
                series.isEmpty() -> item {
                    EmptyState(
                        "No repeats yet",
                        "Subscriptions and EMIs show up here once Kosh has seen them a few times.",
                    )
                }
                else -> items(series, key = { "s${it.key}" }) { s ->
                    SeriesCard(s, hidden, today,
                        onHide = { scope.launch { c.recurring.setHidden(s.key, true) } },
                        onSchedule = { nav.navigate(Routes.editRecurring(fromSeries = s.key)) })
                }
            }
            val hiddenKeys = features?.hiddenRecurring.orEmpty()
            if (hiddenKeys.isNotEmpty()) {
                item {
                    TextButton(onClick = { showHidden = !showHidden }) {
                        Text(if (showHidden) "Hide the list" else "Not recurring (${hiddenKeys.size})")
                    }
                }
                if (showHidden) {
                    items(hiddenKeys.sorted()) { key ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(key.substringBefore(':').replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { c.recurring.setHidden(key, false) } }) { Text("Show again") }
                        }
                    }
                }
            }
        }
    }
}

private fun monthlyOf(r: RecurringRuleEntity): Long = when (cadenceOf(r.cadence)) {
    Recurring.Cadence.WEEKLY -> r.amount * 52 / 12
    Recurring.Cadence.MONTHLY -> r.amount
    Recurring.Cadence.QUARTERLY -> r.amount / 3
    Recurring.Cadence.YEARLY -> r.amount / 12
}

@Composable
private fun SeriesCard(s: Recurring.Series, hidden: Boolean, today: LocalDate, onHide: () -> Unit, onSchedule: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    KoshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.merchant, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val next = if (s.overdue) "last paid ${Fmt.date(s.lastDate)}" else "next ${Fmt.dayLabel(s.nextDate, today)}"
                Text("${s.cadence.title()} · ${s.occurrences} payments · $next", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
            AmountText(s.lastAmount, style = AmountStyles.medium, hidden = hidden)
            Box0 {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Schedule it") }, onClick = { menu = false; onSchedule() })
                    DropdownMenuItem(text = { Text("Not recurring") }, onClick = { menu = false; onHide() })
                }
            }
        }
        val pills = buildList {
            if (s.autoDebit) add("AutoPay" to KoshTheme.colors.tip)
            s.priceChangePct?.let { pct ->
                add((if (pct > 0) "Price up ${Fmt.pct(pct, 0)}" else "Price down ${Fmt.pct(-pct, 0)}") to (if (pct > 0) KoshTheme.colors.alert else KoshTheme.colors.good))
            }
            if (s.overdue) add("Stopped?" to KoshTheme.colors.warning)
        }
        if (pills.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { pills.forEach { (t, col) -> Pill(t, col) } }
        }
    }
}

@Composable
private fun Box0(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box { content() }

/** Adds or edits a scheduled payment. [fromSeries] prefills it from a detected repeating payment. */
@Composable
fun RecurringEditScreen(nav: NavController, id: Long?, fromSeries: String?) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val today = Time.today()
    val accounts by c.accounts.observeAccounts().collectAsState(initial = emptyList())
    val categories by c.categories.observeAll().collectAsState(initial = emptyList())
    var loaded by remember { mutableStateOf(id == null && fromSeries == null) }
    var existing by remember { mutableStateOf<RecurringRuleEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxnType.EXPENSE) }
    var amount by remember { mutableStateOf<Long?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var toAccountId by remember { mutableStateOf<Long?>(null) }
    var cadence by remember { mutableStateOf(Recurring.Cadence.MONTHLY) }
    var first by remember { mutableStateOf(today) }
    var autoAdd by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id, fromSeries) {
        if (id != null) {
            c.recurring.rule(id)?.let { r ->
                existing = r; name = r.name; type = r.type; amount = r.amount; category = r.categoryKey
                accountId = r.accountId; toAccountId = r.toAccountId; cadence = cadenceOf(r.cadence)
                first = LocalDate.ofEpochDay(r.anchorDay); autoAdd = r.autoAdd; active = r.active; note = r.note.orEmpty()
            }
        } else if (fromSeries != null) {
            c.recurring.detected(today).firstOrNull { it.key == fromSeries }?.let { s ->
                name = s.merchant; amount = s.lastAmount; category = s.category; cadence = s.cadence; first = s.nextDate
                autoAdd = false
            }
        }
        loaded = true
    }
    LaunchedEffect(accounts) {
        if (accountId == null) accountId = accounts.firstOrNull { !it.isPending && !it.isCard }?.id ?: accounts.firstOrNull()?.id
    }

    val usable = accounts.filter { !it.isPending }
    val catOptions = categories.filter { it.type == (if (type == TxnType.TRANSFER) TxnType.EXPENSE else type) }
    val valid = name.isNotBlank() && (amount ?: 0) > 0 && accountId != null &&
        (type == TxnType.TRANSFER && toAccountId != null && toAccountId != accountId || type != TxnType.TRANSFER && category != null)

    KoshScreen(
        title = if (id == null) "Schedule a payment" else "Edit schedule",
        onBack = { nav.popBackSafely() },
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
        },
    ) { pad ->
        if (!loaded) return@KoshScreen
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp)) {
            FormColumn {
                SegmentedTabs(listOf("Expense", "Income", "Transfer"), listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER).indexOf(type),
                    onSelect = { type = listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER)[it]; category = null })
                KoshTextField(name, { name = it.take(60) }, "Name", supporting = "e.g. Rent, SIP, Netflix, Maid")
                AmountField("Amount", amount, { amount = it }, allowZero = false)
                if (type != TxnType.TRANSFER) {
                    ChoiceField("Category", catOptions, catOptions.firstOrNull { it.key == category }, { it.name }, { category = it.key })
                }
                ChoiceField(if (type == TxnType.TRANSFER) "From account" else "Account", usable, usable.firstOrNull { it.id == accountId },
                    { it.account.label() }, { accountId = it.id })
                if (type == TxnType.TRANSFER) {
                    ChoiceField("To account", usable, usable.firstOrNull { it.id == toAccountId }, { it.account.label() }, { toAccountId = it.id })
                }
                ChoiceField("Repeats", Recurring.Cadence.entries, cadence, { it.title() }, { cadence = it })
                DateField(if (existing == null) "First payment" else "Schedule starts", first, { first = it })
                SwitchRow("Add automatically", "Records it on each date. Off: only shows it as upcoming.", autoAdd, { autoAdd = it })
                if (existing != null) SwitchRow("Active", null, active, { active = it })
                KoshTextField(note, { note = it.take(200) }, "Note (optional)")
                Text(
                    "Dates before today are not added; the schedule starts from its next date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                Spacer(Modifier.height(4.dp))
                PrimaryButton("Save", enabled = valid, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        val anchor = first
                        val old = existing
                        val keepNext = old != null && old.anchorDay == anchor.toEpochDay() && old.cadence == cadence.name
                        val next = if (keepNext) old!!.nextDay else Recurring.nextOccurrence(anchor, cadence, today.minusDays(1)).toEpochDay()
                        val rule = RecurringRuleEntity(
                            id = old?.id ?: 0,
                            name = name.trim(),
                            type = type,
                            amount = amount ?: 0,
                            categoryKey = if (type == TxnType.TRANSFER) "transfer" else category ?: "other",
                            accountId = accountId!!,
                            toAccountId = if (type == TxnType.TRANSFER) toAccountId else null,
                            cadence = cadence.name,
                            anchorDay = anchor.toEpochDay(),
                            nextDay = next,
                            active = active,
                            autoAdd = autoAdd,
                            note = note.trim().ifBlank { null },
                            createdAt = old?.createdAt ?: System.currentTimeMillis(),
                        )
                        c.recurring.save(rule)
                        if (rule.active && rule.autoAdd) runCatching { c.recurring.recordDue(today) }
                        nav.popBackSafely()
                    }
                })
                Spacer(Modifier.width(1.dp).height(24.dp))
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete this schedule?",
            "Payments it already added stay in your transactions.",
            "Delete",
            onConfirm = {
                confirmDelete = false
                scope.launch { existing?.let { c.recurring.delete(it) }; nav.popBackSafely() }
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}
