package com.amitjha.kosh.di

import android.content.Context
import com.amitjha.kosh.data.DemoData
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.finance.NavSource
import com.amitjha.kosh.finance.NpsNavApi
import com.amitjha.kosh.gmail.GmailAuth
import com.amitjha.kosh.gmail.GmailSync
import com.amitjha.kosh.notify.Notifications
import com.amitjha.kosh.repo.AccountRepository
import com.amitjha.kosh.repo.BillRepository
import com.amitjha.kosh.repo.BudgetRepository
import com.amitjha.kosh.repo.CategoryRepository
import com.amitjha.kosh.repo.GoalRepository
import com.amitjha.kosh.repo.InsightRepository
import com.amitjha.kosh.repo.InvestmentRepository
import com.amitjha.kosh.repo.NetWorthRepository
import com.amitjha.kosh.repo.TransactionRepository
import com.amitjha.kosh.security.BackupManager
import com.amitjha.kosh.sms.SmsImporter
import com.amitjha.kosh.work.WorkScheduler

/** Manual dependency container; get it with `(context.applicationContext as KoshApp).container`. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val db: KoshDatabase = KoshDatabase.create(app)
    val settings = SettingsStore(app)
    val navSource: NavSource = NpsNavApi()
    val notifications = Notifications(app)

    val accounts = AccountRepository(db)
    val categories = CategoryRepository(db)
    val transactions = TransactionRepository(db)
    val bills = BillRepository(db, transactions)
    val budgets = BudgetRepository(db, settings)
    val goals = GoalRepository(db)
    val investments = InvestmentRepository(db, navSource)
    val insights = InsightRepository(db, accounts, settings)
    val netWorth = NetWorthRepository(db, accounts)

    val smsImporter = SmsImporter(app, db, settings)
    val gmailAuth = GmailAuth(app, settings)
    val gmailSync = GmailSync(db, settings, gmailAuth)
    val backup = BackupManager(app, db)
    val demoData = DemoData(db, investments)

    /** Runs the full daily refresh (NAVs, SMS, Gmail, net worth) once in the background. */
    fun syncNow() = WorkScheduler.syncNow(app)
}
