package com.amitjha.kosh.ui.planning

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.components.FanBand
import com.amitjha.kosh.designsystem.components.FanChart
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.finance.GoalSimulation
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.repo.GoalOutlook
import com.amitjha.kosh.ui.common.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/** "82% chance by Mar 2029" chip; expands to the fan chart and the saving needed for 80% confidence. */
@Composable
fun GoalOutlookSection(o: GoalOutlook, deadline: LocalDate, hidden: Boolean, onInflation: (Double) -> Unit) {
    var expanded by rememberSaveable(o.goalId) { mutableStateOf(false) }
    val r = o.result
    val pct = (r.probability * 100).roundToInt()
    val color = when {
        pct >= 80 -> KoshTheme.colors.good
        pct >= 50 -> KoshTheme.colors.warning
        else -> KoshTheme.colors.alert
    }
    fun money(p: Long) = if (hidden) "₹ ••••" else Money.format(p, showPaise = false)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(KoshTheme.colors.cardSurface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$pct% chance",
                color = color,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "by ${monthYear.format(deadline)} at ${money(o.monthlySaving)}/month",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "Hide outlook" else "Show outlook", tint = KoshTheme.colors.subtleText)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val today = LocalDate.now()
                FanChart(
                    bands = r.fan.map {
                        FanBand(monthYear.format(today.plusMonths(it.month.toLong())), it.p10, it.p50, it.p90, it.target)
                    },
                    hidden = hidden,
                )
                Text(
                    "Save ${money(r.monthlyForConfidence)}/month for ${(GoalSimulation.CONFIDENCE * 100).roundToInt()}% confidence.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Simulated ${GoalSimulation.DEFAULT_PATHS} market paths at ${Fmt.pct(o.result.volatilityPct, 1)} yearly volatility " +
                        "for this return. Half end above ${money(r.median)}; 1 in 10 below ${money(r.p10)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Target", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    FilterChip(selected = o.inflationPct == 0.0, onClick = { onInflation(0.0) }, label = { Text("Fixed") })
                    FilterChip(selected = o.inflationPct > 0.0, onClick = { onInflation(6.0) }, label = { Text("Grows 6%/yr") })
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
