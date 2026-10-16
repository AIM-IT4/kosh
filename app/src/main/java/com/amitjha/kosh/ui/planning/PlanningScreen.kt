package com.amitjha.kosh.ui.planning

import androidx.compose.foundation.layout.size
import com.amitjha.kosh.designsystem.theme.KoshText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.BudgetProgressBar
import com.amitjha.kosh.designsystem.components.CategoryIcon
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.GoalCard
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.ProgressRing
import com.amitjha.kosh.designsystem.components.SegmentedTabs
import com.amitjha.kosh.designsystem.components.staggeredEntrance
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.model.Money
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.BudgetStatus
import com.amitjha.kosh.repo.GoalStatus
import com.amitjha.kosh.ui.common.AmountField
import com.amitjha.kosh.ui.common.AmountSheet
import com.amitjha.kosh.ui.common.ChoiceField
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.DateField
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.FormColumn
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.color
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.common.launchGuarded
import com.amitjha.kosh.ui.common.rememberHaptic
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PlanningState(
    val budgets: List<BudgetStatus> = emptyList(),
    val goals: List<GoalStatus> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val loaded: Boolean = false,
    /** Monte Carlo outlook per goal id (goals not yet reached and with time left). */
    val outlooks: Map<Long, com.amitjha.kosh.repo.GoalOutlook> = emptyMap(),
    /** Zero-based view of this month, when envelope mode is on. */
    val envelope: com.amitjha.kosh.repo.EnvelopeStatus? = null,
    val envelopeMode: Boolean = false,
)

class PlanningViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<PlanningState> = combine(
        c.budgets.observeStatuses(),
        c.goals.observeStatuses(),
        c.categories.observeByType(TxnType.EXPENSE),
        c.goalOutlooks.observe(),
        combine(c.budgets.observeEnvelope(), c.featurePrefs.settings) { e, f -> e to f.envelopeMode },
    ) { b, g, cats, outlooks, env ->
        PlanningState(b, g, cats, loaded = true, outlooks = outlooks, envelope = env.first.takeIf { env.second }, envelopeMode = env.second)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningState())

    fun setBudget(categoryKey: String?, limit: Long, rollover: Boolean? = null) = viewModelScope.launch { c.budgets.upsert(categoryKey, limit, rollover) }
    fun setEnvelopeMode(on: Boolean) = viewModelScope.launch { c.featurePrefs.setEnvelopeMode(on) }
    fun deleteBudget(s: BudgetStatus) = viewModelScope.launch { c.budgets.delete(s.budget) }
    private val guard = com.amitjha.kosh.ui.common.ActionGuard()

    fun saveGoal(goal: GoalEntity) = launchGuarded(guard) { c.goals.upsert(goal) }
    fun contribute(id: Long, amount: Long) = launchGuarded(guard) { c.goals.contribute(id, amount) }
    fun deleteGoal(goal: GoalEntity) = viewModelScope.launch { c.goals.delete(goal) }
    fun setInflation(goalId: Long, pct: Double) = viewModelScope.launch { c.goalOutlooks.setInflation(goalId, pct) }
}

/** Lets other screens (e.g. an insight's action) open Plan on a given tab: 0 = Budgets, 1 = Goals. */
object PlanningTabRequest {
    var pending by mutableStateOf<Int?>(null)
}

@Composable
fun PlanningScreen(@Suppress("UNUSED_PARAMETER") nav: NavController) {
    val vm = koshViewModel { PlanningViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val requestedTab = PlanningTabRequest.pending
    androidx.compose.runtime.LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            PlanningTabRequest.pending = null
        }
    }
    var addBudget by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf<GoalEntity?>(null) }
    var newGoal by remember { mutableStateOf(false) }

    PlanningContent(
        state = state,
        tab = tab,
        onTab = { tab = it },
        onAddBudget = { addBudget = true },
        onNewGoal = { newGoal = true },
        onSetBudget = { key, limit, roll -> vm.setBudget(key, limit, roll) },
        onEnvelopeMode = vm::setEnvelopeMode,
        onDeleteBudget = vm::deleteBudget,
        onOpenGoal = { editGoal = it },
        onContribute = vm::contribute,
        onInflation = vm::setInflation,
    )

    if (addBudget) {
        AddBudgetSheet(
            categories = state.categories.filter { c -> state.budgets.none { it.budget.categoryKey == c.key } && c.key != "transfer" },
            hasOverall = state.budgets.any { it.isOverall },
            onDismiss = { addBudget = false },
            onSave = { key, limit ->
                vm.setBudget(key, limit)
                addBudget = false
            },
        )
    }
    if (newGoal || editGoal != null) {
        GoalSheet(
            goal = editGoal,
            onDismiss = {
                newGoal = false
                editGoal = null
            },
            onSave = {
                vm.saveGoal(it)
                newGoal = false
                editGoal = null
            },
            onDelete = editGoal?.let { g ->
                {
                    vm.deleteGoal(g)
                    editGoal = null
                }
            },
        )
    }
}

/** Stateless Plan screen (header, tabs and lists, without sheets); used by the app and screenshot tests. */
@Composable
fun PlanningContent(
    state: PlanningState,
    tab: Int,
    onTab: (Int) -> Unit = {},
    onAddBudget: () -> Unit = {},
    onNewGoal: () -> Unit = {},
    onSetBudget: (String?, Long, Boolean?) -> Unit = { _, _, _ -> },
    onEnvelopeMode: (Boolean) -> Unit = {},
    onDeleteBudget: (BudgetStatus) -> Unit = {},
    onOpenGoal: (GoalEntity) -> Unit = {},
    onContribute: (Long, Long) -> Unit = { _, _ -> },
    onInflation: (Long, Double) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = KoshText.screenTitle, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (tab == 0) onAddBudget() else onNewGoal() }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = KoshTheme.colors.ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (tab == 0) "Budget" else "Goal", color = KoshTheme.colors.ink)
                }
            }
            SegmentedTabs(listOf("Budgets", "Goals"), tab, onSelect = onTab, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
        }
        if (tab == 0) {
            BudgetsTab(state, onAdd = onAddBudget, onSet = onSetBudget, onDelete = onDeleteBudget, onEnvelopeMode = onEnvelopeMode)
        } else {
            GoalsTab(state, onAdd = onNewGoal, onOpen = onOpenGoal, onContribute = onContribute, onInflation = onInflation)
        }
    }
}

@Composable
private fun BudgetsTab(
    state: PlanningState,
    onAdd: () -> Unit,
    onSet: (String?, Long, Boolean?) -> Unit,
    onDelete: (BudgetStatus) -> Unit,
    onEnvelopeMode: (Boolean) -> Unit = {},
) {
    val hidden = LocalHideAmounts.current
    var editing by remember { mutableStateOf<BudgetStatus?>(null) }
    var deleting by remember { mutableStateOf<BudgetStatus?>(null) }
    if (state.loaded && state.budgets.isEmpty()) {
        EmptyState(
            "No budgets yet",
            "Set a monthly limit overall or per category. Kosh tracks your pace and warns you before you overspend.",
            Modifier.padding(top = 40.dp),
            icon = Icons.Outlined.PieChart,
            actionLabel = "Create a budget",
            onAction = onAdd,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.envelope?.let { env ->
            item(key = "envelope") {
                KoshCard {
                    Text("Give every rupee a job", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    EnvelopeLine("Income this month", env.envelope.income, hidden)
                    EnvelopeLine("Assigned to budgets", env.envelope.assigned, hidden)
                    EnvelopeLine(if (env.envelope.toAssign >= 0) "Left to assign" else "Over-assigned", env.envelope.toAssign, hidden,
                        color = if (env.envelope.toAssign >= 0) KoshTheme.colors.good else KoshTheme.colors.alert)
                }
            }
        }
        items(state.budgets.size, key = { state.budgets[it].budget.id }) { i ->
            val s = state.budgets[i]
            val p = s.projection
            KoshCard(Modifier.staggeredEntrance(i), onClick = { editing = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.isOverall) {
                        ProgressRing(p.usedFraction.toFloat().coerceIn(0f, 1f), size = 36.dp, thickness = 4.dp)
                    } else {
                        CategoryIcon(s.category?.icon, s.category.color(), size = 36.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (s.isOverall) "Overall budget" else s.category?.name ?: s.budget.categoryKey.orEmpty(), fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AmountText(s.spent, style = AmountStyles.small, hidden = hidden)
                            Text(" of ", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                            AmountText(s.limit, style = AmountStyles.small, hidden = hidden, color = KoshTheme.colors.subtleText)
                            if (s.budget.rollover && s.carry != 0L) {
                                Text(
                                    if (s.carry > 0) " · +${if (hidden) "₹ ••••" else Money.format(s.carry, showPaise = false)} rolled over"
                                    else " · −${if (hidden) "₹ ••••" else Money.format(-s.carry, showPaise = false)} carried",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KoshTheme.colors.subtleText,
                                )
                            }
                        }
                    }
                    Text(
                        Fmt.pct(p.usedFraction * 100, 0),
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            p.isExceeded -> KoshTheme.colors.alert
                            p.willExceed -> KoshTheme.colors.warning
                            else -> KoshTheme.colors.subtleText
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                BudgetProgressBar(s.spent, s.limit, projected = p.projected)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        p.isExceeded -> "Over by ${if (hidden) "₹ ••••" else Money.format(-p.remaining, showPaise = false)}"
                        p.willExceed -> "On pace for ${if (hidden) "₹ ••••" else Money.format(p.projected, showPaise = false)} · keep under ${
                            if (hidden) "₹ ••••" else Money.format(p.dailyAllowance, showPaise = false)}/day"
                        else -> "${if (hidden) "₹ ••••" else Money.format(p.remaining, showPaise = false)} left · ${
                            if (hidden) "₹ ••••" else Money.format(p.dailyAllowance, showPaise = false)}/day"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
        }
        item(key = "envelope-mode") {
            KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                com.amitjha.kosh.ui.common.SwitchRow(
                    "Envelope mode",
                    "Assign this month's income to budgets until nothing is left over",
                    state.envelopeMode,
                    onEnvelopeMode,
                )
            }
        }
    }
    editing?.let { s ->
        EditBudgetSheet(
            s,
            onDismiss = { editing = null },
            onSave = { limit, roll ->
                onSet(s.budget.categoryKey, limit, roll)
                editing = null
            },
            onDelete = {
                deleting = s
                editing = null
            },
        )
    }
    deleting?.let { s ->
        ConfirmDialog("Delete budget?", "The limit is removed; your transactions are not affected.", "Delete", onConfirm = {
            onDelete(s)
            deleting = null
        }, onDismiss = { deleting = null }, destructive = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditBudgetSheet(s: BudgetStatus, onDismiss: () -> Unit, onSave: (Long, Boolean) -> Unit, onDelete: () -> Unit) {
    var limit by remember { mutableStateOf<Long?>(s.budget.monthlyLimit) }
    var rollover by remember { mutableStateOf(s.budget.rollover) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(
                if (s.isOverall) "Overall budget" else s.category?.name ?: "Budget",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            FormColumn {
                AmountField("Monthly limit", limit, { limit = it }, allowZero = false)
                com.amitjha.kosh.ui.household.HouseholdSwitch(com.amitjha.kosh.sync.HouseholdKind.BUDGET, s.budget.id, card = false)
                com.amitjha.kosh.ui.common.SwitchRow(
                    "Roll over",
                    "Unspent money adds to next month; overspending comes out of it (up to one month's limit)",
                    rollover,
                    { rollover = it },
                )
                PrimaryButton("Save", enabled = (limit ?: 0) > 0, modifier = Modifier.fillMaxWidth(), onClick = { onSave(limit!!, rollover) })
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete budget", color = KoshTheme.colors.alert) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBudgetSheet(
    categories: List<CategoryEntity>,
    hasOverall: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, Long) -> Unit,
) {
    val options: List<CategoryEntity?> = (if (hasOverall) emptyList<CategoryEntity?>() else listOf(null)) + categories
    var selected by remember { mutableStateOf(options.firstOrNull()) }
    var limit by remember { mutableStateOf<Long?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("New budget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            FormColumn {
                ChoiceField(
                    "For",
                    options,
                    selected,
                    { it?.name ?: "All spending (overall)" },
                    { selected = it },
                    leading = { c -> if (c != null) CategoryIcon(c.icon, c.color(), size = 28.dp) },
                )
                AmountField("Monthly limit", limit, { limit = it }, allowZero = false)
                PrimaryButton("Save budget", enabled = (limit ?: 0) > 0 && options.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                    onSave(selected?.key, limit!!)
                })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GoalsTab(
    state: PlanningState,
    onAdd: () -> Unit,
    onOpen: (GoalEntity) -> Unit,
    onContribute: (Long, Long) -> Unit,
    onInflation: (Long, Double) -> Unit = { _, _ -> },
) {
    val hidden = LocalHideAmounts.current
    var contributing by remember { mutableStateOf<GoalStatus?>(null) }
    if (state.loaded && state.goals.isEmpty()) {
        EmptyState(
            "No goals yet",
            "An emergency fund, a trip, a new phone: set a target and a date, and Kosh tells you how much to save each month.",
            Modifier.padding(top = 40.dp),
            icon = Icons.Outlined.Flag,
            actionLabel = "Create a goal",
            onAction = onAdd,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state.goals, key = { it.goal.id }) { g ->
            val plan = g.plan
            val hint = when {
                plan.reached -> "Goal reached 🎉"
                plan.overdue -> "Deadline passed · ${Fmt.date(g.deadline)}"
                g.hasLoan -> "Save ${if (hidden) "₹ ••••" else Money.format(plan.requiredMonthly, showPaise = false)}/month + loan " +
                    "${if (hidden) "₹ ••••" else Money.format(g.goal.loanAmount, showPaise = false)} (EMI " +
                    "${if (hidden) "₹ ••••" else Money.format(g.loanEmi, showPaise = false)} for ${g.goal.loanTenureMonths} months)"
                else -> "Save ${if (hidden) "₹ ••••" else Money.format(plan.requiredMonthly, showPaise = false)}/month at " +
                    "${Fmt.pct(g.goal.expectedReturnPct, 0)} a year · ${plan.monthsLeft} months left"
            }
            Column {
                GoalCard(
                    name = g.goal.name,
                    saved = g.goal.saved,
                    target = g.goal.target,
                    hint = hint,
                    onTrack = !plan.overdue,
                    hidden = hidden,
                    onClick = { onOpen(g.goal) },
                )
                state.outlooks[g.goal.id]?.let { o ->
                    GoalOutlookSection(o, g.deadline, hidden, onInflation = { pct -> onInflation(g.goal.id, pct) })
                }
                if (!plan.reached) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { contributing = g }) {
                            Text("+ Add savings", color = KoshTheme.colors.accent, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
    contributing?.let { g ->
        AmountSheet("Add to ${g.goal.name}", null, onDismiss = { contributing = null }, onConfirm = {
            onContribute(g.goal.id, it)
            contributing = null
        }, type = TxnType.INCOME)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(goal: GoalEntity?, onDismiss: () -> Unit, onSave: (GoalEntity) -> Unit, onDelete: (() -> Unit)?) {
    val haptic = rememberHaptic()
    var name by remember { mutableStateOf(goal?.name ?: "") }
    var target by remember { mutableStateOf(goal?.target) }
    var saved by remember { mutableStateOf(goal?.saved ?: 0L) }
    var deadline by remember { mutableStateOf(goal?.deadline?.let(LocalDate::ofEpochDay) ?: Time.today().plusYears(1)) }
    var ret by remember { mutableStateOf((goal?.expectedReturnPct ?: 7.0).toString()) }
    var loanOn by remember { mutableStateOf((goal?.loanAmount ?: 0) > 0) }
    var loanAmount by remember { mutableStateOf(goal?.loanAmount?.takeIf { it > 0 }) }
    var loanRate by remember { mutableStateOf((goal?.loanRatePct ?: 9.0).toString()) }
    var loanMonths by remember { mutableStateOf((goal?.loanTenureMonths ?: 36).toString()) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(if (goal == null) "New goal" else "Edit goal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            FormColumn {
                KoshTextField(name, { name = it }, "Goal name", supporting = "e.g. Emergency fund, Goa trip")
                AmountField("Target amount", target, { target = it }, allowZero = false)
                AmountField("Already saved", saved, { saved = it })
                DateField("Target date", deadline, { deadline = it })
                ReturnField(ret, { ret = it })
                LoanSection(
                    target = target,
                    enabled = loanOn,
                    onEnabled = { loanOn = it },
                    amount = loanAmount,
                    onAmount = { loanAmount = it },
                    rate = loanRate,
                    onRate = { loanRate = it },
                    months = loanMonths,
                    onMonths = { loanMonths = it },
                )
                val loanForPlan = if (loanOn) (loanAmount ?: 0) else 0
                RequiredSaving(
                    target?.let { com.amitjha.kosh.domain.GoalLoan.savingsTarget(it, loanForPlan) },
                    saved, deadline, ret.toDoubleOrNull()?.coerceIn(0.0, 30.0) ?: 7.0,
                )
                if (goal != null) com.amitjha.kosh.ui.household.HouseholdSwitch(com.amitjha.kosh.sync.HouseholdKind.GOAL, goal.id, card = false)
                PrimaryButton(
                    if (goal == null) "Create goal" else "Save",
                    enabled = name.isNotBlank() && (target ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        haptic()
                        onSave(
                            (goal ?: GoalEntity(name = "", target = 0, deadline = 0)).copy(
                                name = name.trim(),
                                target = target!!,
                                saved = saved,
                                deadline = deadline.toEpochDay(),
                                expectedReturnPct = ret.toDoubleOrNull()?.coerceIn(0.0, 30.0) ?: 7.0,
                                loanAmount = if (loanOn) (loanAmount ?: 0).coerceIn(0, target!!) else 0,
                                loanRatePct = loanRate.toDoubleOrNull()?.coerceIn(0.0, 40.0) ?: 9.0,
                                loanTenureMonths = loanMonths.toIntOrNull()?.coerceIn(1, 360) ?: 36,
                            ),
                        )
                    },
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete goal", color = KoshTheme.colors.alert)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete goal?", "This removes the goal and its saved amount.", "Delete", onConfirm = {
            confirmDelete = false
            onDelete()
        }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

/** Typical yearly returns, offered as one-tap choices for a goal's savings. */
private val RETURN_PRESETS = listOf("Savings account" to 3.0, "FD" to 7.0, "Debt fund" to 7.0, "Equity fund" to 12.0)

/**
 * The yearly return expected on the money set aside for a goal. Kosh assumes each monthly saving earns this
 * return until the target date, so a higher return means a smaller monthly saving is needed.
 */
@Composable
private fun ReturnField(value: String, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KoshTextField(
            value,
            { onValue(it.filter { ch -> ch.isDigit() || ch == '.' }) },
            "Expected return on your savings (% per year)",
            keyboardType = KeyboardType.Decimal,
            suffix = "%",
        )
        Text(
            "The yearly return you expect on the money you put aside for this goal, depending on where you keep it. " +
                "Kosh uses it to work out how much to save each month.",
            style = MaterialTheme.typography.bodySmall,
            color = KoshTheme.colors.subtleText,
        )
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RETURN_PRESETS.size) { i ->
                val (label, pct) = RETURN_PRESETS[i]
                androidx.compose.material3.FilterChip(
                    selected = value.toDoubleOrNull() == pct,
                    onClick = { onValue(pct.toInt().toString()) },
                    label = { Text("$label ${pct.toInt()}%") },
                )
            }
        }
    }
}

/**
 * The loan the user is willing to take at purchase: amount (or % of the target), rate and tenure, with the
 * EMI and total interest. The goal's savings target becomes target − loan.
 */
@Composable
private fun LoanSection(
    target: Long?,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    amount: Long?,
    onAmount: (Long) -> Unit,
    rate: String,
    onRate: (String) -> Unit,
    months: String,
    onMonths: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Loan at purchase", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Part of the cost you're OK to borrow when you buy", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        if (!enabled) return@Column
        val t = target ?: 0L
        val pct = if (t > 0) ((amount ?: 0).toDouble() / t * 100).coerceIn(0.0, 80.0) else 0.0
        AmountField("Loan amount", amount, onAmount)
        if (t > 0) {
            Text("${pct.toInt()}% of the target", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
            androidx.compose.material3.Slider(
                value = pct.toFloat(),
                onValueChange = { onAmount((t * it / 100.0).toLong() / 100 * 100) },
                valueRange = 0f..80f,
                steps = 15,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KoshTextField(rate, { onRate(it.filter { ch -> ch.isDigit() || ch == '.' }) }, "Interest", Modifier.weight(1f),
                keyboardType = KeyboardType.Decimal, suffix = "% p.a.")
            KoshTextField(months, { onMonths(it.filter(Char::isDigit).take(3)) }, "Tenure", Modifier.weight(1f),
                keyboardType = KeyboardType.Number, suffix = "months")
        }
        val principal = (amount ?: 0).coerceAtMost(t)
        val r = rate.toDoubleOrNull() ?: 9.0
        val n = months.toIntOrNull() ?: 36
        if (principal > 0 && n > 0) {
            val emi = com.amitjha.kosh.domain.GoalLoan.emi(principal, r, n)
            val interest = com.amitjha.kosh.domain.GoalLoan.totalInterest(principal, r, n)
            KoshCard(contentPadding = PaddingValues(14.dp)) {
                Text("EMI after purchase", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
                Text("${Money.format(emi, showPaise = false)} a month for $n months", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Total interest ${Money.format(interest, showPaise = false)} · you save the remaining " +
                    "${Money.format(com.amitjha.kosh.domain.GoalLoan.savingsTarget(t, principal), showPaise = false)}",
                    style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
        }
    }
}

/** Live "Required monthly saving" for the goal as the inputs change. */
@Composable
private fun RequiredSaving(target: Long?, saved: Long, deadline: LocalDate, returnPct: Double) {
    val t = target ?: return
    val plan = remember(t, saved, deadline, returnPct) {
        com.amitjha.kosh.finance.Goals.plan(t, saved, deadline, Time.today(), returnPct)
    }
    KoshCard(contentPadding = PaddingValues(14.dp)) {
        Text("Required monthly saving", style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.subtleText)
        Text(
            when {
                plan.reached -> "Already reached"
                plan.overdue -> "Pick a date in the future"
                else -> Money.format(plan.requiredMonthly, showPaise = false) + " a month for ${plan.monthsLeft} months"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!plan.reached && !plan.overdue) {
            Text(
                "Assuming your savings earn ${Fmt.pct(returnPct, 0)} a year.",
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
            )
        }
    }
}

@Composable
private fun EnvelopeLine(label: String, amount: Long, hidden: Boolean, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = KoshTheme.colors.subtleText)
        AmountText(amount, style = AmountStyles.medium, hidden = hidden, color = color)
    }
}
