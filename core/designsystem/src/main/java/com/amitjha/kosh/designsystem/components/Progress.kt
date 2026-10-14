package com.amitjha.kosh.designsystem.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.BrandPalette
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise
import androidx.compose.material3.Icon

/** Animates from 0 to [target] on first composition, then springs to new targets. */
@Composable
private fun animatedFraction(target: Float): Float {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val value by animateFloatAsState(
        targetValue = if (started) target else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "progress",
    )
    return value
}

/**
 * A circular progress ring with a rounded gradient arc and centred content.
 * Values above 1 are drawn as a full ring.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    thickness: Dp = 10.dp,
    brush: Brush = Brush.sweepGradient(listOf(BrandPalette.Teal500, BrandPalette.Indigo400, BrandPalette.Teal500)),
    trackColor: Color = KoshTheme.colors.cardSurfaceHigh,
    content: @Composable () -> Unit = {},
) {
    val p = animatedFraction(progress.coerceIn(0f, 1f))
    Box(
        modifier = modifier.size(size).semantics { contentDescription = "${(progress * 100).toInt()} percent" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(brush, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

/**
 * A budget bar that shifts green → amber → red as it fills, with an optional marker for the
 * projected month-end spend.
 *
 * @param spent spent so far this period.
 * @param limit the budget.
 * @param projected projected spend at period end; drawn as a thin marker when given.
 */
@Composable
fun BudgetProgressBar(
    spent: Paise,
    limit: Paise,
    modifier: Modifier = Modifier,
    projected: Paise? = null,
    height: Dp = 10.dp,
) {
    val colors = KoshTheme.colors
    val fraction = if (limit > 0) spent.toFloat() / limit else 0f
    val f = animatedFraction(fraction.coerceIn(0f, 1f))
    val color = when {
        fraction >= 1f -> colors.alert
        fraction >= 0.8f -> colors.warning
        else -> colors.income
    }
    val track = colors.cardSurfaceHigh
    val markerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height + 6.dp)
            .semantics { contentDescription = "Budget used ${(fraction * 100).toInt()} percent" },
    ) {
        val h = height.toPx()
        val top = (this.size.height - h) / 2f
        val r = CornerRadius(h / 2f)
        drawRoundRect(track, Offset(0f, top), Size(this.size.width, h), r)
        drawRoundRect(color, Offset(0f, top), Size(this.size.width * f, h), r)
        if (projected != null && limit > 0) {
            val px = (projected.toFloat() / limit).coerceIn(0f, 1f) * this.size.width
            drawLine(markerColor, Offset(px, 0f), Offset(px, this.size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * A savings goal: ring with percentage, name, saved / target and a hint such as
 * "₹8,500/month to reach by Mar 2027".
 */
@Composable
fun GoalCard(
    name: String,
    saved: Paise,
    target: Paise,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    hint: String? = null,
    onTrack: Boolean = true,
    hidden: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val progress = if (target > 0) saved.toFloat() / target else 0f
    KoshCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(progress = progress, size = 64.dp, thickness = 7.dp) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                } else {
                    Text("${(progress * 100).toInt().coerceAtMost(999)}%", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AmountText(saved, hidden = hidden, style = AmountStyles.medium, compact = true)
                    Text(" of ", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    AmountText(target, hidden = hidden, style = AmountStyles.small, compact = true, color = KoshTheme.colors.subtleText)
                }
                if (hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (onTrack) KoshTheme.colors.good else KoshTheme.colors.warning,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** A thin linear progress bar, e.g. for FD tenure elapsed. */
@Composable
fun ThinProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 6.dp,
) {
    val f = animatedFraction(progress.coerceIn(0f, 1f))
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(KoshTheme.colors.cardSurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}
