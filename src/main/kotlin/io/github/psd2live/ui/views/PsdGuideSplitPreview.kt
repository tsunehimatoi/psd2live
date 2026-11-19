package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.core.Bounds
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.BatchSplitItemState
import io.github.psd2live.ui.state.PsdGuideState
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.utils.toImageBitmapFast
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private data class SplitRegion(val bounds: Bounds, val name: String = "")

@Composable
internal fun SplitGuidePreview(state: PsdGuideState, source: WorkspacePsdGuide.Candidate) {
    val candidates = state.splitItems
    val focused = state.splitFocus
    val analysis = source.model.analysis
    // Every row reads the original-size PSD from the same bitmap.
    val image = remember(analysis.preview) { analysis.preview.toImageBitmapFast() }
    if (candidates.isEmpty()) {
        SplitArtworkViewport(image, analysis.anchors.character, Modifier.fillMaxSize(), emptyList())
        return
    }

    val list = rememberLazyListState()
    LaunchedEffect(candidates, focused) {
        val index = candidates.indexOfFirst { it.offer.layerId == focused }
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none {
                it.index == index && it.offset >= list.layoutInfo.viewportStartOffset &&
                    it.offset + it.size <= list.layoutInfo.viewportEndOffset
            }) list.scrollToItem(index)
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        items(candidates, key = { it.offer.layerId }) { item ->
            SplitPreviewRow(item, image, focused == item.offer.layerId)
        }
    }
}

@Composable
private fun SplitPreviewRow(item: BatchSplitItemState, image: ImageBitmap, focused: Boolean) {
    val components = item.offer.plan.componentBounds
    val target = remember(components) { components.reduce(Bounds::union) }
    val margin = maxOf(target.width, target.height) * .28f
    val frame = Bounds(target.left - margin, target.top - margin, target.right + margin, target.bottom + margin)
    val names = item.generatedNamesAndSides().first
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val shape = RoundedCornerShape(Corners.Panel)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.panelElevated)
        .border(1.dp, if (focused) colors.accent else colors.divider, shape).padding(Spacing.Margin),
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        Text(item.offer.layerName, color = colors.textPrimary, style = typography.body, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            SplitPreviewCell(tr("psdGuide.split.before"), image, frame, listOf(SplitRegion(target)), Modifier.weight(1f))
            SplitPreviewCell(tr("psdGuide.split.after"), image, frame,
                components.mapIndexed { index, bounds -> SplitRegion(bounds, names[index]) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SplitPreviewCell(title: String, image: ImageBitmap, frame: Bounds, regions: List<SplitRegion>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        Text(title, color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.caption)
        SplitArtworkViewport(image, frame, Modifier.fillMaxWidth().height(120.dp), regions)
    }
}

/** Both views sample the same decoded PSD, so the detail view keeps its surrounding artwork and its source pixels. */
@Composable
private fun SplitArtworkViewport(
    image: ImageBitmap,
    frame: Bounds,
    modifier: Modifier,
    regions: List<SplitRegion>,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val measurer = rememberTextMeasurer()
    val sourceLeft = floor(frame.left).toInt().coerceIn(0, image.width - 1)
    val sourceTop = floor(frame.top).toInt().coerceIn(0, image.height - 1)
    val sourceRight = ceil(frame.right).toInt().coerceIn(sourceLeft + 1, image.width)
    val sourceBottom = ceil(frame.bottom).toInt().coerceIn(sourceTop + 1, image.height)
    val sourceSize = IntSize(sourceRight - sourceLeft, sourceBottom - sourceTop)

    Canvas(modifier.clip(RoundedCornerShape(Corners.Panel)).background(colors.inputBackground)) {
        val inset = 10.dp.toPx()
        val scale = minOf(
            (size.width - 2 * inset).coerceAtLeast(1f) / sourceSize.width,
            (size.height - 2 * inset).coerceAtLeast(1f) / sourceSize.height,
        )
        val drawn = IntSize((sourceSize.width * scale).roundToInt().coerceAtLeast(1), (sourceSize.height * scale).roundToInt().coerceAtLeast(1))
        val origin = IntOffset(((size.width - drawn.width) / 2).roundToInt(), ((size.height - drawn.height) / 2).roundToInt())
        val scaleX = drawn.width.toFloat() / sourceSize.width
        val scaleY = drawn.height.toFloat() / sourceSize.height
        drawImage(image, srcOffset = IntOffset(sourceLeft, sourceTop), srcSize = sourceSize,
            dstOffset = origin, dstSize = drawn, filterQuality = FilterQuality.High)

        clipRect {
            regions.forEachIndexed { index, (bounds, name) ->
                val color = if (index % 2 == 1) colors.highlight else colors.accent
                val at = Offset(origin.x + (bounds.left - sourceLeft) * scaleX, origin.y + (bounds.top - sourceTop) * scaleY)
                val box = Size(bounds.width * scaleX, bounds.height * scaleY)
                drawRect(color.copy(alpha = .16f), at, box)
                drawRect(color, at, box, style = Stroke(1.dp.toPx()))
                if (name.isBlank()) return@forEachIndexed

                val padding = Spacing.Gap.toPx()
                val label = measurer.measure(name, typography.caption.copy(color = colors.textPrimary),
                    overflow = TextOverflow.Ellipsis, maxLines = 1,
                    constraints = Constraints(maxWidth = (size.width * .5f).toInt()))
                val labelSize = Size(label.size.width + 2 * padding, label.size.height + padding)
                val labelAt = Offset(
                    at.x.coerceIn(0f, (size.width - labelSize.width).coerceAtLeast(0f)),
                    (at.y - labelSize.height).coerceAtLeast(0f),
                )
                drawRoundRect(colors.panelElevated.copy(alpha = .95f), labelAt, labelSize, CornerRadius(Corners.Control.toPx()))
                drawText(label, topLeft = labelAt + Offset(padding, padding / 2))
            }
        }
    }
}
