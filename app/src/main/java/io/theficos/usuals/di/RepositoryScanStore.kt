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
import io.theficos.usuals.ui.scan.*
import kotlinx.coroutines.CancellationException

class RepositoryScanStore(
    private val items: ItemRepository,
    private val history: HistoryRepository,
    private val enabled: () -> Boolean,
    private val enricher: () -> ProductEnricher,
    private val categories: BarcodeCategories,
) : ScanStore {
    override suspend fun lookup(gtin: Gtin): ScanNameOffer {
        rememberedName(gtin)?.let { return ScanNameOffer(gtin, it, null, false) }
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
        // The database a non-food barcode lives in already says which aisle it
        // belongs to, even when it has no name, and outranks a guess from the name.
        val category = nonFood?.let(::aisleOf) ?: accepted?.productName?.let { categories.category(accepted.categories, it) }
        return ScanNameOffer(gtin, accepted?.productName, category, accepted != null, problem, accepted)
    }

    /**
     * Confirmed aliases survive Finish and removal. Prefer the person's display
     * spelling when an active row or a purchase still carries it.
     */
    private suspend fun rememberedName(gtin: Gtin): String? = items.transaction {
        val facts = history.snapshot()
        val key = ItemIdentity.resolve("gtin:${gtin.value}", facts.aliases.associate { it.fromKey to it.toKey })
        if (key.startsWith("gtin:")) null else {
            items.items().firstOrNull { Normalizer.normalize(it.name) == key }?.name
                ?: facts.purchases.filter { Normalizer.normalize(it.name) == key }.maxByOrNull { it.boughtAt }?.name
                ?: key
        }
    }

    /** There is no pet aisle, so pet food is shopped with the household goods. */
    private fun aisleOf(type: ProductType): Category? = when (type) {
        ProductType.BEAUTY -> Category.PERSONAL_CARE
        ProductType.PRODUCT, ProductType.PET_FOOD -> Category.HOUSEHOLD
        ProductType.FOOD -> null
    }

    override suspend fun add(offer: ScanNameOffer, name: String): ItemId = items.transaction {
        val trimmed = name.trim()
        require(trimmed.any(Char::isLetter))
        val facts = history.snapshot()
        val aliases = facts.aliases.associate { it.fromKey to it.toKey }
        val barcode = "gtin:${offer.gtin.value}"
        val key = ItemIdentity.resolve(Normalizer.normalize(trimmed), aliases)
        // The alias is what names the next scan of this barcode. It can be
        // refused when the list still holds an open code-only row of the same
        // barcode; the product is added under its name all the same.
        if (ItemIdentity.resolve(barcode, aliases) != key) history.saveAlias(ItemAlias(barcode, key))
        val existing = ItemIdentity.preferences(facts.preferences, aliases)[key]
        // A category the person chose for this item outranks the lookup's guess.
        if (offer.category != null && existing?.category == null) {
            history.savePreference((existing ?: ItemPreference(key)).copy(key = key, category = offer.category.name))
        }
        items.add(ParsedItem(trimmed), gtin = offer.gtin)
    }
}
