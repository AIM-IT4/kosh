package com.amitjha.kosh.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Translates fixed UI text (titles, labels, buttons) into the app's chosen language. The app provides it; the
 * default leaves text unchanged. Text with numbers or names in it passes through as it is.
 */
val LocalTranslator = compositionLocalOf<(String) -> String> { { it } }

@Composable
fun tr(text: String): String = LocalTranslator.current(text)
