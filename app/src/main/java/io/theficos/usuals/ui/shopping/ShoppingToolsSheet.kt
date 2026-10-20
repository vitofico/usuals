@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.shopping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.shopping_tools), style = MaterialTheme.typography.headlineSmall)
            if (preferences.unavailable) {
                Text(stringResource(R.string.shopping_preferences_unavailable))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.shopping_close)) }
                return@Column
            }
            Text(stringResource(R.string.shopping_personal_help), style = MaterialTheme.typography.bodySmall)
            Choice(R.string.shopping_mode, preferences.mode) { enabled -> update { it.mode(enabled) } }
            Choice(R.string.shopping_keep_awake, preferences.keepAwake) { enabled -> update { it.keepAwake(enabled) } }
            Choice(R.string.shopping_haptics, preferences.haptics) { enabled -> update { it.haptics(enabled) } }
            onReminder?.let { TextButton(onClick = it) { Text(stringResource(R.string.reminder_title)) } }
            HorizontalDivider()
            Text(stringResource(R.string.shopping_favorites), style = MaterialTheme.typography.titleMedium)
            if (preferences.portable.favorites.isEmpty()) Text(stringResource(R.string.shopping_no_favorites))
            preferences.portable.favorites.forEach { favorite ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(favorite.name)
                        favorite.quantity?.let { Text(io.theficos.usuals.ui.list.quantityText(it), style = MaterialTheme.typography.bodySmall) }
                        favorite.store?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { onAdd(favorite) }) { Text(stringResource(R.string.capture_add)) }
                    TextButton(onClick = { update { it.unpin(favorite.id) } }) { Text(stringResource(R.string.shopping_unpin)) }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.shopping_aisle_order), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.shopping_aisle_for, store ?: stringResource(R.string.store_all)), style = MaterialTheme.typography.bodySmall)
            val order = preferences.portable.categoryOrder(store)
            order.forEachIndexed { index, category ->
                val label = categoryLabel(category)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    IconButton(enabled = index > 0, onClick = { update { it.order(store.orEmpty(), moved(order, index, index - 1)) } }) {
                        Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.shopping_up, label))
                    }
                    IconButton(enabled = index < order.lastIndex, onClick = { update { it.order(store.orEmpty(), moved(order, index, index + 1)) } }) {
                        Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.shopping_down, label))
                    }
                }
            }
            TextButton(onClick = { update { it.order(store.orEmpty(), Category.entries.toList()) } }) { Text(stringResource(R.string.shopping_reset_order)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shopping_close)) }
        }
    }
}

private fun moved(order: List<Category>, from: Int, to: Int) = order.toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun Choice(label: Int, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = enabled, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
}
