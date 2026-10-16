package com.amitjha.kosh.ui.features

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.amitjha.kosh.data.DebtEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.components.ThinProgressBar
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.repo.DebtRepository
import com.amitjha.kosh.ui.common.AmountField
import com.amitjha.kosh.ui.common.DateField
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.FormColumn
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.Pill
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Money lent to or borrowed from people, with partial repayments. */
@Composable
fun DebtsScreen(nav: NavController) {
    val c = LocalContainer.current
    val hidden = LocalHideAmounts.current
    val scope = rememberCoroutineScope()
    val debts by c.debts.observe().collectAsState(initial = null)
    var editing by remember { mutableStateOf<DebtEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var repaying by remember { mutableStateOf<DebtEntity?>(null) }
    val today = Time.today()

    KoshScreen(
        title = "Lent & borrowed",
        onBack = { nav.popBackSafely() },
        floating = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add") }) },
    ) { pad ->
        val list = debts ?: return@KoshScreen
        val open = list.filter { !it.closed }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                KoshCard {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Others owe you", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                            AmountText(open.filter { it.direction == DebtRepository.LENT }.sumOf { it.amount - it.repaid }, style = AmountStyles.large,
                                color = KoshTheme.colors.good, hidden = hidden)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("You owe", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                            AmountText(open.filter { it.direction == DebtRepository.BORROWED }.sumOf { it.amount - it.repaid }, style = AmountStyles.large,
                                color = KoshTheme.colors.alert, hidden = hidden)
                        }
                    }
                }
            }
            if (list.isEmpty()) {
                item { EmptyState("Nothing owed", "Keep track of money you lend to friends or borrow from family, and record repayments as they come.") }
            }
            if (open.isNotEmpty()) item { SectionHeader("Open") }
            items(open, key = { it.id }) { d -> DebtCard(d, hidden, today, onClick = { editing = d }, onRepay = { repaying = d }) }
            val closed = list.filter { it.closed }
            if (closed.isNotEmpty()) item { SectionHeader("Settled") }
            items(closed, key = { it.id }) { d -> DebtCard(d, hidden, today, onClick = { editing = d }, onRepay = null) }
        }
    }

    if (adding || editing != null) {
        DebtDialog(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { d -> scope.launch { c.debts.save(d) }; adding = false; editing = null },
            onDelete = { d -> scope.launch { c.debts.delete(d) }; editing = null },
        )
    }
    repaying?.let { d ->
        var amount by remember(d.id) { mutableStateOf<Long?>(d.amount - d.repaid) }
        AlertDialog(
            onDismissRequest = { repaying = null },
            title = { Text(if (d.direction == DebtRepository.LENT) "${d.person} paid back" else "You paid ${d.person} back") },
            text = { AmountField("Amount", amount, { amount = it }, allowZero = false) },
            confirmButton = {
                TextButton(enabled = (amount ?: 0) > 0, onClick = {
                    scope.launch { c.debts.repay(d.id, amount ?: 0) }
                    repaying = null
                }) { Text("Record") }
            },
            dismissButton = { TextButton(onClick = { repaying = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DebtCard(d: DebtEntity, hidden: Boolean, today: LocalDate, onClick: () -> Unit, onRepay: (() -> Unit)?) {
    val lent = d.direction == DebtRepository.LENT
    KoshCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.person, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    (if (lent) "You lent" else "You borrowed") + " · " + Fmt.date(LocalDate.ofEpochDay(d.date)) + (d.note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                    maxLines = 1,
                )
            }
            AmountText(d.amount - d.repaid, style = AmountStyles.medium, color = if (lent) KoshTheme.colors.good else KoshTheme.colors.alert, hidden = hidden)
        }
        if (d.repaid > 0 && !d.closed) {
            Spacer(Modifier.height(8.dp))
            ThinProgressBar(d.repaid.toFloat() / d.amount, color = KoshTheme.colors.accent)
        }
        val due = d.dueDate?.let { LocalDate.ofEpochDay(it) }
        if (!d.closed && (due != null || onRepay != null)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (due != null) {
                    val overdue = due.isBefore(today)
                    Pill(if (overdue) "Was due ${Fmt.date(due)}" else "Due ${Fmt.date(due)}", if (overdue) KoshTheme.colors.alert else KoshTheme.colors.tip)
                }
                Spacer(Modifier.weight(1f))
                if (onRepay != null) TextButton(onClick = onRepay) { Text("Record repayment") }
            }
        }
    }
}

@Composable
private fun DebtDialog(existing: DebtEntity?, onDismiss: () -> Unit, onSave: (DebtEntity) -> Unit, onDelete: (DebtEntity) -> Unit) {
    var lent by remember { mutableStateOf(existing?.direction != DebtRepository.BORROWED) }
    var person by remember { mutableStateOf(existing?.person.orEmpty()) }
    var amount by remember { mutableStateOf(existing?.amount) }
    var date by remember { mutableStateOf(existing?.date?.let { LocalDate.ofEpochDay(it) } ?: Time.today()) }
    var due by remember { mutableStateOf(existing?.dueDate?.let { LocalDate.ofEpochDay(it) }) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add" else "Edit") },
        text = {
            FormColumn {
                SegmentedTabs(listOf("I lent", "I borrowed"), if (lent) 0 else 1, onSelect = { lent = it == 0 })
                KoshTextField(person, { person = it.take(40) }, "Person")
                AmountField("Amount", amount, { amount = it }, allowZero = false)
                DateField("On", date, { date = it })
                if (due != null) {
                    DateField("Due by", due!!, { due = it })
                    TextButton(onClick = { due = null }) { Text("No due date") }
                } else {
                    TextButton(onClick = { due = date.plusMonths(1) }) { Text("Add a due date") }
                }
                KoshTextField(note, { note = it.take(100) }, "Note (optional)")
            }
        },
        confirmButton = {
            TextButton(enabled = person.isNotBlank() && (amount ?: 0) > 0, onClick = {
                val a = amount ?: 0
                val repaid = (existing?.repaid ?: 0).coerceAtMost(a)
                onSave(
                    DebtEntity(
                        id = existing?.id ?: 0,
                        person = person.trim(),
                        direction = if (lent) DebtRepository.LENT else DebtRepository.BORROWED,
                        amount = a,
                        repaid = repaid,
                        date = date.toEpochDay(),
                        dueDate = due?.toEpochDay(),
                        note = note.trim().ifBlank { null },
                        closed = repaid >= a,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { onDelete(existing) }) { Text("Delete", color = KoshTheme.colors.alert) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
