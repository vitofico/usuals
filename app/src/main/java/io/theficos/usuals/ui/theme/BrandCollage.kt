package io.theficos.usuals.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.theficos.usuals.R

@Composable
fun BrandCollage(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val detail = if (scheme.background.luminance() < 0.5f) 0.45f else 0.16f
    val layers = listOf(
        R.drawable.brand_collage_secondary to lerp(scheme.secondaryContainer, scheme.onSecondaryContainer, detail),
        R.drawable.brand_collage_tertiary to lerp(scheme.tertiaryContainer, scheme.onTertiaryContainer, 0.35f),
        R.drawable.brand_collage_paper to scheme.surface,
        R.drawable.brand_collage_primary to scheme.primary,
    )
    Box(modifier.clipToBounds()) {
        for ((resource, color) in layers) {
            Image(painterResource(resource), null, Modifier.matchParentSize(),
                colorFilter = ColorFilter.tint(color), contentScale = ContentScale.Fit)
        }
    }
}
