package com.amitjha.kosh.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.ui.common.LocalContainer

/**
 * Picks the other account of a transfer between the user's own accounts. [onPick] gets null when the user
 * only wants it marked as a transfer without choosing the account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTransferSheet(currentAccountId: Long, isIncome: Boolean, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val container = LocalContainer.current
    val flow = remember { container.db.accounts().observeActive() }
    val accounts by flow.collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Transfer between my accounts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                if (isIncome) "Which of your accounts did this money come from?" else "Which of your accounts did this money go to?",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            accounts.filter { it.id != currentAccountId }.forEach { acc ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(acc.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BankBadge(Bank.fromName(acc.bank), size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(acc.name + (acc.last4?.let { " ••$it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                "Just mark as transfer",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}
