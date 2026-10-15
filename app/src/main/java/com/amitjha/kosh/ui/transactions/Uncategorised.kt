package com.amitjha.kosh.ui.transactions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.MerchantRuleEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.SecondaryButton
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.ui.common.Fmt
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Uncategorised transactions to the same payee, fixed together with one tap. */
data class PayeeGroup(
    val key: String,
    val label: String,
    val merchant: String?,
    val transactions: List<TransactionEntity>,
) {
    val total: Paise get() = transactions.sumOf { it.amount }
    val count: Int get() = transactions.size
    val latest: Long get() = transactions.maxOf { it.timestamp }
}

data class UncategorisedState(
    val groups: List<PayeeGroup> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val loaded: Boolean = false,
)

/** Groups "Other" expenses by payee: same merchant spelled differently shares a group; no merchant goes last. */
internal fun groupByPayee(rows: List<TransactionEntity>): List<PayeeGroup> =
    rows.groupBy { t -> t.merchant?.let { SmsMapping.merchantKey(it) }?.takeIf { it.isNotEmpty() } ?: UNKNOWN_KEY }
        .map { (key, txns) ->
            val merchant = txns.firstNotNullOfOrNull { it.merchant }
            PayeeGroup(key, if (key == UNKNOWN_KEY) "Unknown payee" else merchant ?: key, if (key == UNKNOWN_KEY) null else merchant, txns)
        }
        .sortedWith(compareBy<PayeeGroup> { it.key == UNKNOWN_KEY }.thenByDescending { it.total })

private const val UNKNOWN_KEY = "\u0001unknown"

class UncategorisedViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<UncategorisedState> = combine(
        c.db.transactions().observeUncategorised(),
        c.categories.observeAll(),
    ) { rows, cats ->
        UncategorisedState(groupByPayee(rows), cats.filter { it.type == TxnType.EXPENSE }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UncategorisedState())

    /** Assigns [categoryKey] to every transaction in the group and, for a named payee, remembers it for future imports. */
    fun assign(group: PayeeGroup, categoryKey: String) = viewModelScope.launch {
        val dao = c.db.transactions()
        group.merchant?.let { c.db.merchantRules().upsert(MerchantRuleEntity(merchant = it, categoryKey = categoryKey)) }
        group.transactions.forEach { t ->
            dao.update(t.copy(categoryKey = categoryKey, reviewed = true, excludeFromStats = categoryKey == SmsMapping.TRANSFER_CATEGORY))
        }
    }

    /** Masked original SMS of up to 15 "Other" imports, for the user to share so parsing can be tuned. */
    suspend fun samples(): List<String> {
        val fingerprints = state.value.groups.flatMap { g -> g.transactions }
            .filter { it.source == TxnSource.SMS }
            .mapNotNull { it.fingerprint }
            .toSet()
        return c.smsImporter.samplesFor(fingerprints)
    }
}

/** "N transactions are uncategorised · Fix": shown on Home and Activity. */
@Composable
fun UncategorisedBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = KoshTheme.colors.cardSurfaceHigh,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Category, contentDescription = null, tint = KoshTheme.colors.subtleText)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$count transactions in \"Other\" this month", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Fix them by payee in one tap", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Fix uncategorised", tint = KoshTheme.colors.subtleText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UncategorisedScreen(nav: NavController) {
    val vm = koshViewModel { UncategorisedViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<PayeeGroup?>(null) }

    KoshScreen(
        title = "Fix uncategorised",
        subtitle = "Pick a category once per payee",
        onBack = { nav.popBackStack() },
        snackbar = snackbar,
    ) { padding ->
        if (state.loaded && state.groups.isEmpty()) {
            EmptyState(
                "Nothing in Other",
                "Every imported transaction has a category.",
                Modifier.padding(padding).padding(top = 60.dp),
            )
            return@KoshScreen
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Kosh couldn't tell what these were for. Choose a category for a payee and every transaction to them " +
                            "moves; future messages from them will be categorised automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KoshTheme.colors.subtleText,
                    )
                    SecondaryButton(
                        text = "Copy masked SMS samples",
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            scope.launch {
                                val samples = vm.samples()
                                if (samples.isEmpty()) {
                                    snackbar.showSnackbar("No matching SMS found to share")
                                } else {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.setPrimaryClip(ClipData.newPlainText("Kosh SMS samples", samples.joinToString("\n\n")))
                                    snackbar.showSnackbar("Copied ${samples.size} masked messages")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Account, card and phone numbers are replaced with X before copying. Nothing is sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
            items(state.groups, key = { it.key }) { g ->
                KoshCard(onClick = { picking = g }, contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${g.count} transaction${if (g.count == 1) "" else "s"} · last ${Fmt.date(Time.toDate(g.latest))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoshTheme.colors.subtleText,
                            )
                        }
                        AmountText(g.total, style = AmountStyles.small, hidden = hidden)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Choose category", tint = KoshTheme.colors.subtleText)
                    }
                }
            }
        }
    }

    picking?.let { group ->
        ModalBottomSheet(onDismissRequest = { picking = null }) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Category for ${group.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (group.merchant != null) "Applies to all ${group.count} transactions and future ones from this payee."
                    else "Applies to these ${group.count} transactions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                CategoryGrid(categories = state.categories.filter { it.key != "other" }, selectedKey = null, onSelect = {
                    vm.assign(group, it.key)
                    picking = null
                    scope.launch { snackbar.showSnackbar("Moved ${group.count} to ${it.name}") }
                })
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
