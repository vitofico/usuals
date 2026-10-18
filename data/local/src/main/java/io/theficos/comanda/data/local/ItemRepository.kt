package io.theficos.comanda.data.local

import androidx.room.withTransaction
import io.theficos.comanda.model.*
import io.theficos.comanda.parse.ItemIdentity
import io.theficos.comanda.parse.Normalizer
import io.theficos.comanda.parse.ParsedItem
import io.theficos.comanda.sync.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ItemRepository(
    private val database: ComandaDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val replica: LocalReplica = LocalReplica(database),
) {
    private val dao = database.itemDao()
    private suspend fun listId() = database.listDao().selected()
    suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction(block)
    fun observeItems(): Flow<List<Item>> = database.listDao().observeSelected().flatMapLatest { scope ->
        database.historyDao().observeShoppingInvalidations(androidx.sqlite.db.SimpleSQLiteQuery("SELECT 1")).map { database.withTransaction { versioned(scope) } }
    }
    suspend fun items(): List<Item> = database.withTransaction { versioned(listId()) }
    private suspend fun versioned(scope: String): List<Item> {
        val state = replica.projection(scope)
        val preferenceRevisions = state.preferences.mapValues { it.value.string("revision") }
        return dao.active(scope).map { entity -> entity.toItem().also { item ->
            state.rows[entity.id]?.let { row -> item.version = ItemVersion(scope, entity.id, row.revisions, row.lifecycle,
                state.aliasRevision, state.aliases, preferenceRevisions, row.facts.toString()) }
        } }
    }
    suspend fun currentListId(): String = listId()
    suspend fun find(id: ItemId): Item? = dao.find(id.value, listId())?.toItem()

    suspend fun add(parsed: ParsedItem, addedBy: MemberId? = null, gtin: Gtin? = null,
        rawText: String = parsed.name, fromSuggestion: Boolean = false, store: String? = null): ItemId = database.withTransaction {
        val proposed = newId()
        val now = clock()
        val scope = listId()
        val aliases = database.historyDao().aliases(scope).associate { it.fromKey to it.toKey }
        val row = ItemEntity(proposed, rawText, parsed.name.trim(), Normalizer.normalize(parsed.name),
            parsed.quantity?.amount, parsed.quantity?.unit?.name, parsed.note, gtin?.value,
            fromSuggestion = fromSuggestion, store = store, position = nextPosition(), addedBy = addedBy?.value,
            createdAt = now, updatedAt = now, listId = scope)
        val result = replica.submitTo(scope, "capture", JsonObject(row.facts(aliases) + ("proposedId" to JsonPrimitive(proposed))))
        val winner = result.projection.redirects[proposed] ?: proposed
        // An incompatible amount remains a visible conflict beside the existing row.
        val kept = if (result.outcome == Outcome.CONFLICT) result.projection.rows.values.firstOrNull {
            !it.archived && it.facts.string("key") == row.facts(aliases).string("key")
        }?.id else winner
        require(result.outcome in setOf(Outcome.APPLIED, Outcome.MERGED, Outcome.CONFLICT))
        ItemId(requireNotNull(kept))
    }
    suspend fun addRepeat(key: String, name: String, asOf: Long): ItemId? = database.withTransaction {
        var proposed: String? = null
        val scope = listId()
        val initial = replica.projection(scope)
        val initialRule = initial.rules[key] ?: return@withTransaction null
        val initialRevision = initialRule.getValue("revision").jsonPrimitive.content
        val initialPurchase = initial.purchases.values.filter { it.string("key") == key }
            .sortedWith(compareByDescending<JsonObject> { it.getValue("boughtAt").jsonPrimitive.long }.thenBy { it.getValue("id").jsonPrimitive.content }).firstOrNull()
        val next = nextRepeatCycle(initial, scope, key, initialRevision, initialPurchase?.string("id") ?: initialRevision) ?: return@withTransaction null
        val result = replica.submit("repeat") { state, _ ->
            val rule = state.rules[key]
            if (rule == null) return@submit buildJsonObject { }
            val revision = rule.getValue("revision").jsonPrimitive.content
            val purchase = state.purchases.values.filter { it.string("key") == key }.sortedWith(compareByDescending<JsonObject> { it.getValue("boughtAt").jsonPrimitive.long }.thenBy { it.getValue("id").jsonPrimitive.content }).firstOrNull()
            val enabling = purchase?.get("id")?.jsonPrimitive?.content ?: revision
            proposed = newId()
            buildJsonObject {
                put("proposedId", proposed!!); put("key", key); put("name", name); put("ruleRevision", revision)
                put("enablingPurchase", enabling); put("asOf", asOf)
                put("occurrence", next.first)
                next.second?.let { cycle -> put("cycle", buildJsonObject { put("previous", cycle.previous); put("deletion", cycle.deletion); put("finish", cycle.finish) }) }
                put("position", nextPosition()); put("createdAt", asOf); put("updatedAt", asOf)
            }
        }
        if (result.outcome !in setOf(Outcome.APPLIED, Outcome.NO_OP)) return@withTransaction null
        proposed?.let { ItemId(result.projection.redirects[it] ?: it) }
    }
    suspend fun setChecked(id: ItemId, checked: Boolean, version: ItemVersion? = null) {
        if (version != null) require(version.id == id.value && version.list == listId())
        replica.submit("check") { state, _ ->
            val row = state.rows[id.value]
            buildJsonObject {
                put("target", id.value); put("expected", buildJsonObject { put("check", (if (version != null) version.fields["check"] else row?.revisions?.get("check"))?.let(::JsonPrimitive) ?: JsonNull); put("lifecycle", (if (version != null) version.lifecycle else row?.lifecycle)?.let(::JsonPrimitive) ?: JsonNull) })
                put("checked", checked); put("checkedAt", if (checked) JsonPrimitive(row?.facts?.get("checkedAt")?.jsonPrimitive?.longOrNull ?: clock()) else JsonNull)
                put("updatedAt", clock())
            }
        }
    }
    suspend fun setQuantity(id: ItemId, quantity: Quantity?, version: ItemVersion? = null) { editFields(id, buildJsonObject {
        put("quantity", quantity?.amount?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("unit", quantity?.unit?.name?.let(::JsonPrimitive) ?: JsonNull)
    }, version = version) }
    suspend fun rename(id: ItemId, name: String) = database.withTransaction {
        val aliases = database.historyDao().aliases(listId()).associate { it.fromKey to it.toKey }
        val item = dao.find(id.value, listId()) ?: return@withTransaction
        editFields(id, buildJsonObject {
            put("name", name.trim()); put("key", ItemIdentity.key(name.trim(), Normalizer.normalize(name), item.parsedGtin(), aliases))
        })
    }
    private suspend fun editFields(id: ItemId, changes: JsonObject, aliasDelta: Map<String, String> = emptyMap(), version: ItemVersion? = null): Boolean {
        if (version != null) require(version.id == id.value && version.list == listId())
        val result = replica.submit("edit") { state, _ ->
            val row = state.rows[id.value]
            val frozen = version?.facts?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject } ?: row?.facts.orEmpty()
            val delta = changes.filter { (field, value) -> value != frozen[field] }
            if (delta.isEmpty()) return@submit buildJsonObject { put("target", id.value); put("expected", buildJsonObject { }); put("changes", buildJsonObject { put("updatedAt", clock()) }) }
            val fields = delta.keys.map { when (it) { "unit" -> "quantity"; "key", "gtin" -> "name"; else -> it } }
            buildJsonObject {
                put("target", id.value)
                put("expected", JsonObject((fields + "lifecycle").associateWith { (if (it == "lifecycle") { if (version != null) version.lifecycle else row?.lifecycle } else if (version != null) version.fields[it] else row?.revisions?.get(it))?.let(::JsonPrimitive) ?: JsonNull }))
                put("changes", JsonObject(delta + ("updatedAt" to JsonPrimitive(clock()))))
                if (aliasDelta.isNotEmpty()) {
                    put("expectedAliasRevision", (if (version != null) version.aliasRevision else state.aliasRevision)?.let(::JsonPrimitive) ?: JsonNull)
                    put("aliasDelta", JsonObject(aliasDelta.mapValues { JsonPrimitive(it.value) }))
                }
            }
        }
        return result.outcome == Outcome.APPLIED
    }
    suspend fun edit(id: ItemId, name: String, quantity: Quantity?, preferences: List<ItemPreference>, store: String? = null, version: ItemVersion? = null): Boolean = database.withTransaction {
        val existing = dao.find(id.value, listId()) ?: return@withTransaction false
        if (existing.deletedAt != null) return@withTransaction false
        val aliases = version?.aliases ?: database.historyDao().aliases(listId()).associate { it.fromKey to it.toKey }
        val key = if (existing.gtin != null && name != existing.gtin) ItemIdentity.resolve(Normalizer.normalize(name), aliases)
            else ItemIdentity.key(name, Normalizer.normalize(name), existing.parsedGtin(), aliases)
        val saved = editFields(id, buildJsonObject {
            put("name", name.trim()); put("key", key)
            put("quantity", quantity?.amount?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("unit", quantity?.unit?.name?.let(::JsonPrimitive) ?: JsonNull); put("store", store?.trim()?.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
        }, if (existing.gtin != null && name != existing.gtin) mapOf("gtin:${existing.gtin}" to key) else emptyMap(), version)
        if (saved) {
            for (preference in preferences) savePreference(preference.entity().copy(listId = listId()), version)
        }
        saved
    }
    internal suspend fun savePreference(value: ItemPreferenceEntity, version: ItemVersion? = null) = database.withTransaction {
        if (version != null) require(version.list == value.listId)
        val current = database.historyDao().preferences(value.listId).firstOrNull { it.key == value.key }
        if (current?.category != value.category || current?.repeatEveryDays != value.repeatEveryDays || (current == null && (value.category != null || value.repeatEveryDays != null))) {
            replica.submitTo(value.listId, "preference", buildJsonObject {
                val state = replica.projection(value.listId)
                put("key", value.key); put("expected", if (version != null) version.preferences[value.key]?.let(::JsonPrimitive) ?: JsonNull else state.preferences[value.key]?.get("revision") ?: JsonNull)
                put("changes", buildJsonObject {
                    if (current == null || current.category != value.category) put("category", value.category?.let(::JsonPrimitive) ?: JsonNull)
                    if (current == null || current.repeatEveryDays != value.repeatEveryDays) put("repeatEveryDays", value.repeatEveryDays?.let(::JsonPrimitive) ?: JsonNull)
                })
            })
        }
        database.historyDao().saveLocalPreferences(listOf(LocalPreferenceEntity(value.listId, value.key, value.muted, value.snoozedUntil)))
    }
    internal suspend fun saveAlias(value: ItemAliasEntity, version: ListVersion? = null): Boolean = database.withTransaction {
        if (version != null) require(version.list == value.listId)
        val result = replica.submitTo(value.listId, "alias", buildJsonObject {
            put("expected", (if (version != null) version.aliasRevision else replica.projection(value.listId).aliasRevision)?.let(::JsonPrimitive) ?: JsonNull)
            put("delta", buildJsonObject { put(value.fromKey, value.toKey) })
        })
        require(result.outcome in setOf(Outcome.APPLIED, Outcome.CONFLICT))
        result.outcome == Outcome.APPLIED
    }
    suspend fun merge(source: ItemVersion, target: ItemVersion): Boolean = database.withTransaction {
        require(source.list == listId() && target.list == source.list && source.aliasRevision == target.aliasRevision)
        val from = Json.parseToJsonElement(source.facts).jsonObject
        val to = Json.parseToJsonElement(target.facts).jsonObject
        val destination = ItemIdentity.resolve(requireNotNull(to.string("key")), target.aliases)
        val sourceKeys = (listOfNotNull(from.string("key"), from.string("gtin")?.let { "gtin:$it" }).toSet() - destination)
        val delta = sourceKeys.associateWith { destination }
        val result = replica.submitTo(source.list, "merge", buildJsonObject {
            put("source", source.id); put("target", target.id)
            fun expected(value: ItemVersion) = JsonObject(value.fields.mapValues { it.value?.let(::JsonPrimitive) ?: JsonNull } + ("lifecycle" to (value.lifecycle?.let(::JsonPrimitive) ?: JsonNull)))
            put("sourceExpected", expected(source)); put("targetExpected", expected(target))
            put("expectedAliasRevision", source.aliasRevision?.let(::JsonPrimitive) ?: JsonNull)
            put("aliasDelta", JsonObject(delta.mapValues { JsonPrimitive(it.value) }))
            put("deletedAt", clock()); put("updatedAt", clock())
        })
        result.outcome == Outcome.MERGED
    }
    suspend fun delete(id: ItemId, version: ItemVersion? = null) = database.withTransaction {
        if (version != null) require(version.id == id.value && version.list == listId())
        val existing = dao.find(id.value, listId()) ?: return@withTransaction
        if (existing.deletedAt != null) return@withTransaction
        val aliases = database.historyDao().aliases(listId()).associate { it.fromKey to it.toKey }
        val key = ItemIdentity.key(existing.toItem(), aliases)
        val pref = database.historyDao().preferences(listId()).firstOrNull { it.key == key }
        if (pref?.repeatEveryDays != null) database.historyDao().saveLocalPreferences(listOf(LocalPreferenceEntity(listId(), key, pref.muted, clock())))
        replica.submit("delete") { state, _ -> buildJsonObject {
            put("target", id.value); put("expected", buildJsonObject { version?.fields?.forEach { (key, value) -> put(key, value?.let(::JsonPrimitive) ?: JsonNull) }; put("lifecycle", (if (version != null) version.lifecycle else state.rows[id.value]?.lifecycle)?.let(::JsonPrimitive) ?: JsonNull) })
            put("deletedAt", clock()); put("updatedAt", clock())
        } }
    }
    suspend fun reap(olderThanMillis: Long = FORTNIGHT_MILLIS): Int = replica.reap(clock() - olderThanMillis)
    suspend fun nextPosition(): Double = (dao.maxPosition(listId()) ?: 0.0) + POSITION_STEP

    companion object {
        /** Gap between consecutive rows, leaving room to drag something in between. */
        const val POSITION_STEP: Double = 1.0

        /** Fourteen days, the tombstone window from design section 7.1. */
        const val FORTNIGHT_MILLIS: Long = 14L * 24 * 60 * 60 * 1000

    }
}
