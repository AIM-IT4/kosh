package com.amitjha.kosh.ui.transactions

import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.theme.KoshText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
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
import com.amitjha.kosh.data.tombstones
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
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.repo.TransactionRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val F_EXPENSE = "Expenses"
private const val F_INCOME = "Income"
private const val F_TRANSFER = "Transfers"
internal const val F_SMS = "From SMS"
private const val F_MANUAL = "Manual"

/** Opens the fix-uncategorised screen rather than filtering the list. */
internal const val F_OTHER = "Other"
/** Transactions marked as household (shown only when household sync exists in this edition). */
private const val F_HOUSEHOLD = "Household"
private val FILTERS = listOf(F_SMS, F_OTHER, F_EXPENSE, F_INCOME, F_TRANSFER, F_MANUAL) +
    listOfNotNull(F_HOUSEHOLD.takeIf { com.amitjha.kosh.ui.household.householdAvailable })

/** One-shot filter requested by another screen (e.g. Settings → "View email transactions"). */
object ActivityFilterRequest {
    @Volatile var pending: String? = null

    /** A category key to filter by, set by insights and the Home category chart before navigating. */
    @Volatile var pendingCategory: String? = null

    fun consume(): String? = pending.also { pending = null }
    fun consumeCategory(): String? = pendingCategory.also { pendingCategory = null }
}

internal const val CATEGORY_CHIP_PREFIX = "Category: "

data class DayGroup(val date: LocalDate, val items: List<TransactionItem>, val net: Long)

data class TxnListState(
    val period: Period,
    val query: String = "",
    val filters: Set<String> = emptySet(),
    val groups: List<DayGroup> = emptyList(),
    val income: Long = 0,
    val expense: Long = 0,
    val loaded: Boolean = false,
    /** Name of the category being filtered, if any; shown as a removable chip. */
    val categoryName: String? = null,
    /** Imported expenses this period still in "Other". */
    val uncategorised: Int = 0,
    /** True when a category, type, source or search narrows the list; the totals then cover only what's shown. */
    val narrowed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(private val c: AppContainer) : ViewModel() {
    /** Months before the current budgeting month (0 = this month). */
    private val offset = MutableStateFlow(0)
    private val period = combine(c.settings.settings, offset) { s, o ->
        var p = Period.containing(Time.today(), s.monthStartDay)
        repeat(o) { p = p.previous() }
        p
    }.distinctUntilChanged()
    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow<Set<String>>(emptySet())
    private val category = MutableStateFlow<String?>(null)

    val state: StateFlow<TxnListState> = combine(period, query, filters, category) { p, q, f, cat -> listOf(p, q, f, cat) }
        .flatMapLatest { values ->
            val p = values[0] as Period
            val q = values[1] as String
            @Suppress("UNCHECKED_CAST") val f = values[2] as Set<String>
            val cat = values[3] as String?
            c.transactions.observe(TxnFilter(period = p, query = q, categoryKey = cat)).let { flow ->
                combine(flow, c.categories.observeAll(), c.household.observeSharedIds(com.amitjha.kosh.sync.HouseholdKind.TXN)) { list, cats, shared ->
                    val filtered = list.filter { matches(it, f) && (F_HOUSEHOLD !in f || it.txn.id in shared) }
                    // Totals come from exactly the rows shown, with the same rules as Home and Insights
                    // (transfers, excluded and held-for-review rows don't count).
                    val summary = TransactionRepository.summarise(p, filtered.map { it.txn }, cats.associateBy { it.key })
                    val groups = filtered.groupBy { it.date }.toSortedMap(compareByDescending { it })
                        .map { (d, items) -> DayGroup(d, items, items.sumOf { it.signedAmount }) }
                    val catName = cat?.let { key -> list.firstOrNull()?.category?.name ?: c.categories.byKey(key)?.name ?: key }
                    val other = list.count {
                        it.txn.type == TxnType.EXPENSE && it.txn.categoryKey == "other" && it.txn.source != TxnSource.MANUAL
                    }
                    TxnListState(
                        p, q, f, groups, summary.income, summary.expense, loaded = true, categoryName = catName,
                        uncategorised = other, narrowed = cat != null || f.isNotEmpty() || q.isNotBlank(),
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnListState(Period.containing(Time.today())))

    /** The period currently shown (for callers that need it synchronously). */
    val currentPeriod: Period get() = state.value.period

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
                F_MANUAL -> TxnSource.MANUAL
                else -> null
            }
        }
        return (types.isEmpty() || t.txn.type in types) && (sources.isEmpty() || t.txn.source in sources)
    }

    fun previous() { offset.value = offset.value + 1 }
    fun next() { offset.value = (offset.value - 1).coerceAtLeast(0) }
    fun setQuery(q: String) { query.value = q }
    fun toggle(f: String) {
        if (f.startsWith(CATEGORY_CHIP_PREFIX)) {
            category.value = null
            return
        }
        filters.value = if (f in filters.value) filters.value - f else filters.value + f
    }
    /** Opened from Home or an insight: show this budgeting month's [key] transactions, matching the figure tapped. */
    fun filterCategory(key: String) {
        offset.value = 0
        filters.value = emptySet()
        query.value = ""
        category.value = key
    }
    fun only(f: String) { filters.value = setOf(f) }

    /** Deletes the transaction (remembering an imported one so a rescan doesn't bring it back) and returns it for Undo. */
    suspend fun delete(id: Long): TransactionEntity? = c.tombstones.deleteTransaction(id)

    fun restore(t: TransactionEntity) = viewModelScope.launch { c.tombstones.undoDelete(t) }
    fun markReviewed(id: Long) = viewModelScope.launch { c.transactions.markReviewed(id) }
}

@Composable
fun TransactionsScreen(nav: NavController) {
    val vm = koshViewModel { TransactionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ActivityFilterRequest.consume()?.let(vm::only)
        ActivityFilterRequest.consumeCategory()?.let(vm::filterCategory)
    }
    val hidden = LocalHideAmounts.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    TransactionsContent(
        state = state,
        hidden = hidden,
        snackbar = snackbar,
        onPrevious = vm::previous,
        onNext = vm::next,
        onQuery = vm::setQuery,
        onToggleFilter = { f -> if (f == F_OTHER) nav.navigate(Routes.UNCATEGORISED) else vm.toggle(f) },
        onFixUncategorised = { nav.navigate(Routes.UNCATEGORISED) },
        onDelete = { t ->
            haptic()
            scope.launch {
                val removed = vm.delete(t.txn.id) ?: return@launch
                val r = snackbar.showSnackbar("Transaction deleted", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) vm.restore(removed)
            }
        },
        onReviewed = { t ->
            haptic()
            vm.markReviewed(t.txn.id)
        },
        onOpen = { t -> nav.navigate(Routes.txn(t.txn.id)) },
    )
}

/** Stateless Activity screen; used by the app and by screenshot tests. */
@Composable
fun TransactionsContent(
    state: TxnListState,
    hidden: Boolean,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onQuery: (String) -> Unit = {},
    onToggleFilter: (String) -> Unit = {},
    onDelete: (TransactionItem) -> Unit = {},
    onReviewed: (TransactionItem) -> Unit = {},
    onOpen: (TransactionItem) -> Unit = {},
    onFixUncategorised: () -> Unit = {},
) {
    val canGoNext = !state.period.endExclusive.isAfter(Time.today())
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item(key = "head") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                    Text("Activity", style = KoshText.screenTitle)
                    MonthSwitcher(
                        label = Fmt.periodLabel(state.period),
                        onPrevious = onPrevious,
                        onNext = onNext,
                        canGoNext = canGoNext,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                        if (state.narrowed) {
                            Text(
                                (state.categoryName?.let { "$it · " } ?: "") + "totals for the transactions shown",
                                style = MaterialTheme.typography.labelSmall,
                                color = KoshTheme.colors.subtleText,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Row(Modifier.fillMaxWidth()) {
                            StatChip("Income", state.income, Modifier.weight(1f), type = TxnType.INCOME, hidden = hidden)
                            StatChip("Spent", state.expense, Modifier.weight(1f), type = TxnType.EXPENSE, hidden = hidden)
                            StatChip("Net", state.income - state.expense, Modifier.weight(1f), hidden = hidden)
                        }
                    }
                    KoshTextField(
                        value = state.query,
                        onValue = onQuery,
                        label = "Search merchant, note or category",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item(key = "filters") {
                val categoryChip = state.categoryName?.let { "$CATEGORY_CHIP_PREFIX$it" }
                FilterChipsRow(
                    options = listOfNotNull(categoryChip) + FILTERS,
                    selected = state.filters + listOfNotNull(categoryChip),
                    onToggle = onToggleFilter,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (state.uncategorised > com.amitjha.kosh.ui.home.UNCATEGORISED_BANNER_MIN) {
                item(key = "uncategorised") {
                    UncategorisedBanner(state.uncategorised, onFixUncategorised, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
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
                        icon = if (state.query.isNotBlank()) Icons.Outlined.SearchOff else Icons.AutoMirrored.Outlined.ReceiptLong,
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            }
            state.groups.forEach { g ->
                item(key = "d" + g.date) {
                    DateHeader(Fmt.dayLabel(g.date), Modifier.padding(horizontal = 20.dp), netPaise = g.net, hidden = hidden)
                }
                items(g.items, key = { it.txn.id }) { t ->
                    SwipeActionsRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onDelete = {
                            onDelete(t)
                            true
                        },
                        onConfirm = if (!t.txn.reviewed) {
                            {
                                onReviewed(t)
                                false
                            }
                        } else {
                            null
                        },
                    ) {
                        TxnRow(t, hidden, Modifier.fillMaxWidth()) { onOpen(t) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

