package app.tripledger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.tripledger.domain.ThemeMode

/** Accent colours offered in Settings. */
val AccentColors: List<Pair<String, Int>> = listOf(
    "Teal" to 0xFF00897B.toInt(),
    "Green" to 0xFF43A047.toInt(),
    "Blue" to 0xFF1E88E5.toInt(),
    "Indigo" to 0xFF3949AB.toInt(),
    "Purple" to 0xFF8E24AA.toInt(),
    "Pink" to 0xFFD81B60.toInt(),
    "Red" to 0xFFE53935.toInt(),
    "Orange" to 0xFFF4511E.toInt(),
    "Amber" to 0xFFFFA000.toInt(),
    "Slate" to 0xFF546E7A.toInt(),
)

/** Colours offered for categories. */
val CategoryColors: List<Int> = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E,
    0xFF8D6E63, 0xFF757575, 0xFF546E7A, 0xFF78909C,
).map { it.toInt() }

/** Black or white, whichever reads better on [background]. */
fun contentColorOn(background: Color): Color = if (background.luminance() > 0.55f) Color(0xFF1A1C1E) else Color.White

private fun lightScheme(accent: Color): ColorScheme {
    val onSurface = Color(0xFF1A1C1E)
    return lightColorScheme(
        primary = accent,
        onPrimary = contentColorOn(accent),
        primaryContainer = lerp(accent, Color.White, 0.82f),
        onPrimaryContainer = lerp(accent, Color.Black, 0.62f),
        inversePrimary = lerp(accent, Color.White, 0.55f),
        secondary = lerp(accent, Color(0xFF5B6168), 0.55f),
        onSecondary = Color.White,
        secondaryContainer = lerp(accent, Color.White, 0.86f),
        onSecondaryContainer = lerp(accent, Color.Black, 0.65f),
        tertiary = lerp(accent, Color(0xFF5B6168), 0.3f),
        onTertiary = Color.White,
        tertiaryContainer = lerp(accent, Color.White, 0.88f),
        onTertiaryContainer = lerp(accent, Color.Black, 0.65f),
        background = Color(0xFFF5F6F8),
        onBackground = onSurface,
        surface = Color(0xFFF5F6F8),
        onSurface = onSurface,
        surfaceVariant = Color(0xFFE6E8ED),
        onSurfaceVariant = Color(0xFF5B6168),
        surfaceTint = accent,
        inverseSurface = Color(0xFF2E3134),
        inverseOnSurface = Color(0xFFF0F1F3),
        outline = Color(0xFF8B9199),
        outlineVariant = Color(0xFFD5D8DE),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDFE1E5),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFF0F1F4),
        surfaceContainerHighest = Color(0xFFE9EBEF),
    )
}

private fun darkScheme(accent: Color): ColorScheme {
    val primary = lerp(accent, Color.White, 0.38f)
    val onSurface = Color(0xFFE3E5E8)
    return darkColorScheme(
        primary = primary,
        onPrimary = lerp(accent, Color.Black, 0.7f),
        primaryContainer = lerp(accent, Color(0xFF101214), 0.55f),
        onPrimaryContainer = lerp(accent, Color.White, 0.82f),
        inversePrimary = accent,
        secondary = lerp(primary, Color(0xFFB0B5BC), 0.5f),
        onSecondary = Color(0xFF1A1C1E),
        secondaryContainer = lerp(accent, Color(0xFF101214), 0.68f),
        onSecondaryContainer = lerp(accent, Color.White, 0.85f),
        tertiary = lerp(primary, Color(0xFFB0B5BC), 0.3f),
        onTertiary = Color(0xFF1A1C1E),
        tertiaryContainer = lerp(accent, Color(0xFF101214), 0.7f),
        onTertiaryContainer = lerp(accent, Color.White, 0.85f),
        background = Color(0xFF101214),
        onBackground = onSurface,
        surface = Color(0xFF101214),
        onSurface = onSurface,
        surfaceVariant = Color(0xFF2B2F33),
        onSurfaceVariant = Color(0xFFA9AEB5),
        surfaceTint = primary,
        inverseSurface = Color(0xFFE3E5E8),
        inverseOnSurface = Color(0xFF2E3134),
        outline = Color(0xFF7B8189),
        outlineVariant = Color(0xFF34383D),
        surfaceBright = Color(0xFF34383D),
        surfaceDim = Color(0xFF101214),
        surfaceContainerLowest = Color(0xFF0B0D0E),
        surfaceContainerLow = Color(0xFF1A1D20),
        surfaceContainer = Color(0xFF1A1D20),
        surfaceContainerHigh = Color(0xFF24282C),
        surfaceContainerHighest = Color(0xFF2E3237),
    )
}

@Composable
fun TripLedgerTheme(themeMode: ThemeMode, accent: Int, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = remember(dark, accent) { if (dark) darkScheme(Color(accent)) else lightScheme(Color(accent)) }

    // Keep the status and navigation bar icons readable against our own background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
