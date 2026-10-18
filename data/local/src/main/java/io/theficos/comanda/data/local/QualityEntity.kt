package io.theficos.comanda.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local Open Food Facts cache, excluded from shopping-data JSON and future sync.
 * Direct database transfer may carry it to the receiving phone. Attribution is
 * rendered where the app offers data, rather than repeated in every cache row.
 * Null means not stated. Empty allergen tags are not a claim of safety.
 * Primitive columns keep the exported Room schema readable without converters.
 */
@Entity(tableName = "product_quality")
data class QualityEntity(
    /** Validated canonical EAN-13; this cache has no household principal. */
    @PrimaryKey val gtin: String,
    /** A `NutriScore` name, or null when OFF stated none. A null is not an E. */
    val nutriScore: String? = null,
    /** A `NovaGroup` name, or null when OFF stated none. */
    val novaGroup: String? = null,
    /** An `EcoScore` name, or null when OFF stated none. */
    val ecoScore: String? = null,
    /**
     * Allergen tags, encoded by [TagList], or NULL when OFF stated nothing.
     *
     * NULL and the empty string are different facts here, and the difference
     * has somebody's allergy behind it. NULL is "nobody filled this in"; the
     * empty string is "we looked, OFF lists none". Only the second is a claim
     * anybody made, and rendering the first as the second tells a person with a
     * peanut allergy that a product is safe because a volunteer never finished
     * a form.
     */
    val allergens: String? = null,
    /** Additive tags (`en:e322`), encoded by [TagList], or NULL when OFF stated nothing. */
    val additives: String? = null,
    /**
     * True when OFF answered and does not have this product.
     *
     * This is the column that keeps a confirmed absence apart from a barcode
     * nobody has asked about, which is no row at all. Without it every cold
     * start re-asks OFF about the products it has already said it does not
     * have, which is exactly the traffic the rate limit exists to prevent.
     *
     * False therefore means a record exists, even one whose every facet is
     * null. "OFF has a record and it says nothing" and "OFF has no record" are
     * different answers, and this table stores both.
     */
    val confirmedMissing: Boolean = false,
    /**
     * When the lookup that produced this row went out.
     *
     * Named the way this schema names its instants (`correctedAt`,
     * `createdAt`) rather than after `CachedQuality.fetchedAtEpochMillis`. It
     * is a property of the cache entry and never of the product, which is why
     * it lives here and not in `ProductQuality`, where it would make two
     * records with identical nutrition compare unequal.
     */
    val fetchedAt: Long,
    val productName: String? = null,
    val categories: String? = null,
    val brands: String? = null,
    val packageSize: String? = null,
    val ingredients: String? = null,
    val traces: String? = null,
    val sourceModifiedAtEpochSeconds: Long? = null,
)

/**
 * The two tag columns, encoded so that "nobody said" and "there are none"
 * survive the round trip as different values.
 *
 * Joining a list into one column is the small thing here: the alternative is a
 * second table and a join on the decision path, for a handful of slugs that are
 * only ever read all at once. What it costs is an encoding that has to be
 * exact, because the distinction being carried is a safety one. NULL decodes to
 * null, the empty string decodes to the empty list, and neither is reachable
 * from the other by accident.
 *
 * The separator is the ASCII unit separator rather than a comma, for one
 * reason. An OFF tag is a slug like `en:milk` or `en:e322`, so a comma is
 * *probably* safe, and "probably" is not the standard for a column that decides
 * whether somebody is told about an allergen. No tag can contain a control
 * character, so this encoding cannot be broken by a value we have not seen yet.
 * The price is a column that reads slightly less pleasantly in a SQLite shell,
 * paid once.
 */
internal object TagList {

    private const val SEPARATOR: String = ""

    /** Null in, NULL out. An empty list in, the empty string out. */
    fun encode(tags: List<String>?): String? = tags?.joinToString(SEPARATOR)

    /**
     * NULL in, null out. The empty string in, the empty list out.
     *
     * Blank segments are dropped rather than kept, so a stray separator cannot
     * become a nameless allergen in a list somebody is reading to decide what
     * is safe to eat. An empty tag is not a fact about a product.
     */
    fun decode(stored: String?): List<String>? =
        stored?.split(SEPARATOR)?.filter { it.isNotBlank() }
}
