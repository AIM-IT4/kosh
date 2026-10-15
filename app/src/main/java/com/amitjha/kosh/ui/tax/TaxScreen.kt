package com.amitjha.kosh.ui.tax

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.prefs.TaxOverrides
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.ThinProgressBar
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.finance.Tax
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.repo.DeductionLine
import com.amitjha.kosh.repo.TaxView
import com.amitjha.kosh.ui.common.AmountSheet
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.nav.popBackSafely
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaxViewModel(private val c: AppContainer) : ViewModel() {
    val view: StateFlow<TaxView?> = c.tax.observe().map<TaxView, TaxView?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (TaxOverrides) -> TaxOverrides) {
        val current = view.value?.overrides ?: TaxOverrides()
        viewModelScope.launch { c.tax.save(transform(current)) }
    }
}

/** Which input an amount sheet is editing. */
private enum class Field(val title: String) {
    SALARY("Gross salary (year)"), OTHER("Other taxable income (year)"), SAVINGS_INTEREST("Savings interest (year)"),
    FD_INTEREST("FD interest (year)"), C80("80C total (year)"), CCD1B("Own NPS contributions (year)"),
    D80("Health insurance premiums (year)"), HOME_PRINCIPAL("Home-loan principal (year)"),
    HOME_INTEREST("Home-loan interest (year)"), BASIC("Basic salary (month)"), HRA("HRA received (month)"), RENT("Rent paid (year)"),
}

@Composable
fun TaxScreen(nav: NavController) {
    val vm = koshViewModel { TaxViewModel(it) }
    val view by vm.view.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Field?>(null) }
    KoshScreen(title = "Tax", subtitle = view?.fy?.label ?: "Income tax estimate", onBack = { nav.popBackSafely() }) { padding ->
        val v = view ?: return@KoshScreen
        TaxContent(v, onEdit = { editing = it }, onToggle = vm::update, modifier = Modifier.padding(padding))
        editing?.let { f ->
            AmountSheet(f.title, current(v, f), onDismiss = { editing = null }, allowZero = true, onConfirm = { amount ->
                vm.update { o -> apply(o, f, amount) }
                editing = null
            })
        }
    }
}

private fun current(v: TaxView, f: Field): Paise = when (f) {
    Field.SALARY -> v.inputs.grossSalary
    Field.OTHER -> v.inputs.otherIncome
    Field.SAVINGS_INTEREST -> v.inputs.savingsInterest
    Field.FD_INTEREST -> v.inputs.fdInterest
    Field.C80 -> v.inputs.sec80C
    Field.CCD1B -> v.inputs.sec80CCD1B
    Field.D80 -> v.inputs.sec80D
    Field.HOME_PRINCIPAL -> v.overrides.homeLoanPrincipal
    Field.HOME_INTEREST -> v.inputs.homeLoanInterest
    Field.BASIC -> v.overrides.basicMonthly
    Field.HRA -> v.overrides.hraMonthly
    Field.RENT -> v.overrides.rentAnnual ?: v.auto.rent
}

private fun apply(o: TaxOverrides, f: Field, a: Paise): TaxOverrides = when (f) {
    Field.SALARY -> o.copy(grossSalary = a)
    Field.OTHER -> o.copy(otherIncome = a)
    Field.SAVINGS_INTEREST -> o.copy(savingsInterest = a)
    Field.FD_INTEREST -> o.copy(fdInterest = a)
    Field.C80 -> o.copy(sec80C = a)
    Field.CCD1B -> o.copy(sec80CCD1B = a)
    Field.D80 -> o.copy(sec80D = a)
    Field.HOME_PRINCIPAL -> o.copy(homeLoanPrincipal = a)
    Field.HOME_INTEREST -> o.copy(homeLoanInterest = a)
    Field.BASIC -> o.copy(basicMonthly = a)
    Field.HRA -> o.copy(hraMonthly = a)
    Field.RENT -> o.copy(rentAnnual = a)
}

@Composable
private fun TaxContent(v: TaxView, onEdit: (Field) -> Unit, onToggle: ((TaxOverrides) -> TaxOverrides) -> Unit, modifier: Modifier = Modifier) {
    val hidden = LocalHideAmounts.current
    fun money(p: Paise) = if (hidden) "₹ ••••" else Money.format(p, showPaise = false)
    val c = v.comparison
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "compare") {
            KoshCard {
                val better = if (c.better == Tax.Regime.NEW) "New regime" else "Old regime"
                Text(if (c.saving == 0L) "Both regimes cost the same" else "$better saves ${money(c.saving)}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    RegimeColumn("New regime", c.new, c.better == Tax.Regime.NEW, hidden, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    RegimeColumn("Old regime", c.old, c.better == Tax.Regime.OLD, hidden, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text(Tax.RULES_LABEL, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
        }
        item(key = "income") {
            SectionHeader("Income")
            KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                InputRow("Gross salary", v.inputs.grossSalary, v.overrides.grossSalary == null, hidden) { onEdit(Field.SALARY) }
                Divider()
                InputRow("Savings interest", v.inputs.savingsInterest, v.overrides.savingsInterest == null, hidden) { onEdit(Field.SAVINGS_INTEREST) }
                Divider()
                InputRow("FD interest", v.inputs.fdInterest, v.overrides.fdInterest == null, hidden) { onEdit(Field.FD_INTEREST) }
                Divider()
                InputRow("Other income", v.inputs.otherIncome, false, hidden) { onEdit(Field.OTHER) }
            }
            v.tdsWarning?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.warning)
            }
        }
        item(key = "deductions") {
            SectionHeader("Deductions (old regime)")
            KoshCard {
                v.deductions.forEachIndexed { i, d ->
                    if (i > 0) Spacer(Modifier.height(14.dp))
                    DeductionRow(d, hidden) {
                        when (d.section) {
                            "80C" -> onEdit(Field.C80)
                            "80CCD(1B)" -> onEdit(Field.CCD1B)
                            "80D" -> onEdit(Field.D80)
                            "80TTA", "80TTB" -> onEdit(Field.SAVINGS_INTEREST)
                            "24(b)" -> onEdit(Field.HOME_INTEREST)
                            "HRA" -> onEdit(Field.BASIC)
                        }
                    }
                }
            }
        }
        item(key = "hra") {
            SectionHeader("Rent & home loan")
            KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                InputRow("Basic salary / month", v.overrides.basicMonthly, false, hidden) { onEdit(Field.BASIC) }
                Divider()
                InputRow("HRA received / month", v.overrides.hraMonthly, false, hidden) { onEdit(Field.HRA) }
                Divider()
                InputRow("Rent paid / year", v.overrides.rentAnnual ?: v.auto.rent, v.overrides.rentAnnual == null, hidden) { onEdit(Field.RENT) }
                Divider()
                ToggleRow("Metro city (50% of basic)", v.overrides.metro) { on -> onToggle { it.copy(metro = on) } }
                Divider()
                InputRow("Home-loan principal / year", v.overrides.homeLoanPrincipal, false, hidden) { onEdit(Field.HOME_PRINCIPAL) }
                Divider()
                InputRow("Home-loan interest / year", v.overrides.homeLoanInterest, false, hidden) { onEdit(Field.HOME_INTEREST) }
                Divider()
                ToggleRow("Senior citizen (60+)", v.overrides.senior) { on -> onToggle { it.copy(senior = on) } }
            }
        }
        item(key = "reset") {
            TextButton(onClick = { onToggle { TaxOverrides() } }) { Text("Reset to values from my data", color = KoshTheme.colors.accent) }
            Text(
                "Auto values come from your salary credits, EPF and NPS contributions, insurance and rent payments, " +
                    "savings interest credits and FDs, projected to the full year. Tap any amount to change it.",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
            )
        }
    }
}

@Composable
private fun RegimeColumn(title: String, b: Tax.Breakdown, best: Boolean, hidden: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(title + if (best) " ✓" else "", style = MaterialTheme.typography.labelLarge,
            color = if (best) KoshTheme.colors.accent else KoshTheme.colors.subtleText, fontWeight = FontWeight.SemiBold)
        AmountText(b.total, style = AmountStyles.large, hidden = hidden)
        Text("Taxable ${if (hidden) "₹ ••••" else Money.compact(b.taxableIncome)}", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        Text("Deductions ${if (hidden) "₹ ••••" else Money.compact(b.deductions)}", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
    }
}

@Composable
private fun DeductionRow(d: DeductionLine, hidden: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.section, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(d.title, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText, modifier = Modifier.weight(1f))
            val claimed = if (hidden) "₹ ••••" else Money.compact(minOf(d.claimed, d.cap ?: d.claimed))
            Text(if (d.cap != null) "$claimed of ${Money.compact(d.cap)}" else claimed, style = MaterialTheme.typography.bodySmall)
        }
        if (d.cap != null) {
            Spacer(Modifier.height(6.dp))
            ThinProgressBar((d.claimed.toFloat() / d.cap).coerceIn(0f, 1f), color = KoshTheme.colors.accent)
        }
        if (d.savingIfFilled > 0 && d.cap != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Invest ${if (hidden) "₹ ••••" else Money.compact(d.cap - d.claimed)} more before 31 Mar to save " +
                    "${if (hidden) "₹ ••••" else Money.compact(d.savingIfFilled)} in the old regime",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.accent,
            )
        }
        if (d.auto) Text("From your data", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
    }
}

@Composable
private fun InputRow(label: String, value: Paise, auto: Boolean, hidden: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (auto) Text("From your data · tap to change", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
        }
        AmountText(value, style = AmountStyles.small, hidden = hidden)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = KoshTheme.colors.divider)
