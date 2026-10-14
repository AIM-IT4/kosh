package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Background shape of [KoshLogo]. */
enum class LogoShape { Circle, RoundedSquare, None }

private val LogoGradientStart = Color(0xFF34D399)
private val LogoGradientEnd = Color(0xFF047857)

/**
 * The Kosh mark ("Growth K"): a K whose arm is a rising chart line ending in an arrow,
 * white on an emerald gradient tile.
 *
 * Drawn on the same 108-unit grid as the launcher icon. With [LogoShape.None] the mark is
 * drawn alone in [markColor] (emerald by default) and fills more of the box.
 */
@Composable
fun KoshLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    shape: LogoShape = LogoShape.RoundedSquare,
    markColor: Color = if (shape == LogoShape.None) LogoGradientEnd else Color.White,
    @Suppress("UNUSED_PARAMETER") accentColor: Color = markColor,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Kosh logo" },
    ) {
        val u = this.size.minDimension / 108f
        val gradient = Brush.linearGradient(
            listOf(LogoGradientStart, LogoGradientEnd),
            start = Offset.Zero,
            end = Offset(this.size.width, this.size.height),
        )
        when (shape) {
            LogoShape.Circle -> drawCircle(gradient, radius = this.size.minDimension / 2f)
            LogoShape.RoundedSquare -> drawRoundRect(gradient, size = this.size, cornerRadius = CornerRadius(26f * u, 26f * u))
            LogoShape.None -> Unit
        }
        val scale = if (shape == LogoShape.None) 1.25f else 1.1f
        val dx = -3.75f
        fun p(x: Float, y: Float) = Offset((54f + (x + dx - 54f) * scale) * u, (54f + (y - 54f) * scale) * u)
        val w = 9f * u * scale
        drawLine(markColor, p(40f, 30f), p(40f, 78f), strokeWidth = w, cap = StrokeCap.Round)
        val chart = Path().apply {
            p(40f, 62f).let { moveTo(it.x, it.y) }
            p(56f, 48f).let { lineTo(it.x, it.y) }
            p(64f, 54f).let { lineTo(it.x, it.y) }
            p(76f, 38f).let { lineTo(it.x, it.y) }
        }
        drawPath(chart, markColor, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val arrow = Path().apply {
            p(68f, 34f).let { moveTo(it.x, it.y) }
            p(80f, 33f).let { lineTo(it.x, it.y) }
            p(79f, 45f).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(arrow, markColor)
    }
}
