package com.amitjha.kosh.ui.features

import com.amitjha.kosh.ui.common.NO_FILE_PICKER
import com.amitjha.kosh.ui.common.launchOrFalse
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.StatementImporter
import com.amitjha.kosh.security.AutoBackup
import com.amitjha.kosh.ui.common.ActionRow
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.ui.settings.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PASSWORD = 8

/** Scheduled, encrypted backups to a folder the user picks (on the phone, an SD card or a synced cloud folder). */
@Composable
fun AutoBackupScreen(nav: NavController) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val features by c.featurePrefs.settings.collectAsState(initial = null)
    var hasPassword by remember { mutableStateOf(AutoBackup.hasPassword(context)) }
    var askPassword by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val a = features?.autoBackup ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching { AutoBackup.rememberFolder(context, uri) }.isSuccess
            if (ok) c.featurePrefs.setAutoBackup(a.enabled || hasPassword, uri.toString(), a.everyDays, a.keep)
            else snackbar.showSnackbar("Kosh can't keep access to that folder. Pick another one.")
        }
    }

    KoshScreen(title = "Automatic backups", onBack = { nav.popBackSafely() }, snackbar = snackbar) { pad ->
        val a = features?.autoBackup ?: return@KoshScreen
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)) {
            item {
                Text(
                    "Kosh writes an encrypted backup to the folder you choose, and keeps the newest few. Pick a folder that " +
                        "a cloud app syncs (Drive, OneDrive) to keep a copy off the phone. Restore any of them from More → Restore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KoshTheme.colors.subtleText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Section("Setup") {
                    ActionRow(
                        "Backup folder",
                        a.folderUri?.let { folderName(it) } ?: "Not chosen",
                        Icons.Outlined.Folder,
                    ) { if (!pickFolder.launchOrFalse(null)) scope.launch { snackbar.showSnackbar(NO_FILE_PICKER) } }
                    ActionRow(
                        "Backup password",
                        if (hasPassword) "Set · kept encrypted on this phone" else "Not set",
                        Icons.Outlined.Key,
                    ) { askPassword = true }
                    SwitchRow(
                        "Back up automatically",
                        if (a.folderUri == null || !hasPassword) "Choose a folder and a password first" else null,
                        a.enabled,
                        { on -> scope.launch { c.featurePrefs.setAutoBackup(on, a.folderUri, a.everyDays, a.keep) } },
                        icon = Icons.Outlined.Backup,
                        enabled = a.folderUri != null && hasPassword,
                    )
                }
            }
            item {
                Section("Schedule") {
                    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SegmentedTabs(listOf("Daily", "Weekly"), if (a.everyDays >= 7) 1 else 0,
                            onSelect = { scope.launch { c.featurePrefs.setAutoBackup(a.enabled, a.folderUri, if (it == 1) 7 else 1, a.keep) } })
                        ChoiceField("Keep", listOf(3, 7, 14, 30), a.keep, { "Newest $it backups" },
                            { k -> scope.launch { c.featurePrefs.setAutoBackup(a.enabled, a.folderUri, a.everyDays, k) } })
                    }
                }
            }
            item {
                Section("Status") {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(
                            if (a.lastMillis > 0) "Last backup ${Fmt.ago(a.lastMillis)}" + (a.lastFile?.let { " · $it" } ?: "") else "No automatic backup yet",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        a.lastError?.let {
                            Spacer(Modifier.height(4.dp))
                            Text("Last attempt failed: $it", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.alert)
                        }
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            if (running) "Backing up…" else "Back up now",
                            enabled = !running && a.folderUri != null && hasPassword,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                running = true
                                scope.launch {
                                    val r = runCatching { AutoBackup.run(context, c, a) }
                                    running = false
                                    snackbar.showSnackbar(r.map { "Saved $it" }.getOrElse { "Backup failed: ${it.message}" })
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (askPassword) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("Backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Every automatic backup is encrypted with this password. You need it to restore, so keep it somewhere safe.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    KoshTextField(pw, { pw = it }, "Password", keyboardType = KeyboardType.Password)
                    KoshTextField(pw2, { pw2 = it }, "Repeat password", keyboardType = KeyboardType.Password)
                }
            },
            confirmButton = {
                TextButton(enabled = pw.length >= MIN_PASSWORD && pw == pw2, onClick = {
                    AutoBackup.setPassword(context, pw.toCharArray())
                    hasPassword = true
                    askPassword = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text("Cancel") } },
        )
    }
}

private fun folderName(uri: String): String =
    runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifBlank { "Selected folder" } }
        .getOrDefault("Selected folder")

/** Imports a bank statement CSV into one account, previewing what is new before adding it. */
@Composable
fun StatementImportScreen(nav: NavController) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val hidden = LocalHideAmounts.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val accounts by c.accounts.observeAccounts().collectAsState(initial = emptyList())
    var accountId by remember { mutableStateOf<Long?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<StatementImporter.Preview?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        val t = text ?: return
        val acc = accountId ?: return
        scope.launch {
            busy = true
            preview = runCatching { c.statementImporter.preview(t, acc) }.getOrNull()
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        val bytes = s.readBytes()
                        require(bytes.size <= MAX_CSV_BYTES) { "That file is too large" }
                        String(bytes, Charsets.UTF_8)
                    } ?: error("Can't read the file")
                }
            }
            r.onSuccess {
                text = it
                fileName = uri.lastPathSegment?.substringAfterLast('/')
                refresh()
            }.onFailure { snackbar.showSnackbar("Couldn't open it: ${it.message}") }
        }
    }

    KoshScreen(title = "Import statement", onBack = { nav.popBackSafely() }, snackbar = snackbar) { pad ->
        val usable = accounts.filter { !it.isPending }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Download your statement as CSV (or Excel saved as CSV) from net banking, then pick it here. Kosh reads " +
                        "it on the phone, skips rows it already has from SMS, and adds the rest for you to review.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KoshTheme.colors.subtleText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                ChoiceField("Into account", usable, usable.firstOrNull { it.id == accountId }, { it.account.label() }, {
                    accountId = it.id
                    refresh()
                })
            }
            item {
                PrimaryButton(fileName ?: "Choose CSV file", icon = Icons.Outlined.UploadFile, modifier = Modifier.fillMaxWidth(), onClick = {
                    val types = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "*/*")
                    if (!pick.launchOrFalse(types)) scope.launch { snackbar.showSnackbar(NO_FILE_PICKER) }
                })
            }
            val p = preview
            if (busy) item { Text("Reading…", color = KoshTheme.colors.subtleText) }
            if (p != null && text != null) {
                if (p.parsed.rows.isEmpty()) {
                    item {
                        Text(
                            "No transactions found. Kosh looks for a header row with Date, Narration/Description and Debit/Credit " +
                                "(or Amount) columns.",
                            color = KoshTheme.colors.alert,
                        )
                    }
                } else {
                    item {
                        KoshCard {
                            Text("${p.fresh.size} new · ${p.duplicates} already in Kosh", style = MaterialTheme.typography.titleMedium)
                            if (p.parsed.skippedLines > 0) {
                                Text("${p.parsed.skippedLines} lines skipped (totals, balances or unreadable rows)", style = MaterialTheme.typography.bodySmall,
                                    color = KoshTheme.colors.subtleText)
                            }
                            val out = p.fresh.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
                            val inn = p.fresh.filter { it.type == TxnType.INCOME }.sumOf { it.amount }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Column(Modifier.weight(1f)) {
                                    Text("Money out", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                                    AmountText(out, type = TxnType.EXPENSE, hidden = hidden)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Money in", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                                    AmountText(inn, type = TxnType.INCOME, hidden = hidden)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton("Import ${p.fresh.size}", enabled = p.fresh.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                val acc = accountId ?: return@PrimaryButton
                                busy = true
                                scope.launch {
                                    val n = runCatching { c.statementImporter.import(acc, p.fresh) }
                                    busy = false
                                    n.onSuccess {
                                        snackbar.showSnackbar("Added $it transactions. Check them in Review.")
                                        nav.popBackSafely()
                                        nav.navigate(Routes.REVIEW)
                                    }.onFailure { snackbar.showSnackbar("Import failed: ${it.message}") }
                                }
                            })
                        }
                    }
                    item { SectionHeader("New rows") }
                    items(p.fresh.take(PREVIEW_ROWS)) { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(Fmt.fullDate(r.date), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                            }
                            AmountText(r.amount, style = AmountStyles.medium, type = r.type, hidden = hidden)
                        }
                    }
                    if (p.fresh.size > PREVIEW_ROWS) {
                        item { Text("and ${p.fresh.size - PREVIEW_ROWS} more", color = KoshTheme.colors.subtleText, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
    }
}

private const val PREVIEW_ROWS = 50
private const val MAX_CSV_BYTES = 8 * 1024 * 1024
