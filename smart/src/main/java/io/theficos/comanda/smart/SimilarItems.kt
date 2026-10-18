package io.theficos.comanda.smart

import io.theficos.comanda.model.*
import io.theficos.comanda.parse.ItemIdentity
import io.theficos.comanda.parse.Normalizer
import kotlin.math.abs

/** A candidate for a question, never permission to merge. */
class SimilarItems(private val ranker: HistoryRanker) {
    fun find(name: String, history: HistorySnapshot, active: List<Item>): ItemMemory? {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val source = ItemIdentity.resolve(Normalizer.normalize(name), aliases)
        val memories = ranker.memories(history) + active.map {
            ItemMemory(ItemIdentity.key(it, aliases), it.name, 0, it.updatedAt)
        }
        if (Normalizer.normalize(name) in aliases || memories.any { it.key == source }) return null
        return memories.distinctBy { it.key }.filter { it.key != source && similar(source, it.key) }
            .sortedWith(compareByDescending<ItemMemory> { it.purchaseCount }.thenByDescending { it.lastBoughtAt }.thenBy { it.key })
            .firstOrNull()
    }

    private fun similar(a: String, b: String): Boolean {
        if (a.startsWith("gtin:") || b.startsWith("gtin:") || minOf(a.length, b.length) < 4) return false
        val left = a.split(' ').toSet()
        val right = b.split(' ').toSet()
        if ((left.containsAll(right) || right.containsAll(left)) &&
            minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length) >= 0.4) return true
        if (abs(a.length - b.length) > 1) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return previous.last() <= 1
    }
}
