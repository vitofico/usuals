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

/**
 * The projection, source intent and outbox are committed by the same Room transaction. A synced list
 * shows the server's confirmed projection with its pending intents applied on top; a local list's
 * intents are confirmed as they are made.
 */
class LocalReplica(private val database: UsualsDatabase, private val reducer: Reducer = Reducer(NORMALIZER_VERSION), private val newId: () -> String = { UUID.randomUUID().toString() }) {
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
        val synced = database.listDao().find(scope)?.synced == true
        val encoded = json.encodeToString(result.projection)
        val confirmed = if (synced) database.syncDao().projection(scope)?.confirmed ?: json.encodeToString(before) else encoded
        database.syncDao().saveProjection(ProjectionEntity(scope, confirmed, encoded))
        database.syncDao().enqueue(OutboxEntity(scope, intent.id, database.syncDao().nextOrdinal(scope), json.encodeToString(intent), acknowledged = !synced))
        result
    }
    suspend fun rename(scope: String, name: String): Reduction = database.withTransaction {
        require(name.isNotBlank() && name.length <= 100)
        submitTo(scope, "list_name", buildJsonObject {
            put("expected", projection(scope).nameRevision?.let(::JsonPrimitive) ?: JsonNull); put("name", name)
        })
    }

    /**
     * Signed in, every list belongs to the account: a local list's current state becomes its creation snapshot.
     * The phone's unused starting list stays local, so connecting a phone does not add an empty list to the account.
     */
    suspend fun adoptLocalLists() = database.withTransaction {
        for (list in database.listDao().all().filter { !it.synced && !unused(it) }) {
            val encoded = json.encodeToString(projection(list.id))
            database.syncDao().saveProjection(ProjectionEntity(list.id, encoded, encoded))
            database.listDao().update(list.copy(synced = true))
        }
    }
    /** Signing in again, perhaps to another account: every list is offered to the server again and the feed restarts. */
    suspend fun prepareSignIn() = database.withTransaction {
        for (list in database.listDao().all().filter { it.synced && it.created }) database.listDao().update(list.copy(created = false, stale = false))
        database.syncDao().saveFeed(FeedEntity())
    }
    /** Signing out removes the account's lists; work continues in a fresh local list. */
    suspend fun forgetAccount() = database.withTransaction {
        for (list in database.listDao().all()) if (list.synced) drop(list.id)
        database.syncDao().saveFeed(FeedEntity())
    }
    /** Synced lists the server has yet to create or that have pending intents, in creation order. */
    suspend fun outgoing(): List<ListEntity> = database.withTransaction {
        val pending = database.syncDao().pendingLists().toSet()
        database.listDao().all().filter { it.synced && (!it.created || it.id in pending) }
    }
    /** How many changes and uncreated lists have not reached the server. */
    suspend fun unsent(): Int = database.withTransaction { outgoing().sumOf { list -> pendingIntents(list.id, Int.MAX_VALUE).size + if (list.created) 0 else 1 } }
    /** What creating [list] on the server sends: the state it was last confirmed with. */
    suspend fun creation(list: String): Projection = database.withTransaction {
        database.syncDao().projection(list)?.confirmed?.let { json.decodeFromString<Projection>(it) } ?: seed(list)
    }
    /** Pending intents in submission order. One whose id was accepted with other contents waits for review instead. */
    suspend fun pendingIntents(list: String, limit: Int): List<Intent> = database.withTransaction {
        val dao = database.syncDao()
        val dismissed = dao.dismissals(list).toSet()
        val accepted = dao.projection(list)?.confirmed?.let { json.decodeFromString<Projection>(it).seen }.orEmpty()
        dao.pending(list).asSequence().filter { !it.acknowledged && it.intentId !in dismissed }
            .map { json.decodeFromString<Intent>(it.command) }
            .filter { accepted[it.id] == null || accepted[it.id] == it }.take(limit).toList()
    }
    /** A server answer: [acknowledged] intents stop being pending, and the list becomes confirmed unless a newer one is stored. */
    suspend fun accept(server: SharedList, acknowledged: Collection<String> = emptyList()) = database.withTransaction {
        if (acknowledged.isNotEmpty()) database.syncDao().acknowledge(server.id, acknowledged)
        store(server, force = false)
        keepSelection()
    }
    /** Drops pending intents the server can never take, so later ones can still be sent. */
    suspend fun discard(list: String, intents: Collection<String>) = database.withTransaction {
        database.syncDao().acknowledge(list, intents)
        val saved = database.syncDao().projection(list) ?: return@withTransaction
        val before = json.decodeFromString<Projection>(saved.optimistic)
        val confirmed = json.decodeFromString<Projection>(saved.confirmed)
        val after = rebase(list, confirmed)
        materialize(list, before, after)
        database.syncDao().saveProjection(saved.copy(optimistic = json.encodeToString(after)))
    }
    /** One feed page and its cursor commit together; the last page of a reset drops the lists it did not return. */
    suspend fun applyPage(page: ListChanges) = database.withTransaction {
        val lists = database.listDao()
        var resetting = database.syncDao().feed()?.resetting ?: false
        if (page.reset) {
            resetting = true
            for (list in lists.all()) if (list.synced && list.created) lists.update(list.copy(stale = true))
        }
        val entries: List<Pair<Long, Any>> = page.lists.map { it.seq to it } + page.removed.map { it.seq to it }
        for ((seq, entry) in entries.sortedBy { it.first }) when (entry) {
            is SharedList -> store(entry, force = resetting)
            is RemovedList -> lists.find(entry.id)?.takeIf { it.synced && seq >= it.seq }?.let { drop(it.id) }
        }
        if (!page.hasMore && resetting) {
            for (list in lists.all()) if (list.stale) drop(list.id)
            resetting = false
        }
        // Only a synced list that is not archived replaces the blank one, or an account whose lists are all archived would loop.
        if (lists.all().any { it.synced && !it.archived }) for (list in lists.all()) if (unused(list)) drop(list.id)
        keepSelection()
        database.syncDao().saveFeed(FeedEntity(cursor = page.cursor, resetting = resetting))
    }
    suspend fun cursor(): String? = database.syncDao().feed()?.cursor
    /** The server gave this id to someone else: the same contents continue under a new id, as a list to create. */
    suspend fun copyAsNew(source: String): String = database.withTransaction {
        val original = requireNotNull(database.listDao().find(source))
        val id = newId()
        database.listDao().insert(ListEntity(id, original.name, original.archiveSource, synced = original.synced, archived = original.archived))
        copyTo(source, id)
        database.historyDao().saveLocalPreferences(database.historyDao().preferences(source).map { LocalPreferenceEntity(id, it.key, it.muted, it.snoozedUntil) })
        val selected = database.listDao().selected() == source
        drop(source)
        if (selected) database.listDao().select(ListSelectionEntity(listId = id))
        id
    }
    /** A list the server does not hold is archived or restored here; one it holds changes through the server. */
    suspend fun archive(list: String, archived: Boolean) = database.withTransaction {
        database.listDao().update(requireNotNull(database.listDao().find(list)).copy(archived = archived))
        keepSelection()
    }
    /** Removes a list and everything scoped to it, keeping a valid selection. */
    suspend fun drop(list: String) = database.withTransaction {
        val sql = database.openHelper.writableDatabase
        for (table in listOf("items", "purchase", "trip", "item_pref", "local_item_pref", "item_alias", "sync_projection", "sync_outbox", "conflict_dismissal"))
            sql.execSQL("DELETE FROM `$table` WHERE listId = ?", arrayOf(list))
        sql.execSQL("DELETE FROM list_scope WHERE id = ?", arrayOf(list))
        keepSelection()
    }
    /** The selected list is always one that is not archived; with none left, work continues in a fresh local list. */
    private suspend fun keepSelection() {
        val lists = database.listDao()
        val shown = lists.all().filter { !it.archived }
        if (shown.none { it.id == lists.selected() })
            lists.select(ListSelectionEntity(listId = shown.firstOrNull()?.id ?: newId().also { lists.insert(ListEntity(it, "")) }))
    }
    private suspend fun store(server: SharedList, force: Boolean) {
        val lists = database.listDao(); val dao = database.syncDao()
        val owner = json.encodeToString(server.owner); val members = json.encodeToString(server.members)
        val existing = lists.find(server.id)
        if (existing == null) {
            lists.insert(ListEntity(server.id, server.projection.name, synced = true, created = true, seq = server.seq, role = server.role, owner = owner, members = members,
                archived = server.archived, archivedByOwner = server.archivedByOwner))
            materialize(server.id, Projection(name = server.projection.name), server.projection)
            val encoded = json.encodeToString(server.projection)
            dao.saveProjection(ProjectionEntity(server.id, encoded, encoded))
            return
        }
        val saved = dao.projection(server.id)
        val before = saved?.optimistic?.let { json.decodeFromString<Projection>(it) } ?: seed(server.id)
        val newer = force || !existing.created || server.seq >= existing.seq
        val confirmed = if (newer) server.projection else saved?.confirmed?.let { json.decodeFromString<Projection>(it) } ?: before
        if (newer) lists.update(existing.copy(synced = true, created = true, seq = server.seq, role = server.role, owner = owner, members = members, stale = false,
            archived = server.archived, archivedByOwner = server.archivedByOwner))
        val optimistic = rebase(server.id, confirmed)
        materialize(server.id, before, optimistic)
        dao.saveProjection(ProjectionEntity(server.id, json.encodeToString(confirmed), json.encodeToString(optimistic)))
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
    private suspend fun copyTo(source: String, destination: String) {
        // Rebuild genesis from materialized facts, excluding device choices and prior command lineage.
        val seeded = seed(source)
        val initial = seeded.copy(tombstones = seeded.tombstones.filterKeys { it in seeded.rows }, name = requireNotNull(database.listDao().find(destination)).name)
        materialize(destination, Projection(), initial)
        val encoded = json.encodeToString(initial)
        database.syncDao().saveProjection(ProjectionEntity(destination, encoded, encoded))
    }
    suspend fun reap(cutoff: Long): Int = database.withTransaction {
        val scope = database.listDao().selected()
        if (database.listDao().find(scope)?.synced == true) return@withTransaction 0
        val gone = database.historyDao().allItems(scope).filter { it.deletedAt?.let { at -> at < cutoff } == true }.map { it.id }.toSet()
        val count = database.itemDao().reapTombstones(cutoff, scope)
        database.syncDao().projection(scope)?.let {
            val state = json.decodeFromString<Projection>(it.optimistic)
            val cleaned = json.encodeToString(state.copy(rows = state.rows - (gone - state.occurrences.values.toSet())))
            database.syncDao().saveProjection(it.copy(confirmed = cleaned, optimistic = cleaned))
        }
        count
    }
    /** A blank local list nothing was ever added to, like the one a fresh install or signing out starts with. */
    private suspend fun unused(list: ListEntity) = !list.synced && list.name.isBlank() && projection(list.id) == Projection()
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
