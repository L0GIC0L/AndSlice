package io.github.l0gic0l.andslice.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback schemes for Android 9-11, from the Material Theme Builder with an
// orange seed (#FF8A3D). Android 12+ uses the wallpaper's dynamic colors.

private val LightColors = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC0),
    onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF725A47),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC4),
    onSecondaryContainer = Color(0xFF2A1709),
    tertiary = Color(0xFF5B6237),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E7B1),
    onTertiaryContainer = Color(0xFF191E00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A14),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A14),
    surfaceVariant = Color(0xFFF3DFD2),
    onSurfaceVariant = Color(0xFF52443B),
    outline = Color(0xFF85746A),
    outlineVariant = Color(0xFFD7C3B7),
    inverseSurface = Color(0xFF382F28),
    inverseOnSurface = Color(0xFFFFEDE3),
    inversePrimary = Color(0xFFFFB874),
    surfaceDim = Color(0xFFE7D7CE),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1E9),
    surfaceContainer = Color(0xFFFAEBE2),
    surfaceContainerHigh = Color(0xFFF4E6DC),
    surfaceContainerHighest = Color(0xFFEEE0D7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB874),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCC0),
    secondary = Color(0xFFE1C1A9),
    onSecondary = Color(0xFF402C1C),
    secondaryContainer = Color(0xFF594331),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Color(0xFFC4CB97),
    onTertiary = Color(0xFF2D330D),
    tertiaryContainer = Color(0xFF444A22),
    onTertiaryContainer = Color(0xFFE0E7B1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF19120C),
    onBackground = Color(0xFFEFE0D6),
    surface = Color(0xFF19120C),
    onSurface = Color(0xFFEFE0D6),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD7C3B7),
    outline = Color(0xFF9F8D82),
    outlineVariant = Color(0xFF52443B),
    inverseSurface = Color(0xFFEFE0D6),
    inverseOnSurface = Color(0xFF382F28),
    inversePrimary = Color(0xFF8B5000),
    surfaceDim = Color(0xFF19120C),
    surfaceBright = Color(0xFF41372F),
    surfaceContainerLowest = Color(0xFF140D07),
    surfaceContainerLow = Color(0xFF221A14),
    surfaceContainer = Color(0xFF261E18),
    surfaceContainerHigh = Color(0xFF312822),
    surfaceContainerHighest = Color(0xFF3C332C),
)

@Composable
fun AndSliceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
