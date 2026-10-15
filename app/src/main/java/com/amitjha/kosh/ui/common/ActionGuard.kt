package com.amitjha.kosh.ui.common

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ignores repeated taps on a Save/Add/Delete button: a second call is dropped while the first is still running
 * and for [cooldownMillis] after it finishes (long enough for the screen or sheet to close). Without this a
 * double tap inserts the same transaction, account or goal twice.
 */
class ActionGuard(private val cooldownMillis: Long = 800L, private val clock: () -> Long = { SystemClock.uptimeMillis() }) {
    private val running = AtomicBoolean(false)
    @Volatile private var finishedAt = Long.MIN_VALUE / 2

    fun tryStart(): Boolean {
        if (clock() - finishedAt < cooldownMillis) return false
        return running.compareAndSet(false, true)
    }

    fun finish() {
        finishedAt = clock()
        running.set(false)
    }
}

/** Launches [block] unless [guard] is busy; returns null when the call was dropped as a repeat. */
fun ViewModel.launchGuarded(guard: ActionGuard, block: suspend () -> Unit): Job? {
    if (!guard.tryStart()) return null
    return viewModelScope.launch {
        try {
            block()
        } finally {
            guard.finish()
        }
    }
}
