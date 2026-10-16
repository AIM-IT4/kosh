package com.amitjha.kosh.repo

import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.data.prefs.PlanningPrefs
import com.amitjha.kosh.data.prefs.TaxOverrides
import com.amitjha.kosh.domain.InvestmentSpec
import com.amitjha.kosh.domain.NpsSimple
import com.amitjha.kosh.domain.SmsMapping
import com.amitjha.kosh.finance.Tax
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** The Indian financial year (1 April – 31 March) containing [date]. */
data class FinancialYear(val start: LocalDate) {
    val endInclusive: LocalDate get() = start.plusYears(1).minusDays(1)
    val label: String get() = "FY ${start.year}-${(start.year + 1) % 100}"

    companion object {
        fun containing(date: LocalDate): FinancialYear =
            FinancialYear(LocalDate.of(if (date.monthValue >= 4) date.year else date.year - 1, 4, 1))
    }
}

/** Values Kosh works out from your data, projected to the full financial year (paise). */
data class TaxAuto(
    val grossSalary: Paise = 0,
    val savingsInterest: Paise = 0,
    val fdInterest: Paise = 0,
    val epfEmployee: Paise = 0,
    val lifeInsuranceAndElss: Paise = 0,
    val npsContribution: Paise = 0,
    val healthInsurance: Paise = 0,
    val rent: Paise = 0,
    /** Months of the financial year with data so far (used to project). */
    val monthsElapsed: Int = 1,
)

/** A capped deduction with how much is claimed and what topping it up would save. */
data class DeductionLine(
    val section: String,
    val title: String,
    val claimed: Paise,
    val cap: Paise?,
    /** Old-regime tax saved by filling the rest of the cap, or 0. */
    val savingIfFilled: Paise = 0,
    val auto: Boolean,
)

data class TaxView(
    val fy: FinancialYear,
    val auto: TaxAuto,
    val overrides: TaxOverrides,
    val inputs: Tax.Inputs,
    val comparison: Tax.Comparison,
    val deductions: List<DeductionLine>,
    val tdsWarning: String?,
)

/** Builds the tax view from Kosh's own data plus the user's overrides. Estimates only. */
class TaxRepository(
    private val db: KoshDatabase,
    private val investments: InvestmentRepository,
    private val prefs: PlanningPrefs,
    private val loans: LoanRepository? = null,
) {

    fun observe(): Flow<TaxView> =
        combine(prefs.taxOverrides, db.transactions().observeChanges(), investments.observeItems(), db.loans().observeActive()) { o, _, _, _ -> o }
        .mapLatest { build(it) }
        .flowOn(Dispatchers.Default)

    suspend fun save(o: TaxOverrides) = prefs.setTaxOverrides(o)

    suspend fun build(o: TaxOverrides, today: LocalDate = Time.today()): TaxView {
        val fy = FinancialYear.containing(today)
        val auto = autoValues(fy, today)
        val hra = Tax.hraExemption(o.basicMonthly * 12, o.hraMonthly * 12, o.rentAnnual ?: auto.rent, o.metro)
        // Home-loan figures typed in the planner win; otherwise they come from the loans added in Kosh.
        val homeLoan = loans?.let { runCatching { it.homeLoanYear(fy.start.year) }.getOrNull() }
        val homePrincipal = if (o.homeLoanPrincipal > 0) o.homeLoanPrincipal else homeLoan?.principal ?: 0
        val homeInterest = if (o.homeLoanInterest > 0) o.homeLoanInterest else homeLoan?.interest ?: 0
        val sec80C = o.sec80C ?: (auto.epfEmployee + auto.lifeInsuranceAndElss + homePrincipal)
        val inputs = Tax.Inputs(
            grossSalary = o.grossSalary ?: auto.grossSalary,
            savingsInterest = o.savingsInterest ?: auto.savingsInterest,
            fdInterest = o.fdInterest ?: auto.fdInterest,
            otherIncome = o.otherIncome ?: 0,
            sec80C = sec80C,
            sec80CCD1B = o.sec80CCD1B ?: auto.npsContribution,
            sec80D = o.sec80D ?: auto.healthInsurance,
            hraExemption = hra,
            homeLoanInterest = homeInterest,
            senior = o.senior,
        )
        val comparison = Tax.compare(inputs)
        fun fill(claimed: Paise, cap: Paise, c80: Boolean) =
            if (claimed >= cap) 0L else if (c80) Tax.oldRegimeSavingFrom(inputs, extra80C = cap - claimed)
            else Tax.oldRegimeSavingFrom(inputs, extra80CCD1B = cap - claimed)
        val interestCap = if (o.senior) Tax.CAP_80TTB else Tax.CAP_80TTA
        val deductions = listOf(
            DeductionLine("80C", "EPF, life insurance, ELSS, PPF, home-loan principal", inputs.sec80C, Tax.CAP_80C,
                fill(inputs.sec80C, Tax.CAP_80C, true), auto = o.sec80C == null),
            DeductionLine("80CCD(1B)", "Your own NPS contributions", inputs.sec80CCD1B, Tax.CAP_80CCD1B,
                fill(inputs.sec80CCD1B, Tax.CAP_80CCD1B, false), auto = o.sec80CCD1B == null),
            DeductionLine("80D", "Health insurance premiums", inputs.sec80D, if (o.senior) Tax.CAP_80D_SENIOR else Tax.CAP_80D, auto = o.sec80D == null),
            DeductionLine(if (o.senior) "80TTB" else "80TTA", if (o.senior) "Interest on deposits" else "Savings account interest",
                if (o.senior) inputs.savingsInterest + inputs.fdInterest else inputs.savingsInterest, interestCap, auto = o.savingsInterest == null),
            DeductionLine("HRA", "Rent exemption (old regime)", hra, null, auto = o.rentAnnual == null && o.basicMonthly > 0),
            DeductionLine("24(b)", "Home-loan interest", inputs.homeLoanInterest, Tax.CAP_24B,
                auto = o.homeLoanInterest <= 0 && (homeLoan?.interest ?: 0) > 0),
        )
        val tdsLimit = if (o.senior) 50_000L * 100 else 40_000L * 100
        val tds = if (inputs.fdInterest > tdsLimit) {
            "FD interest of ${com.amitjha.kosh.model.Money.format(inputs.fdInterest, showPaise = false)} this year is above " +
                "${com.amitjha.kosh.model.Money.format(tdsLimit, showPaise = false)}, so banks deduct 10% TDS. Submit Form 15G/15H " +
                "only if your total tax is nil."
        } else null
        return TaxView(fy, auto, o, inputs, comparison, deductions, tds)
    }

    private suspend fun autoValues(fy: FinancialYear, today: LocalDate): TaxAuto {
        val from = Time.startOfDay(fy.start)
        val txns = db.transactions().between(from, System.currentTimeMillis()).filter { SmsMapping.countsInStats(it) }
        val monthsElapsed = (ChronoUnit.MONTHS.between(YearMonth.from(fy.start), YearMonth.from(today)) + 1).toInt().coerceIn(1, 12)
        fun project(ytd: Paise) = ytd * 12 / monthsElapsed

        val salary = txns.filter { it.type == TxnType.INCOME && it.categoryKey == "salary" }
        val salaryMonths = salary.map { YearMonth.from(Time.toDate(it.timestamp)) }.distinct().size
        val grossSalary = if (salaryMonths == 0) 0 else salary.sumOf { it.amount } / salaryMonths * 12

        val interest = txns.filter { it.type == TxnType.INCOME && it.categoryKey == "interest" }.sumOf { it.amount }
        val expenses = txns.filter { it.type == TxnType.EXPENSE }
        val life = expenses.filter { it.categoryKey == "insurance" && LIFE.containsMatchIn(text(it)) } +
            expenses.filter { it.categoryKey == "investment" && ELSS_PPF.containsMatchIn(text(it)) }
        val health = expenses.filter { it.categoryKey == "insurance" && HEALTH.containsMatchIn(text(it)) }
        val rent = expenses.filter { it.categoryKey == "rent" }.sumOf { it.amount }

        val items = investments.items()
        var epf = 0L
        var nps = 0L
        var fdInterest = 0L
        val fyEnd = fy.endInclusive
        for (item in items) {
            when (val s = item.spec) {
                is InvestmentSpec.Epf -> epf += s.monthlyEmployee * 12
                is InvestmentSpec.Nps -> nps += s.monthlyContribution * 12
                is NpsSimple -> nps += s.monthlyContribution * 12
                is InvestmentSpec.Fd -> {
                    val fd = s.toFinance(item.entity.institution, item.entity.name)
                    val start = maxOf(fy.start, s.startDate)
                    val end = minOf(fyEnd, s.maturityDate)
                    if (end.isAfter(start)) fdInterest += (fd.interestEarnedTill(end) - fd.interestEarnedTill(start)).coerceAtLeast(0)
                }
                else -> Unit
            }
        }
        return TaxAuto(
            grossSalary = grossSalary,
            savingsInterest = project(interest),
            fdInterest = fdInterest,
            epfEmployee = epf,
            lifeInsuranceAndElss = life.sumOf { it.amount },
            npsContribution = nps,
            healthInsurance = health.sumOf { it.amount },
            rent = project(rent),
            monthsElapsed = monthsElapsed,
        )
    }

    private fun text(t: TransactionEntity) = listOfNotNull(t.merchant, t.note).joinToString(" ")

    private companion object {
        val LIFE = Regex("(?i)\\b(lic|life|max life|sbi life|hdfc life|icici pru|tata aia|bajaj allianz life|term plan)\\b")
        val ELSS_PPF = Regex("(?i)\\b(elss|tax ?saver|ppf|sukanya|ssy|nsc|tax saving)\\b")
        val HEALTH = Regex("(?i)\\b(health|mediclaim|star health|care health|niva bupa|hdfc ergo|aditya birla health|icici lombard)\\b")
    }
}
