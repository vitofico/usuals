@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import io.theficos.usuals.enrich.ProductType
import io.theficos.usuals.ui.QualityAttribution
import io.theficos.usuals.ui.ScoreStrip
import io.theficos.usuals.ui.list.categoryLabel
import io.theficos.usuals.ui.components.DisclosureSection

@Composable
fun ScanNameSheet(offer: ScanNameOffer, remaining: Int, busy: Boolean, onConfirm: (String) -> Unit, onSkip: () -> Unit, onSkipAll: () -> Unit,
    onEnableLookup: (() -> Unit)? = null, onRetry: (() -> Unit)? = null) {
    var name by rememberSaveable(offer.gtin.value, offer.name) { mutableStateOf(offer.name.orEmpty()) }
    val scroll = key(offer.gtin, offer.name, offer.problem) { rememberScrollState() }
    val uri = LocalUriHandler.current
    var lookupExpanded by rememberSaveable(offer.gtin.value) { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(offer.gtin.value) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onSkip() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
          Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (offer.name == null) R.string.scan_name_manual else R.string.scan_name_offer), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(name, { name = it.take(160) }, label = { Text(stringResource(R.string.edit_name)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            offer.category?.let { Text(stringResource(R.string.scan_name_category, categoryLabel(it)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.scan_name_barcode, offer.gtin.value), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            offer.problem?.let { problem ->
              if (problem == ScanNameProblem.LOOKUP_DISABLED) {
                DisclosureSection(stringResource(R.string.quiet_scan_lookup), lookupExpanded, { lookupExpanded = it }) {
                    Text(stringResource(R.string.scan_name_disabled), style = MaterialTheme.typography.bodyMedium)
                    if (onEnableLookup != null) OutlinedButton(onClick = onEnableLookup, enabled = !busy) {
                        Text(stringResource(R.string.scan_name_enable))
                    }
                }
              } else {
                Text(stringResource(when (problem) {
                    ScanNameProblem.LOOKUP_DISABLED -> R.string.scan_name_disabled
                    ScanNameProblem.NOT_FOUND -> R.string.scan_name_not_found
                    ScanNameProblem.NOT_FOOD -> R.string.product_not_food
                    ScanNameProblem.UNAVAILABLE -> R.string.scan_name_unavailable
                    ScanNameProblem.RESTRICTED -> R.string.scan_name_restricted
                }, stringResource(R.string.app_name)), style = MaterialTheme.typography.bodyMedium)
                if (problem == ScanNameProblem.UNAVAILABLE && onRetry != null) {
                    TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.scan_name_retry)) }
                }
              }
            }
            offer.quality?.let { quality ->
                DisclosureSection(stringResource(R.string.product_details), detailsExpanded, { detailsExpanded = it }) {
                    quality.brands?.let { Text(stringResource(R.string.product_brand, it)) }
                    quality.packageSize?.let { Text(stringResource(R.string.product_pack, it)) }
                    if (quality.productType == ProductType.FOOD) ScoreStrip(quality)
                }
            }
            if (offer.fromOff) TextButton(onClick = { uri.openUri("https://world.openfoodfacts.org/product/${offer.gtin.value}") }) { QualityAttribution() }
            Spacer(Modifier.height(4.dp))
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (remaining > 1) TextButton(onClick = onSkipAll, enabled = !busy) { Text(stringResource(R.string.scan_name_skip_all)) }
                TextButton(onClick = onSkip, enabled = !busy) { Text(stringResource(R.string.scan_name_skip)) }
                Button(onClick = { onConfirm(name.trim()) }, enabled = name.any(Char::isLetter) && !busy) { Text(stringResource(R.string.scan_name_confirm)) }
          }
        }
    }
}
