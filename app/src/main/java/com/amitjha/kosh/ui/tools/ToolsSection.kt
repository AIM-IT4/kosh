package com.amitjha.kosh.ui.tools

import com.amitjha.kosh.ui.common.NO_FILE_PICKER
import com.amitjha.kosh.ui.common.launchOrFalse
import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.export.ExportData
import com.amitjha.kosh.export.ExportFormat
import com.amitjha.kosh.export.Exporters
import com.amitjha.kosh.security.DecoySession
import com.amitjha.kosh.security.PinHasher
import com.amitjha.kosh.tools.DigestSchedule
import com.amitjha.kosh.tools.ToolsPrefs
import com.amitjha.kosh.tools.ToolsSettings
import com.amitjha.kosh.ui.common.ActionRow
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalSettings
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.ui.settings.Section
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Date ranges offered by the export dialog. */
enum class ExportRange(val label: String) { THIS_PERIOD("This month"), LAST_PERIOD("Last month"), LAST_12("Last 12 months"), ALL("All time") }

/** Settings for export, widgets, the daily digest and the app PIN / decoy PIN. */
@Composable
fun ToolsSection(onMessage: (String) -> Unit) {
    val c = LocalContainer.current
    val s = LocalSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ToolsSettings.get(context) }
    val realPrefs by remember { store.prefs }.collectAsStateWithLifecycle(initialValue = ToolsPrefs())

    // The decoy profile shows ordinary-looking settings but never changes the real ones.
    val decoy = DecoySession.isActive
    var decoyPrefs by remember { mutableStateOf(ToolsPrefs(pinHash = DECOY_PLACEHOLDER)) }
    val prefs = if (decoy) decoyPrefs else realPrefs

    var exportOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<ExportFormat, ExportRange>?>(null) }
    var pinDialog by remember { mutableStateOf<PinAction?>(null) }

    fun writeExport(uri: Uri) {
        val (format, range) = pendingExport ?: return
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val data = exportData(c, range, s.payCycle)
                    val text = Exporters.export(format, data)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Couldn't open the file")
                    Exporters.rows(data).size
                }
            }
            onMessage(r.map { "Exported $it transactions" }.getOrElse { "Export failed: ${it.message}" })
        }
    }

    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) writeExport(uri) }
    val createText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) writeExport(uri) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun saveDigest(enabled: Boolean, minutes: Int = prefs.digestMinutes) {
        if (decoy) {
            decoyPrefs = decoyPrefs.copy(digestEnabled = enabled, digestMinutes = minutes); return
        }
        scope.launch {
            store.setDigestEnabled(enabled)
            store.setDigestMinutes(minutes)
            DigestSchedule.sync(context)
        }
    }

    Section("Tools") {
        ActionRow("Export data", "CSV, Excel, Beancount or Ledger", Icons.Outlined.FileDownload) { exportOpen = true }
        SwitchRow(
            "Daily digest",
            "Today's spending, budget left and items to review",
            prefs.digestEnabled,
            { on ->
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                saveDigest(on)
            },
            Icons.Outlined.Today,
        )
        if (prefs.digestEnabled) {
            ActionRow("Digest time", formatMinutes(prefs.digestMinutes), Icons.Outlined.Schedule) {
                TimePickerDialog(
                    context,
                    { _, h, m -> saveDigest(true, h * 60 + m) },
                    prefs.digestMinutes / 60, prefs.digestMinutes % 60,
                    DateFormat.is24HourFormat(context),
                ).show()
            }
        }
        SwitchRow(
            "Amounts on widget",
            if (s.biometricLockEnabled) "Show amounts on the home-screen widget even with app lock on" else "Turn on app lock to choose",
            prefs.widgetAmountsWhenLocked,
            { on ->
                if (decoy) decoyPrefs = decoyPrefs.copy(widgetAmountsWhenLocked = on)
                else scope.launch { store.setWidgetAmountsWhenLocked(on) }
            },
            Icons.Outlined.Widgets,
            enabled = s.biometricLockEnabled,
        )
    }

    Section("PIN") {
        ActionRow(
            "App PIN",
            when {
                !prefs.pinSet -> "Off · unlock with a PIN instead of fingerprint"
                s.biometricLockEnabled -> "On"
                else -> "On · used when app lock is on"
            },
            Icons.Outlined.Password,
        ) { pinDialog = if (prefs.pinSet) PinAction.MANAGE else PinAction.SET }
        ActionRow(
            "Decoy PIN",
            when {
                prefs.decoySet -> "On · opens a separate sample profile"
                !prefs.pinSet || !s.biometricLockEnabled -> "Needs app lock and an app PIN"
                else -> "Off · a second PIN that opens a sample profile"
            },
            Icons.Outlined.TheaterComedy,
            enabled = prefs.pinSet && s.biometricLockEnabled,
        ) { pinDialog = if (prefs.decoySet) PinAction.REMOVE_DECOY else PinAction.SET_DECOY }
    }

    if (exportOpen) {
        ExportDialog(
            onDismiss = { exportOpen = false },
            onExport = { format, range ->
                exportOpen = false
                pendingExport = format to range
                val (from, to) = rangeDates(range, s.payCycle, null)
                val name = Exporters.fileName(format, from ?: LocalDate.of(2000, 1, 1), to).let {
                    if (range == ExportRange.ALL) "kosh-all-${LocalDate.now()}.${format.extension}" else it
                }
                val opened = if (format.mimeType == "text/csv") createCsv.launchOrFalse(name) else createText.launchOrFalse(name)
                if (!opened) {
                    pendingExport = null
                    onMessage(NO_FILE_PICKER)
                }
            },
        )
    }

    pinDialog?.let { action ->
        PinDialog(
            action = action,
            // In the decoy profile the decoy PIN plays the part of the app PIN.
            currentHash = if (decoy) realPrefs.decoyPinHash else realPrefs.pinHash,
            otherHash = if (decoy) null else realPrefs.decoyPinHash,
            onDismiss = { pinDialog = null },
            onAction = { next -> pinDialog = next },
            onSave = { result ->
                pinDialog = null
                if (decoy) {
                    decoyPrefs = when (result) {
                        is PinResult.Real -> decoyPrefs.copy(pinHash = result.hash?.let { DECOY_PLACEHOLDER }, decoyPinHash = null)
                        is PinResult.Decoy -> decoyPrefs.copy(decoyPinHash = result.hash?.let { DECOY_PLACEHOLDER })
                    }
                } else {
                    scope.launch {
                        when (result) {
                            is PinResult.Real -> store.setPinHash(result.hash)
                            is PinResult.Decoy -> store.setDecoyPinHash(result.hash)
                        }
                    }
                }
                onMessage(
                    when (result) {
                        is PinResult.Real -> if (result.hash == null) "App PIN removed" else "App PIN saved"
                        is PinResult.Decoy -> if (result.hash == null) "Decoy PIN removed" else "Decoy PIN saved"
                    },
                )
            },
        )
    }
}

private const val DECOY_PLACEHOLDER = "decoy"

private fun formatMinutes(minutes: Int): String =
    LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Inclusive dates for [range]; `from` is null for "All time" until the data is known. */
internal fun rangeDates(range: ExportRange, cycle: com.amitjha.kosh.finance.PayCycle, earliest: LocalDate?, today: LocalDate = Time.today()): Pair<LocalDate?, LocalDate> {
    val period = Period.containing(today, cycle)
    return when (range) {
        ExportRange.THIS_PERIOD -> period.start to period.lastDay
        ExportRange.LAST_PERIOD -> period.previous().let { it.start to it.lastDay }
        ExportRange.LAST_12 -> today.minusMonths(12).plusDays(1) to today
        ExportRange.ALL -> earliest to today
    }
}

private suspend fun exportData(c: AppContainer, range: ExportRange, cycle: com.amitjha.kosh.finance.PayCycle): ExportData {
    val accounts = c.db.accounts().all()
    val categories = c.db.categories().all()
    val all = c.db.transactions().all()
    val earliest = all.minOfOrNull { it.timestamp }?.let { Time.toDate(it) }
    val (from, to) = rangeDates(range, cycle, earliest)
    val start = from ?: Time.today()
    // Transactions after the range stay in so balance assertions can tell whether a reported balance is final.
    return ExportData(accounts, categories, all, start, to)
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit, onExport: (ExportFormat, ExportRange) -> Unit) {
    var format by remember { mutableStateOf(ExportFormat.CSV) }
    var range by remember { mutableStateOf(ExportRange.THIS_PERIOD) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export data") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Range", style = MaterialTheme.typography.labelLarge, color = KoshTheme.colors.subtleText)
                ExportRange.entries.forEach { r -> Choice(r.label, r == range) { range = r } }
                Text("Format", style = MaterialTheme.typography.labelLarge, color = KoshTheme.colors.subtleText, modifier = Modifier.padding(top = 12.dp))
                ExportFormat.entries.forEach { f -> Choice(f.label, f == format) { format = f } }
                Text(
                    "The file is saved where you choose and isn't encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onExport(format, range) }) { Text("Export", fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private enum class PinAction { SET, MANAGE, CHANGE, REMOVE, SET_DECOY, REMOVE_DECOY }

private sealed interface PinResult {
    /** A new app PIN hash, or null to remove it (and the decoy PIN with it). */
    data class Real(val hash: String?) : PinResult
    data class Decoy(val hash: String?) : PinResult
}

@Composable
private fun PinDialog(
    action: PinAction,
    currentHash: String?,
    otherHash: String?,
    onDismiss: () -> Unit,
    onAction: (PinAction) -> Unit,
    onSave: (PinResult) -> Unit,
) {
    if (action == PinAction.MANAGE) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("App PIN") },
            text = { Text("Change your PIN, or remove it to unlock with fingerprint or screen lock only. Removing it also removes the decoy PIN.") },
            confirmButton = { TextButton(onClick = { onAction(PinAction.CHANGE) }) { Text("Change") } },
            dismissButton = { TextButton(onClick = { onAction(PinAction.REMOVE) }) { Text("Remove", color = KoshTheme.colors.expense) } },
        )
        return
    }
    val scope = rememberCoroutineScope()
    val needsCurrent = action != PinAction.SET
    val needsNew = action == PinAction.SET || action == PinAction.CHANGE || action == PinAction.SET_DECOY
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val title = when (action) {
        PinAction.SET -> "Set app PIN"
        PinAction.CHANGE -> "Change app PIN"
        PinAction.REMOVE -> "Remove app PIN"
        PinAction.SET_DECOY -> "Set decoy PIN"
        PinAction.REMOVE_DECOY -> "Remove decoy PIN"
        PinAction.MANAGE -> ""
    }

    fun submit() {
        if (needsNew) {
            if (!PinHasher.isValidPin(new)) { error = "Use ${PinHasher.MIN_LENGTH}–${PinHasher.MAX_LENGTH} digits"; return }
            if (new != confirm) { error = "The PINs don't match"; return }
        }
        working = true
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                // Changes need the current app PIN, so a borrowed unlocked phone can't change or reveal the setup.
                if (needsCurrent && !PinHasher.verify(current, currentHash)) return@withContext "Wrong current PIN"
                when (action) {
                    PinAction.SET, PinAction.CHANGE -> {
                        if (otherHash != null && PinHasher.verify(new, otherHash)) "Use a PIN different from the decoy PIN"
                        else PinResult.Real(PinHasher.hash(new))
                    }
                    PinAction.SET_DECOY -> {
                        if (action == PinAction.SET_DECOY && PinHasher.verify(new, currentHash)) "The decoy PIN must differ from the app PIN"
                        else PinResult.Decoy(PinHasher.hash(new))
                    }
                    PinAction.REMOVE -> PinResult.Real(null)
                    PinAction.REMOVE_DECOY -> PinResult.Decoy(null)
                    PinAction.MANAGE -> null
                }
            }
            working = false
            when (outcome) {
                is PinResult -> onSave(outcome)
                is String -> error = outcome
                else -> onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (action == PinAction.SET_DECOY) {
                    Text(
                        "Entering this PIN on the lock screen opens a separate profile with sample data. Your real data stays hidden, and bank SMS keep being recorded in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
                if (needsCurrent) PinField(current, "Current app PIN") { current = it; error = null }
                if (needsNew) {
                    PinField(new, if (action == PinAction.SET_DECOY) "Decoy PIN" else "New PIN") { new = it; error = null }
                    PinField(confirm, "Repeat PIN") { confirm = it; error = null }
                }
                error?.let { Text(it, color = KoshTheme.colors.expense, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !working) {
                Text(if (working) "…" else if (needsNew) "Save" else "Remove", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

@Composable
private fun PinField(value: String, label: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValue(v.filter { it.isDigit() }.take(PinHasher.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
