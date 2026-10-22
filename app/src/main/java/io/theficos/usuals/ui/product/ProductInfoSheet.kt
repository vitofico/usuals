@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.product

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.enrich.*
import io.theficos.usuals.ui.QualityAttribution
import io.theficos.usuals.ui.ScorePanel
import io.theficos.usuals.ui.allergenList
import java.text.DateFormat
import java.util.Date

@Composable
fun ProductInfoSheet(state: ProductInfoState, onRefresh: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val locale = LocalConfiguration.current.locales[0]
    val quality = state.cached?.quality
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.product_details), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(quality?.productName ?: state.title, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.scan_name_barcode, state.gtin.value), style = MaterialTheme.typography.bodySmall)
            quality?.brands?.let { Text(stringResource(R.string.product_brand, it)) }
            quality?.packageSize?.let { Text(stringResource(R.string.product_pack, it)) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.lookupEnabled) {
                Text(stringResource(R.string.product_disabled))
                TextButton(onClick = onSettings) { Text(stringResource(R.string.product_settings)) }
            }
            state.result?.let { result ->
                val message = when (result) {
                    is QualityLookup.Failed -> when (result.reason) {
                        FailureReason.RATE_LIMITED -> R.string.product_rate_limited
                        else -> R.string.product_unavailable
                    }
                    is QualityLookup.Missing -> R.string.product_not_found
                    is QualityLookup.Skipped -> if (result.reason == SkipReason.RESTRICTED_CIRCULATION) R.string.scan_name_restricted else null
                    is QualityLookup.Found -> null
                }
                message?.let { Text(stringResource(it)) }
            }
            if (state.result == null && state.cached?.confirmedMissing == true) Text(stringResource(R.string.product_not_found))
            if (quality != null && quality.productType != ProductType.FOOD) Text(stringResource(R.string.product_not_food))
            if (quality != null && quality.productType == ProductType.FOOD) {
                ScorePanel(quality)
                HorizontalDivider()
                Text(stringResource(R.string.product_ingredients), style = MaterialTheme.typography.titleMedium)
                Text(quality.ingredients ?: stringResource(R.string.product_ingredients_unknown))
                Text(stringResource(when {
                    quality.traces == null -> R.string.product_traces_unknown
                    quality.traces?.isEmpty() == true -> R.string.product_traces_empty
                    else -> R.string.product_traces
                }, allergenList(quality.traces.orEmpty())))
                Text(stringResource(R.string.product_check_package), style = MaterialTheme.typography.bodySmall)
            }
            quality?.sourceModifiedAtEpochSeconds?.let { seconds ->
                Text(stringResource(R.string.product_source_updated,
                    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(seconds * 1_000))),
                    style = MaterialTheme.typography.bodySmall)
            }
            state.cached?.let { cached ->
                Text(stringResource(if (state.stale) R.string.product_saved_stale else R.string.product_saved,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(cached.fetchedAtEpochMillis))),
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !state.loading && state.lookupEnabled) { Text(stringResource(R.string.product_refresh)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.product_close)) }
            }
            TextButton(onClick = { uri.openUri("https://world.openfoodfacts.org/product/${state.gtin.value}") }) {
                Text(stringResource(R.string.product_source))
            }
            QualityAttribution()
        }
    }
}
