package com.amitjha.kosh.finance

import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Paise
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

data class InvestmentValuation(
    val name: String,
    val type: InvestmentType,
    val currentValue: Paise,
    val investedAmount: Paise,
    /** Simple annualised return on the invested amount since [since]; null if not meaningful. */
    val annualisedReturnPct: Double?,
    val since: LocalDate?,
) {
    val gain: Paise get() = currentValue - investedAmount
    val gainPct: Double? get() = if (investedAmount > 0) gain * 100.0 / investedAmount else null
}

data class PortfolioSummary(val items: List<InvestmentValuation>) {
    val totalValue: Paise get() = items.sumOf { it.currentValue }
    val totalInvested: Paise get() = items.sumOf { it.investedAmount }
    val totalGain: Paise get() = totalValue - totalInvested
    fun byType(type: InvestmentType): Paise = items.filter { it.type == type }.sumOf { it.currentValue }
}

data class NamedEpf(val name: String, val account: EpfAccount)
data class NamedNps(val name: String, val account: NpsAccount, val since: LocalDate? = null)

/** Values FDs, EPF and NPS on a date. */
object PortfolioValuer {

    fun value(
        date: LocalDate,
        fds: List<FixedDeposit> = emptyList(),
        epf: List<NamedEpf> = emptyList(),
        nps: List<NamedNps> = emptyList(),
        navs: Map<String, Double> = emptyMap(),
    ): PortfolioSummary {
        val items = mutableListOf<InvestmentValuation>()
        for (fd in fds) {
            val v = fd.valueOn(date)
            val invested = fd.principal
            items += InvestmentValuation(
                fd.name.ifBlank { "FD ${fd.bank}".trim() }, InvestmentType.FD, v, invested,
                annualised(v + if (fd.payout == FdPayout.INTEREST_PAYOUT) fd.interestEarnedTill(date) else 0, invested, fd.startDate, date),
                fd.startDate,
            )
        }
        for (e in epf) {
            val p = e.account.projectBalance(date)
            val invested = e.account.balanceAsOf + p.contributionsSinceAsOf
            items += InvestmentValuation(e.name, InvestmentType.EPF, p.totalIncludingAccrued, invested, null, e.account.asOfDate)
        }
        for (n in nps) {
            val v = n.account.value(navs)
            val invested = n.account.investedAmount
            items += InvestmentValuation(
                n.name, InvestmentType.NPS, v, if (invested > 0) invested else v,
                if (invested > 0 && n.since != null) annualised(v, invested, n.since, date) else null, n.since,
            )
        }
        return PortfolioSummary(items)
    }

    /** Compound annualised return between [start] and [end]; null for periods under 30 days. */
    fun annualised(value: Paise, invested: Paise, start: LocalDate, end: LocalDate): Double? {
        val days = ChronoUnit.DAYS.between(start, end)
        if (invested <= 0 || days < 30) return null
        return ((value.toDouble() / invested).pow(365.0 / days) - 1) * 100
    }
}
