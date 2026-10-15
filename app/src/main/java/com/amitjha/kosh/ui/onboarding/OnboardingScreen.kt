package com.amitjha.kosh.ui.onboarding

import com.amitjha.kosh.designsystem.theme.KoshText
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.KoshLogo
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.BrandPalette
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.domain.InvestmentSpec
import com.amitjha.kosh.finance.Compounding
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.sms.ImportResult
import com.amitjha.kosh.ui.accounts.BankPicker
import com.amitjha.kosh.ui.common.AmountField
import com.amitjha.kosh.ui.common.DateField
import com.amitjha.kosh.ui.common.FormColumn
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.LocalSettings
import com.amitjha.kosh.ui.common.bankEnum
import com.amitjha.kosh.ui.common.label
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.ui.settings.describe
import com.amitjha.kosh.ui.settings.hasSmsPermission
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.amitjha.kosh.ui.common.rememberSmsPermissionRequest

private const val STEPS = 7

@Composable
fun OnboardingScreen() {
    val c = LocalContainer.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val finish: () -> Unit = { scope.launch { c.settings.setOnboardingDone(true) } }
    BackHandler(enabled = step > 0) { step-- }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StepDots(step, STEPS, Modifier.weight(1f))
            if (step in 1 until STEPS - 1) TextButton(onClick = { step++ }) { Text("Skip") }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(260)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
            },
            label = "onboarding",
            modifier = Modifier.weight(1f),
        ) { s ->
            when (s) {
                0 -> Welcome { step = 1 }
                1 -> AccountsStep(card = false) { step = 2 }
                2 -> AccountsStep(card = true) { step = 3 }
                3 -> InvestmentsStep { step = 4 }
                4 -> PermissionsStep { step = 5 }
                5 -> ImportStep { step = 6 }
                else -> DoneStep(finish)
            }
        }
    }
}

@Composable
private fun StepDots(step: Int, total: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val w by animateDpAsState(if (i == step) 22.dp else 8.dp, label = "dot")
            val color by animateColorAsState(
                if (i <= step) MaterialTheme.colorScheme.primary else KoshTheme.colors.divider,
                label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun StepPage(
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (icon != null) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(KoshTheme.colors.ink),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = KoshTheme.colors.onInk) }
                Spacer(Modifier.height(16.dp))
            }
            Text(title, style = KoshText.screenTitle)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
            Spacer(Modifier.height(20.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton(primary, onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth())
        if (secondary != null && onSecondary != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondary) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        KoshLogo(size = 108.dp)
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Kosh", style = KoshText.screenTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Your expenses, income, cards and investments in one private place.",
            style = MaterialTheme.typography.bodyLarge, color = KoshTheme.colors.subtleText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        ValueProp(Icons.Outlined.Sms, "Automatic tracking", "Bank SMS become transactions and card bills automatically.")
        ValueProp(Icons.AutoMirrored.Outlined.TrendingUp, "Wealth on autopilot", "FD, EPF and NPS values update by themselves.")
        ValueProp(Icons.Outlined.Lock, "Private by design", "Everything stays on your phone. Insights are computed offline.")
        Spacer(Modifier.weight(1f))
        PrimaryButton("Get started", onClick = onNext, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ValueProp(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
        }
    }
}

@Composable
private fun AccountsStep(card: Boolean, onNext: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val all by c.accounts.observeAccounts().collectAsStateWithLifecycle(initialValue = emptyList())
    val added = all.filter { it.isCard == card }
    var bank by remember { mutableStateOf(Bank.HDFC) }
    var name by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf<Long?>(null) }
    var limit by remember { mutableStateOf<Long?>(null) }
    var statementDay by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("") }
    val defaultName = "${bank.shortName} ${if (card) "Credit Card" else "Savings"}"

    StepPage(
        title = if (card) "Your credit cards" else "Your bank accounts",
        subtitle = if (card) {
            "Add each card with its limit and dates, so Kosh can track dues and remind you before bills are due."
        } else {
            "Add the accounts you use with today's balance. The last 4 digits let Kosh match bank SMS to each account."
        },
        primary = if (added.isEmpty()) "Continue" else "Continue with ${added.size}",
        onPrimary = onNext,
    ) {
        added.forEach { a ->
            KoshCard(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BankBadge(a.bank, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(a.account.label(), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    AmountText(if (card) a.outstanding else a.balance, style = AmountStyles.small)
                }
            }
        }
        FormColumn {
            BankPicker(bank) { bank = it }
            KoshTextField(name, { name = it }, "Name", supporting = "Leave empty for “$defaultName”")
            KoshTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, "Last 4 digits", keyboardType = KeyboardType.Number)
            AmountField(if (card) "Amount owed today" else "Balance today", amount, { amount = it })
            if (card) {
                AmountField("Credit limit", limit, { limit = it })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KoshTextField(statementDay, { statementDay = it.filter(Char::isDigit).take(2) }, "Statement day", Modifier.weight(1f),
                        keyboardType = KeyboardType.Number)
                    KoshTextField(dueDay, { dueDay = it.filter(Char::isDigit).take(2) }, "Due day", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                }
            }
            val addGuard = remember { com.amitjha.kosh.ui.common.ActionGuard() }
            OutlinedButton(
                onClick = onClick@{
                    if (!addGuard.tryStart()) return@onClick
                    haptic()
                    scope.launch {
                        try {
                            c.accounts.add(
                                AccountEntity(
                                    name = name.trim().ifEmpty { defaultName },
                                    type = if (card) AccountType.CREDIT_CARD else AccountType.BANK,
                                    bank = bank.name,
                                    last4 = last4.takeIf { it.length == 4 },
                                    openingBalance = amount ?: 0,
                                    creditLimit = if (card) limit else null,
                                    statementDay = statementDay.toIntOrNull()?.coerceIn(1, 31),
                                    dueDay = dueDay.toIntOrNull()?.coerceIn(1, 31),
                                    colorArgb = bank.brandColor,
                                ),
                            )
                            name = ""
                            last4 = ""
                            amount = null
                            limit = null
                            statementDay = ""
                            dueDay = ""
                        } finally {
                            addGuard.finish()
                        }
                    }
                },
                enabled = last4.isEmpty() || last4.length == 4,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (card) "Add card" else "Add account") }
        }
    }
}

@Composable
private fun InvestmentsStep(onNext: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(listOf<String>()) }
    // FD
    var fdBank by remember { mutableStateOf(Bank.HDFC) }
    var fdAmount by remember { mutableStateOf<Long?>(null) }
    var fdRate by remember { mutableStateOf("7.0") }
    var fdStart by remember { mutableStateOf(Time.today()) }
    var fdMaturity by remember { mutableStateOf(Time.today().plusYears(1)) }
    // EPF
    var epfBalance by remember { mutableStateOf<Long?>(null) }
    var epfEmployee by remember { mutableStateOf<Long?>(null) }
    var epfEmployer by remember { mutableStateOf<Long?>(null) }

    StepPage(
        title = "Investments",
        subtitle = "Enter an FD or your EPF once and Kosh keeps the value current. Add NPS from the Wealth tab (it looks up live NAVs).",
        primary = "Continue",
        onPrimary = onNext,
        icon = Icons.AutoMirrored.Outlined.TrendingUp,
    ) {
        added.forEach {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = KoshTheme.colors.good)
                Spacer(Modifier.width(8.dp))
                Text(it)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { mode = "FD" }, Modifier.weight(1f)) { Text("+ Fixed deposit") }
            OutlinedButton(onClick = { mode = "EPF" }, Modifier.weight(1f)) { Text("+ EPF") }
        }
        when (mode) {
            "FD" -> FormColumn {
                BankPicker(fdBank) { fdBank = it }
                AmountField("Deposit amount", fdAmount, { fdAmount = it }, allowZero = false)
                KoshTextField(fdRate, { fdRate = it }, "Interest rate", keyboardType = KeyboardType.Decimal, suffix = "% p.a.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateField("Start", fdStart, { fdStart = it }, Modifier.weight(1f))
                    DateField("Maturity", fdMaturity, { fdMaturity = it }, Modifier.weight(1f))
                }
                val rate = fdRate.toDoubleOrNull()
                val fdGuard = remember { com.amitjha.kosh.ui.common.ActionGuard() }
                PrimaryButton("Add FD", enabled = (fdAmount ?: 0) > 0 && rate != null && fdMaturity.isAfter(fdStart), modifier = Modifier.fillMaxWidth(),
                    onClick = onClick@{
                        // Capture the inputs now: a second tap after the fields reset must not crash or add twice.
                        val amount = fdAmount ?: return@onClick
                        val pct = rate ?: return@onClick
                        val bank = fdBank
                        val start = fdStart
                        val maturity = fdMaturity
                        if (!fdGuard.tryStart()) return@onClick
                        scope.launch {
                            try {
                                c.investments.addFd(
                                    "${bank.shortName} FD",
                                    bank.shortName,
                                    InvestmentSpec.Fd(amount, pct, start, maturity, Compounding.QUARTERLY),
                                )
                                added = added + "${bank.shortName} FD"
                                mode = null
                                fdAmount = null
                            } finally {
                                fdGuard.finish()
                            }
                        }
                    })
            }
            "EPF" -> FormColumn {
                AmountField("Current EPF balance", epfBalance, { epfBalance = it })
                AmountField("Your monthly contribution", epfEmployee, { epfEmployee = it })
                AmountField("Employer's EPF share per month", epfEmployer, { epfEmployer = it })
                val epfGuard = remember { com.amitjha.kosh.ui.common.ActionGuard() }
                PrimaryButton("Add EPF", enabled = epfBalance != null, modifier = Modifier.fillMaxWidth(), onClick = onClick@{
                    val balance = epfBalance ?: return@onClick
                    val employee = epfEmployee ?: 0
                    val employer = epfEmployer ?: 0
                    if (!epfGuard.tryStart()) return@onClick
                    scope.launch {
                        try {
                            c.investments.addEpf("EPF", InvestmentSpec.Epf(balance, Time.today(), employee, employer))
                            added = added + "EPF"
                            mode = null
                        } finally {
                            epfGuard.finish()
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var smsGranted by remember { mutableStateOf(context.hasSmsPermission()) }
    val requestSms = rememberSmsPermissionRequest { smsGranted = true }
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    StepPage(
        title = "Track automatically",
        subtitle = "Allow Kosh to read bank SMS so transactions, balances and card bills appear by themselves.",
        primary = "Continue",
        onPrimary = onNext,
        icon = Icons.Outlined.Sms,
    ) {
        KoshCard(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("SMS never leave your phone", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Only messages from banks are read: OTPs and personal messages are ignored, and nothing is uploaded anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
            )
        }
        Spacer(Modifier.height(16.dp))
        PermissionRow(Icons.Outlined.Sms, "Read bank SMS", smsGranted) {
            requestSms()
        }
        PermissionRow(Icons.Outlined.NotificationsActive, "Notifications for new spends and bill reminders", notifGranted) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, granted: Boolean, onAllow: () -> Unit) {
    KoshCard(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (granted) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Allowed", tint = KoshTheme.colors.good)
            } else {
                TextButton(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun ImportStep(onNext: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("Reading your bank SMS…") }
    LaunchedEffect(Unit) {
        running = true
        try {
            if (context.hasSmsPermission()) {
                result = c.smsImporter.importInbox()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note = e.message ?: "Import failed. Try again from More."
        } finally {
            running = false
        }
    }
    StepPage(
        title = if (running) phase else "Your history",
        subtitle = if (context.hasSmsPermission()) "Kosh looks back 180 days and skips anything it has already seen."
        else "SMS access wasn't allowed. You can scan later from More › Scan inbox now.",
        primary = "Continue",
        onPrimary = onNext,
        primaryEnabled = !running,
        icon = Icons.Outlined.AutoAwesome,
    ) {
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText) }
        result?.let { r ->
            KoshCard {
                Text(r.describe(), fontWeight = FontWeight.SemiBold)
                if (r.inserted > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("They're waiting in Review so you can confirm categories.", style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText)
                }
            }
        }
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    val c = LocalContainer.current
    val accounts by c.accounts.observeAccounts().collectAsStateWithLifecycle(initialValue = emptyList())
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.7f))
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = KoshTheme.colors.good, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(16.dp))
        Text("You're all set", style = KoshText.screenTitle)
        Spacer(Modifier.height(8.dp))
        Text(
            "${accounts.size} account${if (accounts.size == 1) "" else "s"} ready. New bank SMS will be picked up automatically.",
            style = MaterialTheme.typography.bodyLarge,
            color = KoshTheme.colors.subtleText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        accounts.take(4).forEach { a ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BankBadge(a.account.bankEnum(), size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(a.account.label())
            }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Open Kosh", onClick = onFinish, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
    }
}

