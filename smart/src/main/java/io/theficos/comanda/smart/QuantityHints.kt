package io.theficos.comanda.smart

import io.theficos.comanda.model.*
import io.theficos.comanda.parse.ItemIdentity

class QuantityHints {
    fun hint(key: String, history: HistorySnapshot): Quantity? {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val recent = history.purchases.filter { ItemIdentity.purchaseKey(it, aliases) == key }
            .sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).take(5)
        val counts = recent.mapNotNull { it.quantity }.groupingBy { it }.eachCount()
        return recent.mapNotNull { it.quantity }.distinct().filter { (counts[it] ?: 0) >= 2 }
            .sortedByDescending { counts[it] }.firstOrNull()
    }
}
