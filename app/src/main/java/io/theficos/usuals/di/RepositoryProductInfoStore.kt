package io.theficos.usuals.di

import io.theficos.usuals.enrich.*
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.ui.product.ProductInfoStore

/** A cache-first reader, sharing the scan client's permits and in-flight requests. */
class RepositoryProductInfoStore(
    private val cache: QualityCache,
    private val enricher: ProductEnricher,
    private val allowed: () -> Boolean,
    private val clock: () -> Long,
) : ProductInfoStore {
    override fun enabled() = allowed()
    override suspend fun cached(gtin: Gtin) = cache.get(gtin)
    override suspend fun refresh(gtin: Gtin): QualityLookup {
        if (!allowed()) return QualityLookup.Skipped(gtin, SkipReason.OPTED_OUT)
        val requestedAt = clock()
        val result = enricher.lookup(gtin)
        if (!allowed()) return QualityLookup.Skipped(gtin, SkipReason.OPTED_OUT)
        cache.put(result, requestedAt)
        return result
    }
}
