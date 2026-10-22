package io.theficos.usuals.ui.basket

import io.theficos.usuals.enrich.CachedQuality
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.model.Item
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.smart.BarcodeMemory
import io.theficos.usuals.smart.CategoryClassifier

/**
 * Turns a list or a household's trips into overviews, from product details this
 * phone already holds.
 *
 * Nothing here asks Open Food Facts. A row's facts come from the local cache for
 * its own barcode or the one the household's history remembers for it, read in
 * one batch, and the result stays on the phone: it is drawn, never stored or
 * synced.
 */
class BasketSource(
    private val classifier: CategoryClassifier,
    /** The local cache, read for many barcodes at once. Must not reach the network. */
    private val cached: suspend (Collection<Gtin>) -> Map<Gtin, CachedQuality>,
    /** The overview setting, which only counts while Open Food Facts lookup is on. */
    val enabled: () -> Boolean,
) {
    private class Pending(val key: String, val name: String, val categoryOverride: String?, val gtin: Gtin?)

    suspend fun forList(items: List<Item>, history: HistorySnapshot): BasketOverview {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val memory = BarcodeMemory(history)
        val preferences = ItemIdentity.preferences(history.preferences, aliases)
        val pending = items.map { item ->
            val key = ItemIdentity.key(item, aliases)
            Pending(key, item.name, preferences[key]?.category, item.gtin ?: memory.remembered(key)?.gtin)
        }
        return resolveAll(mapOf("" to pending)).getValue("")
    }

    /** One overview per trip, from that trip's purchases: the barcode bought, else the one remembered for its key. */
    suspend fun forTrips(history: HistorySnapshot): Map<String, BasketOverview> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val memory = BarcodeMemory(history)
        val preferences = ItemIdentity.preferences(history.preferences, aliases)
        val byTrip = history.purchases.groupBy { it.tripId }.mapValues { (_, purchases) ->
            purchases.map { purchase ->
                val key = ItemIdentity.purchaseKey(purchase, aliases)
                Pending(key, purchase.name, preferences[key]?.category, purchase.gtin ?: memory.remembered(key)?.gtin)
            }
        }
        return resolveAll(byTrip)
    }

    private suspend fun resolveAll(groups: Map<String, List<Pending>>): Map<String, BasketOverview> {
        val facts = cached(groups.values.flatten().mapNotNull { it.gtin }.toSet())
        return groups.mapValues { (_, pending) ->
            basketOverview(pending.map { line ->
                BasketLine(line.key, line.name, classifier.classify(line.name, line.categoryOverride),
                    hasBarcode = line.gtin != null, quality = line.gtin?.let { facts[it]?.quality })
            }, classifier::isProduceName)
        }
    }
}
