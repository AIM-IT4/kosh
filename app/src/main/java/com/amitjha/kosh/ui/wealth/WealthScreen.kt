package com.amitjha.kosh.ui.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.ChartPoint
import com.amitjha.kosh.designsystem.components.ChartSlice
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.GradientCard
import com.amitjha.kosh.designsystem.components.InvestmentTypeBadge
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.LineAreaChart
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.StackedBar
import com.amitjha.kosh.designsystem.components.staggeredEntrance
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.BrandPalette
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.repo.InvestmentItem
import com.amitjha.kosh.repo.NetWorth
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.nav.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class WealthState(
    val netWorth: NetWorth? = null,
    val history: List<ChartPoint> = emptyList(),
    val items: List<InvestmentItem> = emptyList(),
    val loaded: Boolean = false,
)

class WealthViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<WealthState> = combine(
        c.netWorth.observeNetWorth(),
        c.netWorth.observeHistory(),
        c.investments.observeItems(),
    ) { nw, history, items ->
        WealthState(
            netWorth = nw,
            history = history.takeLast(180).map { ChartPoint(Fmt.date(LocalDate.ofEpochDay(it.epochDay)), it.total) },
            items = items,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WealthState())

    suspend fun refresh(): String = runCatching {
        val r = c.investments.revalueAll()
        c.netWorth.snapshot()
        buildString {
            append("Updated ${r.updated} investment${if (r.updated == 1) "" else "s"}")
            if (r.navsFetched > 0) append(" · ${r.navsFetched} NAVs fetched")
            if (r.contributionsApplied > 0) append(" · ${r.contributionsApplied} contributions added")
        }
    }.getOrElse { "Couldn't refresh: ${it.message ?: "network error"}" }
}

fun InvestmentType.color(): Color = when (this) {
    InvestmentType.FD -> BrandPalette.Teal500
    InvestmentType.EPF -> BrandPalette.Indigo400
    InvestmentType.NPS -> BrandPalette.Gold
}

fun InvestmentType.title(): String = when (this) {
    InvestmentType.FD -> "Fixed deposits"
    InvestmentType.EPF -> "EPF"
    InvestmentType.NPS -> "NPS"
}

@Composable
fun WealthScreen(nav: NavController) {
    val vm = koshViewModel { WealthViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item(key = "head") {
                Row(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Wealth", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).height(22.dp).width(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = {
                            refreshing = true
                            scope.launch {
                                val msg = vm.refresh()
                                refreshing = false
                                snackbar.showSnackbar(msg)
                            }
                        }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh values") }
                    }
                }
            }
            state.netWorth?.let { nw ->
                item(key = "hero") {
                    GradientCard(Modifier.padding(horizontal = 20.dp)) {
                        Text("Net worth", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                        AmountText(nw.total, style = AmountStyles.hero, hidden = hidden, animate = true, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            HeroPart("Cash & bank", nw.liquid, hidden)
                            HeroPart("Investments", nw.investments, hidden)
                            HeroPart("Card dues", -nw.cardDebt, hidden)
                        }
                    }
                }
                if (nw.investmentsByType.isNotEmpty()) {
                    item(key = "split") {
                        val slices = nw.investmentsByType.entries.sortedByDescending { it.value }
                            .map { ChartSlice(it.key.title(), it.value, it.key.color()) }
                        KoshCard(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("Investments by type", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            StackedBar(slices, hidden = hidden)
                        }
                    }
                }
            }
            if (state.history.size >= 2) {
                item(key = "hist") {
                    KoshCard(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text("Net worth over time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LineAreaChart(state.history, hidden = hidden, color = BrandPalette.Teal500)
                    }
                }
            }
            item(key = "inv-h") { SectionHeader("Investments", Modifier.padding(horizontal = 20.dp)) }
            item(key = "add") {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InvestmentType.entries.forEach { t ->
                        OutlinedButton(onClick = { nav.navigate(Routes.addInvestment(t)) }, modifier = Modifier.weight(1f)) { Text("+ ${t.name}") }
                    }
                }
            }
            if (state.loaded && state.items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        "Track FD, EPF and NPS",
                        "Enter the details once. Kosh grows FDs with interest, adds EPF contributions monthly and updates NPS from live NAVs.",
                        Modifier.padding(top = 24.dp),
                        icon = Icons.Filled.Savings,
                    )
                }
            }
            items(state.items.size, key = { state.items[it].id }) { i ->
                val inv = state.items[i]
                InvestmentRow(inv, hidden, Modifier.padding(horizontal = 20.dp, vertical = 5.dp).staggeredEntrance(i)) {
                    nav.navigate(Routes.investment(inv.id))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

@Composable
private fun HeroPart(label: String, value: Long, hidden: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        AmountText(value, style = AmountStyles.small, compact = true, hidden = hidden, color = Color.White)
    }
}

@Composable
fun InvestmentRow(inv: InvestmentItem, hidden: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    KoshCard(modifier, onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InvestmentTypeBadge(inv.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(inv.entity.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (inv.type) {
                    InvestmentType.FD -> inv.daysToMaturity()?.let { d -> if (d > 0) "${inv.entity.institution} · matures in $d days" else "${inv.entity.institution} · matured" }
                    InvestmentType.EPF -> "Contributions added monthly"
                    InvestmentType.NPS -> "Updated with live NAV"
                } ?: inv.entity.institution
                Text(sub, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                AmountText(inv.value, style = AmountStyles.medium, hidden = hidden)
                inv.gainPct?.let {
                    Text(
                        (if (it >= 0) "+" else "") + Fmt.pct(it),
                        color = if (it >= 0) KoshTheme.colors.income else KoshTheme.colors.expense,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

