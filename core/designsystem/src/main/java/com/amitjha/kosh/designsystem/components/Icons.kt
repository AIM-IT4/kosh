package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Maps the app's category icon names (as stored in the categories table) to Material icons.
 * Unknown names fall back to a generic category icon.
 */
object CategoryIcons {
    private val byName: Map<String, ImageVector> = mapOf(
        "restaurant" to Icons.Filled.Restaurant,
        "local_grocery_store" to Icons.Filled.LocalGroceryStore,
        "shopping_bag" to Icons.Filled.ShoppingBag,
        "directions_car" to Icons.Filled.DirectionsCar,
        "local_gas_station" to Icons.Filled.LocalGasStation,
        "receipt_long" to Icons.AutoMirrored.Filled.ReceiptLong,
        "home" to Icons.Filled.Home,
        "movie" to Icons.Filled.Movie,
        "medical_services" to Icons.Filled.MedicalServices,
        "school" to Icons.Filled.School,
        "flight" to Icons.Filled.Flight,
        "subscriptions" to Icons.Filled.Subscriptions,
        "account_balance" to Icons.Filled.AccountBalance,
        "trending_up" to Icons.AutoMirrored.Filled.TrendingUp,
        "swap_horiz" to Icons.Filled.SwapHoriz,
        "category" to Icons.Filled.Category,
        "payments" to Icons.Filled.Payments,
        "replay" to Icons.Filled.Replay,
        "savings" to Icons.Filled.Savings,
        "redeem" to Icons.Filled.Redeem,
        "add_card" to Icons.Filled.AddCard,
    )

    /** All supported icon names, e.g. for an icon picker. */
    val names: List<String> get() = byName.keys.toList()

    fun forName(name: String?): ImageVector = byName[name] ?: Icons.Filled.Category
}

/**
 * A category icon in a tinted rounded-square bubble.
 *
 * @param iconName an icon name from the categories table (see [CategoryIcons]).
 * @param color the category colour (ARGB from the table, wrapped in [Color]).
 */
@Composable
fun CategoryIcon(
    iconName: String?,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    contentDescription: String? = null,
) {
    IconBubble(color = color, modifier = modifier, size = size, shape = RoundedCornerShape(size * 0.34f)) {
        Icon(
            imageVector = CategoryIcons.forName(iconName),
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
