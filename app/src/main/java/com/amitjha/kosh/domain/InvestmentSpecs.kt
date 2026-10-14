package com.amitjha.kosh.domain

import com.amitjha.kosh.finance.Compounding
import com.amitjha.kosh.finance.EpfAccount
import com.amitjha.kosh.finance.FdPayout
import com.amitjha.kosh.finance.FixedDeposit
import com.amitjha.kosh.finance.NpsAccount
import com.amitjha.kosh.finance.NpsHolding
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
    ) : InvestmentSpec {
        override val type get() = InvestmentType.FD
        fun toFinance(bank: String, name: String) =
            FixedDeposit(principal, annualRatePct, startDate, maturityDate, compounding, payout, bank, name)
    }

    data class Epf(
        val balanceAsOf: Paise,
        val asOfDate: LocalDate,
        val monthlyEmployee: Paise,
        val monthlyEmployer: Paise,
        val annualRatePct: Double = EpfAccount.DEFAULT_EPF_RATE_PCT,
        val contributionDay: Int = 15,
    ) : InvestmentSpec {
        override val type get() = InvestmentType.EPF
        fun toFinance() = EpfAccount(balanceAsOf, asOfDate, monthlyEmployee, monthlyEmployer, annualRatePct, contributionDay)
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
        is InvestmentSpec.Epf -> JSONObject()
            .put("type", "EPF")
            .put("balance", spec.balanceAsOf)
            .put("asOf", spec.asOfDate.toString())
            .put("employee", spec.monthlyEmployee)
            .put("employer", spec.monthlyEmployer)
            .put("rate", spec.annualRatePct)
            .put("day", spec.contributionDay)
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
            )
            "EPF" -> InvestmentSpec.Epf(
                balanceAsOf = o.getLong("balance"),
                asOfDate = LocalDate.parse(o.getString("asOf")),
                monthlyEmployee = o.getLong("employee"),
                monthlyEmployer = o.getLong("employer"),
                annualRatePct = o.optDouble("rate", EpfAccount.DEFAULT_EPF_RATE_PCT),
                contributionDay = o.optInt("day", 15),
            )
            "NPS" -> {
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
