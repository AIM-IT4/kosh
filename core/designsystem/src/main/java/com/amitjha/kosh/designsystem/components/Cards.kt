package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.BrandPalette
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise

/**
 * The standard content card: rounded 24dp, theme card surface, no elevation shadow
 * (depth comes from tone, which looks cleaner on dark backgrounds).
 */
@Composable
fun KoshCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(KoshTheme.spacing.lg),
    shape: Shape = RoundedCornerShape(KoshTheme.spacing.cardRadius),
    color: Color = KoshTheme.colors.cardSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A hero card filled with the brand gradient, with two soft light blooms for depth.
 * Content is drawn in white.
 */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    brush: Brush = BrandPalette.brandGradient,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(KoshTheme.spacing.xl),
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(brush)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.05f),
                        radius = size.minDimension * 0.9f,
                    ),
                    radius = size.minDimension * 0.9f,
                    center = Offset(size.width * 0.92f, size.height * 0.05f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(BrandPalette.Teal300.copy(alpha = 0.25f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 1.05f),
                        radius = size.minDimension * 0.8f,
                    ),
                    radius = size.minDimension * 0.8f,
                    center = Offset(size.width * 0.05f, size.height * 1.05f),
                )
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** A frosted, translucent surface with a hairline border — for overlays on gradients. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: PaddingValues = PaddingValues(KoshTheme.spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(KoshTheme.colors.glass)
            .border(1.dp, KoshTheme.colors.glassBorder, shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The dashboard hero: label, big (optionally animated) amount, a change chip and a
 * sparkline of recent values.
 *
 * @param deltaPaise change over the comparison period; `null` hides the chip.
 * @param trend recent values for the sparkline (oldest first); fewer than two hides it.
 * @param onToggleHidden shows an eye button that toggles privacy mode.
 */
@Composable
fun BalanceHeroCard(
    label: String,
    amount: Paise,
    modifier: Modifier = Modifier,
    deltaPaise: Paise? = null,
    deltaLabel: String = "this month",
    trend: List<Float> = emptyList(),
    hidden: Boolean = false,
    onToggleHidden: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    GradientCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.weight(1f),
            )
            if (onToggleHidden != null) {
                IconButton(onClick = onToggleHidden, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (hidden) "Show amounts" else "Hide amounts",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AmountText(
            paise = amount,
            style = AmountStyles.hero,
            color = Color.White,
            hidden = hidden,
            animate = true,
        )
        if (deltaPaise != null || trend.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (deltaPaise != null) {
                    DeltaChip(deltaPaise = deltaPaise, label = deltaLabel, hidden = hidden, onDark = true)
                }
                Spacer(Modifier.weight(1f))
                if (trend.size >= 2) {
                    Sparkline(
                        values = trend,
                        modifier = Modifier.size(width = 96.dp, height = 36.dp),
                        color = BrandPalette.Gold,
                        fill = true,
                    )
                }
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(16.dp))
            footer()
        }
    }
}

/**
 * A pill showing a change with an up/down arrow. Positive is green, negative red.
 * @param onDark render for placement on the brand gradient (white text, glass pill).
 */
@Composable
fun DeltaChip(
    deltaPaise: Paise,
    modifier: Modifier = Modifier,
    label: String? = null,
    hidden: Boolean = false,
    onDark: Boolean = false,
    positiveIsGood: Boolean = true,
) {
    val up = deltaPaise >= 0
    val good = up == positiveIsGood
    val tint = when {
        onDark -> Color.White
        good -> KoshTheme.colors.income
        else -> KoshTheme.colors.expense
    }
    val bg = when {
        onDark -> Color.White.copy(alpha = 0.16f)
        good -> KoshTheme.colors.income.copy(alpha = 0.14f)
        else -> KoshTheme.colors.expense.copy(alpha = 0.14f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
            contentDescription = if (up) "Up" else "Down",
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (hidden) "••••" else formatAmount(kotlin.math.abs(deltaPaise), compact = true),
            style = AmountStyles.small,
            color = tint,
        )
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.8f))
        }
    }
}

/** A filled circle or rounded square background for an icon. */
@Composable
fun IconBubble(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    shape: Shape = RoundedCornerShape(14.dp),
    alpha: Float = 0.16f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(color.copy(alpha = alpha)),
        contentAlignment = Alignment.Center,
    ) { content() }
}
