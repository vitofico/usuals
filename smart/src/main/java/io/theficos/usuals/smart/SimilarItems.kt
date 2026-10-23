package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
import kotlin.math.abs

data class NameMatch(val item: ItemMemory, val automatic: Boolean)

/** Corrections keep every word; qualifiers are never inferred or discarded. */
class SimilarItems(private val ranker: HistoryRanker) {
    fun confirmedName(name: String, history: HistorySnapshot, active: List<Item>): String? {
        val normalized = Normalizer.normalize(name)
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        if (normalized !in aliases) return null
        val key = ItemIdentity.resolve(normalized, aliases)
        val names = active.filter { ItemIdentity.key(it, aliases) == key }.map { it.name } +
            ranker.memories(history).filter { it.key == key }.map { it.name }
        return names.firstOrNull { correction(normalized, Normalizer.normalize(it)) != null }
    }

    fun candidates(name: String, history: HistorySnapshot, active: List<Item>): List<NameMatch> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val source = ItemIdentity.resolve(Normalizer.normalize(name), aliases)
        val preferences = ItemIdentity.preferences(history.preferences, aliases)
        val memories = (ranker.memories(history).filter { preferences[it.key]?.muted != true } + active.filterNot { it.checked }.map {
            ItemMemory(ItemIdentity.key(it, aliases), it.name, 0, it.updatedAt)
        }).distinctBy { it.key }
        if (Normalizer.normalize(name) in aliases || memories.any { it.key == source }) return emptyList()
        val ranked = memories.mapNotNull { memory ->
            if (memory.key.startsWith("gtin:")) null
            else correction(source, Normalizer.normalize(memory.name))?.let { memory to it }
        }.sortedWith(compareByDescending<Pair<ItemMemory, Boolean>> { it.second }
            .thenByDescending { it.first.purchaseCount }.thenByDescending { it.first.lastBoughtAt }.thenBy { it.first.key })
        // Frequency breaks display ties, but never makes an ambiguous correction automatic.
        return ranked.take(3).map { (memory, safe) -> NameMatch(memory, safe && ranked.size == 1) }
    }

    private fun correction(a: String, b: String): Boolean? {
        if (a == b || a.startsWith("gtin:") || b.startsWith("gtin:")) return null
        val left = a.split(' ')
        val right = b.split(' ')
        if (left.size != right.size) return null
        val changed = left.indices.filter { left[it] != right[it] }
        if (changed.size != 1) return null
        val from = left[changed.single()]
        val to = right[changed.single()]
        if (minOf(from.length, to.length) < 4 || from.any(Char::isDigit) || to.any(Char::isDigit)) return null
        val swap = from.length == to.length && (0 until from.lastIndex).any { index ->
            from[index] == to[index + 1] && from[index + 1] == to[index] &&
                from.take(index) == to.take(index) && from.drop(index + 2) == to.drop(index + 2)
        }
        if (swap) return minOf(from.length, to.length) >= 5
        if (abs(from.length - to.length) > 1) return null
        val oneEdit = if (from.length == to.length) from.indices.count { from[it] != to[it] } == 1
        else {
            val longer = if (from.length > to.length) from else to
            val shorter = if (from.length > to.length) to else from
            longer.indices.any { longer.removeRange(it, it + 1) == shorter }
        }
        if (!oneEdit) return null
        // Only a transposition or an accidental doubled letter can be automatic.
        val doubled = from.length > to.length && from.indices.drop(1).any {
            from[it] == from[it - 1] && from.removeRange(it, it + 1) == to
        }
        return doubled && to.length >= 5
    }
}
