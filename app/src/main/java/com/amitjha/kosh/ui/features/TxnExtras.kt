package com.amitjha.kosh.ui.features

import com.amitjha.kosh.ui.common.NO_FILE_PICKER
import com.amitjha.kosh.ui.common.launchOrFalse
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.data.AttachmentEntity
import com.amitjha.kosh.data.AttachmentStore
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.SplitEntity
import com.amitjha.kosh.data.Tags
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.domain.Splits
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.ui.common.AmountField
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.DateField
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Tags, split and receipts of one transaction, shown on its detail screen. */
@Composable
fun TxnExtras(t: TransactionEntity, categories: List<CategoryEntity>) {
    TagsCard(t)
    if (t.type != TxnType.TRANSFER && !t.excludeFromStats) SplitCard(t, categories)
    ReceiptsCard(t)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsCard(t: TransactionEntity) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tags = Tags.decode(t.tags)
    var adding by remember { mutableStateOf(false) }
    KoshCard {
        SectionHeader("Tags")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { scope.launch { c.transactions.setTags(t.id, tags - tag) } },
                    label = { Text("#$tag") },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) },
                )
            }
            AssistChip(onClick = { adding = true }, label = { Text("Add tag") })
        }
        if (tags.isEmpty()) {
            Text("Group spending across categories, like a trip or work expenses to claim. Search #tag in Activity.",
                style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        var known by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) { known = runCatching { c.transactions.allTags() }.getOrDefault(emptyList()) }
        val suggestions = known.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            .filter { s -> tags.none { it.equals(s, true) } && (text.isBlank() || s.contains(text.trim(), true)) }.take(8)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add tag") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KoshTextField(text, { text = it.take(Tags.MAX_LENGTH) }, "Tag", supporting = "e.g. Goa trip, Reimbursable")
                    if (suggestions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { s ->
                                AssistChip(onClick = {
                                    scope.launch { c.transactions.setTags(t.id, tags + s) }
                                    adding = false
                                }, label = { Text("#$s") })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = Tags.clean(text).isNotEmpty(), onClick = {
                    scope.launch { c.transactions.setTags(t.id, tags + Tags.clean(text)) }
                    adding = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SplitCard(t: TransactionEntity, categories: List<CategoryEntity>) {
    val c = LocalContainer.current
    val hidden = LocalHideAmounts.current
    val splits by remember(t.id) { c.transactions.observeSplitsFor(t.id) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf(false) }
    val byKey = categories.associateBy { it.key }
    KoshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Split", Modifier.weight(1f))
            TextButton(onClick = { editing = true }) { Text(if (splits.isEmpty()) "Split" else "Edit") }
        }
        if (splits.isEmpty()) {
            Text("Split one payment across categories, e.g. a supermarket bill that was part groceries, part household.",
                style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        } else {
            Splits.parts(t, splits).forEach { part ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(byKey[part.categoryKey]?.name ?: part.categoryKey, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    AmountText(part.amount, style = AmountStyles.small, hidden = hidden)
                }
            }
        }
    }
    if (editing) {
        SplitDialog(t, splits, categories.filter { it.type == t.type }, onDismiss = { editing = false })
    }
}

@Composable
private fun SplitDialog(t: TransactionEntity, current: List<SplitEntity>, categories: List<CategoryEntity>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<Pair<String?, Long?>>().apply { current.forEach { add(it.categoryKey to it.amount) }; if (isEmpty()) add(null to null) } }
    val total = rows.sumOf { it.second ?: 0 }
    val over = total > t.amount
    val left = Splits.remainder(t.amount, rows.mapNotNull { it.second })
    val ownName = categories.firstOrNull { it.key == t.categoryKey }?.name ?: t.categoryKey
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split ${Money.format(t.amount, showPaise = false)}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEachIndexed { i, (key, amount) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceField("Category", categories, categories.firstOrNull { it.key == key }, { it.name }, { rows[i] = it.key to amount })
                            AmountField("Amount", amount, { rows[i] = key to it }, allowZero = false)
                        }
                        IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Outlined.Delete, "Remove") }
                    }
                }
                TextButton(onClick = { rows.add(null to null) }) { Text("Add part") }
                Text(
                    if (over) "The parts add up to more than the payment." else "${Money.format(left, showPaise = false)} stays in $ownName.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (over) KoshTheme.colors.alert else KoshTheme.colors.subtleText,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !over && rows.all { (it.first == null) == (it.second == null) }, onClick = {
                val parts = rows.filter { it.first != null && (it.second ?: 0) > 0 }
                    .map { SplitEntity(transactionId = t.id, categoryKey = it.first!!, amount = it.second!!) }
                scope.launch { c.transactions.setSplits(t.id, parts) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(onClick = { scope.launch { c.transactions.setSplits(t.id, emptyList()) }; onDismiss() }) { Text("Remove split") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ReceiptsCard(t: TransactionEntity) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by remember(t.id) { c.attachments.observeFor(t.id) }.collectAsState(initial = emptyList())
    var picked by remember { mutableStateOf<Uri?>(null) }
    var viewing by remember { mutableStateOf<AttachmentEntity?>(null) }
    var saving by remember { mutableStateOf<AttachmentEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picked = uri }
    val onSaved: (Uri?) -> Unit = { uri ->
        val a = saving
        saving = null
        if (uri != null && a != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(c.attachments.bytes(a)) } }
                }.onFailure { error = "Couldn't save it: ${it.message}" }
            }
        }
    }
    val savePhoto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AttachmentStore.JPEG), onSaved)
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AttachmentStore.PDF), onSaved)

    KoshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Receipts & warranty", Modifier.weight(1f))
            TextButton(onClick = { if (!pick.launchOrFalse(arrayOf("image/*", "application/pdf"))) error = NO_FILE_PICKER }) { Text("Add") }
        }
        if (files.isEmpty()) {
            Text("Keep the bill or warranty card with the payment. Files are encrypted and stay on this phone.",
                style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        }
        files.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (a.mimeType == AttachmentStore.PDF) Icons.Outlined.Description else Icons.Outlined.Image, null, tint = KoshTheme.colors.subtleText)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(a.label ?: if (a.mimeType == AttachmentStore.PDF) "PDF" else "Photo", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    val w = a.warrantyUntil?.let { LocalDate.ofEpochDay(it) }
                    Text(
                        listOfNotNull("${a.sizeBytes / 1024} KB", w?.let { if (it.isBefore(Time.today())) "warranty ended ${Fmt.date(it)}" else "warranty till ${Fmt.date(it)}" })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
                if (a.mimeType != AttachmentStore.PDF) TextButton(onClick = { viewing = a }) { Text("View") }
                TextButton(onClick = {
                    saving = a
                    val ok = if (a.mimeType == AttachmentStore.PDF) savePdf.launchOrFalse("receipt.pdf") else savePhoto.launchOrFalse("receipt.jpg")
                    if (!ok) { saving = null; error = NO_FILE_PICKER }
                }) { Text("Save") }
                IconButton(onClick = { scope.launch { c.attachments.delete(a) } }) { Icon(Icons.Outlined.Delete, "Delete") }
            }
        }
        error?.let { Text(it, color = KoshTheme.colors.alert, style = MaterialTheme.typography.bodySmall) }
    }

    picked?.let { uri ->
        var label by remember(uri) { mutableStateOf("") }
        var warranty by remember(uri) { mutableStateOf<LocalDate?>(null) }
        var busy by remember(uri) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) picked = null },
            title = { Text("Add receipt") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KoshTextField(label, { label = it.take(40) }, "Label (optional)", supporting = "e.g. Bill, Warranty card")
                    if (warranty != null) {
                        DateField("Warranty until", warranty!!, { warranty = it })
                        TextButton(onClick = { warranty = null }) { Text("No warranty") }
                    } else {
                        TextButton(onClick = { warranty = Time.toDate(t.timestamp).plusYears(1) }) { Text("Add a warranty end date") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { c.attachments.add(t.id, uri, label.trim().ifBlank { null }, warranty) } }
                            .onFailure { error = it.message ?: "Couldn't add the file" }
                        busy = false
                        picked = null
                    }
                }) { Text(if (busy) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { picked = null }) { Text("Cancel") } },
        )
    }

    viewing?.let { a ->
        var bitmap by remember(a.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        LaunchedEffect(a.id) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching { c.attachments.bytes(a).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
            }
        }
        AlertDialog(
            onDismissRequest = { viewing = null },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
            text = {
                val b = bitmap
                if (b != null) Image(b, a.label ?: "Receipt", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                else Spacer(Modifier.height(120.dp))
            },
        )
    }
}

/** Every warranty saved with a receipt, soonest to end first. */
@Composable
fun WarrantiesScreen(nav: androidx.navigation.NavController) {
    val c = LocalContainer.current
    val files by c.attachments.observeWarranties().collectAsState(initial = null)
    val today = Time.today()
    com.amitjha.kosh.ui.common.KoshScreen(title = "Warranties", onBack = { nav.popBackSafely() }) { pad ->
        val list = files ?: return@KoshScreen
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    com.amitjha.kosh.designsystem.components.EmptyState(
                        "No warranties",
                        "Add a receipt with a warranty date to a purchase and Kosh reminds you a month before it ends.",
                        icon = Icons.Outlined.AttachFile,
                    )
                }
            }
            items(list.size) { i ->
                val a = list[i]
                val until = LocalDate.ofEpochDay(a.warrantyUntil ?: 0)
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, until)
                KoshCard(onClick = { nav.navigate(com.amitjha.kosh.ui.nav.Routes.txn(a.transactionId)) }) {
                    Text(a.label ?: "Receipt", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            days < 0 -> "Ended ${Fmt.fullDate(until)}"
                            days == 0L -> "Ends today"
                            else -> "Ends ${Fmt.fullDate(until)} · in $days days"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (days in 0..30) KoshTheme.colors.warning else if (days < 0) KoshTheme.colors.subtleText else KoshTheme.colors.subtleText,
                    )
                }
            }
        }
    }
}
