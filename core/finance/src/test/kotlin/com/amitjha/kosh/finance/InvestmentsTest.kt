package com.amitjha.kosh.finance

import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private fun rs(x: Double) = Money.fromRupees(x)

class FixedDepositTest {
    private val start = LocalDate.of(2025, 1, 1)

    @Test
    fun quarterlyOneYear() {
        val fd = FixedDeposit(rs(100_000.0), 7.0, start, start.plusYears(1))
        assertEquals(rs(107_185.90), fd.maturityValue())
        assertEquals(rs(7_185.90), fd.interestEarnedTill(start.plusYears(2)))
    }

    @Test
    fun quarterlyFiveYears() {
        val fd = FixedDeposit(rs(100_000.0), 7.5, start, start.plusYears(5))
        assertEquals(rs(144_994.80), fd.maturityValue())
    }

    @Test
    fun partialPeriodUsesSimpleInterest() {
        val fd = FixedDeposit(rs(100_000.0), 7.0, start, start.plusYears(3))
        assertEquals(rs(108_110.93), fd.valueOn(LocalDate.of(2026, 2, 15)))
    }

    @Test
    fun cappedAtMaturityAndPrincipalBeforeStart() {
        val fd = FixedDeposit(rs(100_000.0), 7.0, start, start.plusYears(1))
        assertEquals(fd.maturityValue(), fd.valueOn(start.plusYears(4)))
        assertEquals(rs(100_000.0), fd.valueOn(start.minusDays(3)))
        assertEquals(0L, fd.daysToMaturity(start.plusYears(2)))
        assertEquals(365L, fd.daysToMaturity(start))
        assertTrue(fd.isMatured(start.plusYears(1)))
    }

    @Test
    fun monthlyAndSimple() {
        assertEquals(rs(106_167.78), FixedDeposit(rs(100_000.0), 6.0, start, start.plusYears(1), Compounding.MONTHLY).maturityValue())
        val simple = FixedDeposit(rs(100_000.0), 6.5, start, start.plusDays(200), Compounding.SIMPLE)
        assertEquals(rs(103_561.64), simple.maturityValue())
    }

    @Test
    fun interestPayoutKeepsPrincipal() {
        val fd = FixedDeposit(rs(100_000.0), 7.3, start, start.plusYears(1), payout = FdPayout.INTEREST_PAYOUT)
        assertEquals(rs(100_000.0), fd.valueOn(start.plusDays(100)))
        assertEquals(rs(2_000.0), fd.interestEarnedTill(start.plusDays(100)))
    }
}

class EpfAccountTest {
    // Hand-computed: ₹1,00,000 credited on 31 Mar 2025, ₹2,350/month (1,800 + 550) on the 15th, 8.25%.
    // Month k (1..12) closing balance = 1,00,000 + 2,350k; interest = Σ balance × 0.0825/12
    // = (12,00,000 + 2,350 × 78) × 0.006875 = ₹9,510.1875, credited on 31 Mar 2026.
    private val epf = EpfAccount(rs(100_000.0), LocalDate.of(2025, 3, 31), rs(1_800.0), rs(550.0), 8.25, 15)

    @Test
    fun fullFiscalYearCreditsInterest() {
        val p = epf.projectBalance(LocalDate.of(2026, 3, 31))
        assertEquals(rs(28_200.0), p.contributionsSinceAsOf)
        assertEquals(rs(137_710.19), p.creditedBalance)
        assertEquals(0L, p.accruedInterestNotYetCredited)
    }

    @Test
    fun midYearInterestIsAccruedNotCredited() {
        // Apr-Jun: balances 1,02,350 / 1,04,700 / 1,07,050; interest = 3,14,100 × 0.006875 = 2,159.4375
        val p = epf.projectBalance(LocalDate.of(2025, 6, 30))
        assertEquals(rs(107_050.0), p.creditedBalance)
        assertEquals(rs(2_159.44), p.accruedInterestNotYetCredited)
        assertEquals(rs(109_209.44), p.totalIncludingAccrued)
    }

    @Test
    fun contributionsDue() {
        val due = epf.contributionsDue(LocalDate.of(2025, 4, 15), LocalDate.of(2025, 7, 20))
        assertEquals(listOf(LocalDate.of(2025, 5, 15), LocalDate.of(2025, 6, 15), LocalDate.of(2025, 7, 15)), due.map { it.date })
        assertTrue(due.all { it.amount == rs(2_350.0) })
    }
}

class NpsAccountTest {
    private val account = NpsAccount(
        holdings = listOf(
            NpsHolding("SM008001", "HDFC E", 1000.0, 50.0),
            NpsHolding("SM008002", "HDFC C", 500.0, 40.0),
        ),
        monthlyContribution = rs(10_000.0),
        allocation = mapOf("SM008001" to 0.75, "SM008002" to 0.25),
        contributionDayOfMonth = 5,
        investedAmount = rs(60_000.0),
    )

    @Test
    fun valueUsesLatestNavs() {
        assertEquals(rs(70_000.0), account.value())
        assertEquals(rs(51_101.5 + 20_000.0), account.value(mapOf("SM008001" to 51.1015)))
    }

    @Test
    fun contributionBuysUnitsByAllocation() {
        val r = account.applyContribution(rs(10_000.0), mapOf("SM008001" to 50.0, "SM008002" to 40.0))
        assertEquals(150.0, r.unitsBought.getValue("SM008001"), 1e-9)
        assertEquals(62.5, r.unitsBought.getValue("SM008002"), 1e-9)
        assertEquals(1150.0, r.account.holdings[0].units, 1e-9)
        assertEquals(rs(70_000.0), r.account.investedAmount)
        assertEquals(rs(80_000.0), r.account.value())
    }

    @Test
    fun contributionsDueMonthly() {
        val due = account.contributionsDue(LocalDate.of(2025, 1, 31), LocalDate.of(2025, 3, 5))
        assertEquals(listOf(LocalDate.of(2025, 2, 5), LocalDate.of(2025, 3, 5)), due.map { it.date })
    }
}

class NavJsonTest {
    private val latestSample = """
        {
            "data": [
                {
                    "PFM Code": "PFM001",
                    "PFM Name": "SBI PENSION FUNDS PRIVATE LIMITED",
                    "Scheme Code": "SM001001",
                    "Scheme Name": "SBI PENSION FUND SCHEME - CENTRAL GOVT",
                    "NAV": "49.6166",
                    "1D": "0.33",
                    "Last Updated": "13-10-2026"
                },
                {
                    "PFM Code": "PFM001",
                    "PFM Name": "SBI PENSION FUNDS PRIVATE LIMITED",
                    "Scheme Code": "SM001003",
                    "Scheme Name": "SBI PENSION FUND SCHEME E - TIER I POP",
                    "NAV": "53.5262",
                    "Last Updated": "13-10-2026"
                }
            ]
        }
    """.trimIndent()

    private val detailedSample = """
        {
            "PFM Code": "PFM008",
            "PFM Name": "HDFC PENSION FUND MANAGEMENT LIMITED",
            "Scheme Code": "SM008001",
            "Scheme Name": "NPS TRUST- A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I POP",
            "NAV": "51.1015",
            "5Y": "7.35",
            "Last Updated": "13-10-2026"
        }
    """.trimIndent()

    @Test
    fun parsesLatestList() {
        val quotes = NavJson.parseObjects(latestSample)
        assertEquals(2, quotes.size)
        assertEquals(NavQuote("SM001001", "SBI PENSION FUND SCHEME - CENTRAL GOVT", 49.6166, LocalDate.of(2026, 10, 13)), quotes[0])
        assertEquals(53.5262, quotes[1].nav, 0.0)
    }

    @Test
    fun parsesDetailedObject() {
        val q = NavJson.parseObjects(detailedSample).single()
        assertEquals("SM008001", q.schemeCode)
        assertEquals(51.1015, q.nav, 0.0)
        assertEquals(LocalDate.of(2026, 10, 13), q.date)
    }

    @Test
    fun badInputGivesNothing() {
        assertTrue(NavJson.parseObjects("not json").isEmpty())
        assertNull(NpsNavApi(baseUrl = "http://127.0.0.1:9", timeoutMillis = 500).latestNav("SM008001"))
    }
}

class PortfolioTest {
    @Test
    fun valuesMixedPortfolio() {
        val today = LocalDate.of(2026, 1, 1)
        val fd = FixedDeposit(rs(100_000.0), 7.0, LocalDate.of(2025, 1, 1), LocalDate.of(2027, 1, 1), name = "HDFC FD")
        val nps = NpsAccount(listOf(NpsHolding("A", "A", 100.0, 10.0)), investedAmount = rs(800.0))
        val summary = PortfolioValuer.value(
            today, fds = listOf(fd), nps = listOf(NamedNps("NPS", nps, LocalDate.of(2025, 1, 1))), navs = mapOf("A" to 12.0),
        )
        assertEquals(rs(107_185.90) + rs(1_200.0), summary.totalValue)
        assertEquals(rs(100_800.0), summary.totalInvested)
        assertEquals(rs(1_200.0), summary.byType(InvestmentType.NPS))
        val fdItem = summary.items.first { it.type == InvestmentType.FD }
        assertEquals(7.19, fdItem.annualisedReturnPct!!, 0.01)
        assertEquals(50.0, summary.items.first { it.type == InvestmentType.NPS }.annualisedReturnPct!!, 0.01)
    }
}

class PlanningTest {
    @Test
    fun sipFormula() {
        // target 10L, saved 1L, 24 months at 12% a year (0.9489% a month, which compounds to exactly 12%) -> 32,619.97
        // (annuity), ceiled to the paisa. 12%/12 = 1% a month gave 32,366.13, too little.
        assertEquals(rs(32_619.97), Goals.requiredMonthlySip(rs(1_000_000.0), rs(100_000.0), 24, 12.0))
        assertEquals(rs(37_500.0), Goals.requiredMonthlySip(rs(1_000_000.0), rs(100_000.0), 24, 0.0))
    }

    @Test
    fun goalPlan() {
        val today = LocalDate.of(2026, 1, 10)
        val plan = Goals.plan(rs(120_000.0), rs(60_000.0), LocalDate.of(2026, 7, 10), today)
        assertEquals(6, plan.monthsLeft)
        assertEquals(rs(10_000.0), plan.requiredMonthly)
        assertTrue(plan.isOnTrack(rs(10_000.0)))
        assertTrue(!plan.isOnTrack(rs(9_999.0)))
        assertTrue(Goals.plan(rs(100.0), rs(10.0), today.minusDays(1), today).overdue)
        // Saving happens at month ends: a day past 6 months adds no month-end, so still 6 (rounding up asked too little).
        assertEquals(6, Goals.monthsBetween(today, LocalDate.of(2026, 7, 11)))
        assertEquals(0, Goals.monthsBetween(today, LocalDate.of(2026, 2, 9)))
    }

    @Test
    fun budgetProjection() {
        val p = Budgets.project(rs(30_000.0), rs(15_000.0), LocalDate.of(2026, 4, 10))
        assertEquals(rs(45_000.0), p.projected)
        assertTrue(p.willExceed && !p.isExceeded)
        assertEquals(rs(714.28), p.dailyAllowance) // 15,000 over 21 remaining days incl. today
    }
}
