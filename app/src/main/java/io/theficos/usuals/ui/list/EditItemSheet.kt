@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.ui.components.DisclosureSection
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.smart.Category
import java.text.NumberFormat

@Composable
internal fun EditItemSheet(row: ListRow, busy: Boolean, onSave: (ItemEdit) -> Unit, onDismiss: () -> Unit, onRemove: (ItemId) -> Unit, onPin: (() -> Unit)? = null, onDefer: (() -> Unit)? = null, pinned: Boolean = false, onProductInfo: (() -> Unit)? = null, onTransfer: (() -> Unit)? = null) {
    val context = LocalContext.current
    var name by rememberSaveable(row.id.value) { mutableStateOf(row.rawName) }
    val initialAmount = remember(row.quantity, context.resources.configuration.locales[0]) { row.quantity?.let {
        NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
            maximumFractionDigits = 3
            isGroupingUsed = false
        }.format(it.amount)
    }.orEmpty() }
    var amount by rememberSaveable(row.id.value) { mutableStateOf(initialAmount) }
    var unit by rememberSaveable(row.id.value) { mutableStateOf(row.quantity?.unit ?: MeasureUnit.PIECE) }
    var category by rememberSaveable(row.id.value) { mutableStateOf(row.categoryOverride) }
    var store by rememberSaveable(row.id.value) { mutableStateOf(row.store.orEmpty()) }
    var note by rememberSaveable(row.id.value) { mutableStateOf(row.note.orEmpty()) }
    var repeat by rememberSaveable(row.id.value) { mutableStateOf(row.repeatEveryDays?.toString().orEmpty()) }
    var detailsOpen by rememberSaveable(row.id.value) { mutableStateOf(row.note != null || row.store != null || row.categoryOverride != null || row.repeatEveryDays != null) }
    var actionsOpen by rememberSaveable(row.id.value) { mutableStateOf(false) }
    var removing by rememberSaveable(row.id.value) { mutableStateOf(false) }
    var unitsOpen by remember { mutableStateOf(false) }
    var categoriesOpen by remember { mutableStateOf(false) }
    val number = amount.replace(',', '.').toDoubleOrNull()
    val validAmount = amount.isBlank() || (number != null && number.isFinite() && number >= 0)
    val days = repeat.toIntOrNull()
    val validRepeat = repeat.isBlank() || (days != null && days > 0)
    val validName = name.any { it.isLetterOrDigit() }
    val changed = !validAmount || !validRepeat || name.trim() != row.rawName || note.trim().ifBlank { null } != row.note ||
        amount != initialAmount || unit != (row.quantity?.unit ?: MeasureUnit.PIECE) ||
        category != row.categoryOverride || days != row.repeatEveryDays || store.trim().ifBlank { null } != row.store
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.item_edit), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    enabled = !busy, label = { Text(stringResource(R.string.edit_name)) }, isError = !validName,
                    supportingText = { if (!validName) Text(stringResource(R.string.edit_name_invalid)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it }, singleLine = true,
                        enabled = !busy, label = { Text(stringResource(R.string.edit_quantity)) }, isError = !validAmount,
                        supportingText = { if (!validAmount) Text(stringResource(R.string.edit_quantity_invalid)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    Box(Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { unitsOpen = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(unitLabel(unit)) }
                        DropdownMenu(expanded = unitsOpen, onDismissRequest = { unitsOpen = false }) {
                            MeasureUnit.entries.forEach { choice -> DropdownMenuItem(text = { Text(unitLabel(choice)) }, onClick = { unit = choice; unitsOpen = false }) }
                        }
                    }
                }
                DisclosureSection(stringResource(R.string.quiet_item_details), detailsOpen || !validRepeat, { detailsOpen = it || !validRepeat }) {
                    OutlinedTextField(value = note, onValueChange = { note = it.take(ItemEdit.MAX_NOTE) }, enabled = !busy,
                        label = { Text(stringResource(R.string.edit_note)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = store, onValueChange = { store = it.take(80) }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.edit_store)) }, modifier = Modifier.fillMaxWidth())
                    Box {
                        OutlinedButton(onClick = { categoriesOpen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.quiet_item_category, category?.let { categoryLabel(it) } ?: stringResource(R.string.edit_category_auto)))
                        }
                        DropdownMenu(expanded = categoriesOpen, onDismissRequest = { categoriesOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit_category_auto)) }, onClick = { category = null; categoriesOpen = false })
                            Category.entries.forEach { choice -> DropdownMenuItem(text = { Text(categoryLabel(choice)) }, onClick = { category = choice; categoriesOpen = false }) }
                        }
                    }
                    OutlinedTextField(value = repeat, onValueChange = { repeat = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.edit_repeat_days)) }, isError = !validRepeat,
                        supportingText = { Text(stringResource(if (validRepeat) R.string.edit_repeat_hint else R.string.edit_repeat_invalid)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                onPin?.let { TextButton(onClick = it, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (pinned) R.string.shopping_unpin else R.string.shopping_pin))
                } }
                DisclosureSection(stringResource(R.string.quiet_item_actions), actionsOpen, { actionsOpen = it }) {
                    if (!row.checked) onDefer?.let { TextButton(onClick = it, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.shopping_next_time)) } }
                    onProductInfo?.let { TextButton(onClick = it, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.product_details)) } }
                    onTransfer?.let { transfer ->
                        TextButton(onClick = transfer, enabled = !busy && !changed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.transfer_action)) }
                        if (changed) Text(stringResource(R.string.transfer_save_first), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    TextButton(onClick = { removing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.item_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.edit_cancel)) }
                Button(onClick = { onSave(ItemEdit(row.id, name.trim(), number?.let { Quantity(it, unit) }, category, days, store.trim().ifBlank { null }, row.version, category != row.categoryOverride, days != row.repeatEveryDays, note.trim().ifBlank { null })) },
                    enabled = validName && validAmount && validRepeat && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.edit_save)) }
            }
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false },
        title = { Text(stringResource(R.string.quiet_item_remove_title, row.rawName)) },
        text = { Text(stringResource(R.string.quiet_item_remove_help)) },
        confirmButton = { TextButton(onClick = { removing = false; onRemove(row.id) }, enabled = !busy) { Text(stringResource(R.string.item_remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.edit_cancel)) } })
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
