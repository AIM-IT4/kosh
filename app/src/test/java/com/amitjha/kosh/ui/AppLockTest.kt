package com.amitjha.kosh.ui

import com.amitjha.kosh.ui.common.ActionGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

    @Test
    fun relocksOnlyAfterTheTimeoutAway() {
        val t = 1_000_000L
        assertFalse(AppLock.shouldRelock(backgroundedAt = t, now = t + AppLock.RELOCK_AFTER_MILLIS - 1))
        assertTrue(AppLock.shouldRelock(backgroundedAt = t, now = t + AppLock.RELOCK_AFTER_MILLIS))
        assertTrue(AppLock.shouldRelock(backgroundedAt = t, now = t + 10 * AppLock.RELOCK_AFTER_MILLIS))
    }

    @Test
    fun neverBackgroundedDoesNotRelock() {
        assertFalse(AppLock.shouldRelock(backgroundedAt = 0L, now = 5 * AppLock.RELOCK_AFTER_MILLIS))
    }

    @Test
    fun foregroundAfterTimeoutLocksAndUnlockSticks() {
        AppLock.markUnlocked()
        AppLock.onBackground(now = 10_000L)
        AppLock.onForeground(lockEnabled = true, now = 20_000L)
        assertTrue("a short trip away keeps the app unlocked", AppLock.unlocked.value)

        AppLock.onBackground(now = 30_000L)
        AppLock.onForeground(lockEnabled = true, now = 30_000L + AppLock.RELOCK_AFTER_MILLIS)
        assertFalse(AppLock.unlocked.value)

        AppLock.markUnlocked()
        // Rotation or enabling the lock from Settings: a foreground without a background in between.
        AppLock.onForeground(lockEnabled = true, now = 10 * AppLock.RELOCK_AFTER_MILLIS)
        assertTrue(AppLock.unlocked.value)
    }

    @Test
    fun lockDisabledNeverRelocks() {
        AppLock.markUnlocked()
        AppLock.onBackground(now = 1_000L)
        AppLock.onForeground(lockEnabled = false, now = 1_000L + 5 * AppLock.RELOCK_AFTER_MILLIS)
        assertTrue(AppLock.unlocked.value)
    }

    @Test
    fun actionGuardDropsRepeatsUntilCooldownEnds() {
        var now = 0L
        val guard = ActionGuard(cooldownMillis = 800L, clock = { now })
        assertTrue(guard.tryStart())
        assertFalse("second tap while the first save runs", guard.tryStart())
        now = 100L
        guard.finish()
        now = 500L
        assertFalse("tap during the cooldown", guard.tryStart())
        now = 900L
        assertTrue(guard.tryStart())
    }
}
