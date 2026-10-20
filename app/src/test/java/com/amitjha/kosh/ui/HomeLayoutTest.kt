package com.amitjha.kosh.ui

import com.amitjha.kosh.ui.home.HomeCard
import com.amitjha.kosh.ui.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun aStoredOrderKeepsItsCardsAndGainsNewOnesAtTheEnd() {
        val order = HomeLayout.order(listOf("RECENT", "BILLS", "GONE", "RECENT"))
        assertEquals(listOf(HomeCard.RECENT, HomeCard.BILLS), order.take(2))
        assertEquals(HomeCard.entries.size, order.size)
        assertEquals(HomeLayout.DEFAULT, HomeLayout.order(emptyList()))
    }

    @Test
    fun hiddenCardsAreLeftOutAndMovesStayInBounds() {
        val visible = HomeLayout.visible(emptyList(), setOf("HEATMAP", "SIX_MONTHS"))
        assertEquals(HomeCard.entries.size - 2, visible.size)
        val moved = HomeLayout.move(HomeLayout.DEFAULT, HomeCard.BILLS, -1)
        assertEquals(listOf(HomeCard.THIS_MONTH, HomeCard.BILLS, HomeCard.SAFE_TO_SPEND), moved.take(3))
        assertEquals(HomeLayout.DEFAULT, HomeLayout.move(HomeLayout.DEFAULT, HomeCard.THIS_MONTH, -1))
        assertEquals(HomeLayout.DEFAULT, HomeLayout.move(HomeLayout.DEFAULT, HomeCard.ACCOUNTS, 1))
    }
}
