package io.theficos.usuals.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.ui.components.SectionCard
import io.theficos.usuals.ui.theme.AppPalette
import io.theficos.usuals.ui.theme.paletteScheme

@Composable
internal fun AppearanceSection(selected: AppPalette, onSelect: (AppPalette) -> Unit) {
    val dark = isSystemInDarkTheme()
    SectionCard {
        Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.appearance_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppPalette.entries.forEach { palette ->
                val (name, description) = when (palette) {
                    AppPalette.PLUM -> R.string.appearance_plum to R.string.appearance_plum_description
                    AppPalette.GROVE -> R.string.appearance_grove to R.string.appearance_grove_description
                    AppPalette.TIDE -> R.string.appearance_tide to R.string.appearance_tide_description
                }
                val scheme = paletteScheme(palette, dark)
                val active = palette == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 76.dp)
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(14.dp))
                        .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(14.dp))
                        .selectable(active, role = Role.RadioButton, onClick = { onSelect(palette) })
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RadioButton(selected = active, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.width(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(scheme.primary, scheme.secondaryContainer, scheme.background).forEach { color ->
                            Box(Modifier.size(12.dp).background(color, CircleShape).border(1.dp, scheme.outline, CircleShape))
                        }
                    }
                }
            }
        }
    }
}
