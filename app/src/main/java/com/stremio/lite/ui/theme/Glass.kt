package com.stremio.lite.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's glass language.
 *
 * Depth comes from three layers stacked in one pane: a vertical fill that is lighter at the top,
 * a hairline border that is bright where light would catch the edge and nearly invisible at the
 * bottom, and a soft drop shadow underneath. That reads as a thick pane of glass rather than a
 * flat translucent rectangle.
 */
object Glass {

    val fill: Brush
        get() = Brush.verticalGradient(listOf(Palette.GlassTop, Palette.GlassBottom))

    val strongFill: Brush
        get() = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF)))

    val edge: Brush
        get() = Brush.verticalGradient(listOf(Palette.GlassBorderTop, Palette.GlassBorderBottom))

    val accentEdge: Brush
        get() = Brush.verticalGradient(listOf(Color(0x998B6CFF), Color(0x228B6CFF)))

    /** The page background: a soft dark field with two faint colour blooms, never flat black. */
    val background: Brush
        get() = Brush.linearGradient(
            colors = listOf(Palette.BaseDeep, Palette.Base, Color(0xFF151827)),
            start = Offset.Zero,
            end = Offset.Infinite
        )
}

@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 10.dp,
    accent: Boolean = false,
    strong: Boolean = false
): Modifier = this
    .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .background(if (strong) Glass.strongFill else Glass.fill, shape)
    .border(BorderStroke(1.dp, if (accent) Glass.accentEdge else Glass.edge), shape)

/** A glass pane. [onClick] is optional so the same surface works for static and tappable cards. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 10.dp,
    accent: Boolean = false,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .glass(shape, elevation, accent, strong)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content
    )
}

/** Full-screen background every page sits on. */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Glass.background), content = content)
}

/** Small translucent pill used for metadata (year, runtime, quality, source). */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .glass(shape = shape, elevation = if (selected) 8.dp else 3.dp, accent = selected, strong = selected)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Palette.TextPrimary else Palette.TextSecondary
        )
    }
}
