package com.amitjha.kosh.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Raw brand palette, taken from the app logo. */
object BrandPalette {
    val Indigo900 = Color(0xFF1A1266)
    val Indigo800 = Color(0xFF2B1F9E)
    val Indigo600 = Color(0xFF4C3BE0)
    val Indigo400 = Color(0xFF7B6CF0)
    val Indigo200 = Color(0xFFC4BCFF)
    val Indigo50 = Color(0xFFF0EEFF)
    val Teal500 = Color(0xFF10B5C9)
    val Teal300 = Color(0xFF5DD8E6)
    val Gold = Color(0xFFFFD166)
    val GoldDeep = Color(0xFFE0A93A)

    val Ink = Color(0xFF0E0F1A)
    val InkRaised = Color(0xFF161828)
    val InkCard = Color(0xFF1C1F33)
    val InkCardHigh = Color(0xFF24283F)
    val InkOutline = Color(0xFF34385A)
    val Mist = Color(0xFFF6F7FB)
    val Paper = Color(0xFFFFFFFF)
    val Slate = Color(0xFF5B6178)
    val SlateLight = Color(0xFF9AA0B8)

    val Green = Color(0xFF2ECC8F)
    val GreenDeep = Color(0xFF1F9E6C)
    val Coral = Color(0xFFFF6B6B)
    val CoralDeep = Color(0xFFE04848)
    val Amber = Color(0xFFFFB547)
    val AmberDeep = Color(0xFFCC8A1F)
    val Red = Color(0xFFFF4D5E)
    val RedDeep = Color(0xFFD62839)
    val Grey = Color(0xFF8A90A8)

    /** The logo gradient: indigo to teal. */
    val brandGradient: Brush
        get() = Brush.linearGradient(listOf(Indigo800, Indigo600, Teal500))

    val goldGradient: Brush
        get() = Brush.linearGradient(listOf(Gold, GoldDeep))
}

/**
 * Semantic colours that Material3's [androidx.compose.material3.ColorScheme] has no slot for.
 * Read them through [KoshTheme.colors].
 */
@Immutable
data class KoshColors(
    val income: Color,
    val expense: Color,
    val transfer: Color,
    val warning: Color,
    val alert: Color,
    val good: Color,
    val tip: Color,
    val gold: Color,
    val cardSurface: Color,
    val cardSurfaceHigh: Color,
    val glass: Color,
    val glassBorder: Color,
    val subtleText: Color,
    val divider: Color,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    val isDark: Boolean,
) {
    val heroGradient: Brush get() = BrandPalette.brandGradient
}

internal val DarkKoshColors = KoshColors(
    income = BrandPalette.Green,
    expense = BrandPalette.Coral,
    transfer = BrandPalette.Grey,
    warning = BrandPalette.Amber,
    alert = BrandPalette.Red,
    good = BrandPalette.Teal300,
    tip = BrandPalette.Indigo400,
    gold = BrandPalette.Gold,
    cardSurface = BrandPalette.InkCard,
    cardSurfaceHigh = BrandPalette.InkCardHigh,
    glass = Color.White.copy(alpha = 0.08f),
    glassBorder = Color.White.copy(alpha = 0.14f),
    subtleText = BrandPalette.SlateLight,
    divider = Color.White.copy(alpha = 0.08f),
    shimmerBase = BrandPalette.InkCard,
    shimmerHighlight = BrandPalette.InkCardHigh,
    isDark = true,
)

internal val LightKoshColors = KoshColors(
    income = BrandPalette.GreenDeep,
    expense = BrandPalette.CoralDeep,
    transfer = BrandPalette.Slate,
    warning = BrandPalette.AmberDeep,
    alert = BrandPalette.RedDeep,
    good = Color(0xFF0B8FA0),
    tip = BrandPalette.Indigo600,
    gold = BrandPalette.GoldDeep,
    cardSurface = BrandPalette.Paper,
    cardSurfaceHigh = BrandPalette.Indigo50,
    glass = Color.White.copy(alpha = 0.65f),
    glassBorder = BrandPalette.Indigo200.copy(alpha = 0.5f),
    subtleText = BrandPalette.Slate,
    divider = Color(0x14000000),
    shimmerBase = Color(0xFFE9EAF2),
    shimmerHighlight = Color(0xFFF6F7FB),
    isDark = false,
)

val LocalKoshColors = staticCompositionLocalOf { DarkKoshColors }
