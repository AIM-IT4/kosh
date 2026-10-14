package com.amitjha.kosh.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.InsightCard
import com.amitjha.kosh.designsystem.components.SwipeActionsRow
import com.amitjha.kosh.designsystem.components.accent
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.finance.Insight
import com.amitjha.kosh.finance.Severity
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.ui.common.toUi
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
    suspend fun regenerate() = c.insights.generate()
}

private fun Severity.heading(): String = when (this) {
    Severity.ALERT -> "Needs attention"
    Severity.WARNING -> "Heads up"
    Severity.TIP -> "Tips"
    Severity.GOOD -> "Going well"
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
        actions = { IconButton(onClick = vm::restoreDismissed) { Icon(Icons.Filled.Restore, contentDescription = "Show dismissed insights") } },
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
            val list = insights
            if (list != null && list.isEmpty()) {
                EmptyState(
                    "No insights right now",
                    "As you add transactions, budgets and goals, Kosh spots overspending, subscriptions, idle cash and more.",
                    Modifier.padding(top = 60.dp),
                    icon = Icons.Filled.AutoAwesome,
                )
                return@PullToRefreshBox
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.orEmpty().groupBy { it.severity }.toSortedMap().forEach { (sev, items) ->
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
                            haptic()
                            vm.dismiss(i.id)
                            true
                        }) {
                            InsightCard(severity = i.severity.toUi(), title = i.title, message = i.message)
                        }
                    }
                }
                if (!list.isNullOrEmpty()) {
                    item(key = "hint") {
                        Text("Swipe an insight left to dismiss it.", style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                    }
                }
            }
        }
    }
}
