package com.amitjha.kosh.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.designsystem.components.DateHeader
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.FilterChipsRow
import com.amitjha.kosh.designsystem.components.MonthSwitcher
import com.amitjha.kosh.designsystem.components.StatChip
import com.amitjha.kosh.designsystem.components.SwipeActionsRow
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.TransactionItem
import com.amitjha.kosh.repo.TxnFilter
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.ui.home.TxnRow
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val F_EXPENSE = "Expenses"
private const val F_INCOME = "Income"
private const val F_TRANSFER = "Transfers"
private const val F_SMS = "From SMS"
private const val F_EMAIL = "From email"
private const val F_MANUAL = "Manual"
private val FILTERS = listOf(F_EXPENSE, F_INCOME, F_TRANSFER, F_SMS, F_EMAIL, F_MANUAL)

data class DayGroup(val date: LocalDate, val items: List<TransactionItem>, val net: Long)

data class TxnListState(
    val period: Period,
    val query: String = "",
    val filters: Set<String> = emptySet(),
    val groups: List<DayGroup> = emptyList(),
    val income: Long = 0,
    val expense: Long = 0,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(private val c: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(Period.containing(Time.today()))
    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow<Set<String>>(emptySet())

    init {
        viewModelScope.launch {
            period.value = Period.containing(Time.today(), c.settings.settings.first().monthStartDay)
        }
    }

    val state: StateFlow<TxnListState> = combine(period, query, filters) { p, q, f -> Triple(p, q, f) }
        .flatMapLatest { (p, q, f) ->
            c.transactions.observe(TxnFilter(period = p, query = q)).let { flow ->
                combine(flow, c.transactions.observeSummary(p)) { list, summary ->
                    val filtered = list.filter { matches(it, f) }
                    val groups = filtered.groupBy { it.date }.toSortedMap(compareByDescending { it })
                        .map { (d, items) -> DayGroup(d, items, items.sumOf { it.signedAmount }) }
                    TxnListState(p, q, f, groups, summary.income, summary.expense, loaded = true)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnListState(Period.containing(Time.today())))

    private fun matches(t: TransactionItem, f: Set<String>): Boolean {
        val types = f.mapNotNull {
            when (it) {
                F_EXPENSE -> TxnType.EXPENSE
                F_INCOME -> TxnType.INCOME
                F_TRANSFER -> TxnType.TRANSFER
                else -> null
            }
        }
        val sources = f.mapNotNull {
            when (it) {
                F_SMS -> TxnSource.SMS
                F_EMAIL -> TxnSource.EMAIL
                F_MANUAL -> TxnSource.MANUAL
                else -> null
            }
        }
        return (types.isEmpty() || t.txn.type in types) && (sources.isEmpty() || t.txn.source in sources)
    }

    fun previous() { period.value = period.value.previous() }
    fun next() { period.value = period.value.next() }
    fun setQuery(q: String) { query.value = q }
    fun toggle(f: String) { filters.value = if (f in filters.value) filters.value - f else filters.value + f }

    suspend fun delete(id: Long): TransactionEntity? {
        val t = c.transactions.byId(id) ?: return null
        c.transactions.delete(id)
        return t
    }

    fun restore(t: TransactionEntity) = viewModelScope.launch { c.transactions.add(t.copy(id = 0)) }
    fun markReviewed(id: Long) = viewModelScope.launch { c.transactions.markReviewed(id) }
}

@Composable
fun TransactionsScreen(nav: NavController) {
    val vm = koshViewModel { TransactionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val canGoNext = !state.period.endExclusive.isAfter(Time.today())

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item(key = "head") {
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    MonthSwitcher(
                        label = Fmt.month(state.period.label),
                        onPrevious = vm::previous,
                        onNext = vm::next,
                        canGoNext = canGoNext,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip("Income", state.income, Modifier.weight(1f), type = TxnType.INCOME, hidden = hidden)
                        StatChip("Spent", state.expense, Modifier.weight(1f), type = TxnType.EXPENSE, hidden = hidden)
                    }
                    KoshTextField(
                        value = state.query,
                        onValue = vm::setQuery,
                        label = "Search merchant, note or category",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item(key = "filters") {
                FilterChipsRow(options = FILTERS, selected = state.filters, onToggle = vm::toggle, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (state.loaded && state.groups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (state.query.isNotBlank() || state.filters.isNotEmpty()) "No matches" else "Nothing here yet",
                        message = if (state.query.isNotBlank() || state.filters.isNotEmpty()) {
                            "Try a different search or clear the filters."
                        } else {
                            "Transactions for ${Fmt.month(state.period.label)} will show up here. Tap + to add one."
                        },
                        icon = if (state.query.isNotBlank()) Icons.Filled.SearchOff else Icons.AutoMirrored.Filled.ReceiptLong,
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            }
            state.groups.forEach { g ->
                item(key = "d" + g.date) {
                    DateHeader(Fmt.dayLabel(g.date), Modifier.padding(horizontal = 16.dp), netPaise = g.net, hidden = hidden)
                }
                items(g.items, key = { it.txn.id }) { t ->
                    SwipeActionsRow(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        onDelete = {
                            haptic()
                            scope.launch {
                                val removed = vm.delete(t.txn.id) ?: return@launch
                                val r = snackbar.showSnackbar("Transaction deleted", "Undo", duration = SnackbarDuration.Short)
                                if (r == SnackbarResult.ActionPerformed) vm.restore(removed)
                            }
                            true
                        },
                        onConfirm = if (!t.txn.reviewed) {
                            {
                                haptic()
                                vm.markReviewed(t.txn.id)
                                false
                            }
                        } else {
                            null
                        },
                    ) {
                        TxnRow(t, hidden, Modifier.fillMaxWidth()) { nav.navigate(Routes.txn(t.txn.id)) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

