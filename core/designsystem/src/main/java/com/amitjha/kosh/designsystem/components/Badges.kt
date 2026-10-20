package com.amitjha.kosh.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Paise

/** A bank's colour as a Compose [Color]. */
val Bank.color: Color get() = Color(brandColor)

/** A tinted rounded square with the bank's initials in its brand colour, e.g. "HDFC". */
@Composable
fun BankBadge(bank: Bank, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val initials = when (bank) {
        Bank.OTHER -> "₹"
        // long names whose first two letters would read as "ID", "IN", "UN"
        Bank.IDFC -> "IDFC"
        Bank.INDUSIND -> "IIB"
        Bank.UNION -> "UBI"
        else -> bank.shortName.take(if (bank.shortName.length <= 4) bank.shortName.length else 2).uppercase()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.33f))
            .background(bank.color.copy(alpha = 0.10f))
            .semantics { contentDescription = bank.displayName },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = bank.color,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * if (initials.length > 3) 0.24f else 0.32f).sp,
            maxLines = 1,
        )
    }
}

/**
 * A minimal credit card face: flat ink, bank wordmark, card name, masked number, outstanding
 * amount and a thin utilisation bar (outstanding / limit).
 */
@Composable
fun CreditCardVisual(
    bank: Bank,
    cardName: String,
    last4: String?,
    modifier: Modifier = Modifier,
    holderName: String? = null,
    outstanding: Paise? = null,
    creditLimit: Paise? = null,
    hidden: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .clip(RoundedCornerShape(18.dp))
            .background(KoshTheme.colors.emphasis)
            .then(if (KoshTheme.colors.isDark) Modifier.border(1.dp, KoshTheme.colors.hairline, RoundedCornerShape(18.dp)) else Modifier)
            .semantics {
                contentDescription = "${bank.displayName} card ${last4?.let { "ending $it" } ?: ""}"
            }
            .padding(20.dp),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(bank.shortName, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(bank.color))
            }
            Text(cardName, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                "••••  ${last4 ?: "••••"}",
                color = Color.White.copy(alpha = 0.85f),
                style = AmountStyles.medium.copy(letterSpacing = 2.sp),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    if (holderName != null) {
                        Text(holderName, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    if (outstanding != null && creditLimit != null && creditLimit > 0) {
                        Spacer(Modifier.height(6.dp))
                        UtilisationBar(outstanding.toFloat() / creditLimit, Modifier.fillMaxWidth(0.7f))
                    }
                }
                if (outstanding != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Outstanding", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                        AmountText(outstanding, color = Color.White, hidden = hidden, style = AmountStyles.medium, compact = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun UtilisationBar(fraction: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(800), label = "util")
    val colors = KoshTheme.colors
    val barColor = when {
        fraction >= 0.75f -> Color(0xFFF87171)
        fraction >= 0.3f -> Color(0xFFFBBF24)
        else -> Color(0xFF34D399)
    }
    Box(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .drawBehind {
                drawRoundRect(barColor, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height / 2))
            }
            .semantics { contentDescription = "Credit used ${(fraction * 100).toInt()} percent" },
    )
}

/** A small label for FD / EPF / NPS holdings. */
@Composable
fun InvestmentTypeBadge(type: InvestmentType, modifier: Modifier = Modifier) {
    val colors = KoshTheme.colors
    val (label, color) = when (type) {
        InvestmentType.FD -> "FD" to colors.ink
        InvestmentType.EPF -> "EPF" to colors.accent
        InvestmentType.NPS -> "NPS" to colors.tip
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
