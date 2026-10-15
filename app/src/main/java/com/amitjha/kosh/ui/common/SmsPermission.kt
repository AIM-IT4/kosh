package com.amitjha.kosh.ui.common

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.amitjha.kosh.ui.settings.hasSmsPermission

/** Shared recovery flow for onboarding and manual scans. Never assumes a dialog result is current. */
@Composable
fun rememberSmsPermissionRequest(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val grantedCallback by rememberUpdatedState(onGranted)
    var showHelp by remember { mutableStateOf(false) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.hasSmsPermission()) grantedCallback()
        else showHelp = true
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.hasSmsPermission()) grantedCallback()
        else showHelp = true
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Allow SMS access") },
            text = {
                Text("Kosh needs SMS permission to read bank messages on this phone. " +
                    "Open app settings, then Permissions › SMS › Allow. " +
                    "If SMS is unavailable or disabled, Android or your app installer may be restricting access. " +
                    "You can still use Gmail or add transactions manually.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showHelp = false
                    settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }) { Text("Open app settings") }
            },
            dismissButton = { TextButton(onClick = { showHelp = false }) { Text("Not now") } },
        )
    }
    return {
        if (context.hasSmsPermission()) grantedCallback()
        else permissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
    }
}
