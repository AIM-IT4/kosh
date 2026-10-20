package com.amitjha.kosh.ui.common

import com.amitjha.kosh.designsystem.components.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.components.AmountInputDisplay
import com.amitjha.kosh.designsystem.components.KeypadExpression
import com.amitjha.kosh.designsystem.components.KoshTopBar
import com.amitjha.kosh.designsystem.components.NumberKeypad
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Standard screen: top bar, optional snackbar host, edge-to-edge content. */
@Composable
fun KoshScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(Modifier) {
                KoshTopBar(title = title, subtitle = subtitle, onBack = onBack, actions = actions)
            }
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it, Modifier.navigationBarsPadding()) } },
        floatingActionButton = floating,
        content = content,
    )
}

/** Haptic tick for confirmations. */
@Composable
fun rememberHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
}

/** A bottom sheet with the calculator keypad for fast amount entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountSheet(
    title: String,
    initial: Paise?,
    onDismiss: () -> Unit,
    onConfirm: (Paise) -> Unit,
    type: TxnType = TxnType.EXPENSE,
    allowZero: Boolean = false,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val expr = remember { KeypadExpression(initial?.takeIf { it > 0 }?.toRupeeInput() ?: "") }
    var text by remember { mutableStateOf(expr.text) }
    val haptic = rememberHaptic()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val value = remember(text) { expr.valuePaise() }
            AmountInputDisplay(expression = text, valuePaise = value, type = type, showResult = expr.hasOperator)
            NumberKeypad(onKey = { text = expr.press(it) })
            PrimaryButton(
                text = "Done",
                enabled = value != null && (allowZero || value > 0),
                onClick = {
                    haptic()
                    onConfirm(value ?: 0)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A tappable field showing an amount; opens [AmountSheet]. */
@Composable
fun AmountField(
    label: String,
    value: Paise?,
    onValue: (Paise) -> Unit,
    modifier: Modifier = Modifier,
    allowZero: Boolean = true,
    placeholder: String = "Tap to enter",
) {
    var open by remember { mutableStateOf(false) }
    FieldBox(label = label, modifier = modifier, onClick = { open = true }) {
        Text(
            if (value != null) com.amitjha.kosh.model.Money.format(value) else tr(placeholder),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (value != null) MaterialTheme.colorScheme.onSurface else KoshTheme.colors.subtleText,
        )
    }
    if (open) {
        AmountSheet(title = label, initial = value, allowZero = allowZero, onDismiss = { open = false }, onConfirm = {
            onValue(it)
            open = false
        })
    }
}

/** Card-like field container used for pickers. */
@Composable
fun FieldBox(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: ImageVector = Icons.Outlined.ExpandMore,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = KoshTheme.colors.cardSurface,
        border = BorderStroke(1.dp, KoshTheme.colors.divider),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(label), style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                Spacer(Modifier.height(2.dp))
                content()
            }
            Icon(trailing, contentDescription = null, tint = KoshTheme.colors.subtleText)
        }
    }
}

/** A date field with the Material date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    FieldBox(label = label, modifier = modifier, onClick = { open = true }, trailing = Icons.Outlined.CalendarMonth) {
        Text(Fmt.fullDate(date), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** A single-choice picker shown as a bottom sheet list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose",
    leading: (@Composable (T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    FieldBox(label = label, modifier = modifier, onClick = { open = true }) {
        Text(
            selected?.let(optionLabel) ?: tr(placeholder),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected != null) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Text(tr(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(options.size) { i ->
                    val o = options[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(o)
                                open = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (leading != null) {
                            leading(o)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(optionLabel(o), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (o == selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A themed text field. */
@Composable
fun KoshTextField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supporting: String? = null,
    prefix: String? = null,
    suffix: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(tr(label)) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supporting?.let { { Text(tr(it)) } },
        prefix = prefix?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = KoshTheme.colors.cardSurface,
            focusedContainerColor = KoshTheme.colors.cardSurface,
            unfocusedBorderColor = KoshTheme.colors.divider,
        ),
    )
}

/** A settings-style row with a switch. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChecked(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = KoshTheme.colors.subtleText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(tr(subtitle), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = KoshTheme.colors.accent,
                checkedBorderColor = KoshTheme.colors.accent,
                uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                uncheckedTrackColor = KoshTheme.colors.fill,
                uncheckedBorderColor = KoshTheme.colors.hairline,
            ),
        )
    }
}

/** A plain settings row that navigates or acts on tap. */
@Composable
fun ActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = KoshTheme.colors.subtleText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else KoshTheme.colors.subtleText)
            if (subtitle != null) Text(tr(subtitle), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        }
        trailing?.invoke()
    }
}

/** A confirmation dialog. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = { Text(tr(message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(tr(confirmLabel), color = if (destructive) KoshTheme.colors.alert else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** A dialog asking for one line of text, such as the user's name. [valid] decides when Save is enabled. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    valid: (String) -> Boolean = { true },
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = { KoshTextField(text, { text = it }, label, keyboardType = keyboardType, supporting = supporting) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = valid(text.trim())) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Asks what Home should call the user. Saving an empty name goes back to the plain greeting. */
@Composable
fun NameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    TextInputDialog(
        title = "Your name",
        label = "Name",
        initial = current,
        supporting = "Home greets you with it. It stays on this phone.",
        onSave = onSave,
        onDismiss = onDismiss,
        valid = { it.length <= com.amitjha.kosh.data.prefs.SettingsStore.MAX_NAME },
    )
}

/** Vertical form layout with consistent spacing. */
@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A pill label. */
@Composable
fun Pill(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Fades content in once a condition becomes true. */
@Composable
fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible) { content() }
}

/** Full-screen centered box (for empty or loading states). */
@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
