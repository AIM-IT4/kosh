package com.amitjha.kosh.designsystem.components

import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType

/**
 * One transaction in a list.
 *
 * @param subtitle typically "HDFC ••1234 · 2:45 PM".
 * @param source shows a small SMS/EMAIL/AUTO badge for imported transactions.
 * @param unreviewed shows an accent dot for auto-imported entries awaiting review.
 */
@Composable
fun TransactionRow(
    title: String,
    subtitle: String,
    amount: Paise,
    type: TxnType,
    iconName: String?,
    categoryColor: Color,
    modifier: Modifier = Modifier,
    source: TxnSource = TxnSource.MANUAL,
    unreviewed: Boolean = false,
    hidden: Boolean = false,
    trailingNote: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 2.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CategoryIcon(iconName = iconName, color = categoryColor, size = 36.dp)
            if (unreviewed) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(KoshTheme.colors.accent)
                        .semantics { contentDescription = "Needs review" },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (source != TxnSource.MANUAL) {
                    SourceBadge(source)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            AmountText(paise = amount, type = type, hidden = hidden, style = AmountStyles.medium)
            if (trailingNote != null) {
                Text(trailingNote, style = MaterialTheme.typography.labelSmall, color = KoshTheme.colors.subtleText)
            }
        }
    }
}

/** A tiny pill that says where a transaction came from. */
@Composable
fun SourceBadge(source: TxnSource, modifier: Modifier = Modifier) {
    val (label, color) = when (source) {
        TxnSource.SMS -> "SMS" to KoshTheme.colors.subtleText
        TxnSource.EMAIL -> "EMAIL" to KoshTheme.colors.subtleText
        TxnSource.AUTO -> "AUTO" to KoshTheme.colors.subtleText
        TxnSource.MANUAL -> "MANUAL" to KoshTheme.colors.subtleText
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(KoshTheme.colors.fill)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** A sticky-style header for a day in the transaction list, with that day's net total. */
@Composable
fun DateHeader(
    label: String,
    modifier: Modifier = Modifier,
    netPaise: Paise? = null,
    hidden: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = KoshTheme.colors.subtleText,
            modifier = Modifier.weight(1f),
        )
        if (netPaise != null) {
            AmountText(
                paise = netPaise,
                style = AmountStyles.small,
                hidden = hidden,
                color = KoshTheme.colors.subtleText,
            )
        }
    }
}

/**
 * Swipe a row left to delete (red) or right to confirm (green, e.g. mark reviewed).
 * Either action is optional; the callback decides whether the row stays (return `false`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionsRow(
    modifier: Modifier = Modifier,
    onDelete: (() -> Boolean)? = null,
    onConfirm: (() -> Boolean)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onDelete?.invoke() ?: false
                SwipeToDismissBoxValue.StartToEnd -> onConfirm?.invoke() ?: false
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    // The row and the colour behind it share one shape, so the corners match while swiping.
    val shape = RoundedCornerShape(16.dp)
    val confirmLabel = tr("Confirm")
    val deleteLabel = tr("Delete")
    SwipeToDismissBox(
        state = state,
        // Screen readers can't swipe: the same actions are offered as accessibility actions.
        modifier = modifier.semantics {
            customActions = listOfNotNull(
                onConfirm?.let { confirm -> CustomAccessibilityAction(confirmLabel) { confirm(); true } },
                onDelete?.let { delete -> CustomAccessibilityAction(deleteLabel) { delete(); true } },
            )
        },
        enableDismissFromStartToEnd = onConfirm != null,
        enableDismissFromEndToStart = onDelete != null,
        backgroundContent = {
            val direction = state.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> KoshTheme.colors.alert
                    SwipeToDismissBoxValue.StartToEnd -> KoshTheme.colors.income
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                },
                label = "swipe-bg",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(color.copy(alpha = 0.9f))
                    .padding(horizontal = 22.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = if (direction == SwipeToDismissBoxValue.EndToStart) Icons.Filled.Delete else Icons.Filled.Check,
                        contentDescription = if (direction == SwipeToDismissBoxValue.EndToStart) "Delete" else "Confirm",
                        tint = Color.White,
                    )
                }
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.background),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Staggered entrance for list items: fades and slides each item in, delayed by its index.
 * Use as `Modifier.staggeredEntrance(index)` on items of a LazyColumn.
 */
@Composable
fun Modifier.staggeredEntrance(index: Int, maxDelayItems: Int = 10): Modifier {
    if (LocalInspectionMode.current) return this
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index.coerceAtMost(maxDelayItems) * 35).toLong())
        anim.animateTo(1f, tween(220))
    }
    return this.graphicsLayer {
        alpha = anim.value.coerceIn(0f, 1f)
        translationY = (1f - anim.value) * 24.dp.toPx()
    }
}

/** A quick fade-in for whole sections. */
@Composable
fun Modifier.fadeIn(delayMillis: Int = 0): Modifier {
    if (LocalInspectionMode.current) return this
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(400, delayMillis)) }
    return this.graphicsLayer { alpha = anim.value }
}
