package io.theficos.usuals.ui.history

import io.theficos.usuals.model.FinishedTrip
import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ShoppingSnapshot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface HistoryStore {
    companion object { const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024 }

    suspend fun rebuy(rows: List<io.theficos.usuals.model.Purchase>, scope: String): Int = 0
    fun observe(): Flow<HistorySnapshot>
    suspend fun finish(onlyIds: Set<ItemId>? = null): FinishedTrip?
    suspend fun finishVersions(versions: List<io.theficos.usuals.model.ItemVersion>, onlyIds: Set<ItemId>? = null): FinishedTrip? = finish(onlyIds)
    suspend fun undo(tripId: String): Boolean
    suspend fun exportJson(): String
    suspend fun importJson(text: String)
    suspend fun importPast(text: String, date: Long): FinishedTrip?
}

/** Production reads the selected list's rows and history in one Room transaction. */
interface ScopedHistoryStore : HistoryStore {
    fun observeShopping(): Flow<ShoppingSnapshot>
}

fun HistoryStore.shopping(items: Flow<List<Item>>): Flow<ShoppingSnapshot> =
    if (this is ScopedHistoryStore) observeShopping()
    else combine(items, observe()) { rows, history -> ShoppingSnapshot(items = rows, history = history) }

/** Used only when a view model is rendered without the production graph. */
object NoHistoryStore : HistoryStore {
    override fun observe() = flowOf(HistorySnapshot())
    override suspend fun finish(onlyIds: Set<ItemId>?) = null
    override suspend fun undo(tripId: String) = false
    override suspend fun exportJson() = ""
    override suspend fun importJson(text: String) = Unit
    override suspend fun importPast(text: String, date: Long) = null
}

interface PromptMemory {
    fun claim(type: String, batch: String): Boolean
}

class InMemoryPromptMemory : PromptMemory {
    private val shown = mutableMapOf<String, String>()
    override fun claim(type: String, batch: String): Boolean {
        if (shown[type] == batch) return false
        shown[type] = batch
        return true
    }
}
