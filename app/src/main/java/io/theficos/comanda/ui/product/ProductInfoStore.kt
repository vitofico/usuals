package io.theficos.comanda.ui.product

import io.theficos.comanda.enrich.CachedQuality
import io.theficos.comanda.enrich.QualityLookup
import io.theficos.comanda.model.Gtin

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
