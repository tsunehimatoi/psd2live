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
 */
@Composable
fun Modifier.frostedGlass(
    shape: Shape = RoundedCornerShape(6.dp),
    isHovered: Boolean = false,
    elevation: Dp = 4.dp,
    baseColor: Color = LocalToolColors.current.panelBackground,
    alpha: Float = 0.85f,
): Modifier {
    val glassColor = baseColor.copy(alpha = if (isHovered) (alpha * 1.05f).coerceAtMost(0.95f) else alpha)

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
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
