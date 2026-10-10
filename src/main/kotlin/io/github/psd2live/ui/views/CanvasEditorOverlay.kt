package io.github.psd2live.ui.views

import io.github.psd2live.core.ComponentPalette

import io.github.psd2live.core.RigCanvasSupport

import io.github.psd2live.core.CanvasViewport

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.VertexMode as SkiaVertexMode
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import java.awt.Cursor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.*
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.components.SwingSessionPanel
import io.github.psd2live.ui.theme.frostedGlassTopBar
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.BarDivider
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.tooloptions.ModeBarToolOptions
import io.github.psd2live.ui.views.tooloptions.ToolOptionsBar
import io.github.psd2live.ui.tooloptions.topOptions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.hypot
import org.umamo.runtime.model.DrawableId
import org.umamo.edit.MeshTopology

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.CanvasEditorOverlay(
    editor: CanvasEditor,
    viewport: CanvasViewport,
    // Draw-size viewport. The composed [viewport] can still be the pre-resize centering for a frame
    // after a dock change; points have to be projected with the canvas they are painted on.
    viewportFor: (IntSize) -> CanvasViewport = { viewport },
    // Root position of the canvas. Passed so a sidebar or log toggle cannot skip this overlay:
    // zoom and pan are unchanged, and a skipped draw leaves the mesh points at the old place.
    placementOrigin: Offset = Offset.Zero,
    viewModel: PSD2LiveViewModel,
    keymap: Keymap,
    // Selection must be parameters: Compose may skip this overlay when only StateFlow selection
    // changes (editor/viewport/keymap are stable). Hovering the mode bar previously forced a
    // recomposition; reading these keys updates the target label as soon as a pick lands.
    selectedLayerId: String? = null,
    selectedLayerIds: Set<String> = emptySet(),
    selectedDeformerId: String? = null,
    showMesh: Boolean = true,
    showRotation: Boolean = true,
    focus: () -> Unit
) {
    val colors = LocalToolColors.current
    val target = editor.target(layerId = selectedLayerId, deformerId = selectedDeformerId)
    val pathEditable = target?.kind == "mesh" && (
        editor.tool == CanvasTool.CREATE_DEFORM_PATH ||
            editor.drawingPath ||
            (editor.paths().isNotEmpty() && (
                editor.hierarchyMode == EditHierarchyMode.DEFORM ||
                    editor.hierarchyMode == EditHierarchyMode.EDIT
                ))
        )
    val textMeasurer = rememberTextMeasurer()

    // The drag guide, press point to pointer, is drawn while points are carried. On the release it stays
    // where the drag ended and fades, rather than blinking out the moment the pointer stops.
    val guiding by remember(editor) {
        derivedStateOf { editor.inGesture && editor.marquee.isEmpty() && editor.selection.values.any { it.isNotEmpty() } }
    }
    val guideFade = remember { Animatable(0f) }
    var guideEnd by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(guiding) {
        if (guiding) {
            guideEnd = null
            guideFade.snapTo(1f)
        } else {
            guideEnd = editor.cursor
            guideFade.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        // placementOrigin changes when the dock moves this canvas. The read keeps the draw from
        // being reused at the previous window position.
        placementOrigin.x
        val viewport = viewportFor(IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
        editor.viewport = viewport
        val currentTarget = editor.target() ?: target
        // 1. Mesh wireframe & vertices — same toggle as the global mesh channel, no mode privilege.
        //    Edit draws every mesh it is editing the same way: they are all editable, and a glued point -
        //    one point both meshes share - is drawn once, as the single handle it is.
        val primaryMesh = currentTarget?.takeIf { it.kind == "mesh" }
        val editing = editor.hierarchyMode == EditHierarchyMode.EDIT
        // Simulate holds several meshes like Edit does, and always shows the painted group on them: the
        // weights are what the mode is about, so they do not wait for the mesh toggle.
        val simulating = editor.hierarchyMode == EditHierarchyMode.SIMULATE
        val meshTargets = (if (editing || simulating) editor.editMeshTargets() else emptyList()).ifEmpty { listOfNotNull(primaryMesh) }
        if ((showMesh || simulating) && (editor.hierarchyMode == EditHierarchyMode.DEFORM || editing || simulating) && meshTargets.isNotEmpty()) {
            val screens = meshTargets.associate { it.id to editor.screen(it.geometry.points, it, viewport) }
            val selection = editor.selection
            val gluePair = if (editor.tool == CanvasTool.GLUE) editor.glueMeshPair() else null
            val meshColors = if (editing || simulating) editor.editMeshColors() else emptyMap()
            // Simulate: each mesh's group as it stands (or as the stroke in hand will leave it), and what a
            // press right here would reach - the brush's hover preview.
            val groupWeights = if (simulating) meshTargets.associate { it.id to editor.paintedWeights(it.id) } else emptyMap()
            val weightReach = if (simulating) editor.weightBrushPreview(viewport) else emptyMap()
            val weightTint = weightModeColor(editor.weightStrokeModeShown(), colors)
            val readout = if (simulating && editor.tool == CanvasTool.WEIGHT_PAINT) editor.weightUnderCursor(viewport) else null

            // 1a. Weights washed onto the artwork. The deform brush shows its falloff in red while it is
            //     live, and while Alt + right-drag retunes it, what a press right there would pull - falloff
            //     and connected-only reach included; the glue weight brush shows each side's weld weight in
            //     that side's colour.
            val retuneWeights = when {
                !editor.adjustingBrush -> emptyMap()
                editor.tool in DEFORM_BRUSH_TOOLS -> editor.brushPreviewWeights(viewport)
                editor.tool == CanvasTool.WEIGHT_PAINT -> weightReach
                else -> emptyMap()
            }
            for (t in meshTargets) {
                val pts = screens.getValue(t.id)
                if (pts.isEmpty() || t.indices.isEmpty()) continue
                val brushWeights = editor.activeMeshBrushWeights[t.id]
                    ?: editor.activeBrushWeights?.takeIf { t.id == primaryMesh?.id }
                    ?: retuneWeights[t.id]
                if (brushWeights != null) {
                    drawWeightWash(pts.take(t.count), t.indices, BrushWeightColor) { i ->
                        val w = brushWeights.getOrNull(i) ?: 0f
                        if (w <= 0.0001f) 0f
                        else (if (editor.strength > 0.001f) (w / editor.strength).coerceIn(0f, 1f) else w.coerceIn(0f, 1f)) * 0.62f
                    }
                }
                if (simulating) {
                    // Blender's weight colours: blue where the group is empty, through green and yellow, to red
                    // where it is full. A mesh without the group yet reads as all blue.
                    val weights = groupWeights[t.id] ?: FloatArray(t.count)
                    drawWeightHeat(pts.take(minOf(t.count, weights.size)), t.indices, weights)
                }
                if (editor.tool == CanvasTool.GLUE && editor.glueSubTool == GlueSubTool.WEIGHT) {
                    val shown = when (editor.glueWeightMode) {
                        GlueWeightMode.A -> t.id == gluePair?.first
                        GlueWeightMode.B -> t.id == gluePair?.second
                        GlueWeightMode.BALANCE -> t.id == gluePair?.first || t.id == gluePair?.second
                    }
                    val weights = if (shown) editor.glueWeights(t.id) else null
                    val color = editor.glueRoleColor(t.id)
                    if (weights != null && color != null) {
                        drawWeightWash(pts.take(minOf(t.count, weights.size)), t.indices, color) { i -> weights[i] * 0.62f }
                    }
                }
            }

            // 1b. The faces the last topology op created - a hole fill, so far - washed faintly, so a
            //     provisional patch reads as a patch rather than as a stain. SRC_OVER, not the weight
            //     wash's DST: that one modulates the art underneath, this one lays a colour over it.
            //     Nothing is drawn once the patch marker is gone; it never outlives one commit.
            val patch = editor.topologyFills
            val patchTarget = patch?.let { p -> meshTargets.firstOrNull { it.id == p.drawableId } }
            if (patch != null && patchTarget != null && patchTarget.indices.isNotEmpty()) {
                val pts = screens.getValue(patchTarget.id)
                val vertexCount = minOf(patchTarget.count, pts.size)
                val positions = FloatArray(vertexCount * 2)
                for (i in 0 until vertexCount) {
                    positions[i * 2] = pts[i].x
                    positions[i * 2 + 1] = pts[i].y
                }
                val corners = mutableListOf<Short>()
                val patches = mutableListOf<Path>()
                for (ordinal in patch.triangles) {
                    val base = ordinal * 3
                    if (base + 2 >= patchTarget.indices.size) continue
                    val a = patchTarget.indices[base]
                    val b = patchTarget.indices[base + 1]
                    val c = patchTarget.indices[base + 2]
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
            if (primaryMesh != null && editor.tool == CanvasTool.SUBDIVIDE && editor.subdivideEdges.isNotEmpty()) {
                val pts = screens[primaryMesh.id].orEmpty()
                MeshTopology.uniqueEdges(primaryMesh.indices)
                    .filter { it in editor.subdivideEdges }
                    .forEach { edge ->
                        val a = pts.getOrNull(edge.endpointLow)
                        val b = pts.getOrNull(edge.endpointHigh)
                        if (a != null && b != null) {
                            drawCircle(colors.accent, 2.6f, Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f), style = Stroke(1.2f))
                        }
                    }
            }

            if (primaryMesh != null && editor.elementMode == 2) {
                val pts = screens[primaryMesh.id].orEmpty()
                editor.selectedFaces.forEach { face ->
                    if (face in 0 until primaryMesh.indices.size / 3) {
                        val corners = (0..2).mapNotNull { pts.getOrNull(primaryMesh.indices[face * 3 + it]) }
                        if (corners.size == 3) {
                            drawSelectedFace(corners[0], corners[1], corners[2], colors.accent)
                        }
                    }
                }
            }

            // 1c. Edges. Every edited mesh is drawn alike, each in its own colour when there are several.
            for (t in meshTargets) {
                if (simulating && !showMesh) continue
                val pts = screens.getValue(t.id)
                val meshColor = meshColors[t.id] ?: MeshLook.Structure
                val primary = t.id == primaryMesh?.id
                // One draw per kind of stroke rather than two per edge: a dense mesh has thousands of edges,
                // and this runs on every frame the overlay draws (each cursor move).
                val edges = cachedUniqueEdges(t.indices)
                val plain = ArrayList<Offset>(edges.size * 2)
                val chosen = ArrayList<Offset>()
                val selectedEdges = if (primary && editor.elementMode == 1) editor.selectedEdges else emptySet()
                for (edge in edges) {
                    val a = pts.getOrNull(edge.endpointLow) ?: continue
                    val b = pts.getOrNull(edge.endpointHigh) ?: continue
                    val into = if (selectedEdges.isNotEmpty() && edge in selectedEdges) chosen else plain
                    into.add(a); into.add(b)
                }
                drawMeshWires(plain, meshColor)
                drawSelectedWires(chosen, colors.accent)
            }

            // 1d. Vertices, in the shared look (MeshLook). A glued point is skipped here and drawn once in 1e.
            //     A brush shows what it would move - on hover, and while its stroke is in hand - as a ring on each
            //     point it reaches, as the weight brush does.
            val welds = if (editing) editor.weldGroups() else io.github.psd2live.core.WeldGroups.EMPTY
            val hovered = editor.hoveredMeshVertex
            val hoveredGroup = hovered?.let { welds.members(it) }.orEmpty()
            val hoverReach = editor.brushHoverReach(viewport)
            // The selection brush rings, in the selection's colour, the points a press would take.
            val selectReach = if (editor.tool == CanvasTool.BRUSH_SELECT && !editor.inGesture)
                editor.cursor?.let { it to (editor.radius * viewport.scale).toFloat() } else null
            for (t in meshTargets) {
                val pts = screens.getValue(t.id)
                val selected = selection[t.id].orEmpty()
                val meshColor = meshColors[t.id] ?: MeshLook.Structure
                val primary = t.id == primaryMesh?.id
                val reach = editor.activeMeshBrushWeights[t.id] ?: editor.activeBrushWeights?.takeIf { primary } ?: hoverReach[t.id]
                val stroked = when (t.id) {
                    gluePair?.first -> editor.glueStrokeA
                    gluePair?.second -> editor.glueStrokeB
                    else -> emptySet()
                }
                if (simulating) {
                    val weights = groupWeights[t.id]
                    val weightReachHere = weightReach[t.id]
                    pts.forEachIndexed { i, p ->
                        if (showMesh) {
                            drawCircle(MeshLook.Halo, MeshLook.POINT + MeshLook.HALO_RIM, p)
                            drawCircle(weightHeatColor(weights?.getOrNull(i) ?: 0f), MeshLook.POINT, p)
                        }
                        if (!editor.adjustingBrush) drawReachRing(p, weightReachHere?.getOrNull(i) ?: 0f, weightTint)
                    }
                    continue
                }
                pts.forEachIndexed { i, p ->
                    val vertex = io.github.psd2live.core.MeshVertex(t.id, i)
                    if (editing && welds.isWelded(vertex) && welds.members(vertex).any { it.mesh in screens }) return@forEachIndexed
                    val isHovered = vertex == hovered || (primary && hovered == null && i == editor.hoveredVertex)
                    drawMeshHandle(p, meshColor, colors.accent, selected = i in selected || i in stroked, hovered = isHovered)
                    reach?.getOrNull(i)?.let { w -> drawReachRing(p, editor.reachShown(w), MeshLook.Reach) }
                    selectReach?.let { (center, r) -> if ((p - center).getDistance() <= r) drawReachRing(p, 1f, colors.accent) }
                }
            }

            // 1e. Glued points: one diamond per point, wherever its members sit. Members that drifted
            //     apart (an older glue) are tied with a line so the pull the weld will apply is visible.
            if (editing) {
                for (group in welds.groups) {
                    val points = group.mapNotNull { member -> screens[member.mesh]?.getOrNull(member.index) }
                    if (points.isEmpty()) continue
                    val anchor = points.first()
                    points.drop(1).forEach { other ->
                        if ((other - anchor).getDistance() > 1.5f) drawLine(GlueColorWeld.copy(alpha = 0.8f), anchor, other, 1.2f)
                    }
                    val selected = group.any { it.index in selection[it.mesh].orEmpty() }
                    val hot = group.any { it in hoveredGroup } ||
                        group.any { (it.mesh == gluePair?.first && it.index in editor.glueStrokeA) || (it.mesh == gluePair?.second && it.index in editor.glueStrokeB) }
                    // The shared point look (MeshLook), in the glue's colour and a diamond's shape.
                    val radius = if (selected) 5.2f else 4.2f
                    if (hot) {
                        drawCircle(MeshLook.Halo, MeshLook.HOVER_RING + 1.4f, anchor, style = Stroke(3f))
                        drawCircle(Color.White, MeshLook.HOVER_RING + 1.4f, anchor, style = Stroke(1.4f))
                    }
                    drawGluePoint(if (selected) Color.White else MeshLook.Halo, radius + 1.4f, anchor)
                    drawGluePoint(if (selected) colors.accent else GlueColorWeld, radius, anchor)
                }
            }

            // The weight under the pointer: the nearest point is ringed and its value set beside it, small,
            // so the number sits on the point it describes rather than over the art.
            readout?.let { (point, weight) ->
                drawCircle(Color.Black.copy(alpha = 0.7f), 6.2f, point, style = Stroke(3f))
                drawCircle(Color.White, 6.2f, point, style = Stroke(1.3f))
                val layout = textMeasurer.measure(
                    text = "%.2f".format(weight),
                    style = TextStyle(color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                )
                val origin = Offset(point.x + 8f, point.y - layout.size.height - 4f)
                drawRoundRect(
                    Color.Black.copy(alpha = 0.62f), origin - Offset(3f, 1f),
                    Size(layout.size.width + 6f, layout.size.height + 2f), CornerRadius(3f, 3f),
                )
                drawText(layout, topLeft = origin)
            }

            // Dragging guide line. Until the release's fade has frozen its end, it follows the pointer.
            val guideAlpha = if (guiding) 1f else guideFade.value
            val end = (if (guiding) null else guideEnd) ?: editor.cursor
            if (guideAlpha > 0f && end != null) {
                drawLine(
                    color = colors.accent.copy(alpha = 0.6f * guideAlpha),
                    start = editor.dragStartPos,
                    end = end,
                    strokeWidth = 1.5f
                )
            }
        }

        // 2. Warp deformer or Rotation deformer when in DEFORM or EDIT.
        // Rotation interactive guide uses the same showRotation toggle as the global channel.
        if ((editor.hierarchyMode == EditHierarchyMode.DEFORM || editor.hierarchyMode == EditHierarchyMode.EDIT) && target != null && (target.kind == "warp" || target.kind == "rotation")) {
            val pts = editor.screen(target.geometry.points, target, viewport)
            if (target.kind == "rotation") {
                if (showRotation && pts.size >= 2) {
                    val guide = editor.rotationGuideScreen(target, viewport)
                    drawRotationArrow(
                        pivot = guide[0],
                        tip = guide[1],
                        color = colors.accent,
                        tipColor = Color(0xFF7BBB99),
                        hoveredTip = editor.hoveredVertex == 1 || (editor.inGesture && editor.vertices == setOf(1)),
                        hoveredPivot = editor.hoveredVertex == 0 || (editor.inGesture && editor.vertices.contains(0)),
                        isAltHeld = editor.altHeld,
                    )
                }
            } else if (editor.hierarchyMode == EditHierarchyMode.DEFORM && editor.editLevel == 2) {
                // LEVEL 2: Live2D Cubism-style Bezier Deformer
                editor.ensureBezierState()
                val bState = editor.bezierState
                if (bState != null) {
                    // The lattice the curves bend, faint and neutral: it is there for reference and takes no edits.
                    val columns = target.geometry.columns!! + 1
                    val lattice = colors.textMuted.copy(alpha = 0.28f)
                    pts.indices.flatMap { i ->
                        listOfNotNull(
                            if (i % columns < columns - 1) i to i + 1 else null,
                            if (i + columns < pts.size) i to i + columns else null
                        )
                    }.forEach { (a, b) -> drawLine(lattice, pts[a], pts[b], 0.8f) }

                    fun screenOf(x: Float, y: Float) = editor.screen(floatArrayOf(x, y), target, viewport)[0]
                    // Each curve over a dark halo, so it reads on light and dark art alike.
                    fun curve(p0: Offset, c0: Offset, c1: Offset, p1: Offset) {
                        val path = Path().apply { moveTo(p0.x, p0.y); cubicTo(c0.x, c0.y, c1.x, c1.y, p1.x, p1.y) }
                        drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(3.6f))
                        drawPath(path, BezierCurveColor, style = Stroke(1.6f))
                    }
                    for (r in 0..bState.bezierRows) {
                        for (c in 0 until bState.bezierCols) {
                            val a0 = bState.anchors[r to c] ?: continue
                            val a1 = bState.anchors[r to (c + 1)] ?: continue
                            val p0 = screenOf(a0.x, a0.y)
                            val p1 = screenOf(a1.x, a1.y)
                            val c0 = bState.handles[Triple(r, c, BezierHandleDir.RIGHT)]?.let { screenOf(it.x, it.y) } ?: (p0 + (p1 - p0) / 3f)
                            val c1 = bState.handles[Triple(r, c + 1, BezierHandleDir.LEFT)]?.let { screenOf(it.x, it.y) } ?: (p1 - (p1 - p0) / 3f)
                            curve(p0, c0, c1, p1)
                        }
                    }
                    for (r in 0 until bState.bezierRows) {
                        for (c in 0..bState.bezierCols) {
                            val a0 = bState.anchors[r to c] ?: continue
                            val a1 = bState.anchors[(r + 1) to c] ?: continue
                            val p0 = screenOf(a0.x, a0.y)
                            val p1 = screenOf(a1.x, a1.y)
                            val c0 = bState.handles[Triple(r, c, BezierHandleDir.BOTTOM)]?.let { screenOf(it.x, it.y) } ?: (p0 + (p1 - p0) / 3f)
                            val c1 = bState.handles[Triple(r + 1, c, BezierHandleDir.TOP)]?.let { screenOf(it.x, it.y) } ?: (p1 - (p1 - p0) / 3f)
                            curve(p0, c0, c1, p1)
                        }
                    }

                    // Tangent handles: amber stems ending in small hollow rings, lit when hovered or held.
                    bState.handles.forEach { (key, handle) ->
                        val anchor = bState.anchors[key.first to key.second] ?: return@forEach
                        val ap = screenOf(anchor.x, anchor.y)
                        val hp = screenOf(handle.x, handle.y)
                        val lit = editor.hoveredBezierHandle == key || editor.activeBezierHandle == key
                        drawLine(BezierHandleColor.copy(alpha = if (lit) 1f else 0.7f), ap, hp, 1.1f)
                        drawCircle(Color.Black.copy(alpha = 0.55f), if (lit) 6f else 4.6f, hp)
                        if (lit) drawCircle(BezierHandleColor, 4.4f, hp)
                        else drawCircle(BezierHandleColor, 3f, hp, style = Stroke(1.4f))
                    }

                    // Anchors: white with the curve's colour round them, larger when hovered or held.
                    bState.anchors.forEach { (key, anchor) ->
                        val ap = screenOf(anchor.x, anchor.y)
                        val lit = editor.hoveredBezierAnchor == key || editor.activeBezierAnchor == key
                        val r = if (lit) 5.6f else 4.2f
                        drawCircle(Color.Black.copy(alpha = 0.55f), r + 1.8f, ap)
                        drawCircle(if (lit) BezierCurveColor else Color.White, r, ap)
                        drawCircle(if (lit) Color.White else BezierCurveColor, r, ap, style = Stroke(1.5f))
                    }
                }
            } else {
                // Level 1: the lattice in the mesh's look, its control points square.
                val columns = target.geometry.columns!! + 1
                val wires = ArrayList<Offset>()
                pts.indices.forEach { i ->
                    if (i % columns < columns - 1) { wires += pts[i]; wires += pts[i + 1] }
                    if (i + columns < pts.size) { wires += pts[i]; wires += pts[i + columns] }
                }
                drawMeshWires(wires, MeshLook.Structure)
                val reach = editor.activeBrushWeights ?: editor.brushHoverReach(viewport)[target.id]
                pts.forEachIndexed { i, p ->
                    drawMeshHandle(p, MeshLook.Structure, colors.accent, selected = i in editor.vertices,
                        hovered = i == editor.hoveredVertex, shape = HandleShape.SQUARE)
                    reach?.getOrNull(i)?.let { w -> drawReachRing(p, editor.reachShown(w), MeshLook.Reach) }
                }
            }
        }

        // 3. Transform box (shared by TRANSFORM tool and points)
        if (editor.drawsTransformBox) {
            editor.transformFrame(viewport)?.let { frame ->
                drawTransformBox(frame, editor.hoveredHandle, colors, axis = editor.axis.takeIf { editor.inGesture }, pointer = editor.cursor,
                    active = editor.inGesture)
                // The drag's numbers beside the pointer, in the brush readout's pill.
                val readout = editor.transformReadout(viewport)
                val cur = editor.cursor
                if (readout != null && cur != null) {
                    val layout = textMeasurer.measure(readout, TextStyle(color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                    val origin = Offset(cur.x + 18f, cur.y + 14f)
                    val extent = Size(layout.size.width + 14f, layout.size.height + 6f)
                    drawRoundRect(Color.Black.copy(alpha = 0.3f), origin + Offset(0f, 1f), extent, CornerRadius(5f))
                    drawRoundRect(colors.panelElevated.copy(alpha = 0.94f), origin, extent, CornerRadius(5f))
                    drawRoundRect(colors.divider, origin, extent, CornerRadius(5f), style = Stroke(1f))
                    drawText(layout, topLeft = Offset(origin.x + 7f, origin.y + 3f))
                }
            }
        }

        // 3b. Object mode: the selection, outlined in the colour of the part it is. Object mode has no
        //     transform box, so this outline is the whole of the selection feedback — it is what tells a
        //     picked layer apart from the ones merely drawn, and it carries across a multi-select.
        if (editor.hierarchyMode == EditHierarchyMode.SELECT && editor.tool == CanvasTool.SELECT) {
            editor.objects.forEach { layerId ->
                val item = editor.target(editor.model, layerId, null) ?: return@forEach
                // Rotation is an arrow, not a mesh AABB — a box around the two axis points stretches
                // as the arm turns and reads as a broken length.
                if (item.kind == "rotation") {
                    val guide = editor.rotationGuideScreen(item, viewport)
                    if (guide.size >= 2) {
                        val awt = ComponentPalette.strong(layerId)
                        drawRotationArrow(
                            pivot = guide[0],
                            tip = guide[1],
                            color = Color(awt.red, awt.green, awt.blue),
                            tipColor = Color(0xFF7BBB99),
                        )
                    }
                    return@forEach
                }
                val points = editor.screen(item.geometry.points, item, viewport)
                if (item.kind == "mesh") {
                    val awt = ComponentPalette.strong(layerId)
                    val color = Color(awt.red, awt.green, awt.blue)
                    val area = Path()
                    val edges = mutableMapOf<Pair<Int, Int>, Int>()
                    for (offset in item.indices.indices step 3) {
                        val indices = (0..2).map { item.indices[offset + it] }
                        if (indices.any { it !in points.indices }) continue
                        area.moveTo(points[indices[0]].x, points[indices[0]].y)
                        for (index in indices.drop(1)) area.lineTo(points[index].x, points[index].y)
                        area.close()
                        for (i in 0..2) {
                            val a = indices[i]; val b = indices[(i + 1) % 3]
                            val edge = minOf(a, b) to maxOf(a, b)
                            edges[edge] = (edges[edge] ?: 0) + 1
                        }
                    }
                    drawPath(area, color.copy(alpha = 0.08f))
                    edges.filterValues { it == 1 }.keys.forEach { (a, b) ->
                        drawLine(color, points[a], points[b], strokeWidth = 1.6f)
                    }
                    return@forEach
                }
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

        // Where Create glue would weld: each mark is one point both meshes will share.
        if (editor.tool == CanvasTool.GLUE) {
            editor.gluePreviewMarks(viewport).forEach { mark ->
                drawCircle(Color.Black.copy(alpha = 0.5f), 4.2f, mark, style = Stroke(2.2f))
                drawCircle(GlueColorWeld, 3.4f, mark, style = Stroke(1.3f))
            }
        }

        // 3c. Paint mode: live stroke & shape preview while dragging
        if (editor.hierarchyMode == EditHierarchyMode.PAINT && editor.isPainting) {
            val col = editor.paintColor.copy(alpha = editor.paintOpacity)
            val strokeWidth = ((editor.paintSize / editor.paintPixelsPerUnit).coerceIn(1f, 512f) * viewport.scale.toFloat()).coerceAtLeast(1f)

            // The tips draw nothing of their own: the pixels are already on the layer, with exactly the
            // hardness and opacity that were asked for, and anything laid over them would misreport
            // both. Only a shape - which lands when its second corner does - is previewed here.
            if (editor.tool == CanvasTool.PAINT_SHAPE && editor.paintStrokeStart != null && editor.paintStrokeCurrent != null) {
                val s = editor.paintStrokeStart!!
                val c = editor.paintStrokeCurrent!!
                val fill = editor.paintShapeFilled && editor.paintShape.canFill
                val frame = editor.paintSession?.frame ?: io.github.psd2live.project.LayerTransform.IDENTITY
                if (editor.paintShape != PaintShape.LINE && !frame.isIdentity && !frame.isAxisAligned) {
                    // A turned layer takes the shape in its own frame, so the box turns with the layer: drawn there,
                    // through the layer's transform and the viewport.
                    val scale = viewport.scale.toFloat()
                    val inverse = frame.inverse()
                    fun inFrame(p: Offset): Offset {
                        val x = viewport.canvasX(p.x); val y = viewport.canvasY(p.y)
                        return Offset(inverse.x(x, y), inverse.y(x, y))
                    }
                    val p0 = inFrame(s); val p1 = inFrame(c)
                    val matrix = androidx.compose.ui.graphics.Matrix().apply {
                        this[0, 0] = frame.a * scale; this[0, 1] = frame.b * scale
                        this[1, 0] = frame.c * scale; this[1, 1] = frame.d * scale
                        this[3, 0] = viewport.offsetX.toFloat() + frame.e * scale; this[3, 1] = viewport.offsetY.toFloat() + frame.f * scale
                    }
                    val unit = scale * kotlin.math.sqrt(kotlin.math.abs(frame.a * frame.d - frame.b * frame.c))
                    val topLeft = Offset(minOf(p0.x, p1.x), minOf(p0.y, p1.y))
                    val size = Size(maxOf(1f / unit, kotlin.math.abs(p1.x - p0.x)), maxOf(1f / unit, kotlin.math.abs(p1.y - p0.y)))
                    val style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(strokeWidth / unit)
                    withTransform({ transform(matrix) }) {
                        if (editor.paintShape == PaintShape.RECTANGLE) drawRect(col, topLeft, size, style = style)
                        else drawOval(col, topLeft, size, style = style)
                    }
                } else when (editor.paintShape) {
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
        // While the pointer is the eyedropper, the pipette is the cursor, and the tip ring would only
        // argue with it about what a click will do.
        editor.cursor?.takeIf { editor.paintBrushActive && !editor.eyedropperArmed }?.let { cur ->
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
                        if (place.kind == CreatePlacementKind.WARP) {
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
                        }
                    }
                    // The same box the Transform tool draws; a ghost is an upright rectangle, so it has no turn.
                    editor.placementFrame(viewport)?.let { frame ->
                        drawTransformBox(frame, editor.placementHover, colors, PLACEMENT_HANDLES, outline = false, active = editor.inGesture)
                    }
                }
                CreatePlacementKind.ROTATION -> {
                    editor.placementPivotScreen(viewport)?.let { (pivot, tip) ->
                        drawRotationArrow(pivot, tip, colors.accent, Color(0xFF7BBB99))
                    }
                }
                else -> {}
            }
        }

        // Swing session: the rest outline, both extremes with their centerlines, the pinned edge and the handles.
        viewModel.swingSession?.gizmo?.let { gizmo ->
            fun path(points: List<Pair<Float, Float>>, closed: Boolean) = Path().apply {
                points.forEachIndexed { i, p -> val o = editor.swingScreen(p, viewport); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                if (closed) close()
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
            drawPath(path(gizmo.outline(0f), true), colors.textMuted.copy(alpha = 0.7f), style = Stroke(1.2f, pathEffect = dash))
            drawPath(path(gizmo.outline(-1f), true), colors.accent.copy(alpha = 0.35f), style = Stroke(1.2f))
            drawPath(path(gizmo.centerline(-1f), false), colors.accent.copy(alpha = 0.35f), style = Stroke(1f))
            drawPath(path(gizmo.outline(1f), true), colors.accent.copy(alpha = 0.9f), style = Stroke(1.6f))
            drawPath(path(gizmo.centerline(1f), false), colors.accent.copy(alpha = 0.9f), style = Stroke(1.4f))
            drawPath(path(gizmo.pinnedEdge, false), colors.accent, style = Stroke(4f, cap = StrokeCap.Round))
            drawPath(path(gizmo.axis, false), colors.accent.copy(alpha = 0.6f), style = Stroke(1.2f, pathEffect = dash))
            val lit = editor.swingHandle ?: editor.swingHover
            for ((handle, o) in editor.swingHandles(viewport)) {
                // Hover and drag read like every other canvas handle: an accent fill inside a white outer ring.
                val hot = handle == lit
                fun square(side: Float) {
                    val half = Offset(side / 2f, side / 2f)
                    drawRect(Color.White, o - half - Offset(2.5f, 2.5f), Size(side + 5f, side + 5f), style = Stroke(1.8f))
                    drawRect(colors.accent, o - half, Size(side, side))
                }
                when (handle) {
                    SwingGizmo.Handle.TIP -> {
                        drawCircle(colors.accent, 7f, o)
                        drawCircle(Color.White, if (hot) 10f else 7f, o, style = Stroke(if (hot) 2f else 1.5f))
                    }
                    SwingGizmo.Handle.MID -> {
                        fun diamond(r: Float) = Path().apply { moveTo(o.x, o.y - r); lineTo(o.x + r, o.y); lineTo(o.x, o.y + r); lineTo(o.x - r, o.y); close() }
                        drawPath(diamond(6f), colors.accent)
                        if (hot) drawPath(diamond(9.5f), Color.White, style = Stroke(1.8f))
                        else drawPath(diamond(6f), Color.White, style = Stroke(1.2f))
                    }
                    SwingGizmo.Handle.MOVE -> {
                        // A filled dot with a cross: it moves the whole rectangle.
                        drawCircle(colors.accent, 6f, o)
                        drawCircle(Color.White, if (hot) 9.5f else 6f, o, style = Stroke(if (hot) 1.8f else 1.2f))
                        drawLine(Color.White, o - Offset(3.5f, 0f), o + Offset(3.5f, 0f), 1.2f)
                        drawLine(Color.White, o - Offset(0f, 3.5f), o + Offset(0f, 3.5f), 1.2f)
                    }
                    SwingGizmo.Handle.AXIS -> {
                        // A hollow ring: it turns the axis rather than posing the art.
                        drawCircle(colors.panelElevated.copy(alpha = 0.85f), 5.5f, o)
                        drawCircle(colors.accent, 5.5f, o, style = Stroke(2f))
                        if (hot) drawCircle(Color.White, 9f, o, style = Stroke(1.8f))
                    }
                    SwingGizmo.Handle.CORNER_START, SwingGizmo.Handle.CORNER_END -> {
                        if (hot) square(8f)
                        else {
                            drawRect(colors.accent, Offset(o.x - 4f, o.y - 4f), Size(8f, 8f))
                            drawRect(Color.White, Offset(o.x - 4f, o.y - 4f), Size(8f, 8f), style = Stroke(1f))
                        }
                    }
                    else -> {
                        // Pivot choices: the pinned edge is already drawn; the others are hollow squares to click.
                        if (handle.name.removePrefix("PIVOT_") == gizmo.fulcrum.name) continue
                        if (hot) square(10f)
                        else {
                            drawRect(colors.panelElevated.copy(alpha = 0.85f), Offset(o.x - 5f, o.y - 5f), Size(10f, 10f))
                            drawRect(colors.accent.copy(alpha = 0.8f), Offset(o.x - 5f, o.y - 5f), Size(10f, 10f), style = Stroke(1.2f))
                        }
                    }
                }
            }
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
        if (editor.tool == CanvasTool.SUBDIVIDE || editor.tool == CanvasTool.GLUE) {
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

        // 3h. The weight gradient: the drag from full to empty, with the two lines the ramp runs between.
        editor.weightGradient?.let { (from, to) ->
            val axis = to - from
            val length = axis.getDistance()
            if (length > 1f) {
                val normal = Offset(-axis.y / length, axis.x / length) * 36f
                val full = weightHeatColor(editor.strength)
                drawLine(Color.Black.copy(alpha = 0.55f), from, to, 3.2f, cap = StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.9f), from, to, 1.4f, cap = StrokeCap.Round)
                drawLine(full, from - normal, from + normal, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                drawLine(weightHeatColor(0f), to - normal, to + normal, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                drawCircle(Color.Black.copy(alpha = 0.7f), 5.2f, from)
                drawCircle(full, 4f, from)
                drawCircle(Color.Black.copy(alpha = 0.7f), 5.2f, to, style = Stroke(2.8f))
                drawCircle(weightHeatColor(0f), 5.2f, to, style = Stroke(1.4f))
            }
        }

        // 4. BRUSH / SMOOTH / INFLATE / weight brush: Shape-aware brush outline following cursor
        if (editor.tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE, CanvasTool.WEIGHT_PAINT)) {
            val center = editor.cursor ?: editor.activeBrushCenter
            center?.let { center ->
                val r = (editor.radius * viewport.scale).toFloat()
                val ring = when {
                    editor.tool == CanvasTool.WEIGHT_PAINT -> weightModeColor(editor.weightStrokeModeShown(), colors)
                    editor.tool == CanvasTool.INFLATE && editor.shrinks -> colors.warning
                    else -> colors.textPrimary
                }

                when (editor.brushShape) {
                    BrushShape.CIRCLE -> {
                        if (editor.adjustingBrush) {
                            val samples = 24
                            val stops = Array(samples + 1) { i ->
                                val t = i / samples.toFloat()
                                t to Color.Red.copy(alpha = brushWeight(t * r, r, editor.hardness, editor.brushFalloff, i) * RETUNE_TIP_ALPHA)
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
                                t to Color.Red.copy(alpha = brushWeight(dist, r, editor.hardness, editor.brushFalloff, i) * RETUNE_TIP_ALPHA)
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
                                // Real hardness preview: solid red core + the chosen falloff around it
                                val steps = 16
                                for (step in steps downTo 1) {
                                    val t = step / steps.toFloat()
                                    val curDist = falloff * t
                                    val w = coreW + curDist
                                    val hStep = coreH + curDist
                                    val alpha = editor.brushFalloff.weight(1f - t, step) * RETUNE_TIP_ALPHA
                                    drawRoundRect(
                                        color = Color.Red.copy(alpha = alpha),
                                        topLeft = Offset(center.x - w, center.y - hStep),
                                        size = Size(w * 2f, hStep * 2f),
                                        cornerRadius = CornerRadius(curDist, curDist),
                                    )
                                }
                                if (h > 0.02f) {
                                    drawRect(
                                        color = Color.Red.copy(alpha = RETUNE_TIP_ALPHA),
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

        // 5. PATH_DEFORM: live Catmull-Rom curves + control points (create, select binding, deform)
        if (pathEditable && target.kind == "mesh") {
            editor.paths().forEach { path ->
                if (editor.drawingPath && path.id == editor.draftPathId) return@forEach
                val local = DeformPathTools.positions(path, target.geometry.points)
                val selected = path.id == editor.activePath
                drawDeformPathCurve(
                    screenPoints = editor.screen(
                        DeformPathTools.curve(local, path.points.map { it.corner }, path.closed)
                            .flatMap { listOf(it.first, it.second) }.toFloatArray(),
                        target,
                        viewport,
                    ),
                    stroke = if (selected) colors.accent else colors.textPrimary,
                )
                editor.screen(local.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport).forEachIndexed { i, p ->
                    drawDeformPathHandle(
                        p = p,
                        colors = colors,
                        hovered = i == editor.hoveredPathPoint,
                        active = selected && i == editor.pathPoint,
                    )
                }
            }
            // Creation / extend draft: real curve + live width/hardness influence preview
            if (editor.draft.isNotEmpty()) {
                val extending = editor.draftPathId?.let { id -> editor.model.deformPaths.firstOrNull { it.id == id } }
                val rubber = if (editor.drawingPath) {
                    editor.cursor?.let { editor.local(it, target, viewport, editor.draft.last()) }
                } else null
                val previewPts = if (rubber != null) editor.draft + rubber else editor.draft
                val closedPreview = editor.pathClosed && previewPts.size >= 3
                if (previewPts.size >= 2) {
                    val corners = previewPts.indices.map { i -> extending?.points?.getOrNull(i)?.corner ?: false }
                    drawDeformPathCurve(
                        screenPoints = editor.screen(
                            DeformPathTools.curve(previewPts, corners, closedPreview)
                                .flatMap { listOf(it.first, it.second) }.toFloatArray(),
                            target,
                            viewport,
                        ),
                        stroke = colors.accent.copy(alpha = if (rubber != null) 0.9f else 1f),
                    )
                }
                val draftScreen = editor.screen(editor.draft.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport)
                val canvasWidth = (extending?.width ?: editor.pathWidth).coerceAtLeast(0f)
                drawDeformPathInfluencePreview(
                    centers = draftScreen,
                    canvasWidth = canvasWidth,
                    hardness = extending?.hardness ?: editor.pathHardness,
                    viewport = viewport,
                    colors = colors,
                )
                draftScreen.forEach { drawCircle(colors.accent, 4f, it) }
                if (rubber != null) {
                    editor.screen(floatArrayOf(rubber.first, rubber.second), target, viewport)
                        .firstOrNull()?.let { tip ->
                            drawDeformPathInfluencePreview(
                                centers = listOf(tip),
                                canvasWidth = canvasWidth,
                                hardness = extending?.hardness ?: editor.pathHardness,
                                viewport = viewport,
                                colors = colors,
                                alphaScale = 0.55f,
                            )
                            drawCircle(colors.accent.copy(alpha = 0.45f), 3.5f, tip)
                        }
                }
            }
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
    // The armature is only open for editing with Skeleton mode's Edit tool.
    val skeleton = editor.skeletonDraft?.takeIf { editor.skeletonSelected && editor.hierarchyMode == EditHierarchyMode.SKELETON }
    if (skeleton != null) {
        val currentSkeleton by rememberUpdatedState(skeleton)
        val preview = editor.state.previewModel
        val neutralGeometry = remember(preview?.rig?.puppet) { preview?.let { RigCanvasSupport.evaluate(it) } }
        val meshOutlines = remember(preview?.rig?.puppet) {
            preview?.rig?.puppet?.drawables?.mapNotNull { d -> d.mesh?.let { d.id.raw to outlineEdges(it.indices) } }?.toMap().orEmpty()
        }
        fun boneColor(id: String): Color = SkeletonPalette.color(skeleton, id)
        fun screen(x: Float, y: Float): Offset = Offset(viewport.x(x).toFloat(), (viewport.offsetY + y * viewport.scale).toFloat())
        fun hitJoint(pos: Offset): Pair<String, BoneEnd>? = currentSkeleton.bones.asReversed().firstNotNullOfOrNull { bone ->
            listOf(BoneEnd.HEAD to screen(bone.headX, bone.headY), BoneEnd.TAIL to screen(bone.tailX, bone.tailY))
                .firstOrNull { (_, point) -> hypot(pos.x - point.x, pos.y - point.y) <= 14f }
                ?.let { bone.id to it.first }
        }
        fun hitBoneBody(pos: Offset): String? = currentSkeleton.bones.asReversed().firstOrNull { bone ->
            val h = screen(bone.headX, bone.headY)
            val t = screen(bone.tailX, bone.tailY)
            val dx = t.x - h.x
            val dy = t.y - h.y
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-4f) return@firstOrNull hypot(pos.x - h.x, pos.y - h.y) <= 12f
            val u = (((pos.x - h.x) * dx + (pos.y - h.y) * dy) / lenSq).coerceIn(0f, 1f)
            val projX = h.x + u * dx
            val projY = h.y + u * dy
            val dist = hypot(pos.x - projX, pos.y - projY)
            val maxDist = (kotlin.math.sqrt(lenSq) * 0.12f).coerceIn(6f, 18f)
            dist <= maxDist
        }?.id
        val subTool = editor.skeletonEditSubTool
        var draggedJoint by remember(viewport, subTool) { mutableStateOf<Pair<String, BoneEnd>?>(null) }
        var creationHead by remember(viewport, subTool) { mutableStateOf<Offset?>(null) }
        var creationTail by remember(viewport, subTool) { mutableStateOf<Offset?>(null) }
        var creationParent by remember(viewport, subTool) { mutableStateOf<String?>(null) }
        var pressPosition by remember { mutableStateOf(Offset.Zero) }
        var additiveSelection by remember { mutableStateOf(false) }
        var movingBones by remember(viewport, subTool) { mutableStateOf(false) }
        var boxStart by remember(viewport, subTool) { mutableStateOf<Offset?>(null) }
        var boxEnd by remember(viewport, subTool) { mutableStateOf<Offset?>(null) }
        var moveBefore by remember { mutableStateOf<io.github.psd2live.core.SkeletonSpec?>(null) }
        var weightStrokeActive by remember(viewport, subTool) { mutableStateOf(false) }
        var weightPointer by remember(viewport, subTool) { mutableStateOf<Offset?>(null) }
        val weightMap = remember(skeleton, preview?.rig?.puppet, editor.skeletonWeightDrawableId, subTool) {
            if (subTool == SkeletonEditSubTool.WEIGHTS) editor.activeSkeletonWeights() else null
        }
        fun clearCreation() { creationHead = null; creationTail = null; creationParent = null }
        fun finishCreation() {
            val head = creationHead
            val tail = creationTail
            if (head != null && tail != null && (tail - head).getDistance() >= 4f) {
                editor.createBone(viewport.canvasX(head.x), viewport.canvasY(head.y),
                    viewport.canvasX(tail.x), viewport.canvasY(tail.y), creationParent)
            }
            clearCreation()
        }
        Canvas(Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Move) { if (subTool == SkeletonEditSubTool.WEIGHTS) weightPointer = it.changes.first().position }
            .onPointerEvent(PointerEventType.Exit) { weightPointer = null }
            .onPointerEvent(PointerEventType.Press) { event ->
                pressPosition = event.changes.first().position
                additiveSelection = event.keyboardModifiers.isShiftPressed
            }
            .pointerInput(editor, viewport, subTool) {
                detectDragGestures(
                    onDragStart = { pos ->
                        when (subTool) {
                            SkeletonEditSubTool.WEIGHTS -> {
                                moveBefore = editor.skeletonDraft
                                editor.pickSkeletonDrawable(pressPosition, viewport)?.let(editor::selectSkeletonWeightDrawable)
                                weightStrokeActive = editor.prepareSkeletonWeightStroke()
                                if (weightStrokeActive) editor.paintSkeletonWeights(pressPosition, viewport)
                            }
                            SkeletonEditSubTool.BIND -> { boxStart = pressPosition; boxEnd = pos }
                            SkeletonEditSubTool.EDIT -> {
                                draggedJoint = hitJoint(pressPosition)
                                if (draggedJoint == null) {
                                    val id = hitBoneBody(pressPosition)
                                    if (id != null) {
                                        if (id !in editor.selectedBoneIds) editor.selectBone(id, additiveSelection)
                                        moveBefore = editor.skeletonDraft
                                        movingBones = true
                                    } else { boxStart = pressPosition; boxEnd = pos }
                                }
                            }
                            SkeletonEditSubTool.NEW_BONE -> { creationHead = pressPosition; creationTail = pos }
                            SkeletonEditSubTool.EXTRUDE -> {
                                val selected = currentSkeleton.bone(editor.selectedBoneId ?: "")
                                val id = selected?.takeIf { (screen(it.tailX, it.tailY) - pos).getDistance() <= 14f }?.id
                                    ?: hitJoint(pos)?.first ?: hitBoneBody(pos) ?: selected?.id
                                currentSkeleton.bone(id ?: "")?.let { parent ->
                                    editor.selectBone(parent.id)
                                    creationParent = parent.id
                                    creationHead = screen(parent.tailX, parent.tailY)
                                    creationTail = pos
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        boxStart?.let { start -> boxEnd?.let { end ->
                            if (subTool == SkeletonEditSubTool.BIND) {
                                val left = minOf(start.x, end.x); val right = maxOf(start.x, end.x)
                                val top = minOf(start.y, end.y); val bottom = maxOf(start.y, end.y)
                                val hits = neutralGeometry?.worldPositions.orEmpty().filter { (_, positions) ->
                                    val xs = positions.indices.filter { it % 2 == 0 }.map { viewport.x(positions[it]).toFloat() }
                                    val ys = positions.indices.filter { it % 2 == 1 }.map { viewport.yFromWorld(positions[it]).toFloat() }
                                    xs.isNotEmpty() && xs.min() <= right && xs.max() >= left && ys.min() <= bottom && ys.max() >= top
                                }.keys.mapTo(linkedSetOf()) { it.raw }
                                editor.selectSkeletonBindingDrawables(hits, additiveSelection)
                            } else editor.selectBones(currentSkeleton.bonesInBox(viewport.canvasX(start.x), viewport.canvasY(start.y),
                                viewport.canvasX(end.x), viewport.canvasY(end.y)), additiveSelection)
                        } }
                        draggedJoint = null; movingBones = false; weightStrokeActive = false; moveBefore = null; boxStart = null; boxEnd = null; finishCreation()
                    },
                    onDragCancel = {
                        moveBefore?.let(editor::restoreSkeletonDraft)
                        draggedJoint = null; movingBones = false; weightStrokeActive = false; moveBefore = null; boxStart = null; boxEnd = null; clearCreation()
                    },
                ) { change, amount ->
                    if (subTool == SkeletonEditSubTool.WEIGHTS) {
                        weightPointer = change.position
                        if (weightStrokeActive) {
                            val spacing = (editor.skeletonWeightRadius * viewport.scale.toFloat() * 0.25f).coerceAtLeast(1f)
                            val steps = kotlin.math.ceil(amount.getDistance() / spacing).toInt().coerceIn(1, 64)
                            for (step in 1..steps) editor.paintSkeletonWeights(change.position - amount * (1f - step.toFloat() / steps), viewport)
                        }
                        change.consume()
                    } else if (creationHead != null) {
                        creationTail = change.position
                        change.consume()
                    } else if (movingBones) {
                        editor.transformSelectedBones(dx = (amount.x / viewport.scale).toFloat(), dy = (amount.y / viewport.scale).toFloat())
                        change.consume()
                    } else if (boxStart != null) {
                        boxEnd = change.position
                        change.consume()
                    } else draggedJoint?.let { (id, end) ->
                        editor.moveBoneJoint(id, end, viewport.canvasX(change.position.x), viewport.canvasY(change.position.y))
                        change.consume()
                    }
                }
            }
            .pointerInput(editor, viewport, subTool) {
                detectTapGestures { pos ->
                    if (subTool == SkeletonEditSubTool.NEW_BONE) return@detectTapGestures
                    if (subTool == SkeletonEditSubTool.WEIGHTS) {
                        editor.pickSkeletonDrawable(pos, viewport)?.let(editor::selectSkeletonWeightDrawable)
                        if (editor.prepareSkeletonWeightStroke()) editor.paintSkeletonWeights(pos, viewport)
                        return@detectTapGestures
                    }
                    if (subTool == SkeletonEditSubTool.BIND) {
                        editor.pickSkeletonDrawable(pos, viewport)?.let(editor::toggleSkeletonBindingDrawable)
                        return@detectTapGestures
                    }
                    val joint = hitJoint(pos)
                    if (joint != null) {
                        editor.selectBone(joint.first, additiveSelection)
                    } else {
                        val body = hitBoneBody(pos)
                        if (body != null) {
                            editor.selectBone(body, additiveSelection)
                        } else if (subTool == SkeletonEditSubTool.EXTRUDE) {
                            currentSkeleton.bone(editor.selectedBoneId ?: "")?.let { parent ->
                                creationParent = parent.id
                                creationHead = screen(parent.tailX, parent.tailY)
                                creationTail = pos
                                finishCreation()
                            }
                        } else {
                            editor.pickSkeletonDrawable(pos, viewport)?.let(editor::bindDrawableToSelectedBone)
                        }
                    }
                }
            }
        ) {
            val drawables = preview?.rig?.puppet?.drawables?.associateBy { it.id.raw }.orEmpty()
            // 1. Every bound mesh tinted in its bone's color - the tree lists it in the same color - so what
            //    each bone will move reads at a glance. The selected bone's meshes stand out. Only the mesh's
            //    outline is stroked: the inner triangle edges would put the mesh wires back over the art.
            for (bone in skeleton.bones) {
                if (bone.role.anchor) continue
                val color = boneColor(bone.id)
                val isSelected = bone.id in editor.selectedBoneIds
                for (drawableId in bone.drawableIds) {
                    val mesh = drawables[drawableId]?.mesh ?: continue
                    val positions = neutralGeometry?.worldPositions?.get(DrawableId(drawableId)) ?: continue
                    val shape = Path()
                    for (i in mesh.indices.indices step 3) {
                        if (i + 2 >= mesh.indices.size) break
                        val a = mesh.indices[i] * 2; val b = mesh.indices[i + 1] * 2; val c = mesh.indices[i + 2] * 2
                        if (maxOf(a, b, c) + 1 >= positions.size) continue
                        shape.moveTo(viewport.x(positions[a]).toFloat(), viewport.yFromWorld(positions[a + 1]).toFloat())
                        shape.lineTo(viewport.x(positions[b]).toFloat(), viewport.yFromWorld(positions[b + 1]).toFloat())
                        shape.lineTo(viewport.x(positions[c]).toFloat(), viewport.yFromWorld(positions[c + 1]).toFloat())
                        shape.close()
                    }
                    drawPath(shape, color.copy(alpha = if (isSelected) 0.70f else 0.32f))
                    val outline = Path()
                    for (edge in meshOutlines[drawableId].orEmpty()) {
                        val a = edge.endpointLow * 2; val b = edge.endpointHigh * 2
                        if (maxOf(a, b) + 1 >= positions.size) continue
                        outline.moveTo(viewport.x(positions[a]).toFloat(), viewport.yFromWorld(positions[a + 1]).toFloat())
                        outline.lineTo(viewport.x(positions[b]).toFloat(), viewport.yFromWorld(positions[b + 1]).toFloat())
                    }
                    drawPath(outline, color.copy(alpha = if (isSelected) 1f else 0.70f), style = Stroke(width = if (isSelected) 1.8f else 1.1f))
                }
            }

            // 2. The bones themselves, the selected one on top.
            if (subTool == SkeletonEditSubTool.WEIGHTS && weightMap != null) {
                // Weights read as Simulate's do: the weight colours on the shared point, the brush ringed alike.
                for (v in weightMap.weights.indices) {
                    val value = (weightMap.weights[v][editor.selectedBoneId] ?: 0f).coerceIn(0f, 1f)
                    val p = screen(weightMap.positions[v * 2], weightMap.positions[v * 2 + 1])
                    drawCircle(MeshLook.Halo, MeshLook.POINT + MeshLook.HALO_RIM, p)
                    drawCircle(weightHeatColor(value), MeshLook.POINT, p)
                }
                weightPointer?.let { p ->
                    val r = editor.skeletonWeightRadius * viewport.scale.toFloat()
                    drawCircle(Color.Black.copy(alpha = 0.7f), r, p, style = Stroke(3f))
                    drawCircle(Color.White, r, p, style = Stroke(1.2f))
                }
            }
            if (subTool == SkeletonEditSubTool.BIND) for (id in editor.pendingSkeletonDrawableIds) {
                val positions = neutralGeometry?.worldPositions?.get(DrawableId(id)) ?: continue
                for (edge in meshOutlines[id].orEmpty()) {
                    val a = edge.endpointLow * 2; val b = edge.endpointHigh * 2
                    if (maxOf(a, b) + 1 >= positions.size) continue
                    drawLine(colors.accent, Offset(viewport.x(positions[a]).toFloat(), viewport.yFromWorld(positions[a + 1]).toFloat()),
                        Offset(viewport.x(positions[b]).toFloat(), viewport.yFromWorld(positions[b + 1]).toFloat()), strokeWidth = 3f)
                }
            }
            val halo = colors.windowBackground
            for (bone in skeleton.bones.sortedBy { it.id in editor.selectedBoneIds }) {
                drawCanvasBone(screen(bone.headX, bone.headY), screen(bone.tailX, bone.tailY), boneColor(bone.id), halo,
                    lit = bone.id in editor.selectedBoneIds)
            }
            creationHead?.let { head -> creationTail?.let { tail ->
                drawCanvasBone(head, tail, colors.accent, halo, lit = true)
            } }
            boxStart?.let { start -> boxEnd?.let { end ->
                val bounds = Rect(start, end)
                val origin = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
                val boxSize = Size(kotlin.math.abs(bounds.width), kotlin.math.abs(bounds.height))
                drawRect(colors.accent.copy(alpha = 0.12f), origin, boxSize)
                drawRect(colors.accent, origin, boxSize, style = Stroke(1f))
            } }
        }
    }

    if (skeleton == null && editor.posing()) {
        SkeletonPoseLayer(editor, viewport)
    } else if (skeleton == null && editor.hierarchyMode == EditHierarchyMode.SELECT && editor.bakedSkeleton != null &&
        editor.state.showSkeleton) {
        // Object mode shows the bones faintly so they can be clicked, which is how the skeleton is picked.
        SkeletonPoseLayer(editor, viewport, passive = true)
    }

    // The session is opened here rather than in composition: the bars only ever read it, and one opened while
    // they draw would be a state write during composition.
    LaunchedEffect(editor.state.selectedLayerId, editor.hierarchyMode) {
        if (editor.hierarchyMode == EditHierarchyMode.PAINT) editor.ensurePaintSession()
    }

    // The values that tune the tool in hand, at the bottom left.
    ToolOptionsBar(editor, focus)

    // Left Animated Hover Toolbar (edit / deform / paint tools)
    CanvasToolBar(editor = editor, keymap = keymap, focus = focus)

    // Top Left Hierarchy / Layer Mode Toolbar
    var modeBarWidth by remember { mutableStateOf(0) }
    HierarchyModeBar(
        editor = editor,
        selectedLayerId = selectedLayerId,
        selectedDeformerId = selectedDeformerId,
        focus = focus,
        onWidth = { modeBarWidth = it },
    )

    // Top centre: the paint session, in the bar the atlas's edit session shows.
    PaintSessionBar(editor, modeBarWidth, focus)
}

/**
 * The open paint session as a bar dropping in at the top of the canvas once it holds strokes: what is being painted,
 * undo, redo, discard and apply - the atlas session's bar, so both sessions read and work alike.
 */
@Composable
private fun BoxScope.PaintSessionBar(editor: CanvasEditor, modeBarWidth: Int, focus: () -> Unit) {
    val session = editor.paintSession
    // Read for their state: the stroke list and the step in it are what undo and redo change.
    val strokes = session?.strokeRecords?.size ?: 0
    val step = session?.currentStrokeIndex ?: 0
    val canUndo = session != null && step > 0 && session.canUndo()
    val canRedo = session != null && step < strokes - 1 && session.canRedo()
    val dirty = session?.isDirty == true
    val keymap = editor.state.keymap
    val density = LocalDensity.current
    SessionTopBar(
        visible = editor.hierarchyMode == EditHierarchyMode.PAINT && session != null && (dirty || canRedo),
        summary = session?.let { tr("editor.paint.sessionSummary", it.layerName, it.strokeCount) } ?: "",
        badge = "${session?.strokeCount ?: 0}",
        undo = SessionAction(tr("editor.undo"), { editor.undoPaint(); focus() }, canUndo,
            tr("editor.paint.undoHint", keymap.labelFor(ShortcutAction.UNDO))),
        redo = SessionAction(tr("editor.redo"), { editor.redoPaint(); focus() }, canRedo,
            tr("editor.paint.redoHint", keymap.labelFor(ShortcutAction.REDO))),
        discard = SessionAction(tr("texture.session.discard"), { editor.discardPaintSession(); focus() }, dirty,
            tr("editor.paint.discardHint")),
        apply = SessionAction(tr("texture.session.apply"), { editor.promptCommitPaintSession(); focus() }, dirty && !editor.busy,
            tr("editor.paint.applyHint")),
        startInset = with(density) { (modeBarWidth + 8.dp.roundToPx()).toDp() },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlacementSettingsPanel(
    editor: CanvasEditor,
    place: CreatePlacement,
    isClosing: Boolean,
    keymap: Keymap,
    focus: () -> Unit,
    titleModifier: Modifier = Modifier,
    windowActions: @Composable () -> Unit = {},
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    Column(
        modifier = Modifier
            .width(240.dp)
            .tutorialTarget(TutorialTargetId.PLACEMENT_PANEL)
            .frostedGlass(shape = RoundedCornerShape(6.dp), isHovered = true, elevation = 6.dp, alpha = 0.94f)
            .border(BorderStroke(1.dp, colors.border.copy(alpha = 0.85f)), RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 1. Header: Icon Badge + Title + Close Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(colors.accent.copy(alpha = 0.16f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when (place.kind) {
                    CreatePlacementKind.WARP -> IconWarpDeformer(tint = colors.accent, modifier = Modifier.size(13.dp))
                    CreatePlacementKind.ROTATION -> IconRotationDeformer(tint = colors.accent, modifier = Modifier.size(13.dp))
                    CreatePlacementKind.PATH -> IconDeformPath(tint = colors.accent, modifier = Modifier.size(13.dp))
                }
            }
            Text(
                text = when (place.kind) {
                    CreatePlacementKind.WARP -> tr("editor.tool.create_warp")
                    CreatePlacementKind.ROTATION -> tr("editor.tool.create_rotation")
                    CreatePlacementKind.PATH -> tr("editor.pathDeform")
                },
                color = colors.textPrimary,
                style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).then(titleModifier),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            windowActions()
            CompactIconButton(
                onClick = { if (!isClosing) { editor.cancelPlacement(); focus() } },
                size = 18.dp,
                tooltip = keymap.labelFor(ShortcutAction.CANCEL)?.let { "${tr("editor.placementCancel")} ($it)" } ?: tr("editor.placementCancel"),
            ) {
                IconClose(tint = colors.textMuted, modifier = Modifier.size(10.dp))
            }
        }

        // 2. Target relation info (single-line muted context)
        val relationText = buildString {
            val isParent = place.relation == CreateRelation.AS_PARENT
            append(tr(if (isParent) "editor.placementAsParentOf" else "editor.placementAsChildOf", place.anchorLabel))
            if (place.meshIds.isNotEmpty()) {
                append(" · ")
                append(tr("editor.placementMeshCount", place.meshIds.size))
            }
        }
        Text(
            text = relationText,
            color = colors.textMuted,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        // 3. Name and Part Fields
        if (place.kind != CreatePlacementKind.PATH) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = tr("inspector.name"),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.width(28.dp),
                )
                CompactTextField(
                    value = place.name,
                    onValueChange = { if (!isClosing) editor.updatePlacementName(it) },
                    modifier = Modifier.weight(1f),
                    height = 22.dp,
                )
            }

            run {
                val partOptions = listOf("" to tr("editor.warpPart.inherit")) +
                    editor.model.parts.map { it.id.raw to it.name }
                val partSelected = partOptions.firstOrNull { it.first == (place.partId ?: "") } ?: partOptions.first()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = tr("inspector.part"),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.width(28.dp),
                    )
                    CompactDropdown(
                        items = partOptions,
                        selectedItem = partSelected,
                        onItemSelected = { if (!isClosing) editor.updatePlacementPart(it.first.takeIf { id -> id.isNotEmpty() }) },
                        itemLabel = { it.second },
                        modifier = Modifier.weight(1f),
                        height = 22.dp,
                    )
                }
            }
        }

        // 4. Grid Divisions (for WARP)
        if (place.kind == CreatePlacementKind.WARP) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = tr("inspector.conversionDivision"),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.width(48.dp),
                )
                MiniStepper(
                    value = place.cols,
                    onValueChange = { if (!isClosing) editor.updatePlacementGrid(place.rows, it) },
                    min = 1,
                    max = 32,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "×",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 1.dp),
                )
                MiniStepper(
                    value = place.rows,
                    onValueChange = { if (!isClosing) editor.updatePlacementGrid(it, place.cols) },
                    min = 1,
                    max = 32,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = tr("inspector.bezierDivision"),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.width(48.dp),
                )
                MiniStepper(
                    value = place.bezierCols,
                    onValueChange = { if (!isClosing) editor.updatePlacementBezier(place.bezierRows, it) },
                    min = 1,
                    max = 16,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "×",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 1.dp),
                )
                MiniStepper(
                    value = place.bezierRows,
                    onValueChange = { if (!isClosing) editor.updatePlacementBezier(it, place.bezierCols) },
                    min = 1,
                    max = 16,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 5. Rotation Angle (for ROTATION)
        if (place.kind == CreatePlacementKind.ROTATION) {
            val dx = place.tipX - place.originX
            val dy = place.tipY - place.originY
            val currentDeg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).let { if (it < 0) it + 360.0 else it }.roundToInt() % 360
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = tr("editor.direction"),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.width(28.dp),
                )
                MiniStepper(
                    value = currentDeg,
                    onValueChange = { if (!isClosing) editor.updatePlacementRotationDirection(it.toFloat()) },
                    min = 0,
                    max = 359,
                    step = 15,
                    unit = "°",
                    modifier = Modifier.width(82.dp),
                )
            }
        }

        // 6. Path parameters + point status (for PATH)
        if (place.kind == CreatePlacementKind.PATH) {
            val pointCount = editor.draft.size
            val isReady = pointCount >= 2
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(22.dp),
            ) {
                Text(
                    text = if (isReady) tr("editor.placementPathReady", pointCount) else tr("editor.placementPathNeedMore", pointCount),
                    color = if (isReady) colors.accent else colors.warning,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pointCount > 0) {
                    CompactButton(
                        text = tr("editor.undoPoint"),
                        onClick = { if (!isClosing) editor.undoDraftPoint() },
                        height = 20.dp,
                    )
                }
            }

            Text(
                text = tr("editor.pathEditLevel"),
                color = colors.textMuted,
                fontSize = 10.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 3).forEach { level ->
                    CompactToggleChip(
                        text = "L$level",
                        selected = editor.pathLevel == level,
                        onToggle = { if (!isClosing) editor.pathLevel = level },
                        height = 22.dp,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = tr("editor.width"),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.width(36.dp),
                )
                io.github.psd2live.ui.components.CompactNumberSpinner(
                    value = editor.pathWidth.toDouble(),
                    onValueChange = { if (!isClosing) editor.pathWidth = it.coerceIn(0.0, 100000.0).toFloat() },
                    min = 0.0,
                    max = 100000.0,
                    decimals = 2,
                    step = 1.0,
                    height = 23.dp,
                    unit = "px",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = tr("editor.hardness"),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.width(36.dp),
                )
                io.github.psd2live.ui.components.CompactNumberSpinner(
                    value = editor.pathHardness.toDouble(),
                    onValueChange = { if (!isClosing) editor.pathHardness = it.coerceIn(0.0, 100.0).toFloat() },
                    min = 0.0,
                    max = 100.0,
                    decimals = 2,
                    step = 1.0,
                    height = 23.dp,
                    unit = "%",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CompactToggleChip(
                    text = tr("editor.closePath"),
                    selected = editor.pathClosed,
                    onToggle = { if (!isClosing) editor.pathClosed = !editor.pathClosed },
                    height = 22.dp,
                )
            }

            Text(
                text = tr("editor.placementPathPreviewHint"),
                color = colors.textMuted,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            val finishKey = keymap.labelFor(ShortcutAction.FINISH_PATH).orEmpty()
            val cancelKey = keymap.labelFor(ShortcutAction.CANCEL).orEmpty()
            val createKey = keymap.labelFor(ShortcutAction.TOOL_CREATE_DEFORM_PATH).orEmpty()
            val deleteKey = keymap.labelFor(ShortcutAction.DELETE_SELECTION).orEmpty()
            Text(
                text = buildString {
                    if (createKey.isNotEmpty()) append(tr("editor.placementPathShortcutCreate", createKey))
                    if (finishKey.isNotEmpty()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(tr("editor.placementPathShortcutFinish", finishKey))
                    }
                    if (deleteKey.isNotEmpty()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(tr("editor.placementPathShortcutUndo", deleteKey))
                    }
                    if (cancelKey.isNotEmpty()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(tr("editor.placementPathShortcutCancel", cancelKey))
                    }
                },
                color = colors.textMuted,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 7. Action Buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        ) {
            CompactButton(
                text = keymap.labelFor(ShortcutAction.CANCEL)?.let { "${tr("editor.placementCancel")} ($it)" } ?: tr("editor.placementCancel"),
                onClick = { if (!isClosing) { editor.cancelPlacement(); focus() } },
                modifier = Modifier.weight(1f),
                height = 24.dp,
                leadingIcon = { IconClose(modifier = Modifier.size(9.dp), tint = colors.textMuted) },
            )
            CompactButton(
                text = "${tr("editor.placementConfirm")} (Enter)",
                onClick = { if (!isClosing) { editor.confirmPlacement(); focus() } },
                isPrimary = true,
                enabled = !isClosing && editor.editable && !editor.busy &&
                    (place.kind != CreatePlacementKind.PATH || editor.draft.size >= 2),
                modifier = Modifier.weight(1f),
                height = 24.dp,
                leadingIcon = { IconCheck(modifier = Modifier.size(9.dp), tint = colors.accentText) },
            )
        }
    }
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MiniStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int = 1,
    max: Int = 32,
    step: Int = 1,
    unit: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .height(22.dp)
            .background(colors.inputBackground, RoundedCornerShape(3.dp))
            .border(
                BorderStroke(1.dp, if (isFocused) colors.accent else colors.border),
                RoundedCornerShape(3.dp),
            )
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val dy = change.scrollDelta.y
                if (dy < 0f) {
                    onValueChange((value + step).coerceAtMost(max))
                } else if (dy > 0f) {
                    onValueChange((value - step).coerceAtLeast(min))
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decrement button
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
                .clickable(enabled = value > min) {
                    onValueChange((value - step).coerceAtLeast(min))
                }
                .pointerHoverIcon(if (value > min) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "-",
                color = if (value > min) colors.textPrimary else colors.textDisabled,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Center text / input
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = if (isFocused) textValue else if (unit.isNotEmpty()) "$value$unit" else value.toString(),
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() }.take(4)
                    textValue = filtered
                    filtered.toIntOrNull()?.let { num ->
                        onValueChange(num.coerceIn(min, max))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                        if (!focusState.isFocused) {
                            textValue = value.toString()
                        }
                    },
                textStyle = typography.mono.copy(
                    color = colors.textPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(colors.accent),
                singleLine = true,
            )
        }

        // Increment button
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
                .clickable(enabled = value < max) {
                    onValueChange((value + step).coerceAtMost(max))
                }
                .pointerHoverIcon(if (value < max) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = if (value < max) colors.textPrimary else colors.textDisabled,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun BoxScope.CanvasToolBar(
    editor: CanvasEditor,
    keymap: Keymap,
    focus: () -> Unit,
) {
    val rows = toolbarRows(editor.hierarchyMode)
    val shown = rows.flatten().toSet()
    // A rule opens every group after the first.
    val groupStarts = rows.drop(1).map { it.first() }.toSet()
    val placingTool = when (editor.placement?.kind) {
        CreatePlacementKind.WARP -> CanvasTool.CREATE_WARP
        CreatePlacementKind.ROTATION -> CanvasTool.CREATE_ROTATION
        CreatePlacementKind.PATH -> CanvasTool.CREATE_DEFORM_PATH
        else -> null
    }
    // Glue joins exactly two meshes: with any other count it stays in place, disabled, and says why.
    val glueWaiting = editor.glueMeshCount() != 2 && editor.tool != CanvasTool.GLUE
    CanvasOptionsRail(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 44.dp)
            .tutorialTarget(TutorialTargetId.CANVAS_TOOLBAR),
        leading = true,
        expandedWidth = 140.dp,
        scrollable = true,
    ) {
        // Every tool is walked in one fixed order, each row's visibility following the mode. Walking the mode's own
        // list instead would be shorter, but a row that left the composition has nothing left to animate out of -
        // the rows a mode change adds or removes would pop while the rest slid.
        TOOLBAR_TOOL_ORDER.forEach { tool ->
            RailRows(tool in groupStarts) { RailDivider() }
            RailRows(tool in shown) {
                val creating = tool in CREATE_GROUP_TOOLS
                val waiting = tool == CanvasTool.GLUE && glueWaiting
                RailItem(
                    label = tr("editor.tool.${tool.name.lowercase()}"),
                    selected = if (creating) placingTool == tool || editor.tool == tool else editor.tool == tool,
                    enabled = (if (creating) editor.editable else !editor.busy) && !waiting,
                    tooltip = if (waiting) tr("editor.glueNeedTwo", editor.glueMeshCount()) else null,
                    // The shape tool answers to its shapes' chords, so its row shows the one that is in hand.
                    keyLabel = keymap.labelFor(if (tool == CanvasTool.PAINT_SHAPE) editor.paintShape.action else tool.action).orEmpty(),
                    keyCap = true,
                    icon = { color ->
                        ToolIcon(
                            tool = tool,
                            color = color,
                            brushShape = if (tool == CanvasTool.BRUSH || tool == CanvasTool.WEIGHT_PAINT) editor.brushShape else null,
                            paintShape = if (tool == CanvasTool.PAINT_SHAPE) editor.paintShape else null,
                            skeletonEditSubTool = if (tool == CanvasTool.SKELETON_EDIT) editor.skeletonEditSubTool else null,
                        )
                    },
                    onClick = {
                        editor.activateTool(tool)
                        focus()
                    },
                )
            }
        }

        // A tool's variants unfold under it while it is in hand - Glue's sub-tools, the skeleton tools' sub-tools,
        // the brushes' tips, the paint shape's faces and the vertex groups the weight tools write - each with the
        // number key that picks it.
        RailVariants(editor.tool == CanvasTool.GLUE, GLUE_SUB_TOOL_LABELS.map { it.first }, { editor.glueSubTool == it },
            { tr(GLUE_SUB_TOOL_LABELS.first { p -> p.first == it }.second) }, { color, sub -> GlueSubToolIcon(subTool = sub, color = color) },
            keys = { pickKey(keymap, it) }) { sub ->
            editor.glueSubTool = sub
            editor.activateTool(CanvasTool.GLUE)
            focus()
        }
        RailVariants(editor.tool == CanvasTool.SKELETON_EDIT && CanvasTool.SKELETON_EDIT in shown, SkeletonEditSubTool.entries,
            { editor.skeletonEditSubTool == it }, { tr(it.labelKey) }, { color, sub -> SkeletonEditSubToolIcon(subTool = sub, color = color) },
            keys = { pickKey(keymap, it) }) { sub -> editor.skeletonEditSubTool = sub; focus() }
        RailVariants(editor.tool == CanvasTool.SKELETON_POSE && CanvasTool.SKELETON_POSE in shown, SkeletonPoseSubTool.entries,
            { editor.skeletonPoseSubTool == it }, { tr(it.labelKey) }, { color, sub -> SkeletonPoseSubToolIcon(sub, color) },
            keys = { pickKey(keymap, it) }) { sub -> editor.skeletonPoseSubTool = sub; focus() }
        // The deform brushes and the weight brush share their tip. The number keys reach it only where nothing
        // else of the mode answers to them (Deform's levels on a warp, Simulate's group kinds).
        val tipKeys = editor.hierarchyMode != EditHierarchyMode.SIMULATE && !editor.deformLevelsShown()
        RailVariants(editor.tool in DEFORM_BRUSH_TOOLS || editor.tool == CanvasTool.WEIGHT_PAINT, BrushShape.entries,
            { editor.brushShape == it }, { tr(it.labelKey) }, { color, shape -> BrushShapeIcon(shape = shape, color = color) },
            keys = { if (tipKeys) pickKey(keymap, it) else "" }) { shape -> editor.brushShape = shape; focus() }
        // The shape tool's three faces live under it the way the deform brush carries its tips.
        RailVariants(editor.hierarchyMode == EditHierarchyMode.PAINT && editor.tool == CanvasTool.PAINT_SHAPE, PaintShape.entries,
            { editor.paintShape == it }, { tr(it.labelKey) }, { color, shape -> PaintShapeIcon(shape = shape, color = color) },
            keys = { keymap.labelFor(PaintShape.entries[it].action).orEmpty() }) { shape -> editor.selectPaintShape(shape); focus() }
        // Picking a group kind is picking which group of the mesh the weight strokes write.
        RailVariants(editor.hierarchyMode == EditHierarchyMode.SIMULATE, PAINTED_GROUP_KINDS, { editor.weightGroupKind == it },
            { tr("sim.group.${it.jsonName}") }, { _, kind -> VertexGroupKindIcon(kind = kind, color = vertexGroupKindColor(kind)) },
            keys = { pickKey(keymap, it) }) { kind ->
            editor.weightGroupKind = kind
            if (editor.tool !in WEIGHT_TOOLS) editor.activateTool(CanvasTool.WEIGHT_PAINT)
            focus()
        }
    }
}

/** The variants of the tool in hand as rows under the palette, behind a rule, sliding in and out with the tool. */
@Composable
private fun <T> CanvasRailScope.RailVariants(
    visible: Boolean,
    choices: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    icon: @Composable (Color, T) -> Unit,
    keys: (Int) -> String,
    onPick: (T) -> Unit,
) {
    RailRows(visible) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RailDivider(strong = true)
            choices.forEachIndexed { i, choice ->
                RailItem(
                    label = label(choice),
                    selected = selected(choice),
                    keyLabel = keys(i),
                    icon = { color -> icon(color, choice) },
                    onClick = { onPick(choice) },
                )
            }
        }
    }
}

/** The chord of the number key that picks choice [index] (0-based), or nothing when it is unbound. */
private fun pickKey(keymap: Keymap, index: Int): String =
    ShortcutAction.pickActions.getOrNull(index)?.let(keymap::labelFor).orEmpty()

/** Rows of the tool palette that slide in and out as the mode or the tool in hand changes. */
@Composable
private fun RailRows(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(120)),
    ) { content() }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.HierarchyModeBar(
    editor: CanvasEditor,
    selectedLayerId: String?,
    selectedDeformerId: String?,
    focus: () -> Unit,
    onWidth: (Int) -> Unit = {},
) {
    val colors = LocalToolColors.current
    // Resolve from the selection keys passed by the parent so the badge tracks picks immediately,
    // not only when hover recomposes this bar.
    val target = editor.target(layerId = selectedLayerId, deformerId = selectedDeformerId)
    val targetLabel = target?.geometry?.name ?: target?.id

    FloatingBar(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 8.dp)
            .onSizeChanged { onWidth(it.width) }
            .tutorialTarget(TutorialTargetId.MODE_BAR),
    ) {
        // The mode menu: every mode, Preview included, behind one button (Blender's mode dropdown).
        CanvasModeMenu(
            keymap = editor.state.keymap,
            current = CanvasModeChoice.of(editor.hierarchyMode),
            // A mode asked for before there was anything to work on: it reads as waiting rather than
            // as in force, which is what the canvas is doing until a part is picked.
            waiting = editor.deferredMode?.mode?.let { CanvasModeChoice.of(it) },
            enabled = !editor.busy,
            modifier = Modifier
                .tutorialTarget(TutorialTargetId.EDIT_TAB)
                .tutorialTarget(TutorialTargetId.PREVIEW_TAB),
            onSelect = { choice ->
                editor.chooseCanvasMode(choice)
                focus()
            },
        )

        // 1 2 3 deformation level expansion animation
        // Only a warp keys its levels apart - 1 its grid points, 2 its Bezier handles - so only a warp shows them.
        AnimatedVisibility(
            visible = editor.deformLevelsShown(),
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
                BarDivider()
                (1..2).map { lvl ->
                    val key = pickKey(editor.state.keymap, lvl - 1)
                    lvl to (tr("editor.level.$lvl.desc") + if (key.isEmpty()) "" else "  ($key)")
                }.forEach { (lvl, tooltip) ->
                    BarChip(
                        label = tr("editor.level.$lvl"),
                        selected = editor.editLevel == lvl,
                        tooltip = tooltip,
                        onClick = {
                            editor.setEditLevel(lvl)
                            focus()
                        },
                    )
                }
            }
        }

        // Edit mode: warp grid badge only. Mesh topology actions are on the canvas context menu.
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.EDIT && target?.kind == "warp",
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
                BarDivider()
                val warpRows = target?.geometry?.rows ?: 4
                val warpCols = target?.geometry?.columns ?: 4
                Text(
                    text = tr("editor.warpGrid", warpRows, warpCols),
                    fontSize = 11.sp,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // Simulate: the group the weight tools write, and the live simulation of the meshes in hand.
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.SIMULATE,
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Start,
            ) + fadeIn(animationSpec = tween(160)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(animationSpec = tween(120)),
        ) {
            SimulateModeExtras(editor, focus)
        }

        // Skeleton: the one action each tool needs within reach of the canvas.
        AnimatedVisibility(
            visible = editor.hierarchyMode == EditHierarchyMode.SKELETON,
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BarDivider()
                if (editor.skeletonDraft != null) {
                    BarChip(tr("skeleton.panel.cancel"), selected = false, onClick = { editor.cancelSkeletonEdit(); focus() })
                    AccentButton(tr("skeleton.panel.done"), onClick = { editor.finishSkeletonEdit(); focus() })
                } else {
                    BarChip(
                        label = tr("animation.resetPose"),
                        selected = false,
                        onClick = { editor.resetSkeletonPose(); focus() },
                        enabled = editor.bakedSkeleton != null,
                    )
                }
            }
        }

        // Temporary place-then-confirm session name (warp / rotation / path / layer import).
        val session = editor.placement
        AnimatedVisibility(
            visible = session != null,
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
                BarDivider()
                SessionNameChip(
                    text = when (session?.kind) {
                        CreatePlacementKind.WARP -> tr("editor.tool.create_warp")
                        CreatePlacementKind.ROTATION -> tr("editor.tool.create_rotation")
                        CreatePlacementKind.PATH -> tr("editor.pathDeform")
                        null -> ""
                    },
                )
            }
        }

        // The tool's element mode and the confirm and cancel of a step in hand.
        AnimatedVisibility(
            visible = topOptions(editor).isNotEmpty(),
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Start,
            ) + fadeIn(animationSpec = tween(150)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(animationSpec = tween(100)),
        ) {
            ModeBarToolOptions(editor, focus)
        }

        // Current edit target badge — shown whenever a mesh/deformer is selected so the label
        // tracks the pick in every hierarchy mode, not only after hovering the toolbar.
        AnimatedVisibility(
            visible = targetLabel != null,
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
                BarDivider()
                // Edit holding several meshes names each one in the colour it is drawn in on the canvas;
                // with two, that is glue side A then B.
                val editMeshes = editor.editMeshTargets()
                val meshColors = editor.editMeshColors()
                if (editMeshes.size > 1 && meshColors.isNotEmpty()) {
                    editMeshes.forEach { mesh ->
                        val color = meshColors[mesh.id] ?: colors.textMuted
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 3.dp),
                        ) {
                            Box(Modifier.size(7.dp).background(color, CircleShape))
                            Text(
                                text = editor.meshLabel(mesh.id),
                                fontSize = 10.5.sp,
                                color = color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp).widthIn(max = 140.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = targetLabel.orEmpty(),
                        fontSize = 10.5.sp,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionNameChip(text: String) {
    val colors = LocalToolColors.current
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.accent.copy(alpha = 0.16f))
            .border(0.5.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * Simulate mode's strip: the vertex group the weight tools write, with its kind's colour, then the
 * simulation of the meshes in hand - create one, or run it live and restart it.
 */
@Composable
private fun SimulateModeExtras(editor: CanvasEditor, focus: () -> Unit) {
    val colors = LocalToolColors.current
    val viewModel = editor.viewModel
    val state = editor.state
    val meshes = editor.editMeshTargets().map { it.id }.toSet()
    val simulation = state.rigEdits.simEdits.firstOrNull { edit -> edit.targets.any { it in meshes } }
        ?: state.rigEdits.simEdits.firstOrNull()
    val live = simulation != null && state.simulationPreviewId == simulation.id
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BarDivider()
        VertexGroupKindIcon(editor.weightGroupKind, vertexGroupKindColor(editor.weightGroupKind), size = 12.dp)
        Text(
            text = tr("sim.group.${editor.weightGroupKind.jsonName}"),
            fontSize = 10.5.sp,
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.padding(end = 2.dp),
        )
        BarDivider()
        if (simulation == null) {
            BarChip(
                label = tr("sim.newCloth"),
                selected = false,
                onClick = { viewModel.createSimulationFromSelection(io.github.psd2live.core.sim.SimKind.CLOTH); focus() },
                enabled = meshes.isNotEmpty(),
            )
            BarChip(
                label = tr("sim.newHair"),
                selected = false,
                onClick = { viewModel.createSimulationFromSelection(io.github.psd2live.core.sim.SimKind.HAIR); focus() },
                enabled = meshes.isNotEmpty(),
            )
        } else {
            BarChip(
                label = tr("sim.previewReference"),
                selected = live,
                onClick = { viewModel.setSimulationPreview(if (live) null else simulation.id); focus() },
            )
            if (live) {
                BarChip(tr("sim.restartShort"), selected = false, onClick = { viewModel.restartSimulationPreview(); focus() })
            }
        }
    }
}

/** One colour per vertex group kind, shared by the mode strip and the canvas. */
internal fun vertexGroupKindColor(kind: org.umamo.runtime.model.VertexGroupKind): Color = when (kind) {
    org.umamo.runtime.model.VertexGroupKind.PIN -> Color(0xFFE0564B)
    org.umamo.runtime.model.VertexGroupKind.STIFFNESS -> Color(0xFF5B8DEF)
    org.umamo.runtime.model.VertexGroupKind.MASS -> Color(0xFFB07BE0)
    org.umamo.runtime.model.VertexGroupKind.DAMPING -> Color(0xFF3FBCD6)
    org.umamo.runtime.model.VertexGroupKind.WIND -> Color(0xFF7FD4F0)
    org.umamo.runtime.model.VertexGroupKind.GOAL -> Color(0xFF3FC46B)
}

/**
 * Clean, minimalistic rotation deformer: tapered rounded needle (capsule-like),
 * larger root pivot, single solid color without gradient or arrowhead,
 * and a compact rotation indicator.
 */
/**
 * Tints the artwork under a mesh by a per-vertex weight, interpolated across each triangle, so the wash
 * moves with the mesh. Only triangles with a weighted corner are drawn. [alpha] maps a vertex to 0..1.
 */
private fun DrawScope.drawWeightWash(pts: List<Offset>, indices: IntArray, color: Color, alpha: (Int) -> Float) {
    val vertexCount = pts.size
    if (vertexCount == 0 || indices.isEmpty()) return
    val positions = FloatArray(vertexCount * 2)
    val vertexColors = IntArray(vertexCount)
    val red = (color.red * 255f + 0.5f).toInt()
    val green = (color.green * 255f + 0.5f).toInt()
    val blue = (color.blue * 255f + 0.5f).toInt()
    for (i in 0 until vertexCount) {
        positions[i * 2] = pts[i].x
        positions[i * 2 + 1] = pts[i].y
        val a = (alpha(i).coerceIn(0f, 1f) * 255f).toInt()
        if (a > 0) vertexColors[i] = (a shl 24) or ((a * red / 255) shl 16) or ((a * green / 255) shl 8) or (a * blue / 255)
    }
    val corners = ArrayList<Short>()
    for (tri in 0 until indices.size / 3) {
        val a = indices[tri * 3]
        val b = indices[tri * 3 + 1]
        val c = indices[tri * 3 + 2]
        if (a >= vertexCount || b >= vertexCount || c >= vertexCount) continue
        if (vertexColors[a] == 0 && vertexColors[b] == 0 && vertexColors[c] == 0) continue
        corners += a.toShort(); corners += b.toShort(); corners += c.toShort()
    }
    if (corners.isEmpty()) return
    drawIntoCanvas { canvas ->
        val paint = SkiaPaint().apply { isAntiAlias = true }
        try {
            canvas.skiaCanvas.drawVertices(
                SkiaVertexMode.TRIANGLES,
                positions,
                vertexColors,
                null,
                ShortArray(corners.size) { corners[it] },
                SkiaBlendMode.DST,
                paint,
            )
        } finally {
            paint.close()
        }
    }
}

private val BrushWeightColor = Color(0xFFF81818)

/** Blender's weight ramp: 0 blue, 0.25 cyan, 0.5 green, 0.75 yellow, 1 red. */
internal fun weightHeatColor(weight: Float): Color {
    val w = weight.coerceIn(0f, 1f)
    val stops = WeightRamp
    val scaled = w * (stops.size - 1)
    val at = scaled.toInt().coerceAtMost(stops.size - 2)
    val t = scaled - at
    val a = stops[at]
    val b = stops[at + 1]
    return Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)
}

private val WeightRamp = listOf(
    Color(0xFF2A48E0), Color(0xFF22B8D8), Color(0xFF3CC84A), Color(0xFFE8D030), Color(0xFFE83A2A),
)

/** The ring and hover colour of each weight mode, so what a press will do shows before it is made. */
private fun weightModeColor(mode: WeightPaintMode, colors: io.github.psd2live.ui.theme.ToolColors): Color = when (mode) {
    WeightPaintMode.ADD -> Color.White
    WeightPaintMode.SUBTRACT -> colors.error
    WeightPaintMode.SET -> colors.accent
    WeightPaintMode.SMOOTH -> Color(0xFF7FD4F0)
}

/**
 * Washes a mesh in [weightHeatColor]: every vertex in its own colour, blended across the triangles, over
 * the art like the other weight washes.
 */
private fun DrawScope.drawWeightHeat(pts: List<Offset>, indices: IntArray, weights: FloatArray) {
    val vertexCount = pts.size
    if (vertexCount == 0 || indices.isEmpty()) return
    val positions = FloatArray(vertexCount * 2)
    val vertexColors = IntArray(vertexCount)
    val alpha = 0.58f
    for (i in 0 until vertexCount) {
        positions[i * 2] = pts[i].x
        positions[i * 2 + 1] = pts[i].y
        val c = weightHeatColor(weights.getOrElse(i) { 0f })
        val a = (alpha * 255f).toInt()
        vertexColors[i] = (a shl 24) or ((c.red * a).toInt() shl 16) or ((c.green * a).toInt() shl 8) or (c.blue * a).toInt()
    }
    val corners = ArrayList<Short>()
    for (tri in 0 until indices.size / 3) {
        val a = indices[tri * 3]
        val b = indices[tri * 3 + 1]
        val c = indices[tri * 3 + 2]
        if (a >= vertexCount || b >= vertexCount || c >= vertexCount) continue
        corners += a.toShort(); corners += b.toShort(); corners += c.toShort()
    }
    if (corners.isEmpty()) return
    drawIntoCanvas { canvas ->
        val paint = SkiaPaint().apply { isAntiAlias = true }
        try {
            canvas.skiaCanvas.drawVertices(
                SkiaVertexMode.TRIANGLES,
                positions,
                vertexColors,
                null,
                ShortArray(corners.size) { corners[it] },
                SkiaBlendMode.DST,
                paint,
            )
        } finally {
            paint.close()
        }
    }
}

/**
 * The tip's own falloff fill while Alt + right-drag retunes a deform brush. Kept faint, so the wash of
 * what the brush would really pull on the mesh underneath still reads through it.
 */
private const val RETUNE_TIP_ALPHA = 0.3f

/** A glued point: a diamond, so it never reads as an ordinary vertex. */
private fun DrawScope.drawGluePoint(color: Color, radius: Float, center: Offset) {
    drawPath(Path().apply { moveTo(center.x, center.y - radius); lineTo(center.x + radius, center.y); lineTo(center.x, center.y + radius); lineTo(center.x - radius, center.y); close() }, color)
}

private fun DrawScope.drawRotationArrow(
    pivot: Offset,
    tip: Offset,
    color: Color,
    tipColor: Color,
    hoveredTip: Boolean = false,
    hoveredPivot: Boolean = false,
    isAltHeld: Boolean = false,
) {
    val delta = tip - pivot
    val length = delta.getDistance().coerceAtLeast(1e-3f)
    val dir = delta / length

    // Dimensions: larger root at pivot, tapering to a rounded tip
    val rootR = minOf(11f, maxOf(8.5f, length * 0.10f))
    val tipR = minOf(4.5f, maxOf(3.2f, rootR * 0.45f))
    val angleDeg = Math.toDegrees(atan2(delta.y.toDouble(), delta.x.toDouble())).toFloat()

    // 1. Tapered needle with rounded ends at both sides (no arrow wings)
    val tipCapRect = Rect(tip.x - tipR, tip.y - tipR, tip.x + tipR, tip.y + tipR)
    val rootCapRect = Rect(pivot.x - rootR, pivot.y - rootR, pivot.x + rootR, pivot.y + rootR)
    val needlePath = Path().apply {
        arcTo(tipCapRect, angleDeg - 90f, 180f, false)
        arcTo(rootCapRect, angleDeg + 90f, 180f, false)
        close()
    }

    // Subtle drop shadow for contrast
    translate(left = 0f, top = 1.2f) {
        drawPath(needlePath, Color.Black.copy(alpha = 0.25f))
    }

    // Single solid color fill (no gradient)
    drawPath(needlePath, color.copy(alpha = 0.88f))

    // Crisp white hairline outline
    drawPath(needlePath, Color.White.copy(alpha = 0.85f), style = Stroke(width = 1.2f, join = StrokeJoin.Round))

    // 2. Handle 1: Pivot Origin (Root)
    if (hoveredPivot) {
        // Soft glowing halo on hover/drag
        drawCircle(color.copy(alpha = 0.22f), radius = rootR + 5f, center = pivot)
        drawCircle(color.copy(alpha = 0.80f), radius = rootR + 5f, center = pivot, style = Stroke(width = 1.2f))
    }
    // Concentric root hub
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = 4.8f, center = pivot + Offset(0f, 0.8f))
    drawCircle(Color.White, radius = 4.2f, center = pivot)
    drawCircle(color, radius = 2.6f, center = pivot)
    drawCircle(Color.White, radius = 1.2f, center = pivot)

    // 3. Handle 2: Tip Direction (Rotate & Scale Indicator)
    if (hoveredTip) {
        // Soft glowing halo on hover/drag
        drawCircle(tipColor.copy(alpha = 0.22f), radius = tipR + 5f, center = tip)
        drawCircle(tipColor.copy(alpha = 0.85f), radius = tipR + 5f, center = tip, style = Stroke(width = 1.2f))

        if (isAltHeld) {
            // Alt-key compact stretch indicator (<--->)
            val stretchSpan = 10f
            val sStart = tip - dir * stretchSpan
            val sEnd = tip + dir * stretchSpan
            drawLine(Color.Black.copy(alpha = 0.45f), sStart, sEnd, strokeWidth = 2.4f, cap = StrokeCap.Round)
            drawLine(Color.White, sStart, sEnd, strokeWidth = 1.4f, cap = StrokeCap.Round)

            fun drawStretchHead(tipP: Offset, headDir: Offset) {
                val hPerp = Offset(-headDir.y, headDir.x)
                val sh = Path().apply {
                    moveTo(tipP.x + headDir.x * 1.5f, tipP.y + headDir.y * 1.5f)
                    lineTo(tipP.x - headDir.x * 3.5f + hPerp.x * 2.5f, tipP.y - headDir.y * 3.5f + hPerp.y * 2.5f)
                    lineTo(tipP.x - headDir.x * 3.5f - hPerp.x * 2.5f, tipP.y - headDir.y * 3.5f - hPerp.y * 2.5f)
                    close()
                }
                drawPath(sh, tipColor)
                drawPath(sh, Color.White, style = Stroke(0.8f, join = StrokeJoin.Round))
            }
            drawStretchHead(sStart, -dir)
            drawStretchHead(sEnd, dir)
        } else {
            // Compact rotation arc: sweep shortened by half (from 72° to 36°)
            val orbitR = length

            if (orbitR >= 24f) {
                val sweepAngle = 36f
                val startAngle = angleDeg - sweepAngle / 2f
                val arcRect = Rect(pivot.x - orbitR, pivot.y - orbitR, pivot.x + orbitR, pivot.y + orbitR)
                val orbitArc = Path().apply {
                    arcTo(arcRect, startAngle, sweepAngle, false)
                }

                // Arc stroke
                drawPath(orbitArc, Color.Black.copy(alpha = 0.35f), style = Stroke(width = 3.0f, cap = StrokeCap.Round))
                drawPath(orbitArc, Color.White.copy(alpha = 0.70f), style = Stroke(width = 2.0f, cap = StrokeCap.Round))
                drawPath(orbitArc, tipColor, style = Stroke(width = 1.4f, cap = StrokeCap.Round))

                // Compact bidirectional arrowheads at arc ends
                fun drawOrbitHead(headAngleDeg: Float, isClockwise: Boolean) {
                    val rad = Math.toRadians(headAngleDeg.toDouble())
                    val endP = pivot + Offset((orbitR * cos(rad)).toFloat(), (orbitR * sin(rad)).toFloat())
                    val sign = if (isClockwise) 1f else -1f
                    val tx = (-sin(rad) * sign).toFloat()
                    val ty = (cos(rad) * sign).toFloat()
                    val nx = -ty
                    val ny = tx
                    val aLen = 4.2f
                    val aHalf = 2.6f

                    val head = Path().apply {
                        moveTo(endP.x + tx * aLen, endP.y + ty * aLen)
                        lineTo(endP.x + nx * aHalf, endP.y + ny * aHalf)
                        lineTo(endP.x - nx * aHalf, endP.y - ny * aHalf)
                        close()
                    }
                    drawPath(head, Color.Black.copy(alpha = 0.35f), style = Stroke(1.8f, join = StrokeJoin.Round))
                    drawPath(head, tipColor)
                    drawPath(head, Color.White, style = Stroke(0.8f, join = StrokeJoin.Round))
                }

                drawOrbitHead(startAngle, false)
                drawOrbitHead(startAngle + sweepAngle, true)
            } else {
                // Fallback for very short distance: compact arc around tip
                val arcR = 10f
                val sweep = 90f
                val arcRect = Rect(tip.x - arcR, tip.y - arcR, tip.x + arcR, tip.y + arcR)
                val orbitArc = Path().apply {
                    arcTo(arcRect, angleDeg - 45f, sweep, false)
                }
                drawPath(orbitArc, Color.Black.copy(alpha = 0.35f), style = Stroke(width = 2.8f, cap = StrokeCap.Round))
                drawPath(orbitArc, tipColor, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
            }
        }
    }
    // Tip center target
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = 2.8f, center = tip + Offset(0f, 0.8f))
    drawCircle(Color.White, radius = 2.2f, center = tip)
}

private fun DrawScope.drawDeformPathCurve(screenPoints: List<Offset>, stroke: Color) {
    if (screenPoints.size < 2) return
    screenPoints.zipWithNext().forEach { (a, b) ->
        drawLine(Color.Black.copy(alpha = 0.7f), a, b, 5f)
        drawLine(stroke, a, b, 2f)
    }
}

/** A path's control point, in the shared point look ([MeshLook]). */
private fun DrawScope.drawDeformPathHandle(p: Offset, colors: ToolColors, hovered: Boolean, active: Boolean) =
    drawMeshHandle(p, MeshLook.Structure, colors.accent, selected = active, hovered = hovered)

/** Live width (outer dashed) / hardness (inner) rings while placing a deform path. */
private fun DrawScope.drawDeformPathInfluencePreview(
    centers: List<Offset>,
    canvasWidth: Float,
    hardness: Float,
    viewport: CanvasViewport,
    colors: ToolColors,
    alphaScale: Float = 1f,
) {
    if (centers.isEmpty() || canvasWidth <= 0f) return
    val outerR = (canvasWidth * viewport.scale).toFloat()
    val hard = hardness.coerceIn(0f, 100f) / 100f
    val innerR = outerR * hard
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    centers.forEach { p ->
        if (hard > 0.02f && innerR > 1f) {
            drawCircle(Color(0xFF2196F3).copy(alpha = 0.12f * alphaScale), innerR, p)
            drawCircle(
                color = Color(0xFF2196F3).copy(alpha = 0.7f * alphaScale),
                radius = innerR,
                center = p,
                style = Stroke(1.2f),
            )
        }
        drawCircle(
            color = Color(0xFFF44336).copy(alpha = 0.75f * alphaScale),
            radius = outerR,
            center = p,
            style = Stroke(width = 1.4f, pathEffect = dash),
        )
        drawCircle(colors.accent.copy(alpha = 0.35f * alphaScale), 2f, p)
    }
}

/**
 * The pose tool's layer: every bone where the rig currently holds it, drawn as in the armature editor,
 * with the tip handle that an IK drag pulls. With weights on, each skinned vertex
 * is dotted in the color of the bone it follows, mixed toward the next bone across a joint band.
 */
@Composable
private fun SkeletonPoseLayer(editor: CanvasEditor, viewport: CanvasViewport, passive: Boolean = false) {
    val colors = LocalToolColors.current
    val spec = editor.bakedSkeleton ?: return
    val model = editor.model
    val pose = editor.state.parameterValues
    val bones = remember(model, spec, pose) { editor.posedBones() }
    fun colorOf(id: String) = SkeletonPalette.color(spec, id)
    // The passive (Object mode) armature is a pick target, not a posing aid: no weights, and only the
    // picked skeleton is drawn at full strength.
    val showWeights = editor.showSkeletonWeights && !passive
    val strength = if (!passive || editor.skeletonSelected) 1f else 0.45f
    val weights = remember(model, spec, showWeights) {
        if (showWeights) SkeletonPoseTool.weights(model, spec) else emptyMap()
    }
    val deformed = remember(model, pose, showWeights) {
        if (showWeights) org.umamo.render.eval.CpuDeformationEvaluator().evaluate(model, pose).worldPositions else emptyMap()
    }
    Canvas(Modifier.fillMaxSize()) {
        fun screen(x: Float, y: Float) = Offset(viewport.x(x).toFloat(), (viewport.offsetY + y * viewport.scale).toFloat())
        for ((drawableId, entry) in weights) {
            val (tree, skins) = entry
            val world = deformed[drawableId] ?: continue
            for ((vertex, skin) in skins.withIndex()) {
                if (vertex * 2 + 1 >= world.size) break
                val from = colorOf(tree[skin.from].id)
                val to = colorOf(tree[skin.to].id)
                val color = if (skin.rigid) from else androidx.compose.ui.graphics.lerp(from, to, skin.weight)
                val p = Offset(viewport.x(world[vertex * 2]).toFloat(), viewport.yFromWorld(world[vertex * 2 + 1]).toFloat())
                drawCircle(color.copy(alpha = 0.85f), 2.6f, p)
            }
        }
        val active = editor.poseDrag ?: editor.poseHover
        if (!passive) for ((id, target) in spec.ikTargets) {
            val effector = bones.firstOrNull { it.bone.id == id } ?: continue
            val p = screen(target.x, target.y)
            val error = hypot(effector.tailX - target.x, effector.tailY - target.y)
            val color = if (!target.enabled) colors.textMuted else if (error > effector.bone.ik.tolerancePx) colors.warning else colors.accent
            drawLine(color.copy(alpha = 0.6f), screen(effector.tailX, effector.tailY), p,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
            drawCircle(color, 7f, p, style = Stroke(1.5f))
            drawLine(color, p - Offset(10f, 0f), p + Offset(10f, 0f), strokeWidth = 1.5f)
            drawLine(color, p - Offset(0f, 10f), p + Offset(0f, 10f), strokeWidth = 1.5f)
        }
        for (posed in bones) {
            val lit = active?.boneId == posed.bone.id ||
                (passive && editor.skeletonSelected && editor.selectedBoneId == posed.bone.id)
            drawCanvasBone(screen(posed.headX, posed.headY), screen(posed.tailX, posed.tailY), colorOf(posed.bone.id),
                colors.windowBackground, lit = lit, tipLit = lit && active?.tip == true, strength = strength)
        }
    }
}

/** [MeshTopology.uniqueEdges] of each index array the overlay draws, kept while that array is alive. */
private val uniqueEdgeCache = java.util.WeakHashMap<IntArray, List<org.umamo.edit.MeshElement.Edge>>()

private fun cachedUniqueEdges(indices: IntArray): List<org.umamo.edit.MeshElement.Edge> =
    uniqueEdgeCache.getOrPut(indices) { MeshTopology.uniqueEdges(indices) }


/** The Bezier level's curves and anchor rings, apart from the lattice's accent so the two never read as one. */
private val BezierCurveColor = Color(0xFF5CC8F0)

/** The Bezier level's tangent handles and their stems. */
private val BezierHandleColor = Color(0xFFF2B84B)
