package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.BrandPalette

/** Background shape of [KoshLogo]. */
enum class LogoShape { Circle, RoundedSquare, None }

/**
 * The Kosh mark: a white "K" whose upper arm is a gold rising arrow, on the brand gradient.
 * Drawn on a 108-unit grid matching the launcher icon, so it stays crisp at any size.
 */
@Composable
fun KoshLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    shape: LogoShape = LogoShape.RoundedSquare,
    markColor: Color = Color.White,
    accentColor: Color = BrandPalette.Gold,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Kosh logo" },
    ) {
        val u = this.size.minDimension / 108f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        val bg = Brush.linearGradient(
            listOf(BrandPalette.Indigo800, BrandPalette.Indigo600, BrandPalette.Teal500),
            start = Offset.Zero,
            end = Offset(this.size.width, this.size.height),
        )
        when (shape) {
            LogoShape.Circle -> drawCircle(bg, radius = this.size.minDimension / 2f)
            LogoShape.RoundedSquare -> drawRoundRect(
                brush = bg,
                size = this.size,
                cornerRadius = CornerRadius(26f * u, 26f * u),
            )
            LogoShape.None -> Unit
        }
        // The launcher art sits inside a 108 grid with a safe zone; scale it up slightly when
        // drawn standalone so the mark fills the shape like the launcher icon does.
        val scale = if (shape == LogoShape.None) 1.35f else 1.18f
        val cx = 54f
        fun s(x: Float, y: Float) = p(cx + (x - cx) * scale, cx + (y - cx) * scale)
        val w = 8f * u * scale

        // stem
        drawRoundRect(
            color = markColor,
            topLeft = s(37f, 29f),
            size = Size(8f * u * scale, 50f * u * scale),
            cornerRadius = CornerRadius(4f * u * scale, 4f * u * scale),
        )
        // lower leg
        drawLine(markColor, s(50f, 55f), s(69f, 75f), strokeWidth = w, cap = StrokeCap.Round)
        // upper arm: rising arrow
        drawLine(accentColor, s(45f, 58f), s(66f, 37f), strokeWidth = w, cap = StrokeCap.Round)
        val head = Path().apply {
            val a = s(57f, 30f); val b = s(74f, 29f); val c = s(73f, 46f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
        }
        drawPath(head, accentColor)
    }
}
