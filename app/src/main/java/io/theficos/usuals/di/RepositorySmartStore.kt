package io.theficos.usuals.di

import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.data.local.NewItem
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.list.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RepositorySmartStore(
    private val items: ItemRepository,
    private val history: HistoryRepository,
    private val categories: CategoryClassifier,
    private val ranker: HistoryRanker,
    private val repeats: RepeatPlanner,
    private val suggestions: SuggestionEngine = SuggestionEngine(repeats, IntervalModel(), ranker),
    private val quantities: QuantityHints = QuantityHints(),
    private val offers: RepeatOffers = RepeatOffers(),
    private val similar: SimilarItems = SimilarItems(ranker),
) : SmartStore {
    private val maintenance = Mutex()
    override suspend fun addAgain(rows: List<io.theficos.usuals.ui.shopping.SavedShoppingItem>, scope: String): Int = maintenance.withLock {
        items.transaction {
            require(items.currentListId() == scope)
            val facts = history.snapshot()
            val aliases = facts.aliases.associate { it.fromKey to it.toKey }
            val active = items.items().filterNot { it.checked }.map { ItemIdentity.key(it, aliases) }.toMutableSet()
            val fresh = rows.mapNotNull { row ->
                row.validate()
                val gtin = row.gtin?.let(Gtin::parse)
                val key = ItemIdentity.key(row.name, Normalizer.normalize(row.name), gtin, aliases)
                if (active.add(key)) NewItem(ParsedItem(row.name, row.quantity), gtin = gtin, store = row.store) else null
            }
            items.addAll(fresh).size
        }
    }

    override fun similar(name: String, history: HistorySnapshot, active: List<Item>) = similar.find(name, history, active)
    override fun repeatOffers(active: List<Item>, history: HistorySnapshot) = offers.offers(active, history)

    override suspend fun setRepeat(offer: RepeatOffer) {
        require(offer.days > 0)
        maintenance.withLock { items.transaction {
            val facts = history.snapshot()
            val aliases = facts.aliases.associate { it.fromKey to it.toKey }
            val key = ItemIdentity.resolve(offer.key, aliases)
            val pref = ItemIdentity.preferences(facts.preferences, aliases)[key]?.copy(key = key) ?: ItemPreference(key)
            history.savePreference(pref.copy(repeatEveryDays = offer.days, muted = false, snoozedUntil = null), offer.version)
        } }
    }

    override suspend fun applyQuantityHint(id: ItemId) = applyQuantityHint(id, null)
    override suspend fun applyQuantityHint(id: ItemId, version: ItemVersion?) {
        maintenance.withLock { items.transaction {
            val item = items.items().firstOrNull { it.id == id && it.quantity == null } ?: return@transaction
            val facts = history.snapshot()
            val key = ItemIdentity.key(item, facts.aliases.associate { it.fromKey to it.toKey })
            quantities.hint(key, facts)?.let { items.setQuantity(id, it, version) }
        } }
    }

    override suspend fun acceptQuantityHint(id: ItemId, quantity: Quantity, version: ItemVersion?) = maintenance.withLock {
        items.setQuantity(id, quantity, version)
    }
    override suspend fun addMerged(item: Item, usual: ItemMemory) = addMerged(item, usual, null)
    override suspend fun addMerged(item: Item, usual: ItemMemory, version: ListVersion?): Boolean = maintenance.withLock {
        items.transaction {
            val facts = history.snapshot()
            val aliases = facts.aliases.associate { it.fromKey to it.toKey }
            val from = Normalizer.normalize(item.name)
            val to = if (version != null) usual.key else ItemIdentity.resolve(usual.key, aliases)
            if (from != to && !history.saveAlias(ItemAlias(from, to), version)) return@transaction false
            items.add(ParsedItem(usual.name, item.quantity, item.note), item.addedBy, item.gtin,
                item.rawText, item.fromSuggestion, item.store)
            true
        }
    }

    // No setting enables intervals: rollout requires reviewed real-history replay evidence.
    override fun suggestions(now: Long, history: HistorySnapshot, active: List<Item>) =
        suggestions.rank(now, history, active, intervalsEnabled = false)

    override suspend fun chooseSuggestion(key: String, choice: SuggestionChoice, now: Long) {
        maintenance.withLock {
            items.transaction {
                val facts = history.snapshot()
                val candidate = suggestions(now, facts, items.items()).firstOrNull { it.key == key }
                    ?: return@transaction
                val aliases = facts.aliases.associate { it.fromKey to it.toKey }
                val pref = ItemIdentity.preferences(facts.preferences, aliases)[key]?.copy(key = key) ?: ItemPreference(key)
                when (choice) {
                    SuggestionChoice.ADD -> items.add(ParsedItem(candidate.name), fromSuggestion = true)
                    SuggestionChoice.NOT_NOW -> history.savePreference(pref.copy(snoozedUntil = now))
                    SuggestionChoice.NEVER -> history.savePreference(pref.copy(muted = true))
                }
            }
        }
    }

    override fun present(items: List<Item>, history: HistorySnapshot): Map<ItemId, ItemPresentation> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val preferences = ItemIdentity.preferences(history.preferences, aliases)
        // Grouped once for the whole list: a per-row scan of history is items x purchases.
        val bought by lazy { history.purchases.groupBy { ItemIdentity.purchaseKey(it, aliases) } }
        return items.associate { item ->
            val key = ItemIdentity.key(item, aliases)
            val pref = preferences[key]
            item.id to ItemPresentation(categories.classify(item.name, pref?.category),
                Category.entries.firstOrNull { it.name == pref?.category }, pref?.repeatEveryDays,
                if (item.quantity == null) quantities.hint(bought[key].orEmpty()) else null)
        }
    }

    /** The last history's memories: every keystroke looks them up against the same snapshot. */
    @Volatile private var remembered: Pair<HistorySnapshot, List<ItemMemory>>? = null

    override fun autocomplete(query: String, history: HistorySnapshot, active: List<Item>): List<ItemMemory> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val memories = remembered?.takeIf { it.first === history }?.second
            ?: ranker.memories(history).also { remembered = history to it }
        return ranker.autocomplete(query, memories, active.filterNot { it.checked }.map { ItemIdentity.key(it, aliases) }.toSet())
    }

    override suspend fun maintainRepeats(now: Long, observed: List<Item>, history: HistorySnapshot) {
        if (repeats.due(now, history, observed).isEmpty()) return
        maintenance.withLock { items.transaction { addRepeats(now) } }
    }

    private suspend fun addRepeats(now: Long) =
        items.addRepeats(repeats.due(now, history.snapshot(), items.items()).map { it.key to it.name }, now)

    override suspend fun finish(onlyIds: Set<ItemId>?) = finishPinned(onlyIds, null)
    override suspend fun finishVersions(versions: List<ItemVersion>, onlyIds: Set<ItemId>?) = finishPinned(onlyIds, versions)
    private suspend fun finishPinned(onlyIds: Set<ItemId>?, versions: List<ItemVersion>?): FinishedTrip? = maintenance.withLock {
        items.transaction {
            val result = history.finish(onlyIds, versions) ?: return@transaction null
            addRepeats(result.trip.closedAt)
            result
        }
    }

    override suspend fun undo(tripId: String): Boolean = maintenance.withLock { history.undo(tripId) }

    override suspend fun edit(value: ItemEdit): Boolean = maintenance.withLock {
        items.transaction {
            require(value.name.any { it.isLetterOrDigit() })
            require(value.quantity?.amount?.let { it.isFinite() && it >= 0 } != false)
            require(value.repeatEveryDays == null || value.repeatEveryDays > 0)
            require(value.store == null || value.store.length <= 80)
            require(value.note == null || value.note.length <= ItemEdit.MAX_NOTE)
            val original = items.find(value.id) ?: return@transaction false
            val facts = history.snapshot()
            val aliases = facts.aliases.associate { it.fromKey to it.toKey }
            val pinned = value.version?.facts?.let { org.json.JSONObject(it) }
            val oldKey = pinned?.optString("key")?.takeIf { it.isNotEmpty() } ?: ItemIdentity.key(original, aliases)
            val name = value.name.trim()
            val newKey = if (name == (pinned?.optString("name") ?: original.name)) oldKey else ItemIdentity.resolve(Normalizer.normalize(name), aliases)
            val effective = ItemIdentity.preferences(facts.preferences, aliases)
            val old = effective[oldKey]?.copy(key = oldKey) ?: ItemPreference(oldKey)
            val base = effective[newKey]?.copy(key = newKey) ?: old.copy(key = newKey)
            val repeat = if (value.changeRepeat) value.repeatEveryDays else base.repeatEveryDays
            val preference = base.copy(category = if (value.changeCategory) value.category?.name else base.category, repeatEveryDays = repeat,
                muted = if (value.changeRepeat && repeat != null) false else base.muted,
                snoozedUntil = if (value.changeRepeat && repeat != null) null else base.snoozedUntil)
            val updates = if (oldKey != newKey && old.repeatEveryDays != null) {
                listOf(old.copy(repeatEveryDays = null), preference)
            } else listOf(preference)
            items.edit(value.id, name, value.quantity, updates, value.store, value.version, value.note)
        }
    }
}
