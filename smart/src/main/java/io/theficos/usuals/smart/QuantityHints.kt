package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity

class QuantityHints {
    fun hint(key: String, history: HistorySnapshot): Quantity? {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        return hint(history.purchases.filter { ItemIdentity.purchaseKey(it, aliases) == key })
    }

    /** The hint from one item's purchases, so a whole list groups history once instead of scanning it per row. */
    fun hint(purchases: List<Purchase>): Quantity? {
        val recent = purchases.sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).take(5)
        val counts = recent.mapNotNull { it.quantity }.groupingBy { it }.eachCount()
        return recent.mapNotNull { it.quantity }.distinct().filter { (counts[it] ?: 0) >= 2 }
            .sortedByDescending { counts[it] }.firstOrNull()
    }
}
