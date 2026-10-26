package io.theficos.usuals.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.model.ListColor
import io.theficos.usuals.ui.theme.*

@Composable
internal fun ListColorDialog(list: NamedList, onDismiss: () -> Unit, onSave: (ListColor?) -> Unit) {
    var chosen by rememberSaveable(list.id) { mutableStateOf(list.color?.id) }
    val color = ListColor.fromId(chosen)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_color_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(list.name.ifBlank { stringResource(R.string.household_fallback_name) },
                style = MaterialTheme.typography.headlineSmall,
                color = listAccent(color, MaterialTheme.colorScheme), modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.selectableGroup()) {
                (listOf<ListColor?>(null) + ListColor.entries).forEach { option ->
                    val selected = chosen == option?.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { chosen = option?.id }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null)
                        Box(Modifier.padding(horizontal = 12.dp).size(20.dp).background(listAccent(option, MaterialTheme.colorScheme), CircleShape))
                        Text(listColorLabel(option))
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { if (color != list.color) onSave(color); onDismiss() }) { Text(stringResource(R.string.edit_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } })
}
