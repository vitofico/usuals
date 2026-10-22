package io.theficos.usuals.di

import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.enrich.*
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.smart.BarcodeCategories
import io.theficos.usuals.smart.Category
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.scan.*
import kotlinx.coroutines.CancellationException

class RepositoryScanStore(
    private val items: ItemRepository,
    private val history: HistoryRepository,
    private val smart: SmartStore,
    private val enabled: () -> Boolean,
    private val enricher: () -> ProductEnricher,
    private val categories: BarcodeCategories,
) : ScanStore {
    override suspend fun add(gtin: Gtin) = items.transaction {
        val facts = history.snapshot()
        val key = ItemIdentity.resolve("gtin:${gtin.value}", facts.aliases.associate { it.fromKey to it.toKey })
        // Confirmed aliases survive Finish and removal. Prefer the person's
        // display spelling when an active row or a purchase still carries it.
        val knownName = if (key == "gtin:${gtin.value}" || key.startsWith("gtin:")) null else {
            items.items().firstOrNull { Normalizer.normalize(it.name) == key }?.name
                ?: facts.purchases.filter { Normalizer.normalize(it.name) == key }.maxByOrNull { it.boughtAt }?.name
                ?: key
        }
        items.add(ParsedItem(knownName ?: gtin.value), gtin = gtin)
    }

    override suspend fun lookup(id: ItemId, gtin: Gtin): ScanNameOffer? {
        if (items.items().none { it.id == id && it.name == gtin.value }) return null
        val result = if (enabled()) {
            try { enricher().lookup(gtin) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        // A revoked opt-in cannot display a late third-party result.
        val allowed = enabled()
        val accepted = (result as? QualityLookup.Found)?.quality?.takeIf { allowed }
        val nonFood = accepted?.productType?.takeIf { it != ProductType.FOOD }
        val problem = when {
            !allowed || (result is QualityLookup.Skipped && result.reason == SkipReason.OPTED_OUT) -> ScanNameProblem.LOOKUP_DISABLED
            result is QualityLookup.Skipped && result.reason == SkipReason.RESTRICTED_CIRCULATION -> ScanNameProblem.RESTRICTED
            nonFood != null && accepted?.productName == null -> ScanNameProblem.NOT_FOOD
            result is QualityLookup.Missing || (accepted != null && accepted.productName == null) -> ScanNameProblem.NOT_FOUND
            accepted?.productName != null -> null
            else -> ScanNameProblem.UNAVAILABLE
        }
        val current = items.items().firstOrNull { it.id == id && it.name == gtin.value } ?: return null
        // The database a non-food barcode lives in already says which aisle it
        // belongs to, even when it has no name, and outranks a guess from the name.
        val category = nonFood?.let(::aisleOf) ?: accepted?.productName?.let { categories.category(accepted.categories, it) }
        return ScanNameOffer(id, gtin, accepted?.productName, category, accepted != null, problem, current.version, accepted)
    }

    /** There is no pet aisle, so pet food is shopped with the household goods. */
    private fun aisleOf(type: ProductType): Category? = when (type) {
        ProductType.BEAUTY -> Category.PERSONAL_CARE
        ProductType.PRODUCT, ProductType.PET_FOOD -> Category.HOUSEHOLD
        ProductType.FOOD -> null
    }

    override suspend fun mergePreview(offer: ScanNameOffer, name: String): ScanMerge? = items.transaction {
        val active = items.items()
        val source = active.firstOrNull { it.id == offer.id && !it.checked } ?: return@transaction null
        offer.version?.let { pinned ->
            if (source.version?.fields?.get("name") != pinned.fields["name"] || source.version?.lifecycle != pinned.lifecycle) return@transaction null
        }
        val aliases = history.snapshot().aliases.associate { it.fromKey to it.toKey }
        val key = ItemIdentity.resolve(Normalizer.normalize(name), aliases)
        val destination = active.firstOrNull { it.id != source.id && !it.checked && ItemIdentity.key(it, aliases) == key }
            ?: return@transaction null
        ScanMerge(source, destination)
    }
    override suspend fun merge(review: ScanMerge): Boolean = items.merge(requireNotNull(review.source.version), requireNotNull(review.destination.version))
    override suspend fun confirm(offer: ScanNameOffer, name: String): Boolean {
        val item = items.items().firstOrNull { it.id == offer.id } ?: return false
        val presentation = smart.present(listOf(item), history.snapshot())[item.id]
        val pinned = offer.version?.facts?.let { org.json.JSONObject(it) }
        val quantity = if (pinned == null) item.quantity else if (pinned.isNull("quantity")) null else
            Quantity(pinned.getString("quantity").toDouble(), MeasureUnit.valueOf(pinned.getString("unit")))
        val store = if (pinned == null) item.store else if (pinned.isNull("store")) null else pinned.getString("store")
        val note = if (pinned == null) item.note else if (pinned.isNull("note")) null else pinned.getString("note")
        return smart.edit(ItemEdit(item.id, name, quantity, presentation?.override ?: offer.category,
            presentation?.repeatEveryDays, store, offer.version, changeRepeat = false, note = note))
    }
}
