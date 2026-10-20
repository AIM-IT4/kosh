package com.amitjha.kosh.di

import android.content.Context
import com.amitjha.kosh.data.DemoData
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.finance.NavSource
import com.amitjha.kosh.finance.NpsNavApi
import com.amitjha.kosh.notify.Notifications
import com.amitjha.kosh.repo.AccountRepository
import com.amitjha.kosh.repo.BillRepository
import com.amitjha.kosh.repo.BudgetRepository
import com.amitjha.kosh.repo.CategoryRepository
import com.amitjha.kosh.repo.GoalRepository
import com.amitjha.kosh.repo.InsightRepository
import com.amitjha.kosh.repo.InterestRepository
import com.amitjha.kosh.repo.InvestmentRepository
import com.amitjha.kosh.repo.NetWorthRepository
import com.amitjha.kosh.repo.TransactionRepository
import com.amitjha.kosh.security.BackupManager
import com.amitjha.kosh.sms.SmsImporter
import com.amitjha.kosh.work.WorkScheduler

/** Manual dependency container; get it with `(context.applicationContext as KoshApp).container`. */
/**
 * [sandbox] is set for the decoy profile: an in-memory database, separate settings and no SMS access, so
 * nothing real can ever be shown or written there.
 */
class AppContainer(context: Context, sandbox: com.amitjha.kosh.security.DecoySandbox? = null) {
    private val app = context.applicationContext

    val db: KoshDatabase = sandbox?.db ?: KoshDatabase.create(app)
    val settings = sandbox?.settings ?: SettingsStore(app)
    // The offline edition has no INTERNET permission; it never tries to fetch NPS prices.
    val navSource: NavSource = if (com.amitjha.kosh.BuildConfig.NETWORK_ENABLED) NpsNavApi() else OfflineNavSource
    val notifications = Notifications(app)

    val accounts = AccountRepository(db)
    val family = com.amitjha.kosh.repo.FamilyRepository(db)
    val loans = com.amitjha.kosh.repo.LoanRepository(db)
    val categories = CategoryRepository(db)
    val transactions = TransactionRepository(db)
    val bills = BillRepository(db, transactions)
    val featurePrefs = com.amitjha.kosh.data.prefs.featurePrefsFor(app, decoy = sandbox != null)
    val budgets = BudgetRepository(db, settings, featurePrefs)
    val planningPrefs = com.amitjha.kosh.data.prefs.PlanningPrefs(app)
    val goals = GoalRepository(db, settings, planningPrefs, featurePrefs)
    val investments = InvestmentRepository(db, navSource)
    val insights = InsightRepository(db, accounts, settings, budgets, planningPrefs, featurePrefs)
    val netWorth = NetWorthRepository(db, accounts, loans)
    val interest = InterestRepository(db, accounts)
    val tax = com.amitjha.kosh.repo.TaxRepository(db, investments, planningPrefs, loans)
    val goalOutlooks = com.amitjha.kosh.repo.GoalSimulations(goals, planningPrefs)

    val smsImporter = SmsImporter(sandbox?.smsContext ?: app, db, settings)
    /** Receipt files, encrypted in app storage. */
    val attachmentStore = com.amitjha.kosh.data.AttachmentStore(app)
    val backup = BackupManager(app, db, attachmentStore)
    val recurring = com.amitjha.kosh.repo.RecurringRepository(db, featurePrefs)
    val debts = com.amitjha.kosh.repo.DebtRepository(db)
    val attachments = com.amitjha.kosh.repo.AttachmentRepository(db, attachmentStore)
    val statementImporter = com.amitjha.kosh.repo.StatementImporter(db)
    val reports = com.amitjha.kosh.repo.ReportRepository(db, settings, transactions, recurring)
    val demoData = DemoData(db, investments)
    /** Household sync with a paired phone over local Wi-Fi (standard edition only). */
    val householdStore = com.amitjha.kosh.sync.HouseholdStore(app)
    val household = com.amitjha.kosh.sync.HouseholdRepository(db, householdStore)
    val householdSync = com.amitjha.kosh.sync.HouseholdSync(app, household, householdStore)

    /** Runs the full daily refresh (NAVs, SMS, net worth) once in the background. */
    fun syncNow() = WorkScheduler.syncNow(app)
}

/** NAV source for the offline edition: never touches the network. */
object OfflineNavSource : NavSource {
    override fun latestNav(schemeCode: String): com.amitjha.kosh.finance.NavQuote? = null
    override fun allSchemes(): List<com.amitjha.kosh.finance.NavQuote> = emptyList()
}
