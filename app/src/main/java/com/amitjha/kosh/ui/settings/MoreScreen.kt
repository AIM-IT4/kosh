package com.amitjha.kosh.ui.settings

import com.amitjha.kosh.designsystem.theme.KoshText
import com.amitjha.kosh.ui.common.GmailExplainerSheet
import com.amitjha.kosh.ui.common.friendlyGmailError
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.amitjha.kosh.data.prefs.ThemeMode
import com.amitjha.kosh.designsystem.components.CategoryIcon
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.KoshLogo
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.gmail.GmailAuthOutcome
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.security.BackupCrypto
import com.amitjha.kosh.security.BiometricGate
import com.amitjha.kosh.security.UnlockResult
import com.amitjha.kosh.sms.ImportResult
import com.amitjha.kosh.ui.common.ActionRow
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalSettings
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.ui.common.color
import com.amitjha.kosh.ui.nav.Routes
import kotlinx.coroutines.launch
import java.time.LocalDate

fun ImportResult.describe(): String = when {
    permissionMissing -> "SMS permission is needed to scan your inbox"
    inserted == 0 && bills == 0 -> "Scanned $scanned messages · nothing new"
    else -> buildString {
        append("Added $inserted transaction${if (inserted == 1) "" else "s"}")
        if (bills > 0) append(", $bills bill${if (bills == 1) "" else "s"}")
        if (accountsCreated > 0) append(", $accountsCreated new account${if (accountsCreated == 1) "" else "s"}")
        append(" from $scanned messages")
    }
}

fun Context.hasSmsPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

private fun Context.versionName(): String = runCatching {
    @Suppress("DEPRECATION")
    packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
}.getOrDefault("1.0")

@Composable
fun MoreScreen(nav: NavController) {
    val c = LocalContainer.current
    val s = LocalSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    var backupUri by remember { mutableStateOf<Uri?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    fun show(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    fun scanSms() {
        busy = "Scanning SMS inbox…"
        scope.launch {
            val r = runCatching { c.smsImporter.importInbox() }
            busy = null
            show(r.map { it.describe() }.getOrElse { "Scan failed: ${it.message}" })
        }
    }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.READ_SMS] == true) scanSms() else show("SMS permission denied")
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun syncGmail() {
        busy = "Reading card statements from Gmail…"
        scope.launch {
            val r = runCatching { c.gmailSync.sync() }.getOrNull()
            busy = null
            show(
                when {
                    r == null -> "Gmail sync failed"
                    r.needsReconnect -> "Please reconnect Gmail"
                    r.error != null -> r.error
                    else -> "Checked ${r.scanned} emails · ${r.billsAdded} new bill${if (r.billsAdded == 1) "" else "s"}"
                },
            )
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            when (val o = c.gmailAuth.completeConsent(result.data)) {
                is GmailAuthOutcome.Granted -> syncGmail()
                is GmailAuthOutcome.Failed -> show(friendlyGmailError(o.message))
                is GmailAuthOutcome.NeedsConsent -> show("Google needs more consent; try again")
            }
        }
    }
    var showGmailExplainer by remember { mutableStateOf(false) }
    fun startGmailConsent() {
        val activity = context as? Activity ?: return
        scope.launch {
            when (val o = c.gmailAuth.connect(activity)) {
                is GmailAuthOutcome.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(o.pendingIntent).build())
                is GmailAuthOutcome.Granted -> syncGmail()
                is GmailAuthOutcome.Failed -> show(friendlyGmailError(o.message))
            }
        }
    }

    fun connectGmail() {
        showGmailExplainer = true
    }
    if (showGmailExplainer) {
        GmailExplainerSheet(
            onContinue = {
                showGmailExplainer = false
                startGmailConsent()
            },
            onDismiss = { showGmailExplainer = false },
        )
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) backupUri = uri
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp)) {
            item(key = "head") {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KoshLogo(size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Kosh", style = KoshText.screenTitle)
                        Text("Your money, on your phone", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    }
                }
            }
            item(key = "shortcuts") {
                Section("Manage") {
                    ActionRow("Accounts & cards", "Balances, limits and reconciliation", Icons.Outlined.AccountBalance) { nav.navigate(Routes.ACCOUNTS) }
                    ActionRow("Card bills", "Due dates and payments", Icons.Outlined.CreditCard) { nav.navigate(Routes.BILLS) }
                    ActionRow("Review imports", "Check SMS and email transactions", Icons.Outlined.Inbox) { nav.navigate(Routes.REVIEW) }
                    ActionRow("Insights", "Offline suggestions for your budget and goals", Icons.Outlined.AutoAwesome) { nav.navigate(Routes.INSIGHTS) }
                    ActionRow("Categories", null, Icons.Outlined.Category) { nav.navigate(Routes.CATEGORIES) }
                }
            }
            item(key = "appearance") {
                Section("Appearance & privacy") {
                    SegmentedTabs(
                        listOf("System", "Light", "Dark"),
                        ThemeMode.entries.indexOf(s.themeMode),
                        onSelect = { scope.launch { c.settings.setThemeMode(ThemeMode.entries[it]) } },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    SwitchRow("Hide amounts", "Show ₹ •••• until you tap the eye", s.hideAmounts, { v -> scope.launch { c.settings.setHideAmounts(v) } },
                        Icons.Outlined.VisibilityOff)
                    val canLock = remember { BiometricGate.isAvailable(context) }
                    SwitchRow(
                        "App lock",
                        if (canLock) "Fingerprint, face or screen lock when opening Kosh" else "Set a screen lock on your phone to use this",
                        s.biometricLockEnabled,
                        { v ->
                            val activity = context as? FragmentActivity
                            if (!v) {
                                scope.launch { c.settings.setBiometricLock(false) }
                            } else if (activity != null) {
                                scope.launch {
                                    if (BiometricGate.authenticate(activity, title = "Turn on app lock") == UnlockResult.Success) {
                                        c.settings.setBiometricLock(true)
                                    }
                                }
                            }
                        },
                        Icons.Outlined.Fingerprint,
                        enabled = canLock,
                    )
                }
            }
            item(key = "sms") {
                Section("SMS & email") {
                    SwitchRow("Import bank SMS automatically", "New bank messages are added as they arrive. SMS never leave your phone.",
                        s.smsAutoImport, { v -> scope.launch { c.settings.setSmsAutoImport(v) } }, Icons.Outlined.Sms)
                    ActionRow("Scan inbox now", "Last scan ${Fmt.ago(s.smsLastScanMillis)}", Icons.Outlined.Sync, enabled = busy == null) {
                        if (context.hasSmsPermission()) scanSms()
                        else smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                    }
                    if (s.gmailConnected) {
                        ActionRow(
                            "Gmail: ${s.gmailAccountEmail}",
                            if (s.gmailReconnectNeeded) "Access expired — tap to reconnect" else "Last checked ${Fmt.ago(s.gmailLastSyncMillis)} · tap to sync now",
                            Icons.Outlined.Email,
                            enabled = busy == null,
                        ) { if (s.gmailReconnectNeeded) connectGmail() else syncGmail() }
                        ActionRow("Disconnect Gmail", null, null) { scope.launch { c.gmailAuth.disconnect() } }
                    } else {
                        ActionRow("Connect Gmail", "Read credit card statements for bill amounts and due dates (read-only)", Icons.Outlined.Email,
                            enabled = busy == null) { connectGmail() }
                    }
                }
            }
            item(key = "notif") {
                Section("Notifications") {
                    val ensurePermission: () -> Unit = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    SwitchRow("New transactions", "When a bank SMS is imported", s.notifyTransactions, { v ->
                        if (v) ensurePermission()
                        scope.launch { c.settings.setNotifyTransactions(v) }
                    }, Icons.Outlined.Notifications)
                    SwitchRow("Bill reminders", "3 days and 1 day before the due date", s.notifyBills, { v ->
                        if (v) ensurePermission()
                        scope.launch { c.settings.setNotifyBills(v) }
                    })
                    SwitchRow("Important insights", "Overspending and maturing FDs", s.notifyInsights, { v ->
                        if (v) ensurePermission()
                        scope.launch { c.settings.setNotifyInsights(v) }
                    })
                }
            }
            item(key = "period") {
                Section("Budget month") {
                    ChoiceField(
                        "Month starts on",
                        (1..28).toList(),
                        s.monthStartDay,
                        { if (it == 1) "1st (calendar month)" else com.amitjha.kosh.ui.accounts.ordinal(it) + " (e.g. salary day)" },
                        { d -> scope.launch { c.settings.setMonthStartDay(d) } },
                        leading = null,
                    )
                }
            }
            item(key = "data") {
                Section("Your data") {
                    ActionRow("Back up", "Encrypted file protected by a password", Icons.Outlined.Backup, enabled = busy == null) {
                        createBackup.launch("kosh-backup-${LocalDate.now()}.kosh")
                    }
                    ActionRow("Restore from backup", "Replaces everything on this phone", Icons.Outlined.Restore, enabled = busy == null) {
                        openBackup.launch(arrayOf("*/*"))
                    }
                    ActionRow("Load sample data", "Explore Kosh with demo accounts and transactions", Icons.Outlined.Science, enabled = busy == null) {
                        busy = "Loading sample data…"
                        scope.launch {
                            c.demoData.load()
                            c.investments.revalueAll(fetchNavs = false)
                            c.netWorth.snapshot()
                            busy = null
                            show("Sample data loaded")
                        }
                    }
                    ActionRow("Erase all data", "Deletes accounts, transactions and investments", Icons.Outlined.DeleteForever) { confirmErase = true }
                }
            }
            item(key = "about") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Kosh ${context.versionName()}", style = MaterialTheme.typography.labelLarge, color = KoshTheme.colors.subtleText)
                    Text("Everything stays on this device.", style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
                }
            }
        }
        busy?.let {
            KoshCard(Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp, start = 20.dp, end = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(it)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }

    if (confirmErase) {
        ConfirmDialog(
            "Erase all data?",
            "This deletes every account, transaction, bill, budget, goal and investment on this phone. Make a backup first if unsure.",
            "Erase",
            destructive = true,
            onConfirm = {
                confirmErase = false
                scope.launch {
                    c.demoData.clear()
                    show("All data erased")
                }
            },
            onDismiss = { confirmErase = false },
        )
    }
    backupUri?.let { uri ->
        PasswordDialog("Protect your backup", "Choose a password. You'll need it to restore — it can't be recovered.", confirm = true,
            onDismiss = { backupUri = null }) { pw ->
            backupUri = null
            busy = "Creating backup…"
            scope.launch {
                val r = runCatching { c.backup.export(uri, pw.toCharArray()) }
                busy = null
                show(r.map { "Backed up ${it.transactions} transactions and ${it.accounts} accounts" }.getOrElse { "Backup failed: ${it.message}" })
            }
        }
    }
    restoreUri?.let { uri ->
        PasswordDialog("Restore backup", "Enter the backup's password. Current data on this phone will be replaced.", confirm = false,
            onDismiss = { restoreUri = null }) { pw ->
            restoreUri = null
            busy = "Restoring…"
            scope.launch {
                val r = runCatching { c.backup.restore(uri, pw.toCharArray()) }
                busy = null
                show(
                    r.map { "Restored ${it.transactions} transactions and ${it.accounts} accounts" }.getOrElse {
                        if (it is BackupCrypto.WrongPasswordException) "Wrong password or damaged file" else "Restore failed: ${it.message}"
                    },
                )
            }
        }
    }
}

/** A titled group of settings rows on a card. */
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(title.uppercase(), style = KoshText.sectionLabel, color = KoshTheme.colors.subtleText)
        Spacer(Modifier.height(8.dp))
        KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
private fun PasswordDialog(title: String, message: String, confirm: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val ok = pw.length >= 6 && (!confirm || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(
                    pw, { pw = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirm) {
                    androidx.compose.material3.OutlinedTextField(
                        pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pw2.isNotEmpty() && pw != pw2,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDone(pw) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- categories

@Composable
fun CategoriesScreen(nav: NavController) {
    val c = LocalContainer.current
    val cats by c.categories.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    KoshScreen(title = "Categories", subtitle = "Recategorise a transaction to teach Kosh your merchants", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 40.dp)) {
            listOf(TxnType.EXPENSE to "Spending", TxnType.INCOME to "Income").forEach { (type, title) ->
                item(key = title) {
                    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                }
                items(cats.filter { it.type == type }, key = { it.key }) { cat ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryIcon(cat.icon, cat.color(), size = 38.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(cat.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
