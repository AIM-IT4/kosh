package com.amitjha.kosh.ui.planning

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PieChart
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
)

class PlanningViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<PlanningState> = combine(
        c.budgets.observeStatuses(),
        c.goals.observeStatuses(),
        c.categories.observeByType(TxnType.EXPENSE),
    ) { b, g, cats -> PlanningState(b, g, cats, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningState())

    fun setBudget(categoryKey: String?, limit: Long) = viewModelScope.launch { c.budgets.upsert(categoryKey, limit) }
    fun deleteBudget(s: BudgetStatus) = viewModelScope.launch { c.budgets.delete(s.budget) }
    fun saveGoal(goal: GoalEntity) = viewModelScope.launch { c.goals.upsert(goal) }
    fun contribute(id: Long, amount: Long) = viewModelScope.launch { c.goals.contribute(id, amount) }
    fun deleteGoal(goal: GoalEntity) = viewModelScope.launch { c.goals.delete(goal) }
}

@Composable
fun PlanningScreen(@Suppress("UNUSED_PARAMETER") nav: NavController) {
    val vm = koshViewModel { PlanningViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addBudget by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf<GoalEntity?>(null) }
    var newGoal by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (tab == 0) addBudget = true else newGoal = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (tab == 0) "Budget" else "Goal")
                }
            }
            SegmentedTabs(listOf("Budgets", "Goals"), tab, onSelect = { tab = it }, modifier = Modifier.padding(top = 8.dp))
        }
        if (tab == 0) {
            BudgetsTab(state, onAdd = { addBudget = true }, onSet = vm::setBudget, onDelete = vm::deleteBudget)
        } else {
            GoalsTab(state, onAdd = { newGoal = true }, onOpen = { editGoal = it }, onContribute = vm::contribute)
        }
    }

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

@Composable
private fun BudgetsTab(
    state: PlanningState,
    onAdd: () -> Unit,
    onSet: (String?, Long) -> Unit,
    onDelete: (BudgetStatus) -> Unit,
) {
    val hidden = LocalHideAmounts.current
    var editing by remember { mutableStateOf<BudgetStatus?>(null) }
    var deleting by remember { mutableStateOf<BudgetStatus?>(null) }
    if (state.loaded && state.budgets.isEmpty()) {
        EmptyState(
            "No budgets yet",
            "Set a monthly limit overall or per category. Kosh tracks your pace and warns you before you overspend.",
            Modifier.padding(top = 40.dp),
            icon = Icons.Filled.PieChart,
            actionLabel = "Create a budget",
            onAction = onAdd,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.budgets.size, key = { state.budgets[it].budget.id }) { i ->
            val s = state.budgets[i]
            val p = s.projection
            KoshCard(Modifier.staggeredEntrance(i), onClick = { editing = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.isOverall) {
                        ProgressRing(p.usedFraction.toFloat().coerceIn(0f, 1f), size = 44.dp, thickness = 6.dp)
                    } else {
                        CategoryIcon(s.category?.icon, s.category.color(), size = 44.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (s.isOverall) "Overall budget" else s.category?.name ?: s.budget.categoryKey.orEmpty(), fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AmountText(s.spent, style = AmountStyles.small, hidden = hidden)
                            Text(" of ", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                            AmountText(s.budget.monthlyLimit, style = AmountStyles.small, hidden = hidden, color = KoshTheme.colors.subtleText)
                        }
                    }
                    Text(
                        Fmt.pct(p.usedFraction * 100, 0),
                        fontWeight = FontWeight.Bold,
                        color = when {
                            p.isExceeded -> KoshTheme.colors.alert
                            p.willExceed -> KoshTheme.colors.warning
                            else -> KoshTheme.colors.good
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                BudgetProgressBar(s.spent, s.budget.monthlyLimit, projected = p.projected)
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
    }
    editing?.let { s ->
        EditBudgetSheet(
            s,
            onDismiss = { editing = null },
            onSave = {
                onSet(s.budget.categoryKey, it)
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
private fun EditBudgetSheet(s: BudgetStatus, onDismiss: () -> Unit, onSave: (Long) -> Unit, onDelete: () -> Unit) {
    var limit by remember { mutableStateOf<Long?>(s.budget.monthlyLimit) }
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
                PrimaryButton("Save", enabled = (limit ?: 0) > 0, modifier = Modifier.fillMaxWidth(), onClick = { onSave(limit!!) })
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
) {
    val hidden = LocalHideAmounts.current
    var contributing by remember { mutableStateOf<GoalStatus?>(null) }
    if (state.loaded && state.goals.isEmpty()) {
        EmptyState(
            "No goals yet",
            "An emergency fund, a trip, a new phone: set a target and a date, and Kosh tells you how much to save each month.",
            Modifier.padding(top = 40.dp),
            icon = Icons.Filled.Flag,
            actionLabel = "Create a goal",
            onAction = onAdd,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.goals, key = { it.goal.id }) { g ->
            val plan = g.plan
            val hint = when {
                plan.reached -> "Goal reached 🎉"
                plan.overdue -> "Deadline passed · ${Fmt.date(g.deadline)}"
                else -> "Save ${if (hidden) "₹ ••••" else Money.format(plan.requiredMonthly, showPaise = false)}/month · ${plan.monthsLeft} months left"
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { contributing = g }, enabled = !plan.reached) { Text("Add savings") }
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
                KoshTextField(ret, { ret = it.filter { ch -> ch.isDigit() || ch == '.' } }, "Expected annual return", keyboardType = KeyboardType.Decimal,
                    suffix = "%", supporting = "Used to compute the monthly saving needed (7% ≈ a balanced fund)")
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
