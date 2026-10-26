package io.theficos.usuals.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import java.util.Random
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val AmbientCollageOpacity = 0.22f
internal const val AmbientPigmentStrength = 0.78f

/** One small, repeating alpha tile; pigment only fades, preserving its contrast bounds. */
private val PaperGrain: ImageBitmap by lazy {
    val edge = 192
    val random = Random(0x757375616c73L)
    val coarse = FloatArray(12 * 12) { random.nextFloat() }
    val fine = FloatArray(48 * 48) { random.nextFloat() }
    fun sample(grid: FloatArray, cells: Int, x: Int, y: Int): Float {
        val gx = x.toFloat() * cells / edge
        val gy = y.toFloat() * cells / edge
        val left = floor(gx).toInt(); val top = floor(gy).toInt()
        val dx = gx - left; val dy = gy - top
        fun at(a: Int, b: Int) = grid[(b % cells) * cells + a % cells]
        return (at(left, top) * (1 - dx) + at(left + 1, top) * dx) * (1 - dy) +
            (at(left, top + 1) * (1 - dx) + at(left + 1, top + 1) * dx) * dy
    }
    val pixels = IntArray(edge * edge) { index ->
        val x = index % edge; val y = index / edge
        val grain = sample(coarse, 12, x, y) * 0.6f + sample(fine, 48, x, y) * 0.25f + random.nextFloat() * 0.15f
        val alpha = ((grain * 1.4f).coerceIn(0.25f, 1f) * 255).roundToInt()
        (alpha shl 24) or 0x00FFFFFF
    }
    Bitmap.createBitmap(pixels, edge, edge, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Metadata keeps its tested contrast even where an ink layer passes behind it. */
@Composable
fun Modifier.ambientLabelBacking(): Modifier =
    if (LocalBackgroundStyle.current == BackgroundStyle.ARTWORK) {
        background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp)
    } else this

@Composable
fun BrandCollage(modifier: Modifier = Modifier, softened: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val detail = if (scheme.background.luminance() < 0.5f) 0.45f else 0.16f
    val layers = listOf(
        R.drawable.brand_collage_secondary to lerp(scheme.secondaryContainer, scheme.onSecondaryContainer, detail),
        R.drawable.brand_collage_tertiary to lerp(scheme.tertiaryContainer, scheme.onTertiaryContainer, 0.35f),
        R.drawable.brand_collage_paper to scheme.surface,
        R.drawable.brand_collage_primary to scheme.primary,
    )
    Box(modifier.clipToBounds().graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val grain = ShaderBrush(ImageShader(PaperGrain, TileMode.Repeated, TileMode.Repeated))
        onDrawWithContent {
            drawContent()
            drawRect(grain, blendMode = BlendMode.DstIn)
        }
    }) {
        for ((resource, color) in layers) {
            val pigment = if (softened) lerp(scheme.surface, color, AmbientPigmentStrength) else color
            if (softened) Image(painterResource(resource), null, Modifier.matchParentSize().graphicsLayer {
                scaleX = 1.025f; scaleY = 1.025f; alpha = 0.12f
            }, colorFilter = ColorFilter.tint(pigment), contentScale = ContentScale.Fit)
            Image(painterResource(resource), null, Modifier.matchParentSize(),
                colorFilter = ColorFilter.tint(pigment), contentScale = ContentScale.Fit)
        }
    }
}

/** A static edge crop, underneath the opaque shopping rows and controls. */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier, compact: Boolean = false) {
    if (LocalBackgroundStyle.current != BackgroundStyle.ARTWORK) return
    Box(modifier.fillMaxSize().clipToBounds().graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.Transparent,
            0.82f to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
    }) {
        BrandCollage(Modifier.align(Alignment.TopEnd).offset(x = 100.dp, y = if (compact) (-110).dp else 65.dp)
            .requiredSize(width = 460.dp, height = 320.dp).graphicsLayer {
                rotationZ = -104f; scaleX = if (compact) 1.4f else 2.1f; scaleY = if (compact) 1.4f else 2.1f
                alpha = if (compact) 0.12f else AmbientCollageOpacity
            }, softened = true)
    }
}
