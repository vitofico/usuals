package io.theficos.comanda.smart

import io.theficos.comanda.model.*
import io.theficos.comanda.parse.ItemIdentity

data class RepeatItem(val key: String, val name: String)

class RepeatPlanner {
    fun due(now: Long, history: HistorySnapshot, active: List<Item>): List<RepeatItem> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val present = active.map { ItemIdentity.key(it, aliases) }.toSet()
        val presentBarcodes = active.mapNotNull { it.gtin }.toSet()
        val purchases = history.purchases.groupBy { ItemIdentity.purchaseKey(it, aliases) }
        return ItemIdentity.preferences(history.preferences, aliases).mapNotNull { (key, rule) ->
                val days = rule.repeatEveryDays ?: return@mapNotNull null
                if (days <= 0) return@mapNotNull null
                if (key in present || rule.muted) return@mapNotNull null
                val snoozed = rule.snoozedUntil
                val latestTrip = history.trips.maxOfOrNull { it.closedAt } ?: Long.MIN_VALUE
                if (snoozed != null && latestTrip <= snoozed) return@mapNotNull null
                val last = purchases[key].orEmpty().sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).firstOrNull()
                if (last?.gtin != null && last.gtin in presentBarcodes) return@mapNotNull null
                if (last == null || (now >= last.boughtAt && (now - last.boughtAt) / DAY_MILLIS >= days)) {
                    RepeatItem(key, last?.name ?: key)
                } else null
            }.sortedBy { it.key }
    }

    companion object { const val DAY_MILLIS: Long = 86_400_000L }
}
