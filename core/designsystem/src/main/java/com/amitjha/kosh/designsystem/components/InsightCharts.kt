package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise
import kotlin.math.max

/**
 * Cumulative spending this month against last month: emerald for this month (up to today), grey for
 * last month, a dashed projection to month end and a marker on today. Drag to inspect a day.
 *
 * @param current cumulative spend per day of this month, one value per elapsed day (day 1 first).
 * @param previous cumulative spend per day of last month (full month).
 * @param daysInMonth number of days in the current month (sets the x axis).
 * @param projected projected month-end spend, drawn as a dashed line from today.
 */
@Composable
fun SpendPaceChart(
    current: List<Paise>,
    previous: List<Paise>,
    daysInMonth: Int,
    modifier: Modifier = Modifier,
    projected: Paise? = null,
    height: Dp = 170.dp,
    hidden: Boolean = false,
) {
    val colors = KoshTheme.colors
    val accent = colors.accent
    val grey = colors.tertiaryText.copy(alpha = 0.6f)
    val grid = colors.divider
    val surface = MaterialTheme.colorScheme.surface
    val progress = rememberEntrance(current to previous, 1000)
    var selected by remember(current, previous) { mutableStateOf<Int?>(null) }
    val days = max(daysInMonth, max(current.size, previous.size)).coerceAtLeast(2)

    Column(modifier.semantics { contentDescription = "Spending pace this month compared with last month" }) {
        val day = selected
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (day != null) {
                Text("Day ${day + 1}", style = MaterialTheme.typography.labelMedium, color = colors.subtleText, modifier = Modifier.weight(1f))
                current.getOrNull(day)?.let { AmountText(it, compact = true, hidden = hidden, style = AmountStyles.small, color = accent) }
                Spacer(Modifier.width(10.dp))
                previous.getOrNull(day)?.let { AmountText(it, compact = true, hidden = hidden, style = AmountStyles.small, color = colors.subtleText) }
            } else {
                LegendItem(accent, "This month")
                Spacer(Modifier.width(14.dp))
                LegendItem(grey, "Last month")
                Spacer(Modifier.weight(1f))
                if (projected != null) {
                    Text("Projected ", style = MaterialTheme.typography.labelMedium, color = colors.subtleText)
                    AmountText(projected, compact = true, hidden = hidden, style = AmountStyles.small)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(current, previous) {
                    fun pick(x: Float) { selected = ((x / (this.size.width.toFloat() / (days - 1))) + 0.5f).toInt().coerceIn(0, days - 1) }
                    detectTapGestures(onPress = { pick(it.x); tryAwaitRelease(); selected = null })
                }
                .pointerInput(current, previous) {
                    detectDragGestures(onDragEnd = { selected = null }) { change, _ ->
                        selected = ((change.position.x / (this.size.width.toFloat() / (days - 1))) + 0.5f).toInt().coerceIn(0, days - 1)
                    }
                },
        ) {
            val top = listOfNotNull(current.maxOrNull(), previous.maxOrNull(), projected).maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
            val pad = 6.dp.toPx()
            val h = this.size.height - pad * 2
            val step = this.size.width / (days - 1)
            fun pt(i: Int, v: Paise) = Offset(i * step, pad + h - v.toFloat() / top * h)
            drawLine(grid, Offset(0f, pad + h), Offset(this.size.width, pad + h), 1f)
            clipRect(right = this.size.width * progress) {
                if (previous.size >= 2) {
                    drawPath(smoothPath(previous.mapIndexed(::pt)), grey, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (current.size >= 2) {
                    val pts = current.mapIndexed(::pt)
                    val line = smoothPath(pts)
                    val area = Path().apply {
                        addPath(line)
                        lineTo(pts.last().x, pad + h)
                        lineTo(pts.first().x, pad + h)
                        close()
                    }
                    drawPath(area, accent.copy(alpha = 0.07f))
                    drawPath(line, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (projected != null && current.isNotEmpty() && current.size < days) {
                    drawLine(
                        accent.copy(alpha = 0.6f), pt(current.lastIndex, current.last()), pt(days - 1, projected),
                        strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)), cap = StrokeCap.Round,
                    )
                }
            }
            if (current.isNotEmpty() && progress >= 1f) {
                val today = pt(current.lastIndex, current.last())
                drawLine(grid, Offset(today.x, pad), Offset(today.x, pad + h), 1f)
                drawCircle(accent.copy(alpha = 0.2f), 8.dp.toPx(), today)
                drawCircle(accent, 4.dp.toPx(), today)
            }
            selected?.let { s ->
                drawLine(grid, Offset(s * step, 0f), Offset(s * step, this.size.height), 1f)
                current.getOrNull(s)?.let { drawCircle(surface, 6.dp.toPx(), pt(s, it)); drawCircle(accent, 4.dp.toPx(), pt(s, it)) }
                previous.getOrNull(s)?.let { drawCircle(surface, 5.dp.toPx(), pt(s, it)); drawCircle(grey, 3.5.dp.toPx(), pt(s, it)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("1", style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText, modifier = Modifier.weight(1f))
            Text("${(days + 1) / 2}", style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("$days", style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
    }
}

/**
 * A compact single-series bar chart, for insight cards. The last bar is highlighted in the accent
 * colour; an optional dashed reference line marks a limit or average. Tap a bar to see its value.
 */
@Composable
fun MiniBarChart(
    labels: List<String>,
    values: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    reference: Long? = null,
    hidden: Boolean = false,
    highlightLast: Boolean = true,
) {
    val colors = KoshTheme.colors
    val progress = rememberEntrance(values, 700)
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    Column(modifier.semantics { contentDescription = "Bar chart: " + labels.zip(values).joinToString { "${it.first} ${formatAmount(it.second, compact = true)}" } }) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val i = selected ?: values.lastIndex
            Text(labels.getOrNull(i).orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.subtleText, modifier = Modifier.weight(1f))
            values.getOrNull(i)?.let { AmountText(it, compact = true, hidden = hidden, style = AmountStyles.small) }
        }
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(values) {
                val width = size.width.toFloat()
                detectTapGestures(onPress = { pos ->
                    if (values.isNotEmpty()) selected = (pos.x / (width / values.size)).toInt().coerceIn(0, values.lastIndex)
                    tryAwaitRelease()
                    selected = null
                })
            },
        ) {
            if (values.isEmpty()) return@Canvas
            val top = (listOfNotNull(values.maxOrNull(), reference).maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
            val slot = this.size.width / values.size
            val barW = (slot * 0.55f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                val bh = (max(v, 0L).toFloat() / top) * this.size.height * progress
                val isHot = (highlightLast && i == values.lastIndex) || i == selected
                drawRoundRect(
                    if (isHot) colors.accent else colors.tertiaryText.copy(alpha = 0.35f),
                    topLeft = Offset(i * slot + (slot - barW) / 2f, this.size.height - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
            }
            reference?.let {
                val y = this.size.height - it.toFloat() / top * this.size.height
                drawLine(colors.warning, Offset(0f, y), Offset(this.size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1) }
        }
    }
}

/** A compact line for insight cards, with an optional dashed reference line (e.g. a budget). */
@Composable
fun MiniLineChart(
    values: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    reference: Long? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    hidden: Boolean = false,
) {
    val colors = KoshTheme.colors
    val progress = rememberEntrance(values, 800)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            values.lastOrNull()?.let { AmountText(it, compact = true, hidden = hidden, style = AmountStyles.small) }
        }
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = "Trend line" }) {
            if (values.size < 2) return@Canvas
            val all = values + listOfNotNull(reference)
            val lo = minOf(0L, all.min()).toFloat()
            val hi = all.max().toFloat()
            val span = (hi - lo).takeIf { it > 0f } ?: 1f
            val step = this.size.width / (values.size - 1)
            fun y(v: Long) = this.size.height - (v - lo) / span * this.size.height
            val pts = values.mapIndexed { i, v -> Offset(i * step, y(v)) }
            reference?.let {
                drawLine(colors.warning, Offset(0f, y(it)), Offset(this.size.width, y(it)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
            if (lo < 0f) drawLine(colors.divider, Offset(0f, y(0)), Offset(this.size.width, y(0)), 1f)
            clipRect(right = this.size.width * progress) {
                drawPath(smoothPath(pts), colors.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (progress >= 1f) drawCircle(colors.accent, 3.5.dp.toPx(), pts.last())
        }
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(startLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText, modifier = Modifier.weight(1f))
                Text(endLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText)
            }
        }
    }
}

/**
 * A calendar heatmap of daily spending: one column per week, rows Monday to Sunday, darker emerald
 * for heavier days. `null` cells (future days) are left blank. Tap a day to see its total.
 *
 * @param weeks one entry per week (oldest first), each with 7 values Monday..Sunday.
 * @param dayLabels labels for each cell in the same shape, shown when tapped (e.g. "Tue 14 Oct").
 */
@Composable
fun SpendHeatmap(
    weeks: List<List<Long?>>,
    dayLabels: List<List<String>>,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
) {
    val colors = KoshTheme.colors
    var selected by remember(weeks) { mutableStateOf<Pair<Int, Int>?>(null) }
    val maxValue = weeks.flatten().filterNotNull().maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val rows = listOf("M", "T", "W", "T", "F", "S", "S")
    Column(modifier.semantics { contentDescription = "Daily spending heatmap for the last ${weeks.size} weeks" }) {
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            val sel = selected
            if (sel != null) {
                Text(dayLabels.getOrNull(sel.first)?.getOrNull(sel.second).orEmpty(), style = MaterialTheme.typography.labelMedium,
                    color = colors.subtleText, modifier = Modifier.weight(1f))
                AmountText(weeks[sel.first][sel.second] ?: 0L, compact = true, hidden = hidden, style = AmountStyles.small)
            } else {
                Text("Tap a day", style = MaterialTheme.typography.labelMedium, color = colors.tertiaryText, modifier = Modifier.weight(1f))
                Text("Less", style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText)
                listOf(0.08f, 0.3f, 0.55f, 0.85f).forEach {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors.accent.copy(alpha = it)))
                }
                Spacer(Modifier.width(4.dp))
                Text("More", style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 6.dp)) {
                rows.forEach { Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.tertiaryText) } }
            }
            weeks.forEachIndexed { w, week ->
                Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEachIndexed { d, v ->
                        val alpha = when {
                            v == null -> 0f
                            v <= 0L -> 0.06f
                            else -> 0.15f + 0.8f * (v.toFloat() / maxValue)
                        }
                        val isSel = selected == (w to d)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (v == null) Color.Transparent else colors.accent.copy(alpha = alpha))
                                .then(if (isSel) Modifier.background(colors.ink.copy(alpha = 0.15f)) else Modifier)
                                .pointerInput(weeks) {
                                    detectTapGestures(onPress = {
                                        if (v != null) selected = w to d
                                        tryAwaitRelease()
                                        selected = null
                                    })
                                },
                        )
                    }
                }
            }
        }
    }
}
