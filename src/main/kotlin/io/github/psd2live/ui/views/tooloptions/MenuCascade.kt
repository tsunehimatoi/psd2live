package io.github.psd2live.ui.views.tooloptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.graphics.TransformOrigin
import io.github.psd2live.ui.views.texture.menuAppearance
import io.github.psd2live.ui.views.texture.menuPanel

internal val LocalMenuOpensLeft = compositionLocalOf { false }
private val LocalMenuDirection = compositionLocalOf<MutableState<Boolean>?> { null }

/** A submenu gets its own popup so opening it never moves the primary menu away from the pointer. */
@Composable
internal fun MenuCascade(content: @Composable () -> Unit) {
    val left = remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalMenuDirection provides left, LocalMenuOpensLeft provides left.value) {
        Row(verticalAlignment = Alignment.Top) { content() }
    }
}

internal fun submenuPosition(anchor: IntRect, window: IntSize, size: IntSize, primaryWidth: Int, gap: Int, margin: Int): Pair<IntOffset, Boolean> {
    val right = anchor.left + gap
    val left = anchor.left - primaryWidth - gap - size.width
    val opensLeft = right + size.width > window.width - margin &&
        (left >= margin || anchor.left - primaryWidth > window.width - anchor.left)
    val x = (if (opensLeft) left else right).coerceIn(margin, maxOf(margin, window.width - size.width - margin))
    val y = anchor.top.coerceIn(margin, maxOf(margin, window.height - size.height - margin))
    return IntOffset(x, y) to opensLeft
}

/** The same second-level placement, panel and motion for canvas and hierarchy menus. */
@Composable
internal fun MenuSecondLevel(id: String?, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val direction = LocalMenuDirection.current
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = id != null
    if (!transition.currentState && !transition.targetState) return
    val provider = remember(density, direction) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val (position, left) = with(density) {
                    submenuPosition(anchorBounds, windowSize, popupContentSize, 236.dp.roundToPx(), 4.dp.roundToPx(), 8.dp.roundToPx())
                }
                direction?.value = left
                return position
            }
        }
    }
    Box {
        Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
            Box(Modifier.menuAppearance(transition, TransformOrigin(if (direction?.value == true) 1f else 0f, 0f), rise = false)
                .menuPanel().padding(4.dp).heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                key(id) { content() }
            }
        }
    }
}
