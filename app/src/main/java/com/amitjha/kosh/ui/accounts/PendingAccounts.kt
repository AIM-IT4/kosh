package com.amitjha.kosh.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SecondaryButton
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.domain.BalanceSource
import com.amitjha.kosh.repo.AccountWithBalance
import com.amitjha.kosh.ui.common.AmountSheet
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.launchGuarded
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.amitjha.kosh.ui.nav.popBackSafely

/** Accounts found in SMS/e-mail plus the confirmed accounts they could be merged into. */
data class PendingState(
    val pending: List<AccountWithBalance> = emptyList(),
    val confirmed: List<AccountWithBalance> = emptyList(),
    val loaded: Boolean = false,
)

class PendingAccountsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<PendingState> = c.accounts.observeAccounts()
        .map { all -> PendingState(all.filter { it.isPending }, all.filter { it.counted }, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PendingState())

    private val guard = com.amitjha.kosh.ui.common.ActionGuard()

    fun merge(pendingId: Long, targetId: Long) = launchGuarded(guard) { c.accounts.mergePending(pendingId, targetId) }

    fun addAsNew(id: Long, name: String, balance: Long) = launchGuarded(guard) { c.accounts.confirmPendingAsNew(id, name, balance) }

    fun ignore(id: Long) = viewModelScope.launch { c.accounts.ignorePending(id) }
}

/** "Kosh found N accounts in your SMS": shown on Home and Accounts while any are pending. */
@Composable
fun PendingAccountsBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = KoshTheme.colors.warning.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TravelExplore, contentDescription = null, tint = KoshTheme.colors.warning)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "Kosh found an account in your SMS" else "Kosh found $count accounts in your SMS",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Not counted in net worth until you confirm",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Review accounts", tint = KoshTheme.colors.subtleText)
        }
    }
}

/**
 * Bank SMS from the last week went to accounts the user ignored, so no total includes them. Easy to miss otherwise:
 * the spends simply don't appear anywhere.
 */
@Composable
fun IgnoredSmsBanner(count: Int, onClick: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = KoshTheme.colors.alert.copy(alpha = 0.08f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = KoshTheme.colors.alert)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 bank SMS this week isn't counted" else "$count bank SMS this week aren't counted",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "They went to accounts you ignored. Tap to count them",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = KoshTheme.colors.subtleText)
            }
        }
    }
}

/** One line describing where a balance comes from, used here and in the net worth breakdown. */
fun AccountWithBalance.sourceLine(): String = when (val s = source) {
    is BalanceSource.Entered ->
        if (account.openingDate <= 0L) "Balance not set"
        else {
            val what = if (isCard) "owed" else ""
            "You entered ${com.amitjha.kosh.model.Money.format(s.amount, showPaise = false)}${if (what.isNotEmpty()) " $what" else ""} on " +
                "${Fmt.date(Time.toDate(s.at))} + ${transactionsSinceAnchor} transaction${if (transactionsSinceAnchor == 1) "" else "s"} since"
        }
    is BalanceSource.Reported ->
        "Reported by ${bank.shortName} SMS on ${Fmt.date(Time.toDate(s.at))}" +
            if (transactionsSinceAnchor > 0) " + $transactionsSinceAnchor transaction${if (transactionsSinceAnchor == 1) "" else "s"} since" else ""
    BalanceSource.Unknown -> "Balance unknown"
}

@Composable
fun PendingAccountsScreen(nav: NavController) {
    val vm = koshViewModel { PendingAccountsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    var merging by remember { mutableStateOf<AccountWithBalance?>(null) }
    var adding by remember { mutableStateOf<AccountWithBalance?>(null) }
    var ignoring by remember { mutableStateOf<AccountWithBalance?>(null) }

    KoshScreen(title = "Accounts found in SMS", onBack = { nav.popBackSafely() }) { padding ->
        if (state.loaded && state.pending.isEmpty()) {
            EmptyState(
                "All sorted",
                "Every account Kosh found in your messages is confirmed, merged or ignored.",
                Modifier.padding(padding).padding(top = 60.dp),
            )
            return@KoshScreen
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    "These accounts appear in your bank messages but aren't set up in Kosh. Their imported history has no " +
                        "known starting balance, so they don't count towards net worth until you tell Kosh what they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KoshTheme.colors.subtleText,
                )
            }
            items(state.pending, key = { it.id }) { a ->
                KoshCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BankBadge(a.bank, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.account.label(), fontWeight = FontWeight.SemiBold)
                            Text(a.sourceLine(), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                        }
                        if (a.balanceKnown) {
                            AmountText(if (a.isCard) a.outstanding else a.balance, style = AmountStyles.medium, hidden = hidden)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val candidates = state.confirmed.filter { it.isCard == a.isCard }
                    if (candidates.isNotEmpty()) {
                        PrimaryButton(
                            text = "This is one of my accounts",
                            onClick = { merging = a },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(text = "Add as new", onClick = { adding = a }, modifier = Modifier.weight(1f))
                        SecondaryButton(text = "Ignore", onClick = { ignoring = a }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    ignoring?.let { a ->
        com.amitjha.kosh.ui.common.ConfirmDialog(
            "Ignore ${a.account.label()}?",
            "Use this for an account that isn't yours. Its bank SMS will keep arriving but never count in your spending, " +
                "balances or net worth. If it is yours, choose \"This is one of my accounts\" or \"Add as new\" instead. " +
                "You can undo this in Accounts › Ignored.",
            "Ignore",
            onConfirm = {
                vm.ignore(a.id)
                ignoring = null
            },
            onDismiss = { ignoring = null },
        )
    }

    merging?.let { pending ->
        // Same bank first: an HDFC message most likely belongs to the HDFC account.
        val options = state.confirmed.filter { it.isCard == pending.isCard }
            .sortedBy { if (it.bank == pending.bank) 0 else 1 }
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Which account is ${pending.account.label()}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Its transactions move into the account you pick, and future messages go there too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                    options.forEach { target ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    vm.merge(pending.id, target.id)
                                    merging = null
                                },
                            color = KoshTheme.colors.cardSurfaceHigh,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                BankBadge(target.bank, size = 32.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(target.account.label(), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }

    adding?.let { pending ->
        var name by remember(pending.id) { mutableStateOf(pending.account.name) }
        var askBalance by remember(pending.id) { mutableStateOf(false) }
        if (!askBalance) {
            AlertDialog(
                onDismissRequest = { adding = null },
                title = { Text("Add as a new account") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KoshTextField(value = name, onValue = { name = it }, label = "Name")
                        Text(
                            if (pending.isCard) "Next, enter the amount you owe on this card today."
                            else "Next, enter the balance your bank app shows today. Only transactions after today change it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoshTheme.colors.subtleText,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { askBalance = true }, enabled = name.isNotBlank()) { Text("Next") } },
                dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
            )
        } else {
            AmountSheet(
                title = if (pending.isCard) "Amount owed today" else "Balance today",
                initial = null,
                allowZero = true,
                onDismiss = { adding = null },
                onConfirm = {
                    vm.addAsNew(pending.id, name.trim(), it)
                    adding = null
                },
            )
        }
    }
}
