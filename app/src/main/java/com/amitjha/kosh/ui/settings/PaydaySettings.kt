package com.amitjha.kosh.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.data.prefs.Settings
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.finance.PayWeekend
import com.amitjha.kosh.ui.common.ActionRow
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How Kosh works out payday: the working day before a weekend or holiday due day, and which days count as off.
 * Shown under "Month starts on" when the month starts on a salary day.
 */
@Composable
fun PaydaySettings(s: Settings) {
    if (s.monthStartDay <= 1) return
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var editHolidays by remember { mutableStateOf(false) }

    SwitchRow(
        "Paid early on weekends and holidays",
        "Salary due on a weekend or holiday comes the working day before; once it's in, Kosh uses the day it arrived",
        s.paydayToWorkingDay,
        { v -> scope.launch { c.settings.setPaydayToWorkingDay(v) } },
        Icons.Outlined.Event,
    )
    if (!s.paydayToWorkingDay) return
    ChoiceField(
        "Weekend",
        PayWeekend.entries.toList(),
        s.payWeekend,
        { it.label },
        { w -> scope.launch { c.settings.setPayWeekend(w) } },
        leading = null,
    )
    SwitchRow(
        "India's public holidays",
        "Republic Day, Holi, Diwali, Christmas and the other national holidays",
        s.nationalHolidays,
        { v -> scope.launch { c.settings.setNationalHolidays(v) } },
    )
    ActionRow(
        "Your holidays",
        if (s.customHolidays.isEmpty()) "Add state or company holidays" else "${s.customHolidays.size} added",
        Icons.Outlined.CalendarMonth,
    ) { editHolidays = true }

    // The next three paydays, so the rule can be checked at a glance.
    val cycle = s.payCycle
    val names = s.holidayNames
    val first = cycle.periodContaining(cycle.nextPayday(Time.today())).month
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    Column(Modifier.padding(vertical = 10.dp)) {
        Text("Next paydays", style = MaterialTheme.typography.labelMedium)
        (0L..2L).map { first.plusMonths(it) }.forEach { m ->
            val why = cycle.reason(m, names)
            Text(
                fmt.format(cycle.payday(m)) + (why?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (why != null) KoshTheme.colors.ink else KoshTheme.colors.subtleText,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    if (editHolidays) {
        HolidaysDialog(
            days = s.customHolidays,
            onChange = { days -> scope.launch { c.settings.setCustomHolidays(days) } },
            onDismiss = { editHolidays = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolidaysDialog(days: Set<LocalDate>, onChange: (Set<LocalDate>) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your holidays") },
        text = {
            Column {
                Text(
                    "Days your employer or bank is closed besides weekends and national holidays. A payday on one of them " +
                        "moves to the working day before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                Spacer(Modifier.height(8.dp))
                val upcoming = days.filter { !it.isBefore(Time.today().minusMonths(2)) }.sorted()
                if (upcoming.isEmpty()) {
                    Text("None yet", style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
                }
                upcoming.forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.fullDate(d), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(days - d) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove ${Fmt.fullDate(d)}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picking = true }) { Text("Add a holiday") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = Time.today().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(days + Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
