package com.amitjha.kosh.ui.wealth

import com.amitjha.kosh.ui.nav.popBackSafely
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.ChartPoint
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.GradientCard
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.LineAreaChart
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.domain.InvestmentSpec
import com.amitjha.kosh.finance.Compounding
import com.amitjha.kosh.finance.EpfAccount
import com.amitjha.kosh.finance.FdPayout
import com.amitjha.kosh.finance.FdPayouts
import com.amitjha.kosh.finance.SeenPayout
import com.amitjha.kosh.finance.NavQuote
import com.amitjha.kosh.finance.NpsHolding
import com.amitjha.kosh.finance.PayoutFrequency
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.repo.InvestmentItem
import com.amitjha.kosh.ui.accounts.BankPicker
import com.amitjha.kosh.ui.common.AmountField
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.DateField
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.FormColumn
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

// ---------------------------------------------------------------- edit

class InvestmentEditViewModel(private val c: AppContainer) : ViewModel() {
    suspend fun load(id: Long): InvestmentItem? = c.investments.byId(id)

    /** Bank accounts an FD can pay its interest into. */
    val bankAccounts: StateFlow<List<com.amitjha.kosh.repo.AccountWithBalance>> = c.accounts.observeAccounts()
        .map { list -> list.filter { it.account.type == com.amitjha.kosh.model.AccountType.BANK && !it.isPending } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    suspend fun search(q: String): List<NavQuote> = runCatching { c.investments.searchNpsSchemes(q) }.getOrDefault(emptyList())
    suspend fun nav(code: String): NavQuote? = runCatching { c.investments.latestNav(code) }.getOrNull()

    fun save(existingId: Long?, name: String, institution: String, spec: InvestmentSpec, onDone: () -> Unit) = viewModelScope.launch {
        when {
            existingId != null -> c.investments.update(existingId, name, institution, spec)
            spec is InvestmentSpec.Fd -> c.investments.addFd(name, institution, spec)
            spec is InvestmentSpec.Epf -> c.investments.addEpf(name, spec)
            spec is InvestmentSpec.Nps -> c.investments.addNps(name, spec)
            spec is com.amitjha.kosh.domain.NpsSimple -> c.investments.addNpsSimple(name, spec)
        }
        c.netWorth.snapshot()
        onDone()
    }
}

private fun rate(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

@Composable
fun InvestmentEditScreen(nav: NavController, editId: Long?, type: InvestmentType?) {
    val vm = koshViewModel(key = "inv-edit-$editId-$type") { InvestmentEditViewModel(it) }
    var loaded by remember { mutableStateOf(editId == null) }
    var item by remember { mutableStateOf<InvestmentItem?>(null) }
    LaunchedEffect(editId) {
        if (editId != null) {
            item = vm.load(editId)
            loaded = true
        }
    }
    val kind = item?.type ?: type ?: InvestmentType.FD
    KoshScreen(
        title = (if (editId == null) "Add " else "Edit ") + when (kind) {
            InvestmentType.FD -> "fixed deposit"
            InvestmentType.EPF -> "EPF"
            InvestmentType.NPS -> "NPS"
        },
        onBack = { nav.popBackSafely() },
    ) { padding ->
        if (!loaded) return@KoshScreen
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            val done: () -> Unit = { nav.popBackSafely() }
            when (kind) {
                InvestmentType.FD -> {
                    val accounts by vm.bankAccounts.collectAsStateWithLifecycle()
                    FdForm(item, accounts, onSave = { name, inst, spec -> vm.save(editId, name, inst, spec, done) })
                }
                InvestmentType.EPF -> EpfForm(item, onSave = { name, spec -> vm.save(editId, name, "EPFO", spec, done) })
                InvestmentType.NPS -> NpsEntry(item, vm, onSave = { name, spec -> vm.save(editId, name, "NPS", spec, done) })
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FdForm(item: InvestmentItem?, accounts: List<com.amitjha.kosh.repo.AccountWithBalance>, onSave: (String, String, InvestmentSpec.Fd) -> Unit) {
    val spec = item?.spec as? InvestmentSpec.Fd
    var bank by remember { mutableStateOf(Bank.entries.firstOrNull { item?.entity?.institution == it.shortName || item?.entity?.institution == it.displayName } ?: Bank.HDFC) }
    var name by remember { mutableStateOf(item?.entity?.name ?: "") }
    var principal by remember { mutableStateOf(spec?.principal) }
    var rateText by remember { mutableStateOf(spec?.annualRatePct?.toString() ?: "7.0") }
    var start by remember { mutableStateOf(spec?.startDate ?: Time.today()) }
    var maturity by remember { mutableStateOf(spec?.maturityDate ?: Time.today().plusYears(1)) }
    var compounding by remember { mutableStateOf(spec?.compounding ?: Compounding.QUARTERLY) }
    var payout by remember { mutableStateOf(spec?.payout ?: FdPayout.CUMULATIVE) }
    var frequency by remember { mutableStateOf(spec?.payoutFrequency ?: PayoutFrequency.MONTHLY) }
    var bankAmount by remember { mutableStateOf(spec?.payoutAmount) }
    var dayText by remember { mutableStateOf(spec?.payoutDay?.toString().orEmpty()) }
    var payoutAccountId by remember { mutableStateOf(spec?.payoutAccountId) }
    val haptic = rememberHaptic()

    val r = rate(rateText)
    val paysOut = payout == FdPayout.INTEREST_PAYOUT
    val draft = if (principal != null && principal!! > 0 && r != null && r > 0 && maturity.isAfter(start)) {
        InvestmentSpec.Fd(
            principal!!, r, start, maturity, compounding, payout,
            payoutFrequency = frequency,
            payoutAmount = bankAmount.takeIf { paysOut },
            payoutDay = dayText.toIntOrNull()?.coerceIn(1, 31)?.takeIf { paysOut },
            payoutAccountId = payoutAccountId.takeIf { paysOut },
        )
    } else {
        null
    }
    FormColumn {
        Text("Bank", style = MaterialTheme.typography.labelLarge, color = KoshTheme.colors.subtleText)
        BankPicker(bank) { bank = it }
        KoshTextField(name, { name = it }, "Name (optional)", supporting = "e.g. Tax saver FD")
        AmountField("Deposit amount", principal, { principal = it }, allowZero = false)
        KoshTextField(rateText, { rateText = it }, "Interest rate", keyboardType = KeyboardType.Decimal, suffix = "% p.a.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Start", start, { start = it }, Modifier.weight(1f))
            DateField("Maturity", maturity, { maturity = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 12, 24, 36, 60).forEach { m ->
                Text(
                    if (m < 12) "${m}m" else "${m / 12}y",
                    modifier = Modifier
                        .clickable { maturity = start.plusMonths(m.toLong()) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        ChoiceField("Compounding", Compounding.entries.toList(), compounding, { it.name.lowercase().replace('_', '-').replaceFirstChar(Char::titlecase) }, { compounding = it })
        ChoiceField("Interest", FdPayout.entries.toList(), payout, { if (it == FdPayout.CUMULATIVE) "Reinvested (cumulative)" else "Paid out" }, { payout = it })
        if (paysOut) {
            ChoiceField("Interest is credited", PayoutFrequency.entries.toList(), frequency, { it.label() }, { frequency = it })
            val estimate = draft?.toFinance(bank.shortName, name)?.copy(payoutAmount = null)?.computedPayout()
            AmountField(
                "Amount per credit (from your bank)",
                bankAmount,
                { bankAmount = it },
                allowZero = false,
                placeholder = estimate?.let { "About ${Money.format(it)} · tap to enter yours" } ?: "Tap to enter",
            )
            if (bankAmount != null) {
                Text(
                    "Use Kosh's estimate instead",
                    modifier = Modifier.clickable { bankAmount = null }.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            KoshTextField(
                dayText,
                { v -> dayText = v.filter(Char::isDigit).take(2) },
                "Credited on day (optional)",
                keyboardType = KeyboardType.Number,
                supporting = "Leave empty if it comes on the deposit's date (the ${com.amitjha.kosh.ui.accounts.ordinal(start.dayOfMonth)})",
            )
            if (accounts.isNotEmpty()) {
                val options = listOf<Long?>(null) + accounts.map { it.id }
                ChoiceField(
                    "Credited to",
                    options,
                    payoutAccountId,
                    { id -> accounts.firstOrNull { it.id == id }?.account?.let { it.name + (it.last4?.let { d -> " ••$d" } ?: "") } ?: "Not set" },
                    { payoutAccountId = it },
                    placeholder = "Choose the account",
                )
            }
        }
        if (draft != null) {
            val fd = draft.toFinance(bank.shortName, name)
            KoshCard(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                if (paysOut) {
                    val schedule = fd.payoutSchedule()
                    Text("You get", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                    Row(verticalAlignment = Alignment.Bottom) {
                        AmountText(fd.regularPayout(), style = AmountStyles.large, showPaise = true)
                        Text(" " + frequency.per(), style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    }
                    schedule.firstOrNull()?.let {
                        Text("First credit ${Fmt.fullDate(it.date)}", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    }
                    Text(
                        "${Money.format(schedule.sumOf { it.amount })} interest in ${schedule.size} credits · ${Money.format(fd.principal)} back on ${Fmt.fullDate(fd.maturityDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                } else {
                    Text("At maturity", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                    AmountText(fd.maturityValue(), style = AmountStyles.large, showPaise = true)
                    Text("Value today ${Money.format(fd.valueOn(Time.today()))}", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                }
            }
        }
        PrimaryButton("Save", enabled = draft != null, modifier = Modifier.fillMaxWidth(), onClick = {
            haptic()
            onSave(name.trim().ifEmpty { "${bank.shortName} FD" }, bank.shortName, draft!!)
        })
    }
}

@Composable
private fun EpfForm(item: InvestmentItem?, onSave: (String, InvestmentSpec.Epf) -> Unit) {
    val spec = item?.spec as? InvestmentSpec.Epf
    var name by remember { mutableStateOf(item?.entity?.name ?: "EPF") }
    var balance by remember { mutableStateOf(spec?.balanceAsOf) }
    var asOf by remember { mutableStateOf(spec?.asOfDate ?: Time.today()) }
    var employee by remember { mutableStateOf(spec?.monthlyEmployee) }
    var employer by remember { mutableStateOf(spec?.monthlyEmployer) }
    var rateText by remember { mutableStateOf((spec?.annualRatePct ?: EpfAccount.DEFAULT_EPF_RATE_PCT).toString()) }
    var day by remember { mutableStateOf((spec?.contributionDay ?: 15).toString()) }
    var includesInterest by remember { mutableStateOf(spec?.includesCurrentYearInterest ?: false) }
    val haptic = rememberHaptic()
    val r = rate(rateText)
    val draft = if (balance != null && r != null) {
        InvestmentSpec.Epf(balance!!, asOf, employee ?: 0, employer ?: 0, r, day.toIntOrNull()?.coerceIn(1, 28) ?: 15, includesInterest)
    } else {
        null
    }
    FormColumn {
        KoshTextField(name, { name = it }, "Name")
        AmountField("Balance (from your EPF passbook)", balance, { balance = it })
        DateField("Balance as of", asOf, { asOf = it })
        AmountField("Your monthly contribution", employee, { employee = it })
        AmountField("Employer's EPF share per month", employer, { employer = it })
        Text(
            "Employer share is usually 3.67% of basic pay (the remaining 8.33% goes to EPS, which isn't in your EPF balance).",
            style = MaterialTheme.typography.bodySmall,
            color = KoshTheme.colors.subtleText,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KoshTextField(rateText, { rateText = it }, "Interest rate", Modifier.weight(1f), keyboardType = KeyboardType.Decimal, suffix = "%")
            KoshTextField(day, { day = it.filter(Char::isDigit).take(2) }, "Credited on day", Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Balance includes this year's interest", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Usually off: the passbook shows this year's interest only after it's credited (after 31 March), so Kosh adds it from 1 April.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
            androidx.compose.material3.Switch(checked = includesInterest, onCheckedChange = { includesInterest = it })
        }
        if (draft != null) {
            val p = draft.toFinance().projectBalance(Time.today().plusYears(1))
            KoshCard(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                Text("Projected in one year", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                AmountText(p.totalIncludingAccrued, style = AmountStyles.large)
                Text("Interest is credited every 31 March; contributions are added automatically each month.",
                    style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
        }
        PrimaryButton("Save", enabled = draft != null && name.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            haptic()
            onSave(name.trim(), draft!!)
        })
    }
}

private data class HoldingDraft(val code: String, val name: String, val nav: Double, val navDate: LocalDate?, val units: String, val value: Long?, val allocation: String)

/** NPS: the simple form by default; scheme/NAV tracking only behind "Advanced". */
@Composable
private fun NpsEntry(item: InvestmentItem?, vm: InvestmentEditViewModel, onSave: (String, InvestmentSpec) -> Unit) {
    var advanced by remember { mutableStateOf(item?.spec is InvestmentSpec.Nps) }
    Column {
        if (advanced) NpsForm(item, vm, onSave = { n, sp -> onSave(n, sp) }) else NpsSimpleForm(item, onSave = { n, sp -> onSave(n, sp) })
        // Live NAVs need the network; the offline edition only offers the simple form.
        if (com.amitjha.kosh.BuildConfig.NETWORK_ENABLED || advanced) Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Advanced: track by scheme NAV", style = MaterialTheme.typography.bodyMedium)
                Text("Enter units per scheme; value follows live NAVs.", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
            androidx.compose.material3.Switch(checked = advanced, onCheckedChange = { advanced = it })
        }
    }
}

@Composable
private fun NpsSimpleForm(item: InvestmentItem?, onSave: (String, com.amitjha.kosh.domain.NpsSimple) -> Unit) {
    val spec = item?.spec as? com.amitjha.kosh.domain.NpsSimple
    var name by remember { mutableStateOf(item?.entity?.name ?: "NPS") }
    // Today's projected value, not the last stored one: the worker may not have run since a contribution.
    var value by remember { mutableStateOf(spec?.valueOn(Time.today())?.first ?: item?.entity?.currentValue) }
    var monthly by remember { mutableStateOf(spec?.monthlyContribution) }
    var day by remember { mutableStateOf((spec?.contributionDay ?: 1).toString()) }
    var returnText by remember { mutableStateOf((spec?.annualReturnPct ?: com.amitjha.kosh.domain.NpsSimple.DEFAULT_RETURN_PCT).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }) }
    val haptic = rememberHaptic()
    val valid = name.isNotBlank() && (value ?: 0) > 0 && rate(returnText) != null
    FormColumn {
        KoshTextField(name, { name = it }, "Name")
        AmountField("Total value today", value, { value = it })
        Text("From your NPS statement or app, or the total you've contributed so far.", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        AmountField("Monthly contribution", monthly, { monthly = it })
        KoshTextField(day, { day = it.filter(Char::isDigit).take(2) }, "Contribution day of month", keyboardType = KeyboardType.Number)
        KoshTextField(returnText, { returnText = it }, "Expected return", keyboardType = KeyboardType.Decimal, suffix = "% per year")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("8", "10", "12").forEach { r ->
                androidx.compose.material3.FilterChip(
                    selected = returnText == r,
                    onClick = { returnText = r },
                    label = { Text("$r%") },
                )
            }
        }
        Text(
            "Kosh adds your monthly contribution each month and grows the total at this return, so the value stays up to date on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = KoshTheme.colors.subtleText,
        )
        PrimaryButton("Save", enabled = valid, modifier = Modifier.fillMaxWidth(), onClick = {
            haptic()
            onSave(
                name.trim(),
                com.amitjha.kosh.domain.NpsSimple(
                    valueAsOf = value ?: 0,
                    asOfDate = Time.today(),
                    monthlyContribution = monthly ?: 0,
                    contributionDay = day.toIntOrNull()?.coerceIn(1, 28) ?: 1,
                    annualReturnPct = rate(returnText) ?: com.amitjha.kosh.domain.NpsSimple.DEFAULT_RETURN_PCT,
                    investedAsOf = spec?.let { it.valueOn(Time.today()).second } ?: (value ?: 0),
                ),
            )
        })
    }
}

@Composable
private fun NpsForm(item: InvestmentItem?, vm: InvestmentEditViewModel, onSave: (String, InvestmentSpec.Nps) -> Unit) {
    val spec = item?.spec as? InvestmentSpec.Nps
    var name by remember { mutableStateOf(item?.entity?.name ?: "NPS Tier I") }
    val holdings = remember {
        mutableStateListOf<HoldingDraft>().apply {
            spec?.holdings?.forEach { h ->
                add(
                    HoldingDraft(
                        h.schemeCode, h.schemeName, h.lastNav, h.lastNavDate, String.format(Locale.ENGLISH, "%.4f", h.units), null,
                        ((spec.allocation[h.schemeCode] ?: 0.0) * 100).toInt().toString(),
                    ),
                )
            }
        }
    }
    var monthly by remember { mutableStateOf(spec?.monthlyContribution) }
    var day by remember { mutableStateOf((spec?.contributionDay ?: 1).toString()) }
    var invested by remember { mutableStateOf(spec?.investedAmount?.takeIf { it > 0 }) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NavQuote>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()

    LaunchedEffect(query) {
        if (query.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        results = vm.search(query)
        searching = false
    }

    fun unitsOf(h: HoldingDraft): Double? = h.units.toDoubleOrNull() ?: h.value?.let { v -> if (h.nav > 0) v / 100.0 / h.nav else null }

    val valid = name.isNotBlank() && holdings.isNotEmpty() && holdings.all { unitsOf(it) != null }
    FormColumn {
        KoshTextField(name, { name = it }, "Name")
        SectionHeader("Schemes you hold")
        holdings.forEachIndexed { i, h ->
            KoshCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text("NAV ${String.format(Locale.ENGLISH, "%.4f", h.nav)}${h.navDate?.let { " · " + Fmt.date(it) } ?: ""}",
                            style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
                    }
                    IconButton(onClick = { holdings.removeAt(i) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KoshTextField(h.units, { holdings[i] = h.copy(units = it.filter { c -> c.isDigit() || c == '.' }, value = null) },
                        "Units", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                    KoshTextField(h.allocation, { holdings[i] = h.copy(allocation = it.filter(Char::isDigit).take(3)) },
                        "Allocation", Modifier.weight(0.7f), keyboardType = KeyboardType.Number, suffix = "%")
                }
                AmountField("…or current value", h.value, { v -> holdings[i] = h.copy(value = v, units = if (h.nav > 0) String.format(Locale.ENGLISH, "%.4f", v / 100.0 / h.nav) else "") })
            }
        }
        KoshTextField(query, { query = it }, "Search NPS scheme", supporting = "e.g. HDFC scheme E tier I, SBI scheme G")
        if (searching) CircularProgressIndicator(Modifier.padding(4.dp).height(20.dp).width(20.dp), strokeWidth = 2.dp)
        results.take(8).forEach { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (holdings.none { it.code == q.schemeCode }) {
                            holdings.add(HoldingDraft(q.schemeCode, q.schemeName, q.nav, q.date, "", null, ""))
                            val share = 100 / holdings.size
                            holdings.indices.forEach { idx -> holdings[idx] = holdings[idx].copy(allocation = share.toString()) }
                        }
                        query = ""
                        results = emptyList()
                        scope.launch { vm.nav(q.schemeCode) }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(q.schemeName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(String.format(Locale.ENGLISH, "%.2f", q.nav), style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
            }
            HorizontalDivider(color = KoshTheme.colors.divider)
        }
        AmountField("Monthly contribution (optional)", monthly, { monthly = it })
        KoshTextField(day, { day = it.filter(Char::isDigit).take(2) }, "Contribution day of month", keyboardType = KeyboardType.Number)
        AmountField("Total invested so far (optional)", invested, { invested = it })
        PrimaryButton("Save", enabled = valid, modifier = Modifier.fillMaxWidth(), onClick = {
            haptic()
            val hs = holdings.map { h -> NpsHolding(h.code, h.name, unitsOf(h)!!, h.nav, h.navDate) }
            val totalAlloc = holdings.sumOf { it.allocation.toIntOrNull() ?: 0 }.coerceAtLeast(1)
            val allocation = holdings.associate { it.code to (it.allocation.toIntOrNull() ?: 0).toDouble() / totalAlloc }
                .takeIf { it.values.sum() > 0 } ?: holdings.associate { it.code to 1.0 / holdings.size }
            onSave(
                name.trim(),
                InvestmentSpec.Nps(
                    holdings = hs,
                    monthlyContribution = monthly ?: 0,
                    allocation = allocation,
                    contributionDay = day.toIntOrNull()?.coerceIn(1, 28) ?: 1,
                    investedAmount = invested ?: 0,
                    contributionsAppliedTill = spec?.contributionsAppliedTill,
                ),
            )
        })
    }
}

// ---------------------------------------------------------------- detail

data class InvestmentDetailState(
    val item: InvestmentItem? = null,
    val history: List<ChartPoint> = emptyList(),
    val loaded: Boolean = false,
    /** For a payout FD: every scheduled interest credit, with the one found in the bank's SMS. */
    val payouts: List<SeenPayout> = emptyList(),
    val payoutAccount: com.amitjha.kosh.data.AccountEntity? = null,
)

class InvestmentDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val state: StateFlow<InvestmentDetailState> =
        combine(c.investments.observeItems(), c.investments.observeHistory(id), c.db.transactions().observeChanges()) { items, hist, _ ->
            val item = items.firstOrNull { it.id == id }
            val (payouts, account) = payoutsOf(item)
            InvestmentDetailState(
                item,
                hist.map { ChartPoint(Fmt.date(LocalDate.ofEpochDay(it.epochDay)), it.value) },
                loaded = true,
                payouts = payouts,
                payoutAccount = account,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvestmentDetailState())

    /** A payout FD's schedule, matched against the credits to the account it pays into. */
    private suspend fun payoutsOf(item: InvestmentItem?): Pair<List<SeenPayout>, com.amitjha.kosh.data.AccountEntity?> {
        val spec = item?.spec as? InvestmentSpec.Fd
        if (spec == null || !spec.paysOut) return emptyList<SeenPayout>() to null
        val schedule = spec.toFinance(item.entity.institution, item.entity.name).payoutSchedule()
        val account = spec.payoutAccountId?.let { c.accounts.byId(it) }
        val credits = if (account == null) {
            emptyList()
        } else {
            c.db.transactions().forAccountSince(account.id, Time.startOfDay(spec.startDate))
                .filter { it.type == com.amitjha.kosh.model.TxnType.INCOME && it.accountId == account.id }
                .map { Time.toDate(it.timestamp) to it.amount }
        }
        return FdPayouts.match(schedule, credits) to account
    }

    /** Uses the amount the bank actually credits for the credits still to come. */
    fun useBankAmount(amount: Long) = viewModelScope.launch {
        val item = state.value.item ?: return@launch
        val spec = item.spec as? InvestmentSpec.Fd ?: return@launch
        c.investments.update(item.id, item.entity.name, item.entity.institution, spec.copy(payoutAmount = amount))
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        c.investments.delete(id)
        onDone()
    }
}

@Composable
fun InvestmentDetailScreen(nav: NavController, id: Long) {
    val vm = koshViewModel(key = "inv-$id") { InvestmentDetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    var confirmDelete by remember { mutableStateOf(false) }
    val inv = state.item
    KoshScreen(
        title = inv?.entity?.name ?: "Investment",
        subtitle = inv?.entity?.institution,
        onBack = { nav.popBackSafely() },
        actions = {
            if (inv != null) {
                IconButton(onClick = { nav.navigate(Routes.editInvestment(id)) }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete") }
            }
        },
    ) { padding ->
        if (inv == null) {
            if (state.loaded) EmptyState("Not found", "This investment may have been deleted.", Modifier.padding(padding).padding(top = 60.dp))
            return@KoshScreen
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GradientCard {
                Text("Current value", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                AmountText(inv.value, style = AmountStyles.hero, hidden = hidden, animate = true, color = Color.White, showPaise = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Part("Invested", inv.invested, hidden)
                    val payoutFd = (inv.spec as? InvestmentSpec.Fd)?.takeIf { it.paysOut }
                    if (payoutFd != null) {
                        // The value stays at the principal; what it earns arrives in the bank account.
                        Part("Interest received", payoutFd.toFinance(inv.entity.institution, inv.entity.name).interestPaidTill(Time.today()), hidden)
                    } else {
                        Part("Gain", inv.gain, hidden)
                    }
                    inv.annualisedReturnPct?.let {
                        Column {
                            Text("Return p.a.", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                            Text(Fmt.pct(it), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (state.history.size >= 2) {
                KoshCard {
                    Text("Value history", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LineAreaChart(state.history, hidden = hidden, color = inv.type.color())
                }
            }
            KoshCard {
                when (val s = inv.spec) {
                    is InvestmentSpec.Fd -> {
                        val fd = s.toFinance(inv.entity.institution, inv.entity.name)
                        Info("Principal", Money.format(s.principal), hidden)
                        Info("Rate", Fmt.pct(s.annualRatePct, 2) + " p.a. · " + s.compounding.name.lowercase().replace('_', '-'), false)
                        Info("Started", Fmt.fullDate(s.startDate), false)
                        Info("Matures", Fmt.fullDate(s.maturityDate) + (inv.daysToMaturity()?.let { if (it > 0) " · $it days" else " · matured" } ?: ""), false)
                        if (s.paysOut) {
                            Info("Interest", Money.format(fd.regularPayout()) + " " + s.payoutFrequency.per(), hidden)
                            Info("Credited to", state.payoutAccount?.let { a -> a.name + (a.last4?.let { " ••$it" } ?: "") } ?: "Not set", false)
                            Info("Received so far", Money.format(fd.interestPaidTill(Time.today())), hidden)
                            Info("Still to come", Money.format(fd.payoutSchedule().filter { it.date.isAfter(Time.today()) }.sumOf { it.amount }), hidden)
                            Info("At maturity", Money.format(fd.principal) + " back", hidden)
                        } else {
                            Info("Maturity value", Money.format(fd.maturityValue()), hidden)
                            Info("Interest so far", Money.format(fd.interestEarnedTill(Time.today())), hidden)
                        }
                    }
                    is InvestmentSpec.Epf -> {
                        val p = s.toFinance().projectBalance(Time.today())
                        val next = s.toFinance().projectBalance(Time.today().plusYears(1))
                        Info("Credited balance", Money.format(p.creditedBalance), hidden)
                        Info("Interest accrued (credited 31 Mar)", Money.format(p.accruedInterestNotYetCredited), hidden)
                        Info("Monthly contribution", Money.format(s.monthlyEmployee + s.monthlyEmployer), hidden)
                        Info("Rate", Fmt.pct(s.annualRatePct, 2), false)
                        Info("Projected in a year", Money.format(next.totalIncludingAccrued), hidden)
                    }
                    is InvestmentSpec.Nps -> {
                        s.holdings.forEach { h ->
                            Info(h.schemeName, "${String.format(Locale.ENGLISH, "%.3f", h.units)} units × ${String.format(Locale.ENGLISH, "%.4f", h.lastNav)}", false)
                        }
                        if (s.monthlyContribution > 0) Info("Monthly contribution", Money.format(s.monthlyContribution) + " on day ${s.contributionDay}", hidden)
                        s.holdings.mapNotNull { it.lastNavDate }.maxOrNull()?.let { Info("NAV as of", Fmt.fullDate(it), false) }
                    }
                    is com.amitjha.kosh.domain.NpsSimple -> {
                        Info("Monthly contribution", Money.format(s.monthlyContribution) + " on day ${s.contributionDay}", hidden)
                        Info("Expected return", Fmt.pct(s.annualReturnPct, 1) + " per year", false)
                        Info("Invested so far", Money.format(s.valueOn(Time.today()).second), hidden)
                        Info("Projected in a year", Money.format(s.valueOn(Time.today().plusYears(1)).first), hidden)
                    }
                }
            }
            if (state.payouts.isNotEmpty()) PayoutCredits(state, hidden, onUseAmount = vm::useBankAmount)
            Text("Values update automatically every day.", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete investment?", "Its value history will be removed too.", "Delete", onConfirm = {
            confirmDelete = false
            vm.delete { nav.popBackSafely() }
        }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

/** The FD's interest credits: the next few due, and the last few with whether the bank's SMS showed them. */
@Composable
private fun PayoutCredits(state: InvestmentDetailState, hidden: Boolean, onUseAmount: (Long) -> Unit) {
    val today = Time.today()
    val upcoming = state.payouts.filter { !it.due.date.isBefore(today) }.take(3)
    val past = state.payouts.filter { it.due.date.isBefore(today) }.takeLast(3).reversed()
    val spec = state.item?.spec as? InvestmentSpec.Fd
    KoshCard {
        Text("Interest credits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        upcoming.forEachIndexed { i, p ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, p.due.date)
            val whenText = when (days) {
                0L -> "today"
                1L -> "tomorrow"
                else -> "in $days days"
            }
            Info(
                Fmt.fullDate(p.due.date) + if (i == 0) " · $whenText" else "",
                Money.format(p.due.amount) + if (p.due.final) " + principal" else "",
                true,
            )
        }
        if (past.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = KoshTheme.colors.divider)
            past.forEach { p ->
                val text = when {
                    p.credited != null && p.creditedOn != null -> "✓ " + Money.format(p.credited) + " on " + Fmt.date(p.creditedOn)
                    state.payoutAccount == null -> Money.format(p.due.amount)
                    else -> "Not seen in SMS"
                }
                Info(Fmt.fullDate(p.due.date), text, p.credited != null || state.payoutAccount == null)
            }
            val last = past.firstOrNull { it.credited != null }
            if (spec != null && last?.credited != null && last.credited != last.due.amount && !last.due.final && !hidden) {
                Text(
                    "Use ${Money.format(last.credited)} for the credits to come",
                    modifier = Modifier.clickable { onUseAmount(last.credited) }.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (state.payoutAccount == null) {
            Text(
                "Choose the account it's credited to (Edit) and Kosh will tick off each credit from your bank SMS.",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
            )
        }
    }
}

@Composable
private fun Part(label: String, value: Long, hidden: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        AmountText(value, style = AmountStyles.small, hidden = hidden, color = Color.White)
    }
}

@Composable
private fun Info(label: String, value: String, sensitive: Boolean) {
    val hidden = LocalHideAmounts.current && sensitive
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(0.5f), color = KoshTheme.colors.subtleText, style = MaterialTheme.typography.bodyMedium)
        Text(if (hidden) "₹ ••••" else value, Modifier.weight(0.5f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}


/** "Monthly", "Every 3 months"... */
fun PayoutFrequency.label(): String = when (this) {
    PayoutFrequency.MONTHLY -> "Monthly"
    PayoutFrequency.QUARTERLY -> "Quarterly"
    PayoutFrequency.HALF_YEARLY -> "Half-yearly"
    PayoutFrequency.YEARLY -> "Yearly"
}

/** "a month", "a quarter"... */
fun PayoutFrequency.per(): String = when (this) {
    PayoutFrequency.MONTHLY -> "a month"
    PayoutFrequency.QUARTERLY -> "a quarter"
    PayoutFrequency.HALF_YEARLY -> "every 6 months"
    PayoutFrequency.YEARLY -> "a year"
}
