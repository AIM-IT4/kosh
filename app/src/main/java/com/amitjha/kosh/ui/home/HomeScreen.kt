package com.amitjha.kosh.ui.home

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.text.style.TextAlign
import com.amitjha.kosh.designsystem.components.StackedBar
import com.amitjha.kosh.designsystem.theme.KoshText
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    val reviewFromSms: Int = 0,
    val reviewFromEmail: Int = 0,
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
    ) { unreviewed, bills, insights, recent, accounts ->
        Lists(
            unreviewed.size,
            unreviewed.count { it.txn.source == com.amitjha.kosh.model.TxnSource.SMS },
            unreviewed.count { it.txn.source == com.amitjha.kosh.model.TxnSource.EMAIL },
            bills, insights, recent, accounts,
        )
    }

    val state: StateFlow<HomeState> = combine(money, lists) { (nw, trend, s), l ->
        HomeState(
            loading = false,
            netWorth = nw,
            trend = trend,
            summary = s,
            unreviewed = l.unreviewed,
            reviewFromSms = l.fromSms,
            reviewFromEmail = l.fromEmail,
            bills = l.bills.take(6),
            insights = l.insights.take(5),
            recent = l.recent,
            accounts = l.accounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun toggleHidden(current: Boolean) = viewModelScope.launch { c.settings.setHideAmounts(!current) }

    private data class Lists(
        val unreviewed: Int,
        val fromSms: Int,
        val fromEmail: Int,
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
    HomeContent(
        state = state,
        hidden = hidden,
        loadingDemo = loadingDemo,
        onToggleHidden = { vm.toggleHidden(hidden) },
        onInsights = { nav.navigate(Routes.INSIGHTS) },
        onReview = { nav.navigate(Routes.REVIEW) },
        onBills = { nav.navigate(Routes.BILLS) },
        onActivity = { nav.navigate(Routes.TRANSACTIONS) },
        onTransaction = { nav.navigate(Routes.txn(it)) },
        onAccounts = { nav.navigate(Routes.ACCOUNTS) },
        onAccount = { nav.navigate(Routes.account(it)) },
        onAddAccount = { nav.navigate(Routes.editAccount()) },
        onLoadDemo = {
            loadingDemo = true
            scope.launch {
                container.demoData.load()
                container.investments.revalueAll(fetchNavs = false)
                loadingDemo = false
            }
        },
    )
}

/** Stateless Home screen, rendered from [HomeState]; used by the app and by screenshot tests. */
@Composable
fun HomeContent(
    state: HomeState,
    hidden: Boolean,
    loadingDemo: Boolean = false,
    onToggleHidden: () -> Unit = {},
    onInsights: () -> Unit = {},
    onReview: () -> Unit = {},
    onBills: () -> Unit = {},
    onActivity: () -> Unit = {},
    onTransaction: (Long) -> Unit = {},
    onAccounts: () -> Unit = {},
    onAccount: (Long) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onLoadDemo: () -> Unit = {},
) {
    val pad = Modifier.padding(horizontal = 20.dp)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.todayLong(), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    Text(Fmt.greeting(), style = KoshText.screenTitle)
                }
                IconButton(onClick = onToggleHidden) {
                    Icon(
                        if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (hidden) "Show amounts" else "Hide amounts",
                        tint = KoshTheme.colors.subtleText,
                    )
                }
                IconButton(onClick = onInsights) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = "Insights", tint = KoshTheme.colors.subtleText)
                }
            }
        }

        item(key = "hero") {
            val nw = state.netWorth
            Column(pad.padding(top = 20.dp)) {
                BalanceHeroCard(
                    label = "Net worth",
                    amount = nw?.total ?: 0,
                    trend = state.trend,
                    hidden = hidden,
                )
                if (nw != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        MiniFigure("Cash & bank", nw.liquid, hidden)
                        MiniFigure("Investments", nw.investments, hidden)
                        MiniFigure("Card dues", nw.cardDebt, hidden)
                    }
                }
            }
        }

        item(key = "stats") {
            val s = state.summary
            Column(pad.padding(top = KoshTheme.spacing.section)) {
                SectionHeader("This month")
                KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        StatChip("Income", s?.income ?: 0, Modifier.weight(1f), type = TxnType.INCOME, hidden = hidden)
                        VerticalHairline()
                        StatChip("Spent", s?.expense ?: 0, Modifier.weight(1f).padding(start = 14.dp), type = TxnType.EXPENSE, hidden = hidden)
                        VerticalHairline()
                        StatChip("Saved", s?.savings ?: 0, Modifier.weight(1f).padding(start = 14.dp), hidden = hidden)
                    }
                }
            }
        }

        if (state.unreviewed > 0) {
            item(key = "review") {
                ReviewBanner(state.unreviewed, pad.padding(top = 16.dp), onReview, state.reviewFromSms, state.reviewFromEmail)
            }
        }

        if (state.isEmpty) {
            item(key = "empty") {
                KoshCard(pad.padding(top = KoshTheme.spacing.section)) {
                    EmptyState(
                        title = "Set up Kosh",
                        message = "Add your bank accounts, or scan your SMS inbox to pull in transactions automatically.",
                        icon = Icons.Outlined.AccountBalance,
                        actionLabel = "Add an account",
                        onAction = onAddAccount,
                    )
                    Text(
                        if (loadingDemo) "Loading sample data…" else "Explore with sample data",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !loadingDemo, onClick = onLoadDemo)
                            .padding(12.dp),
                        color = KoshTheme.colors.accent,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (state.bills.isNotEmpty()) {
            item(key = "bills") {
                Column(pad.padding(top = KoshTheme.spacing.section)) {
                    SectionHeader("Upcoming bills", actionLabel = "See all", onAction = onBills)
                    KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        state.bills.take(3).forEachIndexed { i, b ->
                            if (i > 0) ListHairline()
                            BillListRow(b, hidden, onBills)
                        }
                    }
                }
            }
        }

        state.insights.firstOrNull()?.let { top ->
            item(key = "insight") {
                Column(pad.padding(top = KoshTheme.spacing.section)) {
                    SectionHeader("Insight", actionLabel = if (state.insights.size > 1) "All ${state.insights.size}" else null, onAction = onInsights)
                    InsightCard(
                        severity = top.severity.toUi(),
                        title = top.title,
                        message = top.message,
                        compact = true,
                        onClick = onInsights,
                    )
                }
            }
        }

        val spend = state.summary?.byCategory.orEmpty()
        if (spend.isNotEmpty()) {
            item(key = "spend") {
                val slices = remember(spend) {
                    spend.take(6).map { ChartSlice(it.category?.name ?: it.categoryKey, it.total, it.category.color()) }
                }
                Column(pad.padding(top = KoshTheme.spacing.section)) {
                    SectionHeader("Spending", actionLabel = "Details", onAction = onActivity)
                    KoshCard {
                        StackedBar(slices = slices, height = 8.dp, showLegend = false, hidden = hidden)
                        Spacer(Modifier.height(12.dp))
                        spend.take(5).forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(c.category.color()))
                                Spacer(Modifier.width(10.dp))
                                Text(c.category?.name ?: c.categoryKey, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(Fmt.pct(c.share * 100, 0), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.tertiaryText)
                                Spacer(Modifier.width(12.dp))
                                AmountText(c.total, style = AmountStyles.small, hidden = hidden, compact = true)
                            }
                        }
                    }
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "recent") {
                Column(pad.padding(top = KoshTheme.spacing.section)) {
                    SectionHeader("Recent", actionLabel = "See all", onAction = onActivity)
                    KoshCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                        state.recent.forEachIndexed { i, t ->
                            if (i > 0) ListHairline(start = 48.dp)
                            TxnRow(t, hidden) { onTransaction(t.txn.id) }
                        }
                    }
                }
            }
        }

        if (state.accounts.isNotEmpty()) {
            item(key = "accounts") {
                Column(pad.padding(top = KoshTheme.spacing.section)) {
                    SectionHeader("Accounts", actionLabel = "Manage", onAction = onAccounts)
                    KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        state.accounts.forEachIndexed { i, a ->
                            if (i > 0) ListHairline(start = 48.dp)
                            AccountListRow(a, hidden) { onAccount(a.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniFigure(label: String, value: Long, hidden: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        AmountText(value, style = AmountStyles.small, compact = true, hidden = hidden, color = KoshTheme.colors.ink)
    }
}

@Composable
private fun VerticalHairline() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(KoshTheme.colors.divider))
}

/** A 1dp divider for rows inside a card, optionally inset from the start. */
@Composable
fun ListHairline(start: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(1.dp).background(KoshTheme.colors.divider))
}

@Composable
private fun ReviewBanner(count: Int, modifier: Modifier, onClick: () -> Unit, fromSms: Int = 0, fromEmail: Int = 0) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(KoshTheme.colors.accentTint)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(KoshTheme.colors.accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$count transaction${if (count == 1) "" else "s"} to review",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = KoshTheme.colors.ink,
            )
            if (fromSms + fromEmail > 0) {
                Text(
                    "Imported from SMS ($fromSms) & email ($fromEmail)",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
        }
        Text("Review", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.accent)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = KoshTheme.colors.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BillListRow(b: BillItem, hidden: Boolean, onClick: () -> Unit) {
    val days = b.daysLeft
    val urgent = days != null && days <= 3
    val dueColor = when {
        days != null && days < 0 -> KoshTheme.colors.alert
        urgent -> KoshTheme.colors.warning
        else -> KoshTheme.colors.subtleText
    }
    val bank = b.card?.bankEnum() ?: com.amitjha.kosh.model.Bank.OTHER
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BankBadge(bank, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.card?.label() ?: "Card", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(days?.let { Fmt.relativeDays(it) } ?: "No due date", style = MaterialTheme.typography.bodySmall, color = dueColor)
        }
        AmountText(b.bill.totalDue, style = AmountStyles.medium, hidden = hidden)
    }
}

@Composable
private fun AccountListRow(a: AccountWithBalance, hidden: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BankBadge(a.bank, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (a.isCard) "Outstanding" else a.account.type.label(),
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
            )
        }
        AmountText(if (a.isCard) a.outstanding else a.balance, style = AmountStyles.medium, hidden = hidden)
    }
}

@Composable
fun BillMiniCard(b: BillItem, hidden: Boolean, onClick: () -> Unit) {
    KoshCard(Modifier.width(220.dp), onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
        BillListRow(b, hidden, onClick)
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
