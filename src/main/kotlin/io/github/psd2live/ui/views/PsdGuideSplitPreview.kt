package io.github.psd2live.ui.views

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.Cmo3ModelImport
import io.github.psd2live.core.LayerTags
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.BatchSplitItemState
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.PsdGuideState
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.buttonBindingOf
import io.github.psd2live.ui.state.wheelBindingOf
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.utils.toImageBitmapFast
import io.github.psd2live.ui.views.texture.canvasChrome
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun splitPartPalette(colors: ToolColors): List<Color> =
    listOf(colors.accent, colors.highlight, colors.warning)

internal fun splitSideShortLabel(side: Side): String? = when (side) {
    Side.LEFT -> tagLabel(LayerTags.LEFT)
    Side.RIGHT -> tagLabel(LayerTags.RIGHT)
    Side.NONE -> null
}

/** Precomputed per-component mesh triangles and outer boundary edges in source canvas coordinates. */
private class SplitComponentMesh(
    val triangles: FloatArray,
    val boundaryEdges: FloatArray,
)

private fun buildSplitComponentMeshes(
    model: RigPreviewModel,
    item: BatchSplitItemState,
): List<SplitComponentMesh> {
    val id = item.offer.layerId
    val count = item.count
    if (count <= 0) return emptyList()
    val drawable = model.rig.puppet.drawables.firstOrNull {
        it.id.raw == id || model.rig.ownLayerIdByDrawableId[it.id.raw] == id
    } ?: return emptyList()
    val mesh = drawable.mesh ?: return emptyList()
    val canvasCoords = Cmo3ModelImport.textureCanvas(model, drawable)
    val owners = item.offer.plan.ownerByVertex
    if (owners.size < mesh.vertexCount || canvasCoords.size < mesh.vertexCount * 2) return emptyList()

    val triLists = Array(count) { ArrayList<Float>() }
    val edgeCounts = Array(count) { HashMap<Long, Int>() }

    fun addEdge(map: HashMap<Long, Int>, u: Int, v: Int) {
        val lo = minOf(u, v).toLong()
        val hi = maxOf(u, v).toLong()
        val key = (lo shl 32) or (hi and 0xffffffffL)
        map[key] = (map[key] ?: 0) + 1
    }

    val indices = mesh.indices
    for (t in 0 until indices.size - 2 step 3) {
        val a = indices[t]
        val b = indices[t + 1]
        val c = indices[t + 2]
        val comp = owners[a]
        if (comp !in 0 until count) continue
        val tris = triLists[comp]
        tris.add(canvasCoords[a * 2])
        tris.add(canvasCoords[a * 2 + 1])
        tris.add(canvasCoords[b * 2])
        tris.add(canvasCoords[b * 2 + 1])
        tris.add(canvasCoords[c * 2])
        tris.add(canvasCoords[c * 2 + 1])
        val edges = edgeCounts[comp]
        addEdge(edges, a, b)
        addEdge(edges, b, c)
        addEdge(edges, c, a)
    }

    return List(count) { comp ->
        val edges = ArrayList<Float>()
        edgeCounts[comp].forEach { (key, freq) ->
            if (freq == 1) {
                val u = (key ushr 32).toInt()
                val v = (key and 0xffffffffL).toInt()
                edges.add(canvasCoords[u * 2])
                edges.add(canvasCoords[u * 2 + 1])
                edges.add(canvasCoords[v * 2])
                edges.add(canvasCoords[v * 2 + 1])
            }
        }
        SplitComponentMesh(triLists[comp].toFloatArray(), edges.toFloatArray())
    }
}

/**
 * Full-image camera viewport: maps source-image coordinates to canvas pixels without cropping
 * the surrounding artwork, enabling smooth animated framing and consistent pan/zoom gestures.
 */
private data class SplitCameraViewport(
    val canvasSize: IntSize,
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
) {
    fun toCanvas(x: Float, y: Float): Offset = Offset(
        canvasSize.width * 0.5f + (x - centerX) * scale,
        canvasSize.height * 0.5f + (y - centerY) * scale,
    )

    fun toImage(pos: Offset): Offset = Offset(
        centerX + (pos.x - canvasSize.width * 0.5f) / scale,
        centerY + (pos.y - canvasSize.height * 0.5f) / scale,
    )

    fun toCanvasRect(bounds: Bounds): Rect {
        val topLeft = toCanvas(bounds.left, bounds.top)
        return Rect(topLeft, Size(bounds.width * scale, bounds.height * scale))
    }
}

private data class SplitCameraTarget(
    val centerX: Float,
    val centerY: Float,
    val zoom: Float,
)

private fun computeBaseScale(
    canvasSize: IntSize,
    characterBounds: Bounds,
    imageWidth: Int,
    imageHeight: Int,
    insetPx: Float,
): Float {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return 1f
    val w = characterBounds.width.coerceAtLeast(1f)
    val h = characterBounds.height.coerceAtLeast(1f)
    val availW = (canvasSize.width - 2f * insetPx).coerceAtLeast(1f)
    val availH = (canvasSize.height - 2f * insetPx).coerceAtLeast(1f)
    return minOf(availW / w, availH / h)
}

private fun computeFocusTarget(
    canvasSize: IntSize,
    baseScale: Float,
    bounds: Bounds,
    insetPx: Float,
): SplitCameraTarget {
    val cx = (bounds.left + bounds.right) * 0.5f
    val cy = (bounds.top + bounds.bottom) * 0.5f
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || baseScale <= 0f) {
        return SplitCameraTarget(cx, cy, 1f)
    }
    val margin = maxOf(bounds.width, bounds.height, 44f) * 0.55f
    val framedW = (bounds.width + 2f * margin).coerceAtLeast(1f)
    val framedH = (bounds.height + 2f * margin).coerceAtLeast(1f)
    val availW = (canvasSize.width - 2f * insetPx).coerceAtLeast(1f)
    val availH = (canvasSize.height - 2f * insetPx).coerceAtLeast(1f)
    val targetScale = minOf(availW / framedW, availH / framedH)
    val targetZoom = (targetScale / baseScale).coerceIn(1f, 16f)
    return SplitCameraTarget(cx, cy, targetZoom)
}

private fun hitTestSplitLayer(
    position: Offset,
    viewport: SplitCameraViewport?,
    candidates: List<BatchSplitItemState>,
): BatchSplitItemState? {
    viewport ?: return null
    val hits = candidates.mapNotNull { item ->
        val compBounds = item.offer.plan.componentBounds
        if (compBounds.isEmpty()) return@mapNotNull null
        val matched = compBounds.filter { viewport.toCanvasRect(it).contains(position) }
        if (matched.isNotEmpty()) {
            item to matched.minOf { it.width * it.height }
        } else {
            null
        }
    }
    return hits.minByOrNull { it.second }?.first
}

/**
 * Interactive full-canvas preview for the Split step:
 * - Renders the full source artwork so zooming in on a layer keeps surrounding context visible.
 * - Reuses [CanvasNavigation] + [Keymap] for wheel zoom, pan drag, zoom drag, Spacebar pan, and zoom shortcuts.
 * - Selecting a layer smoothly animates camera framing and tints its real mesh triangles + outer contour.
 * - Panning away or zooming far from the focused layer automatically clears selection without snapping back.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SplitGuidePreview(
    state: PsdGuideState,
    source: WorkspacePsdGuide.Candidate,
    keymap: Keymap,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val insetPx = with(LocalDensity.current) { 14.dp.toPx() }
    val focusRequester = remember { FocusRequester() }

    val candidates = state.splitItems
    val baseModel = state.guide?.base?.model ?: source.model
    val analysis = baseModel.analysis
    val image = remember(analysis.preview) { analysis.preview.toImageBitmapFast() }
    val characterBounds = analysis.anchors.character
    val defaultCenterX = (characterBounds.left + characterBounds.right) * 0.5f
    val defaultCenterY = (characterBounds.top + characterBounds.bottom) * 0.5f

    val meshesByLayerId = remember(baseModel, candidates) {
        candidates.associate { it.offer.layerId to buildSplitComponentMeshes(baseModel, it) }
    }

    val focusedItem = remember(candidates, state.splitFocus) {
        state.splitFocus?.let { id -> candidates.firstOrNull { it.offer.layerId == id } }
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var hoveredLayerId by remember { mutableStateOf<String?>(null) }

    // Camera state in source-image coordinates + relative zoom (1f = full character overview).
    val camCenterX = remember(baseModel) { Animatable(defaultCenterX) }
    val camCenterY = remember(baseModel) { Animatable(defaultCenterY) }
    val camZoom = remember(baseModel) { Animatable(1f) }
    var cameraAnimJob by remember { mutableStateOf<Job?>(null) }
    var skipNextResetAnimation by remember { mutableStateOf(false) }
    var focusedTarget by remember { mutableStateOf<SplitCameraTarget?>(null) }
    var initializedSize by remember(baseModel) { mutableStateOf(false) }

    // Pointer / keyboard navigation state matching AtlasPageView & main canvas.
    var spaceHeld by remember { mutableStateOf(false) }
    var panFrom by remember { mutableStateOf<Offset?>(null) }
    var panButton by remember { mutableStateOf<PointerButton?>(null) }
    var zoomDrag by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
    var primaryDownAt by remember { mutableStateOf<Offset?>(null) }
    var primaryDragged by remember { mutableStateOf(false) }

    val baseScale = remember(canvasSize, characterBounds, image.width, image.height, insetPx) {
        computeBaseScale(canvasSize, characterBounds, image.width, image.height, insetPx)
    }

    fun currentViewport(): SplitCameraViewport? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        return SplitCameraViewport(
            canvasSize = canvasSize,
            centerX = camCenterX.value,
            centerY = camCenterY.value,
            scale = (baseScale * camZoom.value).coerceAtLeast(1e-4f),
        )
    }

    fun cancelCameraAnimation() {
        cameraAnimJob?.cancel()
        cameraAnimJob = null
    }

    fun animateCameraTo(target: SplitCameraTarget, snap: Boolean = false) {
        cancelCameraAnimation()
        cameraAnimJob = scope.launch {
            if (snap) {
                camCenterX.snapTo(target.centerX)
                camCenterY.snapTo(target.centerY)
                camZoom.snapTo(target.zoom)
            } else {
                val spec = tween<Float>(Motion.Fold, easing = FastOutSlowInEasing)
                coroutineScope {
                    launch { camCenterX.animateTo(target.centerX, spec) }
                    launch { camCenterY.animateTo(target.centerY, spec) }
                    launch { camZoom.animateTo(target.zoom, spec) }
                }
            }
        }
    }

    fun checkAutoDeselect() {
        val focused = focusedItem ?: return
        val target = focusedTarget ?: return
        val vp = currentViewport() ?: return
        val compBounds = focused.offer.plan.componentBounds
        if (compBounds.isEmpty()) return
        val union = compBounds.reduce(Bounds::union)
        val unionRect = vp.toCanvasRect(union)
        val viewCenter = Offset(canvasSize.width * 0.5f, canvasSize.height * 0.5f)
        val dx = abs(unionRect.center.x - viewCenter.x)
        val dy = abs(unionRect.center.y - viewCenter.y)
        val maxDx = maxOf(canvasSize.width * 0.44f, unionRect.width * 0.70f)
        val maxDy = maxOf(canvasSize.height * 0.44f, unionRect.height * 0.70f)
        val zoomOutTooFar = camZoom.value < target.zoom * 0.55f
        val zoomInTooFar = camZoom.value > target.zoom * 2.6f
        val pannedAway = dx > maxDx || dy > maxDy
        if (zoomOutTooFar || zoomInTooFar || pannedAway) {
            skipNextResetAnimation = true
            state.showSplitPreview(null)
        }
    }

    fun zoomAbout(anchorScreen: Offset, wheelDelta: Float) {
        val vp = currentViewport() ?: return
        cancelCameraAnimation()
        val oldZoom = camZoom.value.toDouble()
        val nextZoom = CanvasNavigation.wheelZoom(oldZoom, wheelDelta).toFloat()
        if (nextZoom == camZoom.value) return
        val anchorImage = vp.toImage(anchorScreen)
        val nextScale = (baseScale * nextZoom).coerceAtLeast(1e-4f)
        val nextCenterX = anchorImage.x - (anchorScreen.x - canvasSize.width * 0.5f) / nextScale
        val nextCenterY = anchorImage.y - (anchorScreen.y - canvasSize.height * 0.5f) / nextScale
        scope.launch {
            camZoom.snapTo(nextZoom)
            camCenterX.snapTo(nextCenterX)
            camCenterY.snapTo(nextCenterY)
            checkAutoDeselect()
        }
    }

    fun panBy(deltaScreen: Offset) {
        val vp = currentViewport() ?: return
        cancelCameraAnimation()
        val nextCenterX = camCenterX.value - deltaScreen.x / vp.scale
        val nextCenterY = camCenterY.value - deltaScreen.y / vp.scale
        scope.launch {
            camCenterX.snapTo(nextCenterX)
            camCenterY.snapTo(nextCenterY)
            checkAutoDeselect()
        }
    }

    fun runSplitNavCommand(action: ShortcutAction?): Boolean {
        val center = Offset(canvasSize.width * 0.5f, canvasSize.height * 0.5f)
        return when (action) {
            ShortcutAction.ZOOM_IN -> { zoomAbout(center, -1f); true }
            ShortcutAction.ZOOM_OUT -> { zoomAbout(center, 1f); true }
            ShortcutAction.CANCEL -> {
                if (state.splitFocus != null) {
                    state.showSplitPreview(null)
                    true
                } else false
            }
            ShortcutAction.FRAME_VIEW -> {
                val focused = focusedItem
                if (focused != null && focused.offer.plan.componentBounds.isNotEmpty()) {
                    val union = focused.offer.plan.componentBounds.reduce(Bounds::union)
                    animateCameraTo(computeFocusTarget(canvasSize, baseScale, union, insetPx))
                } else {
                    animateCameraTo(SplitCameraTarget(defaultCenterX, defaultCenterY, 1f))
                }
                true
            }
            ShortcutAction.RESET_CAMERA, ShortcutAction.ZOOM_RESET -> {
                if (state.splitFocus != null) {
                    state.showSplitPreview(null)
                } else {
                    animateCameraTo(SplitCameraTarget(defaultCenterX, defaultCenterY, 1f))
                }
                true
            }
            else -> false
        }
    }

    // Animate camera framing whenever selection changes (or snap on initial layout).
    LaunchedEffect(focusedItem?.offer?.layerId, canvasSize, baseScale) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val firstLayout = !initializedSize
        initializedSize = true
        val focused = focusedItem
        if (focused != null && focused.offer.plan.componentBounds.isNotEmpty()) {
            skipNextResetAnimation = false
            val union = focused.offer.plan.componentBounds.reduce(Bounds::union)
            val target = computeFocusTarget(canvasSize, baseScale, union, insetPx)
            focusedTarget = target
            animateCameraTo(target, snap = firstLayout)
        } else {
            focusedTarget = null
            if (skipNextResetAnimation) {
                skipNextResetAnimation = false
            } else {
                animateCameraTo(SplitCameraTarget(defaultCenterX, defaultCenterY, 1f), snap = firstLayout)
            }
        }
    }

    val currentCandidates by rememberUpdatedState(candidates)
    val currentBusy by rememberUpdatedState(state.busy)
    val currentFocusedId by rememberUpdatedState(focusedItem?.offer?.layerId)
    val currentKeymap by rememberUpdatedState(keymap)
    val currentSpace by rememberUpdatedState(spaceHeld)

    val hoveredItem = remember(hoveredLayerId, candidates) {
        hoveredLayerId?.let { id -> candidates.firstOrNull { it.offer.layerId == id } }
    }
    val statusItem = hoveredItem ?: focusedItem
    val statusText = when {
        statusItem != null -> {
            if (!statusItem.isSelected) {
                "${statusItem.offer.layerName} · ${tr("psdGuide.split.keepShort")}"
            } else {
                val (names, sides) = statusItem.generatedNamesAndSides()
                val targets = names.mapIndexed { idx, name ->
                    val side = splitSideShortLabel(sides.getOrElse(idx) { Side.NONE })
                    if (side != null) "$name ($side)" else name
                }.joinToString(" / ")
                "${statusItem.offer.layerName} → $targets"
            }
        }
        candidates.isNotEmpty() -> tr("psdGuide.split.overviewHint", candidates.size)
        else -> null
    }

    val palette = splitPartPalette(colors)
    val hoveredComponent = state.splitHoveredComponent
    val grabbingCursor = remember { PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)) }

    GuideCanvasFrame(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    if (event.key == Key.Spacebar) {
                        spaceHeld = event.type == KeyEventType.KeyDown
                        return@onKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    runSplitNavCommand(currentKeymap.match(event, ShortcutScope.CANVAS))
                }
                .focusable()
                .onSizeChanged { canvasSize = it }
                .then(
                    if (panFrom != null || spaceHeld) Modifier.pointerHoverIcon(grabbingCursor)
                    else Modifier.handCursor(hoveredLayerId != null && !state.busy),
                )
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val notch = wheelBindingOf(change.scrollDelta, event.keyboardModifiers)
                    if (runSplitNavCommand(currentKeymap.mouseCommand(notch, ShortcutScope.CANVAS))) {
                        change.consume()
                        return@onPointerEvent
                    }
                    val delta = if (change.scrollDelta.y != 0f) change.scrollDelta.y else change.scrollDelta.x
                    if (delta == 0f) return@onPointerEvent
                    zoomAbout(change.position, delta)
                    change.consume()
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    focusRequester.requestFocus()
                    val pressed = buttonBindingOf(event.button, event.keyboardModifiers)
                    val gesture = currentKeymap.gesture(pressed)
                    if (CanvasNavigation.pans(gesture, event.button, currentSpace)) {
                        panFrom = change.position
                        panButton = event.button
                        change.consume()
                    } else if (gesture == ShortcutAction.ZOOM_DRAG) {
                        zoomDrag = change.position to change.position.y
                        panButton = event.button
                        change.consume()
                    } else if (runSplitNavCommand(currentKeymap.mouseCommand(pressed, ShortcutScope.CANVAS))) {
                        change.consume()
                    } else if (event.button == PointerButton.Primary && !currentBusy) {
                        primaryDownAt = change.position
                        primaryDragged = false
                        change.consume()
                    }
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    zoomDrag?.let { (anchor, lastY) ->
                        zoomAbout(anchor, (change.position.y - lastY) / (24f * density))
                        zoomDrag = anchor to change.position.y
                        change.consume()
                        return@onPointerEvent
                    }
                    val from = panFrom
                    if (from != null) {
                        panBy(change.position - from)
                        panFrom = change.position
                        change.consume()
                        return@onPointerEvent
                    }
                    val down = primaryDownAt
                    if (down != null && (change.position - down).getDistance() > 4f * density) {
                        primaryDragged = true
                    }
                    hoveredLayerId = if (!currentBusy) {
                        hitTestSplitLayer(change.position, currentViewport(), currentCandidates)?.offer?.layerId
                    } else null
                }
                .onPointerEvent(PointerEventType.Release) { event ->
                    val change = event.changes.firstOrNull()
                    if (panButton != null && (event.button == null || event.button == panButton)) {
                        panFrom = null
                        zoomDrag = null
                        panButton = null
                        change?.consume()
                        return@onPointerEvent
                    }
                    val down = primaryDownAt
                    if (down != null && event.button == PointerButton.Primary) {
                        val dragged = primaryDragged
                        primaryDownAt = null
                        primaryDragged = false
                        if (!dragged && !currentBusy && change != null) {
                            val hit = hitTestSplitLayer(change.position, currentViewport(), currentCandidates)
                            if (hit != null) {
                                state.showSplitPreview(hit.offer.layerId)
                            } else if (currentFocusedId != null) {
                                state.showSplitPreview(null)
                            }
                        }
                        change?.consume()
                    }
                }
                .onPointerEvent(PointerEventType.Exit) {
                    hoveredLayerId = null
                    if (panFrom == null && zoomDrag == null) {
                        primaryDownAt = null
                        primaryDragged = false
                    }
                },
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas

            drawGuideChecker(light = colors.checkerLight, dark = colors.checkerDark)
            val vp = SplitCameraViewport(
                canvasSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                centerX = camCenterX.value,
                centerY = camCenterY.value,
                scale = (baseScale * camZoom.value).coerceAtLeast(1e-4f),
            )

            // Always draw the full source image so zoomed-in focus preserves full surrounding artwork.
            val imgTopLeft = vp.toCanvas(0f, 0f)
            val imgDstSize = IntSize(
                (image.width * vp.scale).roundToInt().coerceAtLeast(1),
                (image.height * vp.scale).roundToInt().coerceAtLeast(1),
            )
            clipRect {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(imgTopLeft.x.roundToInt(), imgTopLeft.y.roundToInt()),
                    dstSize = imgDstSize,
                    filterQuality = FilterQuality.High,
                )

                if (candidates.isEmpty()) return@clipRect

                val dashLink = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                val dashKeep = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                val placed = mutableListOf<Rect>()
                val edge = 6.dp.toPx()
                val gap = 6.dp.toPx()
                val padH = 6.dp.toPx()
                val padV = 3.dp.toPx()
                val dotRadius = 3.dp.toPx()
                val dotGap = 5.dp.toPx()
                val corner = CornerRadius(Corners.Control.toPx())
                val maxLabelWidth = (size.width * 0.46f).roundToInt().coerceAtLeast(1)

                fun drawComponentPills(
                    item: BatchSplitItemState,
                    rects: List<Rect>,
                    activeHoverIdx: Int?,
                ) {
                    val (names, sides) = item.generatedNamesAndSides()
                    val invalid = item.invalidIndices
                    rects.forEachIndexed { idx, rect ->
                        val isError = idx in invalid
                        val baseColor = if (isError) colors.error else palette[idx % palette.size]
                        val isHighlighted = activeHoverIdx == idx
                        val isDimmed = activeHoverIdx != null && activeHoverIdx != idx
                        val rawName = names.getOrElse(idx) { "" }.ifBlank { "#${idx + 1}" }
                        val sideSuffix = splitSideShortLabel(sides.getOrElse(idx) { Side.NONE })
                        val text = if (sideSuffix != null) "$rawName · $sideSuffix" else rawName
                        val measured = measurer.measure(
                            text = text,
                            style = typography.captionSmall.copy(
                                color = if (isError) colors.error else colors.textPrimary,
                                fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = maxLabelWidth),
                        )
                        val pillSize = Size(
                            padH + dotRadius * 2f + dotGap + measured.size.width + padH,
                            measured.size.height + 2f * padV,
                        )
                        val pillRect = placeLabel(rect, rects, placed, pillSize, size, edge, gap)
                        placed += pillRect

                        val anchor = closestPointOnRect(pillRect, rect.center)
                        val targetEdge = closestPointOnRect(rect, anchor)
                        if ((anchor - targetEdge).getDistance() > 2.dp.toPx()) {
                            drawLine(
                                color = baseColor.copy(alpha = if (isDimmed) 0.3f else 0.72f),
                                start = anchor,
                                end = targetEdge,
                                strokeWidth = 1.dp.toPx(),
                            )
                        }

                        val pillAlpha = if (isDimmed) 0.65f else 0.94f
                        drawRoundRect(colors.panelElevated.copy(alpha = pillAlpha), pillRect.topLeft, pillRect.size, corner)
                        drawRoundRect(
                            baseColor.copy(alpha = if (isHighlighted) 1f else if (isDimmed) 0.35f else 0.78f),
                            pillRect.topLeft,
                            pillRect.size,
                            corner,
                            style = Stroke(if (isHighlighted) 1.25.dp.toPx() else 0.75.dp.toPx()),
                        )
                        drawCircle(
                            color = baseColor.copy(alpha = if (isDimmed) 0.45f else 1f),
                            radius = dotRadius,
                            center = Offset(pillRect.left + padH + dotRadius, pillRect.center.y),
                        )
                        drawText(
                            measured,
                            topLeft = Offset(pillRect.left + padH + dotRadius * 2f + dotGap, pillRect.top + padV),
                        )
                    }
                }

                if (focusedItem == null) {
                    val labelTarget = hoveredItem
                        ?: hoveredComponent?.first?.let { id -> candidates.firstOrNull { it.offer.layerId == id } }
                        ?: candidates.singleOrNull { it.isSelected }

                    candidates.forEach { item ->
                        val compBounds = item.offer.plan.componentBounds
                        if (compBounds.isEmpty()) return@forEach
                        val compMeshes = meshesByLayerId[item.offer.layerId].orEmpty()
                        val isHovered = !state.busy && hoveredLayerId == item.offer.layerId
                        val hoverCompIdx = hoveredComponent?.takeIf { it.first == item.offer.layerId }?.second
                        val rects = compBounds.map(vp::toCanvasRect)
                        val invalid = item.invalidIndices

                        if (item.isSelected) {
                            drawComponentLinks(rects, colors.textMuted.copy(alpha = if (isHovered) 0.65f else 0.40f), dashLink)
                            rects.forEachIndexed { idx, rect ->
                                val baseColor = if (idx in invalid) colors.error else palette[idx % palette.size]
                                val isCompHighlighted = hoverCompIdx == idx
                                val isCompDimmed = hoverCompIdx != null && hoverCompIdx != idx
                                val fillAlpha = when {
                                    isCompHighlighted -> 0.46f
                                    isCompDimmed -> 0.10f
                                    isHovered -> 0.36f
                                    else -> 0.28f
                                }
                                val strokeAlpha = when {
                                    isCompDimmed -> 0.35f
                                    isCompHighlighted || isHovered -> 1f
                                    else -> 0.85f
                                }
                                val strokeWidth = if (isCompHighlighted || isHovered) 1.75.dp.toPx() else 1.25.dp.toPx()
                                drawSplitComponentTint(
                                    mesh = compMeshes.getOrNull(idx),
                                    fallbackRect = rect,
                                    viewport = vp,
                                    fillColor = baseColor.copy(alpha = fillAlpha),
                                    contourColor = baseColor.copy(alpha = strokeAlpha),
                                    contourWidth = strokeWidth,
                                )
                            }
                        } else if (isHovered) {
                            rects.forEachIndexed { idx, rect ->
                                drawSplitComponentTint(
                                    mesh = compMeshes.getOrNull(idx),
                                    fallbackRect = rect,
                                    viewport = vp,
                                    fillColor = colors.textMuted.copy(alpha = 0.14f),
                                    contourColor = colors.textMuted.copy(alpha = 0.75f),
                                    contourWidth = 1.dp.toPx(),
                                    pathEffect = dashKeep,
                                )
                            }
                        }
                    }

                    if (labelTarget != null && labelTarget.isSelected) {
                        val rects = labelTarget.offer.plan.componentBounds.map(vp::toCanvasRect)
                        val activeHoverIdx = hoveredComponent?.takeIf { it.first == labelTarget.offer.layerId }?.second
                        drawComponentPills(labelTarget, rects, activeHoverIdx)
                    }
                } else {
                    // Draw subtle context contours for other selected items in view.
                    candidates.forEach { other ->
                        if (other.offer.layerId == focusedItem.offer.layerId || !other.isSelected) return@forEach
                        val otherBounds = other.offer.plan.componentBounds
                        if (otherBounds.isEmpty()) return@forEach
                        val otherMeshes = meshesByLayerId[other.offer.layerId].orEmpty()
                        otherBounds.forEachIndexed { idx, b ->
                            val baseColor = palette[idx % palette.size]
                            drawSplitComponentTint(
                                mesh = otherMeshes.getOrNull(idx),
                                fallbackRect = vp.toCanvasRect(b),
                                viewport = vp,
                                fillColor = baseColor.copy(alpha = 0.08f),
                                contourColor = baseColor.copy(alpha = 0.30f),
                                contourWidth = 1.dp.toPx(),
                            )
                        }
                    }

                    val compBounds = focusedItem.offer.plan.componentBounds
                    if (compBounds.isEmpty()) return@clipRect
                    val compMeshes = meshesByLayerId[focusedItem.offer.layerId].orEmpty()
                    val rects = compBounds.map(vp::toCanvasRect)

                    if (!focusedItem.isSelected) {
                        rects.forEachIndexed { idx, rect ->
                            drawSplitComponentTint(
                                mesh = compMeshes.getOrNull(idx),
                                fallbackRect = rect,
                                viewport = vp,
                                fillColor = colors.textMuted.copy(alpha = 0.16f),
                                contourColor = colors.textMuted.copy(alpha = 0.82f),
                                contourWidth = 1.25.dp.toPx(),
                                pathEffect = dashKeep,
                            )
                        }
                        val unionRect = vp.toCanvasRect(compBounds.reduce(Bounds::union))
                        val label = measurer.measure(
                            text = "${focusedItem.offer.layerName} · ${tr("psdGuide.split.keepShort")}",
                            style = typography.captionSmall.copy(color = colors.textMuted, fontWeight = FontWeight.Medium),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = maxLabelWidth),
                        )
                        val pillSize = Size(label.size.width + 2f * padH, label.size.height + 2f * padV)
                        val pillRect = placeLabel(unionRect, listOf(unionRect), emptyList(), pillSize, size, edge, gap)
                        drawRoundRect(colors.panelElevated.copy(alpha = 0.94f), pillRect.topLeft, pillRect.size, corner)
                        drawRoundRect(colors.border, pillRect.topLeft, pillRect.size, corner, style = Stroke(0.75.dp.toPx()))
                        drawText(label, topLeft = pillRect.topLeft + Offset(padH, padV))
                    } else {
                        val invalid = focusedItem.invalidIndices
                        val activeHoverIdx = hoveredComponent?.takeIf { it.first == focusedItem.offer.layerId }?.second
                        drawComponentLinks(rects, colors.textMuted.copy(alpha = 0.55f), dashLink)
                        rects.forEachIndexed { idx, rect ->
                            val isError = idx in invalid
                            val baseColor = if (isError) colors.error else palette[idx % palette.size]
                            val isHighlighted = activeHoverIdx == idx
                            val isDimmed = activeHoverIdx != null && activeHoverIdx != idx
                            val fillAlpha = when {
                                isHighlighted -> 0.48f
                                isDimmed -> 0.12f
                                else -> 0.34f
                            }
                            val strokeAlpha = if (isDimmed) 0.40f else 1f
                            val strokeWidth = if (isHighlighted) 2.25.dp.toPx() else 1.5.dp.toPx()
                            drawSplitComponentTint(
                                mesh = compMeshes.getOrNull(idx),
                                fallbackRect = rect,
                                viewport = vp,
                                fillColor = baseColor.copy(alpha = fillAlpha),
                                contourColor = baseColor.copy(alpha = strokeAlpha),
                                contourWidth = strokeWidth,
                            )
                        }
                        drawComponentPills(focusedItem, rects, activeHoverIdx)
                    }
                }
            }
        }

        if (statusText != null) {
            GuideStatusCapsule(
                text = statusText,
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.Margin).canvasChrome(),
            )
        }
    }
}

/**
 * Tints the actual mesh triangles and draws only the outer boundary edges of the component,
 * matching the editor's mesh-selection highlight style. Falls back to rounded bounds if mesh data is absent.
 */
private fun DrawScope.drawSplitComponentTint(
    mesh: SplitComponentMesh?,
    fallbackRect: Rect,
    viewport: SplitCameraViewport,
    fillColor: Color,
    contourColor: Color,
    contourWidth: Float,
    pathEffect: PathEffect? = null,
) {
    if (mesh == null || mesh.triangles.isEmpty()) {
        val corner = CornerRadius(Corners.Control.toPx())
        drawRoundRect(fillColor, fallbackRect.topLeft, fallbackRect.size, corner)
        drawRoundRect(contourColor, fallbackRect.topLeft, fallbackRect.size, corner, style = Stroke(contourWidth, pathEffect = pathEffect))
        return
    }
    val tris = mesh.triangles
    val path = Path()
    var i = 0
    while (i + 5 < tris.size) {
        val a = viewport.toCanvas(tris[i], tris[i + 1])
        val b = viewport.toCanvas(tris[i + 2], tris[i + 3])
        val c = viewport.toCanvas(tris[i + 4], tris[i + 5])
        path.moveTo(a.x, a.y)
        path.lineTo(b.x, b.y)
        path.lineTo(c.x, c.y)
        path.close()
        i += 6
    }
    drawPath(path, fillColor)

    val edges = mesh.boundaryEdges
    var e = 0
    while (e + 3 < edges.size) {
        val p1 = viewport.toCanvas(edges[e], edges[e + 1])
        val p2 = viewport.toCanvas(edges[e + 2], edges[e + 3])
        drawLine(
            color = contourColor,
            start = p1,
            end = p2,
            strokeWidth = contourWidth,
            cap = StrokeCap.Round,
            pathEffect = pathEffect,
        )
        e += 4
    }
}

private fun DrawScope.drawComponentLinks(rects: List<Rect>, color: Color, pathEffect: PathEffect) {
    if (rects.size < 2) return
    val sorted = rects.sortedWith(compareBy<Rect> { it.center.x }.thenBy { it.center.y })
    for (i in 0 until sorted.lastIndex) {
        val a = sorted[i]
        val b = sorted[i + 1]
        val start = closestPointOnRect(a, b.center)
        val end = closestPointOnRect(b, a.center)
        if ((end - start).getDistance() > 2f) {
            drawLine(color = color, start = start, end = end, strokeWidth = 1.dp.toPx(), pathEffect = pathEffect)
        }
    }
}

private fun closestPointOnRect(rect: Rect, point: Offset): Offset = Offset(
    point.x.coerceIn(rect.left, rect.right),
    point.y.coerceIn(rect.top, rect.bottom),
)

private fun placeLabel(
    regionRect: Rect,
    allRegions: List<Rect>,
    placed: List<Rect>,
    labelSize: Size,
    canvasSize: Size,
    edge: Float,
    gap: Float,
): Rect {
    val maxX = (canvasSize.width - labelSize.width - edge).coerceAtLeast(edge)
    val maxY = (canvasSize.height - labelSize.height - edge).coerceAtLeast(edge)
    fun clamp(x: Float, y: Float) = Rect(Offset(x.coerceIn(edge, maxX), y.coerceIn(edge, maxY)), labelSize)

    val primary = listOf(
        clamp(regionRect.center.x - labelSize.width * 0.5f, regionRect.top - labelSize.height - gap),
        clamp(regionRect.center.x - labelSize.width * 0.5f, regionRect.bottom + gap),
        clamp(regionRect.left, regionRect.top - labelSize.height - gap),
        clamp(regionRect.left, regionRect.bottom + gap),
        clamp(regionRect.right - labelSize.width, regionRect.top - labelSize.height - gap),
        clamp(regionRect.right - labelSize.width, regionRect.bottom + gap),
        clamp(regionRect.right + gap, regionRect.top),
        clamp(regionRect.left - labelSize.width - gap, regionRect.top),
    )
    val fallback = listOf(
        clamp(regionRect.left + gap, regionRect.top + gap),
        clamp(regionRect.left + gap, regionRect.bottom - labelSize.height - gap),
    )
    val nudges = placed.flatMap { other ->
        listOf(
            clamp(other.right + gap, other.top),
            clamp(other.left - labelSize.width - gap, other.top),
            clamp(other.left, other.bottom + gap),
            clamp(other.left, other.top - labelSize.height - gap),
        )
    }

    fun overlapsAny(rect: Rect, others: List<Rect>, padding: Float): Boolean {
        val padded = Rect(rect.left - padding, rect.top - padding, rect.right + padding, rect.bottom + padding)
        return others.any { it.overlaps(padded) }
    }

    return primary.firstOrNull { !overlapsAny(it, placed, gap) && !overlapsAny(it, allRegions, 0f) }
        ?: primary.firstOrNull { !overlapsAny(it, placed, gap) }
        ?: nudges.firstOrNull { !overlapsAny(it, placed, gap) && !overlapsAny(it, allRegions, 0f) }
        ?: nudges.firstOrNull { !overlapsAny(it, placed, gap) }
        ?: fallback.firstOrNull { !overlapsAny(it, placed, gap) }
        ?: (primary + nudges + fallback).minBy { candidate ->
            placed.sumOf { overlapArea(candidate, it).toDouble() }
        }
}

private fun overlapArea(a: Rect, b: Rect): Float {
    val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
    val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    return w * h
}
