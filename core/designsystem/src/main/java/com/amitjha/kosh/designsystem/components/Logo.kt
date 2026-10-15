package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Background shape of [KoshLogo]. */
enum class LogoShape { Circle, RoundedSquare, None }

private val LogoTile = Color(0xFF059669)
private val LogoInk = Color(0xFF047857)

/**
 * The Kosh mark ("Gullak"): the clay money pot Indian households keep their savings in, with its
 * coin slot, white on a flat emerald tile.
 *
 * Drawn on the same 108-unit grid as the launcher icon. The slot is cut out with the even-odd rule,
 * so the tile shows through it. With [LogoShape.None] the mark is drawn alone in [markColor]
 * (emerald by default) and fills more of the box.
 */
@Composable
fun KoshLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    shape: LogoShape = LogoShape.RoundedSquare,
    markColor: Color = if (shape == LogoShape.None) LogoInk else Color.White,
    @Suppress("UNUSED_PARAMETER") accentColor: Color = markColor,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Kosh logo" },
    ) {
        val u = this.size.minDimension / 108f
        when (shape) {
            LogoShape.Circle -> drawCircle(LogoTile, radius = this.size.minDimension / 2f)
            LogoShape.RoundedSquare -> drawRoundRect(LogoTile, size = this.size, cornerRadius = CornerRadius(26f * u, 26f * u))
            LogoShape.None -> Unit
        }
        val k = u * if (shape == LogoShape.None) 1.2f else 1.05f
        // Grid point to canvas, scaled about the middle of the pot (54, 54.4).
        fun x(v: Float) = center.x + (v - 54f) * k
        fun y(v: Float) = center.y + (v - 54.4f) * k
        val body = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(x(47f), y(37f))
            lineTo(x(61f), y(37f))
            cubicTo(x(61f), y(40f), x(63f), y(41.5f), x(66f), y(42.5f))
            cubicTo(x(74f), y(45.5f), x(79f), y(52f), x(79f), y(60.5f))
            cubicTo(x(79f), y(70f), x(72f), y(78f), x(63f), y(81f))
            lineTo(x(62f), y(84f))
            lineTo(x(46f), y(84f))
            lineTo(x(45f), y(81f))
            cubicTo(x(36f), y(78f), x(29f), y(70f), x(29f), y(60.5f))
            cubicTo(x(29f), y(52f), x(34f), y(45.5f), x(42f), y(42.5f))
            cubicTo(x(45f), y(41.5f), x(47f), y(40f), x(47f), y(37f))
            close()
            addRoundRect(RoundRect(x(44.5f), y(52f), x(63.5f), y(57f), CornerRadius(2.5f * k)))
        }
        drawPath(body, markColor)
        drawRoundRect(markColor, topLeft = Offset(x(43f), y(32f)), size = Size(22f * k, 6.5f * k), cornerRadius = CornerRadius(3.25f * k))
        drawCircle(markColor, radius = 3.2f * k, center = Offset(x(54f), y(28f)))
    }
}
