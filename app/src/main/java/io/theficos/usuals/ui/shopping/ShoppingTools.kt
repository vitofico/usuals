package io.theficos.usuals.ui.shopping

import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.smart.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedShoppingItem(
    val id: String,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val gtin: String? = null,
    val store: String? = null,
) {
    val quantity: Quantity? get() = amount?.let { Quantity(it, MeasureUnit.valueOf(requireNotNull(unit))) }
    fun validate() {
        require(id.isNotBlank() && id.length <= 100 && name.any { it.isLetterOrDigit() } && name.length <= 500)
        require((amount == null) == (unit == null))
        require(amount == null || amount.isFinite() && amount >= 0)
        unit?.let(MeasureUnit::valueOf)
        gtin?.let { require(Gtin.parse(it) != null) }
        require(store == null || store.length <= 80)
    }
}

@Serializable
data class DeferredShoppingRows(val trips: Set<String> = emptySet(), val rows: Map<String, String> = emptyMap())

@Serializable
data class PortableShoppingPreferences(
    val favorites: List<SavedShoppingItem> = emptyList(),
    val orders: Map<String, List<String>> = emptyMap(),
) {
    fun validate() {
        require(favorites.size <= 500 && favorites.map { it.id }.distinct().size == favorites.size)
        favorites.forEach(SavedShoppingItem::validate)
        require(orders.size <= 200)
        orders.forEach { (store, order) ->
            require(store.length <= 80 && order.distinct().size == order.size)
            require(order.all { name -> Category.entries.any { it.name == name } })
        }
    }
}

@Serializable
data class ShoppingPreferences(
    val version: Int = 1,
    @kotlinx.serialization.Transient val unavailable: Boolean = false,
    val mode: Boolean = false,
    val keepAwake: Boolean = false,
    val haptics: Boolean = false,
    /** Note import reads "Beer 🍺🍺🍺" as 3 beers; off, the emoji stay in the name. */
    val emojiQuantities: Boolean = false,
    val portable: PortableShoppingPreferences = PortableShoppingPreferences(),
    val deferred: Map<String, DeferredShoppingRows> = emptyMap(),
)

/** Personal view choices never alter shared facts or purchase history. */
class ShoppingToolsStore(
    read: () -> String? = { null },
    private val write: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(runCatching {
        (read()?.let { json.decodeFromString<ShoppingPreferences>(it) } ?: ShoppingPreferences()).also {
            require(it.version == 1); it.portable.validate()
        }
    }.getOrElse { ShoppingPreferences(unavailable = true) })
    val state = mutable.asStateFlow()

    @Synchronized private fun change(update: (ShoppingPreferences) -> ShoppingPreferences) {
        check(!mutable.value.unavailable)
        val next = update(mutable.value)
        next.portable.validate()
        write(json.encodeToString(next))
        mutable.value = next
    }
    fun mode(enabled: Boolean) = change { it.copy(mode = enabled) }
    fun keepAwake(enabled: Boolean) = change { it.copy(keepAwake = enabled) }
    fun haptics(enabled: Boolean) = change { it.copy(haptics = enabled) }
    fun emojiQuantities(enabled: Boolean) = change { it.copy(emojiQuantities = enabled) }
    fun pin(item: SavedShoppingItem) = change {
        if (it.portable.favorites.any { old -> old.id == item.id }) it
        else it.copy(portable = it.portable.copy(favorites = it.portable.favorites + item))
    }
    fun unpin(id: String) = change { it.copy(portable = it.portable.copy(favorites = it.portable.favorites.filterNot { row -> row.id == id })) }
    fun order(store: String, order: List<Category>) = change {
        it.copy(portable = it.portable.copy(orders = it.portable.orders + (store to order.map(Category::name))))
    }
    fun defer(scope: String, id: String, lifecycle: String, trips: Set<String>) = change {
        val previous = it.deferred[scope]?.takeIf { old -> trips.none { trip -> trip !in old.trips } }
        it.copy(deferred = it.deferred + (scope to DeferredShoppingRows(trips, previous?.rows.orEmpty() + (id to lifecycle))))
    }
    fun restore(scope: String, id: String) = change {
        val rows = it.deferred[scope] ?: return@change it
        it.copy(deferred = it.deferred + (scope to rows.copy(rows = rows.rows - id)))
    }
    fun releaseAfterShop(scope: String, trips: Set<String>) {
        val saved = mutable.value.deferred[scope] ?: return
        if (trips.any { it !in saved.trips }) change {
            val current = it.deferred[scope]
            if (current != null && trips.any { trip -> trip !in current.trips }) it.copy(deferred = it.deferred - scope) else it
        }
    }
    fun importPortable(value: PortableShoppingPreferences) = change {
        value.validate()
        val existing = it.portable.favorites.associateBy { row -> row.id }
        require(value.favorites.none { row -> existing[row.id]?.let { saved -> saved != row } == true })
        it.copy(portable = PortableShoppingPreferences(
            it.portable.favorites + value.favorites.filter { row -> row.id !in existing },
            value.orders + it.portable.orders,
        ))
    }
}

fun PortableShoppingPreferences.categoryOrder(store: String?): List<Category> =
    orders[store.orEmpty()].orEmpty().mapNotNull { name -> Category.entries.find { it.name == name } }
        .let { first -> first + Category.entries.filterNot { it in first } }
