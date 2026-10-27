package io.github.l0gic0l.andslice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark scheme with PrusaSlicer-like orange accents; the 3D view is dark too.
private val Colors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF2B1300),
    primaryContainer = Color(0xFF5C2D00),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFFFC94D),
    onSecondary = Color(0xFF2A1F00),
    secondaryContainer = Color(0xFF4A3A10),
    onSecondaryContainer = Color(0xFFFFE8A8),
    background = Color(0xFF1C1E24),
    onBackground = Color(0xFFE4E4E8),
    surface = Color(0xFF23262D),
    onSurface = Color(0xFFE4E4E8),
    surfaceVariant = Color(0xFF30343C),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainer = Color(0xFF272A31),
    surfaceContainerHigh = Color(0xFF2E3138),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AndSliceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
