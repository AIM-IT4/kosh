package com.amitjha.kosh.ui.household

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.BuildConfig
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.sync.HouseholdKind
import com.amitjha.kosh.ui.common.LocalContainer
import com.amitjha.kosh.ui.common.SwitchRow
import kotlinx.coroutines.launch

/** True when household sync exists in this edition (the offline edition has no network access at all). */
val householdAvailable: Boolean get() = BuildConfig.NETWORK_ENABLED

/**
 * "Household" switch for a transaction, budget or goal. Turning it on shares the record with the paired
 * phone at the next sync; turning it off removes it there. Hidden in the offline edition.
 */
@Composable
fun HouseholdSwitch(kind: HouseholdKind, localId: Long, card: Boolean = true) {
    if (!householdAvailable || localId <= 0) return
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val flow = remember(kind) { c.household.observeSharedIds(kind) }
    val shared by flow.collectAsState(initial = emptySet())
    val peer by c.householdStore.state.collectAsState()
    val subtitle = when {
        peer.peer != null -> "Shared with ${peer.peer!!.name} at the next sync"
        else -> "Shared once you pair a phone in More → Household sync"
    }
    val row = @Composable {
        SwitchRow(
            title = "Household",
            subtitle = subtitle,
            checked = localId in shared,
            onChecked = { on -> scope.launch { c.household.setShared(kind, localId, on) } },
            icon = Icons.Outlined.Groups,
        )
    }
    if (card) KoshCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) { row() } else row()
}
