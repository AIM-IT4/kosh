package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalancesTest {

    private val bank = AccountEntity(id = 1, name = "HDFC", type = AccountType.BANK, bank = "HDFC", openingBalance = 50_000_00, openingDate = 1_000)

    @Test
    fun bankUsesOpeningPlusFlow() {
        val b = Balances.compute(bank, netFlowSinceOpening = -12_000_00, netFlowSinceReported = null)
        assertEquals(38_000_00L, b.balance)
        assertEquals(0L, b.outstanding)
        assertNull(b.availableLimit)
    }

    @Test
    fun reportedBalanceIsTheAnchor() {
        val reported = bank.copy(reportedBalance = 41_250_00, reportedAt = 5_000)
        val b = Balances.compute(reported, netFlowSinceOpening = -12_000_00, netFlowSinceReported = -250_00)
        assertEquals(41_000_00L, b.balance)
    }

    @Test
    fun reportedBeforeOpeningIsIgnored() {
        val stale = bank.copy(reportedBalance = 1L, reportedAt = 500)
        assertEquals(50_000_00L, Balances.compute(stale, 0, 0).balance)
    }

    @Test
    fun cardOutstandingFromFlow() {
        val card = AccountEntity(id = 2, name = "Card", type = AccountType.CREDIT_CARD, bank = "HDFC", openingBalance = 5_000_00, creditLimit = 1_00_000_00)
        // Spent 20k, paid 8k: net flow -12k, so outstanding 5k + 12k.
        val b = Balances.compute(card, netFlowSinceOpening = -12_000_00, netFlowSinceReported = null)
        assertEquals(17_000_00L, b.outstanding)
        assertEquals(-17_000_00L, b.balance)
        assertEquals(83_000_00L, b.availableLimit)
    }

    @Test
    fun cardOutstandingFromReportedLimit() {
        val card = AccountEntity(
            id = 2, name = "Card", type = AccountType.CREDIT_CARD, bank = "HDFC",
            creditLimit = 1_00_000_00, reportedBalance = 85_000_00, reportedAt = 10_000, openingDate = 0,
        )
        // 15k used when the SMS arrived, then another 2k spent.
        val b = Balances.compute(card, netFlowSinceOpening = -40_000_00, netFlowSinceReported = -2_000_00)
        assertEquals(17_000_00L, b.outstanding)
        assertEquals(83_000_00L, b.availableLimit)
    }
}
