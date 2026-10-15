package com.amitjha.kosh

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.amitjha.kosh.notify.Notifications
import com.amitjha.kosh.ui.KoshRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Single activity host. It is a FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {

    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch opens the notification's screen; recreation after rotation must not open it again.
        if (savedInstanceState == null) pendingRoute.value = takeRoute(intent)
        val container = (application as KoshApp).container

        // Block screenshots and the recent-apps preview while the user wants their finances hidden.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settings.settings.map { it.blockScreenshots }.distinctUntilChanged().collect(::applySecureWindow)
            }
        }

        setContent {
            // The decoy PIN swaps in a sandboxed profile; keying on the container rebuilds every screen and
            // ViewModel, so nothing from the real profile survives the switch (or the switch back).
            val decoy by com.amitjha.kosh.security.DecoySession.active.collectAsState()
            val active = if (decoy) com.amitjha.kosh.security.DecoySession.container ?: container else container
            key(active) {
                KoshRoot(
                    container = active,
                    initialRoute = pendingRoute.value,
                    onRouteConsumed = { pendingRoute.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeRoute(intent)?.let { pendingRoute.value = it }
    }

    /** Reads the notification route and removes it, so it is handled exactly once. */
    private fun takeRoute(intent: Intent?): String? {
        val route = intent?.getStringExtra(Notifications.EXTRA_ROUTE)
        intent?.removeExtra(Notifications.EXTRA_ROUTE)
        return route
    }

    private fun applySecureWindow(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!secure)
    }
}
