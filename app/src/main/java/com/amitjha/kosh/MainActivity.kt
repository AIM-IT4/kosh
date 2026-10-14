package com.amitjha.kosh

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.amitjha.kosh.notify.Notifications
import com.amitjha.kosh.ui.KoshRoot

/** Single activity host. It is a FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {

    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute.value = intent?.getStringExtra(Notifications.EXTRA_ROUTE)
        val container = (application as KoshApp).container
        setContent {
            KoshRoot(
                container = container,
                initialRoute = pendingRoute.value,
                onRouteConsumed = { pendingRoute.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }
}
