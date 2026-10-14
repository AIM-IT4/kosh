package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.BrandPalette
import com.amitjha.kosh.designsystem.theme.KoshTheme

/** Background shape of [KoshLogo]. */
enum class LogoShape { Circle, RoundedSquare, None }

/**
 * The Kosh mark: a lowercase "k" monogram in ink with an emerald dot.
 *
 * Drawn on the same 108-unit grid as the launcher icon, so it stays crisp at any size.
 * With [LogoShape.None] the mark fills the box; otherwise it sits on a white tile
 * with a hairline outline.
 */
@Composable
fun KoshLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    shape: LogoShape = LogoShape.RoundedSquare,
    markColor: Color = KoshTheme.colors.ink,
    accentColor: Color = BrandPalette.EmeraldBright,
) {
    val tile = KoshTheme.colors.cardSurface
    val outline = KoshTheme.colors.hairline
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Kosh logo" },
    ) {
        val u = this.size.minDimension / 108f
        when (shape) {
            LogoShape.Circle -> {
                drawCircle(tile, radius = this.size.minDimension / 2f)
                drawCircle(outline, radius = this.size.minDimension / 2f - u / 2f, style = Stroke(width = u))
            }
            LogoShape.RoundedSquare -> {
                drawRoundRect(tile, size = this.size, cornerRadius = CornerRadius(26f * u, 26f * u))
                drawRoundRect(
                    outline,
                    topLeft = Offset(u / 2f, u / 2f),
                    size = androidx.compose.ui.geometry.Size(this.size.width - u, this.size.height - u),
                    cornerRadius = CornerRadius(26f * u, 26f * u),
                    style = Stroke(width = u),
                )
            }
            LogoShape.None -> Unit
        }
        // On a tile the mark keeps launcher proportions; standalone it fills more of the box.
        val scale = if (shape == LogoShape.None) 1.6f else 1.0f
        val dx = -1.75f
        fun p(x: Float, y: Float) = Offset((54f + (x + dx - 54f) * scale) * u, (54f + (y - 54f) * scale) * u)
        val w = 8f * u * scale
        drawLine(markColor, p(40f, 30f), p(40f, 78f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(markColor, p(40f, 60f), p(64f, 40f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(markColor, p(50f, 52f), p(66f, 78f), strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(accentColor, radius = 5.5f * u * scale, center = p(70f, 33f))
    }
}
