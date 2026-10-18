package io.theficos.comanda.data.local

import androidx.room.withTransaction
import io.theficos.comanda.model.*
import io.theficos.comanda.parse.ItemIdentity
import io.theficos.comanda.parse.Normalizer
import io.theficos.comanda.parse.ParsedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import io.theficos.comanda.sync.*
import java.util.UUID

class HistoryRepository(
    private val database: ComandaDatabase,
    private val items: ItemRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val replica: LocalReplica = LocalReplica(database),
) {
    private val dao = database.historyDao()
    private suspend fun listId() = database.listDao().selected()
    private val json = Json { encodeDefaults = true }

    fun observeShopping(): Flow<ShoppingSnapshot> = dao.observeShoppingInvalidations(SimpleSQLiteQuery("SELECT 1")).map {
        database.withTransaction {
            val scope = listId(); val rows = items.items()
            val token = rows.firstOrNull()?.version?.let { ListVersion(scope, it.aliasRevision) }
                ?: ListVersion(scope, replica.projection(scope).aliasRevision)
            ShoppingSnapshot(scope, rows, snapshot(), listVersion = token)
        }
    }
    fun observe(): Flow<HistorySnapshot> = observeShopping().map { it.history }

    suspend fun snapshot(): HistorySnapshot = database.withTransaction {
        HistorySnapshot(dao.trips(listId = listId()).map { it.domain() }, dao.purchases(listId = listId()).map { it.domain() },
            dao.preferences(listId = listId()).map { it.domain() }, dao.aliases(listId = listId()).map { it.domain() })
    }

    suspend fun finish(onlyIds: Set<ItemId>? = null, versions: List<ItemVersion>? = null): FinishedTrip? = database.withTransaction {
        items.reap()
        val state = replica.projection()
        if (versions != null) require(versions.all { it.list == listId() })
        val frozen = versions?.filter { onlyIds == null || ItemId(it.id) in onlyIds }?.map { version ->
            io.theficos.comanda.sync.Row(version.id, Json.parseToJsonElement(version.facts).jsonObject, version.fields, version.lifecycle)
        } ?: database.itemDao().active(listId = listId()).filter { it.checked && (onlyIds == null || ItemId(it.id) in onlyIds) }.map { state.rows.getValue(it.id) }
        val checked = frozen.filter { it.facts["checked"]?.jsonPrimitive?.booleanOrNull == true }
        if (checked.isEmpty()) return@withTransaction null
        val trip = TripEntity(newId(), clock(), listId())
        val result = replica.submit("finish") { _, _ -> buildJsonObject {
            put("tripId", trip.id); put("closedAt", trip.closedAt)
            put("rows", JsonArray(checked.map { row ->
                buildJsonObject {
                    put("id", row.id); put("facts", row.facts)
                    put("expectedLifecycle", row.lifecycle?.let(::JsonPrimitive) ?: JsonNull)
                    put("expectedFields", JsonObject(row.revisions.mapValues { it.value?.let(::JsonPrimitive) ?: JsonNull }))
                }
            }))
        } }
        require(result.outcome in setOf(Outcome.APPLIED, Outcome.CONFLICT, Outcome.NO_OP))
        val count = result.projection.purchases.values.count { it.string("tripId") == trip.id }
        if (count == 0) null else FinishedTrip(trip.domain(), count)
    }

    suspend fun undo(tripId: String): Boolean = database.withTransaction {
        val state = replica.projection()
        val close = state.closes.entries.firstOrNull { it.value.tripId == tripId && it.key !in state.cancelledCloses } ?: return@withTransaction false
        replica.submit("undo") { _, _ -> buildJsonObject { put("finishId", close.key); put("tripId", tripId) } }.outcome == Outcome.APPLIED
    }

    suspend fun importPast(parsed: List<ParsedItem>, boughtAt: Long): FinishedTrip? = database.withTransaction {
        if (parsed.isEmpty()) return@withTransaction null
        val trip = TripEntity(newId(), boughtAt, listId())
        val aliases = dao.aliases(listId()).associate { it.fromKey to it.toKey }
        val purchases = parsed.map { draft ->
            require(draft.name.isNotBlank())
            purchase(ItemEntity(id = newId(), rawText = draft.name, name = draft.name,
                normalizedName = Normalizer.normalize(draft.name), quantityAmount = draft.quantity?.amount,
                quantityUnit = draft.quantity?.unit?.name, checked = true, checkedAt = boughtAt,
                createdAt = boughtAt, updatedAt = boughtAt), trip, aliases)
        }
        // A long past list becomes bounded intents inside one local transaction.
        for (batch in purchases.chunked(99)) {
            replica.submit("import") { _, _ -> buildJsonObject {
                put("trips", JsonArray(listOf(buildJsonObject { put("id", trip.id); put("closedAt", trip.closedAt) })))
                put("purchases", JsonArray(batch.map { it.facts() }))
            } }.also { require(it.outcome == Outcome.APPLIED) }
        }
        FinishedTrip(trip.domain(), purchases.size)
    }

    suspend fun savePreference(value: ItemPreference, version: ItemVersion? = null) = items.savePreference(value.entity().copy(listId = listId()), version)
    suspend fun saveAlias(value: ItemAlias, version: ListVersion? = null) = items.saveAlias(ItemAliasEntity(value.fromKey, value.toKey, listId()), version)

    suspend fun exportJson(): String = database.withTransaction {
        val lists = database.listDao().all().ifEmpty { listOf(ListEntity(LOCAL_LIST_ID, LOCAL_HOUSEHOLD_ID, "")) }
        json.encodeToString(ShoppingArchive(formatVersion = 2,
            selectedListId = listId(), lists = lists.map { ArchiveList(it.id, it.name, it.shared) },
            items = lists.flatMap { dao.allItems(it.id) }, trips = lists.flatMap { dao.trips(it.id) },
            purchases = lists.flatMap { dao.purchases(it.id) }, preferences = lists.flatMap { dao.preferences(it.id) },
            aliases = lists.flatMap { dao.aliases(it.id) }))
    }

    /** A shopping archive cannot select a relay or restore device authority. */
    suspend fun importJson(text: String) {
        require(text.toByteArray().size <= 10 * 1024 * 1024)
        val decoded = json.decodeFromString<ShoppingArchive>(text)
        require(decoded.formatVersion in 1..2)
        database.withTransaction {
            if (decoded.formatVersion == 1) {
                require(database.listDao().find(listId())?.shared != true)
                importArchive(decoded.rebind(listId()))
            } else {
                require(decoded.lists.isNotEmpty() && decoded.lists.size <= 100)
                require(decoded.lists.map { it.id }.distinct().size == decoded.lists.size)
                val ids = decoded.lists.map { it.id }.toSet()
                require(decoded.items.all { it.listId in ids } && decoded.trips.all { it.listId in ids } &&
                    decoded.purchases.all { it.listId in ids } && decoded.preferences.all { it.listId in ids } && decoded.aliases.all { it.listId in ids })
                require(decoded.selectedListId == null || decoded.selectedListId in ids)
                val selected = database.listDao().find(listId())
                val emptyDestination = selected?.shared != true && dao.allItems(listId()).isEmpty() &&
                    dao.trips(listId()).isEmpty() && dao.purchases(listId()).isEmpty() &&
                    dao.preferences(listId()).isEmpty() && dao.aliases(listId()).isEmpty()
                val household = database.listDao().localHousehold() ?: HouseholdEntity(UUID.randomUUID().toString())
                val copies = decoded.lists.map { manifest ->
                    require(manifest.id.isNotBlank() && manifest.name.length <= 100)
                    val existing = database.listDao().find(manifest.id)
                    val copied = if (manifest.shared || existing?.shared == true) {
                        database.listDao().all().firstOrNull { it.archiveSource == manifest.id } ?:
                            ListEntity(UUID.randomUUID().toString(), household.id, manifest.name, archiveSource = manifest.id)
                    } else existing ?: ListEntity(manifest.id, household.id, manifest.name, archiveSource = manifest.id)
                    val part = decoded.copy(formatVersion = 1, lists = emptyList(),
                        items = decoded.items.filter { it.listId == manifest.id }, trips = decoded.trips.filter { it.listId == manifest.id },
                        purchases = decoded.purchases.filter { it.listId == manifest.id }, preferences = decoded.preferences.filter { it.listId == manifest.id },
                        aliases = decoded.aliases.filter { it.listId == manifest.id }).rebind(copied.id)
                    validate(part)
                    copied to part
                }
                // Validate every manifest before inserting any scope or shopping fact.
                database.listDao().household(household)
                for ((list, part) in copies) {
                    if (database.listDao().find(list.id) == null) database.listDao().insert(list)
                    importArchive(part)
                }
                if (emptyDestination) {
                    val source = decoded.selectedListId ?: decoded.lists.first().id
                    database.listDao().select(ListSelectionEntity(listId = copies[decoded.lists.indexOfFirst { it.id == source }].first.id))
                }
            }
        }
    }

    private suspend fun importArchive(archive: ShoppingArchive) {
        validate(archive)
        val scope = archive.items.firstOrNull()?.listId ?: archive.trips.firstOrNull()?.listId ?:
            archive.preferences.firstOrNull()?.listId ?: archive.aliases.firstOrNull()?.listId ?: listId()
        val existingPurchases = dao.purchases(scope).associateBy { it.id }
        val existingTrips = dao.trips(scope).associateBy { it.id }
        require(archive.purchases.all { existingPurchases[it.id]?.let { existing -> existing == it } ?: true })
        require(archive.trips.all { existingTrips[it.id]?.let { existing -> existing == it } ?: true })
        validateAliases(dao.aliases(scope).associate { it.fromKey to it.toKey } + archive.aliases.associate { it.fromKey to it.toKey })
        val state = replica.projection(scope)
        val aliases = state.aliases + archive.aliases.associate { it.fromKey to it.toKey }
        val changed = archive.items.filter { item ->
            if (item.deletedAt == null && item.id in existingPurchases) false
            else database.itemDao().find(item.id, scope)?.let { existing ->
                maxOf(item.updatedAt, item.deletedAt ?: Long.MIN_VALUE) > maxOf(existing.updatedAt, existing.deletedAt ?: Long.MIN_VALUE)
            } ?: true
        }
        val patches = changed.map { item -> buildJsonObject {
            val row = state.rows[item.id]
            put("id", item.id); put("facts", item.facts(aliases))
            put("expectedLifecycle", row?.lifecycle?.let(::JsonPrimitive) ?: JsonNull)
            put("expected", JsonObject(row?.revisions.orEmpty().mapValues { it.value?.let(::JsonPrimitive) ?: JsonNull }))
            put("archived", item.deletedAt != null); put("deleted", item.deletedAt != null)
        } }
        val tripFacts = archive.trips.filter { it.id !in existingTrips }.map { buildJsonObject { put("id", it.id); put("closedAt", it.closedAt) } }
        val purchaseFacts = archive.purchases.filter { it.id !in existingPurchases }.map { it.facts() }
        // Preserve parent-before-child order while bounding each encrypted import command.
        for (batch in tripFacts.chunked(100)) replica.submitTo(scope, "import", buildJsonObject { put("trips", JsonArray(batch)) }).also { require(it.outcome == Outcome.APPLIED) }
        for (batch in (patches.map { "rows" to it } + purchaseFacts.map { "purchases" to it }).chunked(100)) {
            replica.submitTo(scope, "import", buildJsonObject {
                batch.groupBy({ it.first }, { it.second }).forEach { (kind, values) -> put(kind, JsonArray(values)) }
            }).also { require(it.outcome == Outcome.APPLIED) }
        }
        for (pref in archive.preferences) items.savePreference(pref)
        for (alias in archive.aliases) require(items.saveAlias(alias))
    }

    private fun purchase(item: ItemEntity, trip: TripEntity, aliases: Map<String, String>): PurchaseEntity {
        val key = ItemIdentity.key(item.toItem(), aliases)
        return PurchaseEntity(item.id, trip.id, key, item.name, item.quantityAmount, item.quantityUnit,
            item.gtin, item.checkedAt ?: trip.closedAt, item.fromSuggestion, listId = trip.listId)
    }

    private fun validate(archive: ShoppingArchive) {
        require(archive.formatVersion == 1)
        fun validId(id: String) = id.isNotBlank() && id.length <= 500
        fun quantity(amount: Double?, unit: String?) = (amount == null && unit == null) ||
            (amount != null && amount.isFinite() && amount >= 0 && enumOrNull<MeasureUnit>(unit) != null)
        fun barcode(gtin: String?) = gtin == null || Gtin.parse(gtin) != null
        require(archive.items.map { it.id }.distinct().size == archive.items.size)
        require(archive.trips.map { it.id }.distinct().size == archive.trips.size)
        require(archive.purchases.map { it.id }.distinct().size == archive.purchases.size)
        require(archive.preferences.map { it.key }.distinct().size == archive.preferences.size)
        require(archive.aliases.map { it.fromKey }.distinct().size == archive.aliases.size)
        val trips = archive.trips.map { it.id }.toSet()
        val purchases = archive.purchases.map { it.id }.toSet()
        val nonemptyTrips = archive.purchases.map { it.tripId }.toSet()
        require(archive.trips.all { validId(it.id) && it.id in nonemptyTrips })
        require(archive.items.all { validId(it.id) && it.name.isNotBlank() && it.name.length <= 1000 &&
            quantity(it.quantityAmount, it.quantityUnit) && barcode(it.gtin) &&
            it.position.isFinite() && (it.deletedAt != null || it.id !in purchases) })
        require(archive.purchases.all { validId(it.id) && validId(it.key) && it.name.isNotBlank() &&
            it.tripId in trips && quantity(it.amount, it.unit) && barcode(it.gtin) && it.normalizerVersion > 0 })
        require(archive.preferences.all { validId(it.key) && (it.repeatEveryDays == null || it.repeatEveryDays > 0) })
        require(archive.aliases.all { validId(it.fromKey) && validId(it.toKey) })
        validateAliases(archive.aliases.associate { it.fromKey to it.toKey })
    }

    private fun validateAliases(aliases: Map<String, String>) {
        for (start in aliases.keys) {
            val visited = mutableSetOf<String>()
            var key = start
            while (true) {
                require(visited.add(key))
                val next = aliases[key] ?: break
                if (next == key) break
                key = next
            }
        }
    }
}

@Serializable
private data class ShoppingArchive(
    val formatVersion: Int,
    val lists: List<ArchiveList> = emptyList(),
    val selectedListId: String? = null,
    val items: List<ItemEntity> = emptyList(),
    val trips: List<TripEntity> = emptyList(),
    val purchases: List<PurchaseEntity> = emptyList(),
    val preferences: List<ItemPreferenceEntity> = emptyList(),
    val aliases: List<ItemAliasEntity> = emptyList(),
)

@Serializable
private data class ArchiveList(val id: String, val name: String, val shared: Boolean = false)

private fun ShoppingArchive.rebind(scope: String) = copy(formatVersion = 1, lists = emptyList(), selectedListId = null,
    items = items.map { it.copy(listId = scope) }, trips = trips.map { it.copy(listId = scope) },
    purchases = purchases.map { it.copy(listId = scope) }, preferences = preferences.map { it.copy(listId = scope) },
    aliases = aliases.map { it.copy(listId = scope) })
