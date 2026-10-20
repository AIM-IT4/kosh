package com.amitjha.kosh.domain

import com.amitjha.kosh.finance.Compounding
import com.amitjha.kosh.finance.EpfAccount
import com.amitjha.kosh.finance.FdPayout
import com.amitjha.kosh.finance.FixedDeposit
import com.amitjha.kosh.finance.NpsAccount
import com.amitjha.kosh.finance.NpsHolding
import com.amitjha.kosh.finance.PayoutFrequency
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Paise
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** What the user entered for an investment, stored as JSON in `InvestmentEntity.specJson`. */
sealed interface InvestmentSpec {
    val type: InvestmentType

    data class Fd(
        val principal: Paise,
        val annualRatePct: Double,
        val startDate: LocalDate,
        val maturityDate: LocalDate,
        val compounding: Compounding = Compounding.QUARTERLY,
        val payout: FdPayout = FdPayout.CUMULATIVE,
        /** For a payout FD: how often interest is credited. */
        val payoutFrequency: PayoutFrequency = PayoutFrequency.QUARTERLY,
        /** The bank's figure for each credit, when the user entered it. */
        val payoutAmount: Paise? = null,
        /** Day of the month interest is credited, when it isn't the deposit's own date. */
        val payoutDay: Int? = null,
        /** The account the interest is credited to. */
        val payoutAccountId: Long? = null,
    ) : InvestmentSpec {
        override val type get() = InvestmentType.FD
        val paysOut: Boolean get() = payout == FdPayout.INTEREST_PAYOUT
        fun toFinance(bank: String, name: String) =
            FixedDeposit(principal, annualRatePct, startDate, maturityDate, compounding, payout, bank, name, payoutFrequency, payoutAmount, payoutDay)
    }

    data class Epf(
        val balanceAsOf: Paise,
        val asOfDate: LocalDate,
        val monthlyEmployee: Paise,
        val monthlyEmployer: Paise,
        val annualRatePct: Double = EpfAccount.DEFAULT_EPF_RATE_PCT,
        val contributionDay: Int = 15,
        /** True when the entered balance already includes this fiscal year's interest. */
        val includesCurrentYearInterest: Boolean = false,
    ) : InvestmentSpec {
        override val type get() = InvestmentType.EPF
        fun toFinance() = EpfAccount(
            balanceAsOf, asOfDate, monthlyEmployee, monthlyEmployer, annualRatePct, contributionDay, includesCurrentYearInterest,
        )
    }

    data class Nps(
        val holdings: List<NpsHolding>,
        val monthlyContribution: Paise = 0,
        val allocation: Map<String, Double> = emptyMap(),
        val contributionDay: Int = 1,
        val investedAmount: Paise = 0,
        /** Contributions up to and including this date have been applied to the units. */
        val contributionsAppliedTill: LocalDate? = null,
    ) : InvestmentSpec {
        override val type get() = InvestmentType.NPS
        fun toFinance() = NpsAccount(holdings, monthlyContribution, allocation, contributionDay, investedAmount)
    }
}

/**
 * Simple NPS: the value today plus a fixed monthly contribution, growing at an expected yearly return.
 * No schemes or NAVs; the value is projected from [asOfDate] each day.
 */
data class NpsSimple(
    val valueAsOf: Paise,
    val asOfDate: LocalDate,
    val monthlyContribution: Paise,
    val contributionDay: Int = 1,
    val annualReturnPct: Double = DEFAULT_RETURN_PCT,
    /** Amount the user had put in by [asOfDate]; defaults to [valueAsOf] when unknown. */
    val investedAsOf: Paise = valueAsOf,
) : InvestmentSpec {
    override val type get() = InvestmentType.NPS

    /**
     * Value and amount invested on [date]: the balance compounds monthly so that a full year grows by exactly
     * [annualReturnPct] (monthly rate (1 + R)^(1/12) − 1), and each contribution is added on [contributionDay] of
     * every month after [asOfDate].
     */
    fun valueOn(date: LocalDate): kotlin.Pair<Paise, Paise> {
        if (!date.isAfter(asOfDate)) return valueAsOf to investedAsOf
        val r = Math.pow(1 + annualReturnPct / 100.0, 1.0 / 12.0) - 1
        var value = valueAsOf.toDouble()
        var invested = investedAsOf
        var cursor = asOfDate
        var next = contributionDate(asOfDate.withDayOfMonth(1).plusMonths(if (asOfDate.dayOfMonth >= contributionDay.coerceIn(1, 28)) 1 else 0))
        while (!next.isAfter(date)) {
            value *= Math.pow(1 + r, months(cursor, next))
            value += monthlyContribution
            invested += monthlyContribution
            cursor = next
            next = contributionDate(next.withDayOfMonth(1).plusMonths(1))
        }
        value *= Math.pow(1 + r, months(cursor, date))
        return Math.round(value) to invested
    }

    private fun contributionDate(monthStart: LocalDate): LocalDate = monthStart.withDayOfMonth(contributionDay.coerceIn(1, 28))

    /** Whole months between the dates plus the leftover days as a fraction of a month. */
    private fun months(from: LocalDate, to: LocalDate): Double {
        val whole = java.time.temporal.ChronoUnit.MONTHS.between(from, to)
        val rest = java.time.temporal.ChronoUnit.DAYS.between(from.plusMonths(whole), to)
        return whole + rest / 30.436875
    }

    companion object {
        const val DEFAULT_RETURN_PCT = 10.0
    }
}

/** JSON codec for [InvestmentSpec], using Android's org.json. */
object SpecCodec {

    fun encode(spec: InvestmentSpec): String = when (spec) {
        is InvestmentSpec.Fd -> JSONObject()
            .put("type", "FD")
            .put("principal", spec.principal)
            .put("rate", spec.annualRatePct)
            .put("start", spec.startDate.toString())
            .put("maturity", spec.maturityDate.toString())
            .put("compounding", spec.compounding.name)
            .put("payout", spec.payout.name)
            .put("payoutFrequency", spec.payoutFrequency.name)
            .putOpt("payoutAmount", spec.payoutAmount)
            .putOpt("payoutDay", spec.payoutDay)
            .putOpt("payoutAccountId", spec.payoutAccountId)
        is InvestmentSpec.Epf -> JSONObject()
            .put("type", "EPF")
            .put("balance", spec.balanceAsOf)
            .put("asOf", spec.asOfDate.toString())
            .put("employee", spec.monthlyEmployee)
            .put("employer", spec.monthlyEmployer)
            .put("rate", spec.annualRatePct)
            .put("day", spec.contributionDay)
            .put("includesFyInterest", spec.includesCurrentYearInterest)
        is InvestmentSpec.Nps -> JSONObject()
            .put("type", "NPS")
            .put("holdings", JSONArray().apply {
                spec.holdings.forEach { h ->
                    put(
                        JSONObject()
                            .put("code", h.schemeCode)
                            .put("name", h.schemeName)
                            .put("units", h.units)
                            .put("nav", h.lastNav)
                            .putOpt("navDate", h.lastNavDate?.toString()),
                    )
                }
            })
            .put("monthly", spec.monthlyContribution)
            .put("allocation", JSONObject().apply { spec.allocation.forEach { (k, v) -> put(k, v) } })
            .put("day", spec.contributionDay)
            .put("invested", spec.investedAmount)
            .putOpt("appliedTill", spec.contributionsAppliedTill?.toString())
        is NpsSimple -> JSONObject()
            .put("type", "NPS")
            .put("mode", "simple")
            .put("value", spec.valueAsOf)
            .put("asOf", spec.asOfDate.toString())
            .put("monthly", spec.monthlyContribution)
            .put("day", spec.contributionDay)
            .put("return", spec.annualReturnPct)
            .put("invested", spec.investedAsOf)
    }.toString()

    fun decode(json: String): InvestmentSpec {
        val o = JSONObject(json)
        return when (o.getString("type")) {
            "FD" -> InvestmentSpec.Fd(
                principal = o.getLong("principal"),
                annualRatePct = o.getDouble("rate"),
                startDate = LocalDate.parse(o.getString("start")),
                maturityDate = LocalDate.parse(o.getString("maturity")),
                compounding = Compounding.valueOf(o.optString("compounding", Compounding.QUARTERLY.name)),
                payout = FdPayout.valueOf(o.optString("payout", FdPayout.CUMULATIVE.name)),
                payoutFrequency = PayoutFrequency.fromName(o.optString("payoutFrequency", "")),
                payoutAmount = if (o.has("payoutAmount")) o.getLong("payoutAmount") else null,
                payoutDay = if (o.has("payoutDay")) o.getInt("payoutDay") else null,
                payoutAccountId = if (o.has("payoutAccountId")) o.getLong("payoutAccountId") else null,
            )
            "EPF" -> InvestmentSpec.Epf(
                balanceAsOf = o.getLong("balance"),
                asOfDate = LocalDate.parse(o.getString("asOf")),
                monthlyEmployee = o.getLong("employee"),
                monthlyEmployer = o.getLong("employer"),
                annualRatePct = o.optDouble("rate", EpfAccount.DEFAULT_EPF_RATE_PCT),
                contributionDay = o.optInt("day", 15),
                includesCurrentYearInterest = o.optBoolean("includesFyInterest", false),
            )
            "NPS" -> if (o.optString("mode") == "simple") {
                NpsSimple(
                    valueAsOf = o.getLong("value"),
                    asOfDate = LocalDate.parse(o.getString("asOf")),
                    monthlyContribution = o.optLong("monthly", 0),
                    contributionDay = o.optInt("day", 1),
                    annualReturnPct = o.optDouble("return", NpsSimple.DEFAULT_RETURN_PCT),
                    investedAsOf = o.optLong("invested", o.getLong("value")),
                )
            } else {
                val arr = o.getJSONArray("holdings")
                val holdings = (0 until arr.length()).map { i ->
                    val h = arr.getJSONObject(i)
                    NpsHolding(
                        schemeCode = h.getString("code"),
                        schemeName = h.optString("name", h.getString("code")),
                        units = h.getDouble("units"),
                        lastNav = h.getDouble("nav"),
                        lastNavDate = h.optString("navDate", "").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                    )
                }
                val alloc = o.optJSONObject("allocation")
                val allocation = alloc?.keys()?.asSequence()?.associateWith { alloc.getDouble(it) } ?: emptyMap()
                InvestmentSpec.Nps(
                    holdings = holdings,
                    monthlyContribution = o.optLong("monthly", 0),
                    allocation = allocation,
                    contributionDay = o.optInt("day", 1),
                    investedAmount = o.optLong("invested", 0),
                    contributionsAppliedTill = o.optString("appliedTill", "").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                )
            }
            else -> error("unknown investment type ${o.getString("type")}")
        }
    }
}
