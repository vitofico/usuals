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
)
