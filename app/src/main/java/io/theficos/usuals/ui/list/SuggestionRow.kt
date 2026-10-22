package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.smart.*

@Composable
internal fun SuggestionRow(suggestions: List<Suggestion>, onChoice: (String, SuggestionChoice) -> Unit) {
    if (suggestions.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expansion = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .semantics { stateDescription = expansion }) {
            Text(pluralStringResource(R.plurals.quiet_list_suggestions, suggestions.size, suggestions.size), Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth - 32.dp).coerceIn(0.dp, 360.dp)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions, key = { it.key }) { suggestion ->
                    Surface(modifier = Modifier.width(cardWidth), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(suggestion.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                val addLabel = stringResource(R.string.suggestion_action, stringResource(R.string.suggestion_add), suggestion.name)
                                FilledTonalButton(onClick = { onChoice(suggestion.key, SuggestionChoice.ADD) },
                                    modifier = Modifier.semantics { contentDescription = addLabel }) { Text(stringResource(R.string.suggestion_add)) }
                                SuggestionOptions(suggestion, onChoice)
                            }
                            Text(stringResource(when (suggestion.reason) {
                                SuggestionReason.REPEAT -> R.string.suggestion_repeat
                                SuggestionReason.INTERVAL -> R.string.suggestion_interval
                                SuggestionReason.STAPLE -> R.string.suggestion_staple
                            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionOptions(suggestion: Suggestion, onChoice: (String, SuggestionChoice) -> Unit) {
    var open by remember(suggestion.key) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.memory_suggestion_options, suggestion.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(SuggestionChoice.NOT_NOW, SuggestionChoice.NEVER).forEach { choice ->
                val label = stringResource(if (choice == SuggestionChoice.NEVER) R.string.memory_dont_suggest else R.string.suggestion_not_now)
                val description = if (choice == SuggestionChoice.NEVER) stringResource(R.string.memory_mute_item, suggestion.name)
                    else stringResource(R.string.suggestion_action, label, suggestion.name)
                DropdownMenuItem(text = { Text(label) }, modifier = Modifier.semantics { contentDescription = description },
                    onClick = { open = false; onChoice(suggestion.key, choice) })
            }
        }
    }
}
