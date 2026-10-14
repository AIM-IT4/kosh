package com.amitjha.kosh.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.designsystem.components.AmountInputDisplay
import com.amitjha.kosh.designsystem.components.BalanceHeroCard
import com.amitjha.kosh.designsystem.components.BankBadge
import com.amitjha.kosh.designsystem.components.BarChart
import com.amitjha.kosh.designsystem.components.BarGroup
import com.amitjha.kosh.designsystem.components.BudgetProgressBar
import com.amitjha.kosh.designsystem.components.CategoryIcon
import com.amitjha.kosh.designsystem.components.ChartLegend
import com.amitjha.kosh.designsystem.components.ChartPoint
import com.amitjha.kosh.designsystem.components.ChartSlice
import com.amitjha.kosh.designsystem.components.CreditCardVisual
import com.amitjha.kosh.designsystem.components.DateHeader
import com.amitjha.kosh.designsystem.components.DonutChart
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.FilterChipsRow
import com.amitjha.kosh.designsystem.components.GoalCard
import com.amitjha.kosh.designsystem.components.InsightCard
import com.amitjha.kosh.designsystem.components.InsightSeverity
import com.amitjha.kosh.designsystem.components.InvestmentTypeBadge
import com.amitjha.kosh.designsystem.components.KeypadExpression
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.KoshLogo
import com.amitjha.kosh.designsystem.components.KoshTopBar
import com.amitjha.kosh.designsystem.components.LineAreaChart
import com.amitjha.kosh.designsystem.components.LoadingShimmer
import com.amitjha.kosh.designsystem.components.LogoShape
import com.amitjha.kosh.designsystem.components.MonthSwitcher
import com.amitjha.kosh.designsystem.components.NumberKeypad
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.ProgressRing
import com.amitjha.kosh.designsystem.components.SectionHeader
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.components.StackedBar
import com.amitjha.kosh.designsystem.components.StatChip
import com.amitjha.kosh.designsystem.components.TransactionRow
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.model.Bank
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType

private val sampleSlices = listOf(
    ChartSlice("Food & Dining", 1_245_000, Color(0xFFFF7A59)),
    ChartSlice("Shopping", 860_000, Color(0xFFE056FD)),
    ChartSlice("Bills", 640_000, Color(0xFF748FFC)),
    ChartSlice("Transport", 410_000, Color(0xFF4DABF7)),
    ChartSlice("Other", 220_000, Color(0xFFADB5BD)),
)

private val sampleBars = listOf(
    BarGroup("May", 12_000_000, 8_400_000),
    BarGroup("Jun", 12_000_000, 9_100_000),
    BarGroup("Jul", 12_500_000, 7_800_000),
    BarGroup("Aug", 12_500_000, 10_200_000),
    BarGroup("Sep", 13_000_000, 8_900_000),
    BarGroup("Oct", 13_000_000, 6_100_000),
)

private val samplePoints = listOf(31, 32, 31.5, 34, 36, 35.2, 38, 41, 40.4, 44).mapIndexed { i, v ->
    ChartPoint("Week ${i + 1}", (v.toDouble() * 10_000_000).toLong())
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = KoshTheme.colors.subtleText)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ShowcaseSurface(dark: Boolean, content: @Composable () -> Unit) {
    KoshTheme(darkTheme = dark) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
        ) { content() }
    }
}

@Preview(name = "Hero & stats — dark", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HeroDark() = ShowcaseSurface(dark = true) { HeroContent() }

@Preview(name = "Hero & stats — light", widthDp = 400)
@Composable
private fun HeroLight() = ShowcaseSurface(dark = false) { HeroContent() }

@Composable
private fun HeroContent() {
    var hidden by remember { mutableStateOf(false) }
    KoshTopBar(title = "Good evening, Amit", subtitle = "Tuesday, 14 Oct")
    BalanceHeroCard(
        label = "Net worth",
        amount = 4_825_634_50,
        deltaPaise = 1_250_000,
        trend = listOf(3f, 3.2f, 3.1f, 3.6f, 3.9f, 4.1f, 4.4f, 4.8f),
        hidden = hidden,
        onToggleHidden = { hidden = !hidden },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip("Income", 13_000_000, Modifier.weight(1f), onDark = true, hidden = hidden)
            StatChip("Spent", 6_100_000, Modifier.weight(1f), onDark = true, hidden = hidden)
            StatChip("Saved", 6_900_000, Modifier.weight(1f), onDark = true, hidden = hidden)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatChip("Spent this month", 6_100_000, Modifier.weight(1f), type = TxnType.EXPENSE, deltaPaise = -1_200_000, positiveIsGood = false)
        StatChip("Income", 13_000_000, Modifier.weight(1f), type = TxnType.INCOME, deltaPaise = 500_000)
    }
}

@Preview(name = "Transactions — dark", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TransactionsDark() = ShowcaseSurface(dark = true) { TransactionsContent() }

@Preview(name = "Transactions — light", widthDp = 400)
@Composable
private fun TransactionsLight() = ShowcaseSurface(dark = false) { TransactionsContent() }

@Composable
private fun TransactionsContent() {
    var selected by remember { mutableStateOf(setOf("All")) }
    FilterChipsRow(listOf("All", "Expenses", "Income", "Cards", "Needs review"), selected, { selected = setOf(it) })
    DateHeader("Today", netPaise = -184_900)
    TransactionRow("Swiggy", "HDFC ••4521 · 8:42 PM", 64_900, TxnType.EXPENSE, "restaurant", Color(0xFFFF7A59),
        source = TxnSource.SMS, unreviewed = true)
    TransactionRow("Amazon", "ICICI Card ••1234 · 1:15 PM", 1_200_000, TxnType.EXPENSE, "shopping_bag", Color(0xFFE056FD),
        source = TxnSource.SMS)
    DateHeader("Yesterday", netPaise = 11_850_000)
    TransactionRow("Salary — Acme Corp", "Kotak ••7788 · 10:02 AM", 12_500_000, TxnType.INCOME, "payments", Color(0xFF2F9E44),
        source = TxnSource.SMS)
    TransactionRow("HDFC card bill", "DBS ••0099 → HDFC Card", 650_000, TxnType.TRANSFER, "swap_horiz", Color(0xFF868E96))
}

@Preview(name = "Charts — dark", widthDp = 400, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChartsDark() = ShowcaseSurface(dark = true) { ChartsContent() }

@Preview(name = "Charts — light", widthDp = 400, heightDp = 900)
@Composable
private fun ChartsLight() = ShowcaseSurface(dark = false) { ChartsContent() }

@Composable
private fun ChartsContent() {
    var tab by remember { mutableIntStateOf(0) }
    SegmentedTabs(listOf("Month", "Quarter", "Year"), tab, { tab = it })
    Spacer(Modifier.height(12.dp))
    MonthSwitcher("Oct 2026", onPrevious = {}, onNext = {}, canGoNext = false)
    Spacer(Modifier.height(12.dp))
    KoshCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(sampleSlices, size = 150.dp, thickness = 18.dp, centerLabel = "Spent")
            Spacer(Modifier.width(16.dp))
            ChartLegend(sampleSlices, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(12.dp))
    KoshCard(Modifier.fillMaxWidth()) { BarChart(sampleBars) }
    Spacer(Modifier.height(12.dp))
    KoshCard(Modifier.fillMaxWidth()) { LineAreaChart(samplePoints) }
    Spacer(Modifier.height(12.dp))
    StackedBar(sampleSlices)
}

@Preview(name = "Cards & badges — dark", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardsDark() = ShowcaseSurface(dark = true) { CardsContent() }

@Preview(name = "Cards & badges — light", widthDp = 400)
@Composable
private fun CardsLight() = ShowcaseSurface(dark = false) { CardsContent() }

@Composable
private fun CardsContent() {
    CreditCardVisual(Bank.HDFC, "Regalia Gold", "4521", holderName = "Amit Kumar Jha",
        outstanding = 3_450_000, creditLimit = 30_000_000)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BankBadge(Bank.ICICI); BankBadge(Bank.HDFC); BankBadge(Bank.KOTAK); BankBadge(Bank.DBS); BankBadge(Bank.OTHER)
        InvestmentTypeBadge(InvestmentType.FD); InvestmentTypeBadge(InvestmentType.EPF); InvestmentTypeBadge(InvestmentType.NPS)
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryIcon("restaurant", Color(0xFFFF7A59)); CategoryIcon("flight", Color(0xFF3BC9DB))
        CategoryIcon("savings", Color(0xFF0CA678)); CategoryIcon("unknown", Color(0xFFADB5BD))
    }
}

@Preview(name = "Budgets, goals & insights — dark", widthDp = 400, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlanningDark() = ShowcaseSurface(dark = true) { PlanningContent() }

@Preview(name = "Budgets, goals & insights — light", widthDp = 400, heightDp = 900)
@Composable
private fun PlanningLight() = ShowcaseSurface(dark = false) { PlanningContent() }

@Composable
private fun PlanningContent() {
    SectionHeader("Budgets", actionLabel = "See all", onAction = {})
    Section("Food & Dining · ₹12,450 of ₹15,000") {
        BudgetProgressBar(spent = 1_245_000, limit = 1_500_000, projected = 1_720_000)
    }
    Section("Shopping · over budget") { BudgetProgressBar(spent = 1_860_000, limit = 1_500_000) }
    GoalCard("Goa trip", saved = 4_200_000, target = 8_000_000, icon = Icons.Filled.BeachAccess,
        hint = "₹6,300/month to reach by Mar 2027")
    Spacer(Modifier.height(10.dp))
    GoalCard("Emergency fund", saved = 22_000_000, target = 60_000_000, icon = Icons.Filled.Savings,
        hint = "Behind: needs ₹31,700/month", onTrack = false)
    Spacer(Modifier.height(10.dp))
    InsightCard(InsightSeverity.ALERT, "HDFC card bill due in 2 days", "₹34,500 is due on 16 Oct. Pay it to avoid interest.",
        actionLabel = "Mark paid", onAction = {})
    Spacer(Modifier.height(10.dp))
    InsightCard(InsightSeverity.WARNING, "Food is on track to overshoot", "At this pace you'll spend ₹17,200 against a ₹15,000 budget.")
    Spacer(Modifier.height(10.dp))
    InsightCard(InsightSeverity.TIP, "3 subscriptions found", "Netflix, Spotify and iCloud cost ₹14,280 a year.")
    Spacer(Modifier.height(10.dp))
    InsightCard(InsightSeverity.GOOD, "Savings rate 53%", "You saved ₹69,000 this month — well above the 20% guideline.")
    Spacer(Modifier.height(10.dp))
    ProgressRing(0.72f) { Text("72%", style = MaterialTheme.typography.titleMedium) }
}

@Preview(name = "Entry & states — dark", widthDp = 400, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EntryDark() = ShowcaseSurface(dark = true) { EntryContent() }

@Preview(name = "Entry & states — light", widthDp = 400, heightDp = 900)
@Composable
private fun EntryLight() = ShowcaseSurface(dark = false) { EntryContent() }

@Composable
private fun EntryContent() {
    val expr = remember { KeypadExpression("1250+300") }
    var text by remember { mutableStateOf(expr.text) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        KoshLogo(size = 56.dp)
        Spacer(Modifier.width(12.dp))
        KoshLogo(size = 56.dp, shape = LogoShape.Circle)
        Spacer(Modifier.width(12.dp))
        Text("Kosh", style = MaterialTheme.typography.headlineLarge)
    }
    AmountInputDisplay(text, expr.valuePaise(), showResult = expr.hasOperator)
    NumberKeypad(onKey = { text = expr.press(it) })
    Spacer(Modifier.height(12.dp))
    PrimaryButton("Save expense", onClick = {}, modifier = Modifier.fillMaxWidth())
    LoadingShimmer(Modifier.padding(top = 12.dp))
    EmptyState("No transactions yet", "Scan your SMS inbox or add one manually.", actionLabel = "Scan SMS", onAction = {})
}
