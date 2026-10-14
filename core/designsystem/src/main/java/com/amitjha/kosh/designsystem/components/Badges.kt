package com.amitjha.kosh.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

/** A rounded square in the bank's brand colour with its initials, e.g. "HD" for HDFC. */
@Composable
fun BankBadge(bank: Bank, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val initials = when (bank) {
        Bank.OTHER -> "₹"
        else -> bank.shortName.take(if (bank.shortName.length <= 4) bank.shortName.length else 2).uppercase()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(bank.color, lerp(bank.color, Color.Black, 0.25f))))
            .semantics { contentDescription = bank.displayName },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * if (initials.length > 3) 0.24f else 0.32f).sp,
            maxLines = 1,
        )
    }
}

/**
 * A credit card face: gradient in the issuer's colour, chip, contactless mark, masked number,
 * name and a utilisation bar (outstanding / limit).
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
    val base = bank.color
    val brush = Brush.linearGradient(
        listOf(lerp(base, Color.Black, 0.15f), lerp(base, Color(0xFF14102E), 0.55f), lerp(base, Color.Black, 0.65f)),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .clip(RoundedCornerShape(22.dp))
            .background(brush)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.1f),
                        radius = size.width * 0.6f,
                    ),
                    radius = size.width * 0.6f,
                    center = Offset(size.width * 0.85f, size.height * 0.1f),
                )
                drawCircle(Color.White.copy(alpha = 0.05f), size.width * 0.45f, Offset(size.width * 0.05f, size.height * 1.1f))
            }
            .semantics {
                contentDescription = "${bank.displayName} card ${last4?.let { "ending $it" } ?: ""}"
            }
            .padding(20.dp),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(bank.shortName, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(cardName, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 38.dp, height = 28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFF3D27A), Color(0xFFC9A04A))))
                        .drawBehind {
                            val c = Color(0x55000000)
                            drawLine(c, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), 1.5f)
                            drawLine(c, Offset(size.width * 2 / 3, 0f), Offset(size.width * 2 / 3, size.height), 1.5f)
                            drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
                        },
                )
                Spacer(Modifier.size(10.dp))
                Icon(Icons.Filled.Contactless, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "••••  ••••  ••••  ${last4 ?: "••••"}",
                color = Color.White,
                style = AmountStyles.medium.copy(letterSpacing = 1.5.sp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (holderName ?: "CARD HOLDER").uppercase(),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    if (outstanding != null && creditLimit != null && creditLimit > 0) {
                        Spacer(Modifier.height(6.dp))
                        UtilisationBar(outstanding.toFloat() / creditLimit, Modifier.fillMaxWidth(0.8f))
                    }
                }
                if (outstanding != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Outstanding", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
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
        fraction >= 0.75f -> colors.alert
        fraction >= 0.3f -> colors.warning
        else -> colors.income
    }
    Box(
        modifier
            .height(5.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.2f))
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
        InvestmentType.FD -> "FD" to colors.gold
        InvestmentType.EPF -> "EPF" to colors.good
        InvestmentType.NPS -> "NPS" to colors.tip
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
