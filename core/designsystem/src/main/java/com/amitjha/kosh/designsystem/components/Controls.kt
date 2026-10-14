package com.amitjha.kosh.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.BrandPalette
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType

/**
 * A pill-shaped segmented control with a sliding indicator, e.g. Month / Quarter / Year.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .background(KoshTheme.colors.cardSurfaceHigh)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(
            targetValue = segment * selectedIndex,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
            label = "segment",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(shape)
                .background(BrandPalette.brandGradient),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable { onSelect(i) }
                        .semantics {
                            role = Role.Tab
                            this.selected = selected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Color.White else KoshTheme.colors.subtleText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** ‹ Oct 2026 › — steps through periods. Disable "next" at the current period. */
@Composable
fun MonthSwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    canGoNext: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(KoshTheme.colors.cardSurfaceHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period")
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 84.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next period",
                modifier = Modifier.alpha(if (canGoNext) 1f else 0.3f),
            )
        }
    }
}

/** A horizontally scrolling row of single- or multi-select filter chips. */
@Composable
fun FilterChipsRow(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(option) },
                label = { Text(option, maxLines = 1) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = KoshTheme.colors.cardSurfaceHigh,
                ),
                border = null,
            )
        }
    }
}

/** An empty-state block: a large tinted icon, a title, a message and an optional action. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.linearGradient(
                        listOf(BrandPalette.Indigo600.copy(alpha = 0.25f), BrandPalette.Teal500.copy(alpha = 0.18f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = KoshTheme.colors.subtleText,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = actionLabel, onClick = onAction, modifier = Modifier.fillMaxWidth(0.7f))
        }
    }
}

/** A shimmering placeholder block for loading states. */
@Composable
fun LoadingShimmer(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    val base = KoshTheme.colors.shimmerBase
    val hi = KoshTheme.colors.shimmerHighlight
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .semantics { contentDescription = "Loading" },
    ) {
        val w = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    Brush.linearGradient(
                        listOf(base, hi, base),
                        start = Offset(w * x, 0f),
                        end = Offset(w * x + w * 0.6f, 0f),
                    ),
                ),
        )
    }
}

/**
 * A minimal top bar: optional back arrow, title with optional subtitle, and actions.
 * Transparent so it sits on the screen background.
 */
@Composable
fun KoshTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText, maxLines = 1)
            }
        }
        actions()
    }
}

/**
 * A compact stat: label, amount, optional change. For rows of "Income · Spent · Saved".
 */
@Composable
fun StatChip(
    label: String,
    value: Paise,
    modifier: Modifier = Modifier,
    type: TxnType? = null,
    deltaPaise: Paise? = null,
    positiveIsGood: Boolean = true,
    hidden: Boolean = false,
    onDark: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (onDark) Color.White.copy(alpha = 0.12f) else KoshTheme.colors.cardSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (onDark) Color.White.copy(alpha = 0.75f) else KoshTheme.colors.subtleText,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        AmountText(
            paise = value,
            style = AmountStyles.medium,
            compact = true,
            hidden = hidden,
            color = if (onDark) Color.White else when (type) {
                TxnType.INCOME -> KoshTheme.colors.income
                TxnType.EXPENSE -> KoshTheme.colors.expense
                else -> Color.Unspecified
            },
        )
        if (deltaPaise != null) {
            Spacer(Modifier.height(6.dp))
            DeltaChip(deltaPaise = deltaPaise, hidden = hidden, onDark = onDark, positiveIsGood = positiveIsGood)
        }
    }
}

/** A section title with an optional trailing "See all" style action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The main call-to-action button, filled with the brand gradient.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(
                if (enabled) BrandPalette.brandGradient
                else Brush.linearGradient(listOf(KoshTheme.colors.cardSurfaceHigh, KoshTheme.colors.cardSurfaceHigh)),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else KoshTheme.colors.subtleText,
        )
    }
}
