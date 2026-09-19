package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.theme.LocalToolColors

// Only tree rows publish here; hovering the main canvas must not open this preview.
internal data class MeshPreviewHover(val drawableId: String, val centerYInRoot: Float)
internal val LocalMeshPreviewHover = staticCompositionLocalOf<MutableState<MeshPreviewHover?>?> { null }

@Composable
internal fun HierarchyMeshPreview(
    model: RigPreviewModel,
    drawableId: String,
    sidebarRight: Dp,
    anchorY: Float,
) {
    val drawable = model.rig.puppet.drawables.firstOrNull { it.id.raw == drawableId } ?: return
    val mesh = drawable.mesh ?: return
    val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
    val placement = model.atlas.placementByLayerId[layerId]
    val pageIndex = placement?.page ?: model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
    val page = model.atlas.pages.getOrNull(pageIndex) ?: return
    // Imported models may have UVs but no source-layer atlas placement.
    val source = remember(page, placement, mesh) {
        if (mesh.uvs.isEmpty()) return@remember null
        val left = (placement?.x ?: floor(mesh.uvs.filterIndexed { i, _ -> i % 2 == 0 }.min() * page.image.width).toInt()).coerceIn(0, page.image.width)
        val top = (placement?.y ?: floor(mesh.uvs.filterIndexed { i, _ -> i % 2 == 1 }.min() * page.image.height).toInt()).coerceIn(0, page.image.height)
        val right = (placement?.let { it.x + it.width } ?: ceil(mesh.uvs.filterIndexed { i, _ -> i % 2 == 0 }.max() * page.image.width).toInt()).coerceIn(left, page.image.width)
        val bottom = (placement?.let { it.y + it.height } ?: ceil(mesh.uvs.filterIndexed { i, _ -> i % 2 == 1 }.max() * page.image.height).toInt()).coerceIn(top, page.image.height)
        if (right == left || bottom == top) null
        else page.image.getSubimage(left, top, right - left, bottom - top).toComposeImageBitmap()
    } ?: return
    val colors = LocalToolColors.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(180.dp, maxHeight)
        val sidePx = with(density) { side.toPx() }
        val availableHeight = with(density) { maxHeight.toPx() }
        val top = (anchorY - sidePx / 2).coerceIn(0f, (availableHeight - sidePx).coerceAtLeast(0f))
        val arrowY = (anchorY - top).coerceIn(0f, sidePx)
        Canvas(
            Modifier.offset { IntOffset(with(density) { sidebarRight.roundToPx() }, top.roundToInt()) }
                .size(side + 8.dp, side),
        ) {
            val arrowWidth = 8.dp.toPx()
            val halfArrow = 6.dp.toPx().coerceAtMost(sidePx / 2)
            val arrowBase = arrowY.coerceIn(halfArrow, sidePx - halfArrow)
            val outline = Path().apply {
                moveTo(arrowWidth, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(arrowWidth, size.height)
                lineTo(arrowWidth, arrowBase + halfArrow)
                lineTo(0f, arrowY)
                lineTo(arrowWidth, arrowBase - halfArrow)
                close()
            }
            drawPath(outline, colors.panelBackground)
            clipRect(left = arrowWidth, top = 0f, right = size.width, bottom = size.height) {
                drawRect(colors.checkerDark)
                val scale = (sidePx - 16.dp.toPx()).coerceAtLeast(1f) / maxOf(source.width, source.height)
                val width = (source.width * scale).roundToInt().coerceAtLeast(1)
                val height = (source.height * scale).roundToInt().coerceAtLeast(1)
                drawImage(
                    source,
                    dstOffset = IntOffset((arrowWidth + (sidePx - width) / 2).roundToInt(), ((sidePx - height) / 2).roundToInt()),
                    dstSize = IntSize(width, height),
                )
            }
            drawPath(outline, colors.border, style = Stroke(1.dp.toPx()))
        }
    }
}
