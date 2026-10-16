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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.amitjha.kosh.data.LoanEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.components.ThinProgressBar
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.finance.Loans
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.repo.LoanRepository
import com.amitjha.kosh.repo.LoanStatus
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
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

fun loanKindTitle(kind: String): String = when (kind) {
    "HOME" -> "Home loan"
    "CAR" -> "Car loan"
    "PERSONAL" -> "Personal loan"
    "EDUCATION" -> "Education loan"
    else -> "Loan"
}

@Composable
fun LoansScreen(nav: NavController) {
    val c = LocalContainer.current
    val hidden = LocalHideAmounts.current
    val loans by c.loans.observe().collectAsState(initial = null)
    KoshScreen(
        title = "Loans & EMIs",
        onBack = { nav.popBackSafely() },
        floating = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.editLoan()) }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add loan") })
        },
    ) { pad ->
        val list = loans ?: return@KoshScreen
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        "No loans",
                        "Add a home, car or personal loan to see what's left, the EMI split and how much a prepayment saves. " +
                            "Home-loan interest and principal flow into the tax planner.",
                    )
                }
                return@LazyColumn
            }
            item {
                KoshCard {
                    Text("Still owed", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    AmountText(list.sumOf { it.outstanding }, style = AmountStyles.large, hidden = hidden)
                    Text(
                        "EMIs about ${if (hidden) "₹ ••••" else Money.format(list.filter { it.outstanding > 0 }.sumOf { it.emi }, showPaise = false)} a month",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
            items(list, key = { it.loan.id }) { s -> LoanCard(s, hidden) { nav.navigate(Routes.loan(s.loan.id)) } }
        }
    }
}

@Composable
private fun LoanCard(s: LoanStatus, hidden: Boolean, onClick: () -> Unit) {
    KoshCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.loan.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(loanKindTitle(s.loan.kind), s.loan.lender.takeIf { it.isNotBlank() }, "${fmtRate(s.loan.ratePct)}%").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
            AmountText(s.outstanding, style = AmountStyles.medium, hidden = hidden)
        }
        Spacer(Modifier.height(10.dp))
        val f = if (s.loan.principal > 0) 1f - s.outstanding.toFloat() / s.loan.principal else 0f
        ThinProgressBar(f, color = KoshTheme.colors.accent)
        Spacer(Modifier.height(6.dp))
        Text(
            if (s.spec == null) "Check the loan details: they don't make a valid schedule"
            else if (s.outstanding == 0L) "Fully repaid"
            else "${s.paidMonths} of ${s.totalMonths} EMIs paid · ends ${s.endDate?.let(Fmt::date) ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (s.spec == null) KoshTheme.colors.alert else KoshTheme.colors.subtleText,
        )
    }
}

private fun fmtRate(r: Double) = String.format(Locale.ENGLISH, "%.2f", r).trimEnd('0').trimEnd('.')

@Composable
fun LoanDetailScreen(nav: NavController, id: Long) {
    val c = LocalContainer.current
    val hidden = LocalHideAmounts.current
    val scope = rememberCoroutineScope()
    val status by c.loans.observe(id).collectAsState(initial = null)
    var tab by remember { mutableStateOf(0) }
    var prepayAmount by remember { mutableStateOf<Long?>(null) }
    var prepayDate by remember { mutableStateOf(Time.today().plusMonths(1)) }
    var reduce by remember { mutableStateOf(Loans.Reduce.TENURE) }
    var confirmRecord by remember { mutableStateOf(false) }

    val s = status
    KoshScreen(
        title = s?.loan?.name ?: "Loan",
        onBack = { nav.popBackSafely() },
        actions = { IconButton(onClick = { nav.navigate(Routes.editLoan(id)) }) { Icon(Icons.Outlined.Edit, "Edit") } },
    ) { pad ->
        if (s == null) return@KoshScreen
        val spec = s.spec
        val rows = remember(spec) { spec?.let { runCatching { Loans.schedule(it) }.getOrNull() }.orEmpty() }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                KoshCard {
                    Text("Still owed", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    AmountText(s.outstanding, style = AmountStyles.hero, hidden = hidden)
                    Spacer(Modifier.height(8.dp))
                    Line("EMI", money(s.emi, hidden))
                    Line("Borrowed", money(s.loan.principal, hidden))
                    Line("Interest over the loan", money(s.totalInterest, hidden))
                    Line("EMIs", "${s.paidMonths} paid of ${s.totalMonths}")
                    s.endDate?.let { Line("Last EMI", Fmt.fullDate(it)) }
                    if (s.loan.kind == LoanRepository.HOME) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Interest counts under 24(b) and principal under 80C in the tax planner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText,
                        )
                    }
                }
            }
            if (spec == null) {
                item { Text("These details don't make a valid schedule. Check the EMI covers the monthly interest.", color = KoshTheme.colors.alert) }
                return@LazyColumn
            }
            item { SegmentedTabs(listOf("Prepay", "Schedule"), tab, onSelect = { tab = it }) }
            if (tab == 0) {
                item {
                    KoshCard {
                        SectionHeader("What if I prepay?")
                        FormColumn {
                            AmountField("Prepayment", prepayAmount, { prepayAmount = it }, allowZero = false)
                            DateField("On", prepayDate, { prepayDate = it })
                            SegmentedTabs(listOf("Fewer EMIs", "Lower EMI"), if (reduce == Loans.Reduce.TENURE) 0 else 1,
                                onSelect = { reduce = if (it == 0) Loans.Reduce.TENURE else Loans.Reduce.EMI })
                        }
                        val amount = prepayAmount
                        if (amount != null && amount > 0) {
                            val impact = remember(spec, amount, prepayDate, reduce) {
                                runCatching { Loans.prepaymentImpact(spec, prepayDate, amount, reduce) }.getOrNull()
                            }
                            if (impact != null) {
                                Spacer(Modifier.height(12.dp))
                                Line("Interest saved", money(impact.interestSaved, hidden), KoshTheme.colors.good)
                                if (impact.monthsSaved > 0) Line("EMIs saved", "${impact.monthsSaved} (${impact.monthsSaved / 12} y ${impact.monthsSaved % 12} m)")
                                Line("EMI after", money(impact.newEmi, hidden))
                                Line("Loan ends", Fmt.fullDate(impact.newEndDate))
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { confirmRecord = true }) { Text("I made this prepayment") }
                            }
                        }
                    }
                }
                val made = spec.prepayments
                if (made.isNotEmpty()) {
                    item { SectionHeader("Prepayments made") }
                    items(made) { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.fullDate(p.date), style = MaterialTheme.typography.bodyLarge)
                                Text(if (p.reduce == Loans.Reduce.TENURE) "Fewer EMIs" else "Lower EMI", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                            }
                            AmountText(p.amount, style = AmountStyles.medium, hidden = hidden)
                            IconButton(onClick = {
                                scope.launch {
                                    val left = made.toMutableList().apply { remove(p) }
                                    c.loans.save(s.loan.copy(prepaymentsJson = LoanRepository.encodePrepayments(left)))
                                }
                            }) { Icon(Icons.Outlined.Delete, "Remove") }
                        }
                    }
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Date", "Interest", "Principal", "Balance").forEachIndexed { i, h ->
                            Text(h, Modifier.weight(if (i == 0) 1.1f else 1f), style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
                        }
                    }
                }
                val today = Time.today()
                items(rows, key = { it.index }) { r ->
                    val past = !r.date.isAfter(today)
                    val color = if (past) KoshTheme.colors.subtleText else MaterialTheme.colorScheme.onSurface
                    Row(Modifier.fillMaxWidth()) {
                        Text(Fmt.date(r.date), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall, color = color)
                        Text(compact(r.interest, hidden), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
                        Text(compact(r.principal + r.prepayment, hidden), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
                        Text(compact(r.balance, hidden), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
                    }
                }
            }
        }
    }
    if (confirmRecord && s?.spec != null) {
        val amount = prepayAmount ?: 0
        ConfirmDialog(
            "Record this prepayment?",
            "${Money.format(amount, showPaise = false)} on ${Fmt.fullDate(prepayDate)}. The schedule and what you owe update from that date.",
            "Record",
            onConfirm = {
                confirmRecord = false
                scope.launch {
                    val list = s.spec.prepayments + Loans.Prepayment(prepayDate, amount, reduce)
                    c.loans.save(s.loan.copy(prepaymentsJson = LoanRepository.encodePrepayments(list)))
                    prepayAmount = null
                }
            },
            onDismiss = { confirmRecord = false },
        )
    }
}

@Composable
private fun Line(label: String, value: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}

private fun money(p: Long, hidden: Boolean) = if (hidden) "₹ ••••" else Money.format(p, showPaise = false)
private fun compact(p: Long, hidden: Boolean) = if (hidden) "••••" else Money.compact(p)

@Composable
fun LoanEditScreen(nav: NavController, id: Long?) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<LoanEntity?>(null) }
    var loaded by remember { mutableStateOf(id == null) }
    var name by remember { mutableStateOf("") }
    var lender by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("HOME") }
    var principal by remember { mutableStateOf<Long?>(null) }
    var rate by remember { mutableStateOf("") }
    var tenureYears by remember { mutableStateOf("") }
    var tenureMonths by remember { mutableStateOf("") }
    var firstEmi by remember { mutableStateOf(Time.today()) }
    var emi by remember { mutableStateOf<Long?>(null) }
    var selfOccupied by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != null) c.loans.observe(id).first()?.let { s ->
            val l = s.loan
            existing = l; name = l.name; lender = l.lender; kind = l.kind; principal = l.principal; rate = fmtRate(l.ratePct)
            tenureYears = (l.tenureMonths / 12).toString(); tenureMonths = (l.tenureMonths % 12).toString()
            firstEmi = LocalDate.ofEpochDay(l.firstEmiDay); emi = l.emi; selfOccupied = l.selfOccupied
        }
        loaded = true
    }

    val months = (tenureYears.toIntOrNull() ?: 0) * 12 + (tenureMonths.toIntOrNull() ?: 0)
    val ratePct = rate.replace(',', '.').toDoubleOrNull()
    val computedEmi = if ((principal ?: 0) > 0 && ratePct != null && ratePct >= 0 && months > 0) runCatching { Loans.emi(principal!!, ratePct, months) }.getOrNull() else null

    KoshScreen(
        title = if (id == null) "Add loan" else "Edit loan",
        onBack = { nav.popBackSafely() },
        actions = { if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") } },
    ) { pad ->
        if (!loaded) return@KoshScreen
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp)) {
            FormColumn {
                ChoiceField("Type", LoanRepository.KINDS, kind, ::loanKindTitle, { kind = it })
                KoshTextField(name, { name = it.take(40) }, "Name", supporting = "e.g. Flat in Pune")
                KoshTextField(lender, { lender = it.take(40) }, "Lender (optional)")
                AmountField("Amount borrowed", principal, { principal = it }, allowZero = false)
                KoshTextField(rate, { rate = it.take(6) }, "Interest rate", keyboardType = KeyboardType.Decimal, suffix = "% a year")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KoshTextField(tenureYears, { tenureYears = it.filter(Char::isDigit).take(2) }, "Years", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    KoshTextField(tenureMonths, { tenureMonths = it.filter(Char::isDigit).take(2) }, "Months", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                }
                DateField("First EMI", firstEmi, { firstEmi = it })
                AmountField("EMI charged by the lender (optional)", emi, { emi = it.takeIf { v -> v > 0 } }, placeholder = computedEmi?.let { "Calculated: ${Money.format(it, showPaise = false)}" } ?: "Tap to enter")
                if (kind == LoanRepository.HOME) {
                    SwitchRow("I live in this home", "Self-occupied: interest deduction capped at ₹2 lakh under 24(b)", selfOccupied, { selfOccupied = it })
                }
                error?.let { Text(it, color = KoshTheme.colors.alert, style = MaterialTheme.typography.bodySmall) }
                PrimaryButton("Save", enabled = name.isNotBlank() && (principal ?: 0) > 0 && ratePct != null && months > 0, modifier = Modifier.fillMaxWidth(), onClick = {
                    val loan = LoanEntity(
                        id = existing?.id ?: 0,
                        name = name.trim(),
                        lender = lender.trim(),
                        kind = kind,
                        principal = principal ?: 0,
                        ratePct = ratePct ?: 0.0,
                        tenureMonths = months,
                        firstEmiDay = firstEmi.toEpochDay(),
                        emi = emi,
                        prepaymentsJson = existing?.prepaymentsJson ?: "[]",
                        selfOccupied = selfOccupied,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    )
                    val check = runCatching { Loans.schedule(LoanRepository.spec(loan)) }
                    if (check.isFailure) {
                        error = "These details don't make a schedule: ${check.exceptionOrNull()?.message}"
                        return@PrimaryButton
                    }
                    scope.launch {
                        val newId = c.loans.save(loan)
                        nav.popBackSafely()
                        if (existing == null && newId > 0) nav.navigate(Routes.loan(newId))
                    }
                })
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete this loan?", "It's removed from net worth and the tax planner.", "Delete",
            onConfirm = {
                confirmDelete = false
                scope.launch { existing?.let { c.loans.delete(it) }; nav.popBackSafely(); nav.popBackSafely() }
            },
            onDismiss = { confirmDelete = false }, destructive = true)
    }
}
