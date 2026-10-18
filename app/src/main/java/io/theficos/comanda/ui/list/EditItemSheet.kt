@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.theficos.comanda.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.theficos.comanda.R
import io.theficos.comanda.model.ItemId
import io.theficos.comanda.model.MeasureUnit
import io.theficos.comanda.model.Quantity
import io.theficos.comanda.smart.Category
import java.text.NumberFormat

@Composable
internal fun EditItemSheet(row: ListRow, busy: Boolean, onSave: (ItemEdit) -> Unit, onDismiss: () -> Unit, onRemove: (ItemId) -> Unit, onPin: (() -> Unit)? = null, onDefer: (() -> Unit)? = null, pinned: Boolean = false, onProductInfo: (() -> Unit)? = null) {
    val context = LocalContext.current
    var name by rememberSaveable(row.id.value) { mutableStateOf(row.rawName) }
    var amount by rememberSaveable(row.id.value) { mutableStateOf(row.quantity?.let {
        NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
            maximumFractionDigits = 3
            isGroupingUsed = false
        }.format(it.amount)
    }.orEmpty()) }
    var unit by rememberSaveable(row.id.value) { mutableStateOf(row.quantity?.unit ?: MeasureUnit.PIECE) }
    var category by rememberSaveable(row.id.value) { mutableStateOf(row.categoryOverride) }
    var store by rememberSaveable(row.id.value) { mutableStateOf(row.store.orEmpty()) }
    var repeat by rememberSaveable(row.id.value) { mutableStateOf(row.repeatEveryDays?.toString().orEmpty()) }
    var unitsOpen by remember { mutableStateOf(false) }
    var categoriesOpen by remember { mutableStateOf(false) }
    val number = amount.replace(',', '.').toDoubleOrNull()
    val validAmount = amount.isBlank() || (number != null && number.isFinite() && number >= 0)
    val days = repeat.toIntOrNull()
    val validRepeat = repeat.isBlank() || (days != null && days > 0)
    val validName = name.any { it.isLetterOrDigit() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.item_edit), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onPin?.let { TextButton(onClick = it) { Text(stringResource(if (pinned) R.string.shopping_unpin else R.string.shopping_pin)) } }
                if (!row.checked) onDefer?.let { TextButton(onClick = it) { Text(stringResource(R.string.shopping_next_time)) } }
            }
            onProductInfo?.let { TextButton(onClick = it) { Text(stringResource(R.string.product_details)) } }
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.edit_name)) }, isError = !validName,
                supportingText = { if (!validName) Text(stringResource(R.string.edit_name_invalid)) },
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, singleLine = true,
                    label = { Text(stringResource(R.string.edit_quantity)) }, isError = !validAmount,
                    supportingText = { if (!validAmount) Text(stringResource(R.string.edit_quantity_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { unitsOpen = true }) { Text(unitLabel(unit)) }
                    DropdownMenu(expanded = unitsOpen, onDismissRequest = { unitsOpen = false }) {
                        MeasureUnit.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(unitLabel(choice)) }, onClick = { unit = choice; unitsOpen = false })
                        }
                    }
                }
            }
            Text(stringResource(R.string.edit_category))
            Box {
                OutlinedButton(onClick = { categoriesOpen = true }) {
                    Text(category?.let { categoryLabel(it) } ?: stringResource(R.string.edit_category_auto))
                }
                DropdownMenu(expanded = categoriesOpen, onDismissRequest = { categoriesOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit_category_auto)) }, onClick = { category = null; categoriesOpen = false })
                    Category.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(categoryLabel(choice)) }, onClick = { category = choice; categoriesOpen = false })
                    }
                }
            }
            OutlinedTextField(value = repeat, onValueChange = { repeat = it }, singleLine = true,
                label = { Text(stringResource(R.string.edit_repeat_days)) }, isError = !validRepeat,
                supportingText = { Text(stringResource(if (validRepeat) R.string.edit_repeat_hint else R.string.edit_repeat_invalid)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = store, onValueChange = { store = it.take(80) }, singleLine = true,
                label = { Text(stringResource(R.string.edit_store)) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(ItemEdit(row.id, name.trim(), number?.let { Quantity(it, unit) }, category, days, store.trim().ifBlank { null }, row.version, category != row.categoryOverride, days != row.repeatEveryDays)) },
                    enabled = validName && validAmount && validRepeat && !busy) { Text(stringResource(R.string.edit_save)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) }
                TextButton(onClick = { onRemove(row.id) }, enabled = !busy) {
                    Text(stringResource(R.string.item_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun unitLabel(unit: MeasureUnit) = stringResource(when (unit) {
    MeasureUnit.PIECE -> R.string.unit_pieces
    MeasureUnit.GRAM -> R.string.unit_gram
    MeasureUnit.KILOGRAM -> R.string.unit_kilogram
    MeasureUnit.MILLILITRE -> R.string.unit_millilitre
    MeasureUnit.LITRE -> R.string.unit_litre
    MeasureUnit.PACK -> R.string.unit_pack
})
