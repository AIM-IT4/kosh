package com.amitjha.kosh.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.CreditCardVisual
import com.amitjha.kosh.designsystem.components.DateHeader
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.GradientCard
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.components.ThinProgressBar
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.repo.AccountWithBalance
import com.amitjha.kosh.repo.BillItem
import com.amitjha.kosh.repo.TransactionItem
import com.amitjha.kosh.repo.TxnFilter
import com.amitjha.kosh.ui.bills.BillRow
import com.amitjha.kosh.ui.bills.PayBillDialog
import com.amitjha.kosh.ui.common.AmountField
import com.amitjha.kosh.ui.common.launchGuarded
import com.amitjha.kosh.ui.common.AmountSheet
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.FormColumn
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.bankEnum
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.home.TxnRow
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.model.TxnType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.amitjha.kosh.ui.nav.popBackSafely

// ---------------------------------------------------------------- list

/** An account the user ignored, with how much of its history totals leave out. */
data class IgnoredAccount(val account: AccountEntity, val transactions: Int, val latest: Long?)

class AccountsViewModel(private val c: AppContainer) : ViewModel() {
    val accounts: StateFlow<List<AccountWithBalance>?> =
        c.accounts.observeAccounts().map<List<AccountWithBalance>, List<AccountWithBalance>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ignored: StateFlow<List<IgnoredAccount>> =
        combine(c.accounts.observeIgnored(), c.db.transactions().observeIgnoredActivity()) { list, activity ->
            val byId = activity.associateBy { it.accountId }
            list.map { IgnoredAccount(it, byId[it.id]?.count ?: 0, byId[it.id]?.latest) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(id: Long) = viewModelScope.launch { c.accounts.restore(id) }
}

@Composable
fun AccountsScreen(nav: NavController) {
    val vm = koshViewModel { AccountsViewModel(it) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val ignored by vm.ignored.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    KoshScreen(
        title = "Accounts",
        onBack = { nav.popBackSafely() },
        actions = { IconButton(onClick = { nav.navigate(Routes.editAccount()) }) { Icon(Icons.Outlined.Add, contentDescription = "Add account") } },
    ) { padding ->
        val list = accounts ?: return@KoshScreen
        if (list.isEmpty() && ignored.isEmpty()) {
            EmptyState(
                "No accounts yet",
                "Add your bank accounts and credit cards to track balances.",
                Modifier.padding(padding).padding(top = 60.dp),
                icon = Icons.Outlined.AccountBalance,
                actionLabel = "Add account",
                onAction = { nav.navigate(Routes.editAccount()) },
            )
            return@KoshScreen
        }
        val pendingCount = list.count { it.isPending }
        val groups = list.filter { it.counted }.groupBy {
            when (it.account.type) {
                AccountType.BANK -> "Bank accounts"
                AccountType.CREDIT_CARD -> "Credit cards"
                AccountType.CASH, AccountType.WALLET -> "Cash & wallets"
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp)) {
            if (pendingCount > 0) {
                item(key = "pending") {
                    PendingAccountsBanner(pendingCount, { nav.navigate(Routes.PENDING_ACCOUNTS) }, Modifier.padding(vertical = 8.dp))
                }
            }
            listOf("Bank accounts", "Credit cards", "Cash & wallets").forEach { title ->
                val items = groups[title].orEmpty()
                if (items.isNotEmpty()) {
                    item(key = title) {
                        val total = if (title == "Credit cards") -items.sumOf { it.outstanding } else items.sumOf { it.balance }
                        DateHeader(title, netPaise = total, hidden = hidden)
                    }
                    items(items, key = { it.id }) { a -> AccountListRow(a, hidden) { nav.navigate(Routes.account(a.id)) } }
                }
            }
            if (ignored.isNotEmpty()) {
                item(key = "ignored") {
                    Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
                        Text("Ignored · not counted", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Kosh still reads their SMS but leaves them out of spending, balances and net worth.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText,
                        )
                    }
                }
                items(ignored, key = { "ignored-${it.account.id}" }) { ig -> IgnoredAccountRow(ig) { vm.restore(ig.account.id) } }
            }
            item(key = "add") {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { nav.navigate(Routes.editAccount()) }, modifier = Modifier.weight(1f)) { Text("Add bank / cash") }
                    OutlinedButton(onClick = { nav.navigate(Routes.editAccount(card = true)) }, modifier = Modifier.weight(1f)) { Text("Add credit card") }
                }
            }
        }
    }
}

@Composable
private fun IgnoredAccountRow(ig: IgnoredAccount, onRestore: () -> Unit) {
    KoshCard(Modifier.padding(vertical = 5.dp), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(ig.account.bankEnum(), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ig.account.label(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(ig.account.type.label())
                        if (ig.transactions > 0) {
                            append(" · ${ig.transactions} transaction${if (ig.transactions == 1) "" else "s"} left out")
                            ig.latest?.let { append(", latest ${Fmt.date(com.amitjha.kosh.util.Time.toDate(it))}") }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        com.amitjha.kosh.designsystem.components.SecondaryButton(
            text = if (ig.account.autoCreated) "Count again and review" else "Count again",
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun AccountListRow(a: AccountWithBalance, hidden: Boolean, onClick: () -> Unit) {
    KoshCard(Modifier.padding(vertical = 5.dp), onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(a.bank, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.account.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(a.account.type.label())
                        a.account.last4?.let { append(" ••$it") }
                        if (a.account.autoCreated) append(" · from SMS")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (a.isCard) {
                    AmountText(a.outstanding, style = AmountStyles.medium, hidden = hidden)
                    Text("outstanding", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
                } else {
                    AmountText(a.balance, style = AmountStyles.medium, hidden = hidden)
                }
            }
        }
        a.utilisation?.let {
            Spacer(Modifier.height(10.dp))
            ThinProgressBar(it.toFloat().coerceIn(0f, 1f), color = if (it > 0.3) KoshTheme.colors.warning else KoshTheme.colors.good)
        }
    }
}

// ---------------------------------------------------------------- detail

data class AccountDetailState(
    val account: AccountWithBalance? = null,
    val transactions: List<TransactionItem> = emptyList(),
    val bills: List<BillItem> = emptyList(),
    val banks: List<AccountEntity> = emptyList(),
    val interest: com.amitjha.kosh.repo.AccountInterest? = null,
    val loaded: Boolean = false,
)

class AccountDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val state: StateFlow<AccountDetailState> = combine(
        c.accounts.observeAccounts(),
        c.transactions.observe(TxnFilter(accountId = id)),
        c.bills.observeForCard(id),
        c.interest.observeAccount(id),
    ) { accounts, txns, bills, interest ->
        AccountDetailState(
            interest = interest,
            account = accounts.firstOrNull { it.id == id },
            transactions = txns.take(100),
            bills = bills,
            banks = accounts.filter { !it.isCard }.map { it.account },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState())

    fun setBalance(amount: Long) = viewModelScope.launch { c.accounts.setCurrentBalance(id, amount) }
    fun setInterestRate(ratePct: Double?, frequency: com.amitjha.kosh.finance.CreditFrequency) =
        viewModelScope.launch { c.interest.setRate(id, ratePct, frequency) }
    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        c.accounts.archive(id)
        onDone()
    }
    private val payGuard = com.amitjha.kosh.ui.common.ActionGuard()
    fun payBill(billId: Long, from: Long?, amount: Long?) = launchGuarded(payGuard) { c.bills.markPaid(billId, from, amount) }
    fun unpay(billId: Long) = viewModelScope.launch { c.bills.markUnpaid(billId) }
}

@Composable
fun AccountDetailScreen(nav: NavController, id: Long) {
    val vm = koshViewModel(key = "acc-$id") { AccountDetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    var reconcile by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<BillItem?>(null) }
    var editingRate by remember { mutableStateOf(false) }
    val a = state.account

    KoshScreen(
        title = a?.account?.name ?: "Account",
        subtitle = a?.account?.type?.label(),
        onBack = { nav.popBackSafely() },
        actions = {
            if (a != null) {
                IconButton(onClick = { nav.navigate(Routes.editAccount(id)) }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                IconButton(onClick = { archive = true }) { Icon(Icons.Outlined.Archive, contentDescription = "Archive") }
            }
        },
    ) { padding ->
        if (a == null) {
            if (state.loaded) EmptyState("Account not found", "It may have been archived.", Modifier.padding(padding).padding(top = 60.dp))
            return@KoshScreen
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp)) {
            if (a.account.autoCreated) {
                item(key = "auto") {
                    KoshCard(Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text("Found in your SMS · not counted yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Kosh spotted ${a.bank.shortName} ••${a.account.last4 ?: "?"} in your messages. Tell Kosh whether it's one of " +
                                "your accounts, a new one (with today's balance), or not yours. Until then it isn't in net worth.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText,
                        )
                        TextButton(onClick = { nav.navigate(Routes.PENDING_ACCOUNTS) }) { Text("Sort it out") }
                    }
                }
            }
            item(key = "hero") {
                if (a.isCard) {
                    CreditCardVisual(
                        bank = a.bank,
                        cardName = a.account.name,
                        last4 = a.account.last4,
                        outstanding = a.outstanding,
                        creditLimit = a.account.creditLimit,
                        hidden = hidden,
                    )
                } else {
                    GradientCard {
                        Text("Current balance", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                        AmountText(a.balance, style = AmountStyles.hero, hidden = hidden, animate = true, color = androidx.compose.ui.graphics.Color.White)
                        a.account.reportedAt?.let {
                            Text("Last confirmed by bank SMS ${Fmt.ago(it)}", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item(key = "actions") {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { reconcile = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Tune, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (a.isCard) "Set outstanding" else "Set balance")
                    }
                    OutlinedButton(onClick = { nav.navigate(Routes.addTxn(TxnType.EXPENSE, id)) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Transaction")
                    }
                }
            }
            state.interest?.let { interest ->
                item(key = "interest") {
                    com.amitjha.kosh.ui.wealth.AccountInterestCard(interest, hidden, onEditRate = { editingRate = true }, Modifier.padding(bottom = 8.dp))
                }
            }
            if (a.isCard) {
                item(key = "card-info") {
                    KoshCard(Modifier.padding(bottom = 8.dp)) {
                        Row {
                            CardStat("Available", a.availableLimit, hidden, Modifier.weight(1f))
                            CardStat("Limit", a.account.creditLimit, hidden, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            buildString {
                                a.account.statementDay?.let { append("Statement on ${ordinal(it)}") }
                                a.account.dueDay?.let { if (isNotEmpty()) append(" · "); append("Due on ${ordinal(it)}") }
                                a.utilisation?.let { if (isNotEmpty()) append(" · "); append("${Fmt.pct(it * 100, 0)} used") }
                            }.ifEmpty { "Add statement and due days in edit" },
                            style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText,
                        )
                    }
                }
                if (state.bills.isNotEmpty()) {
                    item(key = "bills-h") { SectionHeader("Bills") }
                    items(state.bills, key = { "b" + it.bill.id }) { b ->
                        BillRow(b, hidden, onPay = { paying = b }, onUnpay = { vm.unpay(b.bill.id) })
                    }
                }
            }
            item(key = "txn-h") { SectionHeader("Transactions") }
            if (state.transactions.isEmpty()) {
                item(key = "none") {
                    Text("No transactions yet", color = KoshTheme.colors.subtleText, modifier = Modifier.padding(8.dp))
                }
            }
            items(state.transactions, key = { "t" + it.txn.id }) { t ->
                TxnRow(t, hidden) { nav.navigate(Routes.txn(t.txn.id)) }
            }
        }
    }

    val interestNow = state.interest
    if (editingRate && interestNow != null) {
        com.amitjha.kosh.ui.wealth.InterestRateDialog(
            interestNow,
            onDismiss = { editingRate = false },
            onSave = { rate, freq ->
                vm.setInterestRate(rate, freq)
                editingRate = false
            },
        )
    }
    if (reconcile && a != null) {
        AmountSheet(
            title = if (a.isCard) "Amount you owe today" else "Balance in your bank app today",
            initial = if (a.isCard) a.outstanding else a.balance,
            allowZero = true,
            onDismiss = { reconcile = false },
            onConfirm = {
                vm.setBalance(it)
                reconcile = false
            },
        )
    }
    if (archive) {
        ConfirmDialog(
            "Archive account?",
            "It will be hidden and left out of spending, balances and net worth, including its future bank SMS. " +
                "Its transactions stay in your history, and Accounts › Ignored can count it again.",
            "Archive",
            onConfirm = {
                archive = false
                vm.archive { nav.popBackSafely() }
            },
            onDismiss = { archive = false },
        )
    }
    paying?.let { b ->
        PayBillDialog(b, state.banks, onDismiss = { paying = null }) { from, amount ->
            vm.payBill(b.bill.id, from, amount)
            paying = null
        }
    }
}

@Composable
private fun CardStat(label: String, value: Long?, hidden: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
        if (value != null) AmountText(value, style = AmountStyles.medium, hidden = hidden) else Text("—")
    }
}

fun ordinal(day: Int): String = day.toString() + when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

// ---------------------------------------------------------------- edit

class AccountEditViewModel(private val c: AppContainer) : ViewModel() {
    suspend fun load(id: Long): AccountWithBalance? = c.accounts.byId(id)?.let { c.accounts.withBalance(it) }

    private val guard = com.amitjha.kosh.ui.common.ActionGuard()

    fun save(existing: AccountEntity?, draft: AccountEntity, currentAmount: Long, onDone: (Long) -> Unit) = launchGuarded(guard) {
        if (existing == null) {
            // For cards the opening balance is the amount owed; for others, money held.
            val id = c.accounts.add(draft.copy(openingBalance = currentAmount, openingDate = System.currentTimeMillis()))
            onDone(id)
        } else {
            c.accounts.update(
                existing.copy(
                    name = draft.name, type = draft.type, bank = draft.bank, last4 = draft.last4,
                    // Renaming an account found in SMS doesn't confirm it: it stays pending (and out of net worth)
                    // until the user answers the "is this yours?" prompt with a balance.
                    creditLimit = draft.creditLimit, statementDay = draft.statementDay, dueDay = draft.dueDay,
                ),
            )
            onDone(existing.id)
        }
    }
}

private val TYPES = listOf(AccountType.BANK, AccountType.CREDIT_CARD, AccountType.CASH, AccountType.WALLET)

@Composable
fun AccountEditScreen(nav: NavController, editId: Long?, defaultCard: Boolean) {
    val vm = koshViewModel(key = "acc-edit-$editId") { AccountEditViewModel(it) }
    var existing by remember { mutableStateOf<AccountEntity?>(null) }
    var type by remember { mutableStateOf(if (defaultCard) AccountType.CREDIT_CARD else AccountType.BANK) }
    var bank by remember { mutableStateOf(Bank.HDFC) }
    var name by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf<Long?>(null) }
    var limit by remember { mutableStateOf<Long?>(null) }
    var statementDay by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("") }

    LaunchedEffect(editId) {
        if (editId != null) {
            val a = vm.load(editId) ?: return@LaunchedEffect
            existing = a.account
            type = a.account.type
            bank = Bank.fromName(a.account.bank)
            name = a.account.name
            last4 = a.account.last4.orEmpty()
            amount = if (a.isCard) a.outstanding else a.balance
            limit = a.account.creditLimit
            statementDay = a.account.statementDay?.toString().orEmpty()
            dueDay = a.account.dueDay?.toString().orEmpty()
        }
    }

    val isCard = type == AccountType.CREDIT_CARD
    val needsBank = type == AccountType.BANK || isCard
    val valid = name.isNotBlank() && (last4.isEmpty() || last4.length == 4)

    KoshScreen(title = if (existing == null) (if (isCard) "Add credit card" else "Add account") else "Edit account", onBack = { nav.popBackSafely() }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            FormColumn {
                SegmentedTabs(listOf("Bank", "Card", "Cash", "Wallet"), TYPES.indexOf(type), onSelect = { type = TYPES[it] })
                if (needsBank) {
                    Text("Bank", style = MaterialTheme.typography.labelLarge, color = KoshTheme.colors.subtleText)
                    BankPicker(bank) {
                        bank = it
                        if (name.isBlank() || Bank.entries.any { b -> name.startsWith(b.shortName) }) {
                            name = "${it.shortName} ${if (isCard) "Credit Card" else "Savings"}"
                        }
                    }
                }
                KoshTextField(name, { name = it }, "Name", supporting = "e.g. HDFC Savings, ICICI Amazon Pay")
                if (needsBank) {
                    KoshTextField(
                        last4,
                        { v -> last4 = v.filter(Char::isDigit).take(4) },
                        if (isCard) "Last 4 digits of card" else "Last 4 digits of account",
                        keyboardType = KeyboardType.Number,
                        supporting = "Used to match bank SMS to this account",
                    )
                }
                if (existing == null) {
                    AmountField(if (isCard) "Amount you owe now" else "Current balance", amount, { amount = it })
                } else {
                    Text(
                        "To change the balance, use “Set balance” on the account screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
                if (isCard) {
                    AmountField("Credit limit", limit, { limit = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KoshTextField(statementDay, { v -> statementDay = v.filter(Char::isDigit).take(2) }, "Statement day",
                            Modifier.weight(1f), keyboardType = KeyboardType.Number)
                        KoshTextField(dueDay, { v -> dueDay = v.filter(Char::isDigit).take(2) }, "Due day",
                            Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = if (existing == null) "Add" else "Save",
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val draft = AccountEntity(
                            name = name.trim(),
                            type = type,
                            bank = if (needsBank) bank.name else Bank.OTHER.name,
                            last4 = last4.takeIf { it.length == 4 },
                            creditLimit = if (isCard) limit else null,
                            statementDay = if (isCard) statementDay.toIntOrNull()?.coerceIn(1, 31) else null,
                            dueDay = if (isCard) dueDay.toIntOrNull()?.coerceIn(1, 31) else null,
                        )
                        vm.save(existing, draft, amount ?: 0) { nav.popBackSafely() }
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Horizontal bank chooser with brand badges. */
@Composable
fun BankPicker(selected: Bank, onSelect: (Bank) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Bank.entries.toList(), key = { it.name }) { b ->
            val isSel = b == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else KoshTheme.colors.cardSurface,
                border = if (isSel) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, KoshTheme.colors.divider),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(b) },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BankBadge(b, size = 36.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(b.shortName, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

