package io.theficos.comanda.data.local

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

/**
 * One row of the list, on disk.
 *
 * The mirror of `Item` in `:core:model`, with three deliberate differences.
 *
 * **Columns are primitives.** No type converters anywhere in this module: a
 * `Gtin` is stored as its canonical thirteen digits, a `MeasureUnit` as its
 * enum name, an id as its string. Mapping lives in one visible place
 * (`Mappers.kt`) instead of in generated converter code, which means a future
 * migration can be read against the schema JSON without a converter in scope,
 * and a value class in the domain never has to be one in the database.
 *
 * **The quantity is stored flat.** `quantityAmount` and `quantityUnit` are
 * either both set or both null. SQLite has no record type and an embedded
 * nullable object costs a Room `@Embedded` with its own null-check rules, so
 * two columns and one mapper function is the smaller thing.
 *
 * **Deletes are tombstones.** [deletedAt] is the only difference between a row
 * that is gone and a row that is on the list, because a delete has to travel to
 * the other phones in the household. A row is reaped for real a fortnight
 * later, see `ItemRepository.reap`.
 *
 * Two indices, both earning their keep: [normalizedName] is the dedup lookup on
 * every single insert (design 7.1), and [deletedAt] is in the WHERE clause of
 * every read the UI makes.
 */
@Serializable
@Entity(
    tableName = "items",
    primaryKeys = ["listId", "id"],
    indices = [
        Index("listId", "normalizedName"),
        Index("listId", "deletedAt"),
    ],
)
data class ItemEntity(
    val id: String,
    /** Exactly what was captured. Never rewritten, so a better parser can re-read it later. */
    val rawText: String,
    val name: String,
    /** `Normalizer.normalize(name)`. The dedup key and the purchase-history key. */
    val normalizedName: String,
    val quantityAmount: Double? = null,
    /** A `MeasureUnit` name, or null. Null exactly when [quantityAmount] is null. */
    val quantityUnit: String? = null,
    val note: String? = null,
    /** Canonical EAN-13, when the item was scanned. */
    val gtin: String? = null,
    val checked: Boolean = false,
    val checkedAt: Long? = null,
    val fromSuggestion: Boolean = false,
    val store: String? = null,
    /**
     * Fractional sort key, so a drag between two neighbours writes one row
     * rather than renumbering the list.
     */
    val position: Double = 0.0,
    val addedBy: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Set when the row was deleted. Null means it is on the list. */
    val deletedAt: Long? = null,
    val listId: String = LOCAL_LIST_ID,
)
