package io.theficos.usuals.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
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
    var syncInfo by rememberSaveable { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    // Kept by id, like the list menu's dialogs: sync can change the lists while it is open.
    var leaving by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val selected = directory.lists.firstOrNull { it.id == directory.selected }
    val title = selected?.let { listTitle(it) } ?: name ?: stringResource(R.string.household_fallback_name)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp)
            .clickable(enabled = !busy && directory.lists.isNotEmpty(), role = Role.Button, onClickLabel = stringResource(R.string.lists_choose)) { open = true }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, color = io.theficos.usuals.ui.theme.listAccent(selected?.color, MaterialTheme.colorScheme),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (directory.lists.isNotEmpty()) Icon(Icons.Outlined.ExpandMore, stringResource(R.string.lists_choose), Modifier.size(24.dp).padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        if (directory.status != null || directory.pending > 0 || directory.conflicts.isNotEmpty()) {
            val attention = directory.status in setOf(SyncStatus.OFFLINE, SyncStatus.SIGN_IN, SyncStatus.UPGRADE, SyncStatus.CAPACITY) || directory.conflicts.isNotEmpty()
            val syncing = directory.pending > 0 && !attention
            val rotation = if (syncing) {
                val transition = rememberInfiniteTransition(label = "sync")
                transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "sync rotation").value
            } else 0f
            val description = stringResource(R.string.sync_details) + ": " + syncStatusText(directory)
            IconButton(onClick = { syncInfo = true }, modifier = Modifier.size(48.dp)) {
                Icon(if (attention) Icons.Outlined.SyncProblem else if (syncing) Icons.Outlined.Sync else Icons.Outlined.Check,
                    description, Modifier.size(24.dp).rotate(rotation),
                    tint = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.size(48.dp))
    }
    if (syncInfo) AlertDialog(onDismissRequest = { syncInfo = false },
        title = { Text(stringResource(R.string.sync_details)) },
        text = { Column {
            Text(syncStatusText(directory))
            if (directory.pending > 0) Text(pluralStringResource(R.plurals.lists_pending, directory.pending, directory.pending))
            if (directory.conflicts.isNotEmpty()) TextButton(onClick = { syncInfo = false; review = true }) {
                Text(pluralStringResource(R.plurals.lists_conflicts, directory.conflicts.size, directory.conflicts.size))
            }
        } },
        confirmButton = { TextButton(onClick = { syncInfo = false }) { Text(stringResource(R.string.history_dismiss)) } },
        dismissButton = { if (directory.status == SyncStatus.SIGN_IN) TextButton(onClick = { syncInfo = false; actions.onOpenAccount() }) {
            Text(stringResource(R.string.account_expired_title))
        } })
    if (review) directory.conflicts.firstOrNull()?.let { conflict ->
        AlertDialog(onDismissRequest = { review = false }, title = { Text(stringResource(R.string.lists_review_title)) },
            text = { ConflictDetails(conflict) }, confirmButton = { TextButton(enabled = conflict.itemId != null, onClick = { review = false; actions.onReviewConflict(conflict) }) { Text(stringResource(R.string.lists_review_item)) } },
            dismissButton = { TextButton(onClick = { actions.onDismissConflict(conflict.id); if (directory.conflicts.size <= 1) review = false }) { Text(stringResource(R.string.lists_keep_current)) } })
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
                    Box(Modifier.padding(end = 12.dp).size(12.dp).background(io.theficos.usuals.ui.theme.listAccent(list.color, MaterialTheme.colorScheme), androidx.compose.foundation.shape.CircleShape))
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

@Composable
private fun syncStatusText(directory: ListDirectory): String = stringResource(when (directory.status) {
    SyncStatus.SIGN_IN -> R.string.sync_sign_in
    SyncStatus.UPGRADE -> R.string.sync_upgrade
    SyncStatus.CAPACITY -> R.string.sync_capacity
    SyncStatus.OFFLINE -> R.string.sync_offline
    else -> if (directory.pending > 0) R.string.sync_updating else R.string.sync_current
})
