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
    const val PENDING_ACCOUNTS = "accounts/pending"
    const val NET_WORTH = "net-worth"
    const val UNCATEGORISED = "uncategorised"
    const val TAX = "tax"
    const val HOUSEHOLD = "household"
    const val RECURRING = "recurring"
    const val RECURRING_EDIT = "recurring/edit?id={id}&series={series}"
    const val LOANS = "loans"
    const val LOAN_DETAIL = "loan/{id}"
    const val LOAN_EDIT = "loan/edit?id={id}"
    const val DEBTS = "debts"
    const val AUTO_BACKUP = "backup/auto"
    const val STATEMENT_IMPORT = "import/statement"
    const val REPORTS = "reports"
    const val YEAR_REVIEW = "reports/year/{year}"
    const val CALENDAR = "calendar"
    const val WARRANTIES = "warranties"
    const val SMS_CHECKUP = "sms/checkup"

    fun addTxn(type: TxnType = TxnType.EXPENSE, accountId: Long? = null) = "txn/add?type=${type.name}&account=${accountId ?: -1}"
    fun editTxn(id: Long) = "txn/edit/$id"
    fun txn(id: Long) = "txn/$id"
    fun account(id: Long) = "account/$id"
    fun editAccount(id: Long? = null, card: Boolean = false) = "account/edit?id=${id ?: -1}&card=$card"
    fun addInvestment(type: InvestmentType) = "investment/add/${type.name}"
    fun editInvestment(id: Long) = "investment/edit/$id"
    fun investment(id: Long) = "investment/$id"
    fun editRecurring(id: Long? = null, fromSeries: String? = null) =
        "recurring/edit?id=${id ?: -1}" + (fromSeries?.let { "&series=" + android.net.Uri.encode(it) } ?: "")
    fun loan(id: Long) = "loan/$id"
    fun editLoan(id: Long? = null) = "loan/edit?id=${id ?: -1}"
    fun yearReview(year: Int) = "reports/year/$year"
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
