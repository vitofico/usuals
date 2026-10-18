package io.theficos.comanda.model

import kotlinx.serialization.Serializable

@Serializable
data class Trip(val id: String, val closedAt: Long)

@Serializable
data class Purchase(
    val id: ItemId,
    val tripId: String,
    val key: String,
    val name: String,
    val quantity: Quantity? = null,
    val gtin: Gtin? = null,
    val boughtAt: Long,
    val fromSuggestion: Boolean = false,
    val normalizerVersion: Int = NORMALIZER_VERSION,
)

@Serializable
data class ItemPreference(
    val key: String,
    val category: String? = null,
    val muted: Boolean = false,
    val snoozedUntil: Long? = null,
    val repeatEveryDays: Int? = null,
)

@Serializable
data class ItemAlias(val fromKey: String, val toKey: String)

data class HistorySnapshot(
    val trips: List<Trip> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val preferences: List<ItemPreference> = emptyList(),
    val aliases: List<ItemAlias> = emptyList(),
)

data class FinishedTrip(val trip: Trip, val count: Int)

/** One transactionally consistent local list projection. */
data class ShoppingSnapshot(val listId: String = "", val items: List<Item> = emptyList(), val history: HistorySnapshot = HistorySnapshot(),
    // A revision-only acknowledgement must still emit through StateFlow equality.
    val revisions: Map<ItemId, ItemVersion> = items.mapNotNull { item -> item.version?.let { item.id to it } }.toMap(),
    val listVersion: ListVersion? = null)

data class ListVersion(val list: String, val aliasRevision: String?)
