package com.amitjha.kosh.domain

import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.finance.GoalLoan as Engine

/** The optional purchase loan of a goal; delegates to the finance engine so screens and insights agree. */
object GoalLoan {
    fun savingsTarget(target: Paise, loanAmount: Paise): Paise = Engine.savingsTarget(target, loanAmount)
    fun emi(principal: Paise, annualRatePct: Double, months: Int): Paise = Engine.emi(principal, annualRatePct, months)
    fun totalInterest(principal: Paise, annualRatePct: Double, months: Int): Paise = Engine.totalInterest(principal, annualRatePct, months)
}
