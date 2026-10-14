package com.amitjha.kosh.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = BrandPalette.Indigo400,
    onPrimary = Color.White,
    primaryContainer = BrandPalette.Indigo800,
    onPrimaryContainer = BrandPalette.Indigo50,
    secondary = BrandPalette.Teal300,
    onSecondary = BrandPalette.Ink,
    secondaryContainer = Color(0xFF0D4D57),
    onSecondaryContainer = Color(0xFFC8F4FA),
    tertiary = BrandPalette.Gold,
    onTertiary = BrandPalette.Ink,
    tertiaryContainer = Color(0xFF5C4614),
    onTertiaryContainer = Color(0xFFFFEBBE),
    error = BrandPalette.Red,
    onError = Color.White,
    errorContainer = Color(0xFF5C1220),
    onErrorContainer = Color(0xFFFFD9DC),
    background = BrandPalette.Ink,
    onBackground = Color(0xFFECEDF6),
    surface = BrandPalette.Ink,
    onSurface = Color(0xFFECEDF6),
    surfaceVariant = BrandPalette.InkCardHigh,
    onSurfaceVariant = BrandPalette.SlateLight,
    surfaceContainerLowest = Color(0xFF0A0B14),
    surfaceContainerLow = BrandPalette.InkRaised,
    surfaceContainer = BrandPalette.InkCard,
    surfaceContainerHigh = BrandPalette.InkCardHigh,
    surfaceContainerHighest = Color(0xFF2C304A),
    outline = BrandPalette.InkOutline,
    outlineVariant = Color(0xFF262A42),
    inverseSurface = Color(0xFFECEDF6),
    inverseOnSurface = BrandPalette.Ink,
    inversePrimary = BrandPalette.Indigo600,
    scrim = Color.Black,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = BrandPalette.Indigo600,
    onPrimary = Color.White,
    primaryContainer = BrandPalette.Indigo50,
    onPrimaryContainer = BrandPalette.Indigo900,
    secondary = Color(0xFF0B8FA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F5FA),
    onSecondaryContainer = Color(0xFF033B43),
    tertiary = BrandPalette.GoldDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF1CC),
    onTertiaryContainer = Color(0xFF3F2E00),
    error = BrandPalette.RedDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFFDADC),
    onErrorContainer = Color(0xFF410009),
    background = BrandPalette.Mist,
    onBackground = Color(0xFF14162A),
    surface = BrandPalette.Mist,
    onSurface = Color(0xFF14162A),
    surfaceVariant = Color(0xFFE6E7F2),
    onSurfaceVariant = BrandPalette.Slate,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFE),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F1F8),
    surfaceContainerHighest = Color(0xFFE6E7F2),
    outline = Color(0xFFC6C8DA),
    outlineVariant = Color(0xFFE2E3EE),
    inverseSurface = Color(0xFF23253A),
    inverseOnSurface = Color(0xFFF2F2FA),
    inversePrimary = BrandPalette.Indigo200,
    scrim = Color.Black,
)

internal val KoshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing scale used across the app (4dp grid). */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    /** Horizontal padding for screen content. */
    val screen: Dp = 20.dp,
    /** Corner radius of standard cards. */
    val cardRadius: Dp = 24.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/**
 * The Kosh theme. Wrap all app content in it:
 *
 * ```
 * KoshTheme { AppNavHost() }
 * ```
 *
 * Material slots come from [MaterialTheme]; extra semantic colours from [KoshTheme.colors]
 * and spacing from [KoshTheme.spacing].
 *
 * @param darkTheme dark-first by default only if the system is dark; pass `true` to force it.
 * @param dynamicColor use Material You wallpaper colours on Android 12+ (brand colours otherwise).
 */
@Composable
fun KoshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val kosh = if (darkTheme) DarkKoshColors else LightKoshColors
    CompositionLocalProvider(
        LocalKoshColors provides kosh,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KoshTypography,
            shapes = KoshShapes,
            content = content,
        )
    }
}

/** Accessors for the Kosh-specific theme values. */
object KoshTheme {
    val colors: KoshColors
        @Composable @ReadOnlyComposable get() = LocalKoshColors.current

    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
}
