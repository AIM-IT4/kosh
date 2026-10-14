package com.amitjha.kosh.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.ui.graphics.vector.ImageVector
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.TxnType

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val PLANNING = "planning"
    const val WEALTH = "wealth"
    const val MORE = "more"

    const val REVIEW = "review"
    const val ADD_TXN = "txn/add?type={type}&account={account}"
    const val EDIT_TXN = "txn/edit/{id}"
    const val TXN_DETAIL = "txn/{id}"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_DETAIL = "account/{id}"
    const val ACCOUNT_EDIT = "account/edit?id={id}&card={card}"
    const val BILLS = "bills"
    const val INVESTMENT_ADD = "investment/add/{type}"
    const val INVESTMENT_EDIT = "investment/edit/{id}"
    const val INVESTMENT_DETAIL = "investment/{id}"
    const val INSIGHTS = "insights"
    const val CATEGORIES = "categories"

    fun addTxn(type: TxnType = TxnType.EXPENSE, accountId: Long? = null) = "txn/add?type=${type.name}&account=${accountId ?: -1}"
    fun editTxn(id: Long) = "txn/edit/$id"
    fun txn(id: Long) = "txn/$id"
    fun account(id: Long) = "account/$id"
    fun editAccount(id: Long? = null, card: Boolean = false) = "account/edit?id=${id ?: -1}&card=$card"
    fun addInvestment(type: InvestmentType) = "investment/add/${type.name}"
    fun editInvestment(id: Long) = "investment/edit/$id"
    fun investment(id: Long) = "investment/$id"
}

data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

val Tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.TRANSACTIONS, "Activity", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong),
    Tab(Routes.PLANNING, "Plan", Icons.Outlined.TrackChanges, Icons.Filled.TrackChanges),
    Tab(Routes.WEALTH, "Wealth", Icons.AutoMirrored.Outlined.ShowChart, Icons.AutoMirrored.Filled.ShowChart),
    Tab(Routes.MORE, "More", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

val TabRoutes = Tabs.map { it.route }.toSet()
