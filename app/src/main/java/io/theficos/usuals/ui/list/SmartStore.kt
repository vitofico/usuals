package io.theficos.usuals.ui.list

import io.theficos.usuals.model.*
import io.theficos.usuals.smart.Category
import io.theficos.usuals.smart.ItemMemory
import io.theficos.usuals.smart.Suggestion
import io.theficos.usuals.smart.SuggestionChoice
import io.theficos.usuals.smart.RepeatOffer

data class ItemPresentation(val category: Category, val override: Category?, val repeatEveryDays: Int?, val quantityHint: Quantity? = null)
data class ItemEdit(val id: ItemId, val name: String, val quantity: Quantity?, val category: Category?, val repeatEveryDays: Int?, val store: String? = null, val version: ItemVersion? = null, val changeCategory: Boolean = true, val changeRepeat: Boolean = true, val note: String? = null) {
    companion object { const val MAX_NOTE = 500 }
}

interface SmartStore {
    suspend fun addAgain(items: List<io.theficos.usuals.ui.shopping.SavedShoppingItem>, scope: String): Int = 0
    fun similar(name: String, history: HistorySnapshot, active: List<Item>): ItemMemory? = null
    fun repeatOffers(active: List<Item>, history: HistorySnapshot): List<RepeatOffer> = emptyList()
    suspend fun setRepeat(offer: RepeatOffer) = Unit
    suspend fun applyQuantityHint(id: ItemId) = Unit
    suspend fun applyQuantityHint(id: ItemId, version: ItemVersion?) = applyQuantityHint(id)
    suspend fun acceptQuantityHint(id: ItemId, quantity: Quantity, version: ItemVersion?) = applyQuantityHint(id, version)
    suspend fun addMerged(item: Item, usual: ItemMemory): Boolean = false
    suspend fun addMerged(item: Item, usual: ItemMemory, version: ListVersion?): Boolean = addMerged(item, usual)
    fun suggestions(now: Long, history: HistorySnapshot, active: List<Item>): List<Suggestion> = emptyList()
    suspend fun chooseSuggestion(key: String, choice: SuggestionChoice, now: Long) = Unit
    fun present(items: List<Item>, history: HistorySnapshot): Map<ItemId, ItemPresentation>
    fun autocomplete(query: String, history: HistorySnapshot, active: List<Item>): List<ItemMemory>
    suspend fun maintainRepeats(now: Long, observed: List<Item>, history: HistorySnapshot)
    suspend fun edit(value: ItemEdit): Boolean
    suspend fun finish(onlyIds: Set<ItemId>?): FinishedTrip?
    suspend fun finishVersions(versions: List<ItemVersion>, onlyIds: Set<ItemId>?): FinishedTrip? = finish(onlyIds)
    suspend fun undo(tripId: String): Boolean
}

object NoSmartStore : SmartStore {
    override fun present(items: List<Item>, history: HistorySnapshot) = emptyMap<ItemId, ItemPresentation>()
    override fun autocomplete(query: String, history: HistorySnapshot, active: List<Item>) = emptyList<ItemMemory>()
    override suspend fun maintainRepeats(now: Long, observed: List<Item>, history: HistorySnapshot) = Unit
    override suspend fun edit(value: ItemEdit) = false
    override suspend fun finish(onlyIds: Set<ItemId>?) = null
    override suspend fun undo(tripId: String) = false
}
