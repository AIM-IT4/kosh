package com.amitjha.kosh.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.designsystem.components.CategoryIcon
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.ui.common.color

/** A grid of categories; the selected one is outlined. */
@Composable
fun CategoryGrid(
    categories: List<CategoryEntity>,
    selectedKey: String?,
    onSelect: (CategoryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(78.dp),
        modifier = modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { it.key }) { cat ->
            val selected = cat.key == selectedKey
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) cat.color().copy(alpha = 0.18f) else KoshTheme.colors.cardSurface,
                border = if (selected) BorderStroke(1.5.dp, cat.color()) else null,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(cat) },
            ) {
                Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CategoryIcon(cat.icon, cat.color(), size = 38.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        cat.name,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Bottom sheet to recategorise a transaction, with an "apply to all from this merchant" switch
 * that also teaches future imports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecategoriseSheet(
    categories: List<CategoryEntity>,
    currentKey: String?,
    merchant: String?,
    onDismiss: () -> Unit,
    onPick: (key: String, applyToSimilar: Boolean) -> Unit,
) {
    var applyAll by remember { mutableStateOf(merchant != null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Choose a category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (merchant != null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Apply to all from $merchant", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Future imports will follow this too", style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    }
                    Switch(checked = applyAll, onCheckedChange = { applyAll = it })
                }
            }
            CategoryGrid(categories, currentKey, onSelect = { onPick(it.key, applyAll) })
            Spacer(Modifier.height(16.dp))
        }
    }
}

