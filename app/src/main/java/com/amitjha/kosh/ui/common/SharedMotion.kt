package com.amitjha.kosh.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** The app's shared-element scope (set around the nav host); null in previews and screenshot tests. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The nav destination's enter/exit scope, for shared elements on that screen. */
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Makes [scope] (a nav destination's) available to the shared elements of [content]. */
@Composable
fun ProvideNavScope(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalNavScope provides scope, content = content)

/**
 * A transaction's category icon, shared between the list it was tapped in and its detail screen, so it flies across
 * as the screen opens. Does nothing outside the app's navigation.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedTxnIcon(id: Long): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) { this@sharedTxnIcon.sharedElement(rememberSharedContentState("txn-icon-$id"), nav) }
}
