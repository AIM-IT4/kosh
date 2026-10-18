package com.amitjha.kosh.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.BuildConfig
import com.amitjha.kosh.designsystem.theme.KoshTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offers a newer release once the app is open and unlocked; checks GitHub at most once a day. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val updates = remember { Updates(context) }
    var offer by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        if (!updates.enabled) return@LaunchedEffect
        withContext(Dispatchers.IO) { updates.check() }
        offer = updates.toOffer()
    }
    offer?.let { r ->
        UpdateDialog(
            r,
            onUpdate = {
                openDownload(context, r)
                offer = null
            },
            onLater = {
                updates.later()
                offer = null
            },
            onSkip = {
                updates.skip(r.version)
                offer = null
            },
        )
    }
}

@Composable
fun UpdateDialog(r: Release, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Kosh ${r.version} is available") },
        text = {
            Column {
                Text("You have ${BuildConfig.VERSION_NAME}.", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                if (r.notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("What's new", style = MaterialTheme.typography.labelLarge)
                    r.notes.take(MAX_NOTES).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "It installs over this version and keeps all your data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                if (onSkip != null) {
                    TextButton(onClick = onSkip, modifier = Modifier.padding(top = 4.dp)) { Text("Skip this version") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Update") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

/** Downloads the APK in the browser (Android then offers to install it), or opens the release page. */
fun openDownload(context: Context, r: Release) {
    val url = r.apkUrl ?: r.pageUrl
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing to open.
    }
}

private const val MAX_NOTES = 6
