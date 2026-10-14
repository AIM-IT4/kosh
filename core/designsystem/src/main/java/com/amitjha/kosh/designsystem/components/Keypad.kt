package com.amitjha.kosh.designsystem.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * State of a calculator-style amount entry: digits, a decimal point and + − × ÷.
 * Pure logic, so it can live in a ViewModel and be unit tested.
 */
class KeypadExpression(initial: String = "") {
    var text: String = initial
        private set

    /** Applies a key press. Keys: "0".."9", ".", "+", "-", "×", "÷", "=", "⌫", "C". */
    fun press(key: String): String {
        text = when (key) {
            "⌫" -> text.dropLast(1)
            "C" -> ""
            "=" -> evaluate()?.let { format(it) } ?: text
            "+", "-", "×", "÷" -> when {
                text.isEmpty() -> text
                text.last() in OPS -> text.dropLast(1) + key
                else -> text + key
            }
            "." -> {
                val current = text.takeLastWhile { it !in OPS }
                if ('.' in current) text else text + (if (current.isEmpty()) "0." else ".")
            }
            else -> {
                val current = text.takeLastWhile { it !in OPS }
                val decimals = current.substringAfter('.', "")
                when {
                    '.' in current && decimals.length >= 2 -> text
                    current == "0" -> text.dropLast(1) + key
                    current.length >= 10 -> text
                    else -> text + key
                }
            }
        }
        return text
    }

    /** True when the expression contains an operator (so "=" would change it). */
    val hasOperator: Boolean get() = text.any { it in OPS } && text.last() !in OPS

    /** The current value in paise, evaluating any pending operators; null if empty or invalid. */
    fun valuePaise(): Paise? = evaluate()?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.toLong()

    private fun evaluate(): BigDecimal? {
        val t = text.trimEnd { it in OPS }
        if (t.isEmpty()) return null
        val tokens = Regex("(\\d+\\.?\\d*|[+\\-×÷])").findAll(t).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        // Two passes: × ÷ first, then + −.
        val first = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if ((tok == "×" || tok == "÷") && first.isNotEmpty() && i + 1 < tokens.size) {
                val a = first.removeAt(first.lastIndex).toBigDecimalOrNull() ?: return null
                val b = tokens[i + 1].toBigDecimalOrNull() ?: return null
                val r = if (tok == "×") a.multiply(b) else if (b.signum() == 0) return null else a.divide(b, 6, RoundingMode.HALF_UP)
                first += r.toPlainString()
                i += 2
            } else {
                first += tok
                i++
            }
        }
        var acc = first[0].toBigDecimalOrNull() ?: return null
        var j = 1
        while (j + 1 < first.size) {
            val b = first[j + 1].toBigDecimalOrNull() ?: return null
            acc = if (first[j] == "+") acc.add(b) else acc.subtract(b)
            j += 2
        }
        return acc.max(BigDecimal.ZERO)
    }

    private fun format(v: BigDecimal): String =
        v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    companion object {
        private val OPS = setOf('+', '-', '×', '÷')
    }
}

/**
 * A calculator-style keypad for quick amount entry: digits, decimal point, operators,
 * equals and backspace (long-press clears). Buttons give haptic feedback and a springy press.
 *
 * @param onKey receives the key string; feed it to a [KeypadExpression].
 */
@Composable
fun NumberKeypad(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    showOperators: Boolean = true,
) {
    val rows = if (showOperators) {
        listOf(
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "-"),
            listOf(".", "0", "⌫", "+"),
        )
    } else {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫"))
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key -> KeypadKey(key, onKey, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KeypadKey(key: String, onKey: (String) -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        tween(120),
        label = "key-scale",
    )
    val isOp = key in setOf("+", "-", "×", "÷")
    val bg = when {
        isOp -> KoshTheme.colors.fill
        else -> KoshTheme.colors.cardSurface
    }
    val label = when (key) {
        "⌫" -> "Backspace"
        "-" -> "Minus"
        "+" -> "Plus"
        "×" -> "Multiply"
        "÷" -> "Divide"
        "." -> "Decimal point"
        else -> key
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, KoshTheme.colors.hairline, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onKey(key)
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (key == "⌫") {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null)
        } else {
            Text(
                key,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                color = if (isOp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Large display for an amount being typed: the expression and, when it contains operators,
 * the evaluated result underneath.
 */
@Composable
fun AmountInputDisplay(
    expression: String,
    valuePaise: Paise?,
    modifier: Modifier = Modifier,
    type: TxnType = TxnType.EXPENSE,
    showResult: Boolean = false,
) {
    val color = when (type) {
        TxnType.INCOME -> KoshTheme.colors.income
        TxnType.EXPENSE -> MaterialTheme.colorScheme.onSurface
        TxnType.TRANSFER -> KoshTheme.colors.transfer
    }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "₹" + expression.ifEmpty { "0" },
            style = AmountStyles.hero,
            color = if (expression.isEmpty()) KoshTheme.colors.subtleText else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showResult && valuePaise != null) {
            Text(
                "= " + Money.format(valuePaise),
                style = AmountStyles.medium,
                color = KoshTheme.colors.subtleText,
            )
        }
    }
}

