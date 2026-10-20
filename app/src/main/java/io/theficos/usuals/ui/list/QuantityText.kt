package io.theficos.usuals.ui.list

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.theficos.usuals.R
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import java.text.NumberFormat

@Composable
internal fun quantityText(quantity: Quantity): String = formatQuantity(LocalContext.current, quantity)

internal fun formatQuantity(context: Context, quantity: Quantity): String {
    val amount = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        maximumFractionDigits = 3
        isGroupingUsed = false
    }.format(quantity.amount)
    return context.getString(
        when (quantity.unit) {
            MeasureUnit.PIECE -> R.string.item_quantity_pieces
            MeasureUnit.GRAM -> R.string.quantity_gram
            MeasureUnit.KILOGRAM -> R.string.quantity_kilogram
            MeasureUnit.MILLILITRE -> R.string.quantity_millilitre
            MeasureUnit.LITRE -> R.string.quantity_litre
            MeasureUnit.PACK -> R.string.quantity_pack
        },
        amount,
    )
}
