package com.amitjha.kosh.ui.wealth

import com.amitjha.kosh.designsystem.components.tr
import com.amitjha.kosh.designsystem.components.SecondaryButton
import com.amitjha.kosh.designsystem.components.BalanceHeroCard
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import com.amitjha.kosh.designsystem.theme.KoshText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Savings
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
    val interest: com.amitjha.kosh.repo.InterestSummary? = null,
    val loaded: Boolean = false,
)

class WealthViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<WealthState> = combine(
        c.netWorth.observeNetWorth(),
        c.netWorth.observeHistory(),
        c.investments.observeItems(),
        c.interest.observe(),
    ) { nw, history, items, interest ->
        WealthState(
            netWorth = nw,
            history = history.takeLast(180).map { ChartPoint(Fmt.date(LocalDate.ofEpochDay(it.epochDay)), it.total) },
            items = items,
            interest = interest,
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
    InvestmentType.FD -> BrandPalette.Ink
    InvestmentType.EPF -> BrandPalette.Emerald
    InvestmentType.NPS -> BrandPalette.Blue
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
    WealthContent(
        state = state,
        hidden = hidden,
        refreshing = refreshing,
        snackbar = snackbar,
        onRefresh = {
            refreshing = true
            scope.launch {
                val msg = vm.refresh()
                refreshing = false
                snackbar.showSnackbar(msg)
            }
        },
        onAdd = { nav.navigate(Routes.addInvestment(it)) },
        onOpen = { nav.navigate(Routes.investment(it)) },
        onNetWorth = { nav.navigate(Routes.NET_WORTH) },
        onOpenAccount = { nav.navigate(Routes.account(it)) },
        onTax = { nav.navigate(Routes.TAX) },
    )
}

/** Stateless Wealth screen; used by the app and by screenshot tests. */
@Composable
fun WealthContent(
    state: WealthState,
    hidden: Boolean,
    refreshing: Boolean = false,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onRefresh: () -> Unit = {},
    onAdd: (InvestmentType) -> Unit = {},
    onOpen: (Long) -> Unit = {},
    onNetWorth: () -> Unit = {},
    onOpenAccount: (Long) -> Unit = {},
    onTax: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item(key = "head") {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Wealth", style = KoshText.screenTitle, modifier = Modifier.weight(1f))
                    if (onTax != null) {
                        androidx.compose.material3.TextButton(onClick = onTax) { Text("Tax", color = KoshTheme.colors.ink) }
                    }
                    if (refreshing) {
                        CircularProgressIndicator(
                            Modifier.padding(12.dp).height(20.dp).width(20.dp),
                            strokeWidth = 2.dp,
                            color = KoshTheme.colors.ink,
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh values", tint = KoshTheme.colors.subtleText)
                        }
                    }
                }
            }
            state.netWorth?.let { nw ->
                item(key = "hero") {
                    Column(
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)
                            .clickable(onClick = onNetWorth),
                    ) {
                        BalanceHeroCard(label = "Net worth", amount = nw.total, hidden = hidden)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            HeroPart("Cash & bank", nw.liquid, hidden)
                            HeroPart("Investments", nw.investments, hidden)
                            HeroPart(if (nw.loans > 0) "Dues & loans" else "Card dues", nw.cardDebt + nw.loans, hidden)
                        }
                    }
                }
                if (nw.investmentsByType.isNotEmpty()) {
                    item(key = "split") {
                        val slices = nw.investmentsByType.entries.sortedByDescending { it.value }
                            .map { ChartSlice(it.key.title(), it.value, it.key.color()) }
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = KoshTheme.spacing.section)) {
                            SectionHeader("Allocation")
                            KoshCard {
                                StackedBar(slices, height = 8.dp, showLegend = false, hidden = hidden)
                                Spacer(Modifier.height(12.dp))
                                slices.forEach { sl ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.foundation.layout.Box(
                                            Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(sl.color),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(sl.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        AmountText(sl.value, style = AmountStyles.small, compact = true, hidden = hidden)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            state.interest?.takeIf { it.items.isNotEmpty() }?.let { interest ->
                item(key = "interest") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = KoshTheme.spacing.section)) {
                        SectionHeader("Savings interest")
                        SavingsInterestCard(interest, hidden, onOpenAccount)
                    }
                }
            }
            val credits = upcomingFdCredits(state.items, com.amitjha.kosh.util.Time.today())
            if (credits.isNotEmpty()) {
                item(key = "fd-interest") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = KoshTheme.spacing.section)) {
                        SectionHeader("FD interest coming up")
                        KoshCard {
                            credits.forEach { (inv, credit) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpen(inv.id) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(inv.entity.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            Fmt.fullDate(credit.date) + if (credit.final) " · with the principal" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = KoshTheme.colors.subtleText,
                                        )
                                    }
                                    AmountText(credit.amount, style = AmountStyles.small, hidden = hidden, color = KoshTheme.colors.income)
                                }
                            }
                        }
                    }
                }
            }
            if (state.history.size >= 2) {
                item(key = "hist") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = KoshTheme.spacing.section)) {
                        SectionHeader("Net worth over time")
                        KoshCard {
                            LineAreaChart(state.history, hidden = hidden)
                        }
                    }
                }
            }
            item(key = "inv-h") {
                SectionHeader("Investments", Modifier.padding(start = 20.dp, end = 20.dp, top = KoshTheme.spacing.section))
            }
            item(key = "add") {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InvestmentType.entries.forEach { t ->
                        SecondaryButton("+ ${t.name}", onClick = { onAdd(t) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (state.loaded && state.items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        "Track FD, EPF and NPS",
                        "Enter the details once. Kosh grows FDs with interest, adds EPF contributions monthly and updates NPS from live NAVs.",
                        Modifier.padding(top = 16.dp),
                        icon = Icons.Outlined.Savings,
                    )
                }
            }
            items(state.items.size, key = { state.items[it].id }) { i ->
                val inv = state.items[i]
                InvestmentRow(inv, hidden, Modifier.padding(start = 20.dp, end = 20.dp, top = if (i == 0) 12.dp else 8.dp).staggeredEntrance(i)) {
                    onOpen(inv.id)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

@Composable
private fun HeroPart(label: String, value: Long, hidden: Boolean) {
    Column {
        Text(tr(label), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        AmountText(value, style = AmountStyles.small, compact = true, hidden = hidden, color = KoshTheme.colors.ink)
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
                val payoutFd = (inv.spec as? com.amitjha.kosh.domain.InvestmentSpec.Fd)?.takeIf { it.paysOut }
                val next = payoutFd?.toFinance(inv.entity.institution, inv.entity.name)?.nextPayout(com.amitjha.kosh.util.Time.today())
                val sub = when (inv.type) {
                    InvestmentType.FD -> if (next != null) {
                        "${inv.entity.institution} · next interest ${Fmt.date(next.date)}"
                    } else {
                        inv.daysToMaturity()?.let { d -> if (d > 0) "${inv.entity.institution} · matures in $d days" else "${inv.entity.institution} · matured" }
                    }
                    InvestmentType.EPF -> "Contributions added monthly"
                    InvestmentType.NPS -> "Updated with live NAV"
                } ?: inv.entity.institution
                Text(sub, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                AmountText(inv.value, style = AmountStyles.medium, hidden = hidden)
                if (payoutFd != null) {
                    // A payout FD doesn't grow; what it pays is the useful figure.
                    Text(
                        if (hidden) "₹ •••• " + payoutFd.payoutFrequency.per() else
                            com.amitjha.kosh.model.Money.format(payoutFd.toFinance(inv.entity.institution, inv.entity.name).regularPayout(), showPaise = false) +
                                " " + payoutFd.payoutFrequency.per(),
                        color = KoshTheme.colors.income,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else inv.gainPct?.let {
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


/** The next interest credit of each payout FD within [FD_CREDIT_WINDOW_DAYS] of [today], soonest first. */
fun upcomingFdCredits(items: List<InvestmentItem>, today: java.time.LocalDate): List<Pair<InvestmentItem, com.amitjha.kosh.finance.FdPayoutCredit>> =
    items.mapNotNull { inv ->
        val spec = (inv.spec as? com.amitjha.kosh.domain.InvestmentSpec.Fd)?.takeIf { it.paysOut } ?: return@mapNotNull null
        val next = spec.toFinance(inv.entity.institution, inv.entity.name).nextPayout(today) ?: return@mapNotNull null
        if (next.date.isAfter(today.plusDays(FD_CREDIT_WINDOW_DAYS))) null else inv to next
    }.sortedBy { it.second.date }

/** How far ahead "FD interest coming up" looks. */
const val FD_CREDIT_WINDOW_DAYS = 35L
