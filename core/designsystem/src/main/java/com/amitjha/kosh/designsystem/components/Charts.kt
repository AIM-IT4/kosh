package com.amitjha.kosh.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One slice of a [DonutChart] or segment of a [StackedBar]. */
@Immutable
data class ChartSlice(val label: String, val value: Paise, val color: Color)

/** One period of a [BarChart]: income and expense side by side. */
@Immutable
data class BarGroup(val label: String, val income: Paise, val expense: Paise)

/** One point of a [LineAreaChart]. */
@Immutable
data class ChartPoint(val label: String, val value: Paise)

@Composable
private fun rememberEntrance(key: Any?, durationMillis: Int = 900): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing))
    }
    return progress.value
}

/**
 * An animated donut chart. Tap a slice to select it; the centre then shows that slice.
 *
 * @param centerLabel shown in the centre when nothing is selected (e.g. "Spent").
 * @param centerValue amount shown in the centre when nothing is selected (default: the total).
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    thickness: Dp = 26.dp,
    centerLabel: String = "Total",
    centerValue: Paise? = null,
    hidden: Boolean = false,
    onSliceSelected: ((ChartSlice?) -> Unit)? = null,
) {
    val total = slices.sumOf { max(it.value, 0L) }.coerceAtLeast(1L)
    val progress = rememberEntrance(slices)
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    val track = KoshTheme.colors.cardSurfaceHigh
    val description = slices.joinToString { "${it.label} ${formatAmount(it.value, compact = true)}" }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Donut chart: $description" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { tap ->
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        val r = min(this.size.width, this.size.height) / 2f
                        val dist = hypot(tap.x - c.x, tap.y - c.y)
                        val ring = thickness.toPx()
                        if (dist < r - ring * 1.6f || dist > r + 8f) {
                            selected = null
                        } else {
                            var angle = (atan2(tap.y - c.y, tap.x - c.x) * 180f / PI.toFloat()) + 90f
                            if (angle < 0) angle += 360f
                            var acc = 0f
                            var hit: Int? = null
                            slices.forEachIndexed { i, s ->
                                val sweep = max(s.value, 0L).toFloat() / total * 360f
                                if (hit == null && angle >= acc && angle < acc + sweep) hit = i
                                acc += sweep
                            }
                            selected = if (selected == hit) null else hit
                        }
                        onSliceSelected?.invoke(selected?.let { slices[it] })
                    }
                },
        ) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f + 6f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            var start = -90f
            val gap = if (slices.size > 1) 1.6f else 0f
            slices.forEachIndexed { i, s ->
                val sweep = max(s.value, 0L).toFloat() / total * 360f * progress
                val isSel = selected == i
                val alpha = if (selected == null || isSel) 1f else 0.35f
                drawArc(
                    color = s.color.copy(alpha = alpha),
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.1f),
                    useCenter = false,
                    topLeft = if (isSel) Offset(inset - 4f, inset - 4f) else topLeft,
                    size = if (isSel) Size(arcSize.width + 8f, arcSize.height + 8f) else arcSize,
                    style = Stroke(if (isSel) stroke + 8f else stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        val sel = selected?.let { slices.getOrNull(it) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = sel?.label ?: centerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = KoshTheme.colors.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AmountText(
                paise = sel?.value ?: centerValue ?: slices.sumOf { it.value },
                style = AmountStyles.large,
                compact = true,
                hidden = hidden,
            )
            if (sel != null) {
                Text(
                    text = "${(sel.value * 100 / total)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = sel.color,
                )
            }
        }
    }
}

/** Legend rows for a [DonutChart]: colour dot, label, amount and share. */
@Composable
fun ChartLegend(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    maxItems: Int = 6,
) {
    val total = slices.sumOf { max(it.value, 0L) }.coerceAtLeast(1L)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        slices.take(maxItems).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(10.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${s.value * 100 / total}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = KoshTheme.colors.subtleText,
                )
                Spacer(Modifier.width(12.dp))
                AmountText(paise = s.value, compact = true, hidden = hidden, style = AmountStyles.small)
            }
        }
    }
}

/**
 * Grouped income/expense bars per period with animated growth. Drag or tap to inspect a
 * period; its values appear above the chart.
 */
@Composable
fun BarChart(
    groups: List<BarGroup>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    hidden: Boolean = false,
) {
    val colors = KoshTheme.colors
    val progress = rememberEntrance(groups)
    var selected by remember(groups) { mutableStateOf<Int?>(null) }
    val maxValue = groups.maxOfOrNull { max(it.income, it.expense) }?.coerceAtLeast(1L) ?: 1L
    val grid = colors.divider
    val labelColor = colors.subtleText

    Column(modifier = modifier.semantics {
        contentDescription = "Income and expense chart: " + groups.joinToString {
            "${it.label} income ${formatAmount(it.income, compact = true)} expense ${formatAmount(it.expense, compact = true)}"
        }
    }) {
        val sel = selected?.let { groups.getOrNull(it) }
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sel != null) {
                Text(sel.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                LegendDot(colors.income)
                AmountText(sel.income, compact = true, hidden = hidden, style = AmountStyles.small, color = colors.income)
                Spacer(Modifier.width(12.dp))
                LegendDot(colors.expense)
                AmountText(sel.expense, compact = true, hidden = hidden, style = AmountStyles.small, color = colors.expense)
            } else {
                LegendDot(colors.income)
                Text("Income", style = MaterialTheme.typography.labelMedium, color = labelColor)
                Spacer(Modifier.width(14.dp))
                LegendDot(colors.expense)
                Text("Expense", style = MaterialTheme.typography.labelMedium, color = labelColor)
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(groups) {
                    fun indexAt(x: Float): Int? {
                        if (groups.isEmpty()) return null
                        val slot = this.size.width.toFloat() / groups.size
                        return (x / slot).toInt().coerceIn(0, groups.lastIndex)
                    }
                    detectTapGestures { selected = indexAt(it.x).takeIf { i -> i != selected } }
                }
                .pointerInput(groups) {
                    detectDragGestures(
                        onDragEnd = {},
                        onDrag = { change, _ ->
                            val slot = this.size.width.toFloat() / groups.size.coerceAtLeast(1)
                            selected = (change.position.x / slot).toInt().coerceIn(0, (groups.size - 1).coerceAtLeast(0))
                        },
                    )
                },
        ) {
            if (groups.isEmpty()) return@Canvas
            val labelSpace = 0f
            val chartH = this.size.height - labelSpace
            repeat(4) { i ->
                val y = chartH * i / 3f
                drawLine(grid, Offset(0f, y), Offset(this.size.width, y), strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }
            val slot = this.size.width / groups.size
            val barW = min(slot * 0.26f, 18.dp.toPx())
            val radius = barW / 2f
            groups.forEachIndexed { i, g ->
                val cx = slot * i + slot / 2f
                val dim = selected != null && selected != i
                val inH = chartH * g.income / maxValue * progress
                val exH = chartH * g.expense / maxValue * progress
                drawRoundBar(colors.income.copy(alpha = if (dim) 0.3f else 1f), cx - barW - 2f, chartH, barW, inH, radius)
                drawRoundBar(colors.expense.copy(alpha = if (dim) 0.3f else 1f), cx + 2f, chartH, barW, exH, radius)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            groups.forEachIndexed { i, g ->
                Text(
                    g.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected == i) MaterialTheme.colorScheme.onSurface else labelColor,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundBar(
    color: Color, left: Float, bottom: Float, width: Float, height: Float, radius: Float,
) {
    if (height <= 0f) return
    val r = min(radius, height / 2f)
    val path = Path().apply {
        moveTo(left, bottom)
        lineTo(left, bottom - height + r)
        quadraticTo(left, bottom - height, left + r, bottom - height)
        lineTo(left + width - r, bottom - height)
        quadraticTo(left + width, bottom - height, left + width, bottom - height + r)
        lineTo(left + width, bottom)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(color))
}

/**
 * A smooth (monotone cubic) line with a gradient area fill that draws in from the left.
 * Drag across it to inspect values; the selected point's label and value show at the top.
 */
@Composable
fun LineAreaChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    hidden: Boolean = false,
    showHeader: Boolean = true,
) {
    val progress = rememberEntrance(points, 1100)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val subtle = KoshTheme.colors.subtleText
    val grid = KoshTheme.colors.divider
    val surface = MaterialTheme.colorScheme.surface

    Column(modifier = modifier.semantics {
        contentDescription = "Trend chart from ${points.firstOrNull()?.label} to ${points.lastOrNull()?.label}"
    }) {
        if (showHeader) {
            val p = selected?.let { points.getOrNull(it) } ?: points.lastOrNull()
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p?.label ?: "", style = MaterialTheme.typography.labelMedium, color = subtle, modifier = Modifier.weight(1f))
                if (p != null) AmountText(p.value, compact = true, hidden = hidden, style = AmountStyles.medium)
            }
            Spacer(Modifier.height(6.dp))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    fun pick(x: Float) {
                        if (points.size < 2) return
                        val step = this.size.width.toFloat() / (points.size - 1)
                        selected = ((x / step) + 0.5f).toInt().coerceIn(0, points.lastIndex)
                    }
                    detectTapGestures(onPress = { pick(it.x); tryAwaitRelease(); selected = null })
                }
                .pointerInput(points) {
                    detectDragGestures(onDragEnd = { selected = null }) { change, _ ->
                        if (points.size < 2) return@detectDragGestures
                        val step = this.size.width.toFloat() / (points.size - 1)
                        selected = ((change.position.x / step) + 0.5f).toInt().coerceIn(0, points.lastIndex)
                    }
                },
        ) {
            if (points.size < 2) return@Canvas
            val values = points.map { it.value.toFloat() }
            val lo = values.min()
            val hi = values.max()
            val span = (hi - lo).takeIf { it > 0f } ?: max(1f, hi * 0.1f)
            val pad = 8.dp.toPx()
            val h = this.size.height - pad * 2
            val step = this.size.width / (points.size - 1)
            val xy = values.mapIndexed { i, v -> Offset(i * step, pad + h - (v - lo) / span * h) }

            repeat(3) { i ->
                val y = pad + h * i / 2f
                drawLine(grid, Offset(0f, y), Offset(this.size.width, y), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }
            val line = smoothPath(xy)
            val area = Path().apply {
                addPath(line)
                lineTo(xy.last().x, this@Canvas.size.height)
                lineTo(xy.first().x, this@Canvas.size.height)
                close()
            }
            clipRect(right = this.size.width * progress) {
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))))
                drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val sel = selected
            if (sel != null) {
                val p = xy[sel]
                drawLine(color.copy(alpha = 0.5f), Offset(p.x, 0f), Offset(p.x, this.size.height), 1.5f)
                drawCircle(surface, 7.dp.toPx(), p)
                drawCircle(color, 5.dp.toPx(), p)
            } else if (progress >= 1f) {
                drawCircle(color.copy(alpha = 0.25f), 9.dp.toPx(), xy.last())
                drawCircle(color, 4.5.dp.toPx(), xy.last())
            }
        }
    }
}

/** Monotone-ish cubic smoothing: control points at a third of the distance between neighbours. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]
        val p1 = points[i]
        val dx = (p1.x - p0.x) / 3f
        cubicTo(p0.x + dx, p0.y, p1.x - dx, p1.y, p1.x, p1.y)
    }
}

/** A tiny trend line without axes, e.g. inside cards. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    fill: Boolean = false,
    strokeWidth: Dp = 2.dp,
) {
    val progress = rememberEntrance(values, 800)
    Canvas(modifier = modifier.semantics { contentDescription = "Trend" }) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val pad = strokeWidth.toPx()
        val h = this.size.height - pad * 2
        val step = this.size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v -> Offset(i * step, pad + h - (v - lo) / span * h) }
        val line = smoothPath(pts)
        clipRect(right = this.size.width * progress) {
            if (fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(pts.last().x, this@Canvas.size.height)
                    lineTo(pts.first().x, this@Canvas.size.height)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
            }
            drawPath(line, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * A single horizontal bar split into coloured segments (e.g. spending by category),
 * with an optional legend below.
 */
@Composable
fun StackedBar(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    showLegend: Boolean = true,
    hidden: Boolean = false,
) {
    val progress = rememberEntrance(slices)
    val total = slices.sumOf { max(it.value, 0L) }.coerceAtLeast(1L)
    val track = KoshTheme.colors.cardSurfaceHigh
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50))
                .semantics { contentDescription = "Split: " + slices.joinToString { "${it.label} ${it.value * 100 / total}%" } },
        ) {
            drawRect(track)
            var x = 0f
            slices.forEach { s ->
                val w = this.size.width * max(s.value, 0L) / total * progress
                drawRect(s.color, Offset(x, 0f), Size((w - 2f).coerceAtLeast(0f), this.size.height))
                x += w
            }
        }
        if (showLegend) {
            Spacer(Modifier.height(10.dp))
            FlowLegend(slices, total, hidden)
        }
    }
}

@Composable
private fun FlowLegend(slices: List<ChartSlice>, total: Long, hidden: Boolean) {
    // A simple two-column legend; FlowRow would need an opt-in API.
    val rows = slices.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { s ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            s.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (hidden) "" else "${s.value * 100 / total}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoshTheme.colors.subtleText,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Keeps a square aspect for chart containers. */
fun Modifier.squareChart(): Modifier = this.aspectRatio(1f)
