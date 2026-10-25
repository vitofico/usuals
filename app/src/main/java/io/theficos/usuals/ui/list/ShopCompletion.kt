package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShopCompletion(state: ListUiState, actions: ListActions) {
    val finished = state.finishedTrip ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.finish_shop_saved, finished.count, finished.count), Modifier.weight(1f))
                IconButton(onClick = actions.onDismissFinished) { Icon(Icons.Outlined.Close, stringResource(R.string.history_dismiss)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.onUndoFinish, enabled = !state.busy) { Text(stringResource(R.string.finish_shop_undo)) }
                TextButton(onClick = { actions.onOpenReceipt(finished.trip.id) }, enabled = !state.busy) { Text(stringResource(R.string.receipt_add)) }
                if (state.finishedInShoppingMode) TextButton(onClick = actions.onOpenWallet, enabled = !state.busy) { Text(stringResource(R.string.wallet_open)) }
            }
        }
    }
}
