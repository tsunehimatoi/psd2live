package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.PsdGuideBindings
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerTags
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactSelect
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.components.IconUndo
import io.github.psd2live.ui.state.PsdGuideState
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.utils.toImageBitmapFast
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.texture.GlidingSegments
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.canvasChrome
import io.github.psd2live.ui.views.texture.glideItem
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private enum class BindingFilterTab { TODO, AUTO, ALL }

/** Exact source-to-canvas viewport mapping shared between canvas drawing and pointer hit-testing. */
private data class BindingCanvasViewport(
    val srcOffset: IntOffset,
    val srcSize: IntSize,
    val dstOffset: IntOffset,
    val dstSize: IntSize,
    val scaleX: Float,
    val scaleY: Float,
) {
    fun toCanvas(x: Float, y: Float): Offset = Offset(
        dstOffset.x + (x - srcOffset.x) * scaleX,
        dstOffset.y + (y - srcOffset.y) * scaleY,
    )

    fun toImage(position: Offset): Offset? {
        if (scaleX <= 0f || scaleY <= 0f) return null
        if (position.x < dstOffset.x || position.x > dstOffset.x + dstSize.width ||
            position.y < dstOffset.y || position.y > dstOffset.y + dstSize.height
        ) {
            return null
        }
        return Offset(
            srcOffset.x + (position.x - dstOffset.x) / scaleX,
            srcOffset.y + (position.y - dstOffset.y) / scaleY,
        )
    }
}

private fun computeBindingViewport(
    canvasSize: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    focusedLayers: List<ClassifiedLayer>,
    zoomToFocus: Boolean,
): BindingCanvasViewport? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    val (srcOffset, srcSize) = if (zoomToFocus && focusedLayers.isNotEmpty()) {
        val union = focusedLayers.map { it.bounds }.reduce(Bounds::union)
        val pad = maxOf(union.width, union.height, 32f) * 0.35f
        val left = floor(union.left - pad).toInt().coerceIn(0, imageWidth - 1)
        val top = floor(union.top - pad).toInt().coerceIn(0, imageHeight - 1)
        val right = ceil(union.right + pad).toInt().coerceIn(left + 1, imageWidth)
        val bottom = ceil(union.bottom + pad).toInt().coerceIn(top + 1, imageHeight)
        IntOffset(left, top) to IntSize(right - left, bottom - top)
    } else {
        IntOffset.Zero to IntSize(imageWidth, imageHeight)
    }

    val scale = minOf(
        canvasSize.width.toFloat() / srcSize.width.toFloat(),
        canvasSize.height.toFloat() / srcSize.height.toFloat(),
    )
    val dstW = (srcSize.width * scale).roundToInt().coerceAtLeast(1)
    val dstH = (srcSize.height * scale).roundToInt().coerceAtLeast(1)
    val dstOffset = IntOffset(
        ((canvasSize.width - dstW) * 0.5f).roundToInt(),
        ((canvasSize.height - dstH) * 0.5f).roundToInt(),
    )
    val dstSize = IntSize(dstW, dstH)
    return BindingCanvasViewport(
        srcOffset = srcOffset,
        srcSize = srcSize,
        dstOffset = dstOffset,
        dstSize = dstSize,
        scaleX = dstSize.width.toFloat() / srcSize.width.toFloat(),
        scaleY = dstSize.height.toFloat() / srcSize.height.toFloat(),
    )
}

private fun hitTestLayer(
    position: Offset,
    viewport: BindingCanvasViewport?,
    report: PsdGuideBindings.Result?,
): ClassifiedLayer? {
    val vp = viewport ?: return null
    val layers = report?.layers ?: return null
    val point = vp.toImage(position) ?: return null
    return layers
        .filter { layer ->
            point.x >= layer.bounds.left && point.x <= layer.bounds.right &&
                point.y >= layer.bounds.top && point.y <= layer.bounds.bottom
        }
        .minByOrNull { it.bounds.width.coerceAtLeast(0f) * it.bounds.height.coerceAtLeast(0f) }
}

private fun partLabel(tag: SemanticTag): String =
    LayerTags.parts[tag]?.joinToString(" · ") { tagLabel(it) } ?: tr("psdGuide.binding.unclassified")

private fun sideLabel(side: Side): String = when (side) {
    Side.LEFT -> tagLabel(LayerTags.LEFT)
    Side.RIGHT -> tagLabel(LayerTags.RIGHT)
    Side.NONE -> tr("psdGuide.binding.noSide")
}

private fun effectiveClassification(
    report: PsdGuideBindings.Result,
    layer: ClassifiedLayer,
): LayerClassificationOverride = report.overrides[layer.source.id.raw] ?: PsdGuideBindings.classification(layer)

/**
 * Interactive PSD layer preview for the Binding step: supports full/focused zoom, direct
 * click-to-pick on canvas bounds, focus bounding boxes with clamped name pills, and status bar.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BindingGuidePreview(state: PsdGuideState, source: WorkspacePsdGuide.Candidate) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val measurer = rememberTextMeasurer()
    val previewImage = source.model.analysis.preview
    val bitmap = remember(previewImage) { previewImage.toImageBitmapFast() }
    val report = state.bindings
    val layers = report?.layers.orEmpty()
    val focusedLayers = remember(layers, state.bindingFocus) {
        layers.filter { it.source.id.raw in state.bindingFocus }
    }
    var zoomToFocus by remember(source) { mutableStateOf(false) }
    val effectiveZoom = zoomToFocus && focusedLayers.isNotEmpty()
    LaunchedEffect(focusedLayers.isEmpty()) {
        if (focusedLayers.isEmpty()) zoomToFocus = false
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val pointerViewport = remember(canvasSize, bitmap.width, bitmap.height, focusedLayers, effectiveZoom) {
        computeBindingViewport(canvasSize, bitmap.width, bitmap.height, focusedLayers, effectiveZoom)
    }
    var hoveredLayerId by remember { mutableStateOf<String?>(null) }

    val currentPointerViewport by rememberUpdatedState(pointerViewport)
    val currentReport by rememberUpdatedState(report)
    val currentBusy by rememberUpdatedState(state.busy)
    val currentFocusedLayers by rememberUpdatedState(focusedLayers)
    val currentEffectiveZoom by rememberUpdatedState(effectiveZoom)

    val hoveredLayer = remember(hoveredLayerId, layers) {
        hoveredLayerId?.let { id -> layers.firstOrNull { it.source.id.raw == id } }
    }
    val statusText = remember(hoveredLayer, focusedLayers, report) {
        fun describe(list: List<ClassifiedLayer>): String {
            val primary = list.first()
            val value = effectiveClassification(report!!, primary)
            val part = partLabel(value.tag)
            val summary = if (value.side != Side.NONE) "$part · ${sideLabel(value.side)}" else part
            val names = list.joinToString(" / ") { it.source.name }
            return "$names · $summary"
        }
        when {
            report == null -> tr("psdGuide.binding.canvasHint")
            hoveredLayer != null -> describe(listOf(hoveredLayer))
            focusedLayers.isNotEmpty() -> describe(focusedLayers)
            else -> tr("psdGuide.binding.canvasHint")
        }
    }

    GuideCanvasFrame(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .handCursor(hoveredLayerId != null && !state.busy)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val pos = event.changes.firstOrNull()?.position
                    hoveredLayerId = if (pos != null && !currentBusy) {
                        hitTestLayer(pos, currentPointerViewport, currentReport)?.source?.id?.raw
                    } else null
                }
                .onPointerEvent(PointerEventType.Exit) { hoveredLayerId = null }
                .pointerInput(report, zoomToFocus, focusedLayers) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        if (currentBusy) return@awaitEachGesture
                        val vp = computeBindingViewport(
                            canvasSize = size,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            focusedLayers = currentFocusedLayers,
                            zoomToFocus = currentEffectiveZoom,
                        )
                        val hit = hitTestLayer(down.position, vp, currentReport) ?: return@awaitEachGesture
                        down.consume()
                        state.bindingFocus = listOf(hit.source.id.raw)
                    }
                },
        ) {
            drawGuideChecker(light = colors.checkerLight, dark = colors.checkerDark)
            val vp = computeBindingViewport(
                canvasSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                focusedLayers = focusedLayers,
                zoomToFocus = effectiveZoom,
            ) ?: return@Canvas
            drawImage(
                image = bitmap,
                srcOffset = vp.srcOffset,
                srcSize = vp.srcSize,
                dstOffset = vp.dstOffset,
                dstSize = vp.dstSize,
                filterQuality = FilterQuality.High,
            )

            clipRect {
                val hovered = if (state.busy) null else hoveredLayerId
                    ?.takeIf { id -> focusedLayers.none { it.source.id.raw == id } }
                    ?.let { id -> layers.firstOrNull { it.source.id.raw == id } }
                if (hovered != null) {
                    val tl = vp.toCanvas(hovered.bounds.left, hovered.bounds.top)
                    val br = vp.toCanvas(hovered.bounds.right, hovered.bounds.bottom)
                    drawRect(
                        color = colors.accent.copy(alpha = 0.55f),
                        topLeft = tl,
                        size = Size((br.x - tl.x).coerceAtLeast(1f), (br.y - tl.y).coerceAtLeast(1f)),
                        style = Stroke(1.dp.toPx()),
                    )
                }

                val padH = 6.dp.toPx()
                val padV = 3.dp.toPx()
                val gap = 4.dp.toPx()
                val edgeMargin = 4.dp.toPx()
                val placedPills = mutableListOf<Rect>()

                for (layer in focusedLayers) {
                    val topLeft = vp.toCanvas(layer.bounds.left, layer.bounds.top)
                    val bottomRight = vp.toCanvas(layer.bounds.right, layer.bounds.bottom)
                    val boxSize = Size(
                        (bottomRight.x - topLeft.x).coerceAtLeast(1f),
                        (bottomRight.y - topLeft.y).coerceAtLeast(1f),
                    )
                    drawRect(colors.accent.copy(alpha = 0.18f), topLeft, boxSize)
                    drawRect(colors.accent, topLeft, boxSize, style = Stroke(1.5.dp.toPx()))

                    val measured = measurer.measure(
                        text = layer.source.name,
                        style = typography.captionSmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (size.width * 0.6f).roundToInt().coerceAtLeast(1)),
                    )
                    val pillSize = Size(measured.size.width + 2f * padH, measured.size.height + 2f * padV)
                    val maxPillX = (size.width - pillSize.width - edgeMargin).coerceAtLeast(edgeMargin)
                    val maxPillY = (size.height - pillSize.height - edgeMargin).coerceAtLeast(edgeMargin)
                    val pillX = topLeft.x.coerceIn(edgeMargin, maxPillX)
                    val preferredY = if (topLeft.y - pillSize.height - gap >= edgeMargin) {
                        topLeft.y - pillSize.height - gap
                    } else {
                        bottomRight.y + gap
                    }.coerceIn(edgeMargin, maxPillY)

                    var pillRect = Rect(pillX, preferredY, pillX + pillSize.width, preferredY + pillSize.height)
                    if (placedPills.any { it.overlaps(pillRect) }) {
                        val belowY = (bottomRight.y + gap).coerceIn(edgeMargin, maxPillY)
                        val altRect = Rect(pillX, belowY, pillX + pillSize.width, belowY + pillSize.height)
                        pillRect = if (placedPills.none { it.overlaps(altRect) }) {
                            altRect
                        } else {
                            val stackedY = (placedPills.maxOf { it.bottom } + gap).coerceIn(edgeMargin, maxPillY)
                            Rect(pillX, stackedY, pillX + pillSize.width, stackedY + pillSize.height)
                        }
                    }
                    placedPills += pillRect

                    val pillOrigin = Offset(pillRect.left, pillRect.top)
                    drawRoundRect(
                        color = colors.panelElevated.copy(alpha = 0.94f),
                        topLeft = pillOrigin,
                        size = pillSize,
                        cornerRadius = CornerRadius(Corners.Control.toPx()),
                    )
                    drawRoundRect(
                        color = colors.accent.copy(alpha = 0.55f),
                        topLeft = pillOrigin,
                        size = pillSize,
                        cornerRadius = CornerRadius(Corners.Control.toPx()),
                        style = Stroke(0.75.dp.toPx()),
                    )
                    drawText(measured, topLeft = pillOrigin + Offset(padH, padV))
                }
            }
        }

        AnimatedVisibility(
            visible = focusedLayers.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopStart).padding(Spacing.Margin),
            enter = fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)),
            exit = fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)),
        ) {
            FloatingBar(Modifier.canvasChrome()) {
                BarChip(
                    label = tr(if (effectiveZoom) "psdGuide.binding.zoomAll" else "psdGuide.binding.zoomFocus"),
                    selected = effectiveZoom,
                    onClick = { zoomToFocus = !zoomToFocus },
                    enabled = focusedLayers.isNotEmpty(),
                    icon = { tint -> IconSearch(Modifier.size(12.dp), tint = tint) },
                )
            }
        }

        GuideStatusCapsule(
            text = statusText,
            modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.Margin).canvasChrome(),
        )
    }
}

/**
 * Right-hand inspector sidebar for the Binding step: segmented filter tabs (Review / Auto-fixed /
 * All Layers), search box, scrollable DCC layer list, and pinned classification editor.
 */
@Composable
internal fun BindingGuideControls(state: PsdGuideState) {
    val report = state.bindings ?: return
    val colors = LocalToolColors.current
    val layersById = remember(report) { report.layers.associateBy { it.source.id.raw } }

    val availableTabs = remember(report.automatic.isNotEmpty()) {
        buildList {
            add(BindingFilterTab.TODO)
            if (report.automatic.isNotEmpty()) add(BindingFilterTab.AUTO)
            add(BindingFilterTab.ALL)
        }
    }
    var selectedTab by remember(state.source) {
        mutableStateOf(
            when {
                report.findings.isNotEmpty() -> BindingFilterTab.TODO
                report.automatic.isNotEmpty() -> BindingFilterTab.AUTO
                else -> BindingFilterTab.ALL
            },
        )
    }
    val effectiveTab = if (selectedTab in availableTabs) selectedTab else availableTabs.first()
    LaunchedEffect(availableTabs, selectedTab) {
        if (selectedTab !in availableTabs) selectedTab = availableTabs.first()
    }

    var query by remember(state.source) { mutableStateOf("") }
    var missingPromptTag by remember(state.source) { mutableStateOf<SemanticTag?>(null) }

    val activeMissingPrompt = missingPromptTag?.takeIf { tag ->
        effectiveTab == BindingFilterTab.ALL &&
            report.findings.any { it.kind == PsdGuideBindings.Kind.MISSING && it.tag == tag }
    }
    LaunchedEffect(report, effectiveTab) {
        if (missingPromptTag != null && (effectiveTab != BindingFilterTab.ALL ||
                report.findings.none { it.kind == PsdGuideBindings.Kind.MISSING && it.tag == missingPromptTag })
        ) {
            missingPromptTag = null
        }
    }

    val activeCount = when (effectiveTab) {
        BindingFilterTab.TODO -> report.findings.size
        BindingFilterTab.AUTO -> report.automatic.size
        BindingFilterTab.ALL -> report.layers.size
    }
    val showSearch = effectiveTab == BindingFilterTab.ALL || activeCount > 8 || query.isNotBlank()
    val trimmedQuery = query.trim()

    val filteredFindings = remember(report.findings, layersById, trimmedQuery) {
        if (trimmedQuery.isEmpty()) report.findings
        else report.findings.filter { finding ->
            val kindLabel = tr("psdGuide.binding.kind.${finding.kind.name.lowercase()}")
            val tagText = partLabel(finding.tag)
            kindLabel.contains(trimmedQuery, ignoreCase = true) ||
                tagText.contains(trimmedQuery, ignoreCase = true) ||
                finding.layers.any { id ->
                    val layer = layersById[id] ?: return@any false
                    layer.source.name.contains(trimmedQuery, ignoreCase = true) ||
                        layer.source.groupPath.contains(trimmedQuery, ignoreCase = true)
                }
        }
    }
    val autoEntries = remember(report.automatic, layersById, trimmedQuery) {
        val entries = report.automatic.entries.mapNotNull { (id, value) ->
            val layer = layersById[id] ?: return@mapNotNull null
            Triple(id, value, layer)
        }
        if (trimmedQuery.isEmpty()) entries
        else entries.filter { (_, value, layer) ->
            layer.source.name.contains(trimmedQuery, ignoreCase = true) ||
                layer.source.groupPath.contains(trimmedQuery, ignoreCase = true) ||
                partLabel(value.tag).contains(trimmedQuery, ignoreCase = true) ||
                sideLabel(value.side).contains(trimmedQuery, ignoreCase = true)
        }
    }
    val filteredLayers = remember(report, trimmedQuery) {
        if (trimmedQuery.isEmpty()) report.layers
        else report.layers.filter { layer ->
            val value = effectiveClassification(report, layer)
            layer.source.name.contains(trimmedQuery, ignoreCase = true) ||
                layer.source.groupPath.contains(trimmedQuery, ignoreCase = true) ||
                partLabel(value.tag).contains(trimmedQuery, ignoreCase = true) ||
                sideLabel(value.side).contains(trimmedQuery, ignoreCase = true)
        }
    }

    val navigableFocuses = remember(effectiveTab, filteredFindings, autoEntries, filteredLayers) {
        when (effectiveTab) {
            BindingFilterTab.TODO -> filteredFindings.mapNotNull { it.layers.takeIf { ids -> ids.isNotEmpty() } }
            BindingFilterTab.AUTO -> autoEntries.map { listOf(it.first) }
            BindingFilterTab.ALL -> filteredLayers.map { listOf(it.source.id.raw) }
        }
    }
    val currentNavIndex = navigableFocuses.indexOfFirst { it == state.bindingFocus }

    val focusedLayers = remember(state.bindingFocus, layersById) {
        state.bindingFocus.mapNotNull(layersById::get).distinctBy { it.source.id.raw }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val boundedHeight = constraints.hasBoundedHeight
        val rootModifier = if (boundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        val inspectorMaxHeight = if (boundedHeight) maxOf(128.dp, maxHeight * 0.44f) else Dp.Unspecified

        Column(
            modifier = rootModifier,
            verticalArrangement = Arrangement.spacedBy(Spacing.Gap),
        ) {
            val listModifier = if (boundedHeight) {
                Modifier.weight(1f).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth().heightIn(max = 220.dp)
            }
            GuideNote(tr("psdGuide.step.binding.desc"))

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    GlidingSegments(
                        choices = availableTabs,
                        selected = effectiveTab,
                        onSelect = { tab ->
                            selectedTab = tab
                            if (tab != BindingFilterTab.ALL) missingPromptTag = null
                        },
                        label = { tab ->
                            when (tab) {
                                BindingFilterTab.TODO -> tr("psdGuide.binding.tab.todo", report.findings.size)
                                BindingFilterTab.AUTO -> tr("psdGuide.binding.tab.auto", report.automatic.size)
                                BindingFilterTab.ALL -> tr("psdGuide.binding.tab.all", report.layers.size)
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.handCursor(!state.busy),
                    )
                }
                Divider(color = colors.divider, thickness = 0.5.dp)
            }

            if (showSearch) {
                CompactTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = tr("psdGuide.binding.search"),
                    enabled = !state.busy,
                    leadingIcon = { IconSearch(Modifier.size(12.dp), tint = colors.textMuted) },
                    trailingIcon = if (query.isEmpty()) null else {
                        {
                            CompactIconButton(
                                onClick = { query = "" },
                                enabled = !state.busy,
                                tooltip = tr("parameters.clearSearch"),
                                size = 16.dp,
                            ) {
                                IconClose(Modifier.size(8.dp), tint = colors.textMuted)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 24.dp,
                )
            }

            var lastMissingPrompt by remember { mutableStateOf<SemanticTag?>(null) }
            if (activeMissingPrompt != null) lastMissingPrompt = activeMissingPrompt
            AnimatedVisibility(
                visible = activeMissingPrompt != null,
                enter = expandVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)),
                exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing)) +
                    fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)),
            ) {
                lastMissingPrompt?.let { tag ->
                    GuideInfoBanner(
                        text = tr("psdGuide.binding.missingHint", partLabel(tag)),
                        tone = GuideBannerTone.WARNING,
                    )
                }
            }

            if (effectiveTab == BindingFilterTab.TODO && report.findings.isEmpty()) {
                GuideInfoBanner(tr("psdGuide.binding.clean"), GuideBannerTone.SUCCESS)
                if (boundedHeight) Spacer(Modifier.weight(1f))
            } else {
                BindingListRegion(
                    modifier = listModifier,
                    tab = effectiveTab,
                    state = state,
                    report = report,
                    layersById = layersById,
                    filteredFindings = filteredFindings,
                    autoEntries = autoEntries,
                    filteredLayers = filteredLayers,
                    onSelectMissing = { tag ->
                        missingPromptTag = tag
                        query = ""
                        selectedTab = BindingFilterTab.ALL
                    },
                    onFocusLayers = { ids ->
                        missingPromptTag = null
                        state.bindingFocus = ids
                    },
                )
            }

            Divider(color = colors.divider, thickness = 0.5.dp)

            BindingSelectionInspector(
                focusedLayers = focusedLayers,
                report = report,
                state = state,
                maxHeight = inspectorMaxHeight,
                canStepPrev = currentNavIndex > 0,
                canStepNext = currentNavIndex in 0 until navigableFocuses.lastIndex,
                onStepPrev = {
                    if (currentNavIndex > 0) state.bindingFocus = navigableFocuses[currentNavIndex - 1]
                },
                onStepNext = {
                    if (currentNavIndex in 0 until navigableFocuses.lastIndex) {
                        state.bindingFocus = navigableFocuses[currentNavIndex + 1]
                    }
                },
            )
        }
    }
}

@Composable
private fun BindingListRegion(
    modifier: Modifier,
    tab: BindingFilterTab,
    state: PsdGuideState,
    report: PsdGuideBindings.Result,
    layersById: Map<String, ClassifiedLayer>,
    filteredFindings: List<PsdGuideBindings.Finding>,
    autoEntries: List<Triple<String, LayerClassificationOverride, ClassifiedLayer>>,
    filteredLayers: List<ClassifiedLayer>,
    onSelectMissing: (SemanticTag) -> Unit,
    onFocusLayers: (List<String>) -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val enabled = !state.busy

    key(tab) {
        val listState = rememberLazyListState()
        val focusKey = state.bindingFocus
        LaunchedEffect(focusKey, tab, filteredFindings, autoEntries, filteredLayers) {
            if (focusKey.isEmpty()) return@LaunchedEffect
            val targetIndex = when (tab) {
                BindingFilterTab.TODO -> filteredFindings.indexOfFirst {
                    it.layers == focusKey || focusKey.any(it.layers::contains)
                }
                BindingFilterTab.AUTO -> autoEntries.indexOfFirst { (id, _, _) -> id in focusKey }
                BindingFilterTab.ALL -> filteredLayers.indexOfFirst { it.source.id.raw in focusKey }
            }
            if (targetIndex >= 0) {
                val visible = listState.layoutInfo.visibleItemsInfo
                val start = listState.layoutInfo.viewportStartOffset
                val end = listState.layoutInfo.viewportEndOffset
                val alreadyVisible = visible.any { it.index == targetIndex && it.offset >= start && it.offset + it.size <= end }
                if (!alreadyVisible) listState.scrollToItem(targetIndex)
            }
        }

        val listShape = RoundedCornerShape(Corners.Control)
        Box(
            modifier = modifier
                .clip(listShape)
                .background(colors.panelBackground, listShape)
                .border(1.dp, colors.divider, listShape)
                .padding(4.dp),
        ) {
            MenuGlide(Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 2.dp, end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                when (tab) {
                    BindingFilterTab.TODO -> {
                        if (filteredFindings.isEmpty()) {
                            item("empty-todo") {
                                GuideNote(
                                    text = "0 / ${report.findings.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = filteredFindings,
                                key = { idx, f -> "${f.kind}:${f.tag}:${f.layers.joinToString(",")}:$idx" },
                            ) { _, finding ->
                                val isMissing = finding.kind == PsdGuideBindings.Kind.MISSING || finding.layers.isEmpty()
                                val selected = !isMissing && state.bindingFocus == finding.layers
                                val badgeColor = when (finding.kind) {
                                    PsdGuideBindings.Kind.MISSING -> colors.error
                                    PsdGuideBindings.Kind.SIDE,
                                    PsdGuideBindings.Kind.CONFLICT,
                                    PsdGuideBindings.Kind.DUPLICATE,
                                    -> colors.warning
                                    PsdGuideBindings.Kind.UNKNOWN -> colors.textMuted
                                }
                                val badgeBg = when (finding.kind) {
                                    PsdGuideBindings.Kind.MISSING -> colors.errorContainer
                                    PsdGuideBindings.Kind.SIDE,
                                    PsdGuideBindings.Kind.CONFLICT,
                                    PsdGuideBindings.Kind.DUPLICATE,
                                    -> colors.warning.copy(alpha = 0.14f)
                                    PsdGuideBindings.Kind.UNKNOWN -> colors.controlBackground
                                }
                                val title = if (isMissing) {
                                    partLabel(finding.tag)
                                } else {
                                    finding.layers.mapNotNull { layersById[it]?.source?.name }.joinToString(" / ")
                                }
                                val trailing = if (!isMissing && finding.tag != SemanticTag.UNKNOWN) {
                                    partLabel(finding.tag)
                                } else null

                                BindingListRow(
                                    selected = selected,
                                    enabled = enabled,
                                    onClick = {
                                        if (isMissing) onSelectMissing(finding.tag)
                                        else onFocusLayers(finding.layers)
                                    },
                                ) {
                                    GuideBadge(
                                        text = tr("psdGuide.binding.kind.${finding.kind.name.lowercase()}"),
                                        color = badgeColor,
                                        background = badgeBg,
                                    )
                                    Text(
                                        text = title,
                                        style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                        color = if (selected) colors.selectionText else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (trailing != null) {
                                        Text(
                                            text = trailing,
                                            style = typography.captionSmall,
                                            color = if (selected) colors.selectionText.copy(alpha = 0.85f) else colors.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 92.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    BindingFilterTab.AUTO -> {
                        if (autoEntries.isEmpty()) {
                            item("empty-auto") {
                                GuideNote(
                                    text = "0 / ${report.automatic.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            items(autoEntries, key = { (id, _, _) -> id }) { (id, value, layer) ->
                                val selected = state.bindingFocus == listOf(id)
                                BindingListRow(
                                    selected = selected,
                                    enabled = enabled,
                                    onClick = { state.bindingFocus = listOf(id) },
                                ) {
                                    Text(
                                        text = layer.source.name,
                                        style = typography.body.copy(
                                            fontSize = 11.5.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        ),
                                        color = if (selected) colors.selectionText else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    GuideBadge(
                                        text = sideLabel(value.side),
                                        color = colors.highlight,
                                        background = colors.highlightContainer,
                                    )
                                    CompactIconButton(
                                        onClick = { state.classify(id, PsdGuideBindings.classification(layer)) },
                                        enabled = enabled,
                                        tooltip = tr("psdGuide.binding.undo"),
                                        size = 22.dp,
                                    ) {
                                        IconUndo(
                                            modifier = Modifier.size(12.dp),
                                            tint = if (enabled) colors.textPrimary else colors.textDisabled,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    BindingFilterTab.ALL -> {
                        if (filteredLayers.isEmpty()) {
                            item("empty-all") {
                                GuideNote(
                                    text = "0 / ${report.layers.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            items(filteredLayers, key = { it.source.id.raw }) { layer ->
                                val id = layer.source.id.raw
                                val value = effectiveClassification(report, layer)
                                val selected = id in state.bindingFocus
                                BindingListRow(
                                    selected = selected,
                                    enabled = enabled,
                                    onClick = { state.bindingFocus = listOf(id) },
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                    ) {
                                        Text(
                                            text = layer.source.name,
                                            style = typography.body.copy(
                                                fontSize = 11.5.sp,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            ),
                                            color = if (selected) colors.selectionText else colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (layer.source.groupPath.isNotBlank()) {
                                            Text(
                                                text = layer.source.groupPath,
                                                style = typography.captionSmall,
                                                color = if (selected) colors.selectionText.copy(alpha = 0.8f) else colors.textMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = partLabel(value.tag),
                                            style = typography.captionSmall,
                                            color = when {
                                                selected -> colors.selectionText
                                                value.tag == SemanticTag.UNKNOWN -> colors.textMuted
                                                else -> colors.textPrimary
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 96.dp),
                                        )
                                        if (value.side != Side.NONE) {
                                            val autoFixed = id in report.automatic
                                            GuideBadge(
                                                text = sideLabel(value.side),
                                                color = if (autoFixed) colors.highlight else colors.accent,
                                                background = if (autoFixed) colors.highlightContainer else colors.accent.copy(alpha = 0.16f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
            if (listState.canScrollForward || listState.canScrollBackward) {
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                )
            }
        }
    }
}

@Composable
private fun BindingListRow(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalToolColors.current
    val shape = RoundedCornerShape(Corners.Control)
    val rowBg by animateColorAsState(
        targetValue = if (selected) colors.selection else Color.Transparent,
        animationSpec = tween(Motion.Instant),
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(Motion.Instant),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 28.dp)
            .glideItem(enabled && !selected)
            .clip(shape)
            .background(rowBg, shape)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .handCursor(enabled)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun BindingSelectionInspector(
    focusedLayers: List<ClassifiedLayer>,
    report: PsdGuideBindings.Result,
    state: PsdGuideState,
    maxHeight: Dp,
    canStepPrev: Boolean,
    canStepNext: Boolean,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    if (focusedLayers.isEmpty()) {
        GuideSectionCard {
            GuideNote(tr("psdGuide.binding.emptySelection"))
        }
        return
    }

    val cardShape = RoundedCornerShape(Corners.Panel)
    val scrollModifier = when {
        maxHeight != Dp.Unspecified -> Modifier
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
        focusedLayers.size > 2 -> Modifier
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
        else -> Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.panelBackground, cardShape)
            .border(1.dp, colors.divider, cardShape)
            .padding(10.dp)
            .then(scrollModifier),
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap),
    ) {
        val seenFindings = hashSetOf<PsdGuideBindings.Finding>()
        focusedLayers.forEachIndexed { index, layer ->
            val id = layer.source.id.raw
            val value = effectiveClassification(report, layer)
            val findingForLayer = report.findings.firstOrNull { id in it.layers }?.takeIf { seenFindings.add(it) }
            if (index > 0) {
                Divider(
                    color = colors.divider.copy(alpha = 0.6f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            key(id) {
                val fullPath = listOf(layer.source.groupPath, layer.source.name)
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                ) {
                    Text(
                        text = fullPath,
                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (id in report.manual) {
                        CompactIconButton(
                            onClick = { state.classify(id, PsdGuideBindings.classification(layer)) },
                            enabled = !state.busy,
                            tooltip = tr("psdGuide.binding.resetLayer"),
                            size = 22.dp,
                        ) {
                            IconReset(
                                modifier = Modifier.size(12.dp),
                                tint = if (!state.busy) colors.textPrimary else colors.textDisabled,
                            )
                        }
                    }
                    if (index == 0 && (canStepPrev || canStepNext)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            CompactIconButton(
                                onClick = onStepPrev,
                                enabled = !state.busy && canStepPrev,
                                tooltip = tr("psdGuide.binding.prevItem"),
                                size = 22.dp,
                            ) {
                                IconChevron(
                                    expanded = false,
                                    tint = if (!state.busy && canStepPrev) colors.textPrimary else colors.textDisabled,
                                    modifier = Modifier.size(10.dp).rotate(-90f),
                                )
                            }
                            CompactIconButton(
                                onClick = onStepNext,
                                enabled = !state.busy && canStepNext,
                                tooltip = tr("psdGuide.binding.nextItem"),
                                size = 22.dp,
                            ) {
                                IconChevron(
                                    expanded = false,
                                    tint = if (!state.busy && canStepNext) colors.textPrimary else colors.textDisabled,
                                    modifier = Modifier.size(10.dp).rotate(90f),
                                )
                            }
                        }
                    }
                }

                if (findingForLayer != null) {
                    val reason = tr("psdGuide.binding.${findingForLayer.kind.name.lowercase()}", partLabel(findingForLayer.tag))
                    val reasonColor = when (findingForLayer.kind) {
                        PsdGuideBindings.Kind.SIDE,
                        PsdGuideBindings.Kind.CONFLICT,
                        PsdGuideBindings.Kind.DUPLICATE,
                        -> colors.warning
                        PsdGuideBindings.Kind.MISSING -> colors.error
                        PsdGuideBindings.Kind.UNKNOWN -> colors.textMuted
                    }
                    Text(
                        text = reason,
                        style = typography.caption,
                        color = reasonColor,
                    )
                }

                InspectorFormRow(tr("psdGuide.binding.part"), labelWidth = 72.dp) {
                    CompactSelect(
                        options = SemanticTag.entries,
                        selected = value.tag,
                        label = ::partLabel,
                        onSelect = { tag ->
                            state.classify(id, value.copy(tag = tag, role = value.role?.takeIf { it.fits(tag) }))
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                InspectorFormRow(tr("psdGuide.binding.sideLabel"), labelWidth = 72.dp) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corners.Control))
                            .background(colors.inputBackground)
                            .border(1.dp, colors.divider, RoundedCornerShape(Corners.Control))
                            .padding(2.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        GlidingSegments(
                            choices = Side.entries,
                            selected = value.side,
                            onSelect = { side -> state.classify(id, value.copy(side = side)) },
                            label = ::sideLabel,
                            enabled = !state.busy,
                            modifier = Modifier.handCursor(!state.busy),
                        )
                    }
                }
            }
        }
    }
}
