package com.amitjha.kosh.data

import com.amitjha.kosh.model.TxnType

/**
 * Built-in categories. Keys match the ones the SMS engine's CategoryGuesser returns,
 * and `icon` names map to Material icons in the UI layer.
 */
object DefaultCategories {
    val all: List<CategoryEntity> = listOf(
        expense("food", "Food & Dining", "restaurant", 0xFFFF7A59),
        expense("groceries", "Groceries", "local_grocery_store", 0xFF34C38F),
        expense("shopping", "Shopping", "shopping_bag", 0xFFE056FD),
        expense("transport", "Transport", "directions_car", 0xFF4DABF7),
        expense("fuel", "Fuel", "local_gas_station", 0xFFFFA94D),
        expense("bills", "Bills & Utilities", "receipt_long", 0xFF748FFC),
        expense("rent", "Rent", "home", 0xFF9775FA),
        expense("entertainment", "Entertainment", "movie", 0xFFF06595),
        expense("health", "Health", "medical_services", 0xFFFF6B6B),
        expense("education", "Education", "school", 0xFF20C997),
        expense("travel", "Travel", "flight", 0xFF3BC9DB),
        expense("subscriptions", "Subscriptions", "subscriptions", 0xFFCC5DE8),
        expense("emi", "EMI & Loans", "account_balance", 0xFFFA5252),
        expense("investment", "Investments", "trending_up", 0xFF51CF66),
        expense("transfer", "Transfers", "swap_horiz", 0xFF868E96),
        expense("other", "Other", "category", 0xFFADB5BD),
        income("salary", "Salary", "payments", 0xFF2F9E44),
        income("refund", "Refunds", "replay", 0xFF1C7ED6),
        income("interest", "Interest", "savings", 0xFF0CA678),
        income("cashback", "Cashback", "redeem", 0xFFF59F00),
        income("other_income", "Other Income", "add_card", 0xFF37B24D),
    )

    private fun expense(key: String, name: String, icon: String, color: Long) =
        CategoryEntity(key = key, name = name, type = TxnType.EXPENSE, icon = icon, colorArgb = color)

    private fun income(key: String, name: String, icon: String, color: Long) =
        CategoryEntity(key = key, name = name, type = TxnType.INCOME, icon = icon, colorArgb = color)
}
