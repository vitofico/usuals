package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.ui.components.SectionCard

const val CaptureMatchesTag = "capture_matches"

@Composable
internal fun CaptureMatchHeader(state: ListUiState) {
    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(CaptureMatchesTag),
        title = stringResource(R.string.capture_matches_title)) {
        if (state.captureMatches.any { it.matched == null })
            Text(stringResource(R.string.capture_matches_help), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptureMatchRow(review: CaptureMatchReview, busy: Boolean, actions: ListActions) {
    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(review.incoming.name, style = MaterialTheme.typography.titleSmall)
        review.incoming.quantity?.let { Text(quantityText(it), style = MaterialTheme.typography.bodyMedium) }
        review.incoming.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val matched = review.matched
        if (matched != null) {
            Text(stringResource(R.string.capture_matched_automatically, matched.name))
            TextButton(onClick = { actions.onUndoCaptureMatch(review.incoming.id) }, enabled = !busy) {
                Text(stringResource(R.string.capture_match_undo))
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (candidate in review.candidates) FilterChip(
                    selected = review.selectedKey == candidate.item.key,
                    onClick = { actions.onCaptureMatchSelect(review.incoming.id,
                        candidate.item.key.takeUnless { it == review.selectedKey }) },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.capture_match_use, candidate.item.name)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptureMatchActions(state: ListUiState, actions: ListActions) {
    val pending = state.captureMatches.filter { it.matched == null }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (state.matchingError) Text(stringResource(R.string.capture_matches_changed),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending.isNotEmpty()) Button(onClick = actions.onApplyCaptureMatches,
                enabled = !state.matchingBusy && pending.any { it.selectedKey != null }) {
                Text(stringResource(R.string.capture_matches_apply))
            }
            TextButton(onClick = actions.onKeepCaptureWording, enabled = !state.matchingBusy) {
                Text(stringResource(if (pending.isEmpty()) R.string.capture_matches_done else R.string.capture_matches_keep))
            }
        }
    }
}
