@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.scan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.ui.QualityAttribution
import io.theficos.usuals.ui.list.categoryLabel

@Composable
fun ScanNameSheet(offer: ScanNameOffer, remaining: Int, busy: Boolean, onConfirm: (String) -> Unit, onSkip: () -> Unit, onSkipAll: () -> Unit,
    onEnableLookup: (() -> Unit)? = null, onRetry: (() -> Unit)? = null) {
    var name by rememberSaveable(offer.id.value, offer.name) { mutableStateOf(offer.name.orEmpty()) }
    val scroll = key(offer.id, offer.name, offer.problem) { rememberScrollState() }
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = { if (!busy) onSkip() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (offer.name == null) R.string.scan_name_manual else R.string.scan_name_offer), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.scan_name_barcode, offer.gtin.value))
            offer.problem?.let { problem ->
                Text(stringResource(when (problem) {
                    ScanNameProblem.LOOKUP_DISABLED -> R.string.scan_name_disabled
                    ScanNameProblem.NOT_FOUND -> R.string.scan_name_not_found
                    ScanNameProblem.UNAVAILABLE -> R.string.scan_name_unavailable
                    ScanNameProblem.RESTRICTED -> R.string.scan_name_restricted
                }, stringResource(R.string.app_name)))
                if (problem == ScanNameProblem.LOOKUP_DISABLED && onEnableLookup != null) {
                    Button(onClick = onEnableLookup, enabled = !busy) { Text(stringResource(R.string.scan_name_enable)) }
                }
                if (problem == ScanNameProblem.UNAVAILABLE && onRetry != null) {
                    TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.scan_name_retry)) }
                }
            }
            OutlinedTextField(name, { name = it.take(160) }, label = { Text(stringResource(R.string.edit_name)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            offer.category?.let { Text(stringResource(R.string.scan_name_category, categoryLabel(it))) }
            offer.quality?.let { quality ->
                quality.brands?.let { Text(stringResource(R.string.product_brand, it)) }
                quality.packageSize?.let { Text(stringResource(R.string.product_pack, it)) }
            }
            if (offer.fromOff) TextButton(onClick = { uri.openUri("https://world.openfoodfacts.org/product/${offer.gtin.value}") }) { QualityAttribution() }
            Row {
                Button(onClick = { onConfirm(name.trim()) }, enabled = name.any(Char::isLetter) && !busy) { Text(stringResource(R.string.scan_name_confirm)) }
                TextButton(onClick = onSkip, enabled = !busy) { Text(stringResource(R.string.scan_name_skip)) }
            }
            if (remaining > 1) TextButton(onClick = onSkipAll, enabled = !busy) { Text(stringResource(R.string.scan_name_skip_all)) }
        }
    }
}
