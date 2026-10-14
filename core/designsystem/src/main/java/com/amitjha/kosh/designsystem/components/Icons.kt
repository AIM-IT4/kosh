package com.amitjha.kosh.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AddCard
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.SwapHoriz
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
        "restaurant" to Icons.Outlined.Restaurant,
        "local_grocery_store" to Icons.Outlined.LocalGroceryStore,
        "shopping_bag" to Icons.Outlined.ShoppingBag,
        "directions_car" to Icons.Outlined.DirectionsCar,
        "local_gas_station" to Icons.Outlined.LocalGasStation,
        "receipt_long" to Icons.AutoMirrored.Outlined.ReceiptLong,
        "home" to Icons.Outlined.Home,
        "movie" to Icons.Outlined.Movie,
        "medical_services" to Icons.Outlined.MedicalServices,
        "school" to Icons.Outlined.School,
        "flight" to Icons.Outlined.Flight,
        "subscriptions" to Icons.Outlined.Subscriptions,
        "account_balance" to Icons.Outlined.AccountBalance,
        "trending_up" to Icons.AutoMirrored.Outlined.TrendingUp,
        "swap_horiz" to Icons.Outlined.SwapHoriz,
        "category" to Icons.Outlined.Category,
        "payments" to Icons.Outlined.Payments,
        "replay" to Icons.Outlined.Replay,
        "savings" to Icons.Outlined.Savings,
        "redeem" to Icons.Outlined.Redeem,
        "add_card" to Icons.Outlined.AddCard,
    )

    /** All supported icon names, e.g. for an icon picker. */
    val names: List<String> get() = byName.keys.toList()

    fun forName(name: String?): ImageVector = byName[name] ?: Icons.Outlined.Category
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
    size: Dp = 36.dp,
    contentDescription: String? = null,
) {
    IconBubble(color = color, modifier = modifier, size = size, shape = RoundedCornerShape(size * 0.33f), alpha = 0.10f) {
        Icon(
            imageVector = CategoryIcons.forName(iconName),
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}
