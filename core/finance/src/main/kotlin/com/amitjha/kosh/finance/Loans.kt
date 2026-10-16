package com.amitjha.kosh.finance

import com.amitjha.kosh.model.Paise
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Amortising loans (home, car, personal) worked out the way Indian lenders do.
 *
 * Conventions:
 * - The monthly rate is r = annualRatePct / 12 / 100. The EMI is P·r·(1+r)^n / ((1+r)^n − 1), the same formula
 *   as [GoalLoan.emi], but rounded UP to the next whole rupee as lenders charge it; at 0% it is P/n rounded up
 *   to a rupee. A lender-quoted [Spec.emi] is used as given, even when it differs slightly from the formula.
 * - Each month, interest = balance × r rounded half-up to the paisa and principal = EMI − interest. The last
 *   instalment is cut down so the balance ends at exactly 0, so a schedule can have more or fewer rows than
 *   the tenure when the EMI differs from the formula's.
 * - EMI k falls on [Spec.firstEmiDate] plus k − 1 months, clamped to month end (31 Jan, 29 Feb, 31 Mar...).
 * - A prepayment dated on or after EMI k's date and before EMI k+1's is applied right after EMI k, so EMI k+1
 *   already charges interest on the lower balance; one dated before the first EMI is applied before it. It is
 *   capped at the balance then owed. [Reduce.TENURE] keeps the EMI and ends the loan sooner; [Reduce.EMI]
 *   recomputes the EMI (rounded up to a rupee) over the months left of the original tenure, keeping the end
 *   date. When no months of the original tenure are left, the EMI stays.
 */
object Loans {

    /** What a prepayment lowers: the number of EMIs left, or the EMI itself. */
    enum class Reduce { TENURE, EMI }

    data class Prepayment(val date: LocalDate, val amount: Paise, val reduce: Reduce = Reduce.TENURE) {
        init {
            require(amount > 0) { "prepayment must be positive" }
        }
    }

    data class Spec(
        val principal: Paise,
        val annualRatePct: Double,
        val tenureMonths: Int,
        /** Date of the first EMI; later EMIs fall on the same day of month (clamped to month end). */
        val firstEmiDate: LocalDate,
        /** The EMI the lender charges, when known; null computes it. */
        val emi: Paise? = null,
        val prepayments: List<Prepayment> = emptyList(),
    ) {
        init {
            require(principal > 0) { "principal must be positive" }
            require(tenureMonths > 0) { "tenure must be positive" }
            require(annualRatePct >= 0) { "rate must not be negative" }
            require(emi == null || emi > 0) { "EMI must be positive" }
        }
    }

    /**
     * One EMI. [index] counts from 1 and [emi] = [interest] + [principal]. [prepayment] is what was prepaid
     * right after this EMI (on row 1 it also includes anything prepaid before the first EMI), and [balance] is
     * what is owed after both.
     */
    data class Row(
        val index: Int,
        val date: LocalDate,
        val emi: Paise,
        val interest: Paise,
        val principal: Paise,
        val prepayment: Paise,
        val balance: Paise,
    )

    /** EMI for [principal] repaid over [months] at [annualRatePct], rounded up to the next whole rupee. */
    fun emi(principal: Paise, annualRatePct: Double, months: Int): Paise {
        require(principal > 0) { "principal must be positive" }
        require(months > 0) { "months must be positive" }
        require(annualRatePct >= 0) { "rate must not be negative" }
        val r = annualRatePct / 12 / 100
        if (r == 0.0) return (principal + 100L * months - 1) / (100L * months) * 100
        val f = (1 + r).pow(months)
        // The small tolerance stops floating-point noise on an exact rupee amount from adding a rupee.
        return ceil(principal * r * f / (f - 1) / 100 - 1e-6).toLong() * 100
    }

    /** Every EMI until the loan is repaid, with the prepayments applied. */
    fun schedule(spec: Spec): List<Row> = amortise(spec).rows

    /**
     * Balance still owed after every EMI and prepayment dated on or before [asOf]. Before the first EMI it is
     * the principal (less any prepayment already made); after the loan is repaid it is 0.
     */
    fun outstanding(spec: Spec, asOf: LocalDate): Paise {
        val loan = amortise(spec)
        val repaid = loan.rows.filter { !it.date.isAfter(asOf) }.sumOf { it.principal } +
            loan.prepaid.filter { !it.date.isAfter(asOf) }.sumOf { it.amount }
        return spec.principal - repaid
    }

    /** Interest paid over the whole loan, prepayments included. */
    fun totalInterest(spec: Spec): Paise = amortise(spec).rows.sumOf { it.interest }

    /**
     * What a prepayment changes: the interest no longer paid, the EMIs no longer due, the EMI charged after it
     * (0 when it closes the loan) and the date of the last EMI.
     */
    data class PrepaymentImpact(val interestSaved: Paise, val monthsSaved: Int, val newEmi: Paise, val newEndDate: LocalDate)

    /** Effect of prepaying [amount] on [date] (added to the spec's existing prepayments). */
    fun prepaymentImpact(spec: Spec, date: LocalDate, amount: Paise, reduce: Reduce): PrepaymentImpact {
        val before = amortise(spec)
        val after = amortise(spec.copy(prepayments = spec.prepayments + Prepayment(date, amount, reduce)))
        val next = after.rows.indexOfFirst { it.date.isAfter(date) }
        return PrepaymentImpact(
            interestSaved = before.rows.sumOf { it.interest } - after.rows.sumOf { it.interest },
            monthsSaved = before.rows.size - after.rows.size,
            newEmi = if (next < 0) 0L else after.regularEmi[next],
            newEndDate = after.rows.last().date,
        )
    }

    data class YearSplit(val interest: Paise, val principal: Paise)

    /**
     * Interest and principal (EMI principal plus prepayments) paid in the Indian financial year April
     * [fyStartYear] to March [fyStartYear]+1. EMIs count by their date and prepayments by their own date, which
     * can fall in a later year than the row that shows them.
     */
    fun financialYear(spec: Spec, fyStartYear: Int): YearSplit {
        val from = LocalDate.of(fyStartYear, 4, 1)
        val to = LocalDate.of(fyStartYear + 1, 3, 31)
        fun inYear(date: LocalDate) = !date.isBefore(from) && !date.isAfter(to)
        val loan = amortise(spec)
        val rows = loan.rows.filter { inYear(it.date) }
        return YearSplit(
            interest = rows.sumOf { it.interest },
            principal = rows.sumOf { it.principal } + loan.prepaid.filter { inYear(it.date) }.sumOf { it.amount },
        )
    }

    /** A prepayment as it was applied: on its date, capped at the balance. */
    private class Applied(val date: LocalDate, val amount: Paise)

    /** The schedule, the regular (uncapped) EMI due on each row, and the prepayments as applied. */
    private class Run(val rows: List<Row>, val regularEmi: List<Paise>, val prepaid: List<Applied>)

    private fun amortise(spec: Spec): Run {
        val prepayments = spec.prepayments.sortedBy { it.date }
        var next = 0
        var balance = spec.principal
        var instalment = spec.emi ?: emi(spec.principal, spec.annualRatePct, spec.tenureMonths)
        require(instalment > interestOn(spec.principal, spec.annualRatePct)) { "EMI must exceed the first month's interest" }
        val rows = ArrayList<Row>()
        val regular = ArrayList<Paise>()
        val prepaid = ArrayList<Applied>()

        // Applies, in date order, the prepayments dated before [until], once [emisPaid] EMIs have been paid.
        fun prepayBefore(until: LocalDate, emisPaid: Int): Paise {
            var total = 0L
            while (next < prepayments.size && prepayments[next].date.isBefore(until)) {
                val p = prepayments[next++]
                val amount = minOf(p.amount, balance)
                if (amount == 0L) continue
                balance -= amount
                total += amount
                prepaid += Applied(p.date, amount)
                val monthsLeft = spec.tenureMonths - emisPaid
                if (p.reduce == Reduce.EMI && balance > 0 && monthsLeft > 0) {
                    instalment = emi(balance, spec.annualRatePct, monthsLeft)
                }
            }
            return total
        }

        // Prepaid before the first EMI; shown on row 1.
        var carried = prepayBefore(spec.firstEmiDate, 0)
        var k = 1
        while (balance > 0) {
            val interest = interestOn(balance, spec.annualRatePct)
            val pay = minOf(instalment, balance + interest)
            val principal = pay - interest
            check(principal > 0) { "EMI does not cover the interest" }
            balance -= principal
            regular += instalment
            val extra = carried + prepayBefore(dueDate(spec, k + 1), k)
            carried = 0
            rows += Row(k, dueDate(spec, k), pay, interest, principal, extra, balance)
            k++
        }
        // Repaid in full before the first EMI: one row records the prepayment.
        if (rows.isEmpty()) {
            rows += Row(1, spec.firstEmiDate, 0, 0, 0, carried, 0)
            regular += 0L
        }
        return Run(rows, regular, prepaid)
    }

    /** Date of EMI [k] (from 1), counted from the first EMI so a 31st stays a 31st where the month has one. */
    private fun dueDate(spec: Spec, k: Int): LocalDate = spec.firstEmiDate.plusMonths((k - 1).toLong())

    /** balance × annualRatePct / 1200, rounded half-up to the paisa; decimal so a rate like 8.5 is exact. */
    private fun interestOn(balance: Paise, annualRatePct: Double): Paise =
        BigDecimal.valueOf(balance).multiply(BigDecimal.valueOf(annualRatePct))
            .divide(BigDecimal(1200), 0, RoundingMode.HALF_UP).toLong()
}
