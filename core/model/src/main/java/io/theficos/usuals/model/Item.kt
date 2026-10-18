package io.theficos.usuals.model

import kotlinx.serialization.Serializable

/**
 * One line on the list. The list is the product, so this is the type the whole
 * app is arranged around.
 *
 * Three name fields, on purpose, because they answer three different questions:
 *
 * - [rawText] is exactly what was captured, speech recogniser warts included.
 *   It is never rewritten, so a bad parse can always be re-read later by a
 *   better parser, and a person can see what they actually said.
 * - [name] is what the row shows. It is what the parser pulled out of
 *   [rawText], or what the person typed after editing the row.
 * - [normalizedName] is the identity key: what dedup merges on and what
 *   purchase history groups by. `Normalizer.normalize(name)` in `:core:parse`,
 *   stamped here so a lookup never has to normalize the whole list first.
 *
 * Timestamps are epoch milliseconds supplied by the caller's clock, not read
 * from the wall clock in here, so a test can build a list at a fixed instant.
 */
@Serializable
data class Item(
    val id: ItemId,
    val rawText: String,
    val name: String,
    val normalizedName: String,
    val quantity: Quantity? = null,
    val note: String? = null,
    /** Set when the item was scanned. */
    val gtin: Gtin? = null,
    val checked: Boolean = false,
    val checkedAt: Long? = null,
    val fromSuggestion: Boolean = false,
    val store: String? = null,
    /**
     * Sort key, fractional so a drag between two neighbours is one write of one
     * row rather than a renumber of the whole list. Two devices reordering at
     * once converge through the same last-write-wins register as any other
     * field.
     */
    val position: Double = 0.0,
    val addedBy: MemberId? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Ephemeral view token. It is never persisted or included in a shopping archive. */
    @kotlinx.serialization.Transient var version: ItemVersion? = null
}

data class ItemVersion(val list: String, val id: String, val fields: Map<String, String?>, val lifecycle: String?,
    val aliasRevision: String?, val aliases: Map<String, String>, val preferences: Map<String, String?>, val facts: String)
