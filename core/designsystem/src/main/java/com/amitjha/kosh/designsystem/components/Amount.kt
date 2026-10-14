package com.amitjha.kosh.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.designsystem.theme.TabularFigures
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import kotlin.math.abs
import kotlin.math.roundToLong

/** Placeholder shown instead of an amount in privacy mode. */
const val HiddenAmount = "₹ ••••"

/**
 * Formats and renders an amount.
 *
 * @param paise the amount in paise; its sign is ignored when [type] is given.
 * @param type colours and signs the amount: income green with "+", expense red with "-",
 *   transfer neutral. `null` renders the raw signed value in the current content colour.
 * @param compact use ₹12.5K / ₹3.4L / ₹1.2Cr instead of the full figure.
 * @param showPaise show the paise part (ignored when [compact]).
 * @param hidden privacy mode: shows [HiddenAmount] and hides the value from accessibility too.
 * @param animate count up from the previous value when [paise] changes.
 * @param color overrides the colour derived from [type].
 */
@Composable
fun AmountText(
    paise: Paise,
    modifier: Modifier = Modifier,
    style: TextStyle = AmountStyles.medium,
    type: TxnType? = null,
    compact: Boolean = false,
    showPaise: Boolean = false,
    hidden: Boolean = false,
    animate: Boolean = false,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    val colors = KoshTheme.colors
    val resolvedColor = when {
        color != Color.Unspecified -> color
        type == TxnType.INCOME -> colors.income
        type == TxnType.EXPENSE -> colors.expense
        type == TxnType.TRANSFER -> colors.transfer
        else -> LocalContentColor.current
    }
    val shown: Paise = if (animate) animatedPaise(paise) else paise
    val text = when {
        hidden -> HiddenAmount
        else -> formatAmount(shown, type, compact, showPaise)
    }
    Text(
        text = text,
        modifier = modifier.semantics {
            contentDescription = if (hidden) "Amount hidden" else formatAmount(paise, type, false, true)
        },
        style = style.copy(fontFeatureSettings = TabularFigures),
        color = resolvedColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/** Formats an amount the way [AmountText] shows it. */
fun formatAmount(paise: Paise, type: TxnType? = null, compact: Boolean = false, showPaise: Boolean = false): String {
    val value = if (type != null) abs(paise) else paise
    val body = if (compact) Money.compact(value) else Money.format(value, showPaise = showPaise)
    return when (type) {
        TxnType.INCOME -> "+$body"
        TxnType.EXPENSE -> "−$body"
        else -> if (body.startsWith("-")) "−" + body.substring(1) else body
    }
}

@Composable
private fun animatedPaise(target: Paise): Paise {
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        anim.animateTo(target.toFloat(), tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }
    // Float has ~7 significant digits; snap to the exact target when the animation settles.
    return if (anim.isRunning) anim.value.roundToLong() else target
}
