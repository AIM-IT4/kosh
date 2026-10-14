package com.amitjha.kosh.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.designsystem.components.AmountInputDisplay
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.CategoryIcon
import com.amitjha.kosh.designsystem.components.KeypadExpression
import com.amitjha.kosh.designsystem.components.NumberKeypad
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.DateField
import com.amitjha.kosh.ui.common.FieldBox
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.bankEnum
import com.amitjha.kosh.ui.common.color
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.ui.common.toRupeeInput
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class EditorRefs(val accounts: List<AccountEntity> = emptyList(), val categories: List<CategoryEntity> = emptyList())

class AddTransactionViewModel(private val c: AppContainer) : ViewModel() {
    val refs: StateFlow<EditorRefs> = combine(c.accounts.observeAccounts(), c.categories.observeAll()) { a, cats ->
        EditorRefs(a.map { it.account }, cats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorRefs())

    suspend fun load(id: Long): TransactionEntity? = c.transactions.byId(id)

    fun save(existing: TransactionEntity?, draft: TransactionEntity, onDone: () -> Unit) = viewModelScope.launch {
        if (draft.type == TxnType.TRANSFER) {
            val to = draft.toAccountId ?: return@launch
            if (existing == null) {
                c.transactions.addTransfer(draft.accountId, to, draft.amount, draft.timestamp, draft.note)
            } else {
                c.transactions.update(draft.copy(id = existing.id, categoryKey = SmsMapping.TRANSFER_CATEGORY, excludeFromStats = true, reviewed = true))
            }
        } else if (existing == null) {
            c.transactions.add(draft)
        } else {
            c.transactions.update(
                existing.copy(
                    accountId = draft.accountId, toAccountId = null, type = draft.type, amount = draft.amount,
                    categoryKey = draft.categoryKey, merchant = draft.merchant, note = draft.note,
                    timestamp = draft.timestamp, reviewed = true,
                    excludeFromStats = draft.categoryKey == SmsMapping.TRANSFER_CATEGORY,
                ),
            )
        }
        onDone()
    }

    fun delete(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        c.transactions.delete(id)
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(nav: NavController, editId: Long?, initialType: TxnType, initialAccountId: Long?) {
    val vm = koshViewModel(key = "add-$editId") { AddTransactionViewModel(it) }
    val refs by vm.refs.collectAsStateWithLifecycle()
    val haptic = rememberHaptic()

    var existing by remember { mutableStateOf<TransactionEntity?>(null) }
    var type by remember { mutableStateOf(initialType) }
    val expr = remember { KeypadExpression() }
    var exprText by remember { mutableStateOf("") }
    var categoryKey by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf(initialAccountId) }
    var toAccountId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(Time.today()) }
    var timeOfDay by remember { mutableStateOf(java.time.LocalTime.now()) }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pickCategory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(editId) {
        if (editId != null) {
            val t = vm.load(editId) ?: return@LaunchedEffect
            existing = t
            type = t.type
            exprText = expr.apply { press("C"); t.amount.toRupeeInput().forEach { ch -> press(ch.toString()) } }.text
            categoryKey = t.categoryKey
            accountId = t.accountId
            toAccountId = t.toAccountId
            val dt = Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault())
            date = dt.toLocalDate()
            timeOfDay = dt.toLocalTime()
            merchant = t.merchant.orEmpty()
            note = t.note.orEmpty()
        }
    }
    // Default account: the first bank account.
    LaunchedEffect(refs.accounts) {
        if (accountId == null) accountId = refs.accounts.firstOrNull { it.type != com.amitjha.kosh.model.AccountType.CREDIT_CARD }?.id
            ?: refs.accounts.firstOrNull()?.id
    }

    val catsForType = refs.categories.filter { it.type == (if (type == TxnType.INCOME) TxnType.INCOME else TxnType.EXPENSE) }
    val category = refs.categories.firstOrNull { it.key == categoryKey }
    val account = refs.accounts.firstOrNull { it.id == accountId }
    val toAccount = refs.accounts.firstOrNull { it.id == toAccountId }
    val value = remember(exprText) { expr.valuePaise() }
    val canSave = value != null && value > 0 && account != null &&
        (if (type == TxnType.TRANSFER) toAccount != null && toAccount.id != account.id else category != null)

    KoshScreen(
        title = if (existing == null) "New transaction" else "Edit transaction",
        onBack = { nav.popBackStack() },
        actions = {
            if (existing != null) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SegmentedTabs(
                    options = listOf("Expense", "Income", "Transfer"),
                    selectedIndex = type.ordinal,
                    onSelect = {
                        val newType = TxnType.entries[it]
                        if (newType != type) {
                            type = newType
                            if (category?.type != newType) categoryKey = null
                        }
                    },
                )
                AmountInputDisplay(expression = exprText, valuePaise = value, type = type, showResult = expr.hasOperator)
                if (type == TxnType.TRANSFER) {
                    AccountChoice("From", refs.accounts, account) { accountId = it.id }
                    AccountChoice("To", refs.accounts.filter { it.id != accountId }, toAccount) { toAccountId = it.id }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FieldBox(label = "Category", modifier = Modifier.weight(1f), onClick = { pickCategory = true }) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                if (category != null) {
                                    CategoryIcon(category.icon, category.color(), size = 24.dp)
                                    Spacer(Modifier.padding(start = 6.dp))
                                }
                                Text(category?.name ?: "Choose", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                        AccountChoice("Account", refs.accounts, account, Modifier.weight(1f)) { accountId = it.id }
                    }
                    KoshTextField(merchant, { merchant = it }, if (type == TxnType.INCOME) "From (payer)" else "Paid to (merchant)")
                }
                DateField("Date", date, { date = it })
                KoshTextField(note, { note = it }, "Note (optional)")
                if (existing != null) {
                    Text(
                        existing!!.source.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            NumberKeypad(onKey = { exprText = expr.press(it) }, modifier = Modifier.padding(horizontal = 12.dp))
            PrimaryButton(
                text = if (existing == null) "Save" else "Update",
                icon = Icons.Filled.Check,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                onClick = {
                    haptic()
                    val ts = date.atTime(timeOfDay).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val draft = TransactionEntity(
                        accountId = account!!.id,
                        toAccountId = if (type == TxnType.TRANSFER) toAccountId else null,
                        type = type,
                        amount = value!!,
                        categoryKey = if (type == TxnType.TRANSFER) SmsMapping.TRANSFER_CATEGORY else category!!.key,
                        merchant = merchant.trim().ifEmpty { null },
                        note = note.trim().ifEmpty { null },
                        timestamp = ts,
                        source = existing?.source ?: TxnSource.MANUAL,
                    )
                    vm.save(existing, draft) { nav.popBackStack() }
                },
            )
        }
    }

    if (pickCategory) {
        ModalBottomSheet(onDismissRequest = { pickCategory = false }) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
                Text("Category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                CategoryGrid(catsForType, categoryKey, onSelect = {
                    categoryKey = it.key
                    pickCategory = false
                })
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Delete transaction?",
            message = "This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete(existing!!.id) {
                    nav.popBackStack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun AccountChoice(
    label: String,
    accounts: List<AccountEntity>,
    selected: AccountEntity?,
    modifier: Modifier = Modifier,
    onSelect: (AccountEntity) -> Unit,
) {
    ChoiceField(
        label = label,
        options = accounts,
        selected = selected,
        optionLabel = { it.label() },
        onSelect = onSelect,
        modifier = modifier,
        placeholder = if (accounts.isEmpty()) "Add an account first" else "Choose",
        leading = { BankBadge(it.bankEnum(), size = 28.dp) },
    )
}

