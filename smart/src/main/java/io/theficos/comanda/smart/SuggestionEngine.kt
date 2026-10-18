package io.theficos.comanda.smart

import io.theficos.comanda.model.*
import io.theficos.comanda.parse.ItemIdentity

enum class SuggestionReason { REPEAT, INTERVAL, STAPLE }
enum class SuggestionChoice { ADD, NOT_NOW, NEVER }
data class Suggestion(val key: String, val name: String, val reason: SuggestionReason)

class SuggestionEngine(
    private val repeats: RepeatPlanner,
    private val intervals: IntervalModel,
    private val ranker: HistoryRanker,
) {
    fun rank(now: Long, history: HistorySnapshot, active: List<Item>, intervalsEnabled: Boolean = false): List<Suggestion> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val present = active.map { ItemIdentity.key(it, aliases) }.toSet()
        val preferences = ItemIdentity.preferences(history.preferences, aliases)
        val latestTrip = history.trips.maxOfOrNull { it.closedAt } ?: Long.MIN_VALUE
        fun allowed(key: String): Boolean {
            val pref = preferences[key]
            return key !in present && pref?.muted != true &&
                (pref?.snoozedUntil?.let { latestTrip > it } != false)
        }
        val explicit = repeats.due(now, history, active).map { Suggestion(it.key, it.name, SuggestionReason.REPEAT) }
        val predicted = if (intervalsEnabled) intervals.due(now, history).map {
            Suggestion(it.key, it.name, SuggestionReason.INTERVAL)
        } else emptyList()
        return (explicit + predicted + staples(history)).filter { allowed(it.key) }.distinctBy { it.key }.take(6)
    }

    fun staples(history: HistorySnapshot): List<Suggestion> {
        val trips = history.trips.sortedWith(compareByDescending<Trip> { it.closedAt }.thenBy { it.id }).take(8)
        if (trips.isEmpty()) return emptyList()
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val ids = trips.map { it.id }.toSet()
        val counts = history.purchases.filter { it.tripId in ids }.groupBy { ItemIdentity.purchaseKey(it, aliases) }
            .mapValues { (_, purchases) -> purchases.map { it.tripId }.distinct().size }
        return ranker.memories(history).filter { (counts[it.key] ?: 0) * 2 >= trips.size }
            .sortedWith(compareByDescending<ItemMemory> { counts[it.key] ?: 0 }
                .thenByDescending { it.lastBoughtAt }.thenBy { it.key })
            .map { Suggestion(it.key, it.name, SuggestionReason.STAPLE) }
    }
}
