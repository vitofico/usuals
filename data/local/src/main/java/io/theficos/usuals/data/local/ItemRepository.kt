package io.theficos.usuals.data.local

import androidx.room.withTransaction
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ItemRepository(
    private val database: UsualsDatabase,
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

    /** Original captures remain durable if a review is stale or any choice cannot preserve its details. */
    suspend fun matchCaptures(choices: List<CaptureNameChoice>, remember: Boolean): List<Item>? = try {
        database.withTransaction {
            val scope = listId()
            val before = versioned(scope).associateBy { it.id.value }
            val aliases = replica.projection(scope).aliases
            fun ensure(condition: Boolean) { if (!condition) throw CaptureMatchChanged() }
            ensure(choices.map { it.source.id }.distinct().size == choices.size)
            for (choice in choices) {
                val current = before[choice.source.id] ?: throw CaptureMatchChanged()
                ensure(choice.source.list == scope && !current.checked &&
                    current.version?.fields == choice.source.fields && current.version?.lifecycle == choice.source.lifecycle && current.version?.facts == choice.source.facts)
                ensure((!remember || choice.source.aliases == aliases) && ItemIdentity.resolve(choice.key, aliases) == choice.key)
                ensure(ItemIdentity.resolve(current.normalizedName, choice.source.aliases) == ItemIdentity.resolve(current.normalizedName, aliases))
                ensure(ItemIdentity.resolve(Normalizer.normalize(choice.name), aliases) == choice.key)
            }
            val ids = choices.map { choice ->
                val rows = versioned(scope)
                val source = rows.first { it.id.value == choice.source.id }
                val target = rows.firstOrNull { it.id != source.id && !it.checked && ItemIdentity.key(it, aliases) == choice.key }
                if (target == null) {
                    val delta = if (remember) mapOf(source.normalizedName to choice.key) else emptyMap()
                    ensure(editFields(source.id, buildJsonObject {
                        put("name", choice.name); put("key", choice.key)
                    }, delta, source.version))
                    source.id
                } else {
                    // Automatic corrections must be individually reversible without changing another row.
                    ensure(remember)
                    for ((a, b) in listOf(source.note to target.note, source.store to target.store, source.gtin to target.gtin))
                        ensure(a == null || b == null || a == b)
                    val incoming = source.quantity
                    val existing = target.quantity
                    val quantity = when {
                        incoming == null -> existing
                        existing == null -> incoming
                        incoming.unit == existing.unit -> existing.copy(amount = existing.amount + incoming.amount)
                        incoming.inBaseUnits() != null && existing.inBaseUnits() != null && incoming.unit.dimension == existing.unit.dimension ->
                            existing.copy(amount = existing.amount + incoming.inBaseUnits()!! / existing.copy(amount = 1.0).inBaseUnits()!!)
                        else -> throw CaptureMatchChanged()
                    }
                    ensure(quantity == null || quantity.amount.isFinite())
                    ensure(editFields(target.id, buildJsonObject {
                        put("quantity", quantity?.amount?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                        put("unit", quantity?.unit?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("note", (target.note ?: source.note)?.let(::JsonPrimitive) ?: JsonNull)
                        put("store", (target.store ?: source.store)?.let(::JsonPrimitive) ?: JsonNull)
                        put("gtin", (target.gtin ?: source.gtin)?.value?.let(::JsonPrimitive) ?: JsonNull)
                    }, version = target.version))
                    val fresh = versioned(scope).associateBy { it.id }
                    ensure(merge(requireNotNull(fresh[source.id]?.version), requireNotNull(fresh[target.id]?.version)))
                    target.id
                }
            }
            val after = versioned(scope).associateBy { it.id }
            ids.map { requireNotNull(after[it]) }
        }
    } catch (_: CaptureMatchChanged) { null }

    private class CaptureMatchChanged : RuntimeException()

    suspend fun find(id: ItemId): Item? = dao.find(id.value, listId())?.toItem()

    suspend fun add(parsed: ParsedItem, addedBy: MemberId? = null, gtin: Gtin? = null,
        rawText: String = parsed.name, fromSuggestion: Boolean = false, store: String? = null): ItemId =
        addAll(listOf(NewItem(parsed, addedBy, gtin, rawText, fromSuggestion, store))).single()
    /** Each row is its own capture for sync, merging into an open row of its key, but the list is rewritten once. */
    suspend fun addAll(rows: List<NewItem>): List<ItemId> = database.withTransaction {
        if (rows.isEmpty()) return@withTransaction emptyList()
        val now = clock()
        val scope = listId()
        val aliases = database.historyDao().aliases(scope).associate { it.fromKey to it.toKey }
        val first = nextPosition()
        val facts = rows.mapIndexed { index, new -> val parsed = new.parsed; val proposed = newId()
            proposed to ItemEntity(proposed, new.rawText, parsed.name.trim(), Normalizer.normalize(parsed.name),
                parsed.quantity?.amount, parsed.quantity?.unit?.name, parsed.note, new.gtin?.value,
                fromSuggestion = new.fromSuggestion, store = new.store, position = first + index * POSITION_STEP, addedBy = new.addedBy?.value,
                createdAt = now, updatedAt = now, listId = scope).facts(aliases)
        }
        val results = replica.submitAll(scope, "capture", facts.map { (proposed, row) -> JsonObject(row + ("proposedId" to JsonPrimitive(proposed))) })
        facts.zip(results) { (proposed, row), result ->
            require(result.outcome in setOf(Outcome.APPLIED, Outcome.MERGED, Outcome.CONFLICT))
            // An incompatible amount remains a visible conflict beside the existing row.
            ItemId(requireNotNull(if (result.outcome == Outcome.CONFLICT) result.projection.rows.values.firstOrNull {
                !it.archived && it.facts.string("key") == row.string("key")
            }?.id else result.projection.redirects[proposed] ?: proposed))
        }
    }
    /**
     * Adds up to [IMPORT_BATCH] new rows, ticked or not, as one change. Capturing them one by one rewrites
     * the list snapshot once per row, which made a long note take seconds and hold the database meanwhile.
     */
    suspend fun addBatch(rows: List<Pair<ParsedItem, Boolean>>): List<ItemId> = database.withTransaction {
        require(rows.size <= IMPORT_BATCH)
        if (rows.isEmpty()) return@withTransaction emptyList()
        val scope = listId()
        val now = clock()
        val aliases = database.historyDao().aliases(scope).associate { it.fromKey to it.toKey }
        val first = nextPosition()
        val entities = rows.mapIndexed { index, (parsed, checked) -> ItemEntity(newId(), parsed.name, parsed.name.trim(), Normalizer.normalize(parsed.name),
            parsed.quantity?.amount, parsed.quantity?.unit?.name, parsed.note, null, checked = checked, checkedAt = if (checked) now else null,
            position = first + index * POSITION_STEP, createdAt = now, updatedAt = now, listId = scope) }
        val result = replica.submitTo(scope, "import", buildJsonObject {
            put("rows", JsonArray(entities.map { row -> buildJsonObject {
                put("id", row.id); put("facts", row.facts(aliases)); put("expectedLifecycle", JsonNull); put("expected", buildJsonObject { })
                put("archived", false); put("deleted", false)
            } }))
        })
        require(result.outcome == Outcome.APPLIED)
        entities.map { ItemId(it.id) }
    }
    /** Adds each due repeat, [due] being distinct keys and names, as its own intent; the list is rewritten once. */
    suspend fun addRepeats(due: List<Pair<String, String>>, asOf: Long) = database.withTransaction {
        val scope = listId()
        val state = replica.projection(scope)
        var position = nextPosition()
        val payloads = due.mapNotNull { (key, name) ->
            val revision = state.rules[key]?.getValue("revision")?.jsonPrimitive?.content ?: return@mapNotNull null
            val enabling = state.purchases.values.filter { it.string("key") == key }
                .sortedWith(compareByDescending<JsonObject> { it.getValue("boughtAt").jsonPrimitive.long }.thenBy { it.getValue("id").jsonPrimitive.content })
                .firstOrNull()?.string("id") ?: revision
            val next = nextRepeatCycle(state, scope, key, revision, enabling) ?: return@mapNotNull null
            buildJsonObject {
                put("proposedId", newId()); put("key", key); put("name", name); put("ruleRevision", revision)
                put("enablingPurchase", enabling); put("asOf", asOf)
                put("occurrence", next.first)
                next.second?.let { cycle -> put("cycle", buildJsonObject { put("previous", cycle.previous); put("deletion", cycle.deletion); put("finish", cycle.finish) }) }
                put("position", position); put("createdAt", asOf); put("updatedAt", asOf)
            }.also { position += POSITION_STEP }
        }
        if (payloads.isNotEmpty()) replica.submitAll(scope, "repeat", payloads)
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
    suspend fun edit(id: ItemId, name: String, quantity: Quantity?, preferences: List<ItemPreference>, store: String? = null, version: ItemVersion? = null, note: String? = null): Boolean = database.withTransaction {
        val existing = dao.find(id.value, listId()) ?: return@withTransaction false
        if (existing.deletedAt != null) return@withTransaction false
        val aliases = version?.aliases ?: database.historyDao().aliases(listId()).associate { it.fromKey to it.toKey }
        val key = if (existing.gtin != null && name != existing.gtin) ItemIdentity.resolve(Normalizer.normalize(name), aliases)
            else ItemIdentity.key(name, Normalizer.normalize(name), existing.parsedGtin(), aliases)
        val saved = editFields(id, buildJsonObject {
            put("name", name.trim()); put("key", key)
            put("quantity", quantity?.amount?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("unit", quantity?.unit?.name?.let(::JsonPrimitive) ?: JsonNull); put("store", store?.trim()?.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("note", note?.trim()?.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
        }, if (existing.gtin != null && name != existing.gtin) mapOf("gtin:${existing.gtin}" to key) else emptyMap(), version)
        if (saved) {
            for (preference in preferences) savePreference(preference.entity().copy(listId = listId()), version)
        }
        saved
    }
    internal suspend fun savePreference(value: ItemPreferenceEntity, version: ItemVersion? = null) = savePreferences(listOf(value), version)
    /** Each changed preference, [values] being distinct keys of one list, is its own intent; the list is rewritten once. */
    internal suspend fun savePreferences(values: List<ItemPreferenceEntity>, version: ItemVersion? = null) = database.withTransaction {
        val scope = values.firstOrNull()?.listId ?: return@withTransaction
        require(values.all { it.listId == scope } && values.distinctBy { it.key }.size == values.size)
        if (version != null) require(version.list == scope)
        val stored = database.historyDao().preferences(scope).associateBy { it.key }
        val state = replica.projection(scope)
        val payloads = values.mapNotNull { value ->
            val current = stored[value.key]
            if (current?.category == value.category && current?.repeatEveryDays == value.repeatEveryDays && (current != null || (value.category == null && value.repeatEveryDays == null))) return@mapNotNull null
            buildJsonObject {
                put("key", value.key); put("expected", if (version != null) version.preferences[value.key]?.let(::JsonPrimitive) ?: JsonNull else state.preferences[value.key]?.get("revision") ?: JsonNull)
                put("changes", buildJsonObject {
                    if (current == null || current.category != value.category) put("category", value.category?.let(::JsonPrimitive) ?: JsonNull)
                    if (current == null || current.repeatEveryDays != value.repeatEveryDays) put("repeatEveryDays", value.repeatEveryDays?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
        if (payloads.isNotEmpty()) replica.submitAll(scope, "preference", payloads)
        database.historyDao().saveLocalPreferences(values.map { LocalPreferenceEntity(scope, it.key, it.muted, it.snoozedUntil) })
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
    suspend fun delete(id: ItemId, version: ItemVersion? = null) = deleteAll(listOf(id to version))
    /** Each row is still its own delete for sync, but the list is rewritten once, so emptying a long list is quick. */
    suspend fun deleteAll(rows: List<Pair<ItemId, ItemVersion?>>) = database.withTransaction {
        val scope = listId()
        rows.forEach { (id, version) -> if (version != null) require(version.id == id.value && version.list == scope) }
        val live = rows.mapNotNull { (id, version) -> dao.find(id.value, scope)?.takeIf { it.deletedAt == null }?.let { it to version } }
        if (live.isEmpty()) return@withTransaction
        val aliases = database.historyDao().aliases(scope).associate { it.fromKey to it.toKey }
        val repeating = database.historyDao().preferences(scope).filter { it.repeatEveryDays != null }.associateBy { it.key }
        database.historyDao().saveLocalPreferences(live.mapNotNull { (row, _) -> repeating[ItemIdentity.key(row.toItem(), aliases)] }
            .distinctBy { it.key }.map { LocalPreferenceEntity(scope, it.key, it.muted, clock()) })
        val state = replica.projection(scope)
        replica.submitAll(scope, "delete", live.map { (row, version) -> buildJsonObject {
            put("target", row.id); put("expected", buildJsonObject { version?.fields?.forEach { (key, value) -> put(key, value?.let(::JsonPrimitive) ?: JsonNull) }; put("lifecycle", (if (version != null) version.lifecycle else state.rows[row.id]?.lifecycle)?.let(::JsonPrimitive) ?: JsonNull) })
            put("deletedAt", clock()); put("updatedAt", clock())
        } })
    }
    /**
     * Copies a reviewed row without deduplication. Two local lists commit together. With account lists,
     * the original stays until the server confirms the exact destination commands and both rows are
     * revalidated. An interrupted request can leave a copy, but never a queued premature deletion.
     */
    suspend fun transfer(source: ItemVersion, destination: String, move: Boolean, sync: suspend () -> Unit = {}): ItemTransferResult {
        val prepared = database.withTransaction {
            if (!transferSourceMatches(source)) return@withTransaction PreparedTransfer(ItemTransferResult.SOURCE_CHANGED)
            val target = database.listDao().find(destination)
            if (destination == source.list || target == null || !target.transferWritable())
                return@withTransaction PreparedTransfer(ItemTransferResult.DESTINATION_UNAVAILABLE)
            val sourceList = requireNotNull(database.listDao().find(source.list))
            val sourceState = replica.projection(source.list)
            val original = requireNotNull(dao.find(source.id, source.list))
            val targetState = replica.projection(destination)
            val now = clock()
            val id = newId()
            require(id != source.id && id !in targetState.rows)
            val copied = original.copy(id = id, listId = destination, checked = false, checkedAt = null,
                fromSuggestion = false, position = (dao.maxPosition(destination) ?: 0.0) + POSITION_STEP,
                createdAt = now, updatedAt = now, deletedAt = null)
            val facts = copied.facts(targetState.aliases)
            val imported = replica.submitTo(destination, "import", buildJsonObject {
                put("rows", JsonArray(listOf(buildJsonObject {
                    put("id", id); put("facts", facts); put("expectedLifecycle", JsonNull)
                    put("expected", buildJsonObject { }); put("archived", false); put("deleted", false)
                })))
            })
            check(imported.outcome == Outcome.APPLIED)
            val commands = mutableListOf(requireNotNull(imported.projection.seen[imported.projection.rows.getValue(id).lifecycle]))
            val sourceKey = ItemIdentity.resolve(requireNotNull(sourceState.rows[source.id]?.facts?.string("key")), sourceState.aliases)
            val targetKey = requireNotNull(facts.string("key"))
            val sourceCategory = transferPreference(sourceState, sourceKey)?.second?.string("category")
            val (preferenceKey, targetPreference) = transferPreference(targetState, targetKey) ?: (targetKey to null)
            // A destination's explicit category belongs to its existing items too; keep that choice.
            val category = targetPreference?.string("category") ?: sourceCategory
            if (category != null && targetPreference?.string("category") == null) {
                val preference = replica.submitTo(destination, "preference", buildJsonObject {
                    put("key", preferenceKey); put("expected", targetPreference?.get("revision") ?: JsonNull)
                    put("changes", buildJsonObject { put("category", category) })
                })
                check(preference.outcome == Outcome.APPLIED)
                commands += requireNotNull(preference.projection.seen[preference.projection.preferences.getValue(preferenceKey).string("revision")])
            }
            if (!move) return@withTransaction PreparedTransfer(ItemTransferResult.COPIED)
            if (!sourceList.synced && !target.synced) {
                deleteTransferredSource(source)
                PreparedTransfer(ItemTransferResult.MOVED)
            } else PreparedTransfer(receipt = TransferReceipt(destination, id, facts, targetKey, category, commands, targetState.aliases, targetState.aliasRevision))
        }
        prepared.result?.let { return it }
        val receipt = requireNotNull(prepared.receipt)
        try { sync() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return ItemTransferResult.SOURCE_KEPT }
        return database.withTransaction {
            val target = database.listDao().find(receipt.list)
            val confirmed = replica.confirmedProjection(receipt.list)
            val optimistic = if (target != null) replica.projection(receipt.list) else null
            fun containsCopy(state: Projection): Boolean {
                val row = state.rows[receipt.id] ?: return false
                return !row.archived && receipt.id !in state.tombstones && row.facts == receipt.facts &&
                    state.aliases == receipt.aliases && state.aliasRevision == receipt.aliasRevision &&
                    transferPreference(state, receipt.key)?.second?.string("category") == receipt.category
            }
            val accepted = target != null && target.transferWritable() && target.synced && target.created && confirmed != null &&
                receipt.commands.all { confirmed.seen[it.id] == it && it.id !in confirmed.conflicts && it.id !in confirmed.rejected } &&
                containsCopy(confirmed) && optimistic != null && containsCopy(optimistic)
            if (!accepted || !transferSourceMatches(source)) return@withTransaction ItemTransferResult.SOURCE_KEPT
            deleteTransferredSource(source)
            ItemTransferResult.MOVED
        }
    }

    private suspend fun transferSourceMatches(source: ItemVersion): Boolean {
        if (listId() != source.list || database.listDao().find(source.list)?.transferWritable() != true) return false
        val state = replica.projection(source.list)
        val row = state.rows[source.id] ?: return false
        if (row.archived || source.id in state.tombstones || row.lifecycle != source.lifecycle || row.revisions != source.fields ||
            state.aliasRevision != source.aliasRevision || state.aliases != source.aliases ||
            row.facts != Json.parseToJsonElement(source.facts).jsonObject) return false
        val key = row.facts.string("key")?.let { ItemIdentity.resolve(it, state.aliases) } ?: return false
        val currentPreferences = state.preferences.filterKeys { ItemIdentity.resolve(it, state.aliases) == key }.mapValues { it.value.string("revision") }
        return currentPreferences == source.preferences.filterKeys { ItemIdentity.resolve(it, state.aliases) == key }
    }

    private suspend fun deleteTransferredSource(source: ItemVersion) {
        val state = replica.projection(source.list)
        val key = ItemIdentity.resolve(requireNotNull(state.rows[source.id]?.facts?.string("key")), state.aliases)
        val preferenceKey = transferPreference(state, key)?.first
        database.historyDao().preferences(source.list).firstOrNull { it.key == preferenceKey && it.repeatEveryDays != null }?.let {
            database.historyDao().saveLocalPreferences(listOf(LocalPreferenceEntity(source.list, it.key, it.muted, clock())))
        }
        val result = replica.submitTo(source.list, "delete", buildJsonObject {
            put("target", source.id)
            put("expected", JsonObject(source.fields.mapValues { it.value?.let(::JsonPrimitive) ?: JsonNull } +
                ("lifecycle" to (source.lifecycle?.let(::JsonPrimitive) ?: JsonNull))))
            put("deletedAt", clock()); put("updatedAt", clock())
        })
        check(result.outcome == Outcome.APPLIED)
    }

    private fun transferPreference(state: Projection, key: String): Pair<String, JsonObject>? = state.preferences.entries
        .filter { ItemIdentity.resolve(it.key, state.aliases) == key }
        .sortedWith(compareBy<Map.Entry<String, JsonObject>> { it.key != key }.thenBy { it.key })
        .firstOrNull()?.let { it.key to it.value }

    private fun ListEntity.transferWritable() = !archived && !stale && (!synced || role == null || role in setOf("owner", "editor"))
    private data class PreparedTransfer(val result: ItemTransferResult? = null, val receipt: TransferReceipt? = null)
    private data class TransferReceipt(val list: String, val id: String, val facts: JsonObject, val key: String,
        val category: String?, val commands: List<Intent>, val aliases: Map<String, String>, val aliasRevision: String?)

    suspend fun reap(olderThanMillis: Long = FORTNIGHT_MILLIS): Int = replica.reap(clock() - olderThanMillis)
    suspend fun nextPosition(): Double = (dao.maxPosition(listId()) ?: 0.0) + POSITION_STEP

    companion object {
        /** Gap between consecutive rows, leaving room to drag something in between. */
        const val POSITION_STEP: Double = 1.0
        /** The reducer bounds one import change to 100 rows. */
        const val IMPORT_BATCH = 100

        /** Fourteen days, the tombstone window from design section 7.1. */
        const val FORTNIGHT_MILLIS: Long = 14L * 24 * 60 * 60 * 1000

    }
}

/** One row to capture, as [ItemRepository.add] takes it. */
data class NewItem(val parsed: ParsedItem, val addedBy: MemberId? = null, val gtin: Gtin? = null,
    val rawText: String = parsed.name, val fromSuggestion: Boolean = false, val store: String? = null)
