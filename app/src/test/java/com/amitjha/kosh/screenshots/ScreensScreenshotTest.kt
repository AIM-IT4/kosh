package com.amitjha.kosh.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.InvestmentEntity
import com.amitjha.kosh.designsystem.components.KoshLogo
import com.amitjha.kosh.designsystem.components.LogoShape
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.theme.KoshText
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.domain.InvestmentSpec
import com.amitjha.kosh.finance.Budgets
import com.amitjha.kosh.finance.Goals
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.BudgetStatus
import com.amitjha.kosh.repo.GoalStatus
import com.amitjha.kosh.repo.InvestmentItem
import com.amitjha.kosh.ui.KoshBottomBar
import com.amitjha.kosh.ui.bills.BillsContent
import com.amitjha.kosh.ui.bills.BillsState
import com.amitjha.kosh.ui.common.ActionRow
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.ui.home.HomeContent
import com.amitjha.kosh.ui.home.HomeState
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.planning.PlanningContent
import com.amitjha.kosh.ui.planning.PlanningState
import com.amitjha.kosh.ui.settings.Section
import com.amitjha.kosh.ui.transactions.AddTransactionContent
import com.amitjha.kosh.ui.transactions.DayGroup
import com.amitjha.kosh.ui.transactions.EditorRefs
import com.amitjha.kosh.ui.transactions.TransactionsContent
import com.amitjha.kosh.ui.transactions.TxnListState
import com.amitjha.kosh.ui.wealth.WealthContent
import com.amitjha.kosh.ui.wealth.WealthState
import com.amitjha.kosh.util.Period
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class ScreensScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private val today = LocalDate.of(2026, 10, 14)

    /** A phone frame: screen content above the real bottom bar, like the running app. */
    @Composable
    private fun Frame(dark: Boolean = false, tab: String? = null, fab: Boolean = false, content: @Composable () -> Unit) {
        KoshTheme(darkTheme = dark) {
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(Modifier.weight(1f)) {
                    content()
                    if (fab) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(KoshTheme.colors.ink),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Add, null, tint = KoshTheme.colors.onInk) }
                    }
                }
                if (tab != null) KoshBottomBar(tab) {}
            }
          }
        }
    }

    private fun homeState() = HomeState(
        loading = false,
        netWorth = FakeData.netWorth,
        trend = FakeData.trend,
        summary = FakeData.summary,
        unreviewed = 3,
        bills = FakeData.bills,
        insights = FakeData.insights,
        recent = FakeData.recent,
        accounts = FakeData.accounts,
    )

    @Test
    fun home_light() = paparazzi.snapshot { Frame(tab = Routes.HOME, fab = true) { HomeContent(homeState(), hidden = false) } }

    @Test
    fun home_dark() = paparazzi.snapshot { Frame(dark = true, tab = Routes.HOME, fab = true) { HomeContent(homeState(), hidden = false) } }

    @Test
    fun home_privacy() = paparazzi.snapshot { Frame(tab = Routes.HOME, fab = true) { HomeContent(homeState(), hidden = true) } }

    @Test
    fun activity() = paparazzi.snapshot {
        val groups = FakeData.recent.groupBy { it.date }.map { (d, items) -> DayGroup(d, items, items.sumOf { it.signedAmount }) }
        Frame(tab = Routes.TRANSACTIONS, fab = true) {
            TransactionsContent(
                TxnListState(
                    period = Period(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1)),
                    groups = groups,
                    income = FakeData.summary.income,
                    expense = FakeData.summary.expense,
                    loaded = true,
                ),
                hidden = false,
            )
        }
    }

    @Test
    fun add_transaction() = paparazzi.snapshot {
        Frame {
            AddTransactionContent(
                refs = EditorRefs(FakeData.accounts.map { it.account }, FakeData.categories),
                editId = null,
                initialType = TxnType.EXPENSE,
                initialAccountId = FakeData.hdfc.id,
                initialAmount = "642",
                initialCategoryKey = "food",
                initialMerchant = "Swiggy",
            )
        }
    }

    private fun budget(key: String?, limit: Long, spent: Long) = BudgetStatus(
        BudgetEntity(id = (key ?: "all").hashCode().toLong(), categoryKey = key, monthlyLimit = limit * 100),
        key?.let(FakeData::cat),
        spent * 100,
        Budgets.project(limit * 100, spent * 100, today),
    )

    private fun planState() = PlanningState(
        budgets = listOf(
            budget(null, 70_000, 61_240),
            budget("food", 10_000, 9_820),
            budget("shopping", 12_000, 8_450),
            budget("transport", 6_000, 4_120),
        ),
        goals = listOf(
            GoalStatus(
                GoalEntity(id = 1, name = "Emergency fund", target = 6_00_000_00, saved = 3_80_000_00, deadline = today.plusMonths(10).toEpochDay()),
                Goals.plan(6_00_000_00, 3_80_000_00, today.plusMonths(10), today, 7.0),
            ),
            GoalStatus(
                GoalEntity(id = 2, name = "Japan trip", target = 2_50_000_00, saved = 60_000_00, deadline = today.plusMonths(14).toEpochDay(), icon = "flight"),
                Goals.plan(2_50_000_00, 60_000_00, today.plusMonths(14), today, 6.0),
            ),
        ),
        categories = FakeData.categories,
        loaded = true,
    )

    @Test
    fun plan_budgets() = paparazzi.snapshot { Frame(tab = Routes.PLANNING) { PlanningContent(planState(), tab = 0) } }

    @Test
    fun plan_goals() = paparazzi.snapshot { Frame(tab = Routes.PLANNING) { PlanningContent(planState(), tab = 1) } }

    private fun investment(id: Long, type: InvestmentType, name: String, inst: String, invested: Long, value: Long, spec: InvestmentSpec, ret: Double?) =
        InvestmentItem(
            InvestmentEntity(
                id = id, type = type, name = name, institution = inst, specJson = "{}",
                investedAmount = invested * 100, currentValue = value * 100, lastUpdated = 0,
                maturityDate = (spec as? InvestmentSpec.Fd)?.maturityDate?.toEpochDay(),
            ),
            spec,
            ret,
        )

    @Test
    fun wealth() = paparazzi.snapshot {
        val fd = InvestmentSpec.Fd(4_60_000_00, 7.25, today.minusMonths(14), today.plusMonths(10))
        val epf = InvestmentSpec.Epf(5_90_000_00, today.minusMonths(3), 9_600_00, 3_000_00)
        val nps = InvestmentSpec.Nps(emptyList(), 5_000_00)
        Frame(tab = Routes.WEALTH) {
            WealthContent(
                WealthState(
                    netWorth = FakeData.netWorth,
                    history = emptyList(),
                    items = listOf(
                        investment(1, InvestmentType.FD, "HDFC FD", "HDFC Bank", 4_60_000, 5_00_000, fd, 7.4),
                        investment(2, InvestmentType.EPF, "EPF", "EPFO", 5_90_000, 6_12_000, epf, 8.25),
                        investment(3, InvestmentType.NPS, "NPS Tier I", "HDFC Pension", 2_05_000, 2_30_000, nps, 11.2),
                    ),
                    loaded = true,
                ),
                hidden = false,
            )
        }
    }

    @Test
    fun card_bills() = paparazzi.snapshot {
        Frame {
            BillsContent(
                BillsState(bills = FakeData.bills, cards = listOf(FakeData.kotakCard, FakeData.dbsCard), banks = listOf(FakeData.hdfc), loaded = true),
                hidden = false,
            )
        }
    }

    /** Settings is assembled from the same row widgets the More screen uses. */
    @Test
    fun settings() = paparazzi.snapshot {
        Frame(tab = Routes.MORE) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KoshLogo(size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Kosh", style = KoshText.screenTitle)
                        Text("Your money, on your phone", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    }
                }
                Section("Manage") {
                    ActionRow("Accounts & cards", "Balances, limits and reconciliation", Icons.Outlined.AccountBalance) {}
                    ActionRow("Card bills", "Due dates and payments", Icons.Outlined.CreditCard) {}
                    ActionRow("Review imports", "Check SMS and email transactions", Icons.Outlined.Inbox) {}
                }
                Section("Appearance & privacy") {
                    SegmentedTabs(listOf("System", "Light", "Dark"), 1, onSelect = {}, modifier = Modifier.padding(vertical = 8.dp))
                    SwitchRow("Hide amounts", "Show ₹ •••• until you tap the eye", false, {}, Icons.Outlined.VisibilityOff)
                    SwitchRow("App lock", "Fingerprint, face or screen lock when opening Kosh", true, {}, Icons.Outlined.Fingerprint)
                }
                Section("SMS & email") {
                    SwitchRow("Import bank SMS automatically", "New bank messages are added as they arrive. SMS never leave your phone.", true, {}, Icons.Outlined.Sms)
                    ActionRow("Scan inbox now", "Last scan 2 hours ago", Icons.Outlined.Sync) {}
                    ActionRow("Connect Gmail", "Read credit card statements for bill amounts and due dates (read-only)", Icons.Outlined.Email) {}
                }
                Section("Your data") {
                    ActionRow("Back up", "Encrypted file protected by a password", Icons.Outlined.Backup) {}
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Test
    fun splash_and_logo() = paparazzi.snapshot {
        KoshTheme {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                KoshLogo(size = 88.dp, shape = LogoShape.RoundedSquare)
                Spacer(Modifier.height(12.dp))
                Text("Kosh", fontSize = 20.sp, style = MaterialTheme.typography.titleLarge, color = KoshTheme.colors.ink)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    KoshLogo(size = 96.dp, shape = LogoShape.RoundedSquare)
                    KoshLogo(size = 64.dp, shape = LogoShape.Circle)
                    KoshLogo(size = 40.dp, shape = LogoShape.RoundedSquare)
                }
                Spacer(Modifier.height(48.dp))
                Box(Modifier.fillMaxWidth())
            }
        }
    }
}
