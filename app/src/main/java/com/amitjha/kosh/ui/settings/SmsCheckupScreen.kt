package com.amitjha.kosh.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.sms.CheckupItem
import com.amitjha.kosh.sms.CheckupItem.Verdict
import com.amitjha.kosh.sms.SmsCatchUp
import com.amitjha.kosh.sms.SmsImporter
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.LocalSettings
import com.amitjha.kosh.ui.common.rememberSmsPermissionRequest
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.nav.popBackSafely
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A read-only look at the last week of bank SMS: what Kosh counted, what it holds but doesn't count (and why),
 * what is missing and what it skipped, plus everything that stops messages arriving. The report can be shared
 * with numbers masked, so a missed format can be fixed.
 */
@Composable
fun SmsCheckupScreen(nav: NavController) {
    val c = LocalContainer.current
    val s = LocalSettings.current
    val hidden = LocalHideAmounts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<CheckupItem>?>(null) }
    var read by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(reload) {
        val (found, count) = runCatching { c.smsImporter.checkup() }.getOrDefault(emptyList<CheckupItem>() to 0)
        items = found
        read = count
    }
    val requestSms = rememberSmsPermissionRequest { reload++ }
    val status = checkupStatus(context, s.smsAutoImport)

    fun importMissing() {
        busy = true
        scope.launch {
            val r = runCatching { c.smsImporter.importInbox(System.currentTimeMillis() - SmsImporter.CHECKUP_DAYS * DAY_MILLIS) }
            busy = false
            snackbar.showSnackbar(r.map { it.describe() }.getOrElse { "Import failed: ${it.message}" })
            reload++
        }
    }

    KoshScreen(
        title = "SMS check-up",
        subtitle = "Bank SMS from the last ${SmsImporter.CHECKUP_DAYS} days",
        onBack = { nav.popBackSafely() },
        snackbar = snackbar,
        actions = {
            val list = items
            if (list != null) {
                IconButton(onClick = { shareReport(context, report(status, s.smsLastScanMillis, s.smsProblem, list, read)) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share report")
                }
            }
        },
    ) { pad ->
        val list = items
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "status") {
                KoshCard {
                    StatusLine(status.readSms, "Read bank SMS", "Needed to find bank messages in your inbox")
                    StatusLine(status.receiveSms, "Get new SMS as they arrive", "Without it, new SMS wait for the next check")
                    StatusLine(status.autoImport, "Import automatically", "Turned off in Settings › Bank SMS")
                    StatusLine(
                        status.batteryUnrestricted, "Battery: not restricted",
                        "Battery saving can stop Kosh hearing new SMS; the hourly check still catches up",
                    )
                    if (!status.readSms || !status.receiveSms) {
                        TextButton(onClick = requestSms) { Text("Allow SMS access") }
                    }
                    if (!status.batteryUnrestricted) {
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        }) { Text("Open battery settings") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Last inbox check ${Fmt.ago(s.smsLastScanMillis)}", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    s.smsProblem?.let {
                        Text("$it (${Fmt.ago(s.smsProblemAt)})", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.alert)
                    }
                }
            }
            if (list == null) {
                item(key = "loading") { Text("Reading messages…", color = KoshTheme.colors.subtleText) }
                return@LazyColumn
            }
            item(key = "summary") {
                val n = list.groupingBy { it.verdict }.eachCount()
                Column {
                    Text(
                        "$read SMS read · ${list.size} from banks",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${n[Verdict.COUNTED] ?: 0} counted · ${n[Verdict.NOT_COUNTED] ?: 0} not counted · " +
                            "${n[Verdict.MISSING] ?: 0} missing · ${(n[Verdict.SKIPPED] ?: 0) + (n[Verdict.OTHER] ?: 0)} skipped",
                        style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText,
                    )
                    if ((n[Verdict.MISSING] ?: 0) > 0) {
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton(if (busy) "Importing…" else "Import missing now", onClick = { importMissing() }, enabled = !busy && status.readSms)
                    }
                    val ignored = list.filter { it.accountIgnored }.mapNotNull { it.accountName }.distinct()
                    if (ignored.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Not counted because you ignored ${ignored.joinToString(", ")}. Count ${if (ignored.size == 1) "it" else "them"} " +
                                "again in Accounts › Ignored.",
                            style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.alert,
                        )
                        Spacer(Modifier.height(6.dp))
                        PrimaryButton("Review ignored accounts", onClick = { nav.navigate(Routes.ACCOUNTS) })
                    }
                }
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (status.readSms) "No bank SMS in the last ${SmsImporter.CHECKUP_DAYS} days." else "Allow SMS access to check your messages.",
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
            val byDay = list.withIndex().groupBy { dayOf(it.value.at) }
            byDay.forEach { (day, rows) ->
                item(key = "d$day") {
                    Text(Fmt.dayLabel(day), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                }
                items(rows, key = { "i${it.index}" }) { (i, item) ->
                    CheckupRow(item, hidden, expanded = open == i) {
                        val id = item.transactionId
                        if (id != null) nav.navigate(Routes.txn(id)) else open = if (open == i) null else i
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, title: String, whenOff: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(if (ok) "✓" else "✗", color = if (ok) KoshTheme.colors.good else KoshTheme.colors.alert, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!ok) Text(whenOff, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        }
    }
}

@Composable
private fun CheckupRow(item: CheckupItem, hidden: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val (line, color) = verdictLine(item)
    KoshCard(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Text(maskAmounts(item.title, hidden), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text("${Fmt.time(item.at)} · $line", style = MaterialTheme.typography.bodySmall, color = color)
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(maskAmounts(item.masked, hidden), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        }
    }
}

@Composable
private fun verdictLine(item: CheckupItem): Pair<String, Color> = when (item.verdict) {
    Verdict.COUNTED -> buildString {
        append("Counted")
        item.accountName?.let { append(" · $it") }
        misdated(item)?.let { append(" · dated $it") }
    } to (if (misdated(item) != null) KoshTheme.colors.warning else KoshTheme.colors.good)
    Verdict.NOT_COUNTED -> "In Kosh, not counted: ${item.reason}${item.accountName?.let { " · $it" } ?: ""}" to KoshTheme.colors.warning
    Verdict.MISSING -> "Not in Kosh yet" to KoshTheme.colors.alert
    Verdict.OTHER -> (item.reason ?: "Not a spend").replaceFirstChar { it.uppercase() } to KoshTheme.colors.subtleText
    Verdict.SKIPPED -> "Skipped: ${item.reason}" to KoshTheme.colors.subtleText
}

/** The stored date when it falls on another day than the message, e.g. "15 Oct". */
private fun misdated(item: CheckupItem): String? {
    val dated = item.datedAt ?: return null
    return if (dayOf(dated) != dayOf(item.at)) Fmt.date(dayOf(dated)) else null
}

private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun maskAmounts(text: String, hidden: Boolean): String =
    if (!hidden) text else text.replace(Regex("(₹|Rs\\.?|INR)\\s*[0-9][0-9,]*(\\.[0-9]+)?", RegexOption.IGNORE_CASE), "₹ ••••")

private data class CheckupStatus(val readSms: Boolean, val receiveSms: Boolean, val autoImport: Boolean, val batteryUnrestricted: Boolean)

private fun checkupStatus(context: Context, autoImport: Boolean): CheckupStatus {
    val power = context.getSystemService(PowerManager::class.java)
    return CheckupStatus(
        readSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED,
        receiveSms = SmsCatchUp.canReceive(context),
        autoImport = autoImport,
        batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )
}

/** Plain-text report with numbers masked; counted spends are listed without their message. */
private fun report(st: CheckupStatus, lastScan: Long, problem: String?, items: List<CheckupItem>, read: Int): String = buildString {
    fun yes(b: Boolean) = if (b) "yes" else "NO"
    appendLine("Kosh ${com.amitjha.kosh.BuildConfig.VERSION_NAME} · SMS check-up · ${Fmt.fullDate(LocalDate.now())} ${Fmt.time(System.currentTimeMillis())}")
    appendLine("Read SMS ${yes(st.readSms)} · receive SMS ${yes(st.receiveSms)} · auto-import ${yes(st.autoImport)} · battery unrestricted ${yes(st.batteryUnrestricted)}")
    appendLine("Last inbox check ${Fmt.ago(lastScan)}${problem?.let { " · problem: $it" } ?: ""}")
    appendLine("$read SMS read, ${items.size} from banks")
    for (item in items) {
        appendLine()
        val at = "${Fmt.date(dayOf(item.at))} ${Fmt.time(item.at)}"
        val note = when (item.verdict) {
            Verdict.COUNTED -> "counted${misdated(item)?.let { ", dated $it" } ?: ""}"
            else -> "${item.verdict.name.lowercase().replace('_', ' ')}${item.reason?.let { ": $it" } ?: ""}"
        } + (item.accountName?.let { " · ${it.replace(Regex("\\d{3,}")) { d -> "X".repeat(d.value.length) }}" } ?: "")
        appendLine("$at · ${item.title} · $note")
        if (item.verdict != Verdict.COUNTED || misdated(item) != null) appendLine("  ${item.masked}")
    }
}

private fun shareReport(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, "Kosh SMS check-up")
    runCatching { context.startActivity(Intent.createChooser(send, "Share SMS check-up")) }
}

private const val DAY_MILLIS = 86_400_000L
