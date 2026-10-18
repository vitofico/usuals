package io.theficos.comanda.data.local

import io.theficos.comanda.enrich.CachedQuality
import io.theficos.comanda.enrich.EcoScore
import io.theficos.comanda.enrich.NovaGroup
import io.theficos.comanda.enrich.NutriScore
import io.theficos.comanda.enrich.ProductQuality
import io.theficos.comanda.enrich.QualityCache
import io.theficos.comanda.enrich.QualityLookup
import io.theficos.comanda.model.Gtin

/**
 * [QualityCache] over the `product_quality` table: what `InMemoryQualityCache`
 * does, surviving a restart.
 *
 * The rule this class exists to hold is the one [QualityCache.put] states, and
 * it is the same rule the in-memory implementation holds, deliberately spelled
 * the same way. An outcome whose [QualityLookup.cacheable] is false is dropped
 * on the floor. A timeout is a fact about the network and not about the
 * product, and writing it down as an answer turns a thirty second outage into a
 * week of "Open Food Facts has nothing about this", which at the point where it
 * is read looks exactly like the truth. Persisting it makes that worse than it
 * is in memory: an in-memory mistake dies with the process, and a row does not.
 *
 * Nothing here consults the age of an entry. Freshness is `CachingEnricher`'s
 * job, and it is deliberately not storage's: serving stale beats blocking, and
 * a cache that decided for itself when a row had expired would be making that
 * call without knowing whether anybody is waiting.
 *
 * The clock is injected as everywhere else in this codebase, so "now" has one
 * definition per process and a test can move it. The two-argument [put] the
 * interface declares stays alongside the one-argument convenience because a
 * caller that has just made a request knows something this class cannot: when
 * the request went out. Stamping the row with that instant rather than with the
 * moment a slow response finally landed keeps a record from looking fresher
 * than the data inside it.
 */
class RoomQualityCache(
    private val dao: QualityDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : QualityCache {

    override suspend fun get(gtin: Gtin): CachedQuality? = dao.find(gtin.value)?.toCached()

    override suspend fun put(outcome: QualityLookup, atEpochMillis: Long) {
        // The gate is [QualityLookup.cacheable] rather than a check on the
        // type, so that the rule reads the same here, in the in-memory cache,
        // and in the interface that promises it.
        if (!outcome.cacheable) return

        val entry = when (outcome) {
            is QualityLookup.Found -> row(
                gtin = outcome.gtin,
                quality = outcome.quality,
                confirmedMissing = false,
                atEpochMillis = atEpochMillis,
            )

            // A confirmed absence is a row with no quality in it and the flag
            // set. "Never asked" is no row at all, and keeping the two apart is
            // what stops every cold start from re-asking about the products OFF
            // has already said it does not have.
            is QualityLookup.Missing -> row(
                gtin = outcome.gtin,
                quality = null,
                confirmedMissing = true,
                atEpochMillis = atEpochMillis,
            )

            // Unreachable for as long as [QualityLookup.cacheable] means what it
            // says. Spelled out rather than folded into an `else` so that a
            // fifth outcome type breaks this file at compile time instead of
            // quietly slipping past the guard above and being written down.
            is QualityLookup.Skipped, is QualityLookup.Failed -> return
        }

        dao.upsert(entry)
    }

    /** Records [outcome] as learned now. Same rules, including the one about failures. */
    suspend fun put(outcome: QualityLookup) = put(outcome, clock())

    private fun row(
        gtin: Gtin,
        quality: ProductQuality?,
        confirmedMissing: Boolean,
        atEpochMillis: Long,
    ) = QualityEntity(
        gtin = gtin.value,
        productName = quality?.productName,
        categories = TagList.encode(quality?.categories),
        brands = quality?.brands,
        packageSize = quality?.packageSize,
        ingredients = quality?.ingredients,
        traces = TagList.encode(quality?.traces),
        sourceModifiedAtEpochSeconds = quality?.sourceModifiedAtEpochSeconds,
        nutriScore = quality?.nutriScore?.name,
        novaGroup = quality?.novaGroup?.name,
        ecoScore = quality?.ecoScore?.name,
        // TagList, not a join: a null list has to come back null and an empty
        // list has to come back empty. See `QualityEntity.allergens`.
        allergens = TagList.encode(quality?.allergens),
        additives = TagList.encode(quality?.additives),
        confirmedMissing = confirmedMissing,
        fetchedAt = atEpochMillis,
    )
}

/**
 * Row to entry, total in the same way `Mappers.kt` is total: a column that has
 * gone strange produces missing knowledge, never an exception.
 *
 * Two decisions worth reading.
 *
 * **The barcode is re-parsed rather than trusted.** A stored string is not more
 * trustworthy than a wire format, and there is no path in this codebase that
 * produces a `Gtin` without a passing check digit. A row whose key no longer
 * parses reads as no row at all, which costs one request and gets the truth.
 *
 * **[QualityEntity.confirmedMissing] wins over the facet columns.** This class
 * cannot write a row that sets both, but a future build or an interrupted write
 * could, and `CachingEnricher` resolves the same conflict the same way when it
 * turns an entry back into an outcome. One precedence, written down twice,
 * beats two implementations quietly disagreeing about what a strange row means.
 *
 * When the flag is false the quality is always non-null, even when every facet
 * in it is. That is the whole point of the distinction: a record that says
 * nothing is `ProductQuality.EMPTY`, and it is a different answer from an
 * absence.
 */
internal fun QualityEntity.toCached(): CachedQuality? {
    val barcode = Gtin.parse(gtin) ?: return null
    return CachedQuality(
        gtin = barcode,
        quality = if (confirmedMissing) null else stated(),
        confirmedMissing = confirmedMissing,
        fetchedAtEpochMillis = fetchedAt,
    )
}

/**
 * The facet columns as the record they stand for.
 *
 * An enum name this build does not recognise reads as null, the same way
 * `Mappers.kt` reads an unknown unit: a grade written by a later version of the
 * app is data this build cannot interpret, and "not stated" is the only honest
 * reading of it. Inventing a nearby grade instead would be exactly the
 * inference this feature is not allowed to make.
 */
private fun QualityEntity.stated(): ProductQuality = ProductQuality(
    productName = productName,
    categories = TagList.decode(categories),
    brands = brands,
    packageSize = packageSize,
    ingredients = ingredients,
    traces = TagList.decode(traces),
    sourceModifiedAtEpochSeconds = sourceModifiedAtEpochSeconds,
    nutriScore = enumOrNull<NutriScore>(nutriScore),
    novaGroup = enumOrNull<NovaGroup>(novaGroup),
    ecoScore = enumOrNull<EcoScore>(ecoScore),
    allergens = TagList.decode(allergens),
    additives = TagList.decode(additives),
)
