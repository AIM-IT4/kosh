package com.amitjha.kosh.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.tombstones
import com.amitjha.kosh.ui.common.launchGuarded
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.CategoryIcon
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SourceBadge
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.repo.TransactionItem
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.color
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.nav.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.amitjha.kosh.ui.nav.popBackSafely

data class TxnDetailState(val item: TransactionItem? = null, val categories: List<CategoryEntity> = emptyList(), val loaded: Boolean = false)

class TransactionDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val state: StateFlow<TxnDetailState> = combine(
        c.transactions.observeItem(id),
        c.categories.observeAll(),
    ) { item, cats -> TxnDetailState(item, cats, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnDetailState())

    fun recategorise(key: String, all: Boolean) = viewModelScope.launch { c.transactions.recategorise(id, key, all) }
    fun markSelfTransfer(otherAccountId: Long?) = viewModelScope.launch { c.transactions.markSelfTransfer(id, otherAccountId) }
    fun markReviewed() = viewModelScope.launch { c.transactions.markReviewed(id) }
    private val deleteGuard = com.amitjha.kosh.ui.common.ActionGuard()

    fun delete(onDone: () -> Unit) = launchGuarded(deleteGuard) {
        c.tombstones.deleteTransaction(id)
        onDone()
    }
}

@Composable
fun TransactionDetailScreen(nav: NavController, id: Long) {
    val vm = koshViewModel(key = "txn-$id") { TransactionDetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    var recategorise by remember { mutableStateOf(false) }
    var selfTransfer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val item = state.item

    KoshScreen(
        title = "Transaction",
        onBack = { nav.popBackSafely() },
        actions = {
            if (item != null) {
                IconButton(onClick = { nav.navigate(Routes.editTxn(id)) }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete") }
            }
        },
    ) { padding ->
        if (item == null) {
            if (state.loaded) {
                EmptyState("Transaction not found", "It may have been deleted.", Modifier.padding(padding).padding(top = 60.dp))
            }
            return@KoshScreen
        }
        val t = item.txn
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CategoryIcon(item.category?.icon, item.category.color(), size = 64.dp)
                Spacer(Modifier.height(12.dp))
                Text(t.merchant ?: item.category?.name ?: "Transaction", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                AmountText(t.amount, style = AmountStyles.hero, type = t.type, showPaise = true, hidden = hidden, animate = true)
                Spacer(Modifier.height(8.dp))
                SourceBadge(t.source)
            }
            if (!t.reviewed) {
                KoshCard(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Text("Imported automatically", fontWeight = FontWeight.SemiBold)
                    Text("Check the category and account, then confirm.", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::markReviewed) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                        Spacer(Modifier.padding(start = 6.dp))
                        Text("Looks right")
                    }
                }
            }
            KoshCard {
                DetailRow("Category", item.category?.name ?: t.categoryKey, accent = true, onClick = {
                    if (t.type == com.amitjha.kosh.model.TxnType.TRANSFER) nav.navigate(Routes.editTxn(id)) { launchSingleTop = true }
                    else recategorise = true
                })
                Divider()
                DetailRow(if (t.toAccountId != null) "From" else "Account", item.account?.label() ?: "—")
                item.toAccount?.let {
                    Divider()
                    DetailRow("To", it.label())
                }
                Divider()
                DetailRow("Date", "${Fmt.dayLabel(item.date)}, ${Fmt.time(t.timestamp)}")
                if (t.channel != TxnChannel.OTHER) {
                    Divider()
                    DetailRow("Paid via", t.channel.name.replace('_', ' '))
                }
                t.referenceNo?.let {
                    Divider()
                    DetailRow("Reference", it)
                }
                Divider()
                DetailRow("Source", t.source.label())
                t.note?.let {
                    Divider()
                    DetailRow("Note", it)
                }
                if (t.excludeFromStats) {
                    Divider()
                    DetailRow("Stats", "Not counted in spending")
                }
                if (t.type != com.amitjha.kosh.model.TxnType.TRANSFER && t.categoryKey != com.amitjha.kosh.domain.SmsMapping.TRANSFER_CATEGORY) {
                    Divider()
                    DetailRow("Moved to my own account?", "Mark as transfer", onClick = { selfTransfer = true }, accent = true)
                }
            }
            if (t.type != com.amitjha.kosh.model.TxnType.TRANSFER) {
                com.amitjha.kosh.ui.household.HouseholdSwitch(com.amitjha.kosh.sync.HouseholdKind.TXN, t.id)
            }
        }
    }

    if (selfTransfer && item != null) {
        SelfTransferSheet(
            currentAccountId = item.txn.accountId,
            isIncome = item.txn.type == com.amitjha.kosh.model.TxnType.INCOME,
            onDismiss = { selfTransfer = false },
            onPick = { other ->
                vm.markSelfTransfer(other)
                selfTransfer = false
            },
        )
    }
    if (recategorise && item != null) {
        RecategoriseSheet(
            categories = state.categories.filter { it.type == item.txn.type },
            currentKey = item.txn.categoryKey,
            merchant = item.txn.merchant,
            onDismiss = { recategorise = false },
            onPick = { key, all ->
                vm.recategorise(key, all)
                recategorise = false
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete transaction?",
            message = "This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete { nav.popBackSafely() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = KoshTheme.colors.divider, modifier = Modifier.padding(vertical = 2.dp))

@Composable
fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null, accent: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = KoshTheme.colors.subtleText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = if (accent) MaterialTheme.colorScheme.primary else valueColor,
            modifier = Modifier.weight(0.6f),
        )
    }
}

