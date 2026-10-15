package com.amitjha.kosh.ui.tools

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.security.PinHasher
import com.amitjha.kosh.security.PinMatch
import com.amitjha.kosh.tools.ToolsPrefs
import com.amitjha.kosh.tools.ToolsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PIN entry on the lock screen. The real PIN opens the real profile and the decoy PIN the decoy profile;
 * both look and behave the same, and a wrong PIN is rate-limited.
 */
@Composable
fun PinLockPad(prefs: ToolsPrefs, onMatch: (PinMatch) -> Unit, onBiometric: (() -> Unit)?) {
    val context = LocalContext.current
    val store = remember { ToolsSettings.get(context) }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Lockout is timed on the monotonic clock, which the user can't change.
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(prefs.pinFailures) {
        val wait = PinHasher.lockoutMillis(prefs.pinFailures)
        if (wait > 0 && lockedUntil == 0L) lockedUntil = SystemClock.elapsedRealtime() + wait
    }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > 0 && SystemClock.elapsedRealtime() < lockedUntil) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        now = SystemClock.elapsedRealtime()
    }
    val waiting = lockedUntil > now

    fun submit() {
        if (checking || waiting || pin.length < PinHasher.MIN_LENGTH) return
        checking = true
        val entered = pin
        scope.launch {
            val result = withContext(Dispatchers.Default) { PinHasher.match(entered, prefs.pinHash, prefs.decoyPinHash) }
            if (result == PinMatch.NONE) {
                val failures = store.recordPinFailure()
                val wait = PinHasher.lockoutMillis(failures)
                if (wait > 0) lockedUntil = SystemClock.elapsedRealtime() + wait
                error = "Wrong PIN"
                pin = ""
            } else {
                store.resetPinFailures()
                pin = ""
                onMatch(result)
            }
            checking = false
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { contentDescription = "${pin.length} digits entered" }) {
            repeat(maxOf(pin.length, PinHasher.MIN_LENGTH)) { i ->
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (i < pin.length) KoshTheme.colors.ink else KoshTheme.colors.hairline),
                )
            }
        }
        val status = when {
            waiting -> "Too many attempts. Try again in ${((lockedUntil - now) / 1000) + 1}s"
            checking -> "Checking…"
            else -> error
        }
        Text(status ?: " ", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d -> PinKey(d, enabled = !waiting && !checking) { if (pin.length < PinHasher.MAX_LENGTH) { pin += d; error = null } } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBiometric != null) PinIconKey(Icons.Outlined.Fingerprint, "Use fingerprint or screen lock", enabled = !checking, onClick = onBiometric)
            else Spacer(Modifier.size(64.dp))
            PinKey("0", enabled = !waiting && !checking) { if (pin.length < PinHasher.MAX_LENGTH) { pin += "0"; error = null } }
            PinIconKey(Icons.AutoMirrored.Outlined.Backspace, "Delete", enabled = pin.isNotEmpty() && !checking) { pin = pin.dropLast(1) }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (pin.length >= PinHasher.MIN_LENGTH && !waiting) KoshTheme.colors.ink else KoshTheme.colors.hairline)
                .clickable(enabled = pin.length >= PinHasher.MIN_LENGTH && !waiting && !checking) { submit() }
                .padding(horizontal = 40.dp, vertical = 12.dp),
        ) {
            Text("Unlock", color = KoshTheme.colors.onInk, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PinKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(KoshTheme.colors.fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = KoshTheme.colors.ink)
    }
}

@Composable
private fun PinIconKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = KoshTheme.colors.ink, modifier = Modifier.size(26.dp))
    }
}
