package io.theficos.usuals.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R

@Composable
internal fun NamedListPicker(name: String?, directory: ListDirectory, busy: Boolean, actions: ListActions, compact: Boolean = false) {
    var review by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    // Kept by id, like the list menu's dialogs: sync can change the lists while it is open.
    var leaving by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val selected = directory.lists.firstOrNull { it.id == directory.selected }
    val title = selected?.let { listTitle(it) } ?: name ?: stringResource(R.string.household_fallback_name)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(enabled = !busy && directory.lists.isNotEmpty(), role = Role.Button, onClickLabel = stringResource(R.string.lists_choose)) { open = true }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (directory.lists.isNotEmpty()) Icon(Icons.Outlined.ExpandMore, stringResource(R.string.lists_choose), Modifier.size(24.dp).padding(start = 4.dp))
    }
    if (directory.pending > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.lists_pending, directory.pending, directory.pending),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (directory.conflicts.isNotEmpty()) TextButton(onClick = { review = true }, contentPadding = PaddingValues(0.dp)) {
        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.lists_conflicts, directory.conflicts.size, directory.conflicts.size))
    }
    if (review) directory.conflicts.firstOrNull()?.let { conflict ->
        AlertDialog(onDismissRequest = { review = false }, title = { Text(stringResource(R.string.lists_review_title)) },
            text = { ConflictDetails(conflict) }, confirmButton = { TextButton(enabled = conflict.itemId != null, onClick = { review = false; actions.onReviewConflict(conflict) }) { Text(stringResource(R.string.lists_review_item)) } },
            dismissButton = { TextButton(onClick = { actions.onDismissConflict(conflict.id); if (directory.conflicts.size <= 1) review = false }) { Text(stringResource(R.string.lists_keep_current)) } })
    }
    when (directory.status) {
        null, SyncStatus.SYNCED -> Unit
        SyncStatus.SIGN_IN -> TextButton(onClick = actions.onOpenAccount, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.sync_sign_in)) }
        else -> Text(stringResource(when (directory.status) {
            SyncStatus.UPGRADE -> R.string.sync_upgrade
            SyncStatus.CAPACITY -> R.string.sync_capacity
            else -> R.string.sync_offline
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Keeping one dialog window avoids a focus handoff when entering a new list's name.
    if (open) AlertDialog(onDismissRequest = { open = false; creating = false },
        title = { Text(stringResource(if (creating) R.string.lists_new else R.string.lists_choose)) },
        text = { if (creating) {
            OutlinedTextField(draft, { if (it.length <= 100) draft = it }, label = { Text(stringResource(R.string.lists_name)) }, singleLine = true)
        } else Column(Modifier.verticalScroll(rememberScrollState())) {
            val (archived, current) = directory.lists.partition { it.archived }
            current.forEach { list ->
                TextButton(onClick = { open = false; actions.onSelectList(list.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(listTitle(list))
                        if (list.shared) Text(stringResource(R.string.lists_shared_label),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (list.id == directory.selected) Icon(Icons.Outlined.Check, stringResource(R.string.lists_selected), Modifier.size(24.dp))
                }
            }
            if (archived.isNotEmpty()) {
                val expansion = stringResource(if (archivedOpen) R.string.settings_expanded else R.string.settings_collapsed)
                TextButton(onClick = { archivedOpen = !archivedOpen }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansion }) {
                    Text(pluralStringResource(R.plurals.quiet_list_archived, archived.size, archived.size), Modifier.weight(1f))
                    Icon(if (archivedOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (archivedOpen) archived.forEach { list ->
                val ownerOnly = list.editor && list.archivedByOwner
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(listTitle(list))
                        if (ownerOnly) Text(stringResource(R.string.lists_archived_by_owner),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ownerOnly) TextButton(onClick = { open = false; leaving = list.id }) { Text(stringResource(R.string.share_leave)) }
                    else TextButton(onClick = { actions.onArchiveList(list.id, false) }) { Text(stringResource(R.string.lists_restore)) }
                }
            }
        } },
        confirmButton = { TextButton(enabled = !creating || draft.isNotBlank(), onClick = {
            if (creating) { open = false; creating = false; actions.onCreateList(draft) }
            else { creating = true; draft = "" }
        }) { Text(stringResource(if (creating) R.string.lists_create else R.string.lists_new)) } },
        dismissButton = { TextButton(onClick = { if (creating) creating = false else open = false }) {
            Text(stringResource(if (creating) R.string.edit_cancel else R.string.history_dismiss))
        } })
    directory.lists.firstOrNull { it.id == leaving }?.let { list -> RemoveListDialog(list, { leaving = null }, actions) }
}

@Composable
private fun listTitle(list: NamedList): String = list.name.ifBlank {
    stringResource(if (list.imported) R.string.lists_imported_home else R.string.household_fallback_name)
}
