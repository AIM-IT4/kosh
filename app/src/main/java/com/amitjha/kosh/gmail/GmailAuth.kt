package com.amitjha.kosh.gmail

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.amitjha.kosh.data.prefs.SettingsStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcome of asking Google for Gmail read access. */
sealed interface GmailAuthOutcome {
    /** Access granted; [email] may be null when Google doesn't return the account. */
    data class Granted(val accessToken: String, val email: String?) : GmailAuthOutcome

    /** The user must pick an account / consent: launch [pendingIntent] with StartIntentSenderForResult. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : GmailAuthOutcome

    data class Failed(val message: String) : GmailAuthOutcome
}

/**
 * Gmail read-only access through Google Identity Services' AuthorizationClient.
 *
 * Setup (one time, in Google Cloud Console): enable the Gmail API, configure the OAuth consent screen
 * (External, Testing, add your Gmail as a test user), and create an OAuth client of type Android for
 * package `com.amitjha.kosh` with the release keystore's SHA-1.
 *
 * From the UI:
 * ```
 * val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
 *     scope.launch { gmailAuth.completeConsent(r.data) }
 * }
 * when (val o = gmailAuth.connect(activity)) {
 *     is GmailAuthOutcome.NeedsConsent -> launcher.launch(IntentSenderRequest.Builder(o.pendingIntent).build())
 *     is GmailAuthOutcome.Granted -> // connected
 *     is GmailAuthOutcome.Failed -> // show o.message
 * }
 * ```
 */
class GmailAuth(private val appContext: Context, private val settings: SettingsStore) {

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GMAIL_READONLY))).build()

    /** Starts the connect flow from an Activity. */
    suspend fun connect(activity: Activity): GmailAuthOutcome = authorize(activity)

    /** Finishes the connect flow with the result Intent from the consent screen. */
    suspend fun completeConsent(data: Intent?): GmailAuthOutcome = try {
        val result = Identity.getAuthorizationClient(appContext).getAuthorizationResultFromIntent(data)
        handle(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GmailAuthOutcome.Failed(e.message ?: "Google sign-in was cancelled")
    }

    /**
     * A token for background sync without UI. Returns null (and flags "reconnect needed") if the user must
     * consent again.
     */
    suspend fun accessTokenSilently(): String? = when (val o = authorize(appContext)) {
        is GmailAuthOutcome.Granted -> o.accessToken
        is GmailAuthOutcome.NeedsConsent -> {
            settings.setGmailReconnectNeeded(true)
            null
        }
        is GmailAuthOutcome.Failed -> throw IllegalStateException(o.message)
    }

    /** Forgets the cached token so the next request fetches a fresh one (after a 401). */
    suspend fun invalidateToken(token: String) = withContext(Dispatchers.IO) {
        com.google.android.gms.auth.GoogleAuthUtil.clearToken(appContext, token)
    }

    suspend fun disconnect() = settings.setGmailAccount(null)

    private suspend fun authorize(context: Context): GmailAuthOutcome = try {
        withTimeoutOrNull(20_000L) {
            handle(Identity.getAuthorizationClient(context).authorize(request).await())
        } ?: GmailAuthOutcome.Failed("Google sign-in timed out. Check your connection and try again.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GmailAuthOutcome.Failed(e.message ?: "Couldn't reach Google Play services")
    }

    private suspend fun handle(result: AuthorizationResult): GmailAuthOutcome {
        if (result.hasResolution()) {
            val pi = result.pendingIntent ?: return GmailAuthOutcome.Failed("Consent screen unavailable")
            return GmailAuthOutcome.NeedsConsent(pi)
        }
        val token = result.accessToken ?: return GmailAuthOutcome.Failed("Google returned no access token")
        val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
        settings.setGmailAccount(email ?: settings.current().gmailAccountEmail ?: "Gmail")
        return GmailAuthOutcome.Granted(token, email)
    }

    companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

