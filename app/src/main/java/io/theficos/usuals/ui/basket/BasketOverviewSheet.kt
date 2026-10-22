@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.basket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.enrich.NutriScore

/** The list's overview sheet. [overview] is null while the saved product details are read. */
data class BasketSheetState(val overview: BasketOverview? = null)

@Composable
fun BasketOverviewSheet(state: BasketSheetState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.basket_title), style = MaterialTheme.typography.headlineSmall)
            Quiet(stringResource(R.string.basket_source))
            val overview = state.overview
            if (overview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                Text(stringResource(R.string.basket_processing_title), style = MaterialTheme.typography.titleMedium)
                Processing(overview, detailed = true)
                HorizontalDivider()
                Text(stringResource(R.string.basket_nutri_title), style = MaterialTheme.typography.titleMedium)
                Grades(overview.nutriScore)
                if (overview.nutriScore.enough) Quiet(stringResource(R.string.basket_nutri_note))
                if (!overview.processing.enough || !overview.nutriScore.enough) Quiet(stringResource(R.string.basket_grow_hint))
                if (overview.notFood > 0) Quiet(pluralStringResource(R.plurals.basket_not_food, overview.notFood, overview.notFood))
                Quiet(stringResource(R.string.quality_attribution))
            }
        }
    }
}

/** The compact form on a past trip: the processing headline or why there is none, and the grade strip when it holds. */
@Composable
fun BasketTripSummary(overview: BasketOverview, modifier: Modifier = Modifier) {
    Column(modifier.testTag("basket_trip"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Processing(overview, detailed = false)
        if (overview.nutriScore.enough) Grades(overview.nutriScore)
    }
}

@Composable
private fun Processing(overview: BasketOverview, detailed: Boolean) {
    val mix = overview.processing
    if (!mix.enough) Text(stringResource(R.string.basket_not_enough))
    else {
        val share = mix.ultraShare
        Text(when (share.kind) {
            UltraShare.Kind.NONE -> stringResource(R.string.basket_ultra_none)
            UltraShare.Kind.UNDER_ONE -> stringResource(R.string.basket_ultra_under_one)
            UltraShare.Kind.ABOUT -> pluralStringResource(R.plurals.basket_ultra_about, share.inTen, share.inTen)
            UltraShare.Kind.OVER_NINE -> stringResource(R.string.basket_ultra_over_nine)
            UltraShare.Kind.ALL -> stringResource(R.string.basket_ultra_all)
        }, style = MaterialTheme.typography.bodyLarge)
        if (detailed) {
            Text(stringResource(R.string.basket_bucket_unprocessed, mix.unprocessed))
            Text(stringResource(R.string.basket_bucket_processed, mix.processed))
            Text(stringResource(R.string.basket_bucket_ultra, mix.ultraProcessed))
        }
    }
    if (mix.eligible > 0) Quiet(stringResource(R.string.basket_food_coverage, mix.known, mix.eligible))
    if (mix.enough && mix.freshProduce > 0) Quiet(stringResource(R.string.basket_fresh_produce))
}

@Composable
private fun Grades(grades: GradeCounts) {
    if (!grades.enough) Text(stringResource(R.string.basket_not_enough))
    else {
        // The legend under the strip carries every count, so the strip itself stays out of the reading order.
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).clearAndSetSemantics {}) {
            for (grade in NutriScore.entries) {
                val count = grades.counts.getValue(grade)
                if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(basketGradeColor(grade)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (grade in NutriScore.entries) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).background(basketGradeColor(grade), CircleShape))
                Text(stringResource(R.string.basket_nutri_grade, grade.name, grades.counts.getValue(grade)), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (grades.eligible > 0) Quiet(stringResource(R.string.basket_nutri_coverage, grades.known, grades.eligible))
}

/**
 * Every grade colour on these surfaces, in one place so it can follow the app's
 * shared grade palette. Only stated grades are ever coloured: missing data is
 * a count in words, never a colour.
 */
@Composable
private fun basketGradeColor(grade: NutriScore): Color =
    lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error, grade.ordinal / (NutriScore.entries.size - 1f))

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
