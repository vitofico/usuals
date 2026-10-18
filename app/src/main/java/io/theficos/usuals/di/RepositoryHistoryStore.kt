package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.ui.shopping.*
import kotlinx.serialization.json.*
import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.parse.UtteranceParser
import io.theficos.usuals.ui.history.ScopedHistoryStore
import io.theficos.usuals.ui.history.PromptMemory
import io.theficos.usuals.ui.list.SmartStore

class RepositoryHistoryStore(
    private val repository: HistoryRepository,
    private val parser: UtteranceParser,
    private val smart: SmartStore,
    private val tools: ShoppingToolsStore? = null,
) : ScopedHistoryStore {
    private val json = Json { encodeDefaults = true }
    override suspend fun rebuy(rows: List<io.theficos.usuals.model.Purchase>, scope: String): Int = smart.addAgain(rows.map {
        SavedShoppingItem(it.id.value, it.name, it.quantity?.amount, it.quantity?.unit?.name, it.gtin?.value)
    }, scope)
    override fun observe() = repository.observe()
    override fun observeShopping() = repository.observeShopping()
    override suspend fun finish(onlyIds: Set<ItemId>?) = smart.finish(onlyIds)
    override suspend fun finishVersions(versions: List<io.theficos.usuals.model.ItemVersion>, onlyIds: Set<io.theficos.usuals.model.ItemId>?) = smart.finishVersions(versions, onlyIds)
    override suspend fun undo(tripId: String) = smart.undo(tripId)
    override suspend fun exportJson(): String {
        val base = repository.exportJson()
        val personal = tools ?: return base
        check(!personal.state.value.unavailable)
        val root = json.parseToJsonElement(base).jsonObject
        return JsonObject(root + mapOf("formatVersion" to JsonPrimitive(3),
            "personalShopping" to json.encodeToJsonElement(personal.state.value.portable))).toString()
    }
    override suspend fun importJson(text: String) {
        require(text.toByteArray().size <= 10 * 1024 * 1024)
        val root = json.parseToJsonElement(text).jsonObject
        if (root["formatVersion"]?.jsonPrimitive?.intOrNull != 3) { repository.importJson(text); return }
        val personal = requireNotNull(tools)
        check(!personal.state.value.unavailable)
        val portable = json.decodeFromJsonElement<PortableShoppingPreferences>(root.getValue("personalShopping"))
        portable.validate()
        val current = personal.state.value.portable
        val ids = current.favorites.associateBy { it.id }
        require(portable.favorites.none { row -> ids[row.id]?.let { it != row } == true })
        PortableShoppingPreferences(current.favorites + portable.favorites.filter { it.id !in ids }, portable.orders + current.orders).validate()
        repository.importJson(JsonObject(root - "personalShopping" + ("formatVersion" to JsonPrimitive(2))).toString())
        personal.importPortable(portable)
    }
    override suspend fun importPast(text: String, date: Long) = repository.importPast(parser.parse(text), date)
}

class PreferencePromptMemory(context: Context) : PromptMemory {
    private val prefs = context.getSharedPreferences("history_prompts", Context.MODE_PRIVATE)
    fun migrateScope(scope: String) = synchronized(this) {
        if (prefs.getBoolean("scope_migrated", false)) return@synchronized
        val scopedKey = Regex("^[0-9a-fA-F-]{36}:")
        check(prefs.edit().apply {
            prefs.all.forEach { (key, value) ->
                if (value is String && !scopedKey.containsMatchIn(key)) {
                    putString("$scope:$key", value)
                    remove(key)
                }
            }
            putBoolean("scope_migrated", true)
        }.commit())
    }
    override fun claim(type: String, batch: String): Boolean = synchronized(this) {
        if (prefs.getString(type, null) == batch) return@synchronized false
        prefs.edit().putString(type, batch).apply()
        true
    }
}
