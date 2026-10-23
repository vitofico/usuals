package io.theficos.usuals.di

import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.data.local.NewItem
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.ui.list.ItemStore
import kotlinx.coroutines.flow.Flow

/**
 * Room, behind the list screen's port.
 *
 * The whole point of this file is that it is the only one that knows both
 * shapes. If `:data:local` renames a method, this adapter changes and nothing
 * in `ui/list` does, which matters while the modules are being written in
 * parallel.
 */
class RepositoryItemStore(private val repository: ItemRepository, private val sync: suspend () -> Unit = {}) : ItemStore {

    override fun items(): Flow<List<Item>> = repository.observeItems()

    /**
     * Hands each row back to the repository as the parse that produced it.
     *
     * The view model builds a whole [Item] because that is what the list
     * renders, but identity, position and the dedup-at-insert rule belong to
     * the repository: adding "Leche" when "leche" is already open has to merge
     * rather than stack, and only the store can see the rest of the list to
     * know that. So the id and position the view model invented are dropped
     * here on purpose, and the repository's answer is authoritative.
     */
    override suspend fun add(items: List<Item>) { capture(items) }

    override suspend fun capture(items: List<Item>): List<Item> = repository.transaction {
        val ids = repository.addAll(items.map { item ->
            NewItem(
                parsed = ParsedItem(name = item.name, quantity = item.quantity, note = item.note),
                addedBy = item.addedBy,
                gtin = item.gtin,
                rawText = item.rawText,
                fromSuggestion = item.fromSuggestion,
                store = item.store,
            )
        })
        val saved = repository.items().associateBy { it.id }
        ids.map { requireNotNull(saved[it]) }
    }

    override suspend fun transfer(source: io.theficos.usuals.model.ItemVersion, destination: String, move: Boolean) =
        repository.transfer(source, destination, move, sync)

    override suspend fun setChecked(id: ItemId, checked: Boolean) =
        repository.setChecked(id, checked)

    override suspend fun setChecked(id: ItemId, checked: Boolean, version: io.theficos.usuals.model.ItemVersion?) = repository.setChecked(id, checked, version)
    override suspend fun remove(id: ItemId, version: io.theficos.usuals.model.ItemVersion?) = repository.delete(id, version)
    override suspend fun remove(id: ItemId) = repository.delete(id)
    override suspend fun removeAll(rows: List<Pair<ItemId, io.theficos.usuals.model.ItemVersion?>>) = repository.deleteAll(rows)
}
