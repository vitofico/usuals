package io.theficos.usuals.parse

import io.theficos.usuals.model.*

/** Only confirmed aliases can change a normalized item's identity. */
object ItemIdentity {
    fun resolve(key: String, aliases: Map<String, String>): String {
        var current = key
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val next = aliases[current] ?: break
            if (next == current) break
            current = next
        }
        return current
    }

    fun key(item: Item, aliases: Map<String, String>): String =
        key(item.name, item.normalizedName, item.gtin, aliases)

    fun key(name: String, normalizedName: String, gtin: Gtin?, aliases: Map<String, String>): String {
        val barcode = gtin?.let { "gtin:${it.value}" }
        val base = if (barcode != null && (name == gtin?.value || barcode in aliases)) barcode else normalizedName
        return resolve(base, aliases)
    }

    fun preferences(values: List<ItemPreference>, aliases: Map<String, String>): Map<String, ItemPreference> =
        values.groupBy { resolve(it.key, aliases) }.mapValues { (key, entries) ->
            entries.sortedWith(compareBy<ItemPreference> { it.key != key }.thenBy { it.key }).first()
        }

    fun purchaseKey(purchase: Purchase, aliases: Map<String, String>): String {
        val barcode = purchase.gtin?.let { "gtin:${it.value}" }
        return when {
            barcode != null && barcode in aliases -> resolve(barcode, aliases)
            purchase.normalizerVersion == NORMALIZER_VERSION -> resolve(purchase.key, aliases)
            else -> key(purchase.name, Normalizer.normalize(purchase.name), purchase.gtin, aliases)
        }
    }
}
