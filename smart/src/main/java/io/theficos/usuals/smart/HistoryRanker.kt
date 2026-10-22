package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer

data class ItemMemory(val key: String, val name: String, val purchaseCount: Int, val lastBoughtAt: Long)

class HistoryRanker {
    fun memories(history: HistorySnapshot): List<ItemMemory> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        return history.purchases.groupBy { ItemIdentity.purchaseKey(it, aliases) }.map { (key, purchases) ->
            val latest = purchases.sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).first()
            ItemMemory(key, latest.name, purchases.size, latest.boughtAt)
        }.sortedBy { it.key }
    }

    fun autocomplete(query: String, history: HistorySnapshot, excluded: Set<String> = emptySet(), limit: Int = 5): List<ItemMemory> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val muted = ItemIdentity.preferences(history.preferences, aliases).filterValues { it.muted }.keys
        return autocomplete(query, memories(history), excluded + muted, limit)
    }

    /** Takes [memories] already grouped, so a caller can reuse one grouping of history across keystrokes. */
    fun autocomplete(query: String, memories: List<ItemMemory>, excluded: Set<String> = emptySet(), limit: Int = 5): List<ItemMemory> {
        val key = Normalizer.normalize(query)
        if (key.isBlank()) return emptyList()
        return memories.filter { it.key !in excluded &&
            (it.key.contains(key) || Normalizer.normalize(it.name).contains(key)) }
            .sortedWith(compareByDescending<ItemMemory> { it.key.startsWith(key) }
                .thenByDescending { it.purchaseCount }.thenByDescending { it.lastBoughtAt }.thenBy { it.key })
            .take(limit)
    }
}
