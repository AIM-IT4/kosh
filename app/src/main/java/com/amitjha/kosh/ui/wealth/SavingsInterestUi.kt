package com.amitjha.kosh.ui.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.finance.CreditFrequency
import com.amitjha.kosh.repo.AccountInterest
import com.amitjha.kosh.repo.InterestSummary
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshTextField

private fun rate(pct: Double) = Fmt.pct(pct, 2)

private fun periodName(i: AccountInterest) = if (i.frequency == CreditFrequency.MONTHLY) "this month" else "this quarter"

/** "2.50% · as of 24 Jun 2025 · official" or "2.75% · your rate". */
fun rateLine(i: AccountInterest): String = if (i.userRate) {
    "${rate(i.estimate.ratePct)} a year · your rate"
} else {
    "${rate(i.estimate.ratePct)} a year · as of ${Fmt.date(i.rateCard.asOf)} · ${i.rateCard.confidence.label}"
}

/** Wealth: interest earned on savings balances this quarter, across bank accounts. */
@Composable
fun SavingsInterestCard(summary: InterestSummary, hidden: Boolean, onOpenAccount: (Long) -> Unit, modifier: Modifier = Modifier) {
    KoshCard(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Earned so far", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                AmountText(summary.accrued, style = AmountStyles.large, hidden = hidden, color = KoshTheme.colors.income)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Projected", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                AmountText(summary.projected, style = AmountStyles.medium, hidden = hidden)
            }
        }
        summary.nextCreditDate?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Estimated on daily closing balances · next credit around ${Fmt.date(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
            )
        }
        Spacer(Modifier.height(12.dp))
        summary.items.forEachIndexed { index, i ->
            if (index > 0) HorizontalDivider(color = KoshTheme.colors.divider)
            Row(
                Modifier.fillMaxWidth().clickable { onOpenAccount(i.account.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BankBadge(i.account.bank, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(i.account.account.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        if (i.needsConfirmation) "${rate(i.estimate.ratePct)} · confirm your rate" else rate(i.estimate.ratePct) + " a year",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (i.needsConfirmation) KoshTheme.colors.warning else KoshTheme.colors.subtleText,
                    )
                }
                AmountText(i.estimate.accrued, style = AmountStyles.small, hidden = hidden)
            }
        }
    }
}

/** Account detail: this account's savings interest, with the rate in use and an edit action. */
@Composable
fun AccountInterestCard(i: AccountInterest, hidden: Boolean, onEditRate: () -> Unit, modifier: Modifier = Modifier) {
    val e = i.estimate
    KoshCard(modifier) {
        Text("Savings interest", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Earned ${periodName(i)}", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                AmountText(e.accrued, style = AmountStyles.medium, hidden = hidden, color = KoshTheme.colors.income)
            }
            Column(Modifier.weight(1f)) {
                Text("Projected", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                AmountText(e.projected, style = AmountStyles.medium, hidden = hidden)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(rateLine(i), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        Text(
            "On ${e.daysCounted} day${if (e.daysCounted == 1) "" else "s"} of closing balances · credited around ${Fmt.date(e.nextCreditDate)}",
            style = MaterialTheme.typography.bodySmall,
            color = KoshTheme.colors.subtleText,
        )
        e.lastCredit?.let { c ->
            Text(
                "Last credit: ${Fmt.date(c.creditedOn)} · credited ${com.amitjha.kosh.model.Money.format(c.credited)} " +
                    "(estimated ${com.amitjha.kosh.model.Money.format(c.estimated)})",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
            )
        }
        if (i.needsConfirmation) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Kosh couldn't verify ${i.account.bank.shortName}'s current rate. Check your bank app and confirm it.",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.warning,
            )
        }
        Text(
            "An estimate only; your balance changes when the bank actually credits interest.",
            style = MaterialTheme.typography.labelSmall,
            color = KoshTheme.colors.subtleText,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onEditRate, modifier = Modifier.padding(top = 2.dp)) {
            Text(if (i.needsConfirmation) "Confirm rate" else "Change rate")
        }
    }
}

/** Lets the user set the account's savings rate (blank = Kosh's built-in rate) and credit frequency. */
@Composable
fun InterestRateDialog(
    i: AccountInterest,
    onDismiss: () -> Unit,
    onSave: (ratePct: Double?, frequency: CreditFrequency) -> Unit,
) {
    var text by remember { mutableStateOf(i.account.account.interestRatePct?.toString() ?: "") }
    var frequency by remember { mutableStateOf(i.frequency) }
    val builtIn = com.amitjha.kosh.finance.SavingsRates.forBank(i.account.bank)
    val parsed = text.trim().replace(",", ".").toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed in 0.0..15.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Savings interest rate") },
        text = {
            Column {
                Text(
                    "Built-in rate for ${i.account.bank.shortName}: ${rate(builtIn.headlineRatePct())} " +
                        "(as of ${Fmt.date(builtIn.asOf)}, ${builtIn.confidence.label}). Leave blank to use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                Spacer(Modifier.height(10.dp))
                KoshTextField(text, { text = it }, "Your rate", keyboardType = KeyboardType.Decimal, suffix = "% a year",
                    supporting = if (valid) "From your bank app or website" else "Enter a rate between 0 and 15")
                Spacer(Modifier.height(10.dp))
                Text("Bank credits interest", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CreditFrequency.entries.forEach { f ->
                        FilterChip(
                            selected = frequency == f,
                            onClick = { frequency = f },
                            label = { Text(if (f == CreditFrequency.QUARTERLY) "Quarterly" else "Monthly") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(if (text.isBlank()) null else parsed, frequency) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
