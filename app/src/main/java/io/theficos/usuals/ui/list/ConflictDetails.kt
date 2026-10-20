package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.smart.Category

@Composable
internal fun ConflictDetails(conflict: ListConflict) {
    if (conflict.kind == "alias") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.lists_review_body))
            conflict.incoming.forEach { (from, to) -> Text(stringResource(R.string.lists_alias_requested, from, to.orEmpty())) }
            conflict.current.forEach { (from, to) -> Text(stringResource(R.string.lists_alias_current, from, to.orEmpty())) }
        }
        return
    }
    val labels = mapOf("name" to R.string.edit_name, "quantity" to R.string.edit_quantity,
        "note" to R.string.lists_note, "store" to R.string.lists_store, "checked" to R.string.lists_check,
        "category" to R.string.edit_category, "repeatEveryDays" to R.string.edit_repeat_days)
    val changes = labels.keys.filter { key ->
        key in conflict.incoming &&
            (conflict.incoming[key] != conflict.current[key] || (key == "quantity" && conflict.incoming["unit"] != conflict.current["unit"])) &&
            (conflict.kind != "capture" || (conflict.incoming[key] != null && key != "checked"))
    }.ifEmpty { listOf("name") }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.lists_review_body))
        for (key in changes) {
            Text(stringResource(labels.getValue(key)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.lists_current_value, conflictValue(key, conflict.current)))
            Text(stringResource(R.string.lists_requested_value, conflictValue(key, conflict.incoming)))
        }
    }
}

@Composable
private fun conflictValue(key: String, values: Map<String, String?>): String {
    val value = values[key] ?: return stringResource(R.string.lists_value_unspecified)
    return when (key) {
        "quantity" -> {
            val amount = value.toDoubleOrNull()
            val unit = MeasureUnit.entries.firstOrNull { it.name == values["unit"] }
            if (amount != null && amount.isFinite() && unit != null) formatQuantity(LocalContext.current, Quantity(amount, unit))
            else stringResource(R.string.lists_value_unspecified)
        }
        "checked" -> stringResource(if (value == "true") R.string.lists_value_checked else R.string.lists_value_unchecked)
        "category" -> Category.entries.firstOrNull { it.name == value }?.let { categoryLabel(it) } ?: stringResource(R.string.edit_category_auto)
        else -> value
    }
}
