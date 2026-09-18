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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.*
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
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
    val isPathTool = editor.tool == CanvasTool.CREATE_DEFORM_PATH || editor.drawingPath || (editor.hierarchyMode == EditHierarchyMode.DEFORM && editor.paths().isNotEmpty())

    Canvas(Modifier.fillMaxSize()) {
        // 1. Mesh wireframe & vertices when in DEFORM or STRUCTURE on a mesh
        if (editor.hierarchyMode != EditHierarchyMode.OBJECT && target != null && target.kind == "mesh") {
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

        // 2. Warp deformer or Rotation deformer when in DEFORM or STRUCTURE
        if (editor.hierarchyMode != EditHierarchyMode.OBJECT && target != null && (target.kind == "warp" || target.kind == "rotation")) {
            val pts = editor.screen(target.geometry.points, target, viewport)
            if (target.kind == "rotation") {
                if (pts.size >= 2) {
                    drawLine(colors.accent, pts[0], pts[1], 2.5f)
                    drawCircle(colors.accent, 6.5f, pts[0])
                    drawCircle(Color(0xFF7BBB99), 5.5f, pts[1])
                }
            } else if (editor.hierarchyMode == EditHierarchyMode.DEFORM && editor.editLevel == 2) {
                // LEVEL 2: Live2D Cubism-style Bezier Deformer
                editor.ensureBezierState()
                val bState = editor.bezierState
                if (bState != null) {
                    // Faint underlying lattice lines
                    val columns = target.geometry.columns!! + 1
                    pts.indices.flatMap { i ->
                        listOfNotNull(
                            if (i % columns < columns - 1) i to i + 1 else null,
                            if (i + columns < pts.size) i to i + columns else null
                        )
                    }.forEach { (a, b) ->
                        drawLine(colors.accent.copy(alpha = 0.22f), pts[a], pts[b], 1f)
                    }

                    // Cubic Bezier boundary and internal curves
                    for (r in 0..bState.bezierRows) {
                        for (c in 0 until bState.bezierCols) {
                            val a0 = bState.anchors[r to c] ?: continue
                            val a1 = bState.anchors[r to (c + 1)] ?: continue
                            val h0 = bState.handles[Triple(r, c, BezierHandleDir.RIGHT)]
                            val h1 = bState.handles[Triple(r, c + 1, BezierHandleDir.LEFT)]
                            val p0 = editor.screen(floatArrayOf(a0.x, a0.y), target, viewport)[0]
                            val p1 = editor.screen(floatArrayOf(a1.x, a1.y), target, viewport)[0]
                            val c0 = h0?.let { editor.screen(floatArrayOf(it.x, it.y), target, viewport)[0] } ?: (p0 + (p1 - p0) / 3f)
                            val c1 = h1?.let { editor.screen(floatArrayOf(it.x, it.y), target, viewport)[0] } ?: (p1 - (p1 - p0) / 3f)
                            val curvePath = Path().apply {
                                moveTo(p0.x, p0.y)
                                cubicTo(c0.x, c0.y, c1.x, c1.y, p1.x, p1.y)
                            }
                            drawLine(Color.Black.copy(alpha = 0.35f), p0, p1, 1f)
                            drawPath(curvePath, colors.accent, style = Stroke(2f))
                        }
                    }
                    for (r in 0 until bState.bezierRows) {
                        for (c in 0..bState.bezierCols) {
                            val a0 = bState.anchors[r to c] ?: continue
                            val a1 = bState.anchors[(r + 1) to c] ?: continue
                            val h0 = bState.handles[Triple(r, c, BezierHandleDir.BOTTOM)]
                            val h1 = bState.handles[Triple(r + 1, c, BezierHandleDir.TOP)]
                            val p0 = editor.screen(floatArrayOf(a0.x, a0.y), target, viewport)[0]
                            val p1 = editor.screen(floatArrayOf(a1.x, a1.y), target, viewport)[0]
                            val c0 = h0?.let { editor.screen(floatArrayOf(it.x, it.y), target, viewport)[0] } ?: (p0 + (p1 - p0) / 3f)
                            val c1 = h1?.let { editor.screen(floatArrayOf(it.x, it.y), target, viewport)[0] } ?: (p1 - (p1 - p0) / 3f)
                            val curvePath = Path().apply {
                                moveTo(p0.x, p0.y)
                                cubicTo(c0.x, c0.y, c1.x, c1.y, p1.x, p1.y)
                            }
                            drawPath(curvePath, colors.accent, style = Stroke(2f))
                        }
                    }

                    // Tangent handle stems and end markers
                    bState.handles.forEach { (key, handle) ->
                        val anchor = bState.anchors[key.first to key.second] ?: return@forEach
                        val ap = editor.screen(floatArrayOf(anchor.x, anchor.y), target, viewport)[0]
                        val hp = editor.screen(floatArrayOf(handle.x, handle.y), target, viewport)[0]
                        val isHovered = editor.hoveredBezierHandle == key
                        val isActive = editor.activeBezierHandle == key
                        drawLine(colors.textPrimary.copy(alpha = 0.55f), ap, hp, 1.2f)
                        if (isHovered || isActive) {
                            drawCircle(Color.White, 6.5f, hp, style = Stroke(1.8f))
                            drawCircle(colors.accent, 4.5f, hp)
                        } else {
                            drawCircle(colors.windowBackground, 4.5f, hp)
                            drawCircle(Color(0xFFE5A823), 3f, hp)
                        }
                    }

                    // Bezier anchor points
                    bState.anchors.forEach { (key, anchor) ->
                        val ap = editor.screen(floatArrayOf(anchor.x, anchor.y), target, viewport)[0]
                        val isHovered = editor.hoveredBezierAnchor == key
                        val isActive = editor.activeBezierAnchor == key
                        if (isHovered || isActive) {
                            drawCircle(Color.White, 8.5f, ap, style = Stroke(2f))
                            drawCircle(colors.accent, 5.5f, ap)
                        } else {
                            drawCircle(colors.windowBackground, 5.5f, ap)
                            drawCircle(colors.accent, 4f, ap)
                        }
                    }
                }
            } else {
                // Level 1 or 3: Warp lattice grid lines and vertices
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

        // 3. Transform box (shared by TRANSFORM tool and points)
        if (editor.drawsTransformBox) {
            editor.transformFrame(viewport)?.let { frame ->
                drawTransformBox(frame, editor.hoveredHandle, editor.axis.takeIf { editor.inGesture }, colors)
            }
        }

        // 3b. Object mode: the selection, outlined in the colour of the part it is. Object mode has no
        //     transform box, so this outline is the whole of the selection feedback — it is what tells a
        //     picked layer apart from the ones merely drawn, and it carries across a multi-select.
        if (editor.hierarchyMode == EditHierarchyMode.OBJECT && editor.tool == CanvasTool.SELECT) {
            editor.objects.forEach { layerId ->
                val item = editor.target(editor.model, layerId, null)
                val points = item?.let { editor.screen(it.geometry.points, it, viewport) }.orEmpty()
                if (points.isNotEmpty()) {
                    val left = points.minOf { it.x }; val top = points.minOf { it.y }
                    val origin = Offset(left, top)
                    val extent = Size(points.maxOf { it.x } - left, points.maxOf { it.y } - top)
                    val awt = ComponentPalette.strong(layerId)
                    val color = Color(awt.red, awt.green, awt.blue)
                    drawRect(color.copy(alpha = 0.08f), origin, extent)
                    drawRect(color, origin, extent, style = Stroke(1.6f))
                }
            }
        }

        // Interactive Creation Previews
        if (editor.isCreatingWarp && editor.creationStart != null && editor.creationCurrent != null) {
            val s = editor.creationStart!!; val e = editor.creationCurrent!!
            val origin = Offset(minOf(s.x, e.x), minOf(s.y, e.y))
            val extent = Size(abs(s.x - e.x), abs(s.y - e.y))
            drawRect(colors.accent.copy(alpha = 0.12f), origin, extent)
            drawRect(colors.accent, origin, extent, style = Stroke(1.5f))
            val rows = editor.warpCreateGridRows
            val cols = editor.warpCreateGridCols
            for (r in 1 until rows) {
                val y = origin.y + extent.height * (r.toFloat() / rows)
                drawLine(colors.accent.copy(alpha = 0.45f), Offset(origin.x, y), Offset(origin.x + extent.width, y), 1f)
            }
            for (c in 1 until cols) {
                val x = origin.x + extent.width * (c.toFloat() / cols)
                drawLine(colors.accent.copy(alpha = 0.45f), Offset(x, origin.y), Offset(x, origin.y + extent.height), 1f)
            }
        }

        if (editor.isCreatingRotation && editor.creationStart != null && editor.creationCurrent != null) {
            val s = editor.creationStart!!; val e = editor.creationCurrent!!
            val radius = (e - s).getDistance()
            drawCircle(colors.accent.copy(alpha = 0.1f), radius, s)
            drawCircle(colors.accent, radius, s, style = Stroke(1.2f))
            drawLine(colors.accent, s, e, 2f)
            drawCircle(colors.accent, 5f, s)
            drawCircle(Color(0xFF7BBB99), 4f, e)
        }

        if (editor.tool == CanvasTool.BRUSH_SELECT) {
            editor.cursor?.let { center ->
                val r = (editor.radius * viewport.scale).toFloat()
                drawCircle(Color.Black.copy(alpha = 0.5f), r, center, style = Stroke(2.5f))
                drawCircle(colors.accent, r, center, style = Stroke(1.2f))
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

        // 8. Object-mode hover annotation: a chip naming the part the pointer is over, the deformer it
        //    hangs off, and where a Ctrl-click goes next. It reads [hoveredPick], the same resolution
        //    the press makes, so it cannot promise a pick the click would not.
        //
        //    The highlight itself is not drawn here. The part's own texture is washed in this same
        //    component colour by the viewport, so a box would just be a second, worse answer to the
        //    question the wash already answers.
        val pick = editor.hoveredPick
        if (pick != null && !editor.inGesture) {
            val awt = ComponentPalette.strong(pick.id)
            val accent = Color(awt.red, awt.green, awt.blue)
            val base = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textPrimary)
            val muted = base.copy(color = colors.textMuted)
            val lines = buildList {
                add(textMeasurer.measure(tr("editor.hover.${pick.kind}", pick.name), base.copy(color = accent, fontWeight = FontWeight.Medium)))
                pick.parentName?.let { add(textMeasurer.measure(tr("editor.hover.parent", it), muted)) }
                pick.nextName?.let { add(textMeasurer.measure(tr("editor.hover.ctrl", it), muted)) }
            }
            val padX = 8f
            val padY = 5f
            val gap = 3f
            val dot = 6f
            val dotGap = 6f
            val box = Size(
                lines.maxOf { it.size.width } + padX * 2f + dot + dotGap,
                lines.sumOf { it.size.height }.toFloat() + gap * (lines.size - 1) + padY * 2f,
            )
            val anchor = editor.cursor ?: Offset(size.width * 0.5f, size.height * 0.5f)
            var x = anchor.x + 18f
            var y = anchor.y + 22f
            if (x + box.width > size.width) x = anchor.x - 18f - box.width
            if (y + box.height > size.height) y = anchor.y - 22f - box.height
            x = x.coerceIn(0f, (size.width - box.width).coerceAtLeast(0f))
            y = y.coerceIn(0f, (size.height - box.height).coerceAtLeast(0f))
            drawRoundRect(Color(0xE6181A1E), Offset(x, y), box, CornerRadius(4f, 4f))
            drawRoundRect(accent.copy(alpha = 0.9f), Offset(x, y), box, CornerRadius(4f, 4f), style = Stroke(1f))
            drawCircle(accent, dot / 2f, Offset(x + padX + dot / 2f, y + padY + lines.first().size.height / 2f))
            var lineY = y + padY
            lines.forEach {
                drawText(textLayoutResult = it, topLeft = Offset(x + padX + dot + dotGap, lineY))
                lineY += it.size.height + gap
            }
        }
    }

    // Left Animated Hover Toolbar
    CanvasToolBar(editor = editor, keymap = keymap, focus = focus)

    // Top Right Hierarchy / Layer Mode Toolbar
    HierarchyModeBar(editor = editor, focus = focus)

    // Bottom Status Bar
    Text(
        editor.error ?: if (editor.busy) tr("editor.saving") else tr("editor.selectionCount", editor.objects.size, editor.vertices.size) + "   ·   " + tr(when {
            editor.tool == CanvasTool.CREATE_DEFORM_PATH -> "editor.pathHint"
            editor.tool == CanvasTool.INFLATE -> "editor.inflateHint"
            editor.tool == CanvasTool.BRUSH || editor.tool == CanvasTool.SMOOTH -> "editor.brushHint"
            editor.hierarchyMode == EditHierarchyMode.OBJECT && editor.tool == CanvasTool.SELECT -> "editor.objectHint"
            editor.tool == CanvasTool.SELECT && editor.drawsTransformBox -> "editor.transformHint"
            editor.tool == CanvasTool.CREATE_WARP -> "editor.createWarpHint"
            editor.tool == CanvasTool.CREATE_ROTATION -> "editor.createRotationHint"
            editor.tool == CanvasTool.GLUE -> "editor.glueHint"
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
        val toolGroups = listOf(
            listOf(CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT),
            listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE),
            listOf(CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH, CanvasTool.GLUE),
        )

        toolGroups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .height(1.dp)
                        .background(colors.border.copy(alpha = 0.35f))
                )
            }
            group.forEach { tool ->
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
            CanvasTool.LASSO_SELECT -> {
                val path = Path().apply {
                    moveTo(5 * s, 13 * s)
                    cubicTo(2 * s, 6 * s, 12 * s, 2 * s, 14 * s, 7 * s)
                    cubicTo(16 * s, 12 * s, 9 * s, 16 * s, 5 * s, 13 * s)
                    lineTo(3 * s, 16 * s)
                }
                drawPath(path, color, style = Stroke(s * 1.3f))
            }
            CanvasTool.BRUSH_SELECT -> {
                drawCircle(color, 4.5f * s, p(9f, 9f), style = Stroke(s * 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f * s, 2f * s))))
                drawCircle(color, 2f * s, p(9f, 9f))
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
            CanvasTool.CREATE_WARP -> {
                drawRect(color, Offset(3 * s, 3 * s), Size(12 * s, 12 * s), style = Stroke(s * 1.3f))
                line(7f, 3f, 7f, 15f)
                line(11f, 3f, 11f, 15f)
                line(3f, 7f, 15f, 7f)
                line(3f, 11f, 15f, 11f)
            }
            CanvasTool.CREATE_ROTATION -> {
                drawCircle(color, 2.5f * s, p(6f, 12f))
                line(6f, 12f, 14f, 4f)
                drawCircle(color, 1.8f * s, p(14f, 4f))
                val arcPath = Path().apply {
                    arcTo(androidx.compose.ui.geometry.Rect(Offset(-2f * s, 4f * s), Size(16f * s, 16f * s)), -65f, 45f, false)
                }
                drawPath(arcPath, color, style = Stroke(s * 1.2f))
            }
            CanvasTool.CREATE_DEFORM_PATH -> {
                val path = Path().apply { moveTo(2 * s, 14 * s); cubicTo(6 * s, -2 * s, 12 * s, 20 * s, 16 * s, 4 * s) }
                drawPath(path, color, style = Stroke(1.3f * s))
                drawCircle(color, 2 * s, p(2f, 14f))
                drawCircle(color, 2 * s, p(16f, 4f))
                drawCircle(color, 2 * s, p(9f, 9f))
            }
            CanvasTool.GLUE -> {
                drawCircle(color, 3.5f * s, p(6.5f, 9f), style = Stroke(s * 1.2f))
                drawCircle(color, 3.5f * s, p(11.5f, 9f), style = Stroke(s * 1.2f))
                line(7.5f, 7f, 10.5f, 7f)
                line(7.5f, 11f, 10.5f, 11f)
            }
        }
    }
}

@Composable
private fun ModeIcon(mode: EditHierarchyMode, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val s = size.width / 14f
        when (mode) {
            EditHierarchyMode.OBJECT -> {
                drawRect(color, Offset(2 * s, 2 * s), Size(10 * s, 10 * s), style = Stroke(1.3f * s))
                listOf(2f to 2f, 12f to 2f, 2f to 12f, 12f to 12f).forEach { (x, y) ->
                    drawCircle(color, 1.4f * s, Offset(x * s, y * s))
                }
            }
            EditHierarchyMode.DEFORM -> {
                val path = Path().apply {
                    moveTo(2 * s, 10 * s)
                    cubicTo(5 * s, 3 * s, 9 * s, 11 * s, 12 * s, 4 * s)
                }
                drawPath(path, color, style = Stroke(1.4f * s, cap = StrokeCap.Round))
                drawCircle(color, 1.3f * s, Offset(2 * s, 10 * s))
                drawCircle(color, 1.3f * s, Offset(12 * s, 4 * s))
            }
            EditHierarchyMode.STRUCTURE -> {
                val path = Path().apply {
                    moveTo(7 * s, 2 * s)
                    lineTo(12 * s, 11 * s)
                    lineTo(2 * s, 11 * s)
                    close()
                }
                drawPath(path, color, style = Stroke(1.3f * s))
                drawLine(color, Offset(7 * s, 2 * s), Offset(7 * s, 11 * s), 1f * s)
                drawCircle(color, 1.3f * s, Offset(7 * s, 2 * s))
                drawCircle(color, 1.3f * s, Offset(12 * s, 11 * s))
                drawCircle(color, 1.3f * s, Offset(2 * s, 11 * s))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.HierarchyModeBar(
    editor: CanvasEditor,
    focus: () -> Unit,
) {
    val colors = LocalToolColors.current
    val target = editor.target()

    val interactionSource = remember { MutableInteractionSource() }
    val isHoveredBySource by interactionSource.collectIsHoveredAsState()
    var isHoveredByEvent by remember { mutableStateOf(false) }
    val isToolbarHovered = isHoveredBySource || isHoveredByEvent

    val elevation by animateDpAsState(
        targetValue = if (isToolbarHovered) 8.dp else 2.dp,
        animationSpec = tween(durationMillis = 200),
    )

    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 8.dp)
            .frostedGlass(
                shape = RoundedCornerShape(6.dp),
                isHovered = isToolbarHovered,
                elevation = elevation,
                alpha = if (isToolbarHovered) 0.88f else 0.78f,
            )
            .hoverable(interactionSource)
            .onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
            .onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .animateContentSize(
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Mode buttons: 物体模式 / 变形模式 / 编辑模式
        EditHierarchyMode.entries.forEach { mode ->
            val isSelected = editor.hierarchyMode == mode
            val label = when (mode) {
                EditHierarchyMode.OBJECT -> tr("editor.mode.object")
                EditHierarchyMode.DEFORM -> tr("editor.mode.deform")
                EditHierarchyMode.STRUCTURE -> tr("editor.mode.structure")
            }
            ModeBarChip(
                mode = mode,
                text = label,
                isSelected = isSelected,
                onClick = {
                    editor.setHierarchyMode(mode)
                    focus()
                }
            )
        }

        // 1 2 3 deformation level expansion animation
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.DEFORM,
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Start,
            ) + fadeIn(animationSpec = tween(160)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(animationSpec = tween(120)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .height(14.dp)
                        .width(1.dp)
                        .background(colors.border.copy(alpha = 0.45f))
                )
                listOf(
                    1 to (tr("editor.level.1") + " · " + tr("editor.level.1.desc")),
                    2 to (tr("editor.level.2") + " · " + tr("editor.level.2.desc")),
                    3 to (tr("editor.level.3") + " · " + tr("editor.level.3.desc")),
                ).forEach { (lvl, tooltip) ->
                    val isLvlSelected = editor.editLevel == lvl
                    DeformLevelChip(
                        level = lvl,
                        tooltip = tooltip,
                        isSelected = isLvlSelected,
                        onClick = {
                            editor.setEditLevel(lvl)
                            focus()
                        }
                    )
                }
            }
        }

        // Structure mode tools
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.STRUCTURE,
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Start,
            ) + fadeIn(animationSpec = tween(160)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(animationSpec = tween(120)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .height(14.dp)
                        .width(1.dp)
                        .background(colors.border.copy(alpha = 0.45f))
                )
                if (target != null && target.kind == "warp") {
                    val warpRows = target.geometry.rows ?: 4
                    val warpCols = target.geometry.columns ?: 4
                    Text(
                        text = "Grid: ${warpRows}×${warpCols}",
                        fontSize = 11.sp,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                } else if (target != null && target.kind == "mesh") {
                    listOf(
                        tr("editor.split") to { editor.topology("split"); focus() },
                        tr("editor.connect") to { editor.topology("connect"); focus() },
                        tr("editor.delete") to { editor.topology("delete"); focus() },
                    ).forEach { (lbl, act) ->
                        StructureActionChip(
                            text = lbl,
                            onClick = act
                        )
                    }
                }
            }
        }

        // Object mode target badge
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.OBJECT && target != null,
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Start,
            ) + fadeIn(animationSpec = tween(150)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(animationSpec = tween(100)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .height(14.dp)
                        .width(1.dp)
                        .background(colors.border.copy(alpha = 0.45f))
                )
                Text(
                    text = target?.id.orEmpty(),
                    fontSize = 10.5.sp,
                    color = colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeBarChip(
    mode: EditHierarchyMode,
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = when {
        isSelected -> colors.accent.copy(alpha = 0.22f)
        isHovered -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> colors.accent
        isHovered -> colors.textPrimary
        else -> colors.textMuted
    }

    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(
                0.5.dp,
                if (isSelected) colors.accent.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeIcon(mode = mode, color = textColor)
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun DeformLevelChip(
    level: Int,
    tooltip: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = when {
        isSelected -> colors.accent.copy(alpha = 0.22f)
        isHovered -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> colors.accent
        isHovered -> colors.textPrimary
        else -> colors.textMuted
    }

    val yOffsetPx = with(density) { 28.dp.roundToPx() }

    Box {
        Box(
            modifier = Modifier
                .height(24.dp)
                .defaultMinSize(minWidth = 24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .border(
                    0.5.dp,
                    if (isSelected) colors.accent.copy(alpha = 0.5f) else Color.Transparent,
                    RoundedCornerShape(4.dp)
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$level",
                color = textColor,
                fontSize = 11.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }

        if (isHovered) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, yOffsetPx),
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    color = colors.panelElevated,
                    border = BorderStroke(0.8.dp, colors.border),
                    shape = RoundedCornerShape(4.dp),
                    elevation = 6.dp,
                ) {
                    Text(
                        text = tooltip,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun StructureActionChip(
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) colors.controlHover.copy(alpha = 0.7f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (isHovered) colors.textPrimary else colors.textMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
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

/**
 * The shared transform box: the rectangle, the rotate stem and its handle, the eight scale handles, and
 * the axis-lock guide.
 *
 * The box is drawn inside the frame it was measured in, so an oriented box is still just a rectangle
 * here — the rotation lives in this one [rotate], not in the geometry drawn inside it.
 */
private fun DrawScope.drawTransformBox(frame: TransformFrame, hovered: BoundingHandle, axis: String?, colors: ToolColors) {
    val bounds = frame.bounds
    rotate(frame.angleDeg, frame.pivot) {
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
        // Hovered, a handle takes the treatment the mesh vertices already use: a white ring
        // around a filled accent mark. Swapping the fill on a fixed-size shape was too
        // quiet to tell which handle the pointer had actually caught.
        if (hovered == BoundingHandle.ROTATE) {
            drawCircle(Color.White, 8.5f, bounds.rotateHandlePos, style = Stroke(1.8f))
            drawCircle(colors.accent, 5.5f, bounds.rotateHandlePos)
        } else {
            drawCircle(colors.windowBackground, 4f, bounds.rotateHandlePos)
            drawCircle(colors.accent, 4f, bounds.rotateHandlePos, style = Stroke(1.5f))
        }

        listOf(
            BoundingHandle.TOP_LEFT to Offset(bounds.minX, bounds.minY),
            BoundingHandle.TOP_RIGHT to Offset(bounds.maxX, bounds.minY),
            BoundingHandle.BOTTOM_LEFT to Offset(bounds.minX, bounds.maxY),
            BoundingHandle.BOTTOM_RIGHT to Offset(bounds.maxX, bounds.maxY),
            BoundingHandle.TOP to Offset(bounds.centerX, bounds.minY),
            BoundingHandle.BOTTOM to Offset(bounds.centerX, bounds.maxY),
            BoundingHandle.LEFT to Offset(bounds.minX, bounds.centerY),
            BoundingHandle.RIGHT to Offset(bounds.maxX, bounds.centerY),
        ).forEach { (handle, pt) ->
            val isCorner = handle in listOf(
                BoundingHandle.TOP_LEFT, BoundingHandle.TOP_RIGHT,
                BoundingHandle.BOTTOM_LEFT, BoundingHandle.BOTTOM_RIGHT
            )
            val isHovered = hovered == handle
            val side = when {
                isHovered && isCorner -> 8.5f
                isHovered || isCorner -> 7f
                else -> 5.5f
            }
            if (isHovered) {
                val ring = side + 5f
                drawRect(
                    color = Color.White,
                    topLeft = pt - Offset(ring * 0.5f, ring * 0.5f),
                    size = Size(ring, ring),
                    style = Stroke(1.8f)
                )
                drawRect(colors.accent, pt - Offset(side * 0.5f, side * 0.5f), Size(side, side))
            } else {
                drawRect(Color.White, pt - Offset(side * 0.5f, side * 0.5f), Size(side, side))
                drawRect(
                    color = colors.accent,
                    topLeft = pt - Offset(side * 0.5f, side * 0.5f),
                    size = Size(side, side),
                    style = Stroke(1f)
                )
            }
        }
    }

    // Axis lock guide. The constraint is a keyboard latch with no persistent on-screen state, so
    // without this the drag just silently refuses one of the two directions.
    if (axis != null) {
        val span = size.width + size.height
        // The pivot, not the box centre: it is already a screen point, while the box is expressed
        // in the frame and would need turning back out of it.
        val center = frame.pivot
        val from = if (axis == "x") Offset(center.x - span, center.y) else Offset(center.x, center.y - span)
        val to = if (axis == "x") Offset(center.x + span, center.y) else Offset(center.x, center.y + span)
        drawLine(colors.warning.copy(alpha = 0.7f), from, to, 1.2f)
    }
}
