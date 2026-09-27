package com.stremio.lite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StremioPurple = Color(0xFF7B5BF2)
private val StremioPurpleDark = Color(0xFF5A3FD0)
private val Surface = Color(0xFF14151A)
private val SurfaceVariant = Color(0xFF1E2027)

private val DarkColors = darkColorScheme(
    primary = StremioPurple,
    onPrimary = Color.White,
    secondary = Color(0xFF8A8FA3),
    background = Color(0xFF0D0E12),
    onBackground = Color(0xFFECEDF2),
    surface = Surface,
    onSurface = Color(0xFFECEDF2),
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFFB9BDCC)
)

private val LightColors = lightColorScheme(
    primary = StremioPurpleDark,
    onPrimary = Color.White,
    background = Color(0xFFF7F7FB),
    surface = Color.White
)

@Composable
fun StremioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
