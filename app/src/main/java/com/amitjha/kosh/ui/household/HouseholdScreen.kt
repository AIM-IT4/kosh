package com.amitjha.kosh.ui.household

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SecondaryButton
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sync.HouseholdData
import com.amitjha.kosh.sync.PairingCode
import com.amitjha.kosh.sync.SyncStatus
import com.amitjha.kosh.ui.common.ActionRow
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalSettings
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Household sync: pair two phones with a QR code and share marked transactions, budgets and goals over
 * the same Wi-Fi, end-to-end encrypted. While this screen is open the phone can be found by its partner.
 */
@Composable
fun HouseholdScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val sync = c.householdSync
    val state by c.householdStore.state.collectAsState()
    val status by sync.status.collectAsState()
    val code by sync.pairingCode.collectAsState()
    val data by c.household.observeData().collectAsState(initial = HouseholdData(emptyList(), emptyList(), emptyList()))
    val hidden = com.amitjha.kosh.ui.common.LocalHideAmounts.current
    val monthStartDay = LocalSettings.current.monthStartDay
    val period = remember(monthStartDay) { Period.containing(Time.today(), monthStartDay) }
    val scope = rememberCoroutineScope()
    var confirmUnpair by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        sync.start()
        onDispose {
            sync.stop()
            sync.clearStatus()
        }
    }

    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val parsed = PairingCode.decode(text)
        if (parsed == null) {
            sync.clearStatus()
        } else {
            scope.launch { sync.pairWith(parsed) }
        }
    }
    val scan = {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the Kosh code on the other phone")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    KoshScreen("Household", subtitle = "Share with your partner, no cloud", onBack = { nav.popBackSafely() }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "status") { StatusCard(status) }
            if (state.peer == null) {
                item(key = "pair") {
                    KoshCard {
                        Text("Pair with your partner's phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Both phones on the same Wi-Fi. Open this screen on both, show the code on one and scan it with the other. " +
                                "Only what you mark as household is shared, encrypted end to end — nothing goes to a server.",
                            style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText,
                        )
                        Spacer(Modifier.height(14.dp))
                        if (code == null) {
                            PrimaryButton("Show my code", onClick = { sync.openPairing() }, Modifier.fillMaxWidth(), icon = Icons.Outlined.QrCode2)
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton("Scan partner's code", onClick = scan, Modifier.fillMaxWidth(), icon = Icons.Outlined.QrCodeScanner)
                        } else {
                            QrBox(code!!)
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton("Cancel", onClick = { sync.closePairing() }, Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                item(key = "peer") {
                    KoshCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Paired with", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                                Text(state.peer!!.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                val last = state.lastSyncMillis
                                Text(
                                    if (last == null) "Not synced yet" else "Last sync ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))}" +
                                        (state.lastSummary?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(
                            "Sync now",
                            onClick = { scope.launch { sync.syncNow() } },
                            Modifier.fillMaxWidth(),
                            enabled = status !is SyncStatus.Working,
                            icon = Icons.Outlined.Sync,
                        )
                        Text(
                            "Keep this screen open on ${state.peer!!.name} too.",
                            style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        ActionRow("Unpair", "Stop sharing and remove your partner's records from this phone", Icons.Outlined.LinkOff) { confirmUnpair = true }
                    }
                }
            }

            item(key = "summary") { SummaryCard(data, hidden, period) }

            if (data.budgets.isNotEmpty() || data.goals.isNotEmpty()) {
                item(key = "plans") {
                    KoshCard {
                        SectionHeader("Shared budgets & goals")
                        data.budgets.forEach { b ->
                            val spent = monthSpend(data, b.categoryKey, period)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${b.categoryName} budget", style = MaterialTheme.typography.bodyLarge)
                                    Text("by ${if (b.mine) "you" else b.ownerName}", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                                }
                                AmountText(spent, hidden = hidden)
                                Text(" / ", color = KoshTheme.colors.subtleText)
                                AmountText(b.monthlyLimit, hidden = hidden, color = KoshTheme.colors.subtleText)
                            }
                        }
                        data.goals.forEach { g ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.name, style = MaterialTheme.typography.bodyLarge)
                                    Text("Goal by ${if (g.mine) "you" else g.ownerName}", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                                }
                                AmountText(g.saved, hidden = hidden)
                                Text(" / ", color = KoshTheme.colors.subtleText)
                                AmountText(g.target, hidden = hidden, color = KoshTheme.colors.subtleText)
                            }
                        }
                    }
                }
            }

            item(key = "txns-header") { SectionHeader("Household transactions") }
            if (data.txns.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        "Nothing shared yet",
                        "Open a transaction and turn on \"Household\" to share it. Budgets and goals have the same switch.",
                        icon = Icons.Outlined.Groups,
                    )
                }
            } else {
                items(data.txns.take(200), key = { "${it.timestamp}-${it.amount}-${it.ownerName}-${it.merchant}" }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.merchant ?: t.categoryName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${t.categoryName} · ${if (t.mine) "You" else t.ownerName} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t.timestamp))}",
                                style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText,
                            )
                        }
                        AmountText(t.amount, type = t.type, hidden = hidden)
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        ConfirmDialog(
            title = "Unpair ${state.peer?.name ?: "phone"}?",
            message = "Your partner's shared records are removed from this phone. What you shared stays marked, ready for a new pairing.",
            confirmLabel = "Unpair",
            onConfirm = {
                confirmUnpair = false
                scope.launch {
                    c.household.clearReceived()
                    c.householdStore.unpair()
                }
            },
            onDismiss = { confirmUnpair = false },
        )
    }
}

@Composable
private fun StatusCard(status: SyncStatus) {
    val (text, color) = when (status) {
        SyncStatus.Idle -> return
        is SyncStatus.Working -> status.message to KoshTheme.colors.subtleText
        is SyncStatus.Done -> "Synced · ${status.summary}" to KoshTheme.colors.income
        is SyncStatus.Failed -> status.message to KoshTheme.colors.alert
    }
    KoshCard { Text(text, style = MaterialTheme.typography.bodyMedium, color = color) }
}

@Composable
private fun QrBox(code: PairingCode) {
    val bitmap = remember(code) { qrBitmap(code.encode(), 640) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Pairing code",
            modifier = Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("Scan with Kosh on the other phone", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        Text("Valid for 5 minutes · don't share a screenshot of it", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
    }
}

/** Home's "Household this month" card; shows nothing until a phone is paired. */
@Composable
fun HouseholdHomeCard(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    if (!householdAvailable) return
    val c = LocalContainer.current
    val state by c.householdStore.state.collectAsState()
    if (state.peer == null) return
    val flow = remember { c.household.observeData() }
    val data by flow.collectAsState(initial = HouseholdData(emptyList(), emptyList(), emptyList()))
    val settings = LocalSettings.current
    val period = remember(settings.monthStartDay) { Period.containing(Time.today(), settings.monthStartDay) }
    Column(modifier) {
        SectionHeader("Household", actionLabel = "Open", onAction = onOpen)
        SummaryCard(data, com.amitjha.kosh.ui.common.LocalHideAmounts.current, period, onOpen)
    }
}

@Composable
private fun SummaryCard(data: HouseholdData, hidden: Boolean, period: Period, onClick: (() -> Unit)? = null) {
    val inMonth = data.txns.filter { it.type == TxnType.EXPENSE && it.timestamp in period.startMillis..period.endMillis }
    val mine = inMonth.filter { it.mine }.sumOf { it.amount }
    val theirs = inMonth.filterNot { it.mine }.sumOf { it.amount }
    KoshCard(onClick = onClick) {
        Text("Household this month", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        AmountText(mine + theirs, style = AmountStyles.large, hidden = hidden)
        Spacer(Modifier.height(10.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text("You", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                AmountText(mine, hidden = hidden)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Partner", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                AmountText(theirs, hidden = hidden)
            }
        }
    }
}

private fun monthSpend(data: HouseholdData, categoryKey: String?, period: Period): Long {
    return data.txns.filter {
        it.type == TxnType.EXPENSE && it.timestamp in period.startMillis..period.endMillis &&
            (categoryKey == null || it.categoryKey == categoryKey)
    }.sumOf { it.amount }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF111111.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
