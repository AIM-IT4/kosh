package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.KoshTheme

/** Severity of an insight; mirrors the finance engine's levels. */
enum class InsightSeverity { ALERT, WARNING, TIP, GOOD }

/** Accent colour and icon for a severity. */
@Composable
fun InsightSeverity.accent(): Pair<Color, ImageVector> {
    val c = KoshTheme.colors
    return when (this) {
        InsightSeverity.ALERT -> c.alert to Icons.Filled.ErrorOutline
        InsightSeverity.WARNING -> c.warning to Icons.Filled.WarningAmber
        InsightSeverity.TIP -> c.tip to Icons.Filled.Lightbulb
        InsightSeverity.GOOD -> c.good to Icons.Filled.CheckCircle
    }
}

/**
 * An insight: a severity-tinted card with an icon, title, message and an optional action.
 *
 * @param compact a single-line message for carousels on the dashboard.
 */
@Composable
fun InsightCard(
    severity: InsightSeverity,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val (accent, icon) = severity.accent()
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.16f), KoshTheme.colors.cardSurface, KoshTheme.colors.cardSurface),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.25f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconBubble(color = accent, size = 36.dp, shape = RoundedCornerShape(12.dp), alpha = 0.18f) {
                Icon(icon, contentDescription = severity.name.lowercase(), tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 1 else 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KoshTheme.colors.subtleText,
                    maxLines = if (compact) 2 else Int.MAX_VALUE,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onAction) { Text(actionLabel, color = accent) }
            }
        }
    }
}
