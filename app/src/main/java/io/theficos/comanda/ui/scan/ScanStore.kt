package io.theficos.comanda.ui.scan

import io.theficos.comanda.model.*
import io.theficos.comanda.smart.Category

enum class ScanNameProblem { LOOKUP_DISABLED, NOT_FOUND, UNAVAILABLE, RESTRICTED }

data class ScanNameOffer(
    val id: ItemId,
    val gtin: Gtin,
    val name: String?,
    val category: Category?,
    val fromOff: Boolean,
    val problem: ScanNameProblem? = null,
    val version: ItemVersion? = null,
    val quality: io.theficos.comanda.enrich.ProductQuality? = null,
)

interface ScanStore {
    suspend fun add(gtin: Gtin): ItemId
    suspend fun lookup(id: ItemId, gtin: Gtin): ScanNameOffer?
    suspend fun mergePreview(offer: ScanNameOffer, name: String): ScanMerge? = null
    suspend fun merge(review: ScanMerge): Boolean = false
    suspend fun confirm(offer: ScanNameOffer, name: String): Boolean
}

data class ScanMerge(val source: Item, val destination: Item)
