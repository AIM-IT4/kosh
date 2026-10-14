package com.amitjha.kosh.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * The Kosh palette: warm off-white paper, ink text, a single emerald accent.
 * There are deliberately no gradients; the old gradient names resolve to flat colours.
 */
object BrandPalette {
    // Neutrals (light)
    val Paper = Color(0xFFF7F7F5)
    val Card = Color(0xFFFFFFFF)
    val Hairline = Color(0xFFEAEAE7)
    val Divider = Color(0xFFEFEFEC)
    val Ink = Color(0xFF111111)
    val InkSecondary = Color(0xFF6B6B6B)
    val InkTertiary = Color(0xFFA3A3A3)
    val Fill = Color(0xFFF1F1EE)

    // Neutrals (dark)
    val Night = Color(0xFF0B0B0C)
    val NightCard = Color(0xFF161618)
    val NightHairline = Color(0xFF242427)
    val NightFill = Color(0xFF1E1E21)
    val Snow = Color(0xFFF2F2F0)
    val SnowSecondary = Color(0xFF9B9B9B)
    val SnowTertiary = Color(0xFF6A6A6A)

    // Accent
    val Emerald = Color(0xFF059669)
    val EmeraldDeep = Color(0xFF047857)
    val EmeraldBright = Color(0xFF10B981)
    val EmeraldTint = Color(0xFFECFDF5)
    val EmeraldTintDark = Color(0xFF0D2A21)

    // Status
    val Red = Color(0xFFDC2626)
    val RedTint = Color(0xFFFEF2F2)
    val Amber = Color(0xFFB45309)
    val AmberTint = Color(0xFFFFFBEB)
    val Blue = Color(0xFF2563EB)
    val BlueTint = Color(0xFFEFF6FF)

    /** Kept for compatibility; Kosh no longer uses gradients, so this is flat ink. */
    val brandGradient: Brush get() = SolidColor(Ink)

    /** Kept for compatibility; flat emerald. */
    val goldGradient: Brush get() = SolidColor(Emerald)
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
    /** Primary text / ink buttons. */
    val ink: Color,
    /** Text on ink buttons. */
    val onInk: Color,
    val tertiaryText: Color,
    /** 1dp card outline. */
    val hairline: Color,
    val accent: Color,
    val accentTint: Color,
    val alertTint: Color,
    val warningTint: Color,
    val tipTint: Color,
    /** Neutral fill for icon tiles, chips and inputs. */
    val fill: Color,
) {
    /** Kept for compatibility; a flat surface instead of a gradient. */
    val heroGradient: Brush get() = SolidColor(cardSurface)
}

internal val LightKoshColors = KoshColors(
    income = BrandPalette.Emerald,
    expense = BrandPalette.Ink,
    transfer = BrandPalette.InkSecondary,
    warning = BrandPalette.Amber,
    alert = BrandPalette.Red,
    good = BrandPalette.Emerald,
    tip = BrandPalette.Blue,
    gold = BrandPalette.Emerald,
    cardSurface = BrandPalette.Card,
    cardSurfaceHigh = BrandPalette.Fill,
    glass = BrandPalette.Card,
    glassBorder = BrandPalette.Hairline,
    subtleText = BrandPalette.InkSecondary,
    divider = BrandPalette.Divider,
    shimmerBase = Color(0xFFEDEDEA),
    shimmerHighlight = Color(0xFFF7F7F5),
    isDark = false,
    ink = BrandPalette.Ink,
    onInk = Color.White,
    tertiaryText = BrandPalette.InkTertiary,
    hairline = BrandPalette.Hairline,
    accent = BrandPalette.Emerald,
    accentTint = BrandPalette.EmeraldTint,
    alertTint = BrandPalette.RedTint,
    warningTint = BrandPalette.AmberTint,
    tipTint = BrandPalette.BlueTint,
    fill = BrandPalette.Fill,
)

internal val DarkKoshColors = KoshColors(
    income = BrandPalette.EmeraldBright,
    expense = BrandPalette.Snow,
    transfer = BrandPalette.SnowSecondary,
    warning = Color(0xFFF59E0B),
    alert = Color(0xFFF87171),
    good = BrandPalette.EmeraldBright,
    tip = Color(0xFF60A5FA),
    gold = BrandPalette.EmeraldBright,
    cardSurface = BrandPalette.NightCard,
    cardSurfaceHigh = BrandPalette.NightFill,
    glass = BrandPalette.NightCard,
    glassBorder = BrandPalette.NightHairline,
    subtleText = BrandPalette.SnowSecondary,
    divider = BrandPalette.NightHairline,
    shimmerBase = BrandPalette.NightCard,
    shimmerHighlight = BrandPalette.NightFill,
    isDark = true,
    ink = BrandPalette.Snow,
    onInk = BrandPalette.Night,
    tertiaryText = BrandPalette.SnowTertiary,
    hairline = BrandPalette.NightHairline,
    accent = BrandPalette.EmeraldBright,
    accentTint = BrandPalette.EmeraldTintDark,
    alertTint = Color(0xFF2A1414),
    warningTint = Color(0xFF2A2010),
    tipTint = Color(0xFF14202F),
    fill = BrandPalette.NightFill,
)

val LocalKoshColors = staticCompositionLocalOf { LightKoshColors }
