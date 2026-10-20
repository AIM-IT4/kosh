package com.amitjha.kosh.ui.transactions

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.tombstones
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SourceBadge
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.repo.TransactionItem
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.ui.home.TxnRow
import com.amitjha.kosh.ui.nav.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.amitjha.kosh.ui.nav.popBackSafely

data class ReviewState(val items: List<TransactionItem> = emptyList(), val categories: List<CategoryEntity> = emptyList(), val loaded: Boolean = false)

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<ReviewState> = combine(c.transactions.observeUnreviewed(), c.categories.observeAll()) { items, cats ->
        ReviewState(items, cats, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    /** Confirms [t]; the returned action undoes it. */
    suspend fun confirm(t: TransactionItem): suspend () -> Unit {
        c.transactions.markReviewed(t.txn.id)
        return { c.transactions.update(t.txn) }
    }

    /** Confirms everything not held for review; the returned action puts them back. */
    suspend fun confirmAll(): Pair<Int, suspend () -> Unit> {
        val ids = state.value.items.filter { !it.txn.suspicious }.map { it.txn.id }
        c.transactions.markAllReviewed()
        return ids.size to { c.transactions.markUnreviewed(ids) }
    }

    /** Deletes [id]; the returned action brings it back. */
    suspend fun delete(id: Long): (suspend () -> Unit)? {
        val removed = c.tombstones.deleteTransaction(id) ?: return null
        return { c.tombstones.undoDelete(removed) }
    }
    fun recategorise(id: Long, key: String, all: Boolean) = viewModelScope.launch { c.transactions.recategorise(id, key, all) }
    fun markTransfer(id: Long, otherAccountId: Long?) = viewModelScope.launch { c.transactions.markSelfTransfer(id, otherAccountId) }
}

@Composable
fun ReviewScreen(nav: NavController) {
    val vm = koshViewModel { ReviewViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    val haptic = rememberHaptic()
    var editing by remember { mutableStateOf<TransactionItem?>(null) }
    var transferring by remember { mutableStateOf<TransactionItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Every action on this screen can be taken back for a few seconds.
    fun offerUndo(message: String, undo: (suspend () -> Unit)?) {
        if (undo == null) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
        }
    }

    KoshScreen(
        title = "Review",
        subtitle = if (state.items.isEmpty()) null else "${state.items.size} imported from SMS & email",
        onBack = { nav.popBackSafely() },
        snackbar = snackbar,
        actions = {
            if (state.items.isNotEmpty()) {
                TextButton(onClick = {
                    haptic()
                    scope.launch {
                        val (n, undo) = vm.confirmAll()
                        if (n > 0) offerUndo(if (n == 1) "1 confirmed" else "$n confirmed", undo)
                    }
                }) {
                    Icon(Icons.Outlined.DoneAll, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Confirm all")
                }
            }
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            EmptyState(
                title = "All caught up",
                message = "New transactions from your bank SMS and card emails will appear here for a quick check.",
                icon = Icons.Outlined.TaskAlt,
                modifier = Modifier.padding(padding).padding(top = 60.dp),
                actionLabel = "Back to home",
                onAction = { nav.popBackSafely() },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = { it.txn.id }) { t ->
                    KoshCard(Modifier.animateItem().animateContentSize(), contentPadding = PaddingValues(12.dp)) {
                        if (t.txn.suspicious) {
                            Text(
                                "This amount looks unusually large, maybe a reference number read as an amount. It isn't " +
                                    "counted in balances or totals until you confirm it. \"Confirm all\" skips it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoshTheme.colors.warning,
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                            )
                        }
                        TxnRow(t, hidden) { nav.navigate(Routes.txn(t.txn.id)) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                            SourceBadge(t.txn.source)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "${Fmt.dayLabel(t.date)} · ${t.category?.name ?: t.txn.categoryKey}",
                                style = MaterialTheme.typography.labelSmall,
                                color = KoshTheme.colors.subtleText,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            // The main action stands out as a tonal button; the rest stay quiet.
                            FilledTonalButton(
                                onClick = {
                                    haptic()
                                    scope.launch { offerUndo("Confirmed", vm.confirm(t)) }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = KoshTheme.colors.accentTint,
                                    contentColor = KoshTheme.colors.good,
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(if (t.txn.suspicious) "Looks right" else "Confirm", style = MaterialTheme.typography.labelMedium)
                            }
                            ReviewAction("Category", Icons.Outlined.Category, MaterialTheme.colorScheme.primary) { editing = t }
                            ReviewAction("Transfer", Icons.Outlined.SwapHoriz, KoshTheme.colors.transfer) { transferring = t }
                            ReviewAction("Delete", Icons.Outlined.DeleteOutline, KoshTheme.colors.alert) {
                                scope.launch { offerUndo("Deleted", vm.delete(t.txn.id)) }
                            }
                        }
                    }
                }
            }
        }
    }

    transferring?.let { t ->
        SelfTransferSheet(
            currentAccountId = t.txn.accountId,
            isIncome = t.txn.type == com.amitjha.kosh.model.TxnType.INCOME,
            onDismiss = { transferring = null },
            onPick = { other ->
                vm.markTransfer(t.txn.id, other)
                transferring = null
            },
        )
    }

    editing?.let { t ->
        RecategoriseSheet(
            categories = state.categories.filter { it.type == t.txn.type || t.txn.type == com.amitjha.kosh.model.TxnType.TRANSFER },
            currentKey = t.txn.categoryKey,
            merchant = t.txn.merchant,
            onDismiss = { editing = null },
            onPick = { key, all ->
                vm.recategorise(t.txn.id, key, all)
                editing = null
            },
        )
    }
}

@Composable
private fun ReviewAction(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

