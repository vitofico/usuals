package io.theficos.comanda.enrich

/**
 * Open Food Facts metadata. Null means not stated, never a negative grade.
 * Names and category tags support confirmed scan naming. Nutrition and allergen
 * fields retain source facts for product details; none of them decides shopping identity.
 * Freshness belongs to the cache entry, not to this product record.
 */
data class ProductQuality(
    /** Provider-reported grade, A to E. */
    val nutriScore: NutriScore? = null,
    /** Provider-reported processing group, from 1 to 4. */
    val novaGroup: NovaGroup? = null,
    /** Provider-reported environmental grade, A to E. */
    val ecoScore: EcoScore? = null,
    /** Source tags: null is unstated, empty means no entries, never a safety claim. */
    val allergens: List<String>? = null,
    /** Additive tags (`en:e322`), or null when OFF states nothing. */
    val additives: List<String>? = null,
    val productName: String? = null,
    val categories: List<String>? = null,
    val brands: String? = null,
    val packageSize: String? = null,
    val ingredients: String? = null,
    val traces: List<String>? = null,
    val sourceModifiedAtEpochSeconds: Long? = null,
) {
    /** True when OFF had a record but every facet of it was empty. */
    val isBlank: Boolean
        get() = productName == null && categories == null && nutriScore == null && novaGroup == null && ecoScore == null &&
            allergens == null && additives == null && brands == null && packageSize == null && ingredients == null && traces == null

    companion object {
        /** A record that exists and says nothing. Distinct from having no record. */
        val EMPTY = ProductQuality()
    }
}

/** Nutrition grade, A to E. Missing data is represented by null. */
enum class NutriScore { A, B, C, D, E }

/** NOVA processing group, least to most processed. */
enum class NovaGroup { UNPROCESSED, PROCESSED_CULINARY, PROCESSED, ULTRA_PROCESSED }

/** Provider-reported environmental grade. Missing data is represented by null. */
enum class EcoScore { A, B, C, D, E }
