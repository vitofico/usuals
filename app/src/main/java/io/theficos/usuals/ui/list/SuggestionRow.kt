package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.smart.*

@Composable
internal fun SuggestionRow(suggestions: List<Suggestion>, onChoice: (String, SuggestionChoice) -> Unit) {
    if (suggestions.isEmpty()) return
    Column {
        Text(stringResource(R.string.suggestions_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions, key = { it.key }) { suggestion ->
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(18.dp).widthIn(min = 250.dp, max = 286.dp)) {
                        Text(suggestion.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when (suggestion.reason) {
                            SuggestionReason.REPEAT -> R.string.suggestion_repeat
                            SuggestionReason.INTERVAL -> R.string.suggestion_interval
                            SuggestionReason.STAPLE -> R.string.suggestion_staple
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            SuggestionChoice.entries.forEach { choice ->
                                val label = stringResource(when (choice) {
                                    SuggestionChoice.ADD -> R.string.suggestion_add
                                    SuggestionChoice.NOT_NOW -> R.string.suggestion_not_now
                                    SuggestionChoice.NEVER -> R.string.suggestion_never
                                })
                                val description = stringResource(R.string.suggestion_action, label, suggestion.name)
                                if (choice == SuggestionChoice.ADD) {
                                    FilledTonalButton(onClick = { onChoice(suggestion.key, choice) },
                                        modifier = Modifier.semantics { contentDescription = description }) { Text(label) }
                                } else TextButton(onClick = { onChoice(suggestion.key, choice) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.semantics { contentDescription = description }) { Text(label) }
                            }
                        }
                    }
                }
            }
        }
    }
}
