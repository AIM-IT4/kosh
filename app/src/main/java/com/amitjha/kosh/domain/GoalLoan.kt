package com.amitjha.kosh.domain

import com.amitjha.kosh.model.Paise
import kotlin.math.pow
import kotlin.math.roundToLong

/** The optional loan a user is willing to take when buying a goal, and what it costs. */
object GoalLoan {

    /** The part of the target the user still has to save. */
    fun savingsTarget(target: Paise, loanAmount: Paise): Paise = (target - loanAmount.coerceIn(0, target)).coerceAtLeast(0)

    /** Standard EMI: P·r·(1+r)^n / ((1+r)^n − 1), with r the monthly rate; P/n when the rate is 0. */
    fun emi(principal: Paise, annualRatePct: Double, months: Int): Paise {
        if (principal <= 0 || months <= 0) return 0
        val r = annualRatePct / 100.0 / 12.0
        if (r == 0.0) return (principal.toDouble() / months).roundToLong()
        val f = (1 + r).pow(months)
        return (principal * r * f / (f - 1)).roundToLong()
    }

    /** Interest paid over the whole loan. */
    fun totalInterest(principal: Paise, annualRatePct: Double, months: Int): Paise =
        (emi(principal, annualRatePct, months) * months - principal).coerceAtLeast(0)
}
