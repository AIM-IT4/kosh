package com.amitjha.kosh.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SecondaryButton
import com.amitjha.kosh.designsystem.theme.KoshTheme

/**
 * Shown before Google's consent screen. Kosh's OAuth app is in Testing mode, so Google
 * always warns that it is unverified; this tells the user what to expect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmailExplainerSheet(onContinue: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KoshTheme.colors.cardSurface,
    ) {
        GmailExplainerContent(onContinue, onDismiss)
    }
}

@Composable
fun GmailExplainerContent(onContinue: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        Text("Before you continue", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "Google will show “Google hasn't verified this app” because Kosh is your personal app. " +
                "Tap Continue (or Advanced → Go to Kosh) to allow read-only access to card statement emails.",
            style = MaterialTheme.typography.bodyMedium,
            color = KoshTheme.colors.subtleText,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Kosh only searches for credit card statements and reads the amount and due date. Nothing is sent anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = KoshTheme.colors.tertiaryText,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Not now", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/** Turns Google sign-in errors into an actionable sentence. */
fun friendlyGmailError(message: String): String {
    val m = message.lowercase()
    return when {
        "access_denied" in m || "test user" in m || "not completed the google verification" in m ||
            "has not completed" in m || "developer" in m ->
            "Google blocked access. Add your Gmail as a Test user in Google Cloud → OAuth consent screen → Test users, then try again."
        "cancel" in m -> "Gmail connection cancelled."
        "10:" in m || "developer_error" in m ->
            "Google couldn't match this app. Check the Android OAuth client uses package com.amitjha.kosh and the Kosh signing SHA-1."
        else -> "Couldn't connect Gmail: $message"
    }
}
