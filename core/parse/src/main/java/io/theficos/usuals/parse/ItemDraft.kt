package io.theficos.usuals.parse

import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.MemberId

/**
 * Turns a parsed item into a row on the list.
 *
 * This is the one place that knows an [Item]'s cache key is
 * `Normalizer.normalize(name)`, which is why it lives here rather than in
 * `:core:model`: the model depends on nothing, and normalization is a rule of
 * the parser.
 *
 * The clock is a parameter, not a call to the wall clock, for two reasons. A
 * test needs a list built at a fixed instant to assert anything about ordering,
 * and both timestamps have to come from one reading: a row created and updated
 * at the same moment must compare equal, not differ by the millisecond that
 * elapsed between two calls.
 */
fun ParsedItem.toItem(
    id: ItemId,
    rawText: String,
    addedBy: MemberId? = null,
    position: Double = 0.0,
    gtin: Gtin? = null,
    clock: () -> Long = System::currentTimeMillis,
): Item {
    val now = clock()
    return Item(
        id = id,
        rawText = rawText,
        name = name,
        normalizedName = Normalizer.normalize(name),
        quantity = quantity,
        note = note,
        gtin = gtin,
        checked = false,
        position = position,
        addedBy = addedBy,
        createdAt = now,
        updatedAt = now,
    )
}
