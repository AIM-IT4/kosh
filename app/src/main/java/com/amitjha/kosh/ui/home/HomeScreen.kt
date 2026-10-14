package com.amitjha.kosh.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.BalanceHeroCard
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.ChartLegend
import com.amitjha.kosh.designsystem.components.ChartSlice
import com.amitjha.kosh.designsystem.components.DonutChart
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.InsightCard
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.StatChip
import com.amitjha.kosh.designsystem.components.ThinProgressBar
import com.amitjha.kosh.designsystem.components.TransactionRow
import com.amitjha.kosh.designsystem.components.staggeredEntrance
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.finance.Insight
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.AccountWithBalance
import com.amitjha.kosh.repo.BillItem
import com.amitjha.kosh.repo.MonthSummary
import com.amitjha.kosh.repo.NetWorth
import com.amitjha.kosh.repo.TransactionItem
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.bankEnum
import com.amitjha.kosh.ui.common.color
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.common.toUi
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val netWorth: NetWorth? = null,
    val trend: List<Float> = emptyList(),
    val summary: MonthSummary? = null,
    val unreviewed: Int = 0,
    val bills: List<BillItem> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val recent: List<TransactionItem> = emptyList(),
    val accounts: List<AccountWithBalance> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && accounts.isEmpty() && recent.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {

    private val period = c.settings.settings.map { Period.containing(Time.today(), it.monthStartDay) }

    private val summary = period.flatMapLatest { c.transactions.observeSummary(it) }

    private val money = combine(
        c.netWorth.observeNetWorth(),
        c.netWorth.observeHistory(),
        summary,
    ) { nw, history, s -> Triple(nw, history.takeLast(30).map { (it.total / 100.0).toFloat() }, s) }

    private val lists = combine(
        c.transactions.observeUnreviewed(),
        c.bills.observeUnpaid(),
        c.insights.observeInsights(),
        c.transactions.observeRecent(8),
        c.accounts.observeAccounts(),
    ) { unreviewed, bills, insights, recent, accounts -> Lists(unreviewed.size, bills, insights, recent, accounts) }

    val state: StateFlow<HomeState> = combine(money, lists) { (nw, trend, s), l ->
        HomeState(
            loading = false,
            netWorth = nw,
            trend = trend,
            summary = s,
            unreviewed = l.unreviewed,
            bills = l.bills.take(6),
            insights = l.insights.take(5),
            recent = l.recent,
            accounts = l.accounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun toggleHidden(current: Boolean) = viewModelScope.launch { c.settings.setHideAmounts(!current) }

    private data class Lists(
        val unreviewed: Int,
        val bills: List<BillItem>,
        val insights: List<Insight>,
        val recent: List<TransactionItem>,
        val accounts: List<AccountWithBalance>,
    )
}

@Composable
fun HomeScreen(nav: NavController) {
    val vm = koshViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var loadingDemo by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.greeting(), style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                    Text("Your money", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { vm.toggleHidden(hidden) }) {
                    Icon(
                        if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (hidden) "Show amounts" else "Hide amounts",
                    )
                }
                IconButton(onClick = { nav.navigate(Routes.INSIGHTS) }) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Insights")
                }
            }
        }

        item(key = "hero") {
            val nw = state.netWorth
            BalanceHeroCard(
                label = "Net worth",
                amount = nw?.total ?: 0,
                deltaPaise = state.summary?.savings,
                deltaLabel = "saved this month",
                trend = state.trend,
                hidden = hidden,
                onToggleHidden = { vm.toggleHidden(hidden) },
                modifier = Modifier.padding(horizontal = 20.dp),
                footer = if (nw != null) {
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            HeroFooterItem("Cash & bank", nw.liquid, hidden)
                            HeroFooterItem("Investments", nw.investments, hidden)
                            HeroFooterItem("Card dues", -nw.cardDebt, hidden)
                        }
                    }
                } else {
                    null
                },
            )
        }

        item(key = "stats") {
            val s = state.summary
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatChip("Income", s?.income ?: 0, Modifier.weight(1f), type = TxnType.INCOME, hidden = hidden)
                StatChip("Spent", s?.expense ?: 0, Modifier.weight(1f), type = TxnType.EXPENSE, hidden = hidden)
                StatChip("Saved", s?.savings ?: 0, Modifier.weight(1f), hidden = hidden)
            }
        }

        if (state.isEmpty) {
            item(key = "empty") {
                KoshCard(Modifier.padding(horizontal = 20.dp)) {
                    EmptyState(
                        title = "Let's set up Kosh",
                        message = "Add your bank accounts, or scan your SMS inbox to pull in transactions automatically.",
                        icon = Icons.Filled.AccountBalance,
                        actionLabel = "Add an account",
                        onAction = { nav.navigate(Routes.editAccount()) },
                    )
                    Text(
                        if (loadingDemo) "Loading sample data…" else "Or explore with sample data",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !loadingDemo) {
                                loadingDemo = true
                                scope.launch {
                                    container.demoData.load()
                                    container.investments.revalueAll(fetchNavs = false)
                                    loadingDemo = false
                                }
                            }
                            .padding(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        if (state.unreviewed > 0) {
            item(key = "review") {
                ReviewBanner(state.unreviewed) { nav.navigate(Routes.REVIEW) }
            }
        }

        if (state.bills.isNotEmpty()) {
            item(key = "bills-h") { SectionHeader("Card bills", Modifier.padding(horizontal = 20.dp), "See all") { nav.navigate(Routes.BILLS) } }
            item(key = "bills") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.bills, key = { it.bill.id }) { b -> BillMiniCard(b, hidden) { nav.navigate(Routes.BILLS) } }
                }
            }
        }

        if (state.insights.isNotEmpty()) {
            item(key = "ins-h") { SectionHeader("Insights", Modifier.padding(horizontal = 20.dp), "See all") { nav.navigate(Routes.INSIGHTS) } }
            item(key = "ins") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.insights, key = { it.id }) { i ->
                        InsightCard(
                            severity = i.severity.toUi(),
                            title = i.title,
                            message = i.message,
                            compact = true,
                            modifier = Modifier.width(290.dp),
                            onClick = { nav.navigate(Routes.INSIGHTS) },
                        )
                    }
                }
            }
        }

        val spend = state.summary?.byCategory.orEmpty()
        if (spend.isNotEmpty()) {
            item(key = "donut-h") {
                SectionHeader("Where your money went", Modifier.padding(horizontal = 20.dp), "Details") { nav.navigate(Routes.TRANSACTIONS) }
            }
            item(key = "donut") {
                val slices = remember(spend) {
                    spend.take(7).map { ChartSlice(it.category?.name ?: it.categoryKey, it.total, it.category.color()) }
                }
                KoshCard(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(
                            slices = slices,
                            size = 150.dp,
                            thickness = 20.dp,
                            centerLabel = "Spent",
                            centerValue = state.summary?.expense,
                            hidden = hidden,
                        )
                        Spacer(Modifier.width(16.dp))
                        ChartLegend(slices, Modifier.weight(1f), hidden = hidden, maxItems = 5)
                    }
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "recent-h") {
                SectionHeader("Recent activity", Modifier.padding(horizontal = 20.dp), "See all") { nav.navigate(Routes.TRANSACTIONS) }
            }
            items(state.recent.size, key = { "r" + state.recent[it].txn.id }) { i ->
                val t = state.recent[i]
                TxnRow(t, hidden, Modifier.padding(horizontal = 16.dp).staggeredEntrance(i)) { nav.navigate(Routes.txn(t.txn.id)) }
            }
        }

        if (state.accounts.isNotEmpty()) {
            item(key = "acc-h") {
                SectionHeader("Accounts", Modifier.padding(horizontal = 20.dp), "Manage") { nav.navigate(Routes.ACCOUNTS) }
            }
            item(key = "acc") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.accounts, key = { it.id }) { a -> AccountMiniCard(a, hidden) { nav.navigate(Routes.account(a.id)) } }
                }
            }
        }
    }
}

@Composable
private fun HeroFooterItem(label: String, value: Long, hidden: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        AmountText(value, style = AmountStyles.small, compact = true, hidden = hidden, color = Color.White)
    }
}

@Composable
private fun ReviewBanner(count: Int, onClick: () -> Unit) {
    KoshCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.MarkEmailUnread, contentDescription = null, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$count new transaction${if (count == 1) "" else "s"} to review", fontWeight = FontWeight.SemiBold)
                Text("Picked up from your SMS and email", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun BillMiniCard(b: BillItem, hidden: Boolean, onClick: () -> Unit) {
    val days = b.daysLeft
    val urgency = when {
        days == null -> KoshTheme.colors.subtleText
        days < 0 -> KoshTheme.colors.alert
        days <= 3 -> KoshTheme.colors.warning
        else -> KoshTheme.colors.good
    }
    val bank = b.card?.bankEnum() ?: com.amitjha.kosh.model.Bank.OTHER
    KoshCard(Modifier.width(220.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(bank, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(b.card?.label() ?: "Card", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(12.dp))
        AmountText(b.bill.totalDue, style = AmountStyles.large, hidden = hidden)
        Spacer(Modifier.height(6.dp))
        Text(days?.let { Fmt.relativeDays(it) } ?: "No due date", color = urgency, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AccountMiniCard(a: AccountWithBalance, hidden: Boolean, onClick: () -> Unit) {
    KoshCard(Modifier.width(180.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(a.bank, size = 30.dp)
            Spacer(Modifier.width(8.dp))
            Text(a.account.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(12.dp))
        if (a.isCard) {
            Text("Outstanding", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
            AmountText(a.outstanding, style = AmountStyles.medium, hidden = hidden)
            a.utilisation?.let {
                Spacer(Modifier.height(8.dp))
                ThinProgressBar(it.toFloat().coerceIn(0f, 1f), color = if (it > 0.3) KoshTheme.colors.warning else KoshTheme.colors.good)
            }
        } else {
            Text("Balance", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
            AmountText(a.balance, style = AmountStyles.medium, hidden = hidden)
        }
    }
}

/** A transaction row with the standard subtitle. */
@Composable
fun TxnRow(t: TransactionItem, hidden: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val title = t.txn.merchant ?: t.category?.name ?: "Transaction"
    val account = t.account?.label() ?: ""
    val sub = buildString {
        if (t.txn.type == TxnType.TRANSFER && t.toAccount != null) append("${t.account?.name ?: ""} → ${t.toAccount.name}")
        else append(account)
        append(" · ")
        append(Fmt.time(t.txn.timestamp))
    }
    TransactionRow(
        title = title,
        subtitle = sub,
        amount = t.txn.amount,
        type = t.txn.type,
        iconName = t.category?.icon,
        categoryColor = t.category.color(),
        modifier = modifier,
        source = t.txn.source,
        unreviewed = !t.txn.reviewed,
        hidden = hidden,
        onClick = onClick,
    )
}
