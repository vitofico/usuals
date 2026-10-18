package io.theficos.usuals.data.local

import androidx.room.withTransaction
import io.theficos.usuals.model.NORMALIZER_VERSION
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.sync.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.util.UUID
import kotlinx.coroutines.flow.*

/** The projection, source intent and outbox are committed by the same Room transaction. */
class LocalReplica(private val database: UsualsDatabase, private val reducer: Reducer = Reducer(NORMALIZER_VERSION), private val newId: () -> String = { UUID.randomUUID().toString() }) : SharedReplica {
    private val json = Json { encodeDefaults = true }
    fun observeStatus(scope: String): Flow<ReplicaStatus> = combine(database.syncDao().observePending(scope), database.syncDao().observeProjection(scope), database.syncDao().observeDismissals(scope)) { pending, saved, dismissed ->
        val state = saved?.optimistic?.let { json.decodeFromString<Projection>(it) }
        val owned = pending.map { it.intentId }.toSet()
        val rejected = state?.rejected.orEmpty().mapValues { (id, code) ->
            Conflict(pending.firstOrNull { it.intentId == id }?.let { json.decodeFromString<Intent>(it.command).payload } ?: JsonObject(emptyMap()), JsonObject(emptyMap()), code)
        }
        ReplicaStatus(pending.count { !it.acknowledged && it.intentId !in dismissed }, (state?.conflicts.orEmpty() + rejected).filterKeys { it in owned && it !in dismissed }.map { (id, conflict) ->
            val original = pending.firstOrNull { it.intentId == id }?.let { json.decodeFromString<Intent>(it.command) }
            val target = if (original?.kind == "alias") null else original?.payload?.string("target")?.let { state?.redirects?.get(it) ?: it } ?:
                state?.rows?.values?.firstOrNull { it.facts.string("name") == conflict.current.string("name") }?.id
            ReplicaConflict(id, target, conflict.current.scalarValues(),
                ((if (original?.kind == "alias") conflict.incoming["delta"] as? JsonObject else conflict.incoming["facts"] as? JsonObject) ?: conflict.incoming).scalarValues(), original?.kind.orEmpty())
        })
    }
    suspend fun dismissConflict(id: String) = database.withTransaction {
        val scope = database.listDao().selected()
        val dao = database.syncDao()
        dao.dismiss(ConflictDismissalEntity(scope, id))
        val saved = dao.projection(scope) ?: return@withTransaction
        val before = json.decodeFromString<Projection>(saved.optimistic)
        val after = rebase(scope, json.decodeFromString(saved.confirmed))
        materialize(scope, before, after)
        dao.saveProjection(saved.copy(optimistic = json.encodeToString(after)))
    }
    suspend fun projection(): Projection = projection(database.listDao().selected())
    suspend fun projection(scope: String): Projection =
        database.syncDao().projection(scope)?.optimistic?.let { json.decodeFromString<Projection>(it) } ?: seed(scope)

    suspend fun submit(kind: String, makePayload: suspend (Projection, String) -> JsonObject): Reduction = database.withTransaction {
        val scope = database.listDao().selected()
        submitTo(scope, kind, makePayload(projection(scope), scope))
    }
    suspend fun submitTo(scope: String, kind: String, payload: JsonObject): Reduction = database.withTransaction {
        val before = projection(scope)
        val intent = Intent(newId(), scope, kind, payload, normalizer = NORMALIZER_VERSION)
        val result = reducer.apply(before, intent)
        check(result.outcome != Outcome.UPGRADE_REQUIRED)
        materialize(scope, before, result.projection)
        val shared = database.listDao().find(scope)?.shared == true
        val encoded = json.encodeToString(result.projection)
        val confirmed = if (shared) database.syncDao().projection(scope)?.confirmed ?: json.encodeToString(before) else encoded
        database.syncDao().saveProjection(ProjectionEntity(scope, confirmed, encoded))
        database.syncDao().enqueue(OutboxEntity(scope, intent.id, database.syncDao().nextOrdinal(scope), json.encodeToString(intent), acknowledged = !shared))
        result
    }
    /** Transport verifies signatures, membership and the hash chain before handing over these records. */
    suspend fun receive(scope: String, entries: List<ConfirmedOperation>, epoch: Long, manifestHash: String): ReceiveResult = database.withTransaction {
        require(entries.size <= 100)
        val dao = database.syncDao()
        val saved = dao.projection(scope)
        val before = saved?.optimistic?.let { json.decodeFromString<Projection>(it) } ?: seed(scope)
        var confirmed = saved?.confirmed?.let { json.decodeFromString<Projection>(it) } ?: before
        var cursor = dao.cursor(scope) ?: CursorEntity(scope, 0, Wire.zero, epoch, manifestHash)
        require(epoch >= cursor.epoch && (epoch != cursor.epoch || manifestHash == cursor.manifestHash))
        var applied = 0
        var upgrade = false
        for (entry in entries) {
            require(entry.intent.listId == scope)
            if (entry.seq <= cursor.seq) {
                val old = dao.received(scope, entry.seq)
                require(old != null && old.intentId == entry.intent.id && old.envelopeId == entry.envelopeId && old.digest == entry.digest && old.head == entry.head)
                continue
            }
            require(entry.seq == cursor.seq + 1 && entry.previousHead == cursor.head)
            val result = reducer.apply(confirmed, entry.intent)
            if (result.outcome == Outcome.UPGRADE_REQUIRED) { upgrade = true; break }
            confirmed = result.projection
            dao.received(ReceivedEntity(scope, entry.seq, entry.intent.id, entry.envelopeId, entry.digest, entry.head))
            cursor = CursorEntity(scope, entry.seq, entry.head, epoch, manifestHash)
            applied++
        }
        val optimistic = rebase(scope, confirmed)
        materialize(scope, before, optimistic)
        dao.saveProjection(ProjectionEntity(scope, json.encodeToString(confirmed), json.encodeToString(optimistic)))
        dao.cursor(cursor)
        ReceiveResult(applied, upgrade)
    }

    override suspend fun confirmed(list: String): ReplicaCheckpoint? = database.withTransaction {
        val dao = database.syncDao()
        val cursor = dao.cursor(list) ?: return@withTransaction null
        val saved = dao.projection(list) ?: return@withTransaction null
        ReplicaCheckpoint(ListCheckpoint(cursor.seq, cursor.head, SnapshotCodec.encode(json.decodeFromString<Projection>(saved.confirmed))), cursor.epoch, cursor.manifestHash)
    }
    override suspend fun pending(list: String): List<PendingIntent> = database.withTransaction {
        val dao = database.syncDao()
        val dismissed = dao.dismissals(list).toSet()
        val accepted = dao.projection(list)?.confirmed?.let { json.decodeFromString<Projection>(it).seen }.orEmpty()
        dao.pending(list).filter { !it.acknowledged && it.intentId !in dismissed &&
            (accepted[it.intentId] == null || accepted[it.intentId] == json.decodeFromString<Intent>(it.command)) }.map { PendingIntent(json.decodeFromString(it.command), it.envelope) }
    }
    override suspend fun saveEnvelope(list: String, intent: Intent, bytes: ByteArray) = database.withTransaction {
        require(intent.listId == list)
        val dao = database.syncDao()
        val command = requireNotNull(dao.pending(list).firstOrNull { it.intentId == intent.id })
        require(!command.acknowledged && json.decodeFromString<Intent>(command.command) == intent)
        dao.update(command.copy(envelope = bytes))
    }
    /** Only a fully authenticated checkpoint enters this transaction. Optimistic work is never its source. */
    override suspend fun confirm(list: String, checkpoint: ListCheckpoint, epoch: Long, manifestHash: String) = database.withTransaction {
        val dao = database.syncDao()
        require(database.listDao().find(list)?.shared == true)
        dao.cursor(list)?.let { prior ->
            require(epoch >= prior.epoch && checkpoint.seq >= prior.seq)
            require(epoch != prior.epoch || manifestHash == prior.manifestHash)
            require(checkpoint.seq != prior.seq || checkpoint.head == prior.head)
        }
        val confirmed = SnapshotCodec.decode(checkpoint.payload)
        require(confirmed.seen.values.all { it.listId == list })
        val before = projection(list)
        val optimistic = rebase(list, confirmed)
        materialize(list, before, optimistic)
        dao.saveProjection(ProjectionEntity(list, json.encodeToString(confirmed), json.encodeToString(optimistic)))
        dao.cursor(CursorEntity(list, checkpoint.seq, checkpoint.head, epoch, manifestHash))
    }
    private suspend fun rebase(scope: String, confirmed: Projection): Projection {
        val dao = database.syncDao()
        val dismissed = dao.dismissals(scope).toSet()
        var optimistic = confirmed
        for (command in dao.pending(scope)) {
            val source = json.decodeFromString<Intent>(command.command)
            val accepted = confirmed.seen[command.intentId]
            if (accepted == source) {
                if (!command.acknowledged) dao.update(command.copy(acknowledged = true))
            } else if (!command.acknowledged && command.intentId !in dismissed) {
                if (accepted != null) {
                    val target = source.payload.string("target")
                    optimistic = optimistic.copy(conflicts = optimistic.conflicts + (source.id to Conflict(source.payload,
                        optimistic.rows[target]?.facts ?: JsonObject(emptyMap()), "intent_collision")))
                } else {
                    val result = reducer.apply(optimistic, source)
                    check(result.outcome != Outcome.UPGRADE_REQUIRED)
                    optimistic = result.projection
                }
            }
        }
        return optimistic
    }
    suspend fun exportPending(): String = database.withTransaction {
        val dao = database.syncDao()
        val lists = database.listDao().all().filter { it.shared }.mapNotNull { list ->
            val state = projection(list.id); val dismissed = dao.dismissals(list.id).toSet()
            val commands = dao.pending(list.id).filter { it.intentId !in dismissed &&
                (!it.acknowledged || it.intentId in state.conflicts || it.intentId in state.rejected) }
            if (commands.isEmpty()) null else buildJsonObject {
                put("name", list.name)
                put("commands", JsonArray(commands.map { command -> buildJsonObject {
                    put("source", json.encodeToJsonElement(json.decodeFromString<Intent>(command.command)))
                    put("status", if (command.intentId in state.rejected) "rejected" else if (command.intentId in state.conflicts) "conflict" else "pending")
                    state.conflicts[command.intentId]?.let { put("conflict", json.encodeToJsonElement(it)) }
                    state.rejected[command.intentId]?.let { put("rejection", it) }
                } }))
            }
        }
        buildJsonObject { put("formatVersion", 1); put("lists", JsonArray(lists)) }.toString()
    }
    suspend fun installLocalFacts(scope: String, copy: FrozenCopy) = database.withTransaction {
        require(database.listDao().find(scope)?.shared == false && database.historyDao().allItems(scope).isEmpty() && database.syncDao().projection(scope) == null)
        val restored = SnapshotCodec.decode(copy.payload()).copy(name = requireNotNull(database.listDao().find(scope)).name)
        materialize(scope, Projection(), restored)
        val initial = seed(scope)
        val encoded = json.encodeToString(initial)
        database.syncDao().saveProjection(ProjectionEntity(scope, encoded, encoded))
    }
    suspend fun copyTo(source: String, destination: String, includeHistory: Boolean) = database.withTransaction {
        // Rebuild genesis from materialized facts, excluding device choices and prior command lineage.
        val seeded = seed(source)
        val rows = seeded.rows.filterValues { !it.archived || includeHistory }
        val initial = seeded.copy(rows = rows, purchases = if (includeHistory) seeded.purchases else emptyMap(),
            trips = if (includeHistory) seeded.trips else emptyMap(), closes = if (includeHistory) seeded.closes else emptyMap(),
            tombstones = seeded.tombstones.filterKeys { it in rows }, name = requireNotNull(database.listDao().find(destination)).name)
        materialize(destination, Projection(), initial)
        val encoded = json.encodeToString(initial)
        database.syncDao().saveProjection(ProjectionEntity(destination, encoded, encoded))
    }
    suspend fun reap(cutoff: Long): Int = database.withTransaction {
        val scope = database.listDao().selected()
        if (database.listDao().find(scope)?.shared == true) return@withTransaction 0
        val gone = database.historyDao().allItems(scope).filter { it.deletedAt?.let { at -> at < cutoff } == true }.map { it.id }.toSet()
        val count = database.itemDao().reapTombstones(cutoff, scope)
        database.syncDao().projection(scope)?.let {
            val state = json.decodeFromString<Projection>(it.optimistic)
            val cleaned = json.encodeToString(state.copy(rows = state.rows - (gone - state.occurrences.values.toSet())))
            database.syncDao().saveProjection(it.copy(confirmed = cleaned, optimistic = cleaned))
        }
        count
    }
    private suspend fun seed(scope: String): Projection {
        val history = database.historyDao()
        val aliases = history.aliases(scope).associate { it.fromKey to it.toKey }
        val purchases = history.purchases(scope)
        val byItem = purchases.associateBy { it.id }
        val rows = history.allItems(scope).associate { item -> item.id to Row(item.id, item.facts(aliases),
            lifecycle = byItem[item.id]?.let { "legacy:${it.tripId}" }, archived = item.deletedAt != null) }
        val prefs = history.sharedPreferences(scope)
        return Projection(rows = rows, purchases = purchases.associate { it.id to it.facts() },
            trips = history.trips(scope).associate { it.id to buildJsonObject { put("closedAt", it.closedAt) } }, aliases = aliases,
            preferences = prefs.associate { it.key to buildJsonObject { put("category", it.category?.let(::JsonPrimitive) ?: JsonNull); put("repeatEveryDays", it.repeatEveryDays?.let(::JsonPrimitive) ?: JsonNull) } },
            rules = prefs.filter { it.repeatEveryDays != null }.associate { it.key to buildJsonObject { put("revision", "genesis"); put("everyDays", it.repeatEveryDays!!) } },
            tombstones = rows.filter { (id, row) -> row.archived && id !in byItem }.mapValues { "genesis" },
            closes = purchases.groupBy { it.tripId }.mapKeys { "legacy:${it.key}" }.mapValues { (_, values) -> Close(values.first().tripId, values.map { it.id }) },
            name = database.listDao().find(scope)?.name.orEmpty())
    }
    private suspend fun materialize(scope: String, before: Projection, after: Projection) {
        val history = database.historyDao()
        // A losing optimistic capture is replaced by its durable redirect, not another live Room row.
        for (id in before.rows.keys - after.rows.keys) database.itemDao().removeProjectionRow(id, scope)
        for (key in before.preferences.keys - after.preferences.keys) history.removeSharedPreference(key, scope)
        for (key in before.aliases.keys - after.aliases.keys) history.removeAlias(key, scope)
        for ((id, row) in after.rows) if (row != before.rows[id]) database.itemDao().insert(row.entity(scope, id in after.tombstones))
        for ((id, facts) in before.purchases) if (after.purchases[id] != facts) history.removePurchase(id, scope)
        for (id in before.trips.keys - after.trips.keys) history.removeTrip(id, scope)
        for ((id, trip) in after.trips) if (trip != before.trips[id]) history.insertTrip(TripEntity(id, trip.getValue("closedAt").jsonPrimitive.long, scope))
        val purchases = after.purchases.filter { (id, facts) -> facts != before.purchases[id] }
        history.insertPurchases(purchases.map { (id, facts) -> facts.purchase(id, scope) })
        history.saveSharedPreferences(after.preferences.filter { (key, value) -> value != before.preferences[key] }.map { (key, value) ->
            SharedPreferenceEntity(scope, key, value.string("category"), value["repeatEveryDays"]?.jsonPrimitive?.intOrNull)
        })
        history.saveAliases(after.aliases.filter { (key, value) -> value != before.aliases[key] }.map { ItemAliasEntity(it.key, it.value, scope) })
        if (before.name != after.name) database.listDao().find(scope)?.let { database.listDao().update(it.copy(name = after.name)) }
    }
}

internal fun ItemEntity.facts(aliases: Map<String, String>) = buildJsonObject {
    put("key", ItemIdentity.key(toItem(), aliases)); put("name", name); put("rawText", rawText)
    put("quantity", quantityAmount?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("unit", quantityUnit?.let(::JsonPrimitive) ?: JsonNull)
    put("note", note?.let(::JsonPrimitive) ?: JsonNull); put("gtin", gtin?.let(::JsonPrimitive) ?: JsonNull)
    put("checked", checked); put("checkedAt", checkedAt?.let(::JsonPrimitive) ?: JsonNull)
    put("fromSuggestion", fromSuggestion); put("store", store?.let(::JsonPrimitive) ?: JsonNull)
    put("position", position); put("addedBy", addedBy?.let(::JsonPrimitive) ?: JsonNull)
    put("createdAt", createdAt); put("updatedAt", updatedAt)
    put("deletedAt", deletedAt?.let(::JsonPrimitive) ?: JsonNull); put("normalizerVersion", NORMALIZER_VERSION)
}
internal fun PurchaseEntity.facts() = buildJsonObject {
    put("id", id); put("tripId", tripId); put("key", key); put("name", name)
    put("quantity", amount?.toString()?.let(::JsonPrimitive) ?: JsonNull); put("unit", unit?.let(::JsonPrimitive) ?: JsonNull)
    put("gtin", gtin?.let(::JsonPrimitive) ?: JsonNull); put("boughtAt", boughtAt)
    put("fromSuggestion", fromSuggestion); put("normalizerVersion", normalizerVersion)
}
internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
internal fun Row.entity(scope: String, deleted: Boolean): ItemEntity = ItemEntity(id,
    facts.string("rawText") ?: facts.string("name").orEmpty(), facts.string("name").orEmpty(), Normalizer.normalize(facts.string("name").orEmpty()),
    facts.string("quantity")?.toDouble(), facts.string("unit"), facts.string("note"), facts.string("gtin"),
    facts["checked"]?.jsonPrimitive?.booleanOrNull ?: false, facts["checkedAt"]?.jsonPrimitive?.longOrNull,
    facts["fromSuggestion"]?.jsonPrimitive?.booleanOrNull ?: false, facts.string("store"), facts["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    facts.string("addedBy"), facts["createdAt"]?.jsonPrimitive?.longOrNull ?: 0, facts["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0,
    if (deleted || archived) facts["deletedAt"]?.jsonPrimitive?.longOrNull ?: facts["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0 else null, scope)
internal fun JsonObject.purchase(id: String, scope: String) = PurchaseEntity(id, getValue("tripId").jsonPrimitive.content,
    string("key") ?: Normalizer.normalize(string("name").orEmpty()), string("name").orEmpty(), string("quantity")?.toDouble(), string("unit"), string("gtin"),
    getValue("boughtAt").jsonPrimitive.long, get("fromSuggestion")?.jsonPrimitive?.booleanOrNull ?: false,
    get("normalizerVersion")?.jsonPrimitive?.intOrNull ?: NORMALIZER_VERSION, scope)

data class ReplicaConflict(val id: String, val itemId: String?, val current: Map<String, String?>, val incoming: Map<String, String?>, val kind: String)
private fun JsonObject.scalarValues(): Map<String, String?> = filterValues { it is JsonPrimitive }.mapValues { (_, value) ->
    if (value == JsonNull) null else value.jsonPrimitive.content
}
data class ReplicaStatus(val pending: Int, val conflicts: List<ReplicaConflict>)

data class ConfirmedOperation(val seq: Long, val previousHead: String, val head: String, val envelopeId: String, val digest: String, val intent: Intent)
data class ReceiveResult(val applied: Int, val upgradeRequired: Boolean)
