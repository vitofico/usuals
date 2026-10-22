package io.theficos.usuals.enrich

/**
 * Open Food Facts metadata. Null means not stated, never a negative grade.
 * Names and category tags support confirmed scan naming. Nutrition and allergen
 * fields retain source facts for product details; none of them decides shopping identity.
 * Freshness belongs to the cache entry, not to this product record.
 */
data class ProductQuality(
    /** Provider-reported grade, A to E. */
    val nutriScore: NutriScore? = null,
    /**
     * True when the source says Nutri-Score does not apply (alcohol, non-food).
     *
     * Kept apart from a null [nutriScore] because the two read differently to a
     * person: "nobody graded this yet" invites a look at the package, "this kind
     * of product has no grade" does not.
     */
    val nutriScoreNotApplicable: Boolean = false,
    /** Provider-reported processing group, from 1 to 4. */
    val novaGroup: NovaGroup? = null,
    /** Provider-reported Green-Score (formerly Eco-Score), A+ to F. */
    val greenScore: GreenScore? = null,
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
    /**
     * Which Open Food Facts database holds the product. Anything but [ProductType.FOOD]
     * means Open Food Facts itself has no food record and named a sister database.
     */
    val productType: ProductType = ProductType.FOOD,
) {
    /** True when OFF had a record but every facet of it was empty. */
    val isBlank: Boolean
        get() = productName == null && categories == null && nutriScore == null && !nutriScoreNotApplicable &&
            novaGroup == null && greenScore == null && allergens == null && additives == null && brands == null &&
            packageSize == null && ingredients == null && traces == null

    companion object {
        /** A record that exists and says nothing. Distinct from having no record. */
        val EMPTY = ProductQuality()
    }
}

/** Nutrition grade, A to E. Missing data is represented by null. */
enum class NutriScore { A, B, C, D, E }

/** NOVA processing group, least to most processed. */
enum class NovaGroup { UNPROCESSED, PROCESSED_CULINARY, PROCESSED, ULTRA_PROCESSED }

/**
 * Provider-reported environmental grade, A+ to F since the 2025 Green-Score
 * revision. Missing data is represented by null. A to E keep their names from
 * the old Eco-Score so stored values stay readable.
 */
enum class GreenScore { A_PLUS, A, B, C, D, E, F }

/**
 * The Open Food Facts database a barcode belongs to. Non-food barcodes live in
 * sister databases run by the same organisation under the same licence.
 */
enum class ProductType { FOOD, BEAUTY, PET_FOOD, PRODUCT }
