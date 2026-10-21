package io.theficos.usuals.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R

@Composable
internal fun NamedListPicker(name: String?, directory: ListDirectory, busy: Boolean, actions: ListActions) {
    var review by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    // Kept by id, like the list menu's dialogs: sync can change the lists while it is open.
    var leaving by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val selected = directory.lists.firstOrNull { it.id == directory.selected }
    val title = selected?.let { listTitle(it) } ?: name ?: stringResource(R.string.household_fallback_name)
    Row(Modifier.clickable(enabled = !busy && directory.lists.isNotEmpty(), onClickLabel = stringResource(R.string.lists_choose)) { open = true }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground,
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
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.lists_choose)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val (archived, current) = directory.lists.partition { it.archived }
            current.forEach { list ->
                TextButton(onClick = { open = false; actions.onSelectList(list.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(listTitle(list), maxLines = 2)
                        if (list.shared) Text(stringResource(R.string.lists_shared_label),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (list.id == directory.selected) Text(stringResource(R.string.lists_selected))
                }
            }
            TextButton(onClick = { open = false; creating = true; draft = "" }) { Text(stringResource(R.string.lists_new)) }
            if (archived.isNotEmpty()) Text(stringResource(R.string.lists_archived), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
            archived.forEach { list ->
                val ownerOnly = list.editor && list.archivedByOwner
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(listTitle(list), maxLines = 2)
                        if (ownerOnly) Text(stringResource(R.string.lists_archived_by_owner),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ownerOnly) TextButton(onClick = { open = false; leaving = list.id }) { Text(stringResource(R.string.share_leave)) }
                    else TextButton(onClick = { actions.onArchiveList(list.id, false) }) { Text(stringResource(R.string.lists_restore)) }
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.history_dismiss)) } })
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text(stringResource(R.string.lists_new)) },
        text = { OutlinedTextField(draft, { if (it.length <= 100) draft = it }, label = { Text(stringResource(R.string.lists_name)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { creating = false; actions.onCreateList(draft) }) { Text(stringResource(R.string.lists_create)) } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.history_dismiss)) } })
    directory.lists.firstOrNull { it.id == leaving }?.let { list -> RemoveListDialog(list, { leaving = null }, actions) }
}

@Composable
private fun listTitle(list: NamedList): String = list.name.ifBlank {
    stringResource(if (list.imported) R.string.lists_imported_home else R.string.household_fallback_name)
}
