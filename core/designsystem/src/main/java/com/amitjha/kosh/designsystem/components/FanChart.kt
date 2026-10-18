package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise

/** One point of a [FanChart]: the 10th, 50th and 90th percentile of a simulated value, and the target. */
@Immutable
data class FanBand(val label: String, val p10: Paise, val p50: Paise, val p90: Paise, val target: Paise)

/**
 * A Monte Carlo "fan": the shaded band holds 80% of outcomes (10th to 90th percentile), the line is the
 * median, and the dashed line is the target. Labels show the first and last points.
 */
@Composable
fun FanChart(
    bands: List<FanBand>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    hidden: Boolean = false,
) {
    if (bands.size < 2) return
    val progress by rememberEntrance(900)
    val accent = KoshTheme.colors.accent
    val ink = KoshTheme.colors.ink
    val subtle = KoshTheme.colors.subtleText
    val grid = KoshTheme.colors.divider
    val maxV = bands.maxOf { maxOf(it.p90, it.target) }.coerceAtLeast(1).toFloat()
    val last = bands.last()
    Column(modifier.semantics {
        contentDescription = "Projection: median ${Money.compact(last.p50)}, 80% of outcomes between " +
            "${Money.compact(last.p10)} and ${Money.compact(last.p90)}, target ${Money.compact(last.target)}"
    }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height
            fun x(i: Int) = i * w / (bands.size - 1)
            fun y(v: Paise) = h - (v / maxV) * h * 0.92f * progress
            drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
            val band = Path().apply {
                moveTo(x(0), y(bands[0].p90))
                bands.indices.forEach { lineTo(x(it), y(bands[it].p90)) }
                bands.indices.reversed().forEach { lineTo(x(it), y(bands[it].p10)) }
                close()
            }
            drawPath(band, accent.copy(alpha = 0.14f))
            val median = Path().apply {
                moveTo(x(0), y(bands[0].p50))
                bands.indices.drop(1).forEach { lineTo(x(it), y(bands[it].p50)) }
            }
            drawPath(median, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            val target = Path().apply {
                moveTo(x(0), y(bands[0].target))
                bands.indices.drop(1).forEach { lineTo(x(it), y(bands[it].target)) }
            }
            drawPath(target, ink.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(bands.first().label, style = MaterialTheme.typography.labelSmall, color = subtle)
            Spacer(Modifier.weight(1f))
            Text(last.label, style = MaterialTheme.typography.labelSmall, color = subtle)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(accent.copy(alpha = 0.3f), "80% of outcomes")
            Spacer(Modifier.width(14.dp))
            Legend(accent, "Median ${if (hidden) "₹ ••••" else Money.compact(last.p50)}")
            Spacer(Modifier.width(14.dp))
            Legend(ink.copy(alpha = 0.55f), "Target ${if (hidden) "₹ ••••" else Money.compact(last.target)}")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
    }
}
