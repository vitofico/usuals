@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.shopping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.smart.Category
import io.theficos.usuals.ui.list.ListRow
import io.theficos.usuals.ui.list.categoryLabel
import kotlinx.coroutines.launch
import java.util.UUID

/** Drafts are deliberately temporary. A dismissed review never writes shopping rows. */
@Composable
fun OccasionPacksSheet(preferences: ShoppingPreferences, current: List<ListRow>,
    onList: (SavedShoppingItem) -> Boolean, update: suspend ((ShoppingToolsStore) -> Unit) -> Boolean,
    add: suspend (List<SavedShoppingItem>) -> Boolean, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf<OccasionPack?>(null) }
    var review by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var edit by remember { mutableStateOf<SavedShoppingItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    fun open(pack: OccasionPack, use: Boolean, ids: Set<String> = pack.items.map { it.id }.toSet()) {
        draft = pack; review = use; selected = ids; error = false
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (review) R.string.pack_review else R.string.pack_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.pack_local), style = MaterialTheme.typography.bodyMedium)
                val value = draft
                if (preferences.unavailable) Text(stringResource(R.string.shopping_preferences_unavailable))
                else if (value == null) {
                    Button(onClick = { open(OccasionPack(UUID.randomUUID().toString(), ""), false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pack_new)) }
                    OutlinedButton(onClick = {
                        val rows = current.map { SavedShoppingItem(UUID.randomUUID().toString(), it.rawName,
                            it.quantity?.amount, it.quantity?.unit?.name, it.gtin?.value, it.store, it.note, it.categoryOverride?.name) }
                        open(OccasionPack(UUID.randomUUID().toString(), "", rows), false,
                            rows.zip(current).filterNot { it.second.checked }.map { it.first.id }.toSet())
                    }, enabled = current.isNotEmpty() && current.size <= 200, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pack_from_list)) }
                    if (preferences.portable.packs.isEmpty()) Text(stringResource(R.string.pack_empty))
                    preferences.portable.packs.forEach { pack ->
                        val useLabel = stringResource(R.string.pack_use_named, pack.name)
                        val editLabel = stringResource(R.string.pack_edit_named, pack.name)
                        val deleteLabel = stringResource(R.string.pack_delete_named, pack.name)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                                Button(onClick = { open(pack, true) }, enabled = pack.items.isNotEmpty(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = useLabel }) { Text(stringResource(R.string.pack_use)) }
                                TextButton(onClick = { open(pack, false) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel }) { Text(stringResource(R.string.pack_edit)) }
                                TextButton(onClick = { coroutine.launch { busy = true; error = !update { it.deletePack(pack.id) }; busy = false } }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = deleteLabel }) { Text(stringResource(R.string.pack_delete)) }
                            }
                        }
                    }
                } else {
                    if (review) {
                        Text(value.name, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.pack_review_help))
                    } else OutlinedTextField(value.name, { if (it.length <= 100) draft = value.copy(name = it) },
                        label = { Text(stringResource(R.string.pack_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    value.items.forEach { item ->
                        val present = review && onList(item)
                        val editLabel = stringResource(R.string.pack_edit_named, item.name)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row {
                                    Checkbox(item.id in selected && !present, { checked -> selected = if (checked) selected + item.id else selected - item.id }, enabled = !present && !busy, modifier = Modifier.semantics { contentDescription = item.name })
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                        item.quantity?.let { Text(io.theficos.usuals.ui.list.quantityText(it)) }
                                        item.note?.let { Text(it) }; item.store?.let { Text(it) }
                                        item.category?.let { name -> Category.entries.find { it.name == name }?.let { Text(categoryLabel(it)) } }
                                        if (present) Text(stringResource(R.string.memory_on_list))
                                    }
                                }
                                TextButton(onClick = { edit = item }, enabled = !present && !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = editLabel }) { Text(stringResource(if (review) R.string.pack_quantity else R.string.pack_edit_item)) }
                                if (!review) TextButton(onClick = { draft = value.copy(items = value.items.filterNot { it.id == item.id }); selected = selected - item.id }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pack_remove_item)) }
                            }
                        }
                    }
                    if (!review) OutlinedButton(onClick = { edit = SavedShoppingItem(UUID.randomUUID().toString(), "") }, enabled = value.items.size < 200, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pack_add_item)) }
                }
                if (error) Text(stringResource(if (review) R.string.pack_add_failed else R.string.pack_failed), color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                draft?.let { value ->
                    Button(onClick = {
                        val rows = value.items.filter { it.id in selected && (!review || !onList(it)) }
                        if (review) { busy = true; error = false; coroutine.launch { val ok = add(rows); busy = false; if (ok) draft = null else error = true } }
                        else { busy = true; coroutine.launch { val ok = update { it.savePack(value.copy(name = value.name.trim(), items = rows)) }; busy = false; if (ok) draft = null else error = true } }
                    }, enabled = !busy && if (review) value.items.any { it.id in selected && !onList(it) } else value.name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(if (review) R.string.pack_add_selected else R.string.pack_save))
                    }
                }
                TextButton(onClick = { if (draft != null) draft = null else onDismiss() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(if (draft != null) R.string.pack_cancel else R.string.shopping_close)) }
            }
        }
    }
    edit?.let { item -> PackItemEditor(item, quantityOnly = review, onDismiss = { edit = null }) { saved ->
        draft = draft?.let { it.copy(items = if (it.items.any { old -> old.id == saved.id }) it.items.map { old -> if (old.id == saved.id) saved else old } else it.items + saved) }
        selected = selected + saved.id; edit = null
    } }
}

@Composable
private fun PackItemEditor(item: SavedShoppingItem, quantityOnly: Boolean, onDismiss: () -> Unit, save: (SavedShoppingItem) -> Unit) {
    var name by remember(item) { mutableStateOf(item.name) }
    var amount by remember(item) { mutableStateOf(item.amount?.toString().orEmpty()) }
    var unit by remember(item) { mutableStateOf(item.unit ?: MeasureUnit.PIECE.name) }
    var note by remember(item) { mutableStateOf(item.note.orEmpty()) }
    var store by remember(item) { mutableStateOf(item.store.orEmpty()) }
    var category by remember(item) { mutableStateOf(item.category) }
    val valid = name.any { it.isLetterOrDigit() } && (amount.isBlank() || amount.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (quantityOnly) item.name else stringResource(R.string.pack_edit_item)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!quantityOnly) OutlinedTextField(name, { if (it.length <= 500) name = it }, label = { Text(stringResource(R.string.pack_item_name)) })
            OutlinedTextField(amount, { amount = it }, label = { Text(stringResource(R.string.pack_quantity)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = amount.isNotBlank() && !valid)
            var unitsOpen by remember { mutableStateOf(false) }
            TextButton(onClick = { unitsOpen = !unitsOpen }) { Text(stringResource(R.string.pack_unit, io.theficos.usuals.ui.list.quantityText(io.theficos.usuals.model.Quantity(1.0, MeasureUnit.valueOf(unit))))) }
            if (unitsOpen) MeasureUnit.entries.forEach { value -> TextButton(onClick = { unit = value.name; unitsOpen = false }) { Text(io.theficos.usuals.ui.list.quantityText(io.theficos.usuals.model.Quantity(1.0, value))) } }
            if (!quantityOnly) {
                OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text(stringResource(R.string.pack_note)) })
                OutlinedTextField(store, { if (it.length <= 80) store = it }, label = { Text(stringResource(R.string.pack_store)) })
                var categoriesOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { categoriesOpen = !categoriesOpen }) { Text(category?.let { categoryLabel(Category.valueOf(it)) } ?: stringResource(R.string.pack_category_auto)) }
                if (categoriesOpen) {
                    TextButton(onClick = { category = null; categoriesOpen = false }) { Text(stringResource(R.string.pack_category_auto)) }
                    Category.entries.forEach { value -> TextButton(onClick = { category = value.name; categoriesOpen = false }) { Text(categoryLabel(value)) } }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { save(item.copy(name = name.trim(), gtin = item.gtin.takeIf { name.trim() == item.name }, amount = amount.replace(',', '.').toDoubleOrNull(), unit = if (amount.isBlank()) null else unit, note = note.ifBlank { null }, store = store.ifBlank { null }, category = category)) }, enabled = valid) { Text(stringResource(R.string.pack_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pack_cancel)) } })
}
