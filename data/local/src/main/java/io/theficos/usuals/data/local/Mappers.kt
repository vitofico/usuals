package io.theficos.usuals.data.local

import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.MemberId
import io.theficos.usuals.model.Quantity

/**
 * Row to domain, in one place.
 *
 * Everything in this file is total: a column that has gone strange produces a
 * missing field, never an exception. A row written by a future version of the
 * app, or by a sync that has not been written yet, must not be able to crash
 * the list screen, because the list screen is the product.
 */

/** Enum by name. Null when the stored string is not a value this build knows. */
internal inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? =
    if (raw == null) null else enumValues<T>().firstOrNull { it.name == raw }

/**
 * Re-parses the stored barcode instead of trusting the column.
 *
 * The same discipline `GtinSerializer` applies at the relay boundary: there is
 * no path that produces a [Gtin] without a passing check digit, and a column is
 * not more trustworthy than a wire format. A row whose barcode does not
 * validate loses its barcode and falls back to name matching, which is a
 * degraded resolve rather than a broken one.
 */
internal fun ItemEntity.parsedGtin(): Gtin? = gtin?.let { Gtin.parse(it) }

/**
 * The flat quantity columns, back into one value. Both null is the common case
 * and means the person did not say how much, which is not the same as one.
 */
internal fun ItemEntity.parsedQuantity(): Quantity? {
    val amount = quantityAmount ?: return null
    val unit = enumOrNull<MeasureUnit>(quantityUnit) ?: return null
    return Quantity(amount, unit)
}

internal fun ItemEntity.toItem(): Item = Item(
    id = ItemId(id),
    rawText = rawText,
    name = name,
    normalizedName = normalizedName,
    quantity = parsedQuantity(),
    note = note,
    gtin = parsedGtin(),
    checked = checked,
    checkedAt = checkedAt,
    fromSuggestion = fromSuggestion,
    store = store,
    position = position,
    addedBy = addedBy?.let { MemberId(it) },
    createdAt = createdAt,
    updatedAt = updatedAt,
)
