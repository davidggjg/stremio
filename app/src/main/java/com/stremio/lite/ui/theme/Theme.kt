package com.stremio.lite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/**
 * Soft dark, not pitch black: a blue-grey base that is easy on the eyes for long sessions, with
 * translucent glass surfaces layered on top of it.
 */
object Palette {
    val Base = Color(0xFF12141C)
    val BaseElevated = Color(0xFF181B26)
    val BaseDeep = Color(0xFF0C0E14)
    val Accent = Color(0xFF8B6CFF)
    val AccentSoft = Color(0xFF6D52D8)
    val Success = Color(0xFF3DD68C)
    val Danger = Color(0xFFFF6B6B)
    val TextPrimary = Color(0xFFF2F3F8)
    val TextSecondary = Color(0xFFA7ADC2)
    val TextFaint = Color(0xFF747B91)

    /** Glass fills: a lit top edge fading to a darker bottom gives the pane its thickness. */
    val GlassTop = Color(0x1AFFFFFF)
    val GlassBottom = Color(0x0AFFFFFF)
    val GlassBorderTop = Color(0x40FFFFFF)
    val GlassBorderBottom = Color(0x0DFFFFFF)
    val GlassHighlight = Color(0x26FFFFFF)
}

private val DarkColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.AccentSoft,
    background = Palette.Base,
    onBackground = Palette.TextPrimary,
    surface = Palette.BaseElevated,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.BaseElevated,
    onSurfaceVariant = Palette.TextSecondary,
    error = Palette.Danger,
    outline = Color(0x33FFFFFF)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/**
 * Always dark, and always laid out left to right: the interface is in English, so following a
 * right-to-left system locale mirrored every screen and broke the layout.
 */
@Composable
fun StremioTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = AppTypography,
            content = content
        )
    }
}
