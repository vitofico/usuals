package io.theficos.comanda.ui.list

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.theficos.comanda.R

@Composable
internal fun SmartAssistanceDialogs(state: ListUiState, actions: ListActions) {
    state.scanMerge?.let { review ->
        AlertDialog(onDismissRequest = { if (!state.busy) actions.onScanMerge(false) },
            title = { Text(stringResource(R.string.merge_title)) },
            text = { Text(stringResource(R.string.scan_merge_question,
                review.source.quantity?.let { quantityText(it) } ?: stringResource(R.string.scan_merge_no_quantity), review.destination.name,
                review.destination.quantity?.let { quantityText(it) } ?: stringResource(R.string.scan_merge_no_quantity))) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { actions.onScanMerge(true) }) { Text(stringResource(R.string.merge_yes)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { actions.onScanMerge(false) }) { Text(stringResource(R.string.sharing_cancel)) } })
    }
    state.mergeReview?.let { review ->
        AlertDialog(onDismissRequest = { if (!state.busy) actions.onMergeChoice(false) },
            title = { Text(stringResource(R.string.merge_title)) },
            text = { Text(stringResource(R.string.merge_question, review.incoming.name, review.usual.name, stringResource(R.string.app_name))) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { actions.onMergeChoice(true) }) { Text(stringResource(R.string.merge_yes)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { actions.onMergeChoice(false) }) { Text(stringResource(R.string.merge_no)) } },
        )
    }
    state.repeatOffer?.let { offer ->
        AlertDialog(onDismissRequest = { if (!state.busy) actions.onRepeatOffer(false) },
            title = { Text(stringResource(R.string.repeat_offer_title)) },
            text = { Text(pluralStringResource(R.plurals.repeat_offer_question, offer.days, offer.name, offer.days)) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { actions.onRepeatOffer(true) }) { Text(stringResource(R.string.repeat_offer_yes)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { actions.onRepeatOffer(false) }) { Text(stringResource(R.string.repeat_offer_no)) } },
        )
    }
}
