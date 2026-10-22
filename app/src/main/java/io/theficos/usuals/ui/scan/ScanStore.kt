package io.theficos.usuals.ui.scan

import io.theficos.usuals.model.*
import io.theficos.usuals.smart.Category

enum class ScanNameProblem { LOOKUP_DISABLED, NOT_FOUND, NOT_FOOD, UNAVAILABLE, RESTRICTED }

/** A scanned barcode waiting for the person to confirm a name. Nothing is on the list yet. */
data class ScanNameOffer(
    val gtin: Gtin,
    val name: String?,
    val category: Category?,
    val fromOff: Boolean,
    val problem: ScanNameProblem? = null,
    val quality: io.theficos.usuals.enrich.ProductQuality? = null,
)

/** How the latest confirmation ended: [addedName] is null when the person chose not to add it. */
data class ScanResult(val gtin: Gtin, val addedName: String?)

interface ScanStore {
    suspend fun lookup(gtin: Gtin): ScanNameOffer
    /** Adds the confirmed product, or merges it into the open row of the same name. */
    suspend fun add(offer: ScanNameOffer, name: String): ItemId
}
