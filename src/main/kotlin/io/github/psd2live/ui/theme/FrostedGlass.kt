package io.github.psd2live.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Applies a clean, borderless frosted glass (acrylic) effect.
 *
 * Uses a soft ambient depth shadow and a translucent surface without border or specular highlights.
 * Light chrome softens the elevation tint so floating toolbars do not pick up a muddy dark halo
 * against the pale canvas checkerboard.
 */
@Composable
fun Modifier.frostedGlass(
    shape: Shape = RoundedCornerShape(6.dp),
    isHovered: Boolean = false,
    elevation: Dp = 4.dp,
    baseColor: Color = LocalToolColors.current.panelBackground,
    alpha: Float = 0.85f,
): Modifier {
    val colors = LocalToolColors.current
    val glassAlpha = if (isHovered) (alpha * 1.05f).coerceAtMost(0.95f) else alpha
    // Compose's default shadow tint is opaque black. On light chrome that reads as a dark ring around
    // every floating toolbar; keep depth with a low-alpha ink instead of dropping elevation entirely.
    val shadowInk = if (colors.isDark) {
        Color.Black
    } else {
        Color(0xFF1F2328).copy(alpha = 0.12f)
    }
    val glassColor = if (colors.isDark) {
        baseColor.copy(alpha = glassAlpha)
    } else {
        // Slightly denser wash so the panel edge stays crisp over the checkerboard without relying on
        // a heavy shadow for separation.
        baseColor.copy(alpha = (glassAlpha + 0.08f).coerceAtMost(0.96f))
    }

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = shadowInk,
            spotColor = shadowInk,
        )
        .background(
            color = glassColor,
            shape = shape,
        )
}

/**
 * Frosted glass styling specifically tailored for the top horizontal options bar without borders or highlights.
 */
@Composable
fun Modifier.frostedGlassTopBar(
    baseColor: Color = LocalToolColors.current.panelBackground,
    alpha: Float = 0.88f,
): Modifier {
    return this.background(baseColor.copy(alpha = alpha))
}
