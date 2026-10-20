package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity
import kotlin.math.roundToInt

data class RepeatOffer(val key: String, val name: String, val days: Int, val version: ItemVersion? = null)

class RepeatOffers {
    fun offers(active: List<Item>, history: HistorySnapshot): List<RepeatOffer> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val prefs = ItemIdentity.preferences(history.preferences, aliases)
        val purchases = history.purchases.groupBy { ItemIdentity.purchaseKey(it, aliases) }
        return active.filter { it.fromSuggestion && !it.checked }.mapNotNull { item ->
            val key = ItemIdentity.key(item, aliases)
            if (prefs[key]?.repeatEveryDays != null || prefs[key]?.muted == true) return@mapNotNull null
            val accepted = purchases[key].orEmpty().filter { it.fromSuggestion }
            if (accepted.size < 2) return@mapNotNull null
            val dates = (accepted.map { it.boughtAt } + item.createdAt).distinct().sorted()
            val gaps = dates.zipWithNext { a, b -> (b - a).toDouble() / RepeatPlanner.DAY_MILLIS }.sorted()
            if (gaps.isEmpty()) return@mapNotNull null
            val days = ((gaps[(gaps.size - 1) / 2] + gaps[gaps.size / 2]) / 2).roundToInt()
            if (days < 1) null else RepeatOffer(key, item.name, days, item.version)
        }.distinctBy { it.key }.sortedBy { it.key }
    }
}
