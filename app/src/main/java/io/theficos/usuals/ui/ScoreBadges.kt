package io.theficos.usuals.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.theficos.usuals.R
import io.theficos.usuals.enrich.GreenScore
import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.ui.theme.GradeShade
import java.util.Locale

/**
 * The Nutri-Score letter as a chip, sized for a list row when [compact]. A null
 * [grade] draws the dashed "not stated" chip; callers that would rather show
 * nothing for an unknown grade simply do not call this.
 */
@Composable
fun NutriScoreChip(grade: NutriScore?, modifier: Modifier = Modifier, compact: Boolean = true, notApplicable: Boolean = false) {
    val face = when {
        grade != null -> Face.Graded(grade.name, nutriShade(grade))
        notApplicable -> Face.NotApplicable
        else -> Face.NotStated
    }
    val description = when (face) {
        is Face.Graded -> stringResource(R.string.quality_nutri_score, face.glyph)
        Face.NotApplicable -> stringResource(R.string.quality_nutri_score_not_applicable)
        Face.NotStated -> stringResource(R.string.quality_nutri_score_absent)
    }
    GradeChip(face, description, compact, modifier)
}

/**
 * Nutri-Score, NOVA and Green-Score side by side, then additives and allergens,
 * for the product sheet. Each scale keeps its own unknown state: a gap in the
 * source is shown as a gap, never folded into a grade.
 */
@Composable
fun ScorePanel(quality: ProductQuality, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (quality.isBlank) {
            Text(stringResource(R.string.quality_record_blank), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        ScoreRow(
            chip = { NutriScoreChip(quality.nutriScore, compact = false, notApplicable = quality.nutriScoreNotApplicable) },
            headline = quality.nutriScore?.let { stringResource(R.string.quality_nutri_score, it.name) }
                ?: stringResource(R.string.quality_scale_nutri_score),
            supporting = when {
                quality.nutriScore != null -> stringResource(R.string.quality_nutri_score_meaning)
                quality.nutriScoreNotApplicable -> stringResource(R.string.quality_does_not_apply)
                else -> stringResource(R.string.quality_not_stated)
            },
        )
        ScoreRow(
            chip = { NovaChip(quality.novaGroup, compact = false) },
            headline = quality.novaGroup?.let { stringResource(novaLabelOf(it)) } ?: stringResource(R.string.quality_scale_nova),
            supporting = stringResource(if (quality.novaGroup != null) R.string.quality_nova_meaning else R.string.quality_not_stated),
        )
        ScoreRow(
            chip = { GreenScoreChip(quality.greenScore, compact = false) },
            headline = quality.greenScore?.let { stringResource(R.string.quality_green_score, greenGlyph(it)) }
                ?: stringResource(R.string.quality_scale_green_score),
            supporting = stringResource(if (quality.greenScore != null) R.string.quality_green_score_meaning else R.string.quality_not_stated),
        )
        Text(additivesSentence(quality.additives), style = MaterialTheme.typography.bodyMedium)
        Text(allergensSentence(quality.allergens), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The scan result's one-line summary. Only stated grades appear: the person is
 * holding the pack, and a row of question marks would crowd out the name field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreStrip(quality: ProductQuality, modifier: Modifier = Modifier) {
    val nutri = quality.nutriScore != null || quality.nutriScoreNotApplicable
    if (!nutri && quality.novaGroup == null && quality.greenScore == null) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (nutri) StripEntry(R.string.quality_scale_nutri_score) {
            NutriScoreChip(quality.nutriScore, notApplicable = quality.nutriScoreNotApplicable)
        }
        quality.novaGroup?.let { StripEntry(R.string.quality_scale_nova) { NovaChip(it, compact = true) } }
        quality.greenScore?.let { StripEntry(R.string.quality_scale_green_score) { GreenScoreChip(it, compact = true) } }
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

/** Allergen or trace tags in the reader's language, comma separated. */
@Composable
fun allergenList(tags: List<String>): String = tags.map { tag ->
    ALLERGEN_NAMES[tag.trim().lowercase(Locale.ROOT)]?.let { stringResource(it) } ?: plainTag(tag)
}.joinToString(", ")

/** `en:e322i` reads as E322i, the way packs print it; anything else keeps its tag text. */
internal fun additiveLabel(tag: String): String {
    val id = plainTag(tag)
    val match = E_NUMBER.matchEntire(id.lowercase(Locale.ROOT)) ?: return id
    return "E" + match.groupValues[1] + match.groupValues[2]
}

private sealed interface Face {
    data class Graded(val glyph: String, val shade: GradeShade) : Face
    data object NotStated : Face
    data object NotApplicable : Face
}

@Composable
private fun NovaChip(group: NovaGroup?, compact: Boolean) {
    val face = group?.let { Face.Graded((it.ordinal + 1).toString(), novaShade(it)) } ?: Face.NotStated
    val description = group?.let { stringResource(novaLabelOf(it)) } ?: stringResource(R.string.quality_nova_absent)
    GradeChip(face, description, compact)
}

@Composable
private fun GreenScoreChip(grade: GreenScore?, compact: Boolean) {
    val face = grade?.let { Face.Graded(greenGlyph(it), greenShade(it)) } ?: Face.NotStated
    val description = grade?.let { stringResource(R.string.quality_green_score, greenGlyph(it)) }
        ?: stringResource(R.string.quality_green_score_absent)
    GradeChip(face, description, compact)
}

@Composable
private fun GradeChip(face: Face, description: String, compact: Boolean, modifier: Modifier = Modifier) {
    val side: Dp = if (compact) 20.dp else 28.dp
    val corner = if (compact) 5.dp else 7.dp
    val shape = RoundedCornerShape(corner)
    val colors = MaterialTheme.colorScheme
    val glyphSize = if (compact) 12.sp else 15.sp
    val frame = Modifier
        .heightIn(min = side)
        .widthIn(min = side)
        .clearAndSetSemantics { contentDescription = description }
    val styled = when (face) {
        // The hairline outline keeps light fills (yellow on white) and dark fills
        // (deep red on a dark sheet) visible as a shape; the glyph is what must be read.
        is Face.Graded -> frame.background(face.shade.fill, shape).border(1.dp, colors.outlineVariant, shape)
        Face.NotApplicable -> frame.border(1.dp, colors.outline, shape)
        // Dashed and unfilled, so an unknown grade can never pass for a bad one.
        Face.NotStated -> frame.drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = colors.outline,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(corner.toPx()),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
            )
        }
    }
    Box(modifier.then(styled).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
        when (face) {
            is Face.Graded -> Text(face.glyph, color = face.shade.glyph, fontSize = glyphSize, fontWeight = FontWeight.Bold, maxLines = 1)
            Face.NotStated -> Text("?", color = colors.onSurfaceVariant, fontSize = glyphSize, fontWeight = FontWeight.Bold, maxLines = 1)
            // A bar rather than a letter or a dash character: "does not apply" is not a grade.
            Face.NotApplicable -> Box(Modifier.size(width = side / 2.5f, height = 2.dp).background(colors.onSurfaceVariant))
        }
    }
}

@Composable
private fun ScoreRow(chip: @Composable () -> Unit, headline: String, supporting: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chip()
        Column {
            // The chip already announces this to screen readers.
            Text(headline, style = MaterialTheme.typography.titleSmall, modifier = Modifier.clearAndSetSemantics {})
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StripEntry(scale: Int, chip: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        chip()
        Text(
            stringResource(scale),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun additivesSentence(additives: List<String>?): String = when {
    additives == null -> stringResource(R.string.quality_additives_absent)
    additives.isEmpty() -> stringResource(R.string.quality_additives_none_listed)
    else -> pluralStringResource(R.plurals.quality_additives, additives.size, additives.size,
        additives.joinToString(", ") { additiveLabel(it) })
}

@Composable
private fun allergensSentence(allergens: List<String>?): String = when {
    allergens == null -> stringResource(R.string.quality_allergens_absent)
    allergens.isEmpty() -> stringResource(R.string.quality_allergens_none_listed)
    else -> stringResource(R.string.quality_allergens, allergenList(allergens))
}

private fun plainTag(tag: String): String = tag.substringAfterLast(':').trim()

internal fun nutriShade(grade: NutriScore): GradeShade = when (grade) {
    NutriScore.A -> GradeShade.STEP_1
    NutriScore.B -> GradeShade.STEP_2
    NutriScore.C -> GradeShade.STEP_3
    NutriScore.D -> GradeShade.STEP_4
    NutriScore.E -> GradeShade.STEP_5
}

// Four groups on a six step scale: skipping step 2 keeps NOVA 2, a culinary
// ingredient such as oil or salt, from looking as good as an unprocessed food.
private fun novaShade(group: NovaGroup): GradeShade = when (group) {
    NovaGroup.UNPROCESSED -> GradeShade.STEP_1
    NovaGroup.PROCESSED_CULINARY -> GradeShade.STEP_3
    NovaGroup.PROCESSED -> GradeShade.STEP_4
    NovaGroup.ULTRA_PROCESSED -> GradeShade.STEP_5
}

private fun greenShade(grade: GreenScore): GradeShade = when (grade) {
    GreenScore.A_PLUS, GreenScore.A -> GradeShade.STEP_1
    GreenScore.B -> GradeShade.STEP_2
    GreenScore.C -> GradeShade.STEP_3
    GreenScore.D -> GradeShade.STEP_4
    GreenScore.E -> GradeShade.STEP_5
    GreenScore.F -> GradeShade.STEP_6
}

private fun greenGlyph(grade: GreenScore): String = if (grade == GreenScore.A_PLUS) "A+" else grade.name

private fun novaLabelOf(group: NovaGroup): Int = when (group) {
    NovaGroup.UNPROCESSED -> R.string.quality_nova_1
    NovaGroup.PROCESSED_CULINARY -> R.string.quality_nova_2
    NovaGroup.PROCESSED -> R.string.quality_nova_3
    NovaGroup.ULTRA_PROCESSED -> R.string.quality_nova_4
}

private val E_NUMBER = Regex("e(\\d{3,4})([a-z]*)")

/** The 14 allergens EU Regulation 1169/2011 (Annex II) requires on labels, by Open Food Facts tag. */
private val ALLERGEN_NAMES = mapOf(
    "en:gluten" to R.string.quality_allergen_gluten,
    "en:crustaceans" to R.string.quality_allergen_crustaceans,
    "en:eggs" to R.string.quality_allergen_eggs,
    "en:fish" to R.string.quality_allergen_fish,
    "en:peanuts" to R.string.quality_allergen_peanuts,
    "en:soybeans" to R.string.quality_allergen_soybeans,
    "en:milk" to R.string.quality_allergen_milk,
    "en:nuts" to R.string.quality_allergen_nuts,
    "en:celery" to R.string.quality_allergen_celery,
    "en:mustard" to R.string.quality_allergen_mustard,
    "en:sesame-seeds" to R.string.quality_allergen_sesame_seeds,
    "en:sulphur-dioxide-and-sulphites" to R.string.quality_allergen_sulphites,
    "en:lupin" to R.string.quality_allergen_lupin,
    "en:molluscs" to R.string.quality_allergen_molluscs,
)
