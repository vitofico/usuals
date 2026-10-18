package io.theficos.comanda.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.theficos.comanda.R
import io.theficos.comanda.smart.Category

@Composable
internal fun categoryLabel(category: Category): String = stringResource(when (category) {
    Category.PRODUCE -> R.string.category_produce
    Category.DAIRY -> R.string.category_dairy
    Category.BAKERY -> R.string.category_bakery
    Category.MEAT -> R.string.category_meat
    Category.FISH -> R.string.category_fish
    Category.FROZEN -> R.string.category_frozen
    Category.PANTRY -> R.string.category_pantry
    Category.DRINKS -> R.string.category_drinks
    Category.SNACKS -> R.string.category_snacks
    Category.HOUSEHOLD -> R.string.category_household
    Category.PERSONAL_CARE -> R.string.category_personal_care
    Category.OTHER -> R.string.category_other
})
