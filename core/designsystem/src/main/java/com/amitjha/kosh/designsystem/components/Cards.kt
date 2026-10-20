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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise

/**
 * The standard content card: white (or dark card) surface, 16dp corners and a 1dp hairline
 * outline. No shadow.
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
            .border(1.dp, KoshTheme.colors.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * An emphasis card with a flat ink face (light theme) and white content. Use sparingly:
 * one per screen at most. The [brush] parameter is kept for compatibility; pass a
 * [SolidColor] to change the face.
 */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    brush: Brush = SolidColor(KoshTheme.colors.emphasis),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(KoshTheme.spacing.xl),
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(brush)
            // On the near-black dark background the face alone has no edge.
            .then(if (KoshTheme.colors.isDark) Modifier.border(1.dp, KoshTheme.colors.hairline, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** The flat ink used for emphasis cards and card faces. */
val InkFace = Color(0xFF111111)

/** A plain outlined surface (the old frosted glass, now flat). */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    contentPadding: PaddingValues = PaddingValues(KoshTheme.spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(KoshTheme.colors.cardSurface)
            .border(1.dp, KoshTheme.colors.hairline, shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The dashboard headline: a small label, a large amount and an optional change line.
 * Deliberately not a card: it sits directly on the page.
 *
 * @param deltaPaise change over the comparison period; `null` hides it.
 * @param trend recent values for a small sparkline (oldest first); fewer than two hides it.
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
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tr(label),
                style = MaterialTheme.typography.bodyMedium,
                color = KoshTheme.colors.subtleText,
            )
            if (onToggleHidden != null) {
                IconButton(onClick = onToggleHidden) {
                    Icon(
                        imageVector = if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (hidden) "Show amounts" else "Hide amounts",
                        tint = KoshTheme.colors.tertiaryText,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            AmountText(
                paise = amount,
                style = AmountStyles.hero,
                color = KoshTheme.colors.ink,
                hidden = hidden,
                animate = true,
                modifier = Modifier.weight(1f),
            )
            if (trend.size >= 2) {
                Sparkline(
                    values = trend,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .size(width = 72.dp, height = 28.dp),
                    color = KoshTheme.colors.accent,
                    fill = false,
                )
            }
        }
        if (deltaPaise != null) {
            Spacer(Modifier.height(6.dp))
            DeltaChip(deltaPaise = deltaPaise, label = deltaLabel, hidden = hidden)
        }
        if (footer != null) {
            Spacer(Modifier.height(16.dp))
            footer()
        }
    }
}

/**
 * A change shown as text: "+₹12.5K this month". Positive is emerald, negative is ink
 * (or red when [positiveIsGood] is false and the value rises).
 * @param onDark render on an ink surface (white text).
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
        else -> KoshTheme.colors.alert
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (hidden) "••••" else (if (up) "+" else "−") + formatAmount(kotlin.math.abs(deltaPaise), compact = true),
            style = AmountStyles.small,
            color = tint,
        )
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (onDark) Color.White.copy(alpha = 0.7f) else KoshTheme.colors.subtleText,
            )
        }
    }
}

/** A tinted rounded-square tile behind an icon (10% of [color]). */
@Composable
fun IconBubble(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    alpha: Float = 0.10f,
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
