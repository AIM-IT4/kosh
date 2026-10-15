package com.amitjha.kosh.ui.nav

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * Pops the current screen only when it is the resumed, visible screen and something is underneath.
 *
 * A double tap on a back arrow or a Save button otherwise pops twice: the second pop can remove the screen
 * underneath, or empty the back stack and leave a blank window with no bottom bar.
 */
fun NavController.popBackSafely(): Boolean {
    val entry = currentBackStackEntry ?: return false
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return false
    if (previousBackStackEntry == null) return false
    return popBackStack()
}

/**
 * Leaves an editor and the detail screen it was opened from (e.g. after deleting the item), falling back to
 * a plain [popBackSafely] when the detail screen isn't on the stack.
 */
fun NavController.popPast(detailRoute: String): Boolean {
    val entry = currentBackStackEntry ?: return false
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return false
    val hasDetail = runCatching { getBackStackEntry(detailRoute) }.isSuccess
    return if (hasDetail) popBackStack(detailRoute, inclusive = true) else popBackSafely()
}

/**
 * Opens a bottom-bar tab the same way the bar does, so every tab keeps one back stack: tapping Home always
 * returns to Home instead of restoring another tab that was pushed onto Home's stack.
 */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
