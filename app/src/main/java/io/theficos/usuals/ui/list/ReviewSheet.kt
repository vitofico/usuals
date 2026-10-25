@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.list

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.smart.RepeatPlanner

@Composable
internal fun ReviewEntry(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp).testTag("review_entry")) {
        Text(stringResource(R.string.review_title), Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null)
    }
}

@Composable
internal fun ReviewSheet(session: ReviewSession, onClose: () -> Unit, onAdd: () -> Unit,
    onSkip: () -> Unit, onUndo: () -> Unit, onMore: () -> Unit, onMute: () -> Unit) {
    var menuOpen by remember(session.current?.key) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.review_title), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose, modifier = Modifier.testTag("review_close")) {
                    Text(stringResource(R.string.shopping_close))
                }
            }
            val candidate = session.current
            if (candidate != null) {
                Text(stringResource(R.string.review_progress, session.cursor + 1, session.limit),
                    modifier = Modifier.testTag("review_progress"), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(candidate.name, Modifier.weight(1f).testTag("review_candidate"),
                                style = MaterialTheme.typography.headlineMedium)
                            Box {
                                IconButton(onClick = { menuOpen = true }, enabled = !session.busy, modifier = Modifier.testTag("review_options")) {
                                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.memory_suggestion_options, candidate.name))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.memory_dont_suggest)) },
                                        onClick = { menuOpen = false; onMute() })
                                }
                            }
                        }
                        val days = ((session.startedAt - candidate.lastBoughtAt) / RepeatPlanner.DAY_MILLIS).toInt().coerceAtLeast(1)
                        Text(pluralStringResource(R.plurals.review_bought_ago, days, days),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(pluralStringResource(R.plurals.review_frequency, candidate.windowSize, candidate.shopCount, candidate.windowSize),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val skipLabel = stringResource(R.string.suggestion_action, stringResource(R.string.review_skip), candidate.name)
                val addLabel = stringResource(R.string.autocomplete_add, candidate.name)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSkip, enabled = !session.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("review_skip")
                            .semantics { contentDescription = skipLabel }) {
                        Text(stringResource(R.string.review_skip))
                    }
                    Button(onClick = onAdd, enabled = !session.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("review_add")
                            .semantics { contentDescription = addLabel }) {
                        Text(stringResource(R.string.capture_add))
                    }
                }
            } else {
                Text(pluralStringResource(R.plurals.review_added, session.added, session.added),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("review_summary"),
                    style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.review_back))
                }
                if (session.hasMore) OutlinedButton(onClick = onMore, enabled = !session.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review_more")) {
                    Text(stringResource(R.string.review_more))
                }
            }
            session.problem?.let { problem ->
                Text(stringResource(if (problem == ReviewProblem.SAVE) R.string.review_failed else R.string.review_undo_changed),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (session.last != null) TextButton(onClick = onUndo, enabled = !session.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review_undo")) {
                Text(stringResource(R.string.review_undo))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
