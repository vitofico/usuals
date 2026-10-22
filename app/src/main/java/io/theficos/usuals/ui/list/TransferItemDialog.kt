package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R

internal fun transferTargets(directory: ListDirectory, source: String): List<NamedList> =
    if (directory.selected != source || directory.lists.none { it.id == source && !it.archived }) emptyList()
    else directory.lists.filter { it.id != source && !it.archived }

@Composable
internal fun TransferItemDialog(row: ListRow, source: NamedList, directory: ListDirectory, busy: Boolean,
    onConfirm: (destination: String, move: Boolean) -> Unit, onDismiss: () -> Unit) {
    val targets = transferTargets(directory, source.id)
    var destination by rememberSaveable(source.id, row.id.value) { mutableStateOf(targets.firstOrNull()?.id) }
    var move by rememberSaveable(source.id, row.id.value) { mutableStateOf(true) }
    val selected = targets.firstOrNull { it.id == destination }
    val sourceName = source.name.ifBlank { stringResource(R.string.transfer_unnamed_list) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.transfer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.transfer_from, row.rawName, sourceName))
                Text(stringResource(R.string.transfer_destination), style = MaterialTheme.typography.labelLarge)
                if (targets.isEmpty()) Text(stringResource(R.string.transfer_no_targets))
                targets.forEach { target ->
                    TransferChoice(target.name.ifBlank { stringResource(R.string.transfer_unnamed_list) }, target.id == destination, !busy) { destination = target.id }
                }
                TransferChoice(stringResource(R.string.transfer_move), move, !busy) { move = true }
                TransferChoice(stringResource(R.string.transfer_copy), !move, !busy) { move = false }
                Text(stringResource(if (move) R.string.transfer_move_hint else R.string.transfer_copy_hint))
                Text(stringResource(R.string.transfer_details), style = MaterialTheme.typography.bodySmall)
                selected?.let {
                    Text(stringResource(if (move) R.string.transfer_move_question else R.string.transfer_copy_question,
                        row.rawName, sourceName, it.name.ifBlank { stringResource(R.string.transfer_unnamed_list) }))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected != null, onClick = { selected?.let { onConfirm(it.id, move) } }) {
                Text(stringResource(if (move) R.string.transfer_move else R.string.transfer_copy))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}

@Composable
private fun TransferChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
