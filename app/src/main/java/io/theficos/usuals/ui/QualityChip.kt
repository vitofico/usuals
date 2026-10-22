package io.theficos.usuals.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.ProductQuality

@Composable
fun QualityChip(quality: ProductQuality?, modifier: Modifier = Modifier) {
    if (quality == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (quality.isBlank) {
            Fact(stringResource(R.string.quality_record_blank))
        } else {
            Fact(quality.nutriScore?.let { stringResource(R.string.quality_nutri_score, it.name) }
                ?: stringResource(if (quality.nutriScoreNotApplicable) R.string.quality_nutri_score_not_applicable
                    else R.string.quality_nutri_score_absent))
            Fact(quality.novaGroup?.let { stringResource(novaLabelOf(it)) }
                ?: stringResource(R.string.quality_nova_absent))
            Fact(allergensSentence(quality.allergens))
        }
    }
}

@Composable
fun QualityAttribution(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.quality_attribution),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    )
}

@Composable
private fun allergensSentence(allergens: List<String>?): String = when {
    allergens == null -> stringResource(R.string.quality_allergens_absent)
    allergens.isEmpty() -> stringResource(R.string.quality_allergens_none_listed)
    else -> stringResource(
        R.string.quality_allergens,
        allergens.joinToString(", ") { plainTag(it) },
    )
}

private fun plainTag(tag: String): String = tag.substringAfterLast(':').trim()

private fun novaLabelOf(group: NovaGroup): Int = when (group) {
    NovaGroup.UNPROCESSED -> R.string.quality_nova_1
    NovaGroup.PROCESSED_CULINARY -> R.string.quality_nova_2
    NovaGroup.PROCESSED -> R.string.quality_nova_3
    NovaGroup.ULTRA_PROCESSED -> R.string.quality_nova_4
}

@Composable
private fun Fact(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
}
