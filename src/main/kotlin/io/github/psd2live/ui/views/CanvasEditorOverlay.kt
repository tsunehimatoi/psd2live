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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.VertexMode as SkiaVertexMode
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val target = editor.target()
    val isPathTool = editor.tool == CanvasTool.CREATE_DEFORM_PATH || editor.drawingPath || (editor.hierarchyMode == EditHierarchyMode.DEFORM && editor.paths().isNotEmpty())
    val textMeasurer = rememberTextMeasurer()

    Canvas(Modifier.fillMaxSize()) {
        val currentTarget = editor.target() ?: target
        // 1. Mesh wireframe & vertices when in DEFORM or EDIT on a mesh
        if ((editor.hierarchyMode == EditHierarchyMode.DEFORM || editor.hierarchyMode == EditHierarchyMode.EDIT) && currentTarget != null && currentTarget.kind == "mesh") {
            val pts = editor.screen(currentTarget.geometry.points, currentTarget, viewport)
            val brushWeights = editor.activeBrushWeights

            // 1a. Real-time translucent mesh weight coloring rendered strictly on ArtMesh geometry (transforms with mesh)
            if (brushWeights != null && currentTarget.indices.isNotEmpty() && pts.isNotEmpty()) {
                val vertexCount = minOf(currentTarget.count, pts.size)
                val positions = FloatArray(vertexCount * 2)
                val colorsArr = IntArray(vertexCount)
                for (i in 0 until vertexCount) {
                    positions[i * 2] = pts[i].x
                    positions[i * 2 + 1] = pts[i].y
                    val w = brushWeights.getOrNull(i) ?: 0f
                    if (w > 0.0001f) {
                        val normW = if (editor.strength > 0.001f) (w / editor.strength).coerceIn(0f, 1f) else w.coerceIn(0f, 1f)
                        val a = (normW * 0.62f * 255f).toInt()
                        val red = (a * 248) / 255
                        val green = (a * 24) / 255
                        val blue = (a * 24) / 255
                        colorsArr[i] = (a shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }

                val affectedIndices = mutableListOf<Short>()
                for (tri in 0 until currentTarget.indices.size step 3) {
                    val a = currentTarget.indices[tri]
                    val b = currentTarget.indices[tri + 1]
                    val c = currentTarget.indices[tri + 2]
                    if (a < vertexCount && b < vertexCount && c < vertexCount) {
                        val wa = brushWeights.getOrNull(a) ?: 0f
                        val wb = brushWeights.getOrNull(b) ?: 0f
                        val wc = brushWeights.getOrNull(c) ?: 0f
                        if (wa > 0.0001f || wb > 0.0001f || wc > 0.0001f) {
                            affectedIndices.add(a.toShort())
                            affectedIndices.add(b.toShort())
                            affectedIndices.add(c.toShort())
                        }
                    }
                }

                if (affectedIndices.isNotEmpty()) {
                    val shortIndices = ShortArray(affectedIndices.size) { affectedIndices[it] }
                    drawIntoCanvas { canvas ->
                        val skiaCanvas = canvas.skiaCanvas
                        val weightPaint = SkiaPaint().apply {
                            isAntiAlias = true
                        }
                        try {
                            skiaCanvas.drawVertices(
                                SkiaVertexMode.TRIANGLES,
                                positions,
                                colorsArr,
                                null,
                                shortIndices,
                                SkiaBlendMode.DST,
                                weightPaint
                            )
                        } finally {
                            weightPaint.close()
                        }
                    }
                }
            }

            // 1b. The faces the last topology op created - a hole fill, so far - washed faintly, so a
            //     provisional patch reads as a patch rather than as a stain. SRC_OVER, not the weight
            //     block's DST: that one modulates the art underneath, this one lays a colour over it.
            //     Nothing is drawn once the patch marker is gone; it never outlives one commit.
            val patch = editor.topologyFills?.takeIf { it.drawableId == currentTarget.id }
            if (patch != null && currentTarget.indices.isNotEmpty() && pts.isNotEmpty()) {
                val vertexCount = minOf(currentTarget.count, pts.size)
                val positions = FloatArray(vertexCount * 2)
                for (i in 0 until vertexCount) {
                    positions[i * 2] = pts[i].x
                    positions[i * 2 + 1] = pts[i].y
                }
                val corners = mutableListOf<Short>()
                val patches = mutableListOf<Path>()
                for (ordinal in patch.triangles) {
                    val base = ordinal * 3
                    if (base + 2 >= currentTarget.indices.size) continue
                    val a = currentTarget.indices[base]
                    val b = currentTarget.indices[base + 1]
                    val c = currentTarget.indices[base + 2]
                    if (a >= vertexCount || b >= vertexCount || c >= vertexCount) continue
                    corners.add(a.toShort()); corners.add(b.toShort()); corners.add(c.toShort())
                    patches.add(Path().apply {
                        moveTo(pts[a].x, pts[a].y); lineTo(pts[b].x, pts[b].y); lineTo(pts[c].x, pts[c].y); close()
                    })
                }
                if (corners.isNotEmpty()) {
                    val shortIndices = ShortArray(corners.size) { corners[it] }
                    drawIntoCanvas { canvas ->
                        val wash = SkiaPaint().apply {
                            isAntiAlias = true
                            color = colors.patchFill.toArgb()
                        }
                        try {
                            canvas.skiaCanvas.drawVertices(
                                SkiaVertexMode.TRIANGLES,
                                positions,
                                null,
                                null,
                                shortIndices,
                                SkiaBlendMode.SRC_OVER,
                                wash,
                            )
                        } finally {
                            wash.close()
                        }
                    }
                    // Outlined as well, so the patch has a readable edge over artwork of any colour.
                    patches.forEach { drawPath(it, colors.accent.copy(alpha = 0.35f), style = Stroke(1f)) }
                }
            }

            // Preview exactly the edges the brush collected and the reducer will split.
            if (editor.tool == CanvasTool.SUBDIVIDE && editor.subdivideEdges.isNotEmpty()) {
                MeshTopology.uniqueEdges(currentTarget.indices)
                    .filter { it in editor.subdivideEdges }
                    .forEach { edge ->
                        val a = pts.getOrNull(edge.endpointLow)
                        val b = pts.getOrNull(edge.endpointHigh)
                        if (a != null && b != null) {
                            drawCircle(colors.accent, 2.6f, Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f), style = Stroke(1.2f))
                        }
                    }
            }

            if (editor.elementMode == 2) {
                editor.selectedFaces.forEach { face ->
                    if (face in 0 until currentTarget.indices.size / 3) {
                        val corners = (0..2).map { pts[currentTarget.indices[face * 3 + it]] }
                        val shape = Path().apply {
                            moveTo(corners[0].x, corners[0].y)
                            lineTo(corners[1].x, corners[1].y)
                            lineTo(corners[2].x, corners[2].y)
                            close()
                        }
                        drawPath(shape, colors.accent.copy(alpha = 0.2f))
                    }
                }
            }
            MeshTopology.uniqueEdges(currentTarget.indices).forEach { edge ->
                val a = edge.endpointLow; val b = edge.endpointHigh
                val selected = editor.elementMode == 1 && edge in editor.selectedEdges
                drawLine(Color.Black.copy(alpha = 0.45f), pts[a], pts[b], 2.5f)
                drawLine(colors.accent.copy(alpha = if (selected) 1f else 0.65f), pts[a], pts[b], if (selected) 3f else 1f)
            }
            pts.forEachIndexed { i, p ->
                val isSelected = i in editor.vertices
                val isHovered = i == editor.hoveredVertex
                val w = brushWeights?.getOrNull(i) ?: 0f
                if (brushWeights != null) {
                    if (w > 0.001f) {
                        val normW = if (editor.strength > 0.001f) (w / editor.strength).coerceIn(0f, 1f) else w.coerceIn(0f, 1f)
                        val r = 1.6f + normW * 0.8f
                        drawCircle(Color.Black.copy(alpha = 0.75f), r + 0.8f, p)
                        val redFill = Color(
                            red = 1.0f - normW * 0.08f,
                            green = 1.0f - normW * 0.90f,
                            blue = 1.0f - normW * 0.88f,
                            alpha = 1f
                        )
                        drawCircle(redFill, r, p)
                    } else {
                        drawCircle(Color.Black.copy(alpha = 0.65f), 2.2f, p)
                        drawCircle(Color.White.copy(alpha = 0.9f), 1.5f, p)
                    }
                    if (isHovered) {
                        drawCircle(Color.White, 6f, p, style = Stroke(1.5f))
                    }
                } else if (isHovered) {
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

        // 2. Warp deformer or Rotation deformer when in DEFORM or EDIT
        if ((editor.hierarchyMode == EditHierarchyMode.DEFORM || editor.hierarchyMode == EditHierarchyMode.EDIT) && target != null && (target.kind == "warp" || target.kind == "rotation")) {
            val pts = editor.screen(target.geometry.points, target, viewport)
            if (target.kind == "rotation") {
                if (pts.size >= 2) {
                    drawCircle(colors.accent.copy(alpha = 0.28f), (pts[1] - pts[0]).getDistance(), pts[0], style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))))
                    drawLine(colors.accent, pts[0], pts[1], 2.5f)
                    drawCircle(colors.accent, 10f, pts[0], style = Stroke(1f))
                    if (editor.hoveredVertex == 1) drawCircle(colors.accent, 9f, pts[1], style = Stroke(2f))
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
                // Level 1: Warp lattice grid lines and vertices
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
                val warpBrushWeights = editor.activeBrushWeights
                pts.forEachIndexed { i, p ->
                    val isHovered = i == editor.hoveredVertex
                    val isSelected = i in editor.vertices
                    val w = warpBrushWeights?.getOrNull(i) ?: 0f
                    if (warpBrushWeights != null) {
                        if (w > 0.001f) {
                            val normW = if (editor.strength > 0.001f) (w / editor.strength).coerceIn(0f, 1f) else w.coerceIn(0f, 1f)
                            val r = 1.6f + normW * 0.8f
                            drawCircle(Color.Black.copy(alpha = 0.75f), r + 0.8f, p)
                            val redFill = Color(
                                red = 1.0f - normW * 0.08f,
                                green = 1.0f - normW * 0.90f,
                                blue = 1.0f - normW * 0.88f,
                                alpha = 1f
                            )
                            drawCircle(redFill, r, p)
                        } else {
                            drawCircle(Color.Black.copy(alpha = 0.65f), 2.2f, p)
                            drawCircle(Color.White.copy(alpha = 0.9f), 1.5f, p)
                        }
                        if (isHovered) {
                            drawCircle(Color.White, 6.5f, p, style = Stroke(1.5f))
                        }
                    } else if (isHovered) {
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
        if ((editor.hierarchyMode == EditHierarchyMode.SELECT || (editor.hierarchyMode == EditHierarchyMode.PAINT && editor.tool == CanvasTool.SELECT)) && editor.tool == CanvasTool.SELECT) {
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

        // 3c. Paint mode: live stroke & shape preview while dragging
        if (editor.hierarchyMode == EditHierarchyMode.PAINT && editor.isPainting) {
            val col = editor.paintColor.copy(alpha = editor.paintOpacity)
            val strokeWidth = (editor.paintSize * viewport.scale.toFloat()).coerceAtLeast(1f)

            // The tips draw nothing of their own: the pixels are already on the layer, with exactly the
            // hardness and opacity that were asked for, and anything laid over them would misreport
            // both. Only a shape - which lands when its second corner does - is previewed here.
            if (editor.tool == CanvasTool.PAINT_SHAPE && editor.paintStrokeStart != null && editor.paintStrokeCurrent != null) {
                val s = editor.paintStrokeStart!!
                val c = editor.paintStrokeCurrent!!
                val fill = editor.paintShapeFilled && editor.paintShape.canFill
                when (editor.paintShape) {
                    PaintShape.LINE -> {
                        drawLine(col, s, c, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                    }
                    PaintShape.RECTANGLE -> {
                        val l = minOf(s.x, c.x)
                        val t = minOf(s.y, c.y)
                        val w = maxOf(1f, kotlin.math.abs(c.x - s.x))
                        val h = maxOf(1f, kotlin.math.abs(c.y - s.y))
                        if (fill) drawRect(col, Offset(l, t), Size(w, h))
                        else drawRect(col, Offset(l, t), Size(w, h), style = Stroke(strokeWidth))
                    }
                    PaintShape.ELLIPSE -> {
                        val l = minOf(s.x, c.x)
                        val t = minOf(s.y, c.y)
                        val w = maxOf(1f, kotlin.math.abs(c.x - s.x))
                        val h = maxOf(1f, kotlin.math.abs(c.y - s.y))
                        if (fill) drawOval(col, Offset(l, t), Size(w, h))
                        else drawOval(col, Offset(l, t), Size(w, h), style = Stroke(strokeWidth))
                    }
                }
            }
        }

        // 3d. Paint mode: the tip under the cursor, Photoshop style - the outer ring is the brush, the
        // inner one the part of it that stays solid, and while the tip is being retuned the falloff
        // between them is painted out in the stroke's own colour and opacity.
        // While the pointer is picking, the sampling ring is the cursor, and the tip ring would only
        // argue with it about where the pointer is.
        editor.cursor?.takeIf { editor.paintBrushActive && editor.pickCursor() == null }?.let { cur ->
            // The tip is the one description of the mark: the ring is its radius, the inner circle its
            // core, and the falloff between them is the profile the stroke is rasterized with - so what
            // the cursor promises is what the pixels do. The eraser draws no colour of its own, so its
            // falloff is shown in the neutral ring colour instead.
            val tip = editor.paintTip()
            val scale = viewport.scale.toFloat()
            val r = (tip.radius * scale).coerceAtLeast(1f)
            val tint = if (editor.tool == CanvasTool.PAINT_ERASER) colors.textPrimary else editor.paintColor
            if (editor.adjustingBrush) {
                val samples = 24
                val stops = Array(samples + 1) { i ->
                    val t = i / samples.toFloat()
                    t to tint.copy(alpha = tip.alphaAt(t * tip.radius) * editor.paintOpacity * 0.55f)
                }
                drawCircle(
                    brush = Brush.radialGradient(colorStops = stops, center = cur, radius = r),
                    radius = r,
                    center = cur,
                )
            }
            drawCircle(color = Color.Black.copy(alpha = 0.7f), radius = r, center = cur, style = Stroke(3f))
            drawCircle(color = tint.copy(alpha = 0.85f), radius = r, center = cur, style = Stroke(1.2f))
            val core = tip.core * scale
            if (tip.hardness < 1f && core > 1.5f) {
                drawCircle(color = colors.accent.copy(alpha = 0.6f), radius = core, center = cur, style = Stroke(1f))
            }
        }

        // 3d-bis. Picking a colour: the sampling ring, Photoshop's - the top half is what is under the
        // pointer, the bottom half the colour in hand, so a pick can be judged before it is taken. The
        // ring follows the pointer for the whole gesture, which is what makes an eyedropper usable at
        // all on art whose every pixel is a slightly different shade.
        editor.pickCursor()?.let { cur ->
            val sampled = editor.sampleColorAt(cur, viewport)
            // A ring, not a disc: the middle stays empty so the pixel being read is the one thing the
            // cursor never covers, and the two colours ride around it - what is under the pointer on the
            // top half, what is in hand on the bottom. Sized in dp, because the ring reads a pixel and is
            // sized for the eye rather than for the zoom (or for the display's density).
            val inner = 17.dp.toPx()
            val band = 5.dp.toPx()
            val middle = inner + band / 2f
            val box = Rect(cur - Offset(middle, middle), Size(middle * 2f, middle * 2f))
            fun halfRing(color: Color, startAngle: Float) = drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = box.topLeft,
                size = box.size,
                style = Stroke(band),
            )
            // The dark band underneath is the ring's own edging: half of what it reports can be the same
            // colour as the artwork it is lying on.
            drawCircle(color = Color.Black.copy(alpha = 0.8f), radius = middle, center = cur, style = Stroke(band + 2.5.dp.toPx()))
            if (sampled != null) halfRing(sampled, 180f)
            halfRing(editor.paintColor, 0f)

            val label = sampled?.let { it.toHex() } ?: tr("editor.paint.nothingToPick")
            val layout = textMeasurer.measure(
                text = label,
                style = TextStyle(color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            )
            val origin = Offset(cur.x + middle + 6.dp.toPx(), cur.y - 12.dp.toPx())
            val extent = Size(layout.size.width + 12f, layout.size.height + 6f)
            drawRect(colors.panelElevated.copy(alpha = 0.94f), origin, extent)
            drawRect(colors.divider, origin, extent, style = Stroke(1f))
            drawText(layout, topLeft = Offset(origin.x + 6f, origin.y + 3f))
        }

        // 3e. The number the Alt + right-drag gesture is moving, next to the tip it is moving
        if (editor.adjustingBrush && editor.paintBrushActive && editor.cursor != null) {
            val cur = editor.cursor!!
            val readout = when (editor.brushAxis) {
                BrushAdjustAxis.RADIUS -> tr("editor.radius") to "${editor.paintSize.roundToInt()} px"
                BrushAdjustAxis.HARDNESS -> tr("editor.hardness") to "${(editor.paintHardness * 100f).roundToInt()} %"
                BrushAdjustAxis.OPACITY -> tr("editor.opacity") to "${(editor.paintOpacity * 100f).roundToInt()} %"
                else -> null
            }
            if (readout != null) {
                val layout = textMeasurer.measure(
                    text = "${readout.first}  ${readout.second}",
                    style = TextStyle(color = colors.textPrimary, fontSize = 11.sp),
                )
                val origin = Offset(cur.x + 16f, cur.y - 12f)
                val extent = Size(layout.size.width + 12f, layout.size.height + 6f)
                drawRect(colors.panelElevated.copy(alpha = 0.94f), origin, extent)
                drawRect(colors.divider, origin, extent, style = Stroke(1f))
                drawText(layout, topLeft = Offset(origin.x + 6f, origin.y + 3f))
            }
        }

        // Place-then-confirm ghost (Blender-style)
        editor.placement?.let { place ->
            when (place.kind) {
                CreatePlacementKind.WARP -> {
                    editor.placementScreenRect(viewport)?.let { r ->
                        drawRect(colors.accent.copy(alpha = 0.14f), r.topLeft, r.size)
                        drawRect(colors.accent, r.topLeft, r.size, style = Stroke(2f))
                        // Conversion lattice (solid) — matches created warp.rows × columns
                        val rows = place.rows.coerceAtLeast(1)
                        val cols = place.cols.coerceAtLeast(1)
                        for (row in 1 until rows) {
                            val y = r.top + r.height * (row.toFloat() / rows)
                            drawLine(colors.accent.copy(alpha = 0.55f), Offset(r.left, y), Offset(r.right, y), 1.2f)
                        }
                        for (col in 1 until cols) {
                            val x = r.left + r.width * (col.toFloat() / cols)
                            drawLine(colors.accent.copy(alpha = 0.55f), Offset(x, r.top), Offset(x, r.bottom), 1.2f)
                        }
                        // Bezier edit subdivision (dashed) when it differs from conversion
                        val bRows = place.bezierRows.coerceAtLeast(1)
                        val bCols = place.bezierCols.coerceAtLeast(1)
                        if (bRows != rows || bCols != cols) {
                            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                            for (row in 1 until bRows) {
                                val y = r.top + r.height * (row.toFloat() / bRows)
                                drawLine(
                                    colors.accent.copy(alpha = 0.28f), Offset(r.left, y), Offset(r.right, y), 1f,
                                    pathEffect = dash,
                                )
                            }
                            for (col in 1 until bCols) {
                                val x = r.left + r.width * (col.toFloat() / bCols)
                                drawLine(
                                    colors.accent.copy(alpha = 0.28f), Offset(x, r.top), Offset(x, r.bottom), 1f,
                                    pathEffect = dash,
                                )
                            }
                        }
                        listOf(
                            Offset(r.left, r.top), Offset(r.right, r.top),
                            Offset(r.left, r.bottom), Offset(r.right, r.bottom),
                            Offset(r.center.x, r.top), Offset(r.center.x, r.bottom),
                            Offset(r.left, r.center.y), Offset(r.right, r.center.y),
                        ).forEach { drawCircle(colors.accent, 4.5f, it) }
                    }
                }
                CreatePlacementKind.ROTATION -> {
                    editor.placementPivotScreen(viewport)?.let { (pivot, tip) ->
                        val radius = (tip - pivot).getDistance()
                        drawCircle(colors.accent.copy(alpha = 0.1f), radius, pivot)
                        drawCircle(colors.accent, radius, pivot, style = Stroke(1.2f))
                        drawLine(colors.accent, pivot, tip, 2f)
                        drawCircle(colors.accent, 6f, pivot)
                        drawCircle(Color(0xFF7BBB99), 5f, tip)
                    }
                }
                else -> {}
            }
        }

        // Interactive Creation Previews (legacy drag — only when no placement session)
        if (editor.placement == null && editor.isCreatingWarp && editor.creationStart != null && editor.creationCurrent != null) {
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

        // Rotation create scope: highlight every drawable the root wrap would affect
        if (editor.tool == CanvasTool.CREATE_ROTATION) {
            val (_, drawableIds) = editor.rotationScopeIds()
            val source = editor.preview ?: editor.model
            val layerByDrawable = editor.state.previewModel?.rig?.layerIdByDrawableId.orEmpty()
            for (drawableId in drawableIds) {
                val layerId = layerByDrawable[drawableId] ?: continue
                val meshTarget = editor.target(source, layerId, null) ?: continue
                val pts = editor.screen(meshTarget.geometry.points, meshTarget, viewport)
                if (pts.size < 2 || meshTarget.indices.isEmpty()) continue
                val outline = Path()
                for (t in 0 until meshTarget.indices.size / 3) {
                    val i0 = meshTarget.indices[t * 3]
                    val i1 = meshTarget.indices[t * 3 + 1]
                    val i2 = meshTarget.indices[t * 3 + 2]
                    if (i0 !in pts.indices || i1 !in pts.indices || i2 !in pts.indices) continue
                    outline.moveTo(pts[i0].x, pts[i0].y)
                    outline.lineTo(pts[i1].x, pts[i1].y)
                    outline.lineTo(pts[i2].x, pts[i2].y)
                    outline.close()
                }
                drawPath(outline, colors.warning.copy(alpha = 0.18f))
                drawPath(outline, colors.warning.copy(alpha = 0.55f), style = Stroke(1.2f))
            }
        }

        if (editor.tool == CanvasTool.BRUSH_SELECT) {
            editor.cursor?.let { center ->
                val r = (editor.radius * viewport.scale).toFloat()
                drawCircle(Color.Black.copy(alpha = 0.5f), r, center, style = Stroke(2.5f))
                drawCircle(colors.accent, r, center, style = Stroke(1.2f))
            }
        }

        // 3f. The subdivide brush's ring. It has no hardness and no shape - it takes every edge whose
        //     ends fall inside - so the radius is the whole preview.
        if (editor.tool == CanvasTool.SUBDIVIDE) {
            editor.cursor?.let { center ->
                val r = (editor.radius * viewport.scale).toFloat()
                drawCircle(Color.Black.copy(alpha = 0.5f), r, center, style = Stroke(2.5f))
                drawCircle(colors.accent, r, center, style = Stroke(1.2f))
            }
        }

        // 3g. The knife's polyline: the anchors placed so far, the red rubber band that shows where the cut
        //     would run to the pointer, and a ring that turns red and grows where a click would snap to a
        //     vertex or an edge instead of dropping a new point.
        if (editor.tool == CanvasTool.KNIFE && currentTarget != null && currentTarget.kind == "mesh") {
            val placed = editor.knifeDraft.mapNotNull { editor.knifeAnchorScreen(it, currentTarget, viewport) }
            for (index in 0 until placed.size - 1) {
                drawLine(Color.Black.copy(alpha = 0.45f), placed[index], placed[index + 1], 2.5f)
                drawLine(colors.accent, placed[index], placed[index + 1], 1.4f)
            }
            editor.knifeHover?.let { cursor ->
                // Snapped: the ring grows and turns red, so lock-on reads as a change in size as well as colour.
                val snapped = editor.knifeSnapKind != null
                drawCircle(if (snapped) colors.error else colors.textPrimary, if (snapped) 9f else 6f, cursor, style = Stroke(1.5f))
                placed.lastOrNull()?.let { last ->
                    drawLine(colors.error.copy(alpha = 0.75f), last, cursor, 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                }
            }
            placed.forEachIndexed { index, point ->
                val isLast = index == placed.lastIndex
                drawCircle(if (isLast) colors.accent else colors.textPrimary, 3.5f, point)
            }
        }

        // 4. BRUSH / SMOOTH / INFLATE mode: Shape-aware brush outline following cursor
        if (editor.tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)) {
            val center = editor.cursor ?: editor.activeBrushCenter
            center?.let { center ->
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

        // No floating readouts over the artwork. The brush gesture's numbers and the object under the
        // pointer were both drawn here as chips beside the cursor, and both covered the art they were
        // describing — the brush outline and the hovered part's own highlight already say the same
        // thing without a box in the way.
    }
    // Left Animated Hover Toolbar (edit / deform / paint tools)
    CanvasToolBar(editor = editor, keymap = keymap, focus = focus)

    // Bottom-left placement panel (Blender-style confirm)
    if (editor.placement != null) {
        PlacementSettingsPanel(editor = editor, focus = focus)
    }

    // Top Left Hierarchy / Layer Mode Toolbar
    HierarchyModeBar(editor = editor, focus = focus)

    // Bottom Status Bar
    //
    // A waiting mode's prompt is read from the request itself rather than from `error`, which any tool
    // press clears: the request stands until a pick answers it, and so has to the line asking for one.
    val waiting = editor.deferredModePrompt
    Text(
        editor.error ?: waiting ?: if (editor.busy) tr("editor.saving") else tr("editor.selectionCount", editor.objects.size, editor.vertices.size) + "   ·   " + tr(when {
            editor.tool == CanvasTool.KNIFE -> "editor.knifeGestureHint"
            editor.tool == CanvasTool.SUBDIVIDE -> "editor.subdivideHint"
            editor.tool == CanvasTool.SELECT && target?.kind == "rotation" -> "editor.rotationGestureHint"
            editor.tool == CanvasTool.CREATE_DEFORM_PATH -> "editor.pathHint"
            editor.tool == CanvasTool.INFLATE -> "editor.inflateHint"
            editor.hierarchyMode == EditHierarchyMode.PAINT -> "editor.paintHint"
            editor.hierarchyMode == EditHierarchyMode.SELECT && editor.tool == CanvasTool.SELECT -> "editor.objectHint"
            editor.tool == CanvasTool.SELECT && editor.drawsTransformBox -> "editor.transformHint"
            editor.tool == CanvasTool.CREATE_WARP -> if (editor.placement != null) "editor.placementDragHint" else "editor.createWarpHint"
            editor.tool == CanvasTool.CREATE_ROTATION -> if (editor.placement != null) "editor.placementRotationHint" else "editor.createRotationHint"
            editor.tool == CanvasTool.GLUE -> "editor.glueHint"
            else -> "editor.hint"
        }),
        // Amber rather than red: a request waiting for a part is an instruction, not a failure.
        color = when {
            editor.error != null -> colors.error
            waiting != null -> colors.warning
            else -> colors.textMuted
        },
        fontSize = 10.sp,
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.panelBackground).padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.PlacementSettingsPanel(
    editor: CanvasEditor,
    focus: () -> Unit,
) {
    val colors = LocalToolColors.current
    val place = editor.placement ?: return
    val typography = io.github.psd2live.ui.theme.LocalToolTypography.current

    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 8.dp, bottom = 36.dp)
            .widthIn(min = 220.dp, max = 280.dp)
            .frostedGlass(shape = RoundedCornerShape(8.dp), isHovered = true, elevation = 6.dp, alpha = 0.92f)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (place.kind) {
                CreatePlacementKind.WARP -> tr("editor.tool.create_warp")
                CreatePlacementKind.ROTATION -> tr("editor.tool.create_rotation")
                CreatePlacementKind.PATH -> tr("editor.pathDeform")
            },
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = tr(
                if (place.relation == CreateRelation.AS_PARENT) "editor.placementAsParentOf" else "editor.placementAsChildOf",
                place.anchorLabel,
            ),
            color = colors.accent,
            fontSize = 11.sp,
        )
        if (place.meshIds.isNotEmpty()) {
            Text(
                text = tr("editor.placementMeshCount", place.meshIds.size),
                color = colors.textMuted,
                fontSize = 10.sp,
            )
        }
        if (place.kind != CreatePlacementKind.PATH) {
            CompactTextField(
                value = place.name,
                onValueChange = { editor.updatePlacementName(it) },
                modifier = Modifier.fillMaxWidth(),
                height = 24.dp,
            )
            val partOptions = listOf("" to tr("editor.warpPart.inherit")) +
                editor.model.parts.map { it.id.raw to it.name }
            val partSelected = partOptions.firstOrNull { it.first == (place.partId ?: "") } ?: partOptions.first()
            CompactDropdown(
                items = partOptions,
                selectedItem = partSelected,
                onItemSelected = { editor.updatePlacementPart(it.first.takeIf { id -> id.isNotEmpty() }) },
                itemLabel = { it.second },
                modifier = Modifier.fillMaxWidth(),
                height = 24.dp,
            )
        }
        if (place.kind == CreatePlacementKind.WARP) {
            Text(tr("inspector.conversionDivision"), color = colors.textMuted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3 to 3, 5 to 5, 8 to 8).forEach { (r, c) ->
                    CompactToggleChip(
                        text = "${r}×${c}",
                        selected = place.rows == r && place.cols == c,
                        onToggle = { editor.updatePlacementGrid(r, c) },
                        height = 22.dp,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactNumberSpinner(
                    value = place.cols.toDouble(),
                    onValueChange = { editor.updatePlacementGrid(place.rows, it.toInt()) },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 32.0,
                    unit = "C",
                    height = 22.dp,
                )
                CompactNumberSpinner(
                    value = place.rows.toDouble(),
                    onValueChange = { editor.updatePlacementGrid(it.toInt(), place.cols) },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 32.0,
                    unit = "R",
                    height = 22.dp,
                )
            }
            Text(tr("inspector.bezierDivision"), color = colors.textMuted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2 to 2, 3 to 3, 5 to 5).forEach { (r, c) ->
                    CompactToggleChip(
                        text = "${r}×${c}",
                        selected = place.bezierRows == r && place.bezierCols == c,
                        onToggle = { editor.updatePlacementBezier(r, c) },
                        height = 22.dp,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactNumberSpinner(
                    value = place.bezierCols.toDouble(),
                    onValueChange = { editor.updatePlacementBezier(place.bezierRows, it.toInt()) },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 16.0,
                    unit = "C",
                    height = 22.dp,
                )
                CompactNumberSpinner(
                    value = place.bezierRows.toDouble(),
                    onValueChange = { editor.updatePlacementBezier(it.toInt(), place.bezierCols) },
                    modifier = Modifier.weight(1f),
                    min = 1.0,
                    max = 16.0,
                    unit = "R",
                    height = 22.dp,
                )
            }
            Text(tr("editor.placementDragHint"), color = colors.textMuted, fontSize = 10.sp)
        }
        if (place.kind == CreatePlacementKind.ROTATION) {
            Text(tr("editor.placementRotationHint"), color = colors.textMuted, fontSize = 10.sp)
        }
        if (place.kind == CreatePlacementKind.PATH) {
            Text(tr("editor.pathCreateHint"), color = colors.textMuted, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            CompactButton(
                text = tr("editor.placementCancel"),
                onClick = { editor.cancelPlacement(); focus() },
                modifier = Modifier.weight(1f),
                height = 26.dp,
            )
            CompactButton(
                text = tr("editor.placementConfirm"),
                onClick = { editor.confirmPlacement(); focus() },
                isPrimary = true,
                enabled = editor.editable && !editor.busy &&
                    (place.kind != CreatePlacementKind.PATH || editor.draft.size >= 2),
                modifier = Modifier.weight(1f),
                height = 26.dp,
            )
        }
    }
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
        // The palette is every tool walked in one fixed order, with each row's visibility following the
        // mode. Walking the mode's own list instead would be shorter, but a row that left the
        // composition has nothing left to animate out of — so the rows that a mode change adds or
        // removes would pop while the rest slid. Composing them all and hiding the ones the mode has no
        // use for makes the arriving and departing rows slide with everything else.
        val availableTools = toolbarGroups(editor.hierarchyMode).flatten().toSet()
        val lastVisibleIndex = TOOLBAR_TOOL_ORDER.indexOfLast { it in availableTools }

        TOOLBAR_TOOL_ORDER.forEachIndexed { index, tool ->
            // A divider earns its place only when both sides of it have something to separate; in
            // object mode the whole list is two select tools and every line would be a stray rule.
            if (tool in TOOLBAR_DIVIDERS) {
                val splitsGroups = index < lastVisibleIndex && TOOLBAR_TOOL_ORDER.take(index + 1).any { it in availableTools }
                AnimatedVisibility(
                    visible = splitsGroups,
                    enter = expandVertically(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(150)),
                    exit = shrinkVertically(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(120)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .height(1.dp)
                            .background(colors.border.copy(alpha = 0.35f))
                    )
                }
            }
            AnimatedVisibility(
                visible = tool in availableTools,
                enter = expandVertically(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(120)),
            ) {
                ToolItemRow(
                    tool = tool,
                    isSelected = editor.tool == tool,
                    isToolbarExpanded = animatedWidth > 42.dp,
                    textAlpha = textAlpha,
                    textOffset = textOffset,
                    isBusy = editor.busy,
                    // The shape group answers to its shapes' chords, so its row shows the one that is in
                    // hand: pressing it is how the row is reached.
                    keyLabel = keymap.labelFor(
                        if (tool == CanvasTool.PAINT_SHAPE) editor.paintShape.action else tool.action
                    ).orEmpty(),
                    brushShape = if (tool == CanvasTool.BRUSH) editor.brushShape else null,
                    paintShape = if (tool == CanvasTool.PAINT_SHAPE) editor.paintShape else null,
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
                        label = tr(shape.labelKey),
                        isSelected = editor.brushShape == shape,
                        isToolbarExpanded = animatedWidth > 42.dp,
                        textAlpha = textAlpha,
                        textOffset = textOffset,
                        isBusy = editor.busy,
                        icon = { color -> BrushShapeIcon(shape = shape, color = color, size = 14.dp) },
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

        // The shape tool's three faces live under it, exactly the way the deform brush carries its own:
        // one row in the palette for the tool, and the shapes it draws are what changes.
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.PAINT && editor.tool == CanvasTool.PAINT_SHAPE,
            enter = expandVertically(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(120)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .height(1.dp)
                        .background(colors.border.copy(alpha = 0.45f))
                )

                PaintShape.entries.forEach { shape ->
                    ShapeItemRow(
                        label = tr(shape.labelKey),
                        keyLabel = keymap.labelFor(shape.action).orEmpty(),
                        isSelected = editor.paintShape == shape,
                        isToolbarExpanded = animatedWidth > 42.dp,
                        textAlpha = textAlpha,
                        textOffset = textOffset,
                        isBusy = editor.busy,
                        icon = { color -> PaintShapeIcon(shape = shape, color = color, iconSize = 14.dp) },
                        onClick = {
                            editor.selectPaintShape(shape)
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
    paintShape: PaintShape? = null,
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
                paintShape = paintShape,
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

/**
 * One row of a tool's shape menu: the deform brush's three below the brush, the paint shape tool's three
 * below it. The icon is whatever the caller draws for the shape, in the colour the row's state calls for.
 */
@Composable
private fun ShapeItemRow(
    label: String,
    isSelected: Boolean,
    isToolbarExpanded: Boolean,
    textAlpha: Float,
    textOffset: Dp,
    isBusy: Boolean,
    icon: @Composable (Color) -> Unit,
    keyLabel: String = "",
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val itemInteractionSource = remember { MutableInteractionSource() }
    val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

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
            icon(
                when {
                    isSelected -> colors.accent
                    isItemHovered -> colors.textPrimary
                    else -> colors.textMuted
                }
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
                    Text(
                        text = keyLabel,
                        color = colors.textMuted.copy(alpha = 0.75f),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
    }
}

/** The shape tool's icon: whichever of the three faces is in hand. */
@Composable
private fun PaintShapeIcon(shape: PaintShape, color: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) { drawPaintShape(shape, color) }
}

/** The shape, drawn in an 18-unit box scaled to whatever the caller's canvas is. */
private fun DrawScope.drawPaintShape(shape: PaintShape, color: Color) {
    val s = size.width / 18f
    when (shape) {
        PaintShape.LINE -> {
            drawLine(color, Offset(3 * s, 15 * s), Offset(15 * s, 3 * s), 1.3f * s)
            drawCircle(color, 1.5f * s, Offset(3 * s, 15 * s))
            drawCircle(color, 1.5f * s, Offset(15 * s, 3 * s))
        }
        PaintShape.RECTANGLE ->
            drawRect(color, Offset(3 * s, 4 * s), Size(12 * s, 10 * s), style = Stroke(1.3f * s))
        PaintShape.ELLIPSE ->
            drawOval(color, Offset(3 * s, 4 * s), Size(12 * s, 10 * s), style = Stroke(1.3f * s))
    }
}

@Composable
private fun ToolIcon(
    tool: CanvasTool,
    color: Color,
    brushShape: BrushShape? = null,
    paintShape: PaintShape? = null,
) {
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
            CanvasTool.SUBDIVIDE -> {
                // A triangle with its edge midpoints picked out: exactly what the brush makes.
                line(9f, 3f, 3f, 15f)
                line(3f, 15f, 15f, 15f)
                line(15f, 15f, 9f, 3f)
                drawCircle(color, 1.7f * s, p(6f, 9f), style = Stroke(1.1f * s))
                drawCircle(color, 1.7f * s, p(12f, 9f), style = Stroke(1.1f * s))
                drawCircle(color, 1.7f * s, p(9f, 15f), style = Stroke(1.1f * s))
            }
            CanvasTool.KNIFE -> {
                // A blade over the seam it is opening.
                line(2.5f, 15.5f, 12f, 15.5f)
                val blade = Path().apply {
                    moveTo(4f * s, 12.5f * s)
                    lineTo(11.5f * s, 3f * s)
                    lineTo(15f * s, 6.5f * s)
                    lineTo(7.5f * s, 16f * s)
                    close()
                }
                drawPath(blade, color, style = Stroke(1.2f * s))
            }
            CanvasTool.GLUE -> {
                drawCircle(color, 3.5f * s, p(6.5f, 9f), style = Stroke(s * 1.2f))
                drawCircle(color, 3.5f * s, p(11.5f, 9f), style = Stroke(s * 1.2f))
                line(7.5f, 7f, 10.5f, 7f)
                line(7.5f, 11f, 10.5f, 11f)
            }
            CanvasTool.PAINT_BRUSH -> {
                line(6f, 12f, 15f, 3f)
                line(7.5f, 13.5f, 16.5f, 4.5f)
                line(15f, 3f, 16.5f, 4.5f)
                val tip = Path().apply {
                    moveTo(6f * s, 12f * s)
                    lineTo(3f * s, 15f * s)
                    cubicTo(2f * s, 16f * s, 4f * s, 17f * s, 5.5f * s, 15.5f * s)
                    lineTo(7.5f * s, 13.5f * s)
                    close()
                }
                drawPath(tip, color, style = Stroke(1.3f * s))
            }
            CanvasTool.PAINT_PENCIL -> {
                line(5f, 13f, 14f, 4f)
                line(7f, 15f, 16f, 6f)
                line(14f, 4f, 16f, 6f)
                val point = Path().apply {
                    moveTo(5f * s, 13f * s)
                    lineTo(2.5f * s, 15.5f * s)
                    lineTo(7f * s, 15f * s)
                    close()
                }
                drawPath(point, color, style = Stroke(1.3f * s))
                drawCircle(color, 0.8f * s, p(2.8f, 15.2f))
            }
            CanvasTool.PAINT_ERASER -> {
                val eraser = Path().apply {
                    moveTo(4f * s, 10f * s)
                    lineTo(10f * s, 4f * s)
                    lineTo(14f * s, 8f * s)
                    lineTo(8f * s, 14f * s)
                    close()
                }
                drawPath(eraser, color, style = Stroke(1.3f * s))
                line(7f, 7f, 11f, 11f)
            }
            CanvasTool.PAINT_BUCKET -> {
                val bucket = Path().apply {
                    moveTo(5f * s, 7f * s)
                    lineTo(11f * s, 4f * s)
                    lineTo(14f * s, 10f * s)
                    lineTo(8f * s, 13f * s)
                    close()
                }
                drawPath(bucket, color, style = Stroke(1.3f * s))
                drawCircle(color, 1.2f * s, p(4f, 14f))
            }
            CanvasTool.PAINT_EYEDROPPER -> {
                line(6f, 12f, 12f, 6f)
                line(8f, 14f, 14f, 8f)
                line(12f, 6f, 14f, 8f)
                val tip = Path().apply {
                    moveTo(6f * s, 12f * s)
                    lineTo(3f * s, 15f * s)
                    lineTo(8f * s, 14f * s)
                    close()
                }
                drawPath(tip, color, style = Stroke(1.2f * s))
                drawCircle(color, 1.8f * s, p(14.5f, 5.5f))
            }
            CanvasTool.PAINT_SHAPE -> {
                // The row carries the face in hand, the way the deform brush's row carries its shape.
                drawPaintShape(paintShape ?: PaintShape.LINE, color)
            }
        }
    }
}

@Composable
private fun ModeIcon(mode: EditHierarchyMode, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val s = size.width / 14f
        when (mode) {
            EditHierarchyMode.SELECT -> {
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
            EditHierarchyMode.EDIT -> {
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
            EditHierarchyMode.PAINT -> {
                val handle = Path().apply {
                    moveTo(12 * s, 2 * s)
                    lineTo(10 * s, 4 * s)
                    lineTo(7 * s, 7 * s)
                    lineTo(5 * s, 9 * s)
                    lineTo(3 * s, 12 * s)
                    lineTo(2 * s, 12 * s)
                    lineTo(2 * s, 11 * s)
                    lineTo(5 * s, 7 * s)
                    lineTo(8 * s, 4 * s)
                    close()
                }
                drawPath(handle, color, style = Stroke(1.3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(color, 1.2f * s, Offset(2.5f * s, 11.5f * s))
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
            ModeBarChip(
                mode = mode,
                text = modeLabel(mode),
                isSelected = editor.hierarchyMode == mode,
                // A mode asked for before there was anything to work on: it reads as waiting rather than
                // as in force, which is what the canvas is doing until a part is picked.
                isWaiting = editor.deferredMode?.mode == mode,
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

        // Edit mode tools
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.EDIT,
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

        // Paint mode quick controls
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.PAINT,
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Start,
            ) + fadeIn(animationSpec = tween(160)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(animationSpec = tween(120)),
        ) {
            // The session is opened here rather than in composition: the controls below only ever read
            // it, and one opened while the toolbar draws would be a state write during composition.
            LaunchedEffect(editor.state.selectedLayerId) {
                if (editor.hierarchyMode == EditHierarchyMode.PAINT) editor.ensurePaintSession()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .height(14.dp)
                        .width(1.dp)
                        .background(colors.border.copy(alpha = 0.45f))
                )
                // Foreground and background the way every paint program shows them: the colour in hand
                // in front, the other one behind it, and the swap beside them. The chord for the swap is
                // X, out on the canvas where the hand already is.
                Box(modifier = Modifier.size(26.dp, 24.dp)) {
                    PaintColorChip(
                        color = editor.paintSecondaryColor,
                        onColorChanged = { editor.paintSecondaryColor = it },
                        modifier = Modifier.align(Alignment.BottomEnd).size(17.dp),
                        popupOffset = 22.dp,
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, colors.border),
                    )
                    PaintColorChip(
                        color = editor.paintColor,
                        onColorChanged = { editor.paintColor = it },
                        modifier = Modifier.align(Alignment.TopStart).size(19.dp),
                        popupOffset = 24.dp,
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.5.dp, colors.accent),
                    )
                }
                CompactButton(
                    text = "⇄",
                    onClick = { editor.swapPaintColors(); focus() },
                    height = 20.dp,
                )
                val currentSize = editor.paintSize
                Text(
                    text = "${currentSize.toInt()}px",
                    fontSize = 10.5.sp,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                val session = editor.paintSession
                if (session != null) {
                    StructureActionChip(
                        text = tr("editor.undo"),
                        onClick = { editor.undoPaint(); focus() },
                        enabled = session.canUndo(),
                    )
                    StructureActionChip(
                        text = tr("editor.redo"),
                        onClick = { editor.redoPaint(); focus() },
                        enabled = session.canRedo(),
                    )
                    StructureActionChip(
                        text = tr("editor.paintClear"),
                        onClick = { editor.clearCurrentLayerPaint(); focus() },
                    )
                    val isDirty = session.isDirty
                    StructureActionChip(
                        text = if (isDirty) tr("editor.paint.applyCount", session.strokeCount) else tr("editor.paint.apply"),
                        onClick = { editor.promptCommitPaintSession(); focus() },
                        enabled = isDirty,
                        primary = isDirty,
                    )
                    if (isDirty) {
                        StructureActionChip(
                            text = tr("editor.paint.discard"),
                            onClick = { editor.discardPaintSession(); focus() },
                        )
                    }
                } else if (editor.hierarchyMode == EditHierarchyMode.PAINT) {
                    // The prompt asks for the one thing paint mode cannot start without, so it may only
                    // be said while paint mode is the mode in hand. Leaving it drops the session on the
                    // spot, and this row outlives that by the length of its exit animation - tested on
                    // the mode alone, the row would otherwise flash "pick a layer" at the very moment
                    // the user stops painting, when there is nothing left to pick a layer for.
                    Text(
                        text = tr("editor.paintSelectLayerHint"),
                        fontSize = 10.sp,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // Select mode target badge
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.SELECT && target != null,
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
    isWaiting: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = when {
        isSelected -> colors.accent.copy(alpha = 0.22f)
        isWaiting -> colors.warning.copy(alpha = 0.14f)
        isHovered -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> colors.accent
        isWaiting -> colors.warning
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
                when {
                    isSelected -> colors.accent.copy(alpha = 0.5f)
                    isWaiting -> colors.warning.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
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
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val colors = LocalToolColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = when {
        primary && enabled -> if (isHovered) colors.accent.copy(alpha = 0.9f) else colors.accent
        isHovered && enabled -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }
    val textColor = when {
        primary && enabled -> Color.White
        !enabled -> colors.textMuted.copy(alpha = 0.4f)
        isHovered -> colors.textPrimary
        else -> colors.textMuted
    }

    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
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
