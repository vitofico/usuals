@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.shopping

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.ui.components.DisclosureSection
import io.theficos.usuals.smart.Category
import io.theficos.usuals.ui.list.categoryLabel

@Composable
fun ShoppingToolsSheet(
    preferences: ShoppingPreferences,
    store: String?,
    update: ((ShoppingToolsStore) -> Unit) -> Unit,
    onAdd: (SavedShoppingItem) -> Unit,
    onDismiss: () -> Unit,
    onReminder: (() -> Unit)? = null,
    alreadyAdded: Set<String> = emptySet(),
    onChooseWallet: (() -> Unit)? = null,
) {
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var preferencesOpen by rememberSaveable { mutableStateOf(false) }
    var orderOpen by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.shopping_favorites), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(onClick = { infoOpen = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.quiet_favorites_info)) }
                }
                if (preferences.unavailable) Text(stringResource(R.string.shopping_preferences_unavailable))
                else {
                    Text(stringResource(R.string.quiet_favorites_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (preferences.portable.favorites.isEmpty()) Text(stringResource(R.string.shopping_no_favorites), style = MaterialTheme.typography.bodyMedium)
                    preferences.portable.favorites.forEach { favorite ->
                        FavoriteRow(favorite, favorite.id in alreadyAdded, onAdd) { update { it.unpin(favorite.id) } }
                    }
                    HorizontalDivider()
                    DisclosureSection(stringResource(R.string.quiet_shopping_preferences), preferencesOpen, { preferencesOpen = it }) {
                        Choice(R.string.shopping_mode, preferences.mode) { enabled -> update { it.mode(enabled) } }
                        Choice(R.string.shopping_keep_awake, preferences.keepAwake) { enabled -> update { it.keepAwake(enabled) } }
                        Choice(R.string.shopping_haptics, preferences.haptics) { enabled -> update { it.haptics(enabled) } }
                        onChooseWallet?.let { choose -> TextButton(onClick = choose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.wallet_choose)) } }
                        onReminder?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.reminder_title)) } }
                    }
                    DisclosureSection(stringResource(R.string.shopping_aisle_order), orderOpen, { orderOpen = it }) {
                        Text(stringResource(R.string.shopping_aisle_for, store ?: stringResource(R.string.store_all)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val order = preferences.portable.categoryOrder(store)
                        order.forEachIndexed { index, category ->
                            val label = categoryLabel(category)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                IconButton(enabled = index > 0, onClick = { update { it.order(store.orEmpty(), moved(order, index, index - 1)) } }) {
                                    Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.shopping_up, label))
                                }
                                IconButton(enabled = index < order.lastIndex, onClick = { update { it.order(store.orEmpty(), moved(order, index, index + 1)) } }) {
                                    Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.shopping_down, label))
                                }
                            }
                        }
                        TextButton(onClick = { update { it.order(store.orEmpty(), Category.entries.toList()) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.shopping_reset_order))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.shopping_close))
            }
        }
    }
    if (infoOpen) AlertDialog(onDismissRequest = { infoOpen = false },
        title = { Text(stringResource(R.string.shopping_favorites)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.memory_favorites_help))
            Text(stringResource(R.string.shopping_personal_help))
        } },
        confirmButton = { TextButton(onClick = { infoOpen = false }) { Text(stringResource(R.string.shopping_close)) } })
}

@Composable
private fun FavoriteRow(favorite: SavedShoppingItem, added: Boolean, onAdd: (SavedShoppingItem) -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember(favorite.id) { mutableStateOf(false) }
    val addLabel = stringResource(if (added) R.string.memory_favorite_on_list else R.string.memory_favorite_add, favorite.name)
    val removeLabel = stringResource(R.string.memory_favorite_remove, favorite.name)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(favorite.name, style = MaterialTheme.typography.bodyLarge)
                favorite.quantity?.let { Text(io.theficos.usuals.ui.list.quantityText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                favorite.store?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            OutlinedButton(onClick = { onAdd(favorite) }, enabled = !added,
                modifier = Modifier.widthIn(max = 128.dp).heightIn(min = 48.dp).semantics { contentDescription = addLabel }) {
                Text(stringResource(if (added) R.string.memory_on_list else R.string.capture_add))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.quiet_favorite_options, favorite.name)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.shopping_unpin)) },
                        modifier = Modifier.semantics { contentDescription = removeLabel }, onClick = { menuOpen = false; onRemove() })
                }
            }
        }
    }
}

private fun moved(order: List<Category>, from: Int, to: Int) = order.toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun Choice(label: Int, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = enabled, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled, onCheckedChange = null)
    }
}
