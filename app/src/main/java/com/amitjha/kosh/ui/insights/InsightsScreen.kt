package com.amitjha.kosh.ui.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.MiniBarChart
import com.amitjha.kosh.designsystem.components.MiniLineChart
import com.amitjha.kosh.designsystem.components.ProgressRing
import com.amitjha.kosh.designsystem.components.SecondaryButton
import com.amitjha.kosh.designsystem.components.SwipeActionsRow
import com.amitjha.kosh.designsystem.components.accent
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.finance.Insight
import com.amitjha.kosh.finance.InsightAction
import com.amitjha.kosh.finance.InsightChart
import com.amitjha.kosh.finance.InsightMetric
import com.amitjha.kosh.finance.InsightRoute
import com.amitjha.kosh.finance.Severity
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.ui.common.toUi
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.transactions.ActivityFilterRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InsightsViewModel(private val c: AppContainer) : ViewModel() {
    val insights: StateFlow<List<Insight>?> = c.insights.observeInsights().map<List<Insight>, List<Insight>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismiss(id: String) = viewModelScope.launch { c.insights.dismiss(id) }
    fun restoreDismissed() = viewModelScope.launch { c.settings.clearDismissedInsights() }
    suspend fun regenerate() = c.insights.refresh()
}

private fun Severity.heading(): String = when (this) {
    Severity.ALERT -> "Needs attention"
    Severity.WARNING -> "Heads up"
    Severity.TIP -> "Tips"
    Severity.GOOD -> "Going well"
}

/** Navigates to where an insight's action points. */
fun openInsightAction(nav: NavController, action: InsightAction) {
    when (action.route) {
        InsightRoute.CATEGORY -> {
            ActivityFilterRequest.pendingCategory = action.argument
            nav.navigate(Routes.TRANSACTIONS) { launchSingleTop = true }
        }
        InsightRoute.ACTIVITY -> nav.navigate(Routes.TRANSACTIONS) { launchSingleTop = true }
        InsightRoute.BUDGETS, InsightRoute.GOALS -> {
            com.amitjha.kosh.ui.planning.PlanningTabRequest.pending = if (action.route == InsightRoute.GOALS) 1 else 0
            nav.navigate(Routes.PLANNING) { launchSingleTop = true }
        }
        InsightRoute.BILLS -> nav.navigate(Routes.BILLS)
        InsightRoute.WEALTH -> nav.navigate(Routes.WEALTH) { launchSingleTop = true }
        InsightRoute.ACCOUNTS -> nav.navigate(Routes.ACCOUNTS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(nav: NavController) {
    val vm = koshViewModel { InsightsViewModel(it) }
    val insights by vm.insights.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    var refreshing by remember { mutableStateOf(false) }

    KoshScreen(
        title = "Insights",
        subtitle = "Worked out on your phone, from your own data",
        onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = vm::restoreDismissed) { Icon(Icons.Outlined.Restore, contentDescription = "Show dismissed insights") } },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    vm.regenerate()
                    delay(400)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            InsightsContent(
                insights = insights,
                hidden = LocalHideAmounts.current,
                onDismiss = { haptic(); vm.dismiss(it.id) },
                onAction = { openInsightAction(nav, it) },
            )
        }
    }
}

/** Stateless insights list: summary header, then expandable insight cards grouped by severity. */
@Composable
fun InsightsContent(
    insights: List<Insight>?,
    hidden: Boolean,
    onDismiss: (Insight) -> Unit = {},
    onAction: (InsightAction) -> Unit = {},
    expandedByDefault: Set<String> = emptySet(),
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val list = insights
    if (list != null && list.isEmpty()) {
        EmptyState(
            "No insights right now",
            "As you add transactions, budgets and goals, Kosh spots overspending, subscriptions, idle cash and more.",
            Modifier.padding(top = 60.dp),
            icon = Icons.Outlined.AutoAwesome,
        )
        return
    }
    val summary = list.orEmpty().firstOrNull { it.isSummary }
    val cards = list.orEmpty().filterNot { it.isSummary }
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        summary?.let { s -> item(key = "summary") { SummaryCard(s, hidden, onAction) } }
        cards.groupBy { it.severity }.toSortedMap().forEach { (sev, items) ->
            item(key = "h-$sev") {
                Text(
                    sev.heading().uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = sev.toUi().accent().first,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }
            items(items, key = { it.id }) { i ->
                SwipeActionsRow(onDelete = {
                    onDismiss(i)
                    true
                }) {
                    InsightDetailCard(i, hidden, onAction, initiallyExpanded = i.id in expandedByDefault)
                }
            }
        }
        if (cards.isNotEmpty()) {
            item(key = "hint") {
                Text("Tap an insight for details · swipe left to dismiss", style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(s: Insight, hidden: Boolean, onAction: (InsightAction) -> Unit) {
    val score = s.metrics.firstOrNull { it.label == "Score" }?.value?.substringBefore('/')?.toIntOrNull()
    KoshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (score != null) {
                ProgressRing(score / 100f, size = 64.dp, thickness = 6.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$score", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("score", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.tertiaryText)
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("THIS MONTH", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText, fontWeight = FontWeight.Medium)
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(if (hidden) s.message.maskAmounts() else (s.detail ?: s.message), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        MetricChips(s.metrics.filterNot { it.label == "Score" }, hidden)
        s.chart?.let {
            Spacer(Modifier.height(14.dp))
            InsightChartView(it, hidden)
        }
    }
}

@Composable
private fun InsightDetailCard(i: Insight, hidden: Boolean, onAction: (InsightAction) -> Unit, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable(i.id) { mutableStateOf(initiallyExpanded) }
    val (accent, icon) = i.severity.toUi().accent()
    val hasMore = i.detail != null || i.metrics.isNotEmpty() || i.chart != null || i.action != null
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(KoshTheme.colors.cardSurface)
            .border(1.dp, KoshTheme.colors.hairline, shape)
            .clickable(enabled = hasMore) { expanded = !expanded }
            .animateContentSize(tween(200))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(i.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (hidden) i.message.maskAmounts() else i.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasMore) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Show less" else "Show more",
                    tint = KoshTheme.colors.tertiaryText,
                )
            }
        }
        AnimatedVisibility(expanded, enter = fadeIn(tween(180)) + expandVertically(tween(200)), exit = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
            Column(Modifier.padding(start = 44.dp, top = 12.dp)) {
                i.detail?.let {
                    Text(if (hidden) it.maskAmounts() else it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                }
                if (i.metrics.isNotEmpty()) {
                    MetricChips(i.metrics, hidden)
                    Spacer(Modifier.height(12.dp))
                }
                i.chart?.let {
                    InsightChartView(it, hidden)
                    Spacer(Modifier.height(12.dp))
                }
                i.action?.let { a -> SecondaryButton(a.label, onClick = { onAction(a) }, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricChips(metrics: List<InsightMetric>, hidden: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { m ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(KoshTheme.colors.fill)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(m.label, style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText, maxLines = 1)
                Text(if (hidden) m.value.maskAmounts() else m.value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun InsightChartView(chart: InsightChart, hidden: Boolean) {
    Column {
        chart.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.tertiaryText) }
        when (chart.kind) {
            InsightChart.Kind.BARS -> MiniBarChart(chart.labels, chart.values, reference = chart.reference, hidden = hidden)
            InsightChart.Kind.LINE -> MiniLineChart(
                chart.values, reference = chart.reference, hidden = hidden,
                startLabel = chart.labels.firstOrNull(), endLabel = chart.labels.lastOrNull(),
            )
        }
    }
}

/** Replaces rupee amounts with dots for privacy mode. */
private fun String.maskAmounts(): String = replace(Regex("[−-]?₹[0-9,.]+[KLCr]*"), "₹ ••••")
