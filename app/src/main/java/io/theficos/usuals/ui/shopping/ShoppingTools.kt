package io.theficos.usuals.ui.shopping

import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
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
    val note: String? = null,
    val category: String? = null,
) {
    val quantity: Quantity? get() = amount?.let { Quantity(it, MeasureUnit.valueOf(requireNotNull(unit))) }
    fun matches(name: String, quantity: Quantity?, gtin: Gtin?, store: String?): Boolean =
        this.name == name && this.quantity == quantity && this.gtin == gtin?.value && this.store == store
    fun validate() {
        require(id.isNotBlank() && id.length <= 100 && name.any { it.isLetterOrDigit() } && name.length <= 500)
        require((amount == null) == (unit == null))
        require(amount == null || amount.isFinite() && amount >= 0)
        unit?.let(MeasureUnit::valueOf)
        gtin?.let { require(Gtin.parse(it) != null) }
        require(store == null || store.length <= 80)
        require(note == null || note.length <= 1000)
        require(category == null || Category.entries.any { it.name == category })
    }
}

/** Mirrors add-again deduplication, including aliases and rows hidden by a store filter. */
fun PortableShoppingPreferences.favoritesOnList(items: List<Item>, history: HistorySnapshot): Set<String> {
    val aliases = history.aliases.associate { it.fromKey to it.toKey }
    val present = items.filterNot { it.checked }.map { ItemIdentity.key(it, aliases) }.toSet()
    return favorites.filter { item ->
        ItemIdentity.key(item.name, Normalizer.normalize(item.name), item.gtin?.let(Gtin::parse), aliases) in present
    }.map { it.id }.toSet()
}

@Serializable
data class OccasionPack(val id: String, val name: String, val items: List<SavedShoppingItem> = emptyList()) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 100 && name.isNotBlank() && name.length <= 100)
        require(items.size <= 200 && items.distinctBy { it.id }.size == items.size)
        items.forEach(SavedShoppingItem::validate)
    }
}

@Serializable
data class DeferredShoppingRows(val trips: Set<String> = emptySet(), val rows: Map<String, String> = emptyMap())

@Serializable
data class PortableShoppingPreferences(
    val favorites: List<SavedShoppingItem> = emptyList(),
    val orders: Map<String, List<String>> = emptyMap(),
    val packs: List<OccasionPack> = emptyList(),
) {
    fun validate() {
        require(packs.size <= 100 && packs.sumOf { it.items.size } <= 2000 && packs.distinctBy { it.id }.size == packs.size)
        packs.forEach(OccasionPack::validate)
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
    val version: Int = 2,
    val packOwner: String = "local",
    val otherPacks: Map<String, List<OccasionPack>> = emptyMap(),
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
            require(it.version in 1..2); it.portable.validate()
            val allPacks = it.otherPacks.values.flatten() + it.portable.packs
            require(it.otherPacks.size <= 50 && allPacks.size <= 100 && allPacks.sumOf { pack -> pack.items.size } <= 2000)
            require(it.packOwner !in it.otherPacks)
            it.otherPacks.values.forEach { packs -> PortableShoppingPreferences(packs = packs).validate() }
        }
    }.getOrElse { ShoppingPreferences(unavailable = true) })
    val state = mutable.asStateFlow()

    @Synchronized private fun change(update: (ShoppingPreferences) -> ShoppingPreferences) {
        check(!mutable.value.unavailable)
        val next = update(mutable.value).copy(version = 2)
        if (next == mutable.value) return
        next.portable.validate()
        require(next.otherPacks.size <= 50)
        val allPacks = next.otherPacks.values.flatten() + next.portable.packs
        require(allPacks.size <= 100 && allPacks.sumOf { it.items.size } <= 2000)
        next.otherPacks.values.forEach { PortableShoppingPreferences(packs = it).validate() }
        write(json.encodeToString(next))
        mutable.value = next
    }
    fun bindPacks(account: String?) {
      if (mutable.value.unavailable) return
      try { change { current ->
        val owner = account ?: "local"
        require(owner.length <= 100)
        if (current.packOwner == owner) current else current.copy(packOwner = owner,
            portable = current.portable.copy(packs = current.otherPacks[owner].orEmpty()),
            otherPacks = (current.otherPacks - owner) + (current.packOwner to current.portable.packs))
      } } catch (_: Exception) {
          mutable.value = mutable.value.copy(unavailable = true, portable = mutable.value.portable.copy(packs = emptyList()))
      }
    }
    fun mode(enabled: Boolean) = change { it.copy(mode = enabled) }
    fun keepAwake(enabled: Boolean) = change { it.copy(keepAwake = enabled) }
    fun haptics(enabled: Boolean) = change { it.copy(haptics = enabled) }
    fun emojiQuantities(enabled: Boolean) = change { it.copy(emojiQuantities = enabled) }
    fun pin(item: SavedShoppingItem) = change {
        if (it.portable.favorites.any { old -> old.id == item.id }) it
        else it.copy(portable = it.portable.copy(favorites = it.portable.favorites + item))
    }
    fun savePack(pack: OccasionPack) = change {
        it.copy(portable = it.portable.copy(packs = it.portable.packs.filterNot { old -> old.id == pack.id } + pack))
    }
    fun deletePack(id: String) = change {
        it.copy(portable = it.portable.copy(packs = it.portable.packs.filterNot { pack -> pack.id == id }))
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
    /** Run before importing shopping facts, including limits retained for other accounts. */
    fun validateImport(value: PortableShoppingPreferences) {
        val current = mutable.value
        check(!current.unavailable)
        value.validate()
        val favorites = current.portable.favorites.associateBy { it.id }
        require(value.favorites.none { row -> favorites[row.id]?.let { it != row } == true })
        val packs = current.portable.packs.associateBy { it.id }
        require(value.packs.none { pack -> packs[pack.id]?.let { it != pack } == true })
        val merged = PortableShoppingPreferences(
            current.portable.favorites + value.favorites.filter { it.id !in favorites },
            value.orders + current.portable.orders,
            current.portable.packs + value.packs.filter { it.id !in packs })
        merged.validate()
        val allPacks = current.otherPacks.values.flatten() + merged.packs
        require(allPacks.size <= 100 && allPacks.sumOf { it.items.size } <= 2000)
    }
    fun importPortable(value: PortableShoppingPreferences) = change {
        validateImport(value)
        val existing = it.portable.favorites.associateBy { row -> row.id }
        require(value.favorites.none { row -> existing[row.id]?.let { saved -> saved != row } == true })
        val packs = it.portable.packs.associateBy { pack -> pack.id }
        require(value.packs.none { pack -> packs[pack.id]?.let { saved -> saved != pack } == true })
        it.copy(portable = PortableShoppingPreferences(
            it.portable.favorites + value.favorites.filter { row -> row.id !in existing },
            value.orders + it.portable.orders,
            it.portable.packs + value.packs.filter { pack -> pack.id !in packs },
        ))
    }
}

fun PortableShoppingPreferences.categoryOrder(store: String?): List<Category> =
    orders[store.orEmpty()].orEmpty().mapNotNull { name -> Category.entries.find { it.name == name } }
        .let { first -> first + Category.entries.filterNot { it in first } }
