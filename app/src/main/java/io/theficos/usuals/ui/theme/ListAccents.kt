package io.theficos.usuals.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import io.theficos.usuals.R
import io.theficos.usuals.model.ListColor

internal fun listAccent(color: ListColor?, scheme: ColorScheme): Color {
    if (color == null) return scheme.primary
    val dark = scheme.background.luminance() < 0.5f
    val base = when (color) {
        ListColor.PLUM -> if (dark) 0xFFE2BCD4 else 0xFF583D53
        ListColor.SAGE -> if (dark) 0xFFBCD5AA else 0xFF36593F
        ListColor.TEAL -> if (dark) 0xFFA7D6E5 else 0xFF285D70
        ListColor.SLATE -> if (dark) 0xFFBCCAE6 else 0xFF4C5D7A
        ListColor.OCHRE -> if (dark) 0xFFE5C48D else 0xFF765125
        ListColor.ROSE -> if (dark) 0xFFEAB6C7 else 0xFF8C425D
    }
    return lerp(Color(base), scheme.primary, 0.1f)
}

internal fun listAccentFill(color: ListColor?, scheme: ColorScheme): Color = lerp(scheme.surface, listAccent(color, scheme), 0.12f)

@Composable
internal fun listColorLabel(color: ListColor?): String = stringResource(when (color) {
    null -> R.string.list_color_automatic
    ListColor.PLUM -> R.string.list_color_plum
    ListColor.SAGE -> R.string.list_color_sage
    ListColor.TEAL -> R.string.list_color_teal
    ListColor.SLATE -> R.string.list_color_slate
    ListColor.OCHRE -> R.string.list_color_ochre
    ListColor.ROSE -> R.string.list_color_rose
})
