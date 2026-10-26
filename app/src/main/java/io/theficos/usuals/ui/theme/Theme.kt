package io.theficos.usuals.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Paper, primaryContainer = Petal, onPrimaryContainer = Color(0xFF50364B),
    secondary = Secondary, onSecondary = Paper,
    secondaryContainer = Champagne, onSecondaryContainer = OnChampagne,
    tertiary = Color(0xFF4C613E), onTertiary = Paper,
    tertiaryContainer = Sage, onTertiaryContainer = OnSage,
    background = Ground, onBackground = Ink, surface = Paper, onSurface = Ink,
    surfaceVariant = GroundPressed, onSurfaceVariant = Secondary, surfaceTint = Paper,
    surfaceDim = Hairline, surfaceBright = Paper, surfaceContainerLowest = Paper,
    surfaceContainerLow = Paper, surfaceContainer = GroundPressed,
    surfaceContainerHigh = GroundPressed, surfaceContainerHighest = Hairline,
    inverseSurface = Ink, inverseOnSurface = Paper,
    outline = Outline, outlineVariant = Hairline,
    error = Destructive, onError = Paper,
    errorContainer = Color(0xFFFFDDE5), onErrorContainer = Color(0xFF72112D), scrim = Ink,
)

internal val DarkScheme = darkColorScheme(
    primary = AccentDark, onPrimary = GroundDark,
    primaryContainer = PetalDark, onPrimaryContainer = InkDark,
    secondary = SecondaryDark, onSecondary = GroundDark,
    secondaryContainer = ChampagneDark, onSecondaryContainer = OnChampagneDark,
    tertiary = Color(0xFFC4D6B2), onTertiary = GroundDark,
    tertiaryContainer = SageDark, onTertiaryContainer = OnSageDark,
    background = GroundDark, onBackground = InkDark, surface = PaperDark, onSurface = InkDark,
    surfaceVariant = GroundPressedDark, onSurfaceVariant = SecondaryDark, surfaceTint = PaperDark,
    surfaceDim = GroundDark, surfaceBright = GroundPressedDark, surfaceContainerLowest = GroundDark,
    surfaceContainerLow = PaperDark, surfaceContainer = GroundPressedDark,
    surfaceContainerHigh = GroundPressedDark, surfaceContainerHighest = HairlineDark,
    inverseSurface = InkDark, inverseOnSurface = GroundDark,
    outline = OutlineDark, outlineVariant = HairlineDark,
    error = DestructiveDark, onError = GroundDark,
    errorContainer = Color(0xFF5C2236), onErrorContainer = Color(0xFFFFDDE5), scrim = Color.Black,
)

private val UsualsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun UsualsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: AppPalette = AppPalette.PLUM,
    backgroundStyle: BackgroundStyle = BackgroundStyle.ARTWORK,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBackgroundStyle provides backgroundStyle) {
        MaterialTheme(colorScheme = paletteScheme(palette, darkTheme),
            typography = UsualsTypography, shapes = UsualsShapes, content = content)
    }
}
