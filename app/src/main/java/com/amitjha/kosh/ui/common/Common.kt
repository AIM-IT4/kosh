package com.amitjha.kosh.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.prefs.Settings
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.designsystem.components.InsightSeverity
import com.amitjha.kosh.finance.Severity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.TxnSource
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The app's dependency container, provided at the root of the UI. */
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Current settings, provided at the root so any screen can read privacy mode etc. */
val LocalSettings = compositionLocalOf { Settings() }

/** Privacy mode: amounts render as "₹ ••••" when true. */
val LocalHideAmounts = compositionLocalOf { false }

/** Creates (or reuses) a ViewModel built from the [AppContainer]. Pass a [key] when arguments differ. */
@Composable
inline fun <reified VM : ViewModel> koshViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

object Fmt {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val fullDay = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun date(d: LocalDate): String = if (d.year == LocalDate.now().year) dayMonth.format(d) else dayMonthYear.format(d)
    fun fullDate(d: LocalDate): String = dayMonthYear.format(d)
    fun month(m: YearMonth): String = monthYear.format(m)

    /** "October 2026" for calendar months, "October 2026 · 25 Sep – 24 Oct" when the month starts on another day. */
    fun periodLabel(p: com.amitjha.kosh.util.Period): String {
        val name = monthYear.format(p.label)
        if (p.start.dayOfMonth == 1) return name
        return "$name · ${dayMonth.format(p.start)} – ${dayMonth.format(p.endExclusive.minusDays(1))}"
    }
    fun shortMonth(m: YearMonth): String = shortMonth.format(m)
    fun time(millis: Long): String = time.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

    /** "Today", "Yesterday", or "Mon, 6 Oct". */
    fun dayLabel(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (ChronoUnit.DAYS.between(d, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> if (d.year == today.year) fullDay.format(d) else dayMonthYear.format(d)
    }

    fun relativeDays(days: Long): String = when {
        days < -1 -> "${-days} days overdue"
        days == -1L -> "1 day overdue"
        days == 0L -> "Due today"
        days == 1L -> "Due tomorrow"
        else -> "Due in $days days"
    }

    fun pct(v: Double, digits: Int = 1): String = String.format(Locale.ENGLISH, "%.${digits}f%%", v)

    fun ago(millis: Long): String {
        if (millis <= 0) return "never"
        val mins = (System.currentTimeMillis() - millis) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 24 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (24 * 60)} d ago"
        }
    }

    /** e.g. "Tuesday, 14 October". */
    fun todayLong(): String = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH).format(LocalDate.now())

    fun greeting(): String = when (java.time.LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
}

fun CategoryEntity?.color(): Color = Color(this?.colorArgb ?: 0xFFADB5BD)

fun AccountEntity.bankEnum(): Bank = Bank.fromName(bank)

fun AccountEntity.label(): String = if (last4 != null && !name.contains(last4)) "$name ••$last4" else name

fun AccountType.label(): String = when (this) {
    AccountType.BANK -> "Bank account"
    AccountType.CREDIT_CARD -> "Credit card"
    AccountType.CASH -> "Cash"
    AccountType.WALLET -> "Wallet"
}

fun TxnSource.label(): String = when (this) {
    TxnSource.MANUAL -> "Added by you"
    TxnSource.SMS -> "Imported from SMS"
    TxnSource.EMAIL -> "Imported from email"
    TxnSource.AUTO -> "Added automatically"
}

fun Severity.toUi(): InsightSeverity = InsightSeverity.valueOf(name)

/** Parses a rupee amount typed in a text field ("1,20,000.50") into paise. */
fun parseRupees(text: String): Long? = com.amitjha.kosh.model.Money.parse(text.trim())

fun Long.toRupeeInput(): String {
    val r = this / 100
    val p = this % 100
    return if (p == 0L) r.toString() else "$r.${p.toString().padStart(2, '0')}"
}
