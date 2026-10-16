package com.amitjha.kosh.repo

import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.data.prefs.SettingsStore
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** One row of a report's transaction list. */
data class ReportLine(val date: LocalDate, val title: String, val category: String, val account: String, val amount: Paise, val type: TxnType)

/** Everything a monthly or yearly report shows. */
data class Report(
    val title: String,
    val from: LocalDate,
    val toExclusive: LocalDate,
    val summary: MonthSummary,
    /** One summary per month of the range (a single entry for a monthly report). */
    val months: List<MonthSummary>,
    val lines: List<ReportLine>,
    val categoryNames: Map<String, String>,
)

/** Highlights of one calendar year. */
data class YearReview(
    val year: Int,
    val income: Paise,
    val expense: Paise,
    val transactions: Int,
    val months: List<MonthSummary>,
    val topCategories: List<CategorySpend>,
    val topMerchants: List<MerchantSpend>,
    val biggestExpense: ReportLine?,
    val busiestWeekday: DayOfWeek?,
    val noSpendDays: Int,
    val daysCounted: Int,
    val subscriptionsMonthly: Paise,
) {
    val saved: Paise get() = income - expense
    val savingsRate: Double? get() = if (income > 0) saved.toDouble() / income else null
    val bestMonth: MonthSummary? get() = months.filter { it.income > 0 || it.expense > 0 }.maxByOrNull { it.savings }
    val costliestMonth: MonthSummary? get() = months.maxByOrNull { it.expense }?.takeIf { it.expense > 0 }
}

class ReportRepository(
    private val db: KoshDatabase,
    private val settings: SettingsStore,
    private val transactions: TransactionRepository,
    private val recurring: RecurringRepository,
) {

    /** The budgeting month [month] (starting on the salary day when one is set). */
    suspend fun monthly(month: YearMonth): Report {
        val p = Period.of(month, settings.current().monthStartDay)
        return build("Monthly report", p.start, p.endExclusive, listOf(p))
    }

    /** Calendar year [year] (fyStart = false) or the financial year starting April [year]. */
    suspend fun yearly(year: Int, financialYear: Boolean): Report {
        val from = if (financialYear) LocalDate.of(year, 4, 1) else LocalDate.of(year, 1, 1)
        val to = from.plusYears(1)
        val months = (0 until 12).map { Period(from.plusMonths(it.toLong()), from.plusMonths(it + 1L)) }
        val title = if (financialYear) "Financial year ${year}-${(year + 1) % 100}" else "Year $year"
        return build(title, from, to, months)
    }

    suspend fun yearInReview(year: Int, today: LocalDate = Time.today()): YearReview {
        val report = yearly(year, financialYear = false)
        val counted = countedExpenses(report.from, report.toExclusive)
        val lastDay = minOf(today, report.toExclusive.minusDays(1))
        val days = if (lastDay.isBefore(report.from)) 0 else (lastDay.toEpochDay() - report.from.toEpochDay() + 1).toInt()
        val spendDays = counted.map { Time.toDate(it.timestamp) }.filter { !it.isAfter(lastDay) }.toSet()
        val byWeekday = counted.groupBy { Time.toDate(it.timestamp).dayOfWeek }.mapValues { (_, v) -> v.sumOf { it.amount } }
        return YearReview(
            year = year,
            income = report.summary.income,
            expense = report.summary.expense,
            transactions = report.summary.transactionCount,
            months = report.months,
            topCategories = report.summary.byCategory.take(5),
            topMerchants = report.summary.topMerchants.take(5),
            biggestExpense = report.lines.filter { it.type == TxnType.EXPENSE }.maxByOrNull { it.amount },
            busiestWeekday = byWeekday.maxByOrNull { it.value }?.key,
            noSpendDays = (days - spendDays.size).coerceAtLeast(0),
            daysCounted = days,
            subscriptionsMonthly = runCatching { recurring.detected(today).filter { !it.overdue }.sumOf { it.monthlyEquivalent } }.getOrDefault(0),
        )
    }

    /** Spending per day between [from] and [toExclusive], for the calendar. */
    suspend fun dailySpend(from: LocalDate, toExclusive: LocalDate): Map<LocalDate, Paise> =
        countedExpenses(from, toExclusive).groupBy { Time.toDate(it.timestamp) }.mapValues { (_, v) -> v.sumOf { it.amount } }

    private suspend fun countedExpenses(from: LocalDate, toExclusive: LocalDate): List<TransactionEntity> =
        db.transactions().between(Time.startOfDay(from), Time.startOfDay(toExclusive) - 1)
            .filter { it.type == TxnType.EXPENSE && SmsMapping.countsInStats(it) }

    private suspend fun build(title: String, from: LocalDate, to: LocalDate, periods: List<Period>): Report {
        val txns = db.transactions().between(Time.startOfDay(from), Time.startOfDay(to) - 1)
        val categories = db.categories().all().associateBy { it.key }
        val accounts = db.accounts().all().associateBy { it.id }
        val splits = transactions.splitsByTransaction()
        val whole = Period(from, to)
        val lines = txns.filter { SmsMapping.countsInStats(it) && it.type != TxnType.TRANSFER }
            .sortedBy { it.timestamp }
            .map {
                ReportLine(
                    date = Time.toDate(it.timestamp),
                    title = it.merchant?.takeIf { m -> m.isNotBlank() } ?: categories[it.categoryKey]?.name ?: it.categoryKey,
                    category = categories[it.categoryKey]?.name ?: it.categoryKey,
                    account = accounts[it.accountId]?.name.orEmpty(),
                    amount = it.amount,
                    type = it.type,
                )
            }
        return Report(
            title = title,
            from = from,
            toExclusive = to,
            summary = TransactionRepository.summarise(whole, txns, categories, splits),
            months = periods.map { TransactionRepository.summarise(it, txns, categories, splits) },
            lines = lines,
            categoryNames = categories.mapValues { it.value.name },
        )
    }

    suspend fun monthStartDay(): Int = settings.current().monthStartDay
}
