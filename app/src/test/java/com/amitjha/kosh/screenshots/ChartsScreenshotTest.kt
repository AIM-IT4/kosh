package com.amitjha.kosh.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.amitjha.kosh.data.NetWorthSnapshotEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.finance.AccountInput
import com.amitjha.kosh.finance.BillInput
import com.amitjha.kosh.finance.BudgetInput
import com.amitjha.kosh.finance.InsightEngine
import com.amitjha.kosh.finance.InsightInput
import com.amitjha.kosh.finance.InsightTxn
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.ui.home.HomeCharts
import com.amitjha.kosh.ui.home.HomeContent
import com.amitjha.kosh.ui.home.HomeState
import com.amitjha.kosh.ui.insights.InsightsContent
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.math.sin

/** Tall renders of the chart-heavy screens so every section below the fold is visible. */
class ChartsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private val today = LocalDate.of(2026, 10, 14)
    private fun rs(r: Long) = r * 100

    /** Six months of realistic activity: salary, rent, groceries, food, travel and the odd big purchase. */
    private val history: List<TransactionEntity> = buildList {
        var id = 1L
        fun add(date: LocalDate, type: TxnType, rupees: Long, cat: String, merchant: String) {
            if (date.isAfter(today)) return
            add(TransactionEntity(id++, 1, null, type, rs(rupees), cat, merchant, null, Time.startOfDay(date) + 12 * 3_600_000L, source = TxnSource.SMS))
        }
        for (m in 0..5) {
            val start = today.withDayOfMonth(1).minusMonths(m.toLong())
            add(start, TxnType.INCOME, 185_000, "salary", "Acme Corp")
            add(start.plusDays(1), TxnType.EXPENSE, 32_000, "rent", "Landlord")
            add(start.plusDays(4), TxnType.EXPENSE, 649, "subscriptions", "Netflix")
            add(start.plusDays(6), TxnType.EXPENSE, 119, "subscriptions", "Spotify")
            for (d in 0 until start.lengthOfMonth()) {
                val day = start.plusDays(d.toLong())
                val wave = (sin(d * 0.9 + m) * 0.5 + 0.5)
                if (d % 2 == 0) add(day, TxnType.EXPENSE, 180 + (wave * 520).toLong(), "food", if (d % 4 == 0) "Swiggy" else "Zomato")
                if (d % 5 == 1) add(day, TxnType.EXPENSE, 900 + (wave * 1800).toLong(), "groceries", "Blinkit")
                if (d % 7 == 3) add(day, TxnType.EXPENSE, 250 + (wave * 300).toLong(), "transport", "Uber")
                if (day.dayOfWeek.value >= 6 && d % 3 == 0) add(day, TxnType.EXPENSE, 1_500 + (wave * 3_000).toLong(), "entertainment", "PVR")
            }
            if (m % 2 == 0) add(start.plusDays(12), TxnType.EXPENSE, 9_000 + m * 2_500L, "shopping", "Amazon")
        }
        add(today.minusDays(3), TxnType.EXPENSE, 7_800, "shopping", "Croma")
    }

    private val netWorth = (0..200).map { d ->
        val day = today.minusDays((200 - d).toLong())
        NetWorthSnapshotEntity(day.toEpochDay(), 0, 0, 0, rs(1_320_000L + d * 1_200L + (sin(d / 9.0) * 25_000).toLong()))
    }

    private fun homeState(): HomeState {
        val charts = HomeCharts.build(history, Period.containing(today), today, netWorth)
        return HomeState(
            loading = false,
            netWorth = FakeData.netWorth,
            trend = FakeData.trend,
            summary = FakeData.summary,
            unreviewed = 3,
            reviewFromSms = 2,
            reviewFromEmail = 1,
            bills = FakeData.bills,
            insights = FakeData.insights,
            recent = FakeData.recent,
            accounts = FakeData.accounts,
            charts = charts,
        )
    }

    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        KoshTheme(darkTheme = false) {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }

    private fun home(firstItem: Int) = paparazzi.snapshot {
        Screen { HomeContent(homeState(), hidden = false, listState = rememberLazyListState(firstItem)) }
    }

    // Items: 0 header, 1 hero, 2 stats, 3 pace, 4 review, 5 bills, 6 insight, 7 where it went, 8 six months, 9 heatmap, 10 net worth.
    @Test
    fun home_charts_top() = home(0)

    @Test
    fun home_charts_donut() = home(7)

    @Test
    fun home_charts_trends() = home(8)

    @Test
    fun insights_full() = paparazzi.snapshot {
        val txns = history.map {
            InsightTxn(Time.toDate(it.timestamp), it.amount, it.type, it.categoryKey, it.merchant, it.accountId)
        }
        val insights = InsightEngine.generate(
            InsightInput(
                today = today,
                transactions = txns,
                budgets = listOf(BudgetInput("food", rs(5_000)), BudgetInput(null, rs(80_000))),
                accounts = listOf(AccountInput(1, "HDFC Savings", AccountType.BANK, rs(210_000)), AccountInput(9, "HDFC Regalia", AccountType.CREDIT_CARD, rs(61_400), rs(200_000))),
                upcomingBills = listOf(BillInput("DBS Vantage", rs(42_900), today.plusDays(2), accountId = 1)),
                categoryNames = FakeData.categories.associate { it.key to it.name },
            ),
        )
        val expand = insights.filterNot { it.isSummary }.take(2).map { it.id }.toSet()
        Screen { InsightsContent(insights, hidden = false, expandedByDefault = expand) }
    }

    @Test
    fun insights_more() = paparazzi.snapshot {
        val txns = history.map { InsightTxn(Time.toDate(it.timestamp), it.amount, it.type, it.categoryKey, it.merchant, it.accountId) }
        val insights = InsightEngine.generate(
            InsightInput(today = today, transactions = txns, budgets = listOf(BudgetInput("food", rs(5_000))),
                accounts = listOf(AccountInput(1, "HDFC Savings", AccountType.BANK, rs(210_000))),
                categoryNames = FakeData.categories.associate { it.key to it.name }),
        )
        val cards = insights.filterNot { it.isSummary }
        Screen { InsightsContent(insights, hidden = false, expandedByDefault = cards.drop(2).take(3).map { it.id }.toSet(), listState = rememberLazyListState(4)) }
    }
}
