package io.theficos.usuals.ui.product

import io.theficos.usuals.enrich.CachedQuality
import io.theficos.usuals.enrich.QualityLookup
import io.theficos.usuals.model.Gtin

interface ProductInfoStore {
    fun enabled(): Boolean
    suspend fun cached(gtin: Gtin): CachedQuality?
    suspend fun refresh(gtin: Gtin): QualityLookup
}

data class ProductInfoState(
    val gtin: Gtin,
    val title: String,
    val cached: CachedQuality? = null,
    val loading: Boolean = true,
    val lookupEnabled: Boolean = false,
    val stale: Boolean = false,
    val result: QualityLookup? = null,
    val provenance: ProductProvenance? = null,
)

/** Where a row's product barcode came from, so the sheet can say when it is the household's memory rather than a scan. */
sealed interface ProductProvenance {
    data object Scanned : ProductProvenance
    /** [lastBoughtAt] is null when the product was scanned and named but not bought yet. */
    data class Remembered(val lastBoughtAt: Long?) : ProductProvenance
}
