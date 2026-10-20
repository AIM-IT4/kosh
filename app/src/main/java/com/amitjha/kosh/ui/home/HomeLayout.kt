package com.amitjha.kosh.ui.home

/** The cards Home can show under the net worth and the to-do list, which the user can reorder or hide. */
enum class HomeCard(val title: String) {
    THIS_MONTH("This month"),
    SAFE_TO_SPEND("Safe to spend"),
    BILLS("Upcoming bills"),
    INSIGHT("Insight"),
    RECENT("Recent"),
    WHERE_IT_WENT("Where it went"),
    PACE("Spending pace"),
    NET_WORTH("Net worth trend"),
    SIX_MONTHS("Income vs spending"),
    HEATMAP("Daily spending"),
    ACCOUNTS("Accounts"),
}

/** Home's card order: the everyday cards first, the charts after. */
object HomeLayout {
    val DEFAULT: List<HomeCard> = HomeCard.entries

    /** The stored order (card names), with unknown names dropped and cards added since appended in default order. */
    fun order(stored: List<String>): List<HomeCard> {
        val known = stored.mapNotNull { name -> HomeCard.entries.firstOrNull { it.name == name } }.distinct()
        return known + DEFAULT.filter { it !in known }
    }

    /** The cards to draw, in order. */
    fun visible(stored: List<String>, hidden: Set<String>): List<HomeCard> = order(stored).filter { it.name !in hidden }

    /** [order] with [card] moved one place up ([by] = -1) or down (+1). */
    fun move(order: List<HomeCard>, card: HomeCard, by: Int): List<HomeCard> {
        val i = order.indexOf(card)
        val j = i + by
        if (i < 0 || j !in order.indices) return order
        return order.toMutableList().also { it[i] = order[j]; it[j] = card }
    }
}
