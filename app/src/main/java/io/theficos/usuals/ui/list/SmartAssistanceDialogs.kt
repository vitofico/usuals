package io.theficos.usuals.ui.list

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.theficos.usuals.R

@Composable
internal fun SmartAssistanceDialogs(state: ListUiState, actions: ListActions) {
    state.repeatOffer?.let { offer ->
        AlertDialog(onDismissRequest = { if (!state.busy) actions.onRepeatOffer(false) },
            title = { Text(stringResource(R.string.repeat_offer_title)) },
            text = { Text(pluralStringResource(R.plurals.repeat_offer_question, offer.days, offer.name, offer.days)) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { actions.onRepeatOffer(true) }) { Text(stringResource(R.string.repeat_offer_yes)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { actions.onRepeatOffer(false) }) { Text(stringResource(R.string.repeat_offer_no)) } },
        )
    }
}
