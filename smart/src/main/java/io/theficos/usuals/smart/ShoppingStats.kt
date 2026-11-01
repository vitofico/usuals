package io.theficos.usuals.smart

import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.model.Purchase
import io.theficos.usuals.model.Trip
import io.theficos.usuals.parse.ItemIdentity

data class ShoppingUsual(val key: String, val name: String, val shopCount: Int, val lastBoughtAt: Long)
data class ShoppingStats(val shopCount: Int = 0, val purchaseCount: Int = 0, val differentItems: Int = 0,
    val recentShopCount: Int = 0, val usuals: List<ShoppingUsual> = emptyList())

/** Read-only facts from one list's saved history. Quantities with different units are never added. */
fun shoppingStats(now: Long, history: HistorySnapshot): ShoppingStats {
    val trips = history.trips.filter { it.closedAt <= now }
        .sortedWith(compareByDescending<Trip> { it.closedAt }.thenBy { it.id })
    val ids = trips.map { it.id }.toSet()
    val purchases = history.purchases.filter { it.tripId in ids && it.boughtAt <= now }
    val aliases = history.aliases.associate { it.fromKey to it.toKey }
    val groups = purchases.groupBy { ItemIdentity.purchaseKey(it, aliases) }.filterKeys { it.isNotBlank() }
    val recent = trips.take(8).map { it.id }.toSet()
    val usuals = groups.mapNotNull { (key, rows) ->
        val saved = rows.filter { it.tripId in recent }
        val latest = saved.sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).firstOrNull()
            ?: return@mapNotNull null
        ShoppingUsual(key, latest.name, saved.map { it.tripId }.distinct().size, latest.boughtAt)
    }.sortedWith(compareByDescending<ShoppingUsual> { it.shopCount }.thenByDescending { it.lastBoughtAt }.thenBy { it.key }).take(5)
    return ShoppingStats(trips.size, purchases.size, groups.size, recent.size, usuals)
}
