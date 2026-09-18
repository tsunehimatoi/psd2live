package io.github.psd2live.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.*
import io.github.psd2live.ui.state.*
import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopology
import org.umamo.render.eval.*
import org.umamo.runtime.model.*
import java.util.UUID
import kotlin.math.*

/** Canvas tools, each pointing at the shortcut action that activates it. */
/** Edit hierarchy / layer mode chosen in the top-right toolbar. */
enum class EditHierarchyMode {
    OBJECT,      // 物体模式: 选择/移动/缩放/旋转整个对象
    DEFORM,      // 变形编辑: 不改拓扑，编辑参数形变 (Level 1/2/3)
    STRUCTURE,   // 结构编辑: 改变对象基础结构/网格拓扑/分割
}

/** Canvas tools, each pointing at the shortcut action that activates it. */
internal enum class CanvasTool(val action: ShortcutAction) {
    SELECT(ShortcutAction.TOOL_SELECT),
    LASSO_SELECT(ShortcutAction.TOOL_LASSO_SELECT),
    BRUSH_SELECT(ShortcutAction.TOOL_BRUSH_SELECT),
    BRUSH(ShortcutAction.TOOL_BRUSH),
    SMOOTH(ShortcutAction.TOOL_SMOOTH),
    INFLATE(ShortcutAction.TOOL_INFLATE),
    CREATE_WARP(ShortcutAction.TOOL_CREATE_WARP),
    CREATE_ROTATION(ShortcutAction.TOOL_CREATE_ROTATION),
    CREATE_DEFORM_PATH(ShortcutAction.TOOL_CREATE_DEFORM_PATH),
    GLUE(ShortcutAction.TOOL_GLUE),
}

internal enum class SelectionStyle { BOX, LASSO }

internal val SELECTION_TOOLS = setOf(
    CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT
)

internal val DEFORM_BRUSH_TOOLS = setOf(
    CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE
)

internal val CREATION_TOOLS = setOf(
    CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH, CanvasTool.GLUE
)

/**
 * Tools that edit points rather than whole objects.
 */
internal val VERTEX_TOOLS = setOf(
    CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT,
    CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE,
)

/**
 * The target kinds a point selection may be framed in.
 */
internal val POINT_BOX_KINDS = setOf("mesh", "warp")

/** Which brush parameter the Alt + right-drag gesture latched onto; null until the drag picks a direction. */
internal enum class BrushAdjustAxis { RADIUS, HARDNESS, ANGLE }

internal data class CanvasTarget(
    val kind: String,
    val id: String,
    val geometry: RigGeometryTools.Geometry,
    val mapping: DrawableSpaceMapping,
    val indices: IntArray
) {
    val count get() = geometry.points.size / 2
}

/**
 * One node an object-mode click can pick: a drawable, or one of the deformers above it.
 *
 * [parentName] and [nextName] are what the hover annotation reads — they name the step a Ctrl-click
 * would take, so the canvas can say where the click goes before it is made rather than after.
 */
internal data class HierarchyPick(
    val kind: String,
    val layerId: String? = null,
    val deformerId: String? = null,
    val name: String,
    val parentName: String? = null,
    val nextName: String? = null,
) {
    /** The id the component colour keys off; a deformer and a layer of the same rig never collide. */
    val id get() = layerId ?: deformerId.orEmpty()
}

/** One gesture owns its pose, parent mapping and history HEAD until release. */
internal class CanvasEditor(val viewModel: PSD2LiveViewModel) {
    var state: PSD2LiveState
        get() = viewModel.state.value
        set(_) {}
    var viewport: CanvasViewport? = null
    var tool by mutableStateOf(CanvasTool.SELECT)

    // Top-right Hierarchy & Level state
    var hierarchyMode by mutableStateOf(EditHierarchyMode.OBJECT)
    var editLevel by mutableStateOf(2) // Level 1 (grid), Level 2 (bezier), Level 3 (macro)

    // Level 2 Bezier Deformer state
    var bezierState by mutableStateOf<BezierDeformerState?>(null)
    var activeBezierAnchor by mutableStateOf<Pair<Int, Int>?>(null)
    var activeBezierHandle by mutableStateOf<Triple<Int, Int, BezierHandleDir>?>(null)
    var hoveredBezierAnchor by mutableStateOf<Pair<Int, Int>?>(null)
    var hoveredBezierHandle by mutableStateOf<Triple<Int, Int, BezierHandleDir>?>(null)

    // Interactive Creation state
    var isCreatingWarp by mutableStateOf(false)
    var isCreatingRotation by mutableStateOf(false)
    var creationStart by mutableStateOf<Offset?>(null)
    var creationCurrent by mutableStateOf<Offset?>(null)
    var warpCreateGridRows by mutableStateOf(4)
    var warpCreateGridCols by mutableStateOf(4)
    var warpCreateBezierRows by mutableStateOf(2)
    var warpCreateBezierCols by mutableStateOf(2)
    var glueFirstMesh by mutableStateOf<String?>(null)
    var glueHoverMesh by mutableStateOf<String?>(null)
    var brushSelecting by mutableStateOf(false)

    var vertices by mutableStateOf(emptySet<Int>())
    var radius by mutableStateOf(48f)
    var strength by mutableStateOf(0.5f)
    var hardness by mutableStateOf(0.35f)
    var brushShape by mutableStateOf(BrushShape.CIRCLE)
    var brushAngle by mutableStateOf(0f)
    var brushAspect by mutableStateOf(1f)
    /** Persistent direction toggle for the inflate brush; flipped by the options-bar chip and live Alt. */
    var inflateInvert by mutableStateOf(false)
    /** Live feedback only: the direction the next stroke would take right now. */
    var shrinks by mutableStateOf(false)
    /** True while Alt + right-drag is retuning the brush; the overlay keys its HUD and feather fill off it. */
    var adjustingBrush by mutableStateOf(false)
        private set
    /** Which parameter the live adjustment latched onto; null until the drag clears the lock threshold. */
    var brushAxis by mutableStateOf<BrushAdjustAxis?>(null)
        private set
    var pathWidth by mutableStateOf(0.12f)
    var pathLevel by mutableStateOf(2)
    var activePath by mutableStateOf<String?>(null)
    var pathPoint by mutableStateOf(-1)
    var drawingPath by mutableStateOf(false)
    var draftPathId by mutableStateOf<String?>(null)
    var draft by mutableStateOf(emptyList<Pair<Float, Float>>())
    var cursor by mutableStateOf<Offset?>(null)
    var marquee by mutableStateOf(emptyList<Offset>())
    var preview by mutableStateOf<PuppetModel?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var space by mutableStateOf(false)
    var axis by mutableStateOf<String?>(null)
    var parameter by mutableStateOf<String?>(null)
    var elementMode by mutableStateOf(0) // vertex / edge / face
    var objectMode by mutableStateOf(true)
    var objects by mutableStateOf(emptySet<String>())
    var selectionStyle by mutableStateOf(SelectionStyle.BOX)

    // Visual feedback & hover state
    var hoveredVertex by mutableStateOf<Int?>(null)
    var hoveredHandle by mutableStateOf(BoundingHandle.NONE)
    var isHoveringObject by mutableStateOf(false)
    /** What object mode would pick at the pointer right now; drives the colour annotation and its HUD. */
    var hoveredPick by mutableStateOf<HierarchyPick?>(null)
    var activeHandle by mutableStateOf(BoundingHandle.NONE)
    var dragStartPos by mutableStateOf(Offset.Zero)
    var initialBounds by mutableStateOf<BoundingBox?>(null)
    /** True while the live gesture is a box drag, as opposed to a pick, a marquee or a brush stroke. */
    private var boxDrag = false
    /**
     * The points each target's box drag moves, frozen at press. Index-aligned with [objectTargets]: the
     * whole geometry for the object tools and for a rotation deformer, the vertex selection otherwise.
     */
    private var dragIndices = emptyList<Set<Int>>()
    /** The box a drag is currently showing, in frame coordinates. Null except while a gesture is live. */
    var currentDragBounds by mutableStateOf<BoundingBox?>(null)

    /**
     * The transform frame's orientation in degrees. The box is a rectangle *inside* this frame rather
     * than a fresh axis-aligned hull of the selection — which is what lets rotate, then move, then scale
     * read as one continuous Photoshop-style transform instead of three that each reset the box.
     *
     * Its lifetime is the tool session: switching tools (which cancels) or Escape resets it to
     * axis-aligned. Picking another object, and a gesture committing, do not.
     */
    var frameAngle by mutableStateOf(0f)
        private set

    /** The frame orientation and pivot frozen at press, so a drag is measured against one frame. */
    private var frameAngleAtPress = 0f
    private var framePivotAtPress = Offset.Zero

    /**
     * The layer SELECT picked on press. A marquee started on top of an object is still a marquee, so a
     * release that catches nothing has to fall back to this rather than replace the pick with the empty
     * set — a plain click jitters a few raw pixels, which is enough to count as a drag.
     */
    private var pressedObject: String? = null
    private var initialScreenPoints = emptyList<List<Offset>>()
    private var objectTargets = emptyList<CanvasTarget>()
    private var pendingObjects = emptyList<JsonObject>()
    private var start = Offset.Zero
    private var previous = Offset.Zero
    private var targetAtPress: CanvasTarget? = null
    private var original: PuppetModel? = null
    private var pending: JsonObject? = null
    private var head: String? = null
    private var moved = false
    private var additive = false
    private var subtractive = false
    /** Inflate direction is latched here on press so a stroke never flips sign mid-drag. */
    private var shrinkAtPress = false
    /** Alt + right-drag latches its anchor and the pre-drag brush values here, so Alt can be released mid-drag. */
    private var brushAnchor = Offset.Zero
    private var brushRadiusAtStart = 48f
    private var brushHardnessAtStart = 0.35f
    private var brushAngleAtStart = 0f
    private var dragging = false

    fun cycleBrushShape() {
        val entries = BrushShape.entries
        val next = (brushShape.ordinal + 1) % entries.size
        brushShape = entries[next]
    }
    private var cachedSource: PuppetModel? = null
    private var cachedPose = emptyMap<ParameterId, Float>()
    private var cachedWorlds = emptyMap<DeformerId, DeformerWorld>()
    private val cachedTargets = mutableMapOf<String, CanvasTarget>()

    val inGesture get() = dragging
    val model get() = preview ?: state.previewModel!!.rig.puppet
    val pose get() = state.parameterValues.mapKeys { it.key.raw }

    /**
     * Whether the active tool should draw a transform box at all. Object mode is selection only, so
     * the box never appears there — and neither do its handles, which read the same frame.
     */
    val drawsTransformBox get() = hierarchyMode != EditHierarchyMode.OBJECT && tool == CanvasTool.SELECT && (
        vertices.isNotEmpty() || (target()?.kind == "rotation")
    )

    /**
     * Whether the shared Precise Transform controls have something to act on.
     */
    val hasTransformSelection: Boolean
        get() {
            if (hierarchyMode == EditHierarchyMode.OBJECT) return false
            val t = target() ?: return false
            return t.kind == "rotation" || vertices.any { it in 0 until t.count }
        }
    val editable get() = !busy && !state.canvasEditBusy && !state.isGenerating && !state.isAnalyzing && state.historySnapshot != null

    fun target(source: PuppetModel = model, layerId: String? = state.selectedLayerId, deformerId: String? = state.selectedDeformerId): CanvasTarget? {
        val deformer = source.deformers.firstOrNull { it.id.raw == deformerId }
        val drawable = source.drawables.firstOrNull { state.previewModel!!.rig.layerIdByDrawableId[it.id.raw] == layerId }
        val kind: String; val id: String; val parent: DeformerId?; val indices: IntArray
        if (deformer is Deformer.Warp) {
            if (!deformer.isSelectable || !deformer.isVisible || state.deformerVisibility[deformer.id.raw] == false) return null
            kind = "warp"; id = deformer.id.raw; parent = deformer.parent
            indices = IntArray(0)
        } else if (deformer is Deformer.Rotation) {
            if (!deformer.isSelectable || !deformer.isVisible || state.deformerVisibility[deformer.id.raw] == false) return null
            kind = "rotation"; id = deformer.id.raw; parent = deformer.parent; indices = IntArray(0)
        } else if (drawable?.mesh != null) {
            if (layerId !in state.effectiveVisibleLayerIds || !drawable.isSelectable) return null
            kind = "mesh"; id = drawable.id.raw; parent = drawable.parentDeformerId; indices = drawable.mesh.indices
        } else return null
        if (cachedSource !== source || cachedPose != state.parameterValues) {
            cachedSource = source; cachedPose = state.parameterValues; cachedTargets.clear()
            cachedWorlds = buildDeformerWorlds(source.deformers, { p -> state.parameterValues[p] ?: source.parameters.firstOrNull { it.id == p }?.default ?: 0f })
        }
        val worlds = cachedWorlds
        if (parent != null && worlds[parent] == null) return null
        return cachedTargets.getOrPut("$kind:$id") { CanvasTarget(kind, id, RigGeometryTools.geometry(source, kind, id, pose), DrawableSpaceMapping(parent?.let { worlds[it] }), indices) }
    }

    fun screen(local: FloatArray, target: CanvasTarget, viewport: CanvasViewport): List<Offset> {
        val world = target.mapping.localToWorld(local)
        return (world.indices step 2).map { Offset(viewport.x(world[it]).toFloat(), viewport.yFromWorld(world[it + 1]).toFloat()) }
    }

    fun local(point: Offset, target: CanvasTarget, viewport: CanvasViewport, seed: Pair<Float, Float> = 0.5f to 0.5f): Pair<Float, Float> {
        val world = floatArrayOf(((point.x - viewport.offsetX) / viewport.scale).toFloat(), -((point.y - viewport.offsetY) / viewport.scale).toFloat())
        val result = target.mapping.worldToLocal(world, floatArrayOf(seed.first, seed.second), setOf(0))
        return result[0] to result[1]
    }

    fun paths() = model.deformPaths.filter { it.drawableId.raw == target()?.id && it.editLevel == pathLevel }
    fun selectedPath() = paths().firstOrNull { it.id == activePath }

    private fun coordinate(t: CanvasTarget) = buildMap {
        t.geometry.axes.forEach { a -> put(a.parameterId.raw, pose[a.parameterId.raw] ?: model.parameters.single { it.id == a.parameterId }.default) }
        parameter?.let { p -> model.parameters.firstOrNull { it.id.raw == p }?.let { put(p, pose[p] ?: it.default) } }
    }

    private fun geometryCommand(t: CanvasTarget, points: FloatArray) = buildJsonObject {
        put("op", "canvas_geometry"); put("kind", t.kind); put("id", t.id)
        put("key", JsonObject(coordinate(t).mapValues { JsonPrimitive(it.value) }))
        put("points", JsonArray(points.map(::JsonPrimitive)))
    }

    fun cancel() {
        if (busy) return
        endBrushAdjust(cancel = true)
        preview = null; pending = null; dragging = false; targetAtPress = null; original = null
        marquee = emptyList(); draft = emptyList(); draftPathId = null; drawingPath = false
        axis = null; head = null; objectTargets = emptyList(); pendingObjects = emptyList()
        activeHandle = BoundingHandle.NONE
        initialBounds = null
        boxDrag = false; dragIndices = emptyList()
        endTransformBox()
        // The frame belongs to the tool session, so cancelling is the only thing that drops it.
        frameAngle = 0f
        initialScreenPoints = emptyList()
    }

    fun resetSelection() {
        if (!busy) cancel()
        vertices = emptySet(); activePath = null; pathPoint = -1
    }

    fun activateTool(next: CanvasTool) {
        if (busy) return
        cancel()
        tool = next
        error = null
        if (next == CanvasTool.LASSO_SELECT) selectionStyle = SelectionStyle.LASSO
        else if (next == CanvasTool.SELECT) selectionStyle = SelectionStyle.BOX
        objectMode = (hierarchyMode == EditHierarchyMode.OBJECT)
        if (next == CanvasTool.SELECT && objectMode) vertices = emptySet()
        clearHover()
    }

    @JvmName("changeHierarchyMode")
    fun setHierarchyMode(next: EditHierarchyMode) {
        if (busy) return
        cancel()
        hierarchyMode = next
        objectMode = (next == EditHierarchyMode.OBJECT)
        if (next == EditHierarchyMode.OBJECT) {
            vertices = emptySet()
        } else if (next == EditHierarchyMode.DEFORM && editLevel == 2) {
            ensureBezierState()
        }
        clearHover()
    }

    @JvmName("changeEditLevel")
    fun setEditLevel(level: Int) {
        editLevel = level
        if (level == 2 && hierarchyMode == EditHierarchyMode.DEFORM) {
            ensureBezierState()
        }
        clearHover()
    }

    val warpBezierDivisions = mutableMapOf<String, Pair<Int, Int>>()

    fun ensureBezierState() {
        val t = target()
        if (t != null && t.kind == "warp") {
            val warp = model.deformers.filterIsInstance<Deformer.Warp>().firstOrNull { it.id.raw == t.id }
            if (warp != null) {
                val rows = warp.rows
                val cols = warp.columns
                val (bRows, bCols) = warpBezierDivisions[t.id] ?: (2 to 2)
                val cur = bezierState
                if (cur == null || cur.bezierRows != bRows || cur.bezierCols != bCols) {
                    val bState = BezierDeformerState(bRows, bCols)
                    bState.initFromLattice(t.geometry.points, rows, cols)
                    bezierState = bState
                }
            }
        } else {
            bezierState = null
        }
    }

    fun clearHover() {
        cursor = null
        hoveredVertex = null
        hoveredHandle = BoundingHandle.NONE
        hoveredBezierAnchor = null
        hoveredBezierHandle = null
        isHoveringObject = false
        // The hierarchy panel publishes the same pair from its own hover, so only retract a highlight
        // this editor actually put up — a tool switch must not blink out the panel's.
        if (hoveredPick != null) viewModel.setHoveredItem(null, null)
        hoveredPick = null
        shrinks = inflateInvert
    }

    /**
     * The frame the transform box lives in: the box itself, in frame coordinates, and the screen pivot
     * the frame turns about.
     *
     * Everything downstream works in these coordinates, so an oriented box needs no special case — it is
     * always an axis-aligned rectangle in a frame that happens to be turned.
     *
     * Not gated on `dragging`: a transform drag keeps its own box past the mouse-up, until the commit it
     * produced resolves. See endTransformBox.
     */
    fun transformFrame(viewport: CanvasViewport): TransformFrame? {
        // Object mode is selection only, so it has no frame at all. Enforced here rather than at each
        // caller: the box, its handles, the hover ring and the precise-transform pivot all read the
        // frame, and one null is what keeps every one of them out of the mode.
        if (hierarchyMode == EditHierarchyMode.OBJECT) return null
        val bounds = currentDragBounds
        if (bounds != null) return TransformFrame(bounds, framePivotAtPress, frameAngle)
        return selectionFrame(viewport)
    }

    /**
     * The frame the selected points make, in the tool's own frame orientation.
     *
     * The frame hugs exactly the selected vertices rather than the whole mesh they belong to, which is
     * what lets a box around three points of a cheek read as those three points.
     */
    private fun selectionFrame(viewport: CanvasViewport): TransformFrame? {
        val t = target() ?: return null
        if (t.kind !in POINT_BOX_KINDS) return null
        if (vertices.isEmpty()) return null
        return frameOf(screen(t.geometry.points, t, viewport), vertices, frameAngle)
    }

    /**
     * The points a transform gesture on [t] moves: the vertex selection for a mesh or a warp lattice,
     * and for a rotation deformer — whose two axis points are the whole deformer — everything.
     *
     * Deliberately *not* "empty selection means everything": an empty selection means nothing to move,
     * not the whole mesh. That fallback would move artwork nobody asked to move.
     */
    private fun gestureIndices(t: CanvasTarget): Set<Int> =
        if (t.kind != "rotation") vertices.filter { it in 0 until t.count }.toSet() else (0 until t.count).toSet()

    /** Layers under [pos], in the order a Ctrl-click cycles them. Empty when nothing is pickable. */
    private fun layerCandidates(pos: Offset, viewport: CanvasViewport): List<String> {
        val source = state.previewModel ?: return emptyList()
        val geometry = RigCanvasSupport.evaluate(source, state.parameterValues)
        return RigCanvasSupport.hitLayers(
            source,
            RigCanvasSupport.boundsByDrawable(geometry),
            viewport.canvasX(pos.x.toInt()),
            viewport.canvasY(pos.y.toInt()),
            state.effectiveVisibleLayerIds,
            geometry,
            state.drawOrderOverrides
        ).filter { target(source.rig.puppet, it, null) != null }
    }

    fun updateHover(pos: Offset, viewport: CanvasViewport, ctrl: Boolean = false) {
        cursor = pos
        if (dragging) return
        hoveredBezierAnchor = null
        hoveredBezierHandle = null
        hoveredVertex = null
        hoveredHandle = BoundingHandle.NONE
        isHoveringObject = false
        hoveredPick = null

        if (tool in CREATION_TOOLS) {
            if (tool == CanvasTool.GLUE) {
                glueHoverMesh = layerCandidates(pos, viewport).firstOrNull { it != glueFirstMesh }
            } else if (tool == CanvasTool.CREATE_DEFORM_PATH) {
                val t = target()
                if (t != null && t.kind == "mesh") {
                    val hit = paths().flatMap { path ->
                        screen(DeformPathTools.positions(path, t.geometry.points).flatMap { listOf(it.first, it.second) }.toFloatArray(), t, viewport).mapIndexed { i, p -> Triple(path, i, (p - pos).getDistance()) }
                    }.filter { it.third <= 10f }.minByOrNull { it.third }
                    hoveredVertex = hit?.second
                }
            }
            return
        }

        // Object mode has no transform box, so no handle is ever live here. What the pointer is over is
        // the pick itself, and resolving it through the same call the press makes is what guarantees the
        // annotation names the thing a click would actually select — Ctrl included.
        if (hierarchyMode == EditHierarchyMode.OBJECT) {
            if (tool == CanvasTool.SELECT) {
                val pick = objectPick(pos, viewport, ctrl)
                hoveredPick = pick
                isHoveringObject = pick != null
                viewModel.setHoveredItem(pick?.layerId, pick?.deformerId)
            } else if (tool in SELECTION_TOOLS) {
                val hit = layerCandidates(pos, viewport).firstOrNull()
                isHoveringObject = hit != null
                viewModel.setHoveredItem(hit, null)
            }
            return
        }

        val t = target() ?: return

        if (hierarchyMode == EditHierarchyMode.DEFORM) {
            if (t.kind == "warp") {
                if (editLevel == 2) {
                    ensureBezierState()
                    val bState = bezierState
                    if (bState != null) {
                        var bestHandleDist = Float.MAX_VALUE
                        var bestHandle: Triple<Int, Int, BezierHandleDir>? = null
                        bState.handles.forEach { (key, handle) ->
                            val sp = screen(floatArrayOf(handle.x, handle.y), t, viewport).firstOrNull() ?: return@forEach
                            val dist = (sp - pos).getDistance()
                            if (dist <= 8f && dist < bestHandleDist) {
                                bestHandleDist = dist
                                bestHandle = key
                            }
                        }
                        if (bestHandle != null) {
                            hoveredBezierHandle = bestHandle
                            return
                        }

                        var bestAnchorDist = Float.MAX_VALUE
                        var bestAnchor: Pair<Int, Int>? = null
                        bState.anchors.forEach { (key, anchor) ->
                            val sp = screen(floatArrayOf(anchor.x, anchor.y), t, viewport).firstOrNull() ?: return@forEach
                            val dist = (sp - pos).getDistance()
                            if (dist <= 9f && dist < bestAnchorDist) {
                                bestAnchorDist = dist
                                bestAnchor = key
                            }
                        }
                        if (bestAnchor != null) {
                            hoveredBezierAnchor = bestAnchor
                            return
                        }
                    }
                    if (tool == CanvasTool.SELECT) {
                        val frame = transformFrame(viewport)
                        hoveredHandle = if (frame != null) transformHandleAt(pos, frame) else BoundingHandle.NONE
                    }
                    return
                } else {
                    updatePointHover(pos, viewport, t)
                    return
                }
            } else if (t.kind == "mesh") {
                if (paths().isNotEmpty() && activePath != null) {
                    val hit = paths().filter { it.id == activePath }.flatMap { path ->
                        screen(DeformPathTools.positions(path, t.geometry.points).flatMap { listOf(it.first, it.second) }.toFloatArray(), t, viewport).mapIndexed { i, p -> Triple(path, i, (p - pos).getDistance()) }
                    }.filter { it.third <= 10f }.minByOrNull { it.third }
                    if (hit != null) {
                        hoveredVertex = hit.second
                        return
                    }
                }
                updatePointHover(pos, viewport, t)
                return
            } else if (t.kind == "rotation") {
                updatePointHover(pos, viewport, t)
                return
            }
        }

        if (hierarchyMode == EditHierarchyMode.STRUCTURE) {
            updatePointHover(pos, viewport, t)
        }
    }

    /**
     * Hover for the two point tools: the box's handle ring first, then the nearest point.
     *
     * The ring wins because it is drawn on top of the artwork — a point sitting under a handle would
     * otherwise fight the grab the pointer is visibly over. The frame is built from the points already
     * screened here rather than from [transformFrame], which would screen them a second time on every
     * pointer move.
     */
    private fun updatePointHover(pos: Offset, viewport: CanvasViewport, t: CanvasTarget?) {
        val points = if (t != null) screen(t.geometry.points, t, viewport) else emptyList()
        // A rotation deformer never gets a box, so its points are screened for the vertex hover alone.
        val frame = if (t == null || t.kind !in POINT_BOX_KINDS) null else frameOf(points, vertices, frameAngle)
        hoveredHandle = frame?.let { transformRingAt(pos, it) } ?: BoundingHandle.NONE
        hoveredVertex = if (hoveredHandle != BoundingHandle.NONE || points.isEmpty()) null
        else points.indices.minByOrNull { (points[it] - pos).getDistance() }?.takeIf { (points[it] - pos).getDistance() <= 10f }
    }

    /** The pointer the canvas should show right now, derived from the tool and what is under it. */
    fun activeCursor(): java.awt.Cursor {
        val arrow = java.awt.Cursor.getDefaultCursor()
        val hand = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        val cross = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR)
        val move = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)
        if (space) return if (dragging) move else hand
        if (dragging) {
            if (isCreatingWarp || isCreatingRotation) return cross
            if (marquee.isNotEmpty()) return cross
            if (tool in DEFORM_BRUSH_TOOLS || tool == CanvasTool.BRUSH_SELECT) return cross
            if (boxDrag) return handleCursor(activeHandle)
            if (activeBezierAnchor != null || activeBezierHandle != null) return hand
            return move
        }
        if (tool in CREATION_TOOLS) {
            return if (tool == CanvasTool.GLUE) hand else cross
        }
        if (tool == CanvasTool.BRUSH_SELECT || tool in DEFORM_BRUSH_TOOLS) return cross
        if (tool == CanvasTool.LASSO_SELECT) return cross

        if (hoveredBezierHandle != null || hoveredBezierAnchor != null) return hand
        if (hoveredHandle != BoundingHandle.NONE) return handleCursor(hoveredHandle)
        if (hoveredVertex != null) return hand

        if (hierarchyMode == EditHierarchyMode.OBJECT) {
            if (tool == CanvasTool.SELECT) return if (isHoveringObject) hand else arrow
        } else {
            if (tool == CanvasTool.SELECT) return arrow
        }
        return arrow
    }

    /** The cursor a transform handle promises, shared by hover and the drag itself. */
    private fun handleCursor(handle: BoundingHandle): java.awt.Cursor = when (handle) {
        BoundingHandle.TOP_LEFT, BoundingHandle.BOTTOM_RIGHT -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NW_RESIZE_CURSOR)
        BoundingHandle.TOP_RIGHT, BoundingHandle.BOTTOM_LEFT -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NE_RESIZE_CURSOR)
        BoundingHandle.TOP, BoundingHandle.BOTTOM -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)
        BoundingHandle.LEFT, BoundingHandle.RIGHT -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)
        BoundingHandle.BODY -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)
        // ROTATE keeps the plain arrow on purpose. AWT has no rotate cursor, and the crosshair that
        // stood in for one read as "place a point" rather than "turn this" — the handle says what it
        // does by lighting up under the pointer instead of by changing the pointer.
        BoundingHandle.ROTATE, BoundingHandle.NONE -> java.awt.Cursor.getDefaultCursor()
    }

    fun commit(command: JsonObject) {
        commitBatch(listOf(command))
    }

    /** Returns false when the edit never reached history, so the caller can drop its preview. */
    private fun commitBatch(commands: List<JsonObject>): Boolean {
        if (!editable) { endTransformBox(); return false }
        val expected = head ?: state.historySnapshot?.headNodeId
        if (expected == null) { endTransformBox(); return false }
        try {
            val result = RigAuthoringJournal.compile(state.previewModel!!.rig.puppet, JsonArray(commands))
            // A gesture that ends where it started compiles to nothing: dragging a vertex back onto
            // itself, or a numeric transform applied with its identity values. Dispatching that would ask
            // the workspace for an edit that cannot change anything, so drop it here and let the caller's
            // `false` clear the preview. This is also what keeps the gesture from flipping canvasEditBusy
            // and queueing a pointless save.
            if (result.second.isEmpty()) { preview = null; pending = null; head = null; endTransformBox(); return false }
            preview = result.first; busy = true; error = null
            // The pending edit is only settled here, so this is where the box a drag was holding gives
            // way to one computed from what the model actually became.
            viewModel.saveAuthoringEdits(expected, JsonArray(result.second)) { failure ->
                busy = false; preview = null; pending = null; head = null; error = failure
                endTransformBox()
                if (failure == null) commands.lastOrNull { it["op"]?.jsonPrimitive?.content in setOf("canvas_create_warp", "canvas_create_rotation") }?.let { viewModel.selectDeformer(it.getValue("id").jsonPrimitive.content) }
            }
        } catch (e: Exception) { error = e.message; preview = null; pending = null; head = null; endTransformBox() }
        return true
    }

    fun topology(action: String) {
        val t = target() ?: return
        if (t.kind != "mesh" || !editable) return
        head = null
        commit(buildJsonObject { put("op", "canvas_topology"); put("id", t.id); put("action", action); put("vertices", JsonArray(vertices.map(::JsonPrimitive))) })
        if (error == null) vertices = emptySet()
    }

    fun finishPath() {
        val t = target() ?: return
        if (draft.size < 2 || t.kind != "mesh") return
        try {
            val extent = RigGeometryTools.bounds(t.geometry.points).let { max(it[2], it[3]) }
            val previous = paths().firstOrNull { it.id == draftPathId }
            val points = draft.mapIndexed { i, p -> DeformPathTools.bind(t.geometry.points, t.indices, p.first, p.second, previous?.points?.getOrNull(i)?.corner ?: false) }
            val path = previous?.copy(points = points) ?: DeformPath(UUID.randomUUID().toString(), DrawableId(t.id), points, extent * pathWidth, hardness = hardness, editLevel = pathLevel)
            head = null; commit(DeformPathJournal.encode(path)); activePath = path.id; drawingPath = false; draft = emptyList()
        } catch (e: Exception) { error = e.message }
    }

    fun changePath(update: (DeformPath) -> DeformPath) {
        try { selectedPath()?.let { head = null; commit(DeformPathJournal.encode(update(it))) } }
        catch (failure: IllegalArgumentException) { error = failure.message }
    }

    fun extendPath() {
        val t = target() ?: return; val path = selectedPath() ?: return
        if (path.closed) return
        draft = DeformPathTools.positions(path, t.geometry.points); draftPathId = path.id; drawingPath = true; tool = CanvasTool.CREATE_DEFORM_PATH
    }

    fun preciseTransform(vp: CanvasViewport? = null, first: Float, second: Float = 0f, scaleMode: Boolean = false, rotateMode: Boolean = false) {
        if (!editable) return
        val viewport = vp ?: this.viewport ?: return
        val targets = transformTargets(model)
        val indexSets = targets.map { gestureIndices(it) }
        val chosen = targets.flatMapIndexed { k, item -> screen(item.geometry.points, item, viewport).filterIndexed { i, _ -> i in indexSets[k] } }
        if (chosen.isEmpty()) return
        // The same pivot the transform box uses, so the panel and a canvas drag turn about one point.
        // A rotation deformer is the exception: it turns about its origin, which is its first axis point.
        val center = if (targets.size == 1 && targets[0].kind == "rotation") screen(targets[0].geometry.points, targets[0], viewport)[0] else selectionPivot(chosen)
        val commands = targets.mapIndexed { itemIndex, item ->
            val indices = indexSets[itemIndex]
            val world = item.mapping.localToWorld(item.geometry.points)
            screen(item.geometry.points, item, viewport).forEachIndexed { i, p ->
                if (i in indices) {
                    val d = p - center
                    val destination = when {
                        rotateMode -> { val a = first * PI.toFloat() / 180f; center + Offset(d.x * cos(a) - d.y * sin(a), d.x * sin(a) + d.y * cos(a)) }
                        scaleMode -> center + d * (first / 100f).coerceAtLeast(0.001f)
                        else -> p + Offset(first, second) * viewport.scale.toFloat()
                    }
                    world[i * 2] = ((destination.x - viewport.offsetX) / viewport.scale).toFloat()
                    world[i * 2 + 1] = -((destination.y - viewport.offsetY) / viewport.scale).toFloat()
                }
            }
            geometryCommand(item, item.mapping.worldToLocal(world, item.geometry.points, indices))
        }
        head = null; commitBatch(commands)
    }

    fun deletePathPoint() {
        val p = selectedPath() ?: return
        if (pathPoint >= 0 && p.points.size > 2) changePath { it.copy(points = it.points.filterIndexed { i, _ -> i != pathPoint }) }
        else { head = null; commit(buildJsonObject { put("op", "path_delete"); put("id", p.id) }); activePath = null }
        pathPoint = -1
    }

    fun selectAll(invert: Boolean = false) {
        if (objectMode) {
            objects = state.effectiveVisibleLayerIds.filter { (!invert || it !in objects) && target(model, it, null) != null }.toSet()
            viewModel.selectLayer(objects.lastOrNull())
        } else target()?.let { t -> vertices = (0 until t.count).filter { !invert || it !in vertices }.toSet() }
    }

    fun selectLinked() {
        val t = target() ?: return; if (t.indices.isEmpty()) return
        val adjacency = MeshTopology.buildVertexAdjacency(t.count, t.indices)
        vertices = vertices.flatMap { MeshTopology.connectedVertices(adjacency, it) }.toSet()
    }

    fun createWarp(rotation: Boolean = false) {
        val t = target() ?: return
        if (t.kind != "mesh") return
        val name = if (rotation) "Rotation" else "Warp"
        val id = "${name}_${UUID.randomUUID()}"
        val ids = objects.mapNotNull { target(model, it, null)?.id }.ifEmpty { listOf(t.id) }
        head = null; commit(buildJsonObject { put("op", if (rotation) "canvas_create_rotation" else "canvas_create_warp"); put("id", id); put("name", name); put("meshes", JsonArray(ids.map(::JsonPrimitive))) })
    }

    fun createWarpFromBounds(s: Offset, e: Offset, viewport: CanvasViewport) {
        val x0 = minOf(s.x, e.x); val y0 = minOf(s.y, e.y)
        val x1 = maxOf(s.x, e.x); val y1 = maxOf(s.y, e.y)
        val wX = ((x0 - viewport.offsetX) / viewport.scale).toFloat()
        val wY = -((y1 - viewport.offsetY) / viewport.scale).toFloat()
        val wW = ((x1 - x0) / viewport.scale).toFloat()
        val wH = ((y1 - y0) / viewport.scale).toFloat()

        val targetMeshes = objects.mapNotNull { target(model, it, null)?.id }.ifEmpty { listOfNotNull(target()?.takeIf { it.kind == "mesh" }?.id) }
        if (targetMeshes.isEmpty()) return
        val id = "Warp_${UUID.randomUUID()}"
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", id)
            put("name", "Warp")
            put("rows", warpCreateGridRows)
            put("columns", warpCreateGridCols)
            put("bezierRows", warpCreateBezierRows)
            put("bezierColumns", warpCreateBezierCols)
            put("bounds", buildJsonObject {
                put("x", wX)
                put("y", wY)
                put("w", wW)
                put("h", wH)
            })
            put("meshes", JsonArray(targetMeshes.map(::JsonPrimitive)))
        }
        head = null
        commit(cmd)
    }

    fun createRotationFromPoints(s: Offset, e: Offset, viewport: CanvasViewport) {
        val originX = ((s.x - viewport.offsetX) / viewport.scale).toFloat()
        val originY = -((s.y - viewport.offsetY) / viewport.scale).toFloat()
        val armX = ((e.x - viewport.offsetX) / viewport.scale).toFloat()
        val armY = -((e.y - viewport.offsetY) / viewport.scale).toFloat()
        val angleDeg = Math.toDegrees(kotlin.math.atan2((armY - originY).toDouble(), (armX - originX).toDouble())).toFloat()

        val targetMeshes = objects.mapNotNull { target(model, it, null)?.id }.ifEmpty { listOfNotNull(target()?.takeIf { it.kind == "mesh" }?.id) }
        if (targetMeshes.isEmpty()) return
        val id = "Rotation_${UUID.randomUUID()}"
        val cmd = buildJsonObject {
            put("op", "canvas_create_rotation")
            put("id", id)
            put("name", "Rotation")
            put("origin", JsonArray(listOf(originX, originY).map(::JsonPrimitive)))
            put("angle", angleDeg)
            put("meshes", JsonArray(targetMeshes.map(::JsonPrimitive)))
        }
        head = null
        commit(cmd)
    }

    fun createGlue(meshA: String, meshB: String) {
        val id = "Glue_${UUID.randomUUID()}"
        val cmd = buildJsonObject {
            put("op", "canvas_create_glue")
            put("id", id)
            put("name", "Glue")
            put("mesh_a", meshA)
            put("mesh_b", meshB)
        }
        head = null
        commit(cmd)
    }

    /**
     * The layer a click at [pos] picks. Clicking a stack walks it one layer per click — the same rule
     * [RigCanvasSupport.hitLayer] applies in the preview tab — so the layer under the pointer can be
     * reached without naming it in the hierarchy first. Null when nothing pickable is there.
     */
    private fun pickLayer(pos: Offset, viewport: CanvasViewport): String? =
        RigCanvasSupport.nextLayer(layerCandidates(pos, viewport), state.selectedLayerId)

    /**
     * Every node a Ctrl-click cycles at [pos], in click order: each layer under the cursor followed by
     * the deformers above it, innermost first, then on to the next layer in the stack.
     *
     * An ancestor is visited once however many layers sit under it, so two braids sharing a head
     * rotation step through that rotation together instead of meeting it twice. Deformers that are
     * hidden, locked or missing from the rig are left out — [target] is the single definition of
     * "pickable" and asking it is what keeps the ring from offering a step that selects nothing.
     */
    private fun hierarchyRing(pos: Offset, viewport: CanvasViewport): List<HierarchyPick> {
        val preview = state.previewModel ?: return emptyList()
        val source = preview.rig.puppet
        val ring = mutableListOf<HierarchyPick>()
        val seen = mutableSetOf<String>()
        layerCandidates(pos, viewport).forEach { layerId ->
            val drawable = source.drawables.firstOrNull { preview.rig.layerIdByDrawableId[it.id.raw] == layerId }
            if (drawable != null && seen.add("mesh:$layerId")) {
                ring += HierarchyPick(
                    kind = "mesh",
                    layerId = layerId,
                    name = drawable.name,
                    parentName = drawable.parentDeformerId?.let { id -> source.deformers.firstOrNull { it.id == id }?.name },
                )
            }
            var parent = drawable?.parentDeformerId
            while (parent != null) {
                val id = parent.raw
                if (!seen.add("deformer:$id")) break
                val deformer = source.deformers.firstOrNull { it.id == parent } ?: break
                if (target(source, null, id) == null) break
                ring += HierarchyPick(
                    kind = if (deformer is Deformer.Rotation) "rotation" else "warp",
                    deformerId = id,
                    name = deformer.name,
                    parentName = deformer.parent?.let { p -> source.deformers.firstOrNull { it.id == p }?.name },
                )
                parent = deformer.parent
            }
        }
        // Each node names the step Ctrl takes from it, so the HUD can show the destination up front.
        return ring.mapIndexed { i, pick -> pick.copy(nextName = ring[(i + 1) % ring.size].name) }
    }

    /**
     * What a click at [pos] picks. A plain click walks the stack the one-layer-per-click way the
     * preview tab and [pickLayer] use; Ctrl steps to the next node of the hierarchy instead, so the
     * whole chain a part hangs off is reachable without leaving the canvas.
     *
     * A Ctrl-click with nothing of the ring selected starts at the top, which is what makes the first
     * Ctrl-click on untouched artwork land on the layer rather than skipping past it.
     */
    fun objectPick(pos: Offset, viewport: CanvasViewport, ctrl: Boolean): HierarchyPick? {
        val ring = hierarchyRing(pos, viewport)
        if (ring.isEmpty()) return null
        if (!ctrl) {
            val next = RigCanvasSupport.nextLayer(ring.mapNotNull { it.layerId }, state.selectedLayerId) ?: return null
            return ring.firstOrNull { it.layerId == next }
        }
        val current = ring.indexOfFirst { it.deformerId != null && it.deformerId == state.selectedDeformerId }
            .takeIf { it >= 0 }
            ?: ring.indexOfFirst { it.layerId != null && it.layerId == state.selectedLayerId }
        return if (current < 0) ring.first() else ring[(current + 1) % ring.size]
    }

    /**
     * Selects [pick]; [add] is true for Shift (extend), false for Alt (remove), null to replace.
     *
     * Picking a deformer drops the layer set: the two are different kinds of target, and holding both
     * would leave the canvas framing one while the hierarchy panel listed the other.
     */
    private fun applyObjectPick(pick: HierarchyPick, add: Boolean?) {
        val layer = pick.layerId
        if (layer == null) {
            objects = emptySet()
            if (state.selectedDeformerId != pick.deformerId) viewModel.selectDeformer(pick.deformerId)
            return
        }
        objects = when (add) {
            true -> objects + layer
            false -> objects - layer
            null -> if (layer in objects) objects else setOf(layer)
        }
        if (add != false && state.selectedLayerId != layer) viewModel.selectLayer(layer)
    }

    /**
     * Drops the box a transform drag built.
     *
     * The drag state outlives [release] on purpose. Committing is asynchronous, so clearing it on mouse-up
     * made the box snap to the axis-aligned hull of the already-rotated geometry while the edit was still
     * in flight — the box moved before the artwork it frames had anything to do with the commit. It now
     * holds the gesture's own result and gives way only once the edit lands, or once it is clear none will.
     */
    private fun endTransformBox() {
        currentDragBounds = null
    }

    /**
     * What a transform gesture edits: the mesh or deformer the point tools are working on, always
     * exactly one. Object mode never gets here — it has no box to grab and no body to drag.
     */
    private fun transformTargets(source: PuppetModel): List<CanvasTarget> = listOfNotNull(target(source))

    /**
     * Freezes the pose a transform gesture is about to edit. The handle grab and the body move both
     * start here and differ only in the handle they latch.
     */
    private fun beginTransformDrag(source: PuppetModel, targets: List<CanvasTarget>, handle: BoundingHandle, frame: TransformFrame?, viewport: CanvasViewport) {
        val bounds = frame?.bounds
        val pivot = frame?.pivot ?: Offset.Zero
        activeHandle = handle
        original = source
        objectTargets = targets
        targetAtPress = targets.firstOrNull()
        initialBounds = bounds
        // Every point of every target, indexed by vertex index; [dragIndices] picks the ones that move.
        initialScreenPoints = targets.map { screen(it.geometry.points, it, viewport) }
        dragIndices = targets.map { gestureIndices(it) }
        currentDragBounds = bounds
        frameAngleAtPress = frame?.angleDeg ?: frameAngle
        framePivotAtPress = pivot
        boxDrag = true
        dragging = true
    }

    fun press(pos: Offset, viewport: CanvasViewport, shift: Boolean, alt: Boolean, ctrl: Boolean = false): Boolean {
        if (adjustingBrush) endBrushAdjust(cancel = false)
        if (!editable) return true
        if (space) return false
        error = null; head = state.historySnapshot?.headNodeId; start = pos; previous = pos; dragStartPos = pos
        moved = false; additive = shift; subtractive = alt; pressedObject = null

        // 1. Interactive Creation Tools
        if (tool == CanvasTool.CREATE_WARP) {
            isCreatingWarp = true
            creationStart = pos
            creationCurrent = pos
            dragging = true
            return true
        }
        if (tool == CanvasTool.CREATE_ROTATION) {
            isCreatingRotation = true
            creationStart = pos
            creationCurrent = pos
            dragging = true
            return true
        }
        if (tool == CanvasTool.GLUE) {
            val hit = pickLayer(pos, viewport)
            if (glueFirstMesh == null) {
                glueFirstMesh = hit
            } else if (hit != null && hit != glueFirstMesh) {
                createGlue(glueFirstMesh!!, hit)
                glueFirstMesh = null
            } else {
                glueFirstMesh = null
            }
            return true
        }
        if (tool == CanvasTool.CREATE_DEFORM_PATH) {
            val t = target()
            if (t != null && t.kind == "mesh") {
                val pathTarget = t
                if (drawingPath) { draft = draft + local(pos, t, viewport, draft.lastOrNull() ?: (0.5f to 0.5f)); return true }
                val hit = paths().flatMap { path ->
                    screen(DeformPathTools.positions(path, pathTarget.geometry.points).flatMap { listOf(it.first, it.second) }.toFloatArray(), pathTarget, viewport).mapIndexed { i, p -> Triple(path, i, (p - pos).getDistance()) }
                }.filter { it.third < 10f }.minByOrNull { it.third }
                if (hit != null) { activePath = hit.first.id; pathPoint = hit.second; targetAtPress = t; original = model; dragging = true; return true }
                val curveHit = paths().mapNotNull { path ->
                    val points = DeformPathTools.positions(path, pathTarget.geometry.points)
                    val curve = DeformPathTools.curve(points, path.points.map { it.corner }, path.closed)
                    val scr = screen(curve.flatMap { listOf(it.first, it.second) }.toFloatArray(), pathTarget, viewport)
                    val dist = scr.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(pos, a, b) } ?: Float.MAX_VALUE
                    if (dist < 9f) path to dist else null
                }.minByOrNull { it.second }?.first
                if (curveHit != null) {
                    activePath = curveHit.id; pathPoint = -1
                    if (ctrl) {
                        val points = DeformPathTools.positions(curveHit, pathTarget.geometry.points)
                        val screens = screen(points.flatMap { listOf(it.first, it.second) }.toFloatArray(), pathTarget, viewport)
                        val segment = (0 until if (curveHit.closed) points.size else points.size - 1).minByOrNull { i -> distanceToSegment(pos, screens[i], screens[(i + 1) % points.size]) } ?: 0
                        val point = local(pos, pathTarget, viewport, points[segment])
                        val bound = DeformPathTools.bind(pathTarget.geometry.points, pathTarget.indices, point.first, point.second)
                        changePath { it.copy(points = it.points.take(segment + 1) + bound + it.points.drop(segment + 1)) }; pathPoint = segment + 1
                    }
                    return true
                }
                activePath = null; pathPoint = -1; drawingPath = true; draftPathId = null; draft = listOf(local(pos, t, viewport)); return true
            }
            return true
        }

        // 2. Level 2 Bezier Deformer in DEFORM mode
        if (hierarchyMode == EditHierarchyMode.DEFORM && editLevel == 2) {
            val t = target()
            if (t != null && t.kind == "warp") {
                if (hoveredBezierHandle != null) {
                    activeBezierHandle = hoveredBezierHandle
                    targetAtPress = t
                    original = model
                    dragging = true
                    return true
                }
                if (hoveredBezierAnchor != null) {
                    activeBezierAnchor = hoveredBezierAnchor
                    targetAtPress = t
                    original = model
                    dragging = true
                    return true
                }
            }
        }

        // 3. Brush Select
        if (tool == CanvasTool.BRUSH_SELECT) {
            brushSelecting = true
            dragging = true
            val t = target()
            if (t != null) {
                val points = screen(t.geometry.points, t, viewport)
                val r = (radius * viewport.scale).toFloat()
                val hits = points.indices.filter { (points[it] - pos).getDistance() <= r }.toSet()
                vertices = if (alt) vertices - hits else vertices + hits
            }
            return true
        }

        // 4. Lasso Select. Box selection is not a tool of its own: a drag on canvas that finds nothing
        //    to pick *is* a box marquee, so SELECT starts one further down instead.
        if (tool == CanvasTool.LASSO_SELECT) {
            selectionStyle = SelectionStyle.LASSO
            marquee = listOf(pos, pos)
            dragging = true
            return true
        }

        // 5. Object mode picks and nothing else. The transform box and its handles belong to the point
        //    tools, so a press here selects — Ctrl walks the hierarchy — or starts a marquee. It never
        //    begins a transform drag, which is what keeps the mode read-only.
        if (hierarchyMode == EditHierarchyMode.OBJECT && tool == CanvasTool.SELECT) {
            val pick = objectPick(pos, viewport, ctrl)
            pressedObject = pick?.layerId
            if (pick != null) {
                applyObjectPick(pick, when { alt -> false; shift -> true; else -> null })
            } else if (!shift && !alt) {
                // Empty canvas clears the lot. Both calls are needed: each one only drops the other
                // half when it is given a non-null id, so neither alone clears a deformer selection.
                objects = emptySet()
                viewModel.selectLayer(null)
                viewModel.selectDeformer(null)
            }
            marquee = listOf(pos, pos)
            dragging = true
            return true
        }

        // 6. Point Transform handles (SELECT tool in DEFORM/STRUCTURE mode)
        if (tool == CanvasTool.SELECT) {
            val frame = transformFrame(viewport)
            val handle = frame?.let { transformRingAt(pos, it) } ?: BoundingHandle.NONE
            if (frame != null && handle != BoundingHandle.NONE) {
                val source = state.previewModel?.rig?.puppet ?: return true
                beginTransformDrag(source, transformTargets(source), handle, frame, viewport)
                return true
            }
        }

        val editTarget = target() ?: return true
        targetAtPress = editTarget; original = model; dragging = true
        val points = screen(editTarget.geometry.points, editTarget, viewport)

        val brush = tool in DEFORM_BRUSH_TOOLS
        if (brush) {
            shrinkAtPress = inflateInvert xor alt
            if (editTarget.kind == "rotation") { dragging = false; error = io.github.psd2live.i18n.tr("editor.rotationBrush") }
            return true
        }

        var picked = points.indices.filter { (points[it] - pos).getDistance() < 10f }.minByOrNull { (points[it] - pos).getDistance() }?.let { setOf(it) }.orEmpty()
        if (hierarchyMode == EditHierarchyMode.STRUCTURE && editTarget.kind == "mesh" && elementMode > 0) {
            picked = if (elementMode == 1) MeshTopology.uniqueEdges(editTarget.indices).minByOrNull { edge -> distanceToSegment(pos, points[edge.endpointLow], points[edge.endpointHigh]) }?.takeIf { distanceToSegment(pos, points[it.endpointLow], points[it.endpointHigh]) < 8f }?.let { setOf(it.endpointLow, it.endpointHigh) }.orEmpty()
            else editTarget.indices.toList().chunked(3).firstOrNull { tri -> insidePolygon(pos, tri.map { points[it] }) }?.toSet().orEmpty()
        }

        if (tool == CanvasTool.SELECT) {
            val boxFrame = transformFrame(viewport)
            if (boxFrame != null &&
                (boxFrame.bounds.contains(pos.intoTransformFrame(boxFrame.pivot, boxFrame.angleDeg)) || (picked.isNotEmpty() && picked.all { it in vertices })) &&
                !shift && !alt
            ) {
                val source = state.previewModel?.rig?.puppet ?: return true
                beginTransformDrag(source, transformTargets(source), BoundingHandle.BODY, boxFrame, viewport)
                return true
            }
        }

        if (picked.isNotEmpty()) {
            vertices = when {
                alt -> vertices - picked
                shift -> vertices + picked
                picked.all { it in vertices } -> vertices
                else -> picked
            }
        } else {
            marquee = listOf(pos, pos)
            if (!shift && !alt) vertices = emptySet()
        }
        if (editTarget.kind == "rotation") vertices = if (picked == setOf(1)) setOf(1) else setOf(0, 1)
        if (alt && picked.isNotEmpty()) dragging = false
        return true
    }

    fun move(pos: Offset, viewport: CanvasViewport, shift: Boolean, alt: Boolean = false, ctrl: Boolean = false) {
        updateHover(pos, viewport, ctrl)
        shrinks = if (dragging && tool == CanvasTool.INFLATE) shrinkAtPress else inflateInvert xor alt
        if (!dragging || busy) return
        moved = moved || (pos - start).getDistance() > 2f
        if (!moved) return

        if (isCreatingWarp || isCreatingRotation) {
            creationCurrent = pos
            return
        }

        if (activeBezierHandle != null) {
            val (br, bc, dir) = activeBezierHandle!!
            val t = targetAtPress ?: return; val source = original ?: return
            val (lx, ly) = local(pos, t, viewport)
            bezierState?.moveHandle(br, bc, dir, lx, ly, smooth = !alt)
            val warp = source.deformers.filterIsInstance<Deformer.Warp>().firstOrNull { it.id.raw == t.id } ?: return
            val evaluated = bezierState?.evaluateLattice(warp.rows, warp.columns) ?: return
            val cmd = geometryCommand(t, evaluated)
            preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
            return
        }

        if (activeBezierAnchor != null) {
            val (br, bc) = activeBezierAnchor!!
            val t = targetAtPress ?: return; val source = original ?: return
            val (lx, ly) = local(pos, t, viewport)
            val (prevLx, prevLy) = local(previous, t, viewport)
            bezierState?.moveAnchor(br, bc, lx - prevLx, ly - prevLy)
            val warp = source.deformers.filterIsInstance<Deformer.Warp>().firstOrNull { it.id.raw == t.id } ?: return
            val evaluated = bezierState?.evaluateLattice(warp.rows, warp.columns) ?: return
            val cmd = geometryCommand(t, evaluated)
            preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
            return
        }

        if (tool == CanvasTool.BRUSH_SELECT) {
            val t = target() ?: return
            val points = screen(t.geometry.points, t, viewport)
            val r = (radius * viewport.scale).toFloat()
            val hits = points.indices.filter { (points[it] - pos).getDistance() <= r || distanceToSegment(points[it], previous, pos) <= r }.toSet()
            vertices = if (alt) vertices - hits else vertices + hits
            previous = pos
            return
        }

        if (marquee.isNotEmpty()) {
            marquee = if (selectionStyle == SelectionStyle.LASSO) marquee + pos else listOf(start, pos)
            return
        }

        val t = targetAtPress ?: return; val source = original ?: return

        try {
            if (boxDrag) {
                val b0 = initialBounds ?: return
                val targets = objectTargets.ifEmpty { listOfNotNull(t) }
                if (targets.isEmpty() || initialScreenPoints.size != targets.size) return
                val result = TransformDrag(activeHandle, b0, framePivotAtPress, frameAngleAtPress, start)
                    .apply(pos, axis, shift, alt)
                currentDragBounds = result.bounds
                frameAngle = result.frameAngle
                pendingObjects = targets.mapIndexed { itemIndex, item ->
                    val indices = dragIndices.getOrElse(itemIndex) { (0 until item.count).toSet() }
                    val world = item.mapping.localToWorld(item.geometry.points)
                    val pressPoints = initialScreenPoints[itemIndex]
                    for (i in indices) {
                        if (i !in pressPoints.indices) continue
                        val dest = result.destination(pressPoints[i])
                        world[i * 2] = ((dest.x - viewport.offsetX) / viewport.scale).toFloat()
                        world[i * 2 + 1] = -((dest.y - viewport.offsetY) / viewport.scale).toFloat()
                    }
                    geometryCommand(item, item.mapping.worldToLocalLinearized(world, item.geometry.points, item.geometry.points, indices))
                }
                preview = pendingObjects.fold(source) { m, command -> RigAuthoringJournal.apply(m, command) }
                return
            }

            if (tool == CanvasTool.CREATE_DEFORM_PATH || (paths().isNotEmpty() && activePath != null && pathPoint >= 0)) {
                val path = source.deformPaths.firstOrNull { it.id == activePath }
                if (path != null && pathPoint >= 0) {
                    val points = DeformPathTools.positions(path, t.geometry.points).toMutableList()
                    if (pathPoint in points.indices) {
                        points[pathPoint] = local(pos, t, viewport, points[pathPoint])
                        val cmd = geometryCommand(t, DeformPathTools.deform(t.geometry.points, source.deformPaths, path.id, points))
                        preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
                        return
                    }
                }
            }

            val brush = tool in DEFORM_BRUSH_TOOLS
            val inflate = tool == CanvasTool.INFLATE
            val screenRadius = (radius * viewport.scale).toFloat()
            val base = if (brush && preview != null) RigGeometryTools.geometry(preview!!, t.kind, t.id, pose).points else t.geometry.points
            val screen = screen(base, t, viewport); val world = t.mapping.localToWorld(base)
            val affected = if (brush) screen.indices.filter {
                (vertices.isEmpty() || it in vertices) &&
                isPointInBrush(screen[it], previous, pos, screenRadius, brushShape, brushAngle, brushAspect, hardness)
            }.toSet() else vertices.filter { it in screen.indices }.toSet()
            val delta = if (brush) pos - previous else pos - start
            val adjacency = if (tool == CanvasTool.SMOOTH || (tool == CanvasTool.BRUSH && shift)) neighbors(t) else null
            for (i in affected) {
                val p = screen[i]
                val weight = if (brush) computeBrushWeight(p, previous, pos, screenRadius, hardness, brushShape, brushAngle, brushAspect) * strength else 1f
                val destination = when {
                    inflate -> p + inflateOffset(p, previous, pos, delta.getDistance().coerceAtMost(screenRadius) * weight * INFLATE_GAIN * (if (shrinkAtPress) -1f else 1f))
                    adjacency != null -> { val ns = adjacency[i]; if (ns.isEmpty()) p else p + (Offset(ns.map { screen[it].x }.average().toFloat(), ns.map { screen[it].y }.average().toFloat()) - p) * weight }
                    else -> p + delta * weight
                }
                world[i * 2] = ((destination.x - viewport.offsetX) / viewport.scale).toFloat()
                world[i * 2 + 1] = -((destination.y - viewport.offsetY) / viewport.scale).toFloat()
            }
            if (affected.isEmpty()) { previous = pos; return }
            val cmd = geometryCommand(t, t.mapping.worldToLocal(world, base, affected))
            preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
        } catch (e: Exception) { error = e.message }
    }

    fun release() {
        if (!dragging) return
        dragging = false; axis = null; activeHandle = BoundingHandle.NONE
        initialBounds = null
        initialScreenPoints = emptyList()
        boxDrag = false; dragIndices = emptyList()

        if (isCreatingWarp) {
            isCreatingWarp = false
            val s = creationStart; val e = creationCurrent
            creationStart = null; creationCurrent = null
            if (s != null && e != null && abs(s.x - e.x) > 10f && abs(s.y - e.y) > 10f && viewport != null) {
                createWarpFromBounds(s, e, viewport!!)
            }
            return
        }

        if (isCreatingRotation) {
            isCreatingRotation = false
            val s = creationStart; val e = creationCurrent
            creationStart = null; creationCurrent = null
            if (s != null && e != null && (s - e).getDistance() > 10f && viewport != null) {
                createRotationFromPoints(s, e, viewport!!)
            }
            return
        }

        if (activeBezierAnchor != null || activeBezierHandle != null) {
            activeBezierAnchor = null
            activeBezierHandle = null
            val cmd = pending
            if (moved && cmd != null) {
                commit(cmd)
            } else {
                preview = null; head = null
            }
            pending = null; targetAtPress = null; original = null
            return
        }

        if (brushSelecting) {
            brushSelecting = false
            return
        }

        if (marquee.isNotEmpty()) { endTransformBox(); return }

        val cmd = pending
        if (moved && pendingObjects.isNotEmpty()) { if (!commitBatch(pendingObjects)) preview = null }
        else if (moved && cmd != null) { if (!commitBatch(listOf(cmd))) preview = null }
        else { preview = null; head = null; endTransformBox() }
        pendingObjects = emptyList(); objectTargets = emptyList()
        pending = null; targetAtPress = null; original = null
    }

    /**
     * Starts the Photoshop-style Alt + right-drag brush gesture. Returns false when the active tool has no brush
     * parameters, so the caller leaves the event unhandled. Both values are recomputed from the press anchor on
     * every move, so dragging past a clamp and back re-enters smoothly instead of sticking.
     */
    fun beginBrushAdjust(pos: Offset, shift: Boolean = false): Boolean {
        if (adjustingBrush || dragging) return false
        if (tool != CanvasTool.BRUSH && tool != CanvasTool.SMOOTH && tool != CanvasTool.INFLATE) return false
        adjustingBrush = true
        brushAxis = if (shift && brushShape != BrushShape.CIRCLE) BrushAdjustAxis.ANGLE else null
        brushAnchor = pos
        brushRadiusAtStart = radius
        brushHardnessAtStart = hardness
        brushAngleAtStart = brushAngle
        // Freeze the outline at the press point, and pin the ring colour with it: the viewport stops calling
        // move() for the duration, so nothing else refreshes either. The size change is then judged against
        // fixed artwork instead of an outline sliding along under the cursor.
        cursor = pos
        shrinks = inflateInvert
        return true
    }

    /**
     * Applies the drag to whichever parameter the gesture latched onto. The axis is decided once, from the
     * first movement past [BRUSH_AXIS_LOCK_PX], so radius and hardness are never adjusted together and a
     * mostly-horizontal drag cannot nudge hardness by accident.
     */
    fun updateBrushAdjust(pos: Offset) {
        if (!adjustingBrush) return
        val dx = pos.x - brushAnchor.x
        val dy = pos.y - brushAnchor.y
        if (brushAxis == null) {
            if (max(abs(dx), abs(dy)) < BRUSH_AXIS_LOCK_PX) return
            brushAxis = if (abs(dx) >= abs(dy)) BrushAdjustAxis.RADIUS else BrushAdjustAxis.HARDNESS
        }
        when (brushAxis) {
            BrushAdjustAxis.RADIUS -> radius = (brushRadiusAtStart * 1.2f.pow(dx / BRUSH_RADIUS_STEP_PX)).coerceIn(4f, 500f)
            BrushAdjustAxis.HARDNESS -> hardness = (brushHardnessAtStart + dy / BRUSH_HARDNESS_SPAN_PX * 0.95f).coerceIn(0f, 0.95f)
            BrushAdjustAxis.ANGLE -> brushAngle = (brushAngleAtStart + dx * 0.75f).mod(360f)
            null -> Unit
        }
    }

    /** Ends the gesture; [cancel] restores the values captured at press (Esc, tool switch, focus loss). */
    fun endBrushAdjust(cancel: Boolean) {
        if (!adjustingBrush) return
        adjustingBrush = false
        brushAxis = null
        if (cancel) {
            radius = brushRadiusAtStart
            hardness = brushHardnessAtStart
            brushAngle = brushAngleAtStart
        }
    }

    fun finishSelection(viewport: CanvasViewport) {
        if (marquee.isEmpty()) return
        // Object mode picks on press, so a marquee that never moved — a click, jitter included — is
        // already resolved and must not be re-derived here. Re-deriving it from a zero-area polygon
        // finds nothing and would blank the very selection the press just made, deformers especially,
        // which the marquee has no way to express at all.
        if (hierarchyMode == EditHierarchyMode.OBJECT && !moved) {
            pressedObject = null; marquee = emptyList(); original = null; head = null; return
        }
        val polygon = if (selectionStyle == SelectionStyle.LASSO) marquee else listOf(marquee.first(), Offset(marquee.last().x, marquee.first().y), marquee.last(), Offset(marquee.first().x, marquee.last().y))
        if (hierarchyMode == EditHierarchyMode.OBJECT) {
            val found = state.effectiveVisibleLayerIds.filter { id -> target(model, id, null)?.let { item -> screen(item.geometry.points, item, viewport).any { insidePolygon(it, polygon) } } == true }.toSet()
            objects = when {
                subtractive -> objects - found
                additive -> objects + found
                found.isEmpty() && pressedObject != null -> objects
                else -> found
            }
            viewModel.selectLayer(objects.lastOrNull()); pressedObject = null; marquee = emptyList(); original = null; head = null; return
        }
        val t = targetAtPress ?: target() ?: return
        val found = screen(t.geometry.points, t, viewport).mapIndexedNotNull { i, p -> if (insidePolygon(p, polygon)) i else null }.toSet()
        vertices = when { subtractive -> vertices - found; additive -> vertices + found; else -> found }
        pressedObject = null; marquee = emptyList(); targetAtPress = null; original = null; head = null
    }

    private fun neighbors(t: CanvasTarget): List<IntArray> = if (t.kind == "mesh") MeshTopology.buildVertexAdjacency(t.count, t.indices) else {
        val columns = t.geometry.columns!! + 1
        (0 until t.count).map { i -> listOfNotNull(if (i % columns > 0) i - 1 else null, if (i % columns < columns - 1) i + 1 else null, if (i >= columns) i - columns else null, if (i + columns < t.count) i + columns else null).toIntArray() }
    }
}

internal fun brushWeight(distance: Float, radius: Float, hardness: Float): Float {
    val x = ((distance / radius.coerceAtLeast(1f) - hardness) / (1f - hardness.coerceAtMost(0.95f))).coerceIn(0f, 1f)
    return 1 - x * x * (3 - 2 * x)
}

/** Radial gain for the inflate brush: displacement = min(cursor travel, radius) * weight * gain. */
private const val INFLATE_GAIN = 0.5f

/** Travel in raw px that equals one `]` press (one 1.2x step) in the Alt + right-drag radius gesture. */
private const val BRUSH_RADIUS_STEP_PX = 12f

/** Vertical travel in raw px that spans the whole 0f..0.95f hardness range in the same gesture. */
private const val BRUSH_HARDNESS_SPAN_PX = 200f

/** Drag distance before the gesture commits to radius or hardness; below it nothing is adjusted. */
private const val BRUSH_AXIS_LOCK_PX = 4f

/**
 * Radially pushes [point] away from the closest point on the stroke segment [from]→[to], by [amount] pixels.
 * Degenerate directions return [Offset.Zero] rather than a NaN — a NaN here would be committed to history.
 * The epsilon (instead of an exact zero test) also stops the sign from strobing as the cursor sweeps a vertex.
 */
internal fun inflateOffset(point: Offset, from: Offset, to: Offset, amount: Float): Offset {
    val segment = to - from
    val length2 = segment.x * segment.x + segment.y * segment.y
    val direction = if (length2 < 1e-8f) point - from else {
        val t = (((point - from).x * segment.x + (point - from).y * segment.y) / length2).coerceIn(0f, 1f)
        point - (from + segment * t)
    }
    val distance = direction.getDistance()
    return if (distance < 1e-3f) Offset.Zero else direction / distance * amount
}

internal fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val d = b - a; val length = d.x * d.x + d.y * d.y
    val t = if (length < 1e-8f) 0f else (((p - a).x * d.x + (p - a).y * d.y) / length).coerceIn(0f, 1f)
    return (p - (a + d * t)).getDistance()
}

internal fun insidePolygon(p: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    if (polygon.size < 3) return false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]; val b = polygon[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}
