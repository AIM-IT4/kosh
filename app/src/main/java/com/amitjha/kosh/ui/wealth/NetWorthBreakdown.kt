package com.amitjha.kosh.ui.wealth

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.InvestmentTypeBadge
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.repo.AccountWithBalance
import com.amitjha.kosh.repo.NetWorth
import com.amitjha.kosh.ui.accounts.PendingAccountsBanner
import com.amitjha.kosh.ui.accounts.sourceLine
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.nav.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.amitjha.kosh.ui.nav.popBackSafely

class NetWorthBreakdownViewModel(c: AppContainer) : ViewModel() {
    val netWorth: StateFlow<NetWorth?> = c.netWorth.observeNetWorth()
        .map<NetWorth, NetWorth?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** "How net worth is calculated": every account, card and investment behind the total, with its source. */
@Composable
fun NetWorthBreakdownScreen(nav: NavController) {
    val vm = koshViewModel { NetWorthBreakdownViewModel(it) }
    val nw by vm.netWorth.collectAsStateWithLifecycle()
    KoshScreen(title = "Net worth", subtitle = "How it is calculated", onBack = { nav.popBackSafely() }) { padding ->
        val n = nw ?: return@KoshScreen
        NetWorthBreakdownContent(
            n,
            onPending = { nav.navigate(Routes.PENDING_ACCOUNTS) },
            onAccount = { nav.navigate(Routes.account(it)) },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
fun NetWorthBreakdownContent(
    n: NetWorth,
    onPending: () -> Unit,
    onAccount: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hidden = LocalHideAmounts.current
    val banks = n.counted.filter { it.account.type != AccountType.CREDIT_CARD }
    val cards = n.counted.filter { it.isCard }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "total") {
            KoshCard {
                Text("Net worth", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                AmountText(n.total, style = AmountStyles.hero, hidden = hidden)
                Spacer(Modifier.height(12.dp))
                Formula("Cash & bank", n.liquid, hidden, sign = "")
                Formula("Card dues", n.cardDebt, hidden, sign = "−")
                Formula("Investments", n.investments, hidden, sign = "+")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Each balance starts from the amount you entered (or the latest balance a bank SMS reported, if newer) " +
                        "and moves only with transactions after that. Older imported history is shown in Activity but never " +
                        "changes a balance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
        }
        if (n.pending.isNotEmpty()) {
            item(key = "pending") { PendingAccountsBanner(n.pending.size, onPending) }
        }
        if (banks.isNotEmpty()) {
            item(key = "banks") {
                Group("Cash & bank", n.liquid, hidden) {
                    banks.forEach { AccountLine(it, hidden, amount = it.balance) { onAccount(it.id) } }
                }
            }
        }
        if (cards.isNotEmpty()) {
            item(key = "cards") {
                Group("Card dues", -n.cardDebt, hidden) {
                    cards.forEach { AccountLine(it, hidden, amount = -it.outstanding) { onAccount(it.id) } }
                }
            }
        }
        if (n.investmentItems.isNotEmpty()) {
            item(key = "investments") {
                Group("Investments", n.investments, hidden) {
                    n.investmentItems.forEach { (name, type, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InvestmentTypeBadge(type)
                            Spacer(Modifier.width(10.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            AmountText(value, style = AmountStyles.small, hidden = hidden)
                        }
                    }
                    Text(
                        "Current values, updated daily (FD interest, EPF contributions, NPS NAVs).",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
        }
        if (n.pending.isNotEmpty()) {
            item(key = "not-counted") {
                Group("Not counted", null, hidden) {
                    n.pending.forEach { AccountLine(it, hidden, amount = if (it.balanceKnown) it.balance else null, onClick = onPending) }
                    Text(
                        "Found in SMS but not confirmed. Confirm, merge or ignore them to tidy this up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
        }
    }
}

@Composable
private fun Formula(label: String, value: Paise, hidden: Boolean, sign: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (sign.isEmpty()) label else "$sign $label", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AmountText(value, style = AmountStyles.small, hidden = hidden)
    }
}

@Composable
private fun Group(title: String, total: Paise?, hidden: Boolean, content: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title, Modifier.weight(1f))
            total?.let { AmountText(it, style = AmountStyles.small, hidden = hidden) }
        }
        KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
private fun AccountLine(a: AccountWithBalance, hidden: Boolean, amount: Paise?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(a.bank, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.account.label(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(a.sourceLine(), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                if (a.creditBalance > 0) {
                    Text(
                        "Credit balance ${com.amitjha.kosh.model.Money.format(a.creditBalance, showPaise = false)} (not counted)",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
            if (amount != null) AmountText(amount, style = AmountStyles.small, hidden = hidden) else Text("—", color = KoshTheme.colors.subtleText)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = KoshTheme.colors.divider)
    }
}
