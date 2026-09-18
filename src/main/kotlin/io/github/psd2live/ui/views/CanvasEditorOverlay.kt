package io.github.psd2live.ui.views

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.DeformPathTools
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.*
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.theme.frostedGlassTopBar
import kotlin.math.abs
import kotlin.math.roundToInt
import org.umamo.edit.MeshTopology

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.CanvasEditorOverlay(
    editor: CanvasEditor,
    viewport: CanvasViewport,
    viewModel: PSD2LiveViewModel,
    keymap: Keymap,
    focus: () -> Unit
) {
    val colors = LocalToolColors.current
    val textMeasurer = rememberTextMeasurer()
    val target = editor.target()
    val isPathTool = editor.tool == CanvasTool.PATH_DEFORM

    Canvas(Modifier.fillMaxSize()) {
        // 1. SELECT tool: Draw object bounding box and 8 handles + rotate stem
        if (editor.tool == CanvasTool.SELECT) {
            val bounds = editor.selectionBounds(viewport)
            if (bounds != null) {
                val rotateAngleDeg = Math.toDegrees(editor.currentRotateAngle.toDouble()).toFloat()
                val rotatePivot = editor.currentRotateCenter ?: Offset(bounds.centerX, bounds.centerY)
                rotate(rotateAngleDeg, rotatePivot) {
                    // Main bounding box rectangle
                    drawRect(
                        color = colors.accent,
                        topLeft = Offset(bounds.minX, bounds.minY),
                        size = Size(bounds.width, bounds.height),
                        style = Stroke(1.2f)
                    )

                    // Rotate handle: stem line + circle
                    drawLine(
                        color = colors.accent.copy(alpha = 0.8f),
                        start = Offset(bounds.centerX, bounds.minY),
                        end = bounds.rotateHandlePos,
                        strokeWidth = 1f
                    )
                    val isRotateHovered = editor.hoveredHandle == BoundingHandle.ROTATE
                    drawCircle(
                        color = if (isRotateHovered) colors.accent else colors.windowBackground,
                        radius = if (isRotateHovered) 5.5f else 4f,
                        center = bounds.rotateHandlePos
                    )
                    drawCircle(
                        color = colors.accent,
                        radius = if (isRotateHovered) 5.5f else 4f,
                        center = bounds.rotateHandlePos,
                        style = Stroke(1.5f)
                    )

                    // Draw 8 transform handles
                    val handles = listOf(
                        BoundingHandle.TOP_LEFT to Offset(bounds.minX, bounds.minY),
                        BoundingHandle.TOP_RIGHT to Offset(bounds.maxX, bounds.minY),
                        BoundingHandle.BOTTOM_LEFT to Offset(bounds.minX, bounds.maxY),
                        BoundingHandle.BOTTOM_RIGHT to Offset(bounds.maxX, bounds.maxY),
                        BoundingHandle.TOP to Offset(bounds.centerX, bounds.minY),
                        BoundingHandle.BOTTOM to Offset(bounds.centerX, bounds.maxY),
                        BoundingHandle.LEFT to Offset(bounds.minX, bounds.centerY),
                        BoundingHandle.RIGHT to Offset(bounds.maxX, bounds.centerY),
                    )
                    handles.forEach { (handle, pt) ->
                        val isCorner = handle in listOf(BoundingHandle.TOP_LEFT, BoundingHandle.TOP_RIGHT, BoundingHandle.BOTTOM_LEFT, BoundingHandle.BOTTOM_RIGHT)
                        val isHovered = editor.hoveredHandle == handle
                        val hs = if (isHovered) 8f else if (isCorner) 7f else 5.5f
                        drawRect(
                            color = if (isHovered) colors.accent else Color.White,
                            topLeft = pt - Offset(hs * 0.5f, hs * 0.5f),
                            size = Size(hs, hs)
                        )
                        drawRect(
                            color = if (isHovered) Color.White else colors.accent,
                            topLeft = pt - Offset(hs * 0.5f, hs * 0.5f),
                            size = Size(hs, hs),
                            style = Stroke(1f)
                        )
                    }
                }
            }
        }

        // 2. MESH tool: Draw triangle wireframe, vertices, and hover halo
        if (editor.tool == CanvasTool.MESH && target != null && target.kind == "mesh") {
            val pts = editor.screen(target.geometry.points, target, viewport)
            val edges = MeshTopology.uniqueEdges(target.indices).map { it.endpointLow to it.endpointHigh }
            edges.forEach { (a, b) ->
                drawLine(Color.Black.copy(alpha = 0.45f), pts[a], pts[b], 2.5f)
                drawLine(colors.accent.copy(alpha = 0.65f), pts[a], pts[b], 1f)
            }
            pts.forEachIndexed { i, p ->
                val isSelected = i in editor.vertices
                val isHovered = i == editor.hoveredVertex
                if (isHovered) {
                    drawCircle(Color.White, 7.5f, p, style = Stroke(1.8f))
                    drawCircle(colors.accent, 4.5f, p)
                } else if (isSelected) {
                    drawCircle(colors.windowBackground, 5f, p)
                    drawCircle(colors.accent, 3.8f, p)
                } else {
                    drawCircle(colors.windowBackground, 3.5f, p)
                    drawCircle(colors.textPrimary.copy(alpha = 0.7f), 2.2f, p)
                }
            }

            // Dragging guide line
            if (editor.inGesture && editor.vertices.isNotEmpty() && editor.marquee.isEmpty() && editor.cursor != null) {
                drawLine(
                    color = colors.accent.copy(alpha = 0.6f),
                    start = editor.dragStartPos,
                    end = editor.cursor!!,
                    strokeWidth = 1.5f
                )
            }
        }

        // 3. WARP tool: Draw deformer lattice grid lines or rotation axis
        if (editor.tool == CanvasTool.WARP && target != null && (target.kind == "warp" || target.kind == "rotation")) {
            val pts = editor.screen(target.geometry.points, target, viewport)
            if (target.kind == "rotation") {
                if (pts.size >= 2) {
                    drawLine(colors.accent, pts[0], pts[1], 2.5f)
                    drawCircle(colors.accent, 6.5f, pts[0])
                    drawCircle(Color(0xFF7BBB99), 5.5f, pts[1])
                }
            } else {
                val columns = target.geometry.columns!! + 1
                pts.indices.flatMap { i ->
                    listOfNotNull(
                        if (i % columns < columns - 1) i to i + 1 else null,
                        if (i + columns < pts.size) i to i + columns else null
                    )
                }.forEach { (a, b) ->
                    drawLine(Color.Black.copy(alpha = 0.6f), pts[a], pts[b], 3f)
                    drawLine(colors.accent.copy(alpha = 0.8f), pts[a], pts[b], 1.2f)
                }
                pts.forEachIndexed { i, p ->
                    val isHovered = i == editor.hoveredVertex
                    val isSelected = i in editor.vertices
                    if (isHovered) {
                        drawCircle(Color.White, 8f, p, style = Stroke(2f))
                        drawCircle(colors.accent, 5f, p)
                    } else if (isSelected) {
                        drawCircle(colors.windowBackground, 5.5f, p)
                        drawCircle(colors.accent, 4f, p)
                    } else {
                        drawCircle(colors.windowBackground, 4f, p)
                        drawCircle(colors.textPrimary, 2.8f, p)
                    }
                }
            }
        }

        // 4. BRUSH / SMOOTH / INFLATE mode: Shape-aware brush outline following cursor
        if (editor.tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)) {
            editor.cursor?.let { center ->
                val r = (editor.radius * viewport.scale).toFloat()
                val ring = if (editor.tool == CanvasTool.INFLATE && editor.shrinks) colors.warning else colors.textPrimary

                when (editor.brushShape) {
                    BrushShape.CIRCLE -> {
                        if (editor.adjustingBrush) {
                            val samples = 24
                            val stops = Array(samples + 1) { i ->
                                val t = i / samples.toFloat()
                                t to Color.Red.copy(alpha = brushWeight(t * r, r, editor.hardness) * 0.6f)
                            }
                            drawCircle(
                                brush = Brush.radialGradient(colorStops = stops, center = center, radius = r.coerceAtLeast(1f)),
                                radius = r,
                                center = center,
                            )
                        }
                        drawCircle(Color.Black.copy(alpha = 0.7f), r, center, style = Stroke(3f))
                        drawCircle(ring, r, center, style = Stroke(1.2f))
                        drawCircle(colors.accent.copy(alpha = 0.6f), r * editor.hardness, center, style = Stroke(1f))
                    }

                    BrushShape.LINE -> {
                        // Infinite line spanning across the entire canvas with influence band width 2 * r
                        val rad = Math.toRadians(editor.brushAngle.toDouble())
                        val cosA = kotlin.math.cos(rad).toFloat()
                        val sinA = kotlin.math.sin(rad).toFloat()
                        val u = Offset(cosA, sinA)
                        val v = Offset(-sinA, cosA)

                        val span = (size.width + size.height) * 2f
                        val p1 = center - u * span
                        val p2 = center + u * span

                        val p1Plus = p1 + v * r; val p2Plus = p2 + v * r
                        val p1Minus = p1 - v * r; val p2Minus = p2 - v * r

                        if (editor.adjustingBrush) {
                            val samples = 24
                            val stops = Array(samples + 1) { i ->
                                val t = i / samples.toFloat()
                                val dist = kotlin.math.abs(t * 2f - 1f) * r
                                t to Color.Red.copy(alpha = brushWeight(dist, r, editor.hardness) * 0.6f)
                            }
                            val bandBrush = Brush.linearGradient(
                                colorStops = stops,
                                start = center - v * r,
                                end = center + v * r,
                            )
                            val bandPath = Path().apply {
                                moveTo(p1Minus.x, p1Minus.y)
                                lineTo(p1Plus.x, p1Plus.y)
                                lineTo(p2Plus.x, p2Plus.y)
                                lineTo(p2Minus.x, p2Minus.y)
                                close()
                            }
                            drawPath(bandPath, bandBrush)
                        }

                        // Outer influence band boundary lines at distance +r and -r
                        drawLine(Color.Black.copy(alpha = 0.5f), p1Plus, p2Plus, strokeWidth = 2.5f)
                        drawLine(ring.copy(alpha = 0.5f), p1Plus, p2Plus, strokeWidth = 1.2f)
                        drawLine(Color.Black.copy(alpha = 0.5f), p1Minus, p2Minus, strokeWidth = 2.5f)
                        drawLine(ring.copy(alpha = 0.5f), p1Minus, p2Minus, strokeWidth = 1.2f)

                        // Hardness core zone boundary lines
                        if (editor.hardness > 0.05f) {
                            val hr = r * editor.hardness
                            drawLine(colors.accent.copy(alpha = 0.45f), p1 + v * hr, p2 + v * hr, strokeWidth = 1f)
                            drawLine(colors.accent.copy(alpha = 0.45f), p1 - v * hr, p2 - v * hr, strokeWidth = 1f)
                        }

                        // Central infinite axis line
                        drawLine(Color.Black.copy(alpha = 0.8f), p1, p2, strokeWidth = 3f)
                        drawLine(ring, p1, p2, strokeWidth = 1.5f)

                        // Center point & angle pointer
                        drawCircle(ring, 3.5f, center)
                        drawCircle(colors.accent, 2f, center)
                        val dirPointer = center + u * (r.coerceAtLeast(30f) + 12f)
                        drawLine(colors.accent, center, dirPointer, strokeWidth = 1.8f)
                    }

                    BrushShape.RECTANGLE -> {
                        val h = editor.hardness.coerceIn(0f, 0.95f)
                        val halfW = r.coerceAtLeast(1f)
                        val halfH = (r * editor.brushAspect.coerceIn(0.1f, 10f)).coerceAtLeast(1f)
                        val coreW = halfW * h
                        val coreH = halfH * h
                        val falloff = halfW * (1f - h)

                        rotate(degrees = editor.brushAngle, pivot = center) {
                            val topLeft = Offset(center.x - halfW, center.y - halfH)
                            val rectSize = Size(halfW * 2f, halfH * 2f)
                            val cornerRadius = CornerRadius(falloff, falloff)

                            if (editor.adjustingBrush) {
                                // Real hardness preview: solid red core + smooth Euclidean Hermite falloff
                                val steps = 16
                                for (step in steps downTo 1) {
                                    val t = step / steps.toFloat()
                                    val curDist = falloff * t
                                    val w = coreW + curDist
                                    val hStep = coreH + curDist
                                    val alpha = (1f - t * t * (3f - 2f * t)) * 0.6f
                                    drawRoundRect(
                                        color = Color.Red.copy(alpha = alpha),
                                        topLeft = Offset(center.x - w, center.y - hStep),
                                        size = Size(w * 2f, hStep * 2f),
                                        cornerRadius = CornerRadius(curDist, curDist),
                                    )
                                }
                                if (h > 0.02f) {
                                    drawRect(
                                        color = Color.Red.copy(alpha = 0.6f),
                                        topLeft = Offset(center.x - coreW, center.y - coreH),
                                        size = Size(coreW * 2f, coreH * 2f),
                                    )
                                }
                            }

                            // Outer influence boundary
                            drawRoundRect(Color.Black.copy(alpha = 0.7f), topLeft, rectSize, cornerRadius, style = Stroke(3f))
                            drawRoundRect(ring, topLeft, rectSize, cornerRadius, style = Stroke(1.2f))

                            // Inner core boundary (when hardness > 0)
                            if (h > 0.05f) {
                                drawRect(
                                    color = colors.accent.copy(alpha = 0.6f),
                                    topLeft = Offset(center.x - coreW, center.y - coreH),
                                    size = Size(coreW * 2f, coreH * 2f),
                                    style = Stroke(1f),
                                )
                            }
                        }
                        drawCircle(colors.accent, 2.5f, center)
                    }
                }
            }
        }

        // 5. PATH_DEFORM mode: Curves and control points
        if (isPathTool && target != null && target.kind == "mesh") {
            editor.paths().forEach { path ->
                val local = DeformPathTools.positions(path, target.geometry.points)
                val curve = DeformPathTools.curve(local, path.points.map { it.corner }, path.closed)
                val points = editor.screen(curve.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport)
                val selected = path.id == editor.activePath
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(Color.Black.copy(alpha = 0.7f), a, b, 5f)
                    drawLine(if (selected) colors.accent else colors.textPrimary, a, b, 2f)
                }
                editor.screen(local.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport).forEachIndexed { i, p ->
                    val isHovered = i == editor.hoveredVertex
                    if (isHovered) {
                        drawCircle(Color.White, 8f, p, style = Stroke(2f))
                        drawCircle(colors.accent, 5f, p)
                    } else {
                        drawCircle(colors.windowBackground, 5.5f, p)
                        drawCircle(if (selected && i == editor.pathPoint) colors.accent else colors.textPrimary, 4f, p)
                    }
                }
            }
            val points = editor.screen(editor.draft.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport)
            points.zipWithNext().forEach { (a, b) -> drawLine(colors.accent, a, b, 2f) }
            points.forEach { drawCircle(colors.accent, 4f, it) }
            if (editor.drawingPath && points.isNotEmpty()) editor.cursor?.let { drawLine(colors.accent.copy(alpha = 0.5f), points.last(), it, 1f) }
        }

        // 6. Marquee selection box / lasso
        if (editor.marquee.isNotEmpty()) {
            val points = editor.marquee
            if (editor.selectionStyle == SelectionStyle.LASSO) {
                val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, colors.accent.copy(alpha = 0.15f))
                drawPath(path, colors.accent, style = Stroke(1.2f))
            } else {
                val a = points.first(); val b = points.last()
                val origin = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val extent = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                drawRect(colors.accent.copy(alpha = 0.15f), origin, extent)
                drawRect(colors.accent, origin, extent, style = Stroke(1.2f))
            }
        }

        // 7. Brush gesture HUD: sits with the frozen outline and lists every brush parameter, the way the
        //    Photoshop readout does. The one the drag latched onto is highlighted, so the full picture is
        //    there without having to guess which value is currently moving.
        if (editor.adjustingBrush) {
            editor.cursor?.let { anchor ->
                val base = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textPrimary)
                val live = base.copy(color = colors.accent)
                val rows = buildList {
                    add(BrushAdjustAxis.RADIUS to "${tr("editor.radius")} ${editor.radius.roundToInt()} px")
                    add(BrushAdjustAxis.HARDNESS to "${tr("editor.hardness")} ${(editor.hardness * 100).roundToInt()}%")
                    if (editor.brushShape != BrushShape.CIRCLE) {
                        add(BrushAdjustAxis.ANGLE to "${tr("editor.angle")} ${editor.brushAngle.roundToInt()}°")
                    }
                    add(null to "${tr("editor.strength")} ${(editor.strength * 100).roundToInt()}%")
                }
                val lines = rows.map { (axis, text) ->
                    textMeasurer.measure(text = text, style = if (axis != null && axis == editor.brushAxis) live else base)
                }
                val padX = 8f
                val padY = 5f
                val gap = 3f
                val box = Size(
                    lines.maxOf { it.size.width } + padX * 2f,
                    lines.sumOf { it.size.height }.toFloat() + gap * (lines.size - 1) + padY * 2f,
                )
                // Prefer below-right of the outline; flip to the opposite side when that would leave the canvas.
                var x = anchor.x + 18f
                var y = anchor.y + 22f
                if (x + box.width > size.width) x = anchor.x - 18f - box.width
                if (y + box.height > size.height) y = anchor.y - 22f - box.height
                x = x.coerceIn(0f, (size.width - box.width).coerceAtLeast(0f))
                y = y.coerceIn(0f, (size.height - box.height).coerceAtLeast(0f))
                drawRoundRect(Color(0xE6181A1E), Offset(x, y), box, CornerRadius(4f, 4f))
                drawRoundRect(colors.accent.copy(alpha = 0.9f), Offset(x, y), box, CornerRadius(4f, 4f), style = Stroke(1f))
                var lineY = y + padY
                lines.forEach {
                    drawText(textLayoutResult = it, topLeft = Offset(x + padX, lineY))
                    lineY += it.size.height + gap
                }
            }
        }
    }

    // Left Animated Hover Toolbar
    CanvasToolBar(editor = editor, keymap = keymap, focus = focus)

    // Bottom Status Bar
    Text(
        editor.error ?: if (editor.busy) tr("editor.saving") else tr("editor.selectionCount", editor.objects.size, editor.vertices.size) + "   ·   " + tr(when (editor.tool) {
            CanvasTool.PATH_DEFORM -> "editor.pathHint"
            CanvasTool.INFLATE -> "editor.inflateHint"
            CanvasTool.BRUSH, CanvasTool.SMOOTH -> "editor.brushHint"
            else -> "editor.hint"
        }),
        color = if (editor.error != null) colors.error else colors.textMuted,
        fontSize = 10.sp,
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.panelBackground).padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.CanvasToolBar(
    editor: CanvasEditor,
    keymap: Keymap,
    focus: () -> Unit,
) {
    val colors = LocalToolColors.current
    val toolbarInteractionSource = remember { MutableInteractionSource() }
    val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
    var isHoveredByEvent by remember { mutableStateOf(false) }
    val isToolbarHovered = isHoveredBySource || isHoveredByEvent

    val animatedWidth by animateDpAsState(
        targetValue = if (isToolbarHovered) 156.dp else 34.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (isToolbarHovered) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isToolbarHovered) 150 else 80,
            delayMillis = if (isToolbarHovered) 40 else 0,
            easing = FastOutSlowInEasing,
        ),
    )
    val textOffset by animateDpAsState(
        targetValue = if (isToolbarHovered) 0.dp else (-6).dp,
        animationSpec = tween(
            durationMillis = if (isToolbarHovered) 180 else 80,
            delayMillis = if (isToolbarHovered) 30 else 0,
            easing = FastOutSlowInEasing,
        ),
    )
    val elevation by animateDpAsState(
        targetValue = if (isToolbarHovered) 8.dp else 2.dp,
        animationSpec = tween(durationMillis = 200),
    )

    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 44.dp)
            .width(animatedWidth)
            .frostedGlass(
                shape = RoundedCornerShape(6.dp),
                isHovered = isToolbarHovered,
                elevation = elevation,
                alpha = 0.78f
            )
            .hoverable(toolbarInteractionSource)
            .onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
            .onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
            .verticalScroll(rememberScrollState())
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CanvasTool.entries.forEach { tool ->
            ToolItemRow(
                tool = tool,
                isSelected = editor.tool == tool,
                isToolbarExpanded = animatedWidth > 42.dp,
                textAlpha = textAlpha,
                textOffset = textOffset,
                isBusy = editor.busy,
                keyLabel = keymap.labelFor(tool.action).orEmpty(),
                brushShape = if (tool == CanvasTool.BRUSH) editor.brushShape else null,
                onClick = {
                    editor.activateTool(tool)
                    focus()
                },
            )
        }

        val isBrushTool = editor.tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)

        AnimatedVisibility(
            visible = isBrushTool,
            enter = expandVertically(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(120)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Subtle divider separating primary tools and brush shapes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .height(1.dp)
                        .background(colors.border.copy(alpha = 0.45f))
                )

                // Three brush shapes placed at the bottom of the peer toolbar with text
                BrushShape.entries.forEach { shape ->
                    ShapeItemRow(
                        shape = shape,
                        isSelected = editor.brushShape == shape,
                        isToolbarExpanded = animatedWidth > 42.dp,
                        textAlpha = textAlpha,
                        textOffset = textOffset,
                        isBusy = editor.busy,
                        onClick = {
                            editor.brushShape = shape
                            if (editor.tool !in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)) {
                                editor.activateTool(CanvasTool.BRUSH)
                            }
                            focus()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolItemRow(
    tool: CanvasTool,
    isSelected: Boolean,
    isToolbarExpanded: Boolean,
    textAlpha: Float,
    textOffset: androidx.compose.ui.unit.Dp,
    isBusy: Boolean,
    keyLabel: String,
    brushShape: BrushShape? = null,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val itemInteractionSource = remember { MutableInteractionSource() }
    val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

    val label = tr("editor.tool.${tool.name.lowercase()}")

    val bg = when {
        isSelected -> colors.accent.copy(alpha = 0.24f)
        isItemHovered -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .semantics { contentDescription = "$label  $keyLabel" }
            .clickable(
                interactionSource = itemInteractionSource,
                indication = null,
                enabled = !isBusy,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            ToolIcon(
                tool = tool,
                color = when {
                    isSelected -> colors.accent
                    isItemHovered -> colors.textPrimary
                    else -> colors.textMuted
                },
                brushShape = brushShape,
            )
        }

        if (isToolbarExpanded) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .offset(x = textOffset)
                    .alpha(textAlpha)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = label,
                    color = when {
                        isSelected -> colors.textPrimary
                        isItemHovered -> colors.textPrimary
                        else -> colors.textMuted
                    },
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (keyLabel.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) colors.accent.copy(alpha = 0.18f) else colors.panelElevated,
                                RoundedCornerShape(3.dp)
                            )
                            .border(
                                0.5.dp,
                                if (isSelected) colors.accent.copy(alpha = 0.4f) else colors.border.copy(alpha = 0.6f),
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = keyLabel,
                            color = if (isSelected) colors.accent else colors.textDisabled,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShapeItemRow(
    shape: BrushShape,
    isSelected: Boolean,
    isToolbarExpanded: Boolean,
    textAlpha: Float,
    textOffset: androidx.compose.ui.unit.Dp,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val itemInteractionSource = remember { MutableInteractionSource() }
    val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

    val label = tr(shape.labelKey)

    val bg = when {
        isSelected -> colors.accent.copy(alpha = 0.24f)
        isItemHovered -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = itemInteractionSource,
                indication = null,
                enabled = !isBusy,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            BrushShapeIcon(
                shape = shape,
                color = when {
                    isSelected -> colors.accent
                    isItemHovered -> colors.textPrimary
                    else -> colors.textMuted
                },
                size = 14.dp,
            )
        }

        if (isToolbarExpanded) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .offset(x = textOffset)
                    .alpha(textAlpha)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = label,
                    color = when {
                        isSelected -> colors.textPrimary
                        isItemHovered -> colors.textPrimary
                        else -> colors.textMuted
                    },
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ToolIcon(tool: CanvasTool, color: Color, brushShape: BrushShape? = null) {
    Canvas(Modifier.size(18.dp)) {
        val s = size.width / 18f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, p(x, y), p(a, b), 1.3f * s)
        when (tool) {
            CanvasTool.SELECT -> {
                val path = Path().apply { moveTo(3 * s, 2 * s); lineTo(14 * s, 10 * s); lineTo(9 * s, 11 * s); lineTo(7 * s, 16 * s); close() }
                drawPath(path, color, style = Stroke(s * 1.3f))
            }
            CanvasTool.MESH -> {
                val path = Path().apply { moveTo(9 * s, 2 * s); lineTo(16 * s, 15 * s); lineTo(2 * s, 15 * s); close() }
                drawPath(path, color, style = Stroke(s * 1.3f))
                drawCircle(color, 2 * s, p(9f, 2f))
                drawCircle(color, 2 * s, p(16f, 15f))
                drawCircle(color, 2 * s, p(2f, 15f))
            }
            CanvasTool.WARP -> {
                for (i in listOf(3f, 9f, 15f)) {
                    line(i, 3f, i, 15f)
                    line(3f, i, 15f, i)
                }
            }
            CanvasTool.BRUSH, CanvasTool.SMOOTH -> {
                line(6f, 11f, 14f, 3f)
                line(9f, 14f, 17f, 6f)
                line(14f, 3f, 17f, 6f)
                if (brushShape == BrushShape.LINE) {
                    line(2f, 16f, 8f, 12f)
                } else if (brushShape == BrushShape.RECTANGLE) {
                    drawRect(color, Offset(2 * s, 11 * s), Size(6 * s, 5 * s), style = Stroke(1.3f * s))
                } else {
                    drawCircle(color, 3 * s, p(5f, 14f), style = Stroke(1.3f * s))
                }
                if (tool == CanvasTool.SMOOTH) line(1f, 4f, 7f, 4f)
            }
            CanvasTool.INFLATE -> {
                drawCircle(color, 3.4f * s, p(9f, 9f), style = Stroke(1.3f * s))
                // Four barbs pointing outward: volume pushed away from the stroke
                listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
                    val tipX = 9f + dx * 7.8f
                    val tipY = 9f + dy * 7.8f
                    line(9f + dx * 5.2f, 9f + dy * 5.2f, tipX, tipY)
                    val perpX = -dy * 1.6f
                    val perpY = dx * 1.6f
                    line(tipX, tipY, tipX - dx * 2.2f + perpX, tipY - dy * 2.2f + perpY)
                    line(tipX, tipY, tipX - dx * 2.2f - perpX, tipY - dy * 2.2f - perpY)
                }
            }
            CanvasTool.PATH_DEFORM -> {
                val path = Path().apply { moveTo(2 * s, 14 * s); cubicTo(6 * s, -2 * s, 12 * s, 20 * s, 16 * s, 4 * s) }
                drawPath(path, color, style = Stroke(1.3f * s))
                drawCircle(color, 2 * s, p(2f, 14f))
                drawCircle(color, 2 * s, p(16f, 4f))
                drawCircle(color, 2 * s, p(9f, 9f))
            }
            CanvasTool.HAND -> {
                line(4f, 9f, 4f, 14f)
                line(4f, 14f, 8f, 17f)
                line(8f, 17f, 13f, 15f)
                line(13f, 15f, 15f, 6f)
                for (i in 6..12 step 2) line(i.toFloat(), 3f, i.toFloat(), 10f)
            }
        }
    }
}

/**
 * Renders a crisp vector glyph representing [shape].
 */
@Composable
private fun BrushShapeIcon(
    shape: BrushShape,
    color: Color,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.3f
        when (shape) {
            BrushShape.CIRCLE -> {
                drawCircle(color, radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(strokeW))
                drawCircle(color.copy(alpha = 0.5f), radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(strokeW * 0.8f))
            }
            BrushShape.LINE -> {
                val p1 = Offset(w * 0.18f, h * 0.82f)
                val p2 = Offset(w * 0.82f, h * 0.18f)
                drawLine(color, p1, p2, strokeWidth = strokeW * 1.2f, cap = StrokeCap.Round)
                drawCircle(color, radius = 1.8f, center = p1)
                drawCircle(color, radius = 1.8f, center = p2)
            }
            BrushShape.RECTANGLE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.16f, h * 0.22f),
                    size = Size(w * 0.68f, h * 0.56f),
                    cornerRadius = CornerRadius(1.5f, 1.5f),
                    style = Stroke(strokeW)
                )
            }
        }
    }
}
