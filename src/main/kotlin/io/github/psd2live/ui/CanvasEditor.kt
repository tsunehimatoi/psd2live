package io.github.psd2live.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.*
import kotlinx.serialization.json.*
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshRefinementOps
import org.umamo.edit.withDrawablesDeleted
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.SourceLayer
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.agent.WorkspaceSourceArt
import org.umamo.render.eval.*
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.math.*

/** Canvas tools, each pointing at the shortcut action that activates it. */
/** Edit hierarchy / layer mode chosen in the top-right toolbar. */
enum class EditHierarchyMode {
    /** 选择: pick, move, scale and rotate whole objects. */
    SELECT,
    /** 变形: edit parameter deformation without touching topology (Level 1/2/3). */
    DEFORM,
    /** 编辑: change structure - topology, splitting and deformer creation. */
    EDIT,
    /** 绘画: raster repainting of one layer slice. */
    PAINT,
}

/** Persist the mode's intent so preview and history replay use identical UV semantics. */
internal fun canvasGeometryCommand(
    mode: EditHierarchyMode,
    kind: String,
    id: String,
    coordinate: Map<String, Float>,
    points: FloatArray,
): JsonObject = buildJsonObject {
    val editMesh = kind == "mesh" && mode == EditHierarchyMode.EDIT
    put("op", "canvas_geometry"); put("kind", kind); put("id", id)
    put("key", JsonObject((if (editMesh) emptyMap() else coordinate).mapValues { JsonPrimitive(it.value) }))
    if (editMesh) put("pose", JsonObject(coordinate.mapValues { JsonPrimitive(it.value) }))
    put("preserve_image", editMesh)
    put("points", JsonArray(points.map(::JsonPrimitive)))
}

/**
 * A mode the user asked for while nothing was selected, and the tool that request came with.
 *
 * The canvas stays in object mode until a part is picked, so the request is answered by the same gesture
 * that makes the pick: the click satisfying the prompt is the click the mode needed anyway, while entering
 * the mode early would leave every gesture in it without a target to act on.
 */
internal data class DeferredMode(val mode: EditHierarchyMode, val tool: CanvasTool? = null) {
    /**
     * The prompt this request shows, which names the kind of part the mode actually needs: painting
     * replaces one layer's pixels, so a deformer selection would leave it with nothing to paint.
     * Creation tools ask for a mesh (or deformer) rather than a hierarchy mode.
     */
    val promptKey: String get() = when {
        tool in CREATION_TOOLS -> "editor.creationSelectFirst"
        mode == EditHierarchyMode.PAINT -> "editor.mode.layerFirst"
        else -> "editor.mode.partFirst"
    }
}

/** The localized name of [mode], as the mode chips and the deferred-mode prompt spell it. */
internal fun modeLabel(mode: EditHierarchyMode): String = tr(
    when (mode) {
        EditHierarchyMode.SELECT -> "editor.mode.select"
        EditHierarchyMode.DEFORM -> "editor.mode.deform"
        EditHierarchyMode.EDIT -> "editor.mode.edit"
        EditHierarchyMode.PAINT -> "editor.mode.paint"
    }
)

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
    /** Region subdivide: a radius brush over the mesh's edges. */
    SUBDIVIDE(ShortcutAction.TOOL_SUBDIVIDE),
    /** The knife: click anchors along a cut, connect them, commit with Enter. */
    KNIFE(ShortcutAction.TOOL_KNIFE),
    // Painting mode tools (L1)
    PAINT_BRUSH(ShortcutAction.TOOL_PAINT_BRUSH),
    PAINT_PENCIL(ShortcutAction.TOOL_PAINT_PENCIL),
    PAINT_ERASER(ShortcutAction.TOOL_PAINT_ERASER),
    PAINT_BUCKET(ShortcutAction.TOOL_PAINT_BUCKET),
    PAINT_EYEDROPPER(ShortcutAction.TOOL_PAINT_EYEDROPPER),
    /** Line, rectangle and ellipse: one tool with a [paintShape], not three tools. */
    PAINT_SHAPE(ShortcutAction.TOOL_PAINT_LINE),
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

/** Where a new Warp attaches in the deformer tree (Cubism "Add to"). */
internal enum class WarpAddTo {
    /** New Warp becomes parent of the selected meshes (default). */
    PARENT_OF_SELECTED,
    /** New Warp is created as child of the selected deformer; meshes are not remounted. */
    CHILD_OF_SELECTED_DEFORMER,
    /** New Warp's parent is [CanvasEditor.warpSpecifyParentId]; selected meshes remount under it. */
    SPECIFY_PARENT,
}

/** How Warp bounds are computed for "create from selection". */
internal enum class WarpSizeStrategy {
    /** Current-pose AABB of the selection, expanded 5%. */
    SELECTION_BOUNDS,
    /** Union of base + all keyform AABBs (Cubism "consider keyforms"). */
    KEYFORM_ENVELOPE,
    /** Same size as selection AABB, centered on the selection. */
    CENTER_ALIGN,
}

/** Blender-style add: new node above (parent) or below (child) the tree anchor. */
internal enum class CreateRelation {
    /** New deformer becomes parent of the anchor. */
    AS_PARENT,
    /** New deformer becomes child of the anchor (anchor must be a deformer). */
    AS_CHILD,
}

internal enum class CreatePlacementKind { WARP, ROTATION, PATH }

/** Which handle is being dragged while placing. */
internal enum class PlacementHandle {
    NONE, BODY,
    N, S, E, W, NE, NW, SE, SW,
    PIVOT, TIP,
}

/**
 * An in-progress create: target and relation are fixed from the tree/toolbar; the artist places and
 * sizes a ghost on the canvas, then confirms. Nothing is written to the model until [CanvasEditor.confirmPlacement].
 *
 * Bounds / origin / tip are stored in the new deformer parent-local space — the same units
 * non-UI create-from-selection / [createWarpFromBounds] write. Screen is display-only via
 * [DrawableSpaceMapping.localToWorld]; commit copies these fields as-is (no camera-world round-trip).
 */
internal data class CreatePlacement(
    val kind: CreatePlacementKind,
    val relation: CreateRelation,
    /** "mesh" or "deformer" */
    val anchorKind: String,
    val anchorId: String,
    val anchorLabel: String,
    /** Drawable ids remounted under a new Warp/Rotation when applicable. */
    val meshIds: List<String>,
    /**
     * Parent deformer whose local frame owns [localX]-[tipY]; null = model root.
     * Matches the parent / mesh parent that [CanvasEdits] will assign.
     */
    val spaceParentId: String?,
    var name: String,
    var partId: String?,
    /** Parent-local AABB for Warp (mesh positions / lattice units). */
    var localX: Float,
    var localY: Float,
    var localW: Float,
    var localH: Float,
    /** Parent-local pivot and tip for Rotation. */
    var originX: Float = 0f,
    var originY: Float = 0f,
    var tipX: Float = 0f,
    var tipY: Float = 0f,
    /** Conversion division (Cubism) — lattice rows x cols. */
    var rows: Int = 5,
    var cols: Int = 5,
    /** Bezier edit division — Level-2 handle density. */
    var bezierRows: Int = 2,
    var bezierCols: Int = 2,
)

internal val PAINT_TOOLS = setOf(
    CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER,
    CanvasTool.PAINT_BUCKET, CanvasTool.PAINT_EYEDROPPER,
    CanvasTool.PAINT_SHAPE,
)

/** The paint tools that stamp a tip: they are the ones with a size, a hardness and an opacity. */
internal val PAINT_BRUSH_TOOLS = setOf(
    CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER,
)

/**
 * Tools that edit points rather than whole objects.
 */
internal val VERTEX_TOOLS = setOf(
    CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT,
    CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE,
)

/**
 * Every tool the left (edit) toolbar can show, in the order it shows them.
 *
 * Creation tools are started from the hierarchy tree context menu or shortcuts, not this palette.
 * The per-mode palettes below are subsets of this list.
 */
internal val TOOLBAR_TOOL_ORDER = listOf(
    CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT,
    CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE,
    CanvasTool.SUBDIVIDE, CanvasTool.KNIFE,
    CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER,
    CanvasTool.PAINT_BUCKET, CanvasTool.PAINT_EYEDROPPER,
    CanvasTool.PAINT_SHAPE,
)

/** A divider is drawn after these, when there are visible tools on both sides of them. */
internal val TOOLBAR_DIVIDERS = listOf(CanvasTool.BRUSH_SELECT, CanvasTool.INFLATE)

/**
 * The left toolbar's palette for [mode]. Creation tools are not listed here — use the tree
 * context menu or shortcuts (C / R / P).
 *
 * Object mode is the one without the vertex tools. Deform mode edits points without changing topology.
 * Edit mode handles mesh topology (subdivide / knife). Paint mode replaces layer pixels.
 */
internal fun toolbarGroups(mode: EditHierarchyMode): List<List<CanvasTool>> = when (mode) {
    EditHierarchyMode.SELECT -> listOf(
        listOf(CanvasTool.SELECT, CanvasTool.LASSO_SELECT),
    )
    EditHierarchyMode.DEFORM -> listOf(
        listOf(CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT),
        listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE),
    )
    EditHierarchyMode.EDIT -> listOf(
        listOf(CanvasTool.SELECT, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT),
        listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE),
        listOf(CanvasTool.SUBDIVIDE, CanvasTool.KNIFE),
    )
    EditHierarchyMode.PAINT -> listOf(
        listOf(CanvasTool.SELECT),
        listOf(CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER),
        listOf(CanvasTool.PAINT_BUCKET, CanvasTool.PAINT_EYEDROPPER),
        listOf(CanvasTool.PAINT_SHAPE),
    )
}

/**
 * The target kinds a point selection may be framed in.
 */
internal val POINT_BOX_KINDS = setOf("mesh", "warp")

/** Which brush parameter the Alt + right-drag gesture latched onto; null until the drag picks a direction. */
/**
 * Which parameter the Alt + right-drag gesture latched onto. The deform brushes retune radius,
 * hardness and angle; a paint tip has no angle, so its third axis is the opacity instead.
 */
internal enum class BrushAdjustAxis { RADIUS, HARDNESS, ANGLE, OPACITY }

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
 * One node an object-mode click can pick: a drawable, or one of the deformers above it. Exactly one of
 * the two ids is set.
 */
internal data class HierarchyPick(
    val layerId: String? = null,
    val deformerId: String? = null,
)

/**
 * One layer's slice of the packed atlas, with the source bounds it was cropped from.
 *
 * A mesh's texture coordinates address the slice, not the canvas, so repacking has to translate them
 * through canvas pixels: `uv -> canvas -> uv`. That round trip is what keeps a drawable on the same
 * pixels after its layer was re-cropped, which is why the atlas convention lives in one place.
 */
internal class AtlasSlice(
    val placement: io.github.psd2live.core.AtlasPlacement,
    val pageWidth: Int,
    val pageHeight: Int,
    /** The layer's source bounds in canvas pixels when this slice was packed. */
    val sourceBounds: Bounds,
) {
    private val scale get() = placement.scale.coerceAtLeast(1)

    fun canvasX(uv: Float): Float = sourceBounds.left + (uv * pageWidth - placement.x) / scale
    fun canvasY(uv: Float): Float = sourceBounds.top + (uv * pageHeight - placement.y) / scale
    fun uvX(canvasX: Float): Float = (placement.x + (canvasX - sourceBounds.left) * scale) / pageWidth
    fun uvY(canvasY: Float): Float = (placement.y + (canvasY - sourceBounds.top) * scale) / pageHeight
}

/**
 * The painted layer's box in canvas pixels, as the float box the rig math works in. The two are
 * easy to confuse: a [Bounds] holds edges, a [LayerBounds] holds a width and a height.
 */
private fun LayerBounds.toBounds(): Bounds =
	Bounds(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())

/** Re-addresses a mesh's texture coordinates from one slice of the atlas to another. */
private fun remapUvs(mesh: DrawableMesh, from: AtlasSlice, to: AtlasSlice): FloatArray {
    val uvs = FloatArray(mesh.uvs.size)
    for (index in mesh.uvs.indices step 2) {
        uvs[index] = to.uvX(from.canvasX(mesh.uvs[index]))
        uvs[index + 1] = to.uvY(from.canvasY(mesh.uvs[index + 1]))
    }
    return uvs
}

/** One gesture owns its pose, parent mapping and history HEAD until release. */
/** The faces a topology op created, and which drawable they belong to. See [CanvasEditor.topologyFills]. */
internal data class TopologyFill(val drawableId: String, val triangles: Set<Int>)

/** Tone for [CanvasEditor.statusBarMessage] in the app status bar. */
internal enum class CanvasStatusTone { NORMAL, WARNING, ERROR }

/** Text and tone the app status bar shows while an Edit tab is active. */
internal data class CanvasStatusMessage(val text: String, val tone: CanvasStatusTone)

internal class CanvasEditor(val viewModel: PSD2LiveViewModel) {
    var state: PSD2LiveState
        get() = viewModel.state.value
        set(_) {}
    var viewport: CanvasViewport? = null
    var tool by mutableStateOf(CanvasTool.SELECT)

    // Top-right Hierarchy & Level state
    var hierarchyMode by mutableStateOf(EditHierarchyMode.SELECT)
    var editLevel by mutableStateOf(2) // Level 1 (grid), Level 2 (bezier), Level 3 (macro)

    /**
     * The mode the user asked for that has no part to work on yet, waiting for the pick that gives it
     * one. Null whenever the canvas is in the mode it is showing.
     */
    var deferredMode by mutableStateOf<DeferredMode?>(null)
        private set

    /** What the status bar says while a request waits: it names the mode, so the click that put it there is not lost. */
    val deferredModePrompt: String?
        get() = deferredMode?.let { deferred ->
            if (deferred.tool in CREATION_TOOLS) tr(deferred.promptKey)
            else tr(deferred.promptKey, modeLabel(deferred.mode))
        }

    /**
     * Selection count, tool gesture shortcuts, waiting prompts and submit errors for the app status bar.
     * A waiting mode's prompt is read from the request itself rather than from [error], which any tool
     * press clears: the request stands until a pick answers it, and so has to the line asking for one.
     */
    fun statusBarMessage(
        selectedLayerId: String? = state.selectedLayerId,
        selectedDeformerId: String? = state.selectedDeformerId,
    ): CanvasStatusMessage {
        val waiting = deferredModePrompt
        error?.let { return CanvasStatusMessage(it, CanvasStatusTone.ERROR) }
        waiting?.let { return CanvasStatusMessage(it, CanvasStatusTone.WARNING) }
        if (busy) return CanvasStatusMessage(tr("editor.saving"), CanvasStatusTone.NORMAL)
        // Status bar is always composed; model may still be absent before any PSD is loaded.
        val source = preview ?: state.previewModel?.rig?.puppet
        val target = source?.let { target(it, selectedLayerId, selectedDeformerId) }
        val hintKey = when {
            tool == CanvasTool.KNIFE -> "editor.knifeGestureHint"
            tool == CanvasTool.SUBDIVIDE -> "editor.subdivideHint"
            tool == CanvasTool.SELECT && target?.kind == "rotation" -> "editor.rotationGestureHint"
            tool == CanvasTool.CREATE_DEFORM_PATH -> "editor.pathHint"
            tool == CanvasTool.INFLATE -> "editor.inflateHint"
            hierarchyMode == EditHierarchyMode.PAINT -> "editor.paintHint"
            hierarchyMode == EditHierarchyMode.SELECT && tool == CanvasTool.SELECT -> "editor.objectHint"
            hierarchyMode == EditHierarchyMode.EDIT && tool == CanvasTool.SELECT &&
                target?.kind == "mesh" && source != null &&
                source.deformPaths.any { it.drawableId.raw == target.id && it.editLevel == pathLevel } ->
                "editor.pathBindHint"
            // drawsTransformBox reads model via target(); only evaluate once a puppet exists.
            tool == CanvasTool.SELECT && source != null && drawsTransformBox -> "editor.transformHint"
            tool == CanvasTool.CREATE_WARP -> if (placement != null) "editor.placementDragHint" else "editor.createWarpHint"
            tool == CanvasTool.CREATE_ROTATION -> if (placement != null) "editor.placementRotationHint" else "editor.createRotationHint"
            tool == CanvasTool.GLUE -> "editor.glueHint"
            else -> "editor.hint"
        }
        val text = tr("editor.selectionCount", objects.size, vertices.size) + "   ·   " + tr(hintKey)
        return CanvasStatusMessage(text, CanvasStatusTone.NORMAL)
    }

    // Painting system state (L1)
    var paintColor by mutableStateOf(androidx.compose.ui.graphics.Color.Black)
    var paintSecondaryColor by mutableStateOf(androidx.compose.ui.graphics.Color.White)
    var paintBrushSize by mutableStateOf(16f)
    var paintPencilSize by mutableStateOf(4f)
    var paintEraserSize by mutableStateOf(24f)

    /** Edge softness of the paint and erase tips: 1 is a pen, 0 fades the whole tip to nothing. */
    var paintHardness by mutableStateOf(0.85f)

    /** The size the active paint tool draws at: pencil, eraser and shapes each keep their own. */
    var paintSize: Float
        get() = when (tool) {
            CanvasTool.PAINT_PENCIL -> paintPencilSize
            CanvasTool.PAINT_ERASER -> paintEraserSize
            else -> paintBrushSize
        }
        set(value) {
            when (tool) {
                CanvasTool.PAINT_PENCIL -> paintPencilSize = value
                CanvasTool.PAINT_ERASER -> paintEraserSize = value
                else -> paintBrushSize = value
            }
        }

    /** Whether the active tool stamps the paint tip, which is what the brush keys and HUD act on. */
    val paintBrushActive: Boolean
        get() = hierarchyMode == EditHierarchyMode.PAINT && tool in PAINT_BRUSH_TOOLS

    /** Whether the active tool has a size at all: the shapes are drawn with one, the fills are not. */
    val paintSizeActive: Boolean
        get() = hierarchyMode == EditHierarchyMode.PAINT && tool in PAINT_TOOLS &&
            tool != CanvasTool.PAINT_BUCKET && tool != CanvasTool.PAINT_EYEDROPPER

    /**
     * The tip the active paint tool draws with: the tool's own size, the shared hardness - and, for the
     * pencil, a hard edge with no blending, which is what makes it a pencil.
     *
     * The tip is the one description of the mark, so the overlay previews it from the same profile the
     * stroke is rasterized with.
     */
    fun paintTip(): LayerPaintEngine.Tip = LayerPaintEngine.Tip(
        radius = (paintSize / 2f).coerceAtLeast(0.5f),
        hardness = if (tool == CanvasTool.PAINT_PENCIL) 1f else paintHardness,
        antialias = tool != CanvasTool.PAINT_PENCIL,
    )
    var paintOpacity by mutableStateOf(1f)
    var paintTolerance by mutableStateOf(32)
    var paintShapeFilled by mutableStateOf(false)

    /** Which of the shape tool's three faces is in hand. Its keys arm both the shape and the tool. */
    var paintShape by mutableStateOf(PaintShape.LINE)
        private set

    /** Takes [shape] in hand. The tool follows, so a shape's chord is enough to start drawing with it. */
    fun selectPaintShape(shape: PaintShape) {
        paintShape = shape
        activateTool(CanvasTool.PAINT_SHAPE)
    }

    /** Steps through the shapes, the way `Alt+B` steps through the deform brush's three. */
    fun cyclePaintShape() {
        val shapes = PaintShape.entries
        selectPaintShape(shapes[(paintShape.ordinal + 1) % shapes.size])
    }

    /** Puts the background colour in hand and the foreground into the background's place. */
    fun swapPaintColors() {
        val previous = paintColor
        paintColor = paintSecondaryColor
        paintSecondaryColor = previous
    }

    var isPainting by mutableStateOf(false)
    /** True while the pointer is picking a colour rather than drawing one: the eyedropper's own
     *  gesture, or Alt held down over any paint tool. */
    var isSampling by mutableStateOf(false)

    /**
     * True while Alt is held. The pointer reports its modifiers only when it moves, so the key itself
     * latches this - it is what the sampling ring follows, and a ring that waited for the mouse to
     * twitch would be a ring that is not there when the artist looks for it.
     */
    var altHeld by mutableStateOf(false)
    var paintStrokeStart by mutableStateOf<Offset?>(null)
    var paintStrokeCurrent by mutableStateOf<Offset?>(null)

    var paintSession by mutableStateOf<PaintSession?>(null)
    /** Where the live paint gesture was last seen: the far end of the segment still to be drawn. */
    private var lastPaintPoint: Offset? = null
    var showRebuildMeshDialog by mutableStateOf(false)

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
    var warpCreateGridRows by mutableStateOf(5)
    var warpCreateGridCols by mutableStateOf(5)
    var warpCreateBezierRows by mutableStateOf(2)
    var warpCreateBezierCols by mutableStateOf(2)
    var warpAddTo by mutableStateOf(WarpAddTo.PARENT_OF_SELECTED)
    var warpSizeStrategy by mutableStateOf(WarpSizeStrategy.SELECTION_BOUNDS)
    /** Explicit part for the next Warp/Rotation; null means inherit from the primary mesh. */
    var warpCreatePartId by mutableStateOf<String?>(null)
    /** Parent deformer id when [warpAddTo] is [WarpAddTo.SPECIFY_PARENT]. */
    var warpSpecifyParentId by mutableStateOf<String?>(null)
    /** Keep the create tool armed after a successful commit (Cubism sequential create). */
    var sequentialCreate by mutableStateOf(false)
    /**
     * Mode to restore when a create session ends. Set when arming a creation tool; cleared on cancel
     * or after a successful create that leaves the session.
     */
    private var createSessionReturnMode: EditHierarchyMode? = null
    /** Active Blender-style place-then-confirm session; null when not placing. */
    var placement by mutableStateOf<CreatePlacement?>(null)
        private set
    var placementHandle by mutableStateOf(PlacementHandle.NONE)
        private set
    private var placementDragStart: Offset? = null
    private var placementDragSnapshot: CreatePlacement? = null
    var glueDistance by mutableStateOf(40f)
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
    var pathHardness by mutableStateOf(0.5f)
    var pathClosed by mutableStateOf(false)
    var activePath by mutableStateOf<String?>(null)
    var pathPoint by mutableStateOf(-1)
    /** Path-handle hover index; kept separate from [hoveredVertex] so mesh points are not lit by accident. */
    var hoveredPathPoint by mutableStateOf<Int?>(null)
    /** True only while the current gesture began on a path control point — not while a path point is merely selected. */
    private var pathDragging = false
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
    private var activeElementMode by mutableStateOf(0)
    var elementMode: Int
        get() = activeElementMode
        set(value) {
            if (activeElementMode != value) { selectedEdges = emptySet(); selectedFaces = emptySet() }
            activeElementMode = value.coerceIn(0, 2)
        }
    var selectedEdges by mutableStateOf<Set<MeshElement.Edge>>(emptySet())
    var selectedFaces by mutableStateOf<Set<Int>>(emptySet())

    /**
     * The faces the last topology op created, drawn faintly so a provisional patch reads as a patch.
     * Session-only and never persisted: "this is provisional" is a statement about the editing session,
     * not about the document. Dropped by the next commit or cancel, because after either the indices may
     * no longer mean what they meant.
     */
    var topologyFills by mutableStateOf<TopologyFill?>(null)

    /** The knife's anchors, in click order. Empty when no cut is being drawn. */
    var knifeDraft by mutableStateOf<List<MeshRefinementOps.KnifeAnchor>>(emptyList())

    /** The drawable [knifeDraft] belongs to, so switching targets drops a cut that could not apply. */
    private var knifeDrawableId: String? = null

    /** The only say the artist has over snapping: how close, in screen pixels, counts as "on" a vertex or an
     *  edge. Snapping itself is not optional - outside this radius a click always drops a new point. */
    var knifeSnapRadius by mutableStateOf(10f)

    /** Where the next click would land: the snapped vertex or edge, or the pointer itself. */
    var knifeHover by mutableStateOf<Offset?>(null)

    /** "vertex" or "edge" while [knifeHover] sits on a snap target, null while it is a free point. */
    var knifeSnapKind by mutableStateOf<String?>(null)

    fun undoDraftPoint() {
        if (busy) return
        if (tool == CanvasTool.KNIFE) knifeDraft = knifeDraft.dropLast(1)
        else if (drawingPath) draft = draft.dropLast(1)
        error = null
    }

    /** The vertices the subdivide brush is currently covering; the stroke's union, not the last radius. */
    var subdivideEdges by mutableStateOf<Set<MeshElement.Edge>>(emptySet())
    private var subdividing = false
    val objectMode get() = hierarchyMode == EditHierarchyMode.SELECT
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
    private var paintSizeAtStart = 16f
    private var paintHardnessAtStart = 0.85f
    private var paintOpacityAtStart = 1f
    /** Which colour a live pick is filling in, latched at the press so Alt can be let go mid-scrub. */
    private var samplingSecondary = false
    /** When the painted bitmap was last republished, in [System.nanoTime] units. */
    private var lastPaintBitmapAt = 0L
    private var dragging = false

    /** Active brush deformation vertex weights [0f..1f] for target points; non-null while a brush stroke is live. */
    var activeBrushWeights by mutableStateOf<FloatArray?>(null)
    /** Screen position where the active brush stroke was pressed. */
    var activeBrushCenter by mutableStateOf<Offset?>(null)
    private var brushInitialBase: FloatArray? = null
    private var brushInitialScreen: List<Offset>? = null
    private var brushAffectedIndices: Set<Int> = emptySet()

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
    val drawsTransformBox get() = hierarchyMode != EditHierarchyMode.SELECT && tool == CanvasTool.SELECT && selectionHasExtent

    /**
     * Whether the selection spans enough to be scaled or turned, which is what both the transform box and
     * the numeric scale / rotate rows need.
     *
     * A rotation deformer always does — it turns about its origin, which is an axis point rather than the
     * selection's own centre. A point selection needs two points that are not on the same spot: one point
     * has no extent of its own, so the only thing it can do is move. The box agrees by construction —
     * `frameOf` answers null for the same selection, which is what keeps the handles off a lone point.
     */
    val selectionHasExtent: Boolean
        get() {
            if (hierarchyMode == EditHierarchyMode.SELECT) return false
            val t = target() ?: return false
            if (t.kind == "rotation") return true
            if (t.kind !in POINT_BOX_KINDS) return false
            val points = t.geometry.points
            val chosen = vertices.filter { it in 0 until t.count }
            val first = chosen.firstOrNull() ?: return false
            return chosen.any { points[it * 2] != points[first * 2] || points[it * 2 + 1] != points[first * 2 + 1] }
        }

    /**
     * Whether the shared Precise Transform controls have something to act on.
     */
    val hasTransformSelection: Boolean
        get() {
            if (hierarchyMode == EditHierarchyMode.SELECT) return false
            val t = target() ?: return false
            return t.kind == "rotation" || vertices.any { it in 0 until t.count }
        }
    val editable get() = !busy && !state.canvasEditBusy && !state.isGenerating && !state.isAnalyzing && state.historySnapshot != null

    fun target(source: PuppetModel? = preview ?: state.previewModel?.rig?.puppet, layerId: String? = state.selectedLayerId, deformerId: String? = state.selectedDeformerId): CanvasTarget? {
        // Panels can be composed before a project is loaded or while it is closing.
        val resolvedSource = source ?: return null
        val rig = state.previewModel?.rig ?: return null
        val deformer = resolvedSource.deformers.firstOrNull { it.id.raw == deformerId }
        val drawable = resolvedSource.drawables.firstOrNull { rig.layerIdByDrawableId[it.id.raw] == layerId }
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
        if (cachedSource !== resolvedSource || cachedPose != state.parameterValues) {
            cachedSource = resolvedSource; cachedPose = state.parameterValues; cachedTargets.clear()
            cachedWorlds = buildDeformerWorlds(resolvedSource.deformers, { p -> state.parameterValues[p] ?: resolvedSource.parameters.firstOrNull { it.id == p }?.default ?: 0f })
        }
        val worlds = cachedWorlds
        if (parent != null && worlds[parent] == null) return null
        return cachedTargets.getOrPut("$kind:$id") { CanvasTarget(kind, id, RigGeometryTools.geometry(resolvedSource, kind, id, pose), DrawableSpaceMapping(parent?.let { worlds[it] }), indices) }
    }

    fun screen(local: FloatArray, target: CanvasTarget, viewport: CanvasViewport): List<Offset> {
        if (target.kind == "rotation" && local.size == 4) {
            val projection = rotationProjection(local[0], local[1], viewport, target.mapping)
            return listOf(projection.toScreen(Offset(local[0], local[1])), projection.toScreen(Offset(local[2], local[3])))
        }
        val world = target.mapping.localToWorld(local)
        return (world.indices step 2).map { Offset(viewport.x(world[it]).toFloat(), viewport.yFromWorld(world[it + 1]).toFloat()) }
    }

    /** Use the same parent-local endpoints for placement, drawing, hit-testing and dragging. */
    fun rotationGuideScreen(t: CanvasTarget, viewport: CanvasViewport): List<Offset> {
        require(t.kind == "rotation")
        return screen(t.geometry.points, t, viewport)
    }

    fun local(point: Offset, target: CanvasTarget, viewport: CanvasViewport, seed: Pair<Float, Float> = 0.5f to 0.5f): Pair<Float, Float> {
        if (target.kind == "rotation") {
            val base = target.geometry.points
            val result = rotationProjection(base[0], base[1], viewport, target.mapping).toLocal(point)
            return result.x to result.y
        }
        val world = floatArrayOf(((point.x - viewport.offsetX) / viewport.scale).toFloat(), -((point.y - viewport.offsetY) / viewport.scale).toFloat())
        // Reach-uncapped inverse: the damped worldToLocal clamps steps at 0.5 UV and, when seeded at
        // the cage centre, never reaches a corner — Bezier/placement tips then collapse inward.
        val seedLocal = floatArrayOf(seed.first, seed.second)
        val result = target.mapping.worldToLocalLinearized(world, seedLocal, world, setOf(0))
        return result[0] to result[1]
    }

    fun paths() = model.deformPaths.filter { it.drawableId.raw == target()?.id && it.editLevel == pathLevel }
    fun selectedPath() = paths().firstOrNull { it.id == activePath }

    /** Nearest path control point under the pointer, within [radius] screen px. */
    private fun pathControlHit(
        pos: Offset,
        t: CanvasTarget,
        viewport: CanvasViewport,
        radius: Float = 10f,
        onlyActive: Boolean = false,
    ): Pair<DeformPath, Int>? {
        val candidates = if (onlyActive) paths().filter { it.id == activePath } else paths()
        return candidates.flatMap { path ->
            screen(
                DeformPathTools.positions(path, t.geometry.points).flatMap { listOf(it.first, it.second) }.toFloatArray(),
                t,
                viewport,
            ).mapIndexed { i, p -> Triple(path, i, (p - pos).getDistance()) }
        }.filter { it.third <= radius }.minByOrNull { it.third }?.let { it.first to it.second }
    }

    /** Nearest path whose sampled curve is under the pointer. */
    private fun pathCurveHit(
        pos: Offset,
        t: CanvasTarget,
        viewport: CanvasViewport,
        radius: Float = 9f,
    ): DeformPath? = paths().mapNotNull { path ->
        val points = DeformPathTools.positions(path, t.geometry.points)
        val curve = DeformPathTools.curve(points, path.points.map { it.corner }, path.closed)
        val scr = screen(curve.flatMap { listOf(it.first, it.second) }.toFloatArray(), t, viewport)
        val dist = scr.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(pos, a, b) } ?: Float.MAX_VALUE
        if (dist < radius) path to dist else null
    }.minByOrNull { it.second }?.first

    /**
     * Starts a path-point drag, or selects / Ctrl-inserts on the curve.
     * Returns true when the pointer was over a path so the caller should not fall through to object/mesh picks.
     */
    private fun beginPathInteraction(pos: Offset, t: CanvasTarget, viewport: CanvasViewport, ctrl: Boolean): Boolean {
        val hit = pathControlHit(pos, t, viewport)
        if (hit != null) {
            activePath = hit.first.id
            pathPoint = hit.second
            clearMeshElementSelection()
            targetAtPress = t
            original = model
            pathDragging = true
            dragging = true
            return true
        }
        val curveHit = pathCurveHit(pos, t, viewport) ?: return false
        activePath = curveHit.id
        pathPoint = -1
        pathDragging = false
        clearMeshElementSelection()
        if (ctrl) {
            val points = DeformPathTools.positions(curveHit, t.geometry.points)
            val screens = screen(points.flatMap { listOf(it.first, it.second) }.toFloatArray(), t, viewport)
            val segment = (0 until if (curveHit.closed) points.size else points.size - 1)
                .minByOrNull { i -> distanceToSegment(pos, screens[i], screens[(i + 1) % points.size]) } ?: 0
            val point = local(pos, t, viewport, points[segment])
            val bound = DeformPathTools.bind(t.geometry.points, t.indices, point.first, point.second)
            changePath { it.copy(points = it.points.take(segment + 1) + bound + it.points.drop(segment + 1)) }
            pathPoint = segment + 1
        }
        return true
    }

    /** Ends a path-handle gesture without dropping the selected path / point highlight. */
    private fun endPathDrag() {
        pathDragging = false
    }

    /** Clears path point selection so mesh edits cannot drag a lingering path handle. */
    private fun clearPathPointSelection() {
        pathDragging = false
        pathPoint = -1
    }

    /**
     * Binding edit lives in EDIT; deformation lives in DEFORM.
     * SELECT never edits path handles — object mode only picks whole parts.
     */
    private fun pathPointsInteractive(): Boolean {
        val t = target() ?: return false
        return t.kind == "mesh" && paths().isNotEmpty() && (
            tool == CanvasTool.CREATE_DEFORM_PATH ||
                hierarchyMode == EditHierarchyMode.DEFORM ||
                hierarchyMode == EditHierarchyMode.EDIT
            )
    }

    private fun clearMeshElementSelection() {
        vertices = emptySet()
        selectedEdges = emptySet()
        selectedFaces = emptySet()
    }
    private fun coordinate(t: CanvasTarget) = buildMap {
        t.geometry.axes.forEach { a -> put(a.parameterId.raw, pose[a.parameterId.raw] ?: model.parameters.single { it.id == a.parameterId }.default) }
        parameter?.let { p -> model.parameters.firstOrNull { it.id.raw == p }?.let { put(p, pose[p] ?: it.default) } }
    }

    /** Only structural mesh editing moves UVs; deformation writes the current pose. */
    private fun geometryCommand(t: CanvasTarget, points: FloatArray) =
        canvasGeometryCommand(hierarchyMode, t.kind, t.id, coordinate(t), points)

    fun targetLayerId(t: CanvasTarget? = target(deformerId = null)): String? {
        if (t == null) return state.selectedLayerId
        val preview = state.previewModel ?: return state.selectedLayerId
        return preview.rig.layerIdByDrawableId[t.id] ?: state.selectedLayerId ?: t.id
    }

    fun targetPlacement(t: CanvasTarget? = target(deformerId = null)): io.github.psd2live.core.AtlasPlacement? {
        val atlas = state.previewModel?.atlas ?: return null
        val layerId = targetLayerId(t) ?: return null
        return atlas.placementByLayerId[layerId]
            ?: (state.previewModel?.rig?.layerIdByDrawableId?.get(layerId)?.let { atlas.placementByLayerId[it] })
            ?: atlas.placementByLayerId[layerId.substringBefore(':').substringBeforeLast('-')]
    }

    fun paintTarget(pos: Offset? = null, viewport: CanvasViewport? = null): CanvasTarget? {
        val t = target(deformerId = null)
        if (t != null && t.kind == "mesh") return t
        if (pos != null && viewport != null) {
            val hit = pickLayer(pos, viewport)
            if (hit != null) {
                viewModel.selectLayer(hit)
                return target(layerId = hit, deformerId = null)
            }
        }
        val layerId = state.selectedLayerId ?: return null
        return target(layerId = layerId, deformerId = null)
    }

    fun startPaintSession(layerId: String? = null, forceReload: Boolean = false): PaintSession? {
        val t = paintTarget()
        val targetLid = layerId ?: targetLayerId(t) ?: state.selectedLayerId ?: return null
        val currentSession = paintSession
        if (!forceReload && currentSession != null && currentSession.layerId == targetLid) {
            return currentSession
        }
        if (currentSession != null) {
            discardPaintSession()
        }
        val currentAnalysis = state.analysis ?: state.previewModel?.analysis ?: return null
        val docWidth = currentAnalysis.source.widthPx.coerceAtLeast(1)
        val docHeight = currentAnalysis.source.heightPx.coerceAtLeast(1)

        val layer = sourceLayerFor(currentAnalysis, targetLid) ?: return null
        val layerName = layer.name.ifBlank { targetLid }
        val bounds = layer.bounds
        val raster = layer.raster

        val workingCopy = BufferedImage(docWidth, docHeight, BufferedImage.TYPE_INT_ARGB)
        if (raster.width > 0 && raster.height > 0 && raster.rgba.isNotEmpty()) {
            val layerImg = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
            val rgba = raster.rgba
            val intPixels = IntArray(raster.width * raster.height)
            for (i in intPixels.indices) {
                val r = rgba[i * 4].toInt() and 0xFF
                val g = rgba[i * 4 + 1].toInt() and 0xFF
                val b = rgba[i * 4 + 2].toInt() and 0xFF
                val a = rgba[i * 4 + 3].toInt() and 0xFF
                intPixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            layerImg.setRGB(0, 0, raster.width, raster.height, intPixels, 0, raster.width)
            val g = workingCopy.createGraphics()
            try {
                g.drawImage(layerImg, bounds.left, bounds.top, null)
            } finally {
                g.dispose()
            }
        }

        val newSession = PaintSession(
            layerId = targetLid,
            layerName = layerName,
            workingImage = workingCopy,
            originalImageCopy = PaintSession.copyImage(workingCopy),
        )
        paintSession = newSession
        return newSession
    }

    fun ensurePaintSession(layerId: String? = null): PaintSession? =
        startPaintSession(layerId, forceReload = false)

    fun canUndoPaint(layerId: String? = targetLayerId(paintTarget())): Boolean =
        paintSession?.canUndo() == true

    fun canRedoPaint(layerId: String? = targetLayerId(paintTarget())): Boolean =
        paintSession?.canRedo() == true

    fun undoPaint(layerId: String = targetLayerId(paintTarget()) ?: "") {
        val session = paintSession ?: return
        session.undo()
    }

    fun redoPaint(layerId: String = targetLayerId(paintTarget()) ?: "") {
        val session = paintSession ?: return
        session.redo()
    }

    fun jumpToPaintStroke(index: Int) {
        val session = paintSession ?: return
        session.jumpToStroke(index)
    }

    fun resetPaintSession() {
        paintSession = null
        isPainting = false
        paintStrokeStart = null
        paintStrokeCurrent = null
        showRebuildMeshDialog = false
    }

    fun discardPaintSession() {
        val session = paintSession ?: return
        session.discard()
        paintSession = null
        isPainting = false
        paintStrokeStart = null
        paintStrokeCurrent = null
    }

    fun clearCurrentLayerPaint() {
        val session = ensurePaintSession() ?: return
        session.edit(java.awt.Rectangle(0, 0, session.docWidth, session.docHeight)) { image ->
            LayerPaintEngine.clear(image)
        }
        session.recordStroke(tr("editor.paint.strokeClear"))
    }

    fun promptCommitPaintSession() {
        val session = paintSession ?: return
        if (!session.isDirty) return
        showRebuildMeshDialog = true
    }

    fun commitPaintSession(rebuildMesh: Boolean) {
        val session = paintSession ?: return
        showRebuildMeshDialog = false

        val currentPreview = state.previewModel ?: return
        val currentAnalysis = currentPreview.analysis
        // The frames the live rig was built on. Rebuilt from the previous analysis on purpose: the
        // commit preserves every deformer, so a mesh rebuilt against frames moved by the new paint
        // would no longer line up with the parent deformer it hangs under.
        val rigContext = RigBuilder.rigContext(currentAnalysis, currentPreview.config)
        val img = session.workingImage
        val docW = session.docWidth
        val docH = session.docHeight

        // 1. Scan workingImage to find tight non-transparent bounding box
        var minX = docW
        var minY = docH
        var maxX = -1
        var maxY = -1

        val row = IntArray(docW)
        for (y in 0 until docH) {
            img.getRGB(0, y, docW, 1, row, 0, docW)
            for (x in 0 until docW) {
                val alpha = (row[x] ushr 24) and 0xFF
                if (alpha > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val newBounds: LayerBounds
        val newRaster: LayerRaster

        if (maxX < minX || maxY < minY) {
            // Completely erased / transparent layer
            newBounds = LayerBounds(0, 0, 1, 1)
            newRaster = LayerRaster(1, 1, ByteArray(4))
        } else {
            val cropW = maxX - minX + 1
            val cropH = maxY - minY + 1
            newBounds = LayerBounds(minX, minY, cropW, cropH)
            val croppedImg = img.getSubimage(minX, minY, cropW, cropH)
            val pixels = IntArray(cropW * cropH)
            croppedImg.getRGB(0, 0, cropW, cropH, pixels, 0, cropW)
            val rgba = ByteArray(cropW * cropH * 4)
            for (i in pixels.indices) {
                val argb = pixels[i]
                rgba[i * 4] = ((argb ushr 16) and 0xFF).toByte()     // R
                rgba[i * 4 + 1] = ((argb ushr 8) and 0xFF).toByte()  // G
                rgba[i * 4 + 2] = (argb and 0xFF).toByte()           // B
                rgba[i * 4 + 3] = ((argb ushr 24) and 0xFF).toByte() // A
            }
            newRaster = LayerRaster(cropW, cropH, rgba)
        }

        // 2. Identify target Drawable, ClassifiedLayer, and SourceLayer
        val targetLid = session.layerId
        val targetDrawable = currentPreview.rig.puppet.drawables.firstOrNull {
            it.id.raw == targetLid || currentPreview.rig.layerIdByDrawableId[it.id.raw] == targetLid
        }
        val targetClassified = classifiedLayerFor(currentAnalysis, targetLid)
        val targetSourceLayerId = targetClassified?.source?.id?.raw
            ?: targetLid.substringBefore(':').substringBeforeLast('-')
        val oldBounds = sourceLayerFor(currentAnalysis, targetLid)?.bounds ?: newBounds

        // 3. Update Source Art and Classified Layers
        val updatedSrcLayers = currentAnalysis.source.layers.map { sl ->
            if (sl.id.raw == targetSourceLayerId || sl.id.raw == targetLid || sl.id.raw == targetClassified?.source?.id?.raw) {
                val base = if (sl is WorkspaceSourceLayer) sl else WorkspaceSourceLayer.copyOf(sl, sl.order) as WorkspaceSourceLayer
                base.copy(bounds = newBounds, raster = newRaster)
            } else {
                if (sl is WorkspaceSourceLayer) sl else WorkspaceSourceLayer.copyOf(sl, sl.order)
            }
        }
        val updatedSourceArt = WorkspaceSourceArt(
            widthPx = currentAnalysis.source.widthPx,
            heightPx = currentAnalysis.source.heightPx,
            layers = updatedSrcLayers,
            groups = currentAnalysis.source.groups,
        )

        val updatedClassifiedLayers = currentAnalysis.layers.map { cl ->
            if (cl.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid)) {
                val updatedSource = (cl.source as? WorkspaceSourceLayer)?.copy(bounds = newBounds, raster = newRaster)
                    ?: (WorkspaceSourceLayer.copyOf(cl.source, cl.source.order) as WorkspaceSourceLayer).copy(bounds = newBounds, raster = newRaster)
                val updatedFloatBounds = newBounds.toBounds()
                cl.copy(
                    source = updatedSource,
                    bounds = updatedFloatBounds,
                    opaquePixels = newBounds.width * newBounds.height,
                    centroidX = newBounds.left + newBounds.width * 0.5f,
                    centroidY = newBounds.top + newBounds.height * 0.5f,
                )
            } else cl
        }

        val updatedAnalysis = currentAnalysis.copy(
            source = updatedSourceArt,
            layers = updatedClassifiedLayers,
        )

        // 4. Repack texture atlas with updated layer raster
        val refreshedAnalysis = MouthLipLayers.prepare(updatedAnalysis, currentPreview.config)
        // The ribbons are generated from the mouth layer, so a repaint would normally regenerate them
        // too. Keeping the existing mesh means keeping the ribbons' own pixels as well: a regenerated
        // ribbon follows a contour the kept mesh no longer has, and its coordinates would fall outside
        // the slice it was packed into.
        val effectiveAnalysis = if (rebuildMesh) refreshedAnalysis else refreshedAnalysis.copy(
            layers = refreshedAnalysis.layers.map { layer ->
                if (layer.source is MouthLipLayer) {
                    currentAnalysis.layers.firstOrNull { it.source.id.raw == layer.source.id.raw } ?: layer
                } else {
                    layer
                }
            },
        )
        val newAtlas = AtlasPacker.pack(
            effectiveAnalysis.layers,
            currentPreview.config.atlasSize,
            currentPreview.config.texturePadding,
            currentPreview.config.textureUpscale,
        )
        val oldAtlas = currentPreview.atlas

        fun findPlacement(atlas: PackedAtlas, drawableId: String, layerId: String?): io.github.psd2live.core.AtlasPlacement? {
            if (layerId != null && atlas.placementByLayerId.containsKey(layerId)) {
                return atlas.placementByLayerId[layerId]
            }
            if (atlas.placementByLayerId.containsKey(drawableId)) {
                return atlas.placementByLayerId[drawableId]
            }
            val mappedId = currentPreview.rig.layerIdByDrawableId[drawableId]
            if (mappedId != null && atlas.placementByLayerId.containsKey(mappedId)) {
                return atlas.placementByLayerId[mappedId]
            }
            val baseId = (layerId ?: drawableId).substringBefore(':').substringBeforeLast('-')
            if (atlas.placementByLayerId.containsKey(baseId)) {
                return atlas.placementByLayerId[baseId]
            }
            return null
        }

        /** One layer's slice of [atlas], or null when it holds none. An unknown [bounds] reads as the
         *  canvas origin, which leaves the texture coordinates translated but unscaled. */
        fun sliceOf(atlas: PackedAtlas, drawableId: String, layerId: String, bounds: LayerBounds?): AtlasSlice? {
            val placement = findPlacement(atlas, drawableId, layerId) ?: return null
            val page = atlas.pages.getOrNull(placement.page)
            return AtlasSlice(
                placement = placement,
                pageWidth = page?.image?.width ?: placement.width,
                pageHeight = page?.image?.height ?: placement.height,
                sourceBounds = bounds?.let { Bounds(it.left.toFloat(), it.top.toFloat(), (it.left + it.width).toFloat(), (it.top + it.height).toFloat()) }
                    ?: Bounds(0f, 0f, 0f, 0f),
            )
        }

        /** The mesh a rebuild replaces, described so the frame its parent deformer expects can be
         *  recovered from the geometry itself - the only source left for an imported or hand-made rig. */
        fun replacedMesh(mesh: DrawableMesh, atlas: PackedAtlas, drawableId: String, layerId: String, bounds: LayerBounds): RigBuilder.ReplacedMesh {
            val slice = sliceOf(atlas, drawableId, layerId, bounds)
            return RigBuilder.ReplacedMesh(
                mesh = mesh,
                placement = slice?.placement,
                pageWidth = slice?.pageWidth ?: 1,
                pageHeight = slice?.pageHeight ?: 1,
                sourceBounds = slice?.sourceBounds ?: Bounds(0f, 0f, 0f, 0f),
            )
        }

        val targetPlacement = findPlacement(newAtlas, targetDrawable?.id?.raw ?: targetLid, targetClassified?.source?.id?.raw ?: targetLid)
            ?: newAtlas.placementByLayerId[targetLid]
            ?: newAtlas.placementByLayerId.values.firstOrNull()
            ?: io.github.psd2live.core.AtlasPlacement(0, 0, 0, newBounds.width, newBounds.height)
        val targetPage = newAtlas.pages.getOrNull(targetPlacement.page)
        val targetPageWidth = targetPage?.image?.width ?: currentPreview.config.atlasSize
        val targetPageHeight = targetPage?.image?.height ?: currentPreview.config.atlasSize

        val regeneratedLips = RigBuilder.generatedMouthLips(effectiveAnalysis)
        val finalTargetClassified = effectiveAnalysis.layers.firstOrNull { it.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid) }
            ?: updatedClassifiedLayers.firstOrNull { it.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid) }
            ?: targetClassified

        // 5. Update drawables (preserving deformers, hierarchy, keyforms, and rigging)
        val updatedPageByDrawableId = currentPreview.rig.pageByDrawableId.toMutableMap()
        val updatedSourceBounds = currentPreview.rig.sourceBoundsByDrawableId.toMutableMap()

        val droppedDrawables = mutableSetOf<DrawableId>()
        val rebuiltLips = mutableMapOf<String, RigBuilder.MouthLip>()
        val updatedDrawables = currentPreview.rig.puppet.drawables.mapNotNull { drawable ->
            val layerId = currentPreview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
            val isTarget = (targetDrawable != null && drawable.id == targetDrawable.id) ||
                           drawable.id.raw == targetLid ||
                           layerId == targetLid ||
                           layerId == targetClassified?.source?.id?.raw ||
                           drawable.id.raw == targetClassified?.source?.id?.raw

            if (isTarget) {
                updatedPageByDrawableId[drawable.id.raw] = targetPlacement.page

                if (rebuildMesh || drawable.mesh == null) {
                    // The neutral-pose reference follows the mesh, not the texture: a kept mesh keeps
                    // describing the area it covers even when new pixels were painted beyond it.
                    updatedSourceBounds[drawable.id.raw] =
                        newBounds.toBounds()

                    val targetClassifiedLayer = finalTargetClassified
                        ?: targetClassified
                        ?: error("Target classified layer not found for paint commit: $targetLid")

                    // The rig keeps its deformers, so the new mesh has to be normalized against the
                    // frames those deformers were built on - the context of the analysis the rig came
                    // from - and not against frames derived from the freshly painted bounds, which
                    // would rescale the drawable against every sibling that kept the old frames.
                    val rebuilt = RigBuilder.rebuildDrawableMesh(
                        layer = targetClassifiedLayer,
                        context = rigContext,
                        placement = targetPlacement,
                        pageWidth = targetPageWidth,
                        pageHeight = targetPageHeight,
                        config = currentPreview.config,
                        parentId = drawable.parentDeformerId,
                        owner = drawable,
                        atlas = newAtlas,
                        generatedLips = regeneratedLips,
                        previous = drawable.mesh?.let { replacedMesh(it, oldAtlas, drawable.id.raw, layerId, oldBounds) },
                    )
                    for (lip in rebuilt.mouthLips) rebuiltLips[lip.drawable.id.raw] = lip

                    drawable.copy(
                        mesh = rebuilt.mesh,
                        texturePage = targetPlacement.page,
                        // A user-edited grid only survives a rebuild that kept the vertex count.
                        geometryGrid = if (drawable.mesh?.positions?.size == rebuilt.mesh.positions.size) {
                            drawable.geometryGrid ?: rebuilt.geometryGrid
                        } else {
                            rebuilt.geometryGrid
                        },
                    )
                } else {
                    val oldMesh = drawable.mesh
                    val oldSlice = sliceOf(oldAtlas, drawable.id.raw, layerId, oldBounds)
                    val newSlice = sliceOf(newAtlas, drawable.id.raw, layerId, newBounds)
                    if (oldSlice != null && newSlice != null) {
                        drawable.copy(
                            mesh = DrawableMesh(oldMesh.positions, remapUvs(oldMesh, oldSlice, newSlice), oldMesh.indices),
                            texturePage = targetPlacement.page,
                        )
                    } else {
                        drawable.copy(texturePage = targetPlacement.page)
                    }
                }
            } else {
                val oldSlice = sliceOf(oldAtlas, drawable.id.raw, layerId, sourceLayerFor(currentAnalysis, layerId)?.bounds)
                val newSlice = sliceOf(newAtlas, drawable.id.raw, layerId, sourceLayerFor(effectiveAnalysis, layerId)?.bounds)
                val oldMesh = drawable.mesh
                when {
                    // The repack has no slice for this drawable any more. That is what a generated layer
                    // does when the layer it follows loses the shape it was built from - an erased mouth
                    // takes its lip ribbons with it - and keeping the drawable would leave it sampling
                    // whatever the repack happened to place at its old texture coordinates, which reads
                    // as the art tearing apart instead of disappearing.
                    oldMesh != null && oldSlice != null && newSlice == null -> {
                        droppedDrawables += drawable.id
                        null
                    }
                    oldMesh != null && oldSlice != null && newSlice != null -> {
                        updatedPageByDrawableId[drawable.id.raw] = newSlice.placement.page
                        drawable.copy(
                            mesh = DrawableMesh(oldMesh.positions, remapUvs(oldMesh, oldSlice, newSlice), oldMesh.indices),
                            texturePage = newSlice.placement.page,
                        )
                    }
                    else -> drawable
                }
            }
        }

        // 6. Update puppet and rig (deformers, hierarchy, parameters preserved 100%). Deleting a drawable
        // goes through the model's own delete so the org tree, clip masks, glues and the derived render
        // order all stop referring to it.
        for (id in droppedDrawables) {
            updatedPageByDrawableId.remove(id.raw)
            updatedSourceBounds.remove(id.raw)
        }
        // Ribbons are swapped in by id, whichever side of their owner they sit on, and a ribbon the rig
        // never had - a mouth painted back after its own erase - joins the part its own mouth is in.
        var drawablesAfterRepack = updatedDrawables.map { drawable ->
            val lip = rebuiltLips[drawable.id.raw]
            if (lip == null) drawable else lip.drawable.copy(
                drawOrder = drawable.drawOrder,
                blendMode = drawable.blendMode,
                isVisible = drawable.isVisible,
                maskedBy = drawable.maskedBy,
            )
        }
        val addedLips = rebuiltLips.values.filter { lip -> drawablesAfterRepack.none { it.id == lip.drawable.id } }
        for (lip in addedLips) {
            updatedPageByDrawableId[lip.drawable.id.raw] = lip.drawable.texturePage
            updatedSourceBounds[lip.drawable.id.raw] = lip.neutralBounds
        }
        if (addedLips.isNotEmpty()) drawablesAfterRepack = drawablesAfterRepack + addedLips.map { it.drawable }
        val partsAfterRepack = if (addedLips.isEmpty()) {
            currentPreview.rig.puppet.parts
        } else {
            val addedByOwner = addedLips.groupBy { it.ownerId }
            currentPreview.rig.puppet.parts.map { part ->
                val added = part.children.filterIsInstance<OrgChild.Drawable>()
                    .flatMap { addedByOwner[it.id].orEmpty() }
                if (added.isEmpty()) part else part.copy(children = part.children + added.map { OrgChild.Drawable(it.drawable.id) })
            }
        }
        val updatedPuppet = currentPreview.rig.puppet
            .let { puppet -> if (droppedDrawables.isEmpty()) puppet else puppet.withDrawablesDeleted(droppedDrawables) }
            .copy(
                drawables = drawablesAfterRepack,
                parts = partsAfterRepack,
                deformPaths = currentPreview.rig.puppet.deformPaths.filterNot { it.drawableId in droppedDrawables } +
                    addedLips.mapNotNull { it.path },
            )
            .let { puppet -> if (addedLips.isEmpty()) puppet else puppet.withDerivedRenderRoot() }
        val updatedRig = currentPreview.rig.copy(
            puppet = updatedPuppet,
            pageByDrawableId = updatedPageByDrawableId,
            sourceBoundsByDrawableId = updatedSourceBounds,
            layerIdByDrawableId = currentPreview.rig.layerIdByDrawableId +
                addedLips.associate { it.drawable.id.raw to it.layer.source.id.raw },
        )

        val (runtimeBundle, _) = viewModel.pipeline.buildRuntimeBundle(
            "psd2live-preview",
            effectiveAnalysis,
            newAtlas,
            updatedRig,
            currentPreview.config,
        )

        val finalPreview = currentPreview.copy(
            analysis = updatedAnalysis,
            atlas = newAtlas,
            rig = updatedRig,
            baseRig = currentPreview.baseRig.copy(puppet = updatedPuppet, pageByDrawableId = updatedPageByDrawableId, sourceBoundsByDrawableId = updatedSourceBounds),
            runtimeBundle = runtimeBundle,
        )

        // 7. Update state and project history. The canvas rebuilds its texture painter from the new
        // preview model, so the committed atlas reaches the screen without a swap of its own.
        viewModel.applyCommittedPaint(finalPreview, tr("editor.paint.commitSummary", session.layerName))

        // 8. Refresh PaintSession baseline with new committed image
        paintSession = startPaintSession(session.layerId, forceReload = true)
        isPainting = false
        paintStrokeStart = null
        paintStrokeCurrent = null
        showRebuildMeshDialog = false
    }

    /** The bounds the paint session's layer occupies on the document canvas. */
    fun activePaintLayerBounds(): LayerBounds? {
        val layerId = targetLayerId(paintTarget()) ?: state.selectedLayerId ?: return null
        val analysis = state.analysis ?: state.previewModel?.analysis ?: return null
        return sourceLayerFor(analysis, layerId)?.bounds
    }

    /**
     * Every id a paint target can be named by: the rig maps generated drawables back to their layer,
     * and generated mouth lips carry a suffix on top of it.
     */
    private fun paintTargetIds(layerId: String): List<String> = buildList {
        add(layerId)
        state.previewModel?.rig?.layerIdByDrawableId?.get(layerId)?.let { add(it) }
        layerId.substringBefore(':').substringBeforeLast('-').let { if (it !in this) add(it) }
    }

    private fun classifiedLayerFor(analysis: PipelineAnalysis, layerId: String): ClassifiedLayer? =
        paintTargetIds(layerId).firstNotNullOfOrNull { id ->
            analysis.layers.firstOrNull { it.source.id.raw == id }
        }

    private fun sourceLayerFor(analysis: PipelineAnalysis, layerId: String): SourceLayer? =
        paintTargetIds(layerId).firstNotNullOfOrNull { id ->
            analysis.layers.firstOrNull { it.source.id.raw == id }?.source
                ?: analysis.source.layers.firstOrNull { it.id.raw == id }
        }

    fun screenToCanvasPixel(pos: Offset, viewport: CanvasViewport): Pair<Int, Int>? {
        val session = paintSession ?: return null
        val cx = viewport.canvasX(pos.x).toInt()
        val cy = viewport.canvasY(pos.y).toInt()
        if (cx !in 0 until session.docWidth || cy !in 0 until session.docHeight) return null
        return cx to cy
    }

    /**
     * The colour of the layer's pixel under [pos], or null when the pointer is off the layer's raster or
     * over nothing painted there.
     *
     * The eyedropper picks with this, and so does the cursor's sampling ring - one answer to "what is
     * under the pointer", so the ring cannot report a colour the pick would not take.
     */
    fun sampleColorAt(pos: Offset, viewport: CanvasViewport): androidx.compose.ui.graphics.Color? {
        val session = paintSession ?: return null
        val pixel = screenToCanvasPixel(pos, viewport) ?: return null
        val argb = session.workingImage.getRGB(pixel.first, pixel.second)
        if (((argb ushr 24) and 0xFF) == 0) return null
        return androidx.compose.ui.graphics.Color(
            red = ((argb ushr 16) and 0xFF) / 255f,
            green = ((argb ushr 8) and 0xFF) / 255f,
            blue = (argb and 0xFF) / 255f,
            alpha = ((argb ushr 24) and 0xFF) / 255f,
        )
    }

    /**
     * Where the sampling ring belongs, or null when the pointer is drawing rather than picking.
     *
     * The eyedropper always picks; Alt makes any paint tool pick for as long as it is held, which is how
     * a colour is taken without leaving the brush; and a pick already under way keeps picking until the
     * button comes up. The overlay asks this rather than working it out again, so the ring and the pick
     * cannot disagree about whether the pointer is picking.
     */
    fun pickCursor(): Offset? = cursor?.takeIf {
        hierarchyMode == EditHierarchyMode.PAINT && tool in PAINT_TOOLS &&
            (tool == CanvasTool.PAINT_EYEDROPPER || altHeld || isSampling)
    }

    /** Takes the colour under [pos]: the foreground, or the secondary colour when [secondary]. */
    fun pickColorAt(pos: Offset, viewport: CanvasViewport, secondary: Boolean = false) {
        val sampled = sampleColorAt(pos, viewport) ?: return
        if (secondary) paintSecondaryColor = sampled else paintColor = sampled
    }

    /**
     * Where the pointer is on the layer's raster, in the float the tip is stamped at: a stroke belongs
     * between two pixels, not on one of them, and rounding here would make a slow drag step.
     */
    private fun screenToCanvasPoint(pos: Offset, viewport: CanvasViewport): Pair<Float, Float>? {
        paintSession ?: return null
        return viewport.canvasX(pos.x) to viewport.canvasY(pos.y)
    }

    /**
     * Applies the segment the pointer just covered, the way every paint program does: the layer holds
     * the result while the gesture is still running, so hardness and opacity are visible as they are
     * being dragged rather than one release later, and the canvas shows the mark itself instead of an
     * overlay standing in for it.
     *
     * The stroke's coverage says what the stroke looks like; the layer is then rebuilt over the pixels
     * this segment has just claimed, out of the pixels the stroke started from. Two things follow: the
     * mark reaches nowhere the tip did not, and the work is proportional to the segment rather than to
     * the stroke drawn so far.
     */
    private fun applyLiveSegment(from: Offset, to: Offset, erase: Boolean) {
        val session = paintSession ?: return
        val vp = viewport ?: return
        val start = screenToCanvasPoint(from, vp) ?: return
        val end = screenToCanvasPoint(to, vp) ?: return
        // Nothing new under the segment - a stroke doubling back over itself - is nothing to redraw.
        val claimed = session.stroke()
            .addSegment(start.first, start.second, end.first, end.second, paintTip()) ?: return
        session.landSegment(claimed, paintColor, paintOpacity, erase = erase)
        refreshPaintPreview()
    }

    /** Repaints the preview at most every ~40 ms: a gesture fires far more moves than frames. */
    private fun refreshPaintPreview() {
        val now = System.nanoTime()
        if (lastPaintBitmapAt != 0L && now - lastPaintBitmapAt < 40_000_000L) return
        lastPaintBitmapAt = now
        paintSession?.refreshPreview()
    }

    fun screenToAtlasPixel(pos: Offset, t: CanvasTarget, viewport: CanvasViewport): Pair<Int, Int>? {
        val atlas = state.previewModel?.atlas ?: return null
        val placement = targetPlacement(t) ?: return null
        val page = atlas.pages.getOrNull(placement.page) ?: return null

        val screenPts = screen(t.geometry.points, t, viewport)
        val mesh = (model.drawables.firstOrNull { it.id.raw == t.id }?.mesh) ?: return null
        val uvs = mesh.uvs
        val indices = mesh.indices

        for (tri in indices.indices step 3) {
            val ia = indices[tri]
            val ib = indices[tri + 1]
            val ic = indices[tri + 2]
            val a = screenPts[ia]
            val b = screenPts[ib]
            val c = screenPts[ic]

            val v0 = b - a
            val v1 = c - a
            val v2 = pos - a
            val d00 = v0.x * v0.x + v0.y * v0.y
            val d01 = v0.x * v1.x + v0.y * v1.y
            val d11 = v1.x * v1.x + v1.y * v1.y
            val d20 = v2.x * v0.x + v2.y * v0.y
            val d21 = v2.x * v1.x + v2.y * v1.y
            val denom = d00 * d11 - d01 * d01
            if (abs(denom) < 1e-6f) continue
            val v = (d11 * d20 - d01 * d21) / denom
            val w = (d00 * d21 - d01 * d20) / denom
            val u = 1f - v - w
            if (u in -0.01f..1.01f && v in -0.01f..1.01f && w in -0.01f..1.01f) {
                val uc = (u.coerceIn(0f, 1f) * uvs[ia * 2] + v.coerceIn(0f, 1f) * uvs[ib * 2] + w.coerceIn(0f, 1f) * uvs[ic * 2])
                val vc = (u.coerceIn(0f, 1f) * uvs[ia * 2 + 1] + v.coerceIn(0f, 1f) * uvs[ib * 2 + 1] + w.coerceIn(0f, 1f) * uvs[ic * 2 + 1])
                val px = (uc * page.image.width).toInt()
                val py = (vc * page.image.height).toInt()
                if (px in placement.x until (placement.x + placement.width) &&
                    py in placement.y until (placement.y + placement.height)
                ) {
                    return px to py
                }
            }
        }

        // Fallback: direct projection into layer canvas bounds and placement
        val layerId = targetLayerId(t) ?: return null
        val classifiedLayer = state.analysis?.layers?.firstOrNull { it.source.id.raw == layerId }
        val layerBounds = classifiedLayer?.source?.bounds
        if (layerBounds != null) {
            val canvasX = viewport.canvasX(pos.x.toInt())
            val canvasY = viewport.canvasY(pos.y.toInt())
            val localX = canvasX - layerBounds.left
            val localY = canvasY - layerBounds.top
            if (localX >= 0f && localX < layerBounds.width &&
                localY >= 0f && localY < layerBounds.height
            ) {
                val px = (placement.x + localX * placement.scale).toInt()
                val py = (placement.y + localY * placement.scale).toInt()
                if (px in placement.x until (placement.x + placement.width) &&
                    py in placement.y until (placement.y + placement.height)
                ) {
                    return px to py
                }
            }
        }
        return null
    }

    fun cancel() {
        if (busy) return
        // Every tip edits pixels as it goes, so an abandoned gesture has to give them back.
        val session = paintSession
        if (isPainting && session != null) {
            session.abandonStroke()
            session.refreshPreview()
        }
        endBrushAdjust(cancel = true)
        preview = null; pending = null; dragging = false; targetAtPress = null; original = null; topologyFills = null
        knifeDraft = emptyList(); knifeDrawableId = null; subdividing = false; subdivideEdges = emptySet()
        knifeHover = null; knifeSnapKind = null
        isCreatingWarp = false; isCreatingRotation = false; creationStart = null; creationCurrent = null
        placement = null; placementHandle = PlacementHandle.NONE; placementDragStart = null; placementDragSnapshot = null
        glueFirstMesh = null; glueHoverMesh = null
        activeBezierAnchor = null; activeBezierHandle = null
        activeBrushWeights = null; activeBrushCenter = null
        brushInitialBase = null; brushInitialScreen = null; brushAffectedIndices = emptySet()
        marquee = emptyList(); draft = emptyList(); draftPathId = null; drawingPath = false
        pathDragging = false
        axis = null; head = null; objectTargets = emptyList(); pendingObjects = emptyList()
        activeHandle = BoundingHandle.NONE
        initialBounds = null
        boxDrag = false; dragIndices = emptyList()
        endTransformBox()
        isPainting = false; isSampling = false; paintStrokeStart = null; paintStrokeCurrent = null;        // The frame belongs to the tool session, so cancelling is the only thing that drops it.
        frameAngle = 0f
        initialScreenPoints = emptyList()
    }

    fun resetSelection() {
        if (!busy) cancel()
        vertices = emptySet(); selectedEdges = emptySet(); selectedFaces = emptySet()
        activePath = null; pathPoint = -1; pathDragging = false
    }

    /**
     * Arms [next]. Creation tools ride the create strip and do not switch [hierarchyMode] (except
     * leaving paint). Other tools whose mode palette does not offer them still enter their mode first.
     */
    fun activateTool(next: CanvasTool) {
        if (busy) return
        if (next in CREATION_TOOLS) {
            activateCreationTool(next)
            return
        }
        createSessionReturnMode = null
        if (next !in toolbarGroups(hierarchyMode).flatten()) {
            val mode = modeForTool(next)
            if (!hasPartFor(mode)) { deferMode(mode, next); return }
            enterMode(mode)
        }
        cancel()
        tool = next
        error = null
        if (next == CanvasTool.LASSO_SELECT) selectionStyle = SelectionStyle.LASSO
        else if (next == CanvasTool.SELECT) selectionStyle = SelectionStyle.BOX
        if (next == CanvasTool.SELECT && objectMode) vertices = emptySet()
        clearHover()
    }

    /**
     * Arms a creation tool. With a valid selection, enters place-then-confirm; otherwise waits for a pick.
     */
    private fun activateCreationTool(next: CanvasTool) {
        if (hierarchyMode == EditHierarchyMode.PAINT) {
            leavePaintForCreation()
        }
        if (next == CanvasTool.GLUE) {
            if (createSessionReturnMode == null) createSessionReturnMode = hierarchyMode
            cancel()
            deferredMode = null
            tool = next
            error = null
            clearHover()
            return
        }
        if (next == CanvasTool.CREATE_DEFORM_PATH) {
            if (target()?.kind != "mesh") {
                deferCreation(next)
                return
            }
            beginPlacement(
                kind = CreatePlacementKind.PATH,
                relation = CreateRelation.AS_CHILD,
                anchorKind = "mesh",
                anchorId = target()!!.id,
                anchorLabel = model.drawables.firstOrNull { it.id.raw == target()!!.id }?.name ?: target()!!.id,
                meshIds = listOf(target()!!.id),
            )
            return
        }
        val meshTarget = target()?.takeIf { it.kind == "mesh" }
        val deformerId = state.selectedDeformerId
        when {
            next == CanvasTool.CREATE_WARP && warpAddTo == WarpAddTo.CHILD_OF_SELECTED_DEFORMER && deformerId != null -> {
                val d = model.deformers.firstOrNull { it.id.raw == deformerId } ?: run { deferCreation(next); return }
                beginPlacement(CreatePlacementKind.WARP, CreateRelation.AS_CHILD, "deformer", d.id.raw, d.name, emptyList())
            }
            meshTarget != null -> {
                val kind = if (next == CanvasTool.CREATE_ROTATION) CreatePlacementKind.ROTATION else CreatePlacementKind.WARP
                val name = model.drawables.firstOrNull { it.id.raw == meshTarget.id }?.name ?: meshTarget.id
                val meshes = objects.mapNotNull { target(model, it, null)?.takeIf { t -> t.kind == "mesh" }?.id }
                    .ifEmpty { listOf(meshTarget.id) }
                beginPlacement(kind, CreateRelation.AS_PARENT, "mesh", meshTarget.id, name, meshes)
            }
            deformerId != null && next == CanvasTool.CREATE_WARP -> {
                val d = model.deformers.firstOrNull { it.id.raw == deformerId } ?: run { deferCreation(next); return }
                beginPlacement(CreatePlacementKind.WARP, CreateRelation.AS_CHILD, "deformer", d.id.raw, d.name, emptyList())
            }
            else -> deferCreation(next)
        }
    }

    /**
     * Starts place-then-confirm from the hierarchy tree (Blender-style Add Parent / Add Child).
     * [anchorId] is a drawable id when [anchorIsDeformer] is false, else a deformer id.
     */
    fun beginTreeCreate(
        kind: CreatePlacementKind,
        relation: CreateRelation,
        anchorIsDeformer: Boolean,
        anchorId: String,
    ) {
        if (busy || !editable) return
        if (hierarchyMode == EditHierarchyMode.PAINT) leavePaintForCreation()
        if (anchorIsDeformer) {
            val d = model.deformers.firstOrNull { it.id.raw == anchorId } ?: return
            viewModel.selectDeformer(anchorId)
            when (kind) {
                CreatePlacementKind.PATH -> return // paths attach to meshes only
                CreatePlacementKind.WARP -> {
                    val meshes = if (relation == CreateRelation.AS_PARENT) {
                        descendantMeshIds(anchorId)
                    } else {
                        model.drawables.filter { it.parentDeformerId?.raw == anchorId }.map { it.id.raw }
                    }
                    beginPlacement(kind, relation, "deformer", anchorId, d.name, meshes)
                }
                CreatePlacementKind.ROTATION -> {
                    // Remount the deformer itself; descendant meshes are listed for scope preview only.
                    val meshes = descendantMeshIds(anchorId)
                    beginPlacement(kind, CreateRelation.AS_PARENT, "deformer", anchorId, d.name, meshes)
                }
            }
        } else {
            val drawable = model.drawables.firstOrNull { it.id.raw == anchorId } ?: return
            val layer = state.previewModel?.rig?.layerIdByDrawableId?.get(drawable.id.raw)
            if (layer != null) viewModel.selectLayer(layer)
            when (kind) {
                CreatePlacementKind.PATH -> beginPlacement(
                    CreatePlacementKind.PATH, CreateRelation.AS_CHILD, "mesh", drawable.id.raw, drawable.name, listOf(drawable.id.raw),
                )
                CreatePlacementKind.WARP, CreatePlacementKind.ROTATION -> {
                    beginPlacement(kind, CreateRelation.AS_PARENT, "mesh", drawable.id.raw, drawable.name, listOf(drawable.id.raw))
                }
            }
        }
    }

    private fun descendantMeshIds(deformerId: String): List<String> {
        val byParent = model.deformers.groupBy { it.parent?.raw }
        val under = mutableSetOf<String>()
        fun walk(id: String) {
            under.add(id)
            byParent[id].orEmpty().forEach { walk(it.id.raw) }
        }
        walk(deformerId)
        return model.drawables.filter { it.parentDeformerId?.raw in under }.map { it.id.raw }
    }

    private fun beginPlacement(
        kind: CreatePlacementKind,
        relation: CreateRelation,
        anchorKind: String,
        anchorId: String,
        anchorLabel: String,
        meshIds: List<String>,
    ) {
        if (createSessionReturnMode == null) createSessionReturnMode = hierarchyMode
        cancelKeepingReturnMode()
        deferredMode = null
        val spaceParentId = resolvePlacementSpaceParent(relation, anchorKind, anchorId, meshIds)
        val local = placementLocalBounds(meshIds, anchorKind, anchorId, spaceParentId)
        val cx = local[0] + local[2] / 2f
        val cy = local[1] + local[3] / 2f
        val parentIsWarp = spaceParentId != null &&
            model.deformers.any { it.id.raw == spaceParentId && it is Deformer.Warp }
        // Handle lengths are model units even when the pivot is in a warp's UV space.
        val tipLen = if (parentIsWarp) 100f else max(local[2], local[3]) * 0.35f + 40f
        val name = when (kind) {
            CreatePlacementKind.WARP -> tr("editor.defaultWarpName", anchorLabel)
            CreatePlacementKind.ROTATION -> tr("editor.defaultRotationName", anchorLabel)
            CreatePlacementKind.PATH -> anchorLabel
        }
        val part = warpCreatePartId
            ?: meshIds.firstOrNull()?.let { model.partByDrawable()[DrawableId(it)]?.raw }
            ?: (anchorKind == "deformer").takeIf { it }?.let {
                model.deformers.firstOrNull { d -> d.id.raw == anchorId }?.partId?.raw
            }
        placement = CreatePlacement(
            kind = kind,
            relation = relation,
            anchorKind = anchorKind,
            anchorId = anchorId,
            anchorLabel = anchorLabel,
            meshIds = meshIds,
            spaceParentId = spaceParentId,
            name = name,
            partId = part,
            localX = local[0],
            localY = local[1],
            localW = local[2].coerceAtLeast(1e-3f),
            localH = local[3].coerceAtLeast(1e-3f),
            originX = cx,
            originY = cy,
            tipX = cx + tipLen,
            tipY = cy,
            rows = warpCreateGridRows,
            cols = warpCreateGridCols,
            bezierRows = warpCreateBezierRows,
            bezierCols = warpCreateBezierCols,
        )
        tool = when (kind) {
            CreatePlacementKind.WARP -> CanvasTool.CREATE_WARP
            CreatePlacementKind.ROTATION -> CanvasTool.CREATE_ROTATION
            CreatePlacementKind.PATH -> CanvasTool.CREATE_DEFORM_PATH
        }
        if (kind == CreatePlacementKind.PATH) {
            drawingPath = true
            draft = emptyList()
            draftPathId = null
            pathClosed = false
        }
        error = null
        clearHover()
    }

    /** Parent id the new deformer will live under — same rules as [commitPlacedWarp] / [CanvasEdits]. */
    private fun resolvePlacementSpaceParent(
        relation: CreateRelation,
        anchorKind: String,
        anchorId: String,
        meshIds: List<String>,
    ): String? = when {
        relation == CreateRelation.AS_CHILD && anchorKind == "deformer" -> anchorId
        relation == CreateRelation.AS_PARENT && anchorKind == "deformer" ->
            model.deformers.firstOrNull { it.id.raw == anchorId }?.parent?.raw
        else -> meshIds.firstOrNull()?.let { mid ->
            model.drawables.firstOrNull { it.id.raw == mid }?.parentDeformerId?.raw
        }
    }

    /**
     * Parent-local AABB [x,y,w,h] for the placement ghost.
     * Uses mesh / deformer geometry points directly — same source as create-from-selection —
     * never camera-world envelopes.
     */
    private fun placementLocalBounds(
        meshIds: List<String>,
        anchorKind: String,
        anchorId: String,
        spaceParentId: String?,
    ): FloatArray {
        val pts = mutableListOf<Float>()
        for (id in meshIds) {
            val layer = state.previewModel?.rig?.layerIdByDrawableId?.get(id) ?: continue
            val t = target(model, layer, null) ?: continue
            pts.addAll(t.geometry.points.toList())
        }
        if (pts.isEmpty() && anchorKind == "deformer") {
            if (spaceParentId == anchorId) {
                // Empty child under [anchorId]: cover the parent's local domain.
                val parent = model.deformers.firstOrNull { it.id.raw == anchorId }
                return when (parent) {
                    is Deformer.Warp -> floatArrayOf(-0.05f, -0.05f, 1.1f, 1.1f)
                    is Deformer.Rotation -> floatArrayOf(-55f, -55f, 110f, 110f)
                    null -> floatArrayOf(-50f, -50f, 100f, 100f)
                }
            }
            val t = target(model, null, anchorId)
            if (t != null) pts.addAll(t.geometry.points.toList())
        }
        if (pts.size < 4) {
            val underWarp = spaceParentId != null &&
                model.deformers.any { it.id.raw == spaceParentId && it is Deformer.Warp }
            return if (underWarp) floatArrayOf(-0.05f, -0.05f, 1.1f, 1.1f)
            else floatArrayOf(-50f, -50f, 100f, 100f)
        }
        val b = RigGeometryTools.bounds(pts.toFloatArray())
        return floatArrayOf(
            b[0] - b[2] * 0.05f,
            b[1] - b[3] * 0.05f,
            b[2] * 1.1f,
            b[3] * 1.1f,
        )
    }

    private fun cancelKeepingReturnMode() {
        val keep = createSessionReturnMode
        cancel()
        createSessionReturnMode = keep
    }

    fun updatePlacementName(name: String) { placement = placement?.copy(name = name) }
    fun updatePlacementPart(partId: String?) { placement = placement?.copy(partId = partId) }
    fun updatePlacementGrid(rows: Int, cols: Int) {
        placement = placement?.copy(rows = rows.coerceIn(1, 32), cols = cols.coerceIn(1, 32))
        warpCreateGridRows = rows.coerceIn(1, 32)
        warpCreateGridCols = cols.coerceIn(1, 32)
    }
    fun updatePlacementBezier(rows: Int, cols: Int) {
        placement = placement?.copy(bezierRows = rows.coerceIn(1, 16), bezierCols = cols.coerceIn(1, 16))
        warpCreateBezierRows = rows.coerceIn(1, 16)
        warpCreateBezierCols = cols.coerceIn(1, 16)
    }

    fun updatePlacementRotationDirection(deg: Float) {
        val p = placement?.takeIf { it.kind == CreatePlacementKind.ROTATION } ?: return
        val parentIsWarp = p.spaceParentId != null &&
            model.deformers.any { it.id.raw == p.spaceParentId && it is Deformer.Warp }
        val minLen = if (parentIsWarp) 0.05f else 20f
        val len = hypot(p.tipX - p.originX, p.tipY - p.originY).coerceAtLeast(minLen)
        val rad = Math.toRadians(deg.toDouble())
        placement = p.copy(
            tipX = p.originX + (len * cos(rad)).toFloat(),
            tipY = p.originY + (len * sin(rad)).toFloat(),
        )
    }

    fun cancelPlacement() {
        placement = null
        placementHandle = PlacementHandle.NONE
        placementDragStart = null
        placementDragSnapshot = null
        drawingPath = false
        draft = emptyList()
        val returnMode = createSessionReturnMode
        createSessionReturnMode = null
        if (returnMode != null && returnMode != hierarchyMode && returnMode != EditHierarchyMode.PAINT) {
            hierarchyMode = returnMode
        }
        tool = CanvasTool.SELECT
        clearHover()
    }

    /** Commits the placed ghost into the model. */
    fun confirmPlacement() {
        val p = placement ?: return
        if (!editable || busy) return
        when (p.kind) {
            CreatePlacementKind.PATH -> {
                if (draft.size >= 2) finishPath()
                else error = tr("editor.placementPathNeedPoints")
                return
            }
            CreatePlacementKind.WARP -> commitPlacedWarp(p)
            CreatePlacementKind.ROTATION -> commitPlacedRotation(p)
        }
    }

    private fun commitPlacedWarp(p: CreatePlacement) {
        val id = "Warp_${UUID.randomUUID()}"
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", id)
            put("name", p.name)
            put("rows", p.rows)
            put("columns", p.cols)
            if (p.partId != null) put("part_id", p.partId!!)
            // Bounds already parent-local — same payload shape as [createWarpFromBounds].
            put("bounds", buildJsonObject {
                put("x", p.localX); put("y", p.localY); put("w", p.localW); put("h", p.localH)
            })
            when {
                p.relation == CreateRelation.AS_CHILD && p.anchorKind == "deformer" && p.meshIds.isEmpty() -> {
                    put("add_to", "child_of_deformer")
                    put("parent_id", p.anchorId)
                    put("meshes", JsonArray(emptyList()))
                }
                p.relation == CreateRelation.AS_PARENT && p.anchorKind == "deformer" -> {
                    put("add_to", "parent_of_deformer")
                    put("deformer_id", p.anchorId)
                    put("meshes", JsonArray(p.meshIds.map(::JsonPrimitive)))
                }
                else -> {
                    put("add_to", "parent_of_selected")
                    put("meshes", JsonArray(p.meshIds.map(::JsonPrimitive)))
                }
            }
        }
        // Store Bezier edit density before the journal round-trip clears placement.
        warpBezierDivisions[id] = p.bezierRows to p.bezierCols
        placement = null
        head = null
        commit(cmd)
    }

    private fun commitPlacedRotation(p: CreatePlacement) {
        val id = "Rotation_${UUID.randomUUID()}"
        val angleDeg = Math.toDegrees(
            atan2((p.tipY - p.originY).toDouble(), (p.tipX - p.originX).toDouble()),
        ).toFloat()
        val cmd = buildJsonObject {
            put("op", "canvas_create_rotation")
            put("preservePose", true)
            put("id", id)
            put("name", p.name)
            if (p.partId != null) put("part_id", p.partId!!)
            // Origin already parent-local — same as create-from-selection.
            put("origin", JsonArray(listOf(p.originX, p.originY).map(::JsonPrimitive)))
            put("angle", angleDeg)
            put("handle_length", hypot(p.tipX - p.originX, p.tipY - p.originY).coerceAtLeast(1e-4f))
            when {
                p.relation == CreateRelation.AS_PARENT && p.anchorKind == "deformer" -> {
                    put("add_to", "parent_of_deformer")
                    put("deformer_id", p.anchorId)
                }
                else -> {
                    require(p.meshIds.isNotEmpty()) { "Rotation needs meshes" }
                    put("add_to", "parent_of_selected")
                    put("meshes", JsonArray(p.meshIds.map(::JsonPrimitive)))
                }
            }
        }
        placement = null
        head = null
        commit(cmd)
    }

    /** Mapping for [CreatePlacement.spaceParentId]; root uses identity (model with camera Y-flip). */
    private fun placementMapping(spaceParentId: String?): DrawableSpaceMapping {
        val source = model
        if (cachedSource !== source || cachedPose != state.parameterValues) {
            cachedSource = source
            cachedPose = state.parameterValues
            cachedTargets.clear()
            cachedWorlds = buildDeformerWorlds(
                source.deformers,
                { p -> state.parameterValues[p] ?: source.parameters.firstOrNull { it.id == p }?.default ?: 0f },
            )
        }
        if (spaceParentId == null) return DrawableSpaceMapping(null)
        val world = cachedWorlds[DeformerId(spaceParentId)]
            ?: error("Parent deformer world unavailable: $spaceParentId")
        return DrawableSpaceMapping(world)
    }

    private fun placementLocalToScreen(
        lx: Float,
        ly: Float,
        viewport: CanvasViewport,
        mapping: DrawableSpaceMapping,
    ): Offset {
        val w = mapping.localToWorld(floatArrayOf(lx, ly))
        return Offset(viewport.x(w[0]).toFloat(), viewport.yFromWorld(w[1]).toFloat())
    }

    private fun rotationProjection(ox: Float, oy: Float, viewport: CanvasViewport, mapping: DrawableSpaceMapping) =
        RotationGuideProjection(Offset(ox, oy), mapping) { x, y ->
            Offset(viewport.x(x).toFloat(), viewport.yFromWorld(y).toFloat())
        }

    private fun placementScreenToLocal(
        point: Offset,
        viewport: CanvasViewport,
        mapping: DrawableSpaceMapping,
        seed: Pair<Float, Float>,
    ): Pair<Float, Float> {
        val world = floatArrayOf(
            ((point.x - viewport.offsetX) / viewport.scale).toFloat(),
            -((point.y - viewport.offsetY) / viewport.scale).toFloat(),
        )
        val out = mapping.worldToLocalLinearized(world, floatArrayOf(seed.first, seed.second), world, setOf(0))
        return out[0] to out[1]
    }

    /** Screen AABB of the four projected parent-local corners (display / hit-test only). */
    fun placementScreenRect(viewport: CanvasViewport): Rect? {
        val p = placement ?: return null
        if (p.kind != CreatePlacementKind.WARP) return null
        return placementScreenRectOf(p, viewport)
    }

    private fun placementScreenRectOf(p: CreatePlacement, viewport: CanvasViewport): Rect? {
        if (p.kind != CreatePlacementKind.WARP) return null
        val mapping = placementMapping(p.spaceParentId)
        val corners = listOf(
            p.localX to p.localY,
            p.localX + p.localW to p.localY,
            p.localX to p.localY + p.localH,
            p.localX + p.localW to p.localY + p.localH,
        ).map { (lx, ly) -> placementLocalToScreen(lx, ly, viewport, mapping) }
        return Rect(
            corners.minOf { it.x },
            corners.minOf { it.y },
            corners.maxOf { it.x },
            corners.maxOf { it.y },
        )
    }

    fun placementPivotScreen(viewport: CanvasViewport): Pair<Offset, Offset>? {
        val p = placement?.takeIf { it.kind == CreatePlacementKind.ROTATION } ?: return null
        val mapping = placementMapping(p.spaceParentId)
        val projection = rotationProjection(p.originX, p.originY, viewport, mapping)
        return projection.toScreen(Offset(p.originX, p.originY)) to
            projection.toScreen(Offset(p.tipX, p.tipY))
    }

    private fun hitPlacementHandle(pos: Offset, viewport: CanvasViewport): PlacementHandle {
        val p = placement ?: return PlacementHandle.NONE
        when (p.kind) {
            CreatePlacementKind.WARP -> {
                val r = placementScreenRect(viewport) ?: return PlacementHandle.NONE
                val hs = 8f
                fun near(x: Float, y: Float) = (pos - Offset(x, y)).getDistance() <= hs
                if (near(r.left, r.top)) return PlacementHandle.NW
                if (near(r.right, r.top)) return PlacementHandle.NE
                if (near(r.left, r.bottom)) return PlacementHandle.SW
                if (near(r.right, r.bottom)) return PlacementHandle.SE
                if (near(r.center.x, r.top)) return PlacementHandle.N
                if (near(r.center.x, r.bottom)) return PlacementHandle.S
                if (near(r.left, r.center.y)) return PlacementHandle.W
                if (near(r.right, r.center.y)) return PlacementHandle.E
                if (r.contains(pos)) return PlacementHandle.BODY
                return PlacementHandle.NONE
            }
            CreatePlacementKind.ROTATION -> {
                val pivots = placementPivotScreen(viewport) ?: return PlacementHandle.NONE
                val (pivot, tip) = pivots
                if ((pos - tip).getDistance() <= 10f) return PlacementHandle.TIP
                if ((pos - pivot).getDistance() <= 10f) return PlacementHandle.PIVOT
                return PlacementHandle.NONE
            }
            CreatePlacementKind.PATH -> return PlacementHandle.NONE
        }
    }

    /**
     * Drag edits parent-local state. Warp resize is done on the screen AABB then inverted with
     * corner-matched seeds (same reach-uncapped inverse as [local]); body/tip move via local deltas.
     */
    private fun applyPlacementDrag(pos: Offset, viewport: CanvasViewport, shift: Boolean) {
        val start = placementDragStart ?: return
        val snap = placementDragSnapshot ?: return
        val p = placement ?: return
        val mapping = placementMapping(snap.spaceParentId)
        when (p.kind) {
            CreatePlacementKind.WARP -> {
                when (placementHandle) {
                    PlacementHandle.BODY -> {
                        val seed = (snap.localX + snap.localW / 2f) to (snap.localY + snap.localH / 2f)
                        val a = placementScreenToLocal(start, viewport, mapping, seed)
                        val b = placementScreenToLocal(pos, viewport, mapping, seed)
                        placement = p.copy(
                            localX = snap.localX + (b.first - a.first),
                            localY = snap.localY + (b.second - a.second),
                        )
                    }
                    PlacementHandle.NONE, PlacementHandle.PIVOT, PlacementHandle.TIP -> {}
                    else -> {
                        val r0 = placementScreenRectOf(snap, viewport) ?: return
                        val dx = pos.x - start.x
                        val dy = pos.y - start.y
                        var left = r0.left
                        var top = r0.top
                        var right = r0.right
                        var bottom = r0.bottom
                        val minPx = 8f
                        when (placementHandle) {
                            PlacementHandle.E -> right = (r0.right + dx).coerceAtLeast(left + minPx)
                            PlacementHandle.W -> left = (r0.left + dx).coerceAtMost(right - minPx)
                            PlacementHandle.N -> top = (r0.top + dy).coerceAtMost(bottom - minPx)
                            PlacementHandle.S -> bottom = (r0.bottom + dy).coerceAtLeast(top + minPx)
                            PlacementHandle.NE -> {
                                right = (r0.right + dx).coerceAtLeast(left + minPx)
                                top = (r0.top + dy).coerceAtMost(bottom - minPx)
                            }
                            PlacementHandle.NW -> {
                                left = (r0.left + dx).coerceAtMost(right - minPx)
                                top = (r0.top + dy).coerceAtMost(bottom - minPx)
                            }
                            PlacementHandle.SE -> {
                                right = (r0.right + dx).coerceAtLeast(left + minPx)
                                bottom = (r0.bottom + dy).coerceAtLeast(top + minPx)
                            }
                            PlacementHandle.SW -> {
                                left = (r0.left + dx).coerceAtMost(right - minPx)
                                bottom = (r0.bottom + dy).coerceAtLeast(top + minPx)
                            }
                            else -> {}
                        }
                        val local = screenAabbToLocalBounds(Rect(left, top, right, bottom), viewport, snap, mapping)
                        placement = p.copy(
                            localX = local[0], localY = local[1], localW = local[2], localH = local[3],
                        )
                    }
                }
            }
            CreatePlacementKind.ROTATION -> {
                when (placementHandle) {
                    PlacementHandle.PIVOT -> {
                        val seed = snap.originX to snap.originY
                        val a = placementScreenToLocal(start, viewport, mapping, seed)
                        val b = placementScreenToLocal(pos, viewport, mapping, seed)
                        val ddx = b.first - a.first
                        val ddy = b.second - a.second
                        placement = p.copy(
                            originX = snap.originX + ddx, originY = snap.originY + ddy,
                            tipX = snap.tipX + ddx, tipY = snap.tipY + ddy,
                        )
                    }
                    PlacementHandle.TIP -> {
                        val projection = rotationProjection(snap.originX, snap.originY, viewport, mapping)
                        val tip = if (shift) {
                            val screenOrigin = projection.toScreen(Offset(snap.originX, snap.originY))
                            val screenTip = projection.toScreen(Offset(snap.tipX, snap.tipY))
                            val screenLen = (screenTip - screenOrigin).getDistance().coerceAtLeast(1e-4f)
                            val tipScreen = CanvasGestureGeometry.direction(screenOrigin, pos, screenLen, snap = true)
                            projection.toLocal(tipScreen)
                        } else {
                            projection.toLocal(pos)
                        }
                        placement = p.copy(tipX = tip.x, tipY = tip.y)
                    }
                    else -> {}
                }
            }
            else -> {}
        }
    }

    /** Invert a screen AABB into parent-local bounds, seeding each corner from the nearest snap corner. */
    private fun screenAabbToLocalBounds(
        rect: Rect,
        viewport: CanvasViewport,
        snap: CreatePlacement,
        mapping: DrawableSpaceMapping,
    ): FloatArray {
        val localCorners = listOf(
            snap.localX to snap.localY,
            snap.localX + snap.localW to snap.localY,
            snap.localX to snap.localY + snap.localH,
            snap.localX + snap.localW to snap.localY + snap.localH,
        )
        val projected = localCorners.map { (lx, ly) ->
            placementLocalToScreen(lx, ly, viewport, mapping) to (lx to ly)
        }
        val screenCorners = listOf(
            Offset(rect.left, rect.top),
            Offset(rect.right, rect.top),
            Offset(rect.left, rect.bottom),
            Offset(rect.right, rect.bottom),
        )
        val locals = screenCorners.map { sc ->
            val seed = projected.minBy { (proj, _) -> (proj - sc).getDistance() }.second
            placementScreenToLocal(sc, viewport, mapping, seed)
        }
        val xs = locals.map { it.first }
        val ys = locals.map { it.second }
        val x = xs.min()
        val y = ys.min()
        return floatArrayOf(
            x,
            y,
            (xs.max() - x).coerceAtLeast(1e-6f),
            (ys.max() - y).coerceAtLeast(1e-6f),
        )
    }

    /** Exit paint into Select so a creation tool can be armed without a paint session open. */
    private fun leavePaintForCreation() {
        deferredMode = null
        cancel()
        discardPaintSession()
        hierarchyMode = EditHierarchyMode.SELECT
        vertices = emptySet()
        clearHover()
    }

    /** Wait in Select for a mesh pick, then re-arm [tool]. */
    private fun deferCreation(tool: CanvasTool) {
        val request = DeferredMode(EditHierarchyMode.SELECT, tool)
        if (hierarchyMode != EditHierarchyMode.SELECT) {
            deferredMode = null
            cancel()
            hierarchyMode = EditHierarchyMode.SELECT
            vertices = emptySet()
            clearHover()
        } else {
            cancel()
        }
        this.tool = CanvasTool.SELECT
        deferredMode = request
    }

    /**
     * A mode nothing is selected for is asked for, not entered: the request is remembered and the canvas
     * stays in object mode, which is the mode that can answer the prompt it raises.
     */
    @JvmName("changeHierarchyMode")
    fun setHierarchyMode(next: EditHierarchyMode) {
        if (busy) return
        if (!hasPartFor(next)) { deferMode(next, null); return }
        enterMode(next)
    }

    /**
     * Whether [mode] has the part it works on.
     *
     * Object mode is the one that needs nothing — it is what the canvas does without a selection. The
     * other three each work on a part, and asking [target] is what keeps this the same test the canvas
     * picks with: a layer that is hidden, locked or carries no mesh is not something they could act on,
     * so they wait for one that is.
     */
    private fun hasPartFor(mode: EditHierarchyMode): Boolean = when {
        mode == EditHierarchyMode.SELECT -> true
        // No rig, no part: the canvas that would pick one is not there either, and there is no model for
        // [target] to read. The request waits, which is what it does anyway.
        state.previewModel == null -> false
        // Paint repaints one layer's pixels. A deformer is a target these modes can edit but not paint, and
        // entering paint mode on one would leave the session and the tools alike with nothing to draw on.
        mode == EditHierarchyMode.PAINT -> target(deformerId = null)?.kind == "mesh"
        else -> target() != null
    }

    /**
     * The mode a tool belongs to when the current palette does not offer it.
     * Creation tools are handled separately and never force Edit.
     */
    private fun modeForTool(tool: CanvasTool): EditHierarchyMode = when {
        tool in PAINT_TOOLS -> EditHierarchyMode.PAINT
        tool == CanvasTool.KNIFE || tool == CanvasTool.SUBDIVIDE -> EditHierarchyMode.EDIT
        else -> EditHierarchyMode.DEFORM
    }

    /**
     * Remembers [next] for the first part the user selects, and puts object mode in force to make that
     * selection: object mode is the one that picks, so the request waits in the mode that answers it.
     */
    private fun deferMode(next: EditHierarchyMode, tool: CanvasTool?) {
        val request = DeferredMode(next, tool)
        enterMode(EditHierarchyMode.SELECT)
        deferredMode = request
    }

    /**
     * Enters the mode a request was waiting for, now that its part is selected.
     */
    fun resolveDeferredMode() {
        val request = deferredMode ?: return
        if (busy) return
        if (request.tool in CREATION_TOOLS) {
            if (request.tool == CanvasTool.GLUE) {
                deferredMode = null
                activateCreationTool(request.tool!!)
                return
            }
            if (request.tool == CanvasTool.CREATE_WARP && warpAddTo == WarpAddTo.CHILD_OF_SELECTED_DEFORMER) {
                if (state.selectedDeformerId == null) return
                deferredMode = null
                activateCreationTool(request.tool!!)
                return
            }
            if (target()?.kind != "mesh") return
            deferredMode = null
            activateCreationTool(request.tool!!)
            return
        }
        if (!hasPartFor(request.mode)) return
        enterMode(request.mode)
        request.tool?.let { activateTool(it) }
    }

    /**
     * Puts [next] in force. Creation tools are disarmed when the mode changes — they belong to the
     * create session, not to a hierarchy mode.
     */
    private fun enterMode(next: EditHierarchyMode) {
        if (busy) return
        deferredMode = null
        createSessionReturnMode = null
        cancel()
        val prev = hierarchyMode
        hierarchyMode = next
        if (prev == EditHierarchyMode.PAINT && next != EditHierarchyMode.PAINT) {
            discardPaintSession()
        }
        if (next == EditHierarchyMode.PAINT) {
            startPaintSession(forceReload = true)
        } else if (next == EditHierarchyMode.SELECT) {
            vertices = emptySet()
        } else if (next == EditHierarchyMode.DEFORM && editLevel == 2) {
            ensureBezierState()
        }
        if (tool !in toolbarGroups(next).flatten()) {
            tool = CanvasTool.SELECT
            if (objectMode) vertices = emptySet()
        }
        // Mode only seeds display presets — toggles stay fully user-controlled afterwards.
        viewModel.applyHierarchyModeViewPreset(next)
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
    private var bezierTargetId: String? = null
    private var bezierSourcePoints: FloatArray? = null

    fun ensureBezierState() {
        val t = target()
        if (t != null && t.kind == "warp") {
            val warp = model.deformers.filterIsInstance<Deformer.Warp>().firstOrNull { it.id.raw == t.id }
            if (warp != null) {
                val rows = warp.rows
                val cols = warp.columns
                val (bRows, bCols) = warpBezierDivisions[t.id] ?: (2 to 2)
                val cur = bezierState
                if (cur == null || bezierTargetId != t.id || cur.bezierRows != bRows || cur.bezierCols != bCols ||
                    (!dragging && !busy && bezierSourcePoints?.contentEquals(t.geometry.points) != true)) {
                    val bState = BezierDeformerState(bRows, bCols)
                    bState.initFromLattice(t.geometry.points, rows, cols)
                    bezierState = bState
                    bezierTargetId = t.id
                    bezierSourcePoints = t.geometry.points.copyOf()
                }
            }
        } else {
            bezierState = null
        }
    }

    fun clearHover() {
        cursor = null
        altHeld = false
        hoveredVertex = null
        hoveredPathPoint = null
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
        if (hierarchyMode == EditHierarchyMode.SELECT) return null
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

    private var cachedGeometrySource: PuppetModel? = null
    private var cachedGeometryPose = emptyMap<ParameterId, Float>()
    private var cachedGeometry: DeformedGeometry? = null

    private var warpOutlineSource: PuppetModel? = null
    private var warpOutlinePose = emptyMap<ParameterId, Float>()
    private var warpOutlineIds = emptySet<String>()
    private var warpOutlinePoints = emptyMap<String, FloatArray>()

    /** The rig the canvas is drawing right now: the gesture's preview when one is live, else the rig. */
    private val drawnPreview: RigPreviewModel?
        get() = state.previewModel?.let { source -> preview?.let { source.copy(rig = source.rig.copy(puppet = it)) } ?: source }

    /**
     * The pose, evaluated once per (rig, pose) rather than once per caller.
     *
     * Hit-testing wants the same deformation more than once on a single pointer move, and a full CPU
     * evaluation is far too much to run per caller. Keyed on the puppet the canvas is actually drawing,
     * so a gesture's preview is what gets measured, exactly as the painter measures it.
     */
    private fun evaluatedGeometry(): DeformedGeometry? {
        val drawn = drawnPreview ?: return null
        if (cachedGeometrySource !== drawn.rig.puppet || cachedGeometryPose != state.parameterValues) {
            cachedGeometrySource = drawn.rig.puppet
            cachedGeometryPose = state.parameterValues
            cachedGeometry = RigCanvasSupport.evaluate(drawn, state.parameterValues)
        }
        return cachedGeometry
    }

    /** Layers under [pos], in the order a Ctrl-click cycles them. Empty when nothing is pickable. */
    private fun layerCandidates(pos: Offset, viewport: CanvasViewport): List<String> {
        val source = state.previewModel ?: return emptyList()
        val geometry = evaluatedGeometry() ?: return emptyList()
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

    /**
     * The corner mark of each warp the canvas is drawing a guide for, by deformer id, in screen space,
     * in the order they are painted.
     *
     * Read by the pick. The painting does not come through here — [RigInformationOverlay] draws the mark
     * inside the very loop that draws the lattice, off the very points it draws it with, which is what
     * makes the mark part of the deformer instead of a layer sitting on top of it. This is the same
     * geometry, asked for by the same rule and fetched from the same probe, so the two cannot disagree
     * about where a mark is or whether there is one.
     */
    fun deformerCorners(viewport: CanvasViewport): Map<String, java.awt.geom.Area> {
        val source = drawnPreview?.rig?.puppet ?: return emptyMap()
        val ids = activeWarpIds()
        if (ids.isEmpty()) return emptyMap()
        return RigCanvasSupport.deformerCorners(RigCanvasSupport.deformerOutlines(source, warpOutlinePoints(source, ids)), viewport)
    }

    /**
     * The warps the canvas is drawing guides for right now.
     *
     * This rule used to live in the viewport, which was enough while the viewport was the only thing
     * that cared. The corner marks care too, and a mark that outlives the deformer it belongs to is
     * exactly what a second copy of this rule produces — so it lives here, where the painter and the
     * pick both ask it and cannot get different answers.
     *
     * A selected mesh shows none of them, a selected deformer shows itself and everything under it,
     * and with nothing selected the whole rig shows unless the tab asked for the selection only. The
     * channel as a whole stays off unless the tab wants it or a warp is involved, so an untouched rig
     * in a tab without the option stays as clean as it was.
     */
    fun activeWarpIds(): Set<String> {
        val preview = drawnPreview ?: return emptySet()
        val puppet = preview.rig.puppet
        val warps = puppet.deformers.filterIsInstance<Deformer.Warp>().map { it.id.raw }.toSet()
        if (warps.isEmpty()) return emptySet()
        val hovered = state.hoveredDeformerId?.takeIf { it in warps }
        if (!state.showWarp && state.selectedDeformerId == null && hovered == null) return emptySet()
        // A mesh is being edited, and the warps that shape it are not what the artist is looking at.
        if (state.selectedLayerId != null) return emptySet()

        val selected = state.selectedDeformerId
        val base = if (selected == null) {
            if (state.filterSelectedOnly) emptySet() else warps
        } else {
            // The deformer and everything under it — the chain that moves when it does.
            val under = mutableSetOf(selected)
            var grew = true
            while (grew) {
                grew = false
                for (deformer in puppet.deformers) {
                    if (deformer.id.raw in under) continue
                    val parent = state.parentOverrides[deformer.id.raw] ?: deformer.parent?.raw
                    if (parent in under) {
                        under.add(deformer.id.raw)
                        grew = true
                    }
                }
            }
            under.filter { it in warps }.toSet()
        }
        return (if (hovered != null) base + hovered else base).filter { state.isDeformerVisible(it) }.toSet()
    }

    /**
     * The rotations the canvas is drawing global guides for right now.
     * [TabViewOptions.showRotation] owns the channel — hierarchy mode only seeds presets, never forces.
     */
    fun activeRotationIds(): Set<String> {
        val preview = drawnPreview ?: return emptySet()
        val puppet = preview.rig.puppet
        val rotations = puppet.deformers.filterIsInstance<Deformer.Rotation>().map { it.id.raw }.toSet()
        if (rotations.isEmpty() || !state.showRotation) return emptySet()
        val hovered = state.hoveredDeformerId?.takeIf { it in rotations }
        // A mesh is being edited — rotation guides are not what the artist is looking at.
        if (state.selectedLayerId != null) return emptySet()

        val selected = state.selectedDeformerId
        val base = if (selected == null) {
            if (state.filterSelectedOnly) emptySet() else rotations
        } else {
            val under = mutableSetOf(selected)
            var grew = true
            while (grew) {
                grew = false
                for (deformer in puppet.deformers) {
                    if (deformer.id.raw in under) continue
                    val parent = state.parentOverrides[deformer.id.raw] ?: deformer.parent?.raw
                    if (parent in under) {
                        under.add(deformer.id.raw)
                        grew = true
                    }
                }
            }
            under.filter { it in rotations }.toSet()
        }
        return (if (hovered != null) base + hovered else base).filter { state.isDeformerVisible(it) }.toSet()
    }

    /**
     * Each shown warp's lattice in canvas space, fetched through the same probe the lattice channel
     * draws from — so a mark is placed off the very geometry the deformer is drawn with, rather than off
     * a second opinion about it.
     *
     * Cached against the pose and the shown set, because the probe evaluates a deformation per warp and
     * the pointer asks on every move.
     */
    private fun warpOutlinePoints(source: PuppetModel, ids: Set<String>): Map<String, FloatArray> {
        if (warpOutlineSource !== source || warpOutlinePose != state.parameterValues || warpOutlineIds != ids) {
            warpOutlineSource = source
            warpOutlinePose = state.parameterValues
            warpOutlineIds = ids
            warpOutlinePoints = RigInformationOverlay.warpPoints(source, state.parameterValues, ids)
        }
        return warpOutlinePoints
    }

    /**
     * The deformer whose corner mark is under [pos], or null when the pointer is between them.
     *
     * No ordering to it: each mark is only the band of it that is not covered by a smaller one, so the
     * bands do not overlap and whichever contains the pointer is the one the artist is pointing at —
     * which is the same thing as the one they can see.
     */
    private fun badgeAt(pos: Offset, viewport: CanvasViewport): String? =
        deformerCorners(viewport).entries.firstOrNull { it.value.contains(pos.x.toDouble(), pos.y.toDouble()) }?.key

    fun updateHover(pos: Offset, viewport: CanvasViewport, ctrl: Boolean = false, shift: Boolean = false) {
        cursor = pos
        if (tool == CanvasTool.KNIFE) {
            target()?.takeIf { it.kind == "mesh" }?.let { knifeAnchor(pos, it, viewport, shift) }
        }
        if (tool == CanvasTool.SUBDIVIDE && !dragging) {
            subdivideEdges = target()?.takeIf { it.kind == "mesh" }?.let { edgesWithin(pos, pos, it, viewport) }.orEmpty()
        }
        if (dragging) return
        hoveredBezierAnchor = null
        hoveredBezierHandle = null
        hoveredVertex = null
        hoveredPathPoint = null
        hoveredHandle = BoundingHandle.NONE
        isHoveringObject = false
        hoveredPick = null

        if (tool in CREATION_TOOLS) {
            if (tool == CanvasTool.GLUE) {
                glueHoverMesh = layerCandidates(pos, viewport).firstOrNull { it != glueFirstMesh }
            } else if (tool == CanvasTool.CREATE_DEFORM_PATH) {
                val t = target()
                if (t != null && t.kind == "mesh") {
                    hoveredPathPoint = pathControlHit(pos, t, viewport)?.second
                }
            }
            return
        }

        // Object mode has no transform box, so no handle is ever live here. What the pointer is over is
        // the pick itself, and resolving it through the same call the press makes is what guarantees the
        // annotation names the thing a click would actually select — Ctrl included.
        if (hierarchyMode == EditHierarchyMode.SELECT) {
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
                if (paths().isNotEmpty()) {
                    val hit = pathControlHit(pos, t, viewport, onlyActive = activePath != null)
                        ?: pathControlHit(pos, t, viewport, onlyActive = false)
                    if (hit != null) {
                        hoveredPathPoint = hit.second
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

        if (hierarchyMode == EditHierarchyMode.EDIT) {
            if (t.kind == "mesh" && paths().isNotEmpty()) {
                val hit = pathControlHit(pos, t, viewport, onlyActive = activePath != null)
                    ?: pathControlHit(pos, t, viewport, onlyActive = false)
                if (hit != null) {
                    hoveredPathPoint = hit.second
                    return
                }
            }
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
            if (tool in DEFORM_BRUSH_TOOLS || tool == CanvasTool.BRUSH_SELECT || tool == CanvasTool.SUBDIVIDE || tool == CanvasTool.KNIFE) return cross
            if (boxDrag) return handleCursor(activeHandle)
            if (activeBezierAnchor != null || activeBezierHandle != null) return hand
            return move
        }
        if (tool in CREATION_TOOLS) {
            return if (tool == CanvasTool.GLUE) hand else cross
        }
        if (tool == CanvasTool.BRUSH_SELECT || tool in DEFORM_BRUSH_TOOLS || tool == CanvasTool.SUBDIVIDE || tool == CanvasTool.KNIFE) return cross
        if (tool == CanvasTool.LASSO_SELECT) return cross
        // Painting gets no crosshair: the cursor is exactly where the tip ring already is, and a cross
        // over the pixels being judged is worse than no mark at all.

        if (hoveredBezierHandle != null || hoveredBezierAnchor != null) return hand
        if (hoveredHandle != BoundingHandle.NONE) return handleCursor(hoveredHandle)
        if (hoveredVertex != null || hoveredPathPoint != null) return hand

        if (hierarchyMode == EditHierarchyMode.SELECT) {
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
    private fun commitBatch(commands: List<JsonObject>, onSuccess: (() -> Unit)? = null): Boolean {
        // The provisional fill describes faces of the mesh as it stands; after any commit those indices
        // may mean something else, so it lives exactly as long as the op that set it. topology() sets it
        // after calling through here, which is why clearing on the way in is enough.
        topologyFills = null
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
                if (failure == null) onSuccess?.invoke()
                if (failure == null) commands.lastOrNull { it["op"]?.jsonPrimitive?.content in setOf("canvas_create_warp", "canvas_create_rotation") }?.let {
                    finishCreateSession(it.getValue("id").jsonPrimitive.content)
                }
            }
        } catch (e: Exception) { error = e.message; preview = null; pending = null; head = null; endTransformBox() }
        return true
    }

    /**
     * Runs a topology op on the mesh selection.
     *
     * The selection afterwards is "what the op made", which the op knows and the journal does not carry -
     * a [org.umamo.edit.TopologyOpResult] has no way back through a persisted JSON command. So the op is
     * built here first, against the same mesh the reducer will read, and its result picks the selection
     * and marks the provisional fill. It is a pure function, so the answer the reducer computes is the
     * same one; see CanvasTopology.
     */
    fun topology(action: String, selection: Set<Int> = vertices, edges: Set<MeshElement.Edge> = emptySet()) {
        val t = target() ?: return
        if (t.kind != "mesh" || !editable) return
        head = null
        val selected = selection
        val chosenEdges = if (edges.isNotEmpty()) edges else if (action in setOf("subdivide", "split")) {
            when (elementMode) {
                1 -> selectedEdges
                2 -> selectedFaces.flatMapTo(LinkedHashSet()) { face ->
                    if (face in 0 until t.indices.size / 3) MeshTopology.edgesOfTriangle(t.indices, face) else emptyList()
                }
                else -> emptySet()
            }
        } else emptySet()
        // Against state.previewModel, which is what commitBatch compiles against - not `model`, which
        // prefers the in-flight `preview` and would disagree with the reducer mid-drag.
        val mesh = state.previewModel?.rig?.puppet?.drawables?.firstOrNull { it.id.raw == t.id }?.mesh
        val outcome = mesh?.let { runCatching { CanvasTopology.build(it, action, selected, edges = chosenEdges) }.getOrNull() }
        commitBatch(listOf(buildJsonObject { put("op", "canvas_topology"); put("id", t.id); put("action", action); put("vertices", JsonArray(selected.map(::JsonPrimitive)))
            if (chosenEdges.isNotEmpty()) put("edges", JsonArray(chosenEdges.map { JsonArray(listOf(JsonPrimitive(it.endpointLow), JsonPrimitive(it.endpointHigh))) })) })) {
            vertices = CanvasTopology.selectedVertices(outcome)
            selectedEdges = emptySet(); selectedFaces = emptySet()
            topologyFills = CanvasTopology.createdFaces(outcome).takeIf { it.isNotEmpty() }?.let { TopologyFill(t.id, it) }
        }
    }

    /** Commits the knife polyline. All or nothing - see [MeshRefinementOps.knifeCut]. */
    fun finishKnife() {
        val t = target() ?: return
        if (!editable || t.kind != "mesh" || t.id != knifeDrawableId || knifeDraft.size < 2) return
        val anchors = knifeDraft
        head = null
        val mesh = state.previewModel?.rig?.puppet?.drawables?.firstOrNull { it.id.raw == t.id }?.mesh
        val outcome = mesh?.let { runCatching { CanvasTopology.build(it, "knife", emptySet(), anchors) }.getOrNull() }
        if (outcome == null) {
            error = tr("editor.knifeCannotConnect")
            return
        }
        commitBatch(listOf(buildJsonObject {
            put("op", "canvas_topology"); put("id", t.id); put("action", "knife")
            put("vertices", JsonArray(emptyList()))
            put("anchors", CanvasTopology.encodeAnchors(anchors))
        })) {
            knifeDraft = emptyList()
            vertices = CanvasTopology.selectedVertices(outcome)
            selectedEdges = emptySet(); selectedFaces = emptySet()
            topologyFills = CanvasTopology.createdFaces(outcome).takeIf { it.isNotEmpty() }?.let { TopologyFill(t.id, it) }
        }
    }

    /**
     * Resolve both hover and press through the same screen-space snap, then store mesh-space anchors.
     *
     * Snapping is not optional: a vertex within [knifeSnapRadius] wins, then an edge, and anything further
     * away lands as a new free point. Edges take the point on them under the pointer - never their midpoint.
     * [bypass] is the Shift escape hatch, for the free point that has to sit on top of a vertex.
     */
    private fun knifeAnchor(pos: Offset, t: CanvasTarget, viewport: CanvasViewport, bypass: Boolean = false): MeshRefinementOps.KnifeAnchor {
        val points = screen(t.geometry.points, t, viewport)
        knifeSnapKind = null
        knifeHover = pos
        if (!bypass) {
            val nearest = points.indices.minByOrNull { (points[it] - pos).getDistance() }
            if (nearest != null && (points[nearest] - pos).getDistance() <= knifeSnapRadius) {
                knifeHover = points[nearest]; knifeSnapKind = "vertex"
                return MeshRefinementOps.KnifeAnchor.AtVertex(nearest)
            }
            val edge = MeshTopology.uniqueEdges(t.indices).map { edge ->
                val a = points[edge.endpointLow]; val b = points[edge.endpointHigh]
                val fraction = CanvasGestureGeometry.project(pos, a, b)
                Triple(edge, fraction, a + (b - a) * fraction)
            }.minByOrNull { (it.third - pos).getDistance() }
            if (edge != null && (edge.third - pos).getDistance() <= knifeSnapRadius) {
                knifeHover = edge.third; knifeSnapKind = "edge"
                val a = edge.first.endpointLow * 2; val b = edge.first.endpointHigh * 2
                val geometry = t.geometry.base
                // Journal anchors are in rest-mesh space, even while a parameter pose is displayed.
                return MeshRefinementOps.KnifeAnchor.AtPoint(
                    geometry[a] + (geometry[b] - geometry[a]) * edge.second,
                    geometry[a + 1] + (geometry[b + 1] - geometry[a + 1]) * edge.second)
            }
        }
        val (x, y) = local(pos, t, viewport)
        val rest = runCatching {
            DeformPathTools.bind(t.geometry.points, t.indices, x, y).position(t.geometry.base)
        }.getOrElse { x to y }
        return MeshRefinementOps.KnifeAnchor.AtPoint(rest.first, rest.second)
    }

    fun knifeAnchorScreen(anchor: MeshRefinementOps.KnifeAnchor, t: CanvasTarget, viewport: CanvasViewport): Offset? =
        when (anchor) {
            is MeshRefinementOps.KnifeAnchor.AtVertex -> screen(t.geometry.points, t, viewport).getOrNull(anchor.index)
            is MeshRefinementOps.KnifeAnchor.AtPoint -> {
                val posed = runCatching {
                    DeformPathTools.bind(t.geometry.base, t.indices, anchor.x, anchor.y).position(t.geometry.points)
                }.getOrElse { anchor.x to anchor.y }
                screen(floatArrayOf(posed.first, posed.second), t, viewport).firstOrNull()
            }
        }

    /** The target's vertices inside the brush radius, in screen space. */
    private fun edgesWithin(start: Offset, end: Offset, t: CanvasTarget, viewport: CanvasViewport): Set<MeshElement.Edge> {
        val r = (radius * viewport.scale).toFloat()
        val points = screen(t.geometry.points, t, viewport)
        return MeshTopology.uniqueEdges(t.indices).filterTo(LinkedHashSet()) { edge ->
            CanvasGestureGeometry.segmentDistance(start, end, points[edge.endpointLow], points[edge.endpointHigh]) <= r
        }
    }

    fun finishPath() {
        val t = target() ?: return
        if (draft.size < 2 || t.kind != "mesh") return
        try {
            val extent = RigGeometryTools.bounds(t.geometry.points).let { max(it[2], it[3]) }
            val previous = paths().firstOrNull { it.id == draftPathId }
            val points = draft.mapIndexed { i, p -> DeformPathTools.bind(t.geometry.points, t.indices, p.first, p.second, previous?.points?.getOrNull(i)?.corner ?: false) }
            val path = previous?.copy(points = points, closed = if (previous.closed) previous.closed else pathClosed)
                ?: DeformPath(
                    UUID.randomUUID().toString(),
                    DrawableId(t.id),
                    points,
                    extent * pathWidth,
                    hardness = pathHardness,
                    closed = pathClosed && points.size >= 3,
                    editLevel = pathLevel,
                )
            head = null; commit(DeformPathJournal.encode(path)); activePath = path.id; drawingPath = false; draft = emptyList()
            placement = null
            // After binding, enter EDIT so dragging control points rebinds without deforming.
            createSessionReturnMode = null
            if (hierarchyMode != EditHierarchyMode.EDIT) {
                hierarchyMode = EditHierarchyMode.EDIT
                viewModel.applyHierarchyModeViewPreset(EditHierarchyMode.EDIT)
            }
            tool = CanvasTool.SELECT
            pathClosed = false
            clearHover()
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
        } else target()?.let { t ->
            if (hierarchyMode == EditHierarchyMode.EDIT && t.kind == "mesh" && elementMode == 1) {
                selectedEdges = MeshTopology.uniqueEdges(t.indices).filterTo(LinkedHashSet()) { !invert || it !in selectedEdges }
                vertices = selectedEdges.flatMapTo(LinkedHashSet()) { listOf(it.endpointLow, it.endpointHigh) }
            } else if (hierarchyMode == EditHierarchyMode.EDIT && t.kind == "mesh" && elementMode == 2) {
                selectedFaces = (0 until t.indices.size / 3).filterTo(LinkedHashSet()) { !invert || it !in selectedFaces }
                vertices = selectedFaces.flatMapTo(LinkedHashSet()) { MeshTopology.verticesOfTriangle(t.indices, it) }
            } else {
                vertices = (0 until t.count).filter { !invert || it !in vertices }.toSet()
            }
        }
    }

    fun selectLinked() {
        val t = target() ?: return; if (t.indices.isEmpty()) return
        val adjacency = MeshTopology.buildVertexAdjacency(t.count, t.indices)
        vertices = vertices.flatMap { MeshTopology.connectedVertices(adjacency, it) }.toSet()
    }

    fun createWarp(rotation: Boolean = false) {
        if (rotation) {
            createWarpRotationFromSelection()
            return
        }
        if (warpAddTo == WarpAddTo.CHILD_OF_SELECTED_DEFORMER) {
            createEmptyWarpUnderSelectedDeformer()
            return
        }
        val t = target() ?: return
        if (t.kind != "mesh" || !editable) return
        val targets = objects.mapNotNull { target(model, it, null) }.ifEmpty { listOf(t) }
            .filter { it.kind == "mesh" }
        if (targets.isEmpty()) return
        val parents = targets.map { it.let { tg -> model.drawables.first { d -> d.id.raw == tg.id }.parentDeformerId } }.distinct()
        if (parents.size != 1) {
            error = tr("editor.createWarpSameParent")
            return
        }
        val name = defaultCreateName(targets.first().id, rotation = false)
        val id = "Warp_${UUID.randomUUID()}"
        warpBezierDivisions[id] = warpCreateBezierRows to warpCreateBezierCols
        head = null
        commit(buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", id); put("name", name)
            put("rows", warpCreateGridRows); put("columns", warpCreateGridCols)
            put("size_strategy", warpSizeStrategy.name.lowercase())
            putCreationPlacement(this, targets.map { it.id })
            put("meshes", JsonArray(targets.map { JsonPrimitive(it.id) }))
        })
    }

    private fun createWarpRotationFromSelection() {
        val t = target() ?: return
        if (t.kind != "mesh" || !editable) return
        val name = defaultCreateName(t.id, rotation = true)
        val id = "Rotation_${UUID.randomUUID()}"
        val targets = objects.mapNotNull { target(model, it, null) }.ifEmpty { listOf(t) }
            .filter { it.kind == "mesh" }
        if (targets.isEmpty()) return
        // Origin in the meshes' shared parent-local space (UV under Warp, etc.).
        val locals = targets.flatMap { it.geometry.points.toList().chunked(2) }
        head = null
        commit(buildJsonObject {
            put("op", "canvas_create_rotation")
            put("id", id); put("name", name); put("preservePose", true)
            put("add_to", "parent_of_selected")
            if (locals.isNotEmpty()) put(
                "origin",
                JsonArray(
                    listOf(
                        (locals.minOf { it[0] } + locals.maxOf { it[0] }) / 2f,
                        (locals.minOf { it[1] } + locals.maxOf { it[1] }) / 2f,
                    ).map(::JsonPrimitive),
                ),
            )
            put("angle", 0f)
            putCreationPartId(this, targets.map { it.id })
            put("meshes", JsonArray(targets.map { JsonPrimitive(it.id) }))
        })
    }

    private fun createEmptyWarpUnderSelectedDeformer() {
        val parentId = state.selectedDeformerId ?: return
        if (!editable) return
        val parent = model.deformers.firstOrNull { it.id.raw == parentId } ?: return
        val name = tr("editor.defaultWarpName", parent.name)
        val id = "Warp_${UUID.randomUUID()}"
        warpBezierDivisions[id] = warpCreateBezierRows to warpCreateBezierCols
        head = null
        commit(buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", id); put("name", name)
            put("rows", warpCreateGridRows); put("columns", warpCreateGridCols)
            put("add_to", "child_of_deformer")
            put("parent_id", parentId)
            putCreationPartId(this, emptyList(), fallbackDeformerPart = parent.partId?.raw)
            put("meshes", JsonArray(emptyList()))
        })
    }

    fun createWarpFromBounds(s: Offset, e: Offset, viewport: CanvasViewport) {
        val target = target()?.takeIf { it.kind == "mesh" } ?: return
        val corners = listOf(s, e, Offset(s.x, e.y), Offset(e.x, s.y)).map { local(it, target, viewport) }
        val wX = corners.minOf { it.first }; val wY = corners.minOf { it.second }
        val wW = corners.maxOf { it.first } - wX; val wH = corners.maxOf { it.second } - wY

        val targetMeshes = objects.mapNotNull { target(model, it, null)?.id }.ifEmpty { listOfNotNull(target()?.takeIf { it.kind == "mesh" }?.id) }
        if (targetMeshes.isEmpty()) return
        val id = "Warp_${UUID.randomUUID()}"
        val name = defaultCreateName(targetMeshes.first(), rotation = false)
        warpBezierDivisions[id] = warpCreateBezierRows to warpCreateBezierCols
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", id)
            put("name", name)
            put("rows", warpCreateGridRows)
            put("columns", warpCreateGridCols)
            put("bounds", buildJsonObject {
                put("x", wX)
                put("y", wY)
                put("w", wW)
                put("h", wH)
            })
            putCreationPlacement(this, targetMeshes)
            put("meshes", JsonArray(targetMeshes.map(::JsonPrimitive)))
        }
        head = null
        commit(cmd)
    }

    fun createRotationFromPoints(s: Offset, e: Offset, viewport: CanvasViewport) {
        val meshTarget = target()?.takeIf { it.kind == "mesh" } ?: return
        val origin = local(s, meshTarget, viewport)
        val endpoint = rotationProjection(origin.first, origin.second, viewport, meshTarget.mapping).toLocal(e)
        val tip = endpoint.x to endpoint.y
        val angleDeg = Math.toDegrees(atan2((tip.second - origin.second).toDouble(), (tip.first - origin.first).toDouble())).toFloat()

        val targetMeshes = objects.mapNotNull { target(model, it, null)?.id }.ifEmpty { listOf(meshTarget.id) }
        if (targetMeshes.isEmpty()) return
        val id = "Rotation_${UUID.randomUUID()}"
        val name = defaultCreateName(targetMeshes.first(), rotation = true)
        val cmd = buildJsonObject {
            put("op", "canvas_create_rotation")
            put("preservePose", true)
            put("add_to", "parent_of_selected")
            put("id", id)
            put("name", name)
            put("origin", JsonArray(listOf(origin.first, origin.second).map(::JsonPrimitive)))
            put("angle", angleDeg)
            put("handle_length", hypot(tip.first - origin.first, tip.second - origin.second).coerceAtLeast(1e-4f))
            putCreationPartId(this, targetMeshes)
            put("meshes", JsonArray(targetMeshes.map(::JsonPrimitive)))
        }
        head = null
        commit(cmd)
    }

    private fun defaultCreateName(meshId: String, rotation: Boolean): String {
        val meshName = model.drawables.firstOrNull { it.id.raw == meshId }?.name ?: meshId
        return if (rotation) tr("editor.defaultRotationName", meshName) else tr("editor.defaultWarpName", meshName)
    }

    private fun putCreationPlacement(obj: kotlinx.serialization.json.JsonObjectBuilder, meshIds: List<String>) {
        when (warpAddTo) {
            WarpAddTo.PARENT_OF_SELECTED -> obj.put("add_to", "parent_of_selected")
            WarpAddTo.CHILD_OF_SELECTED_DEFORMER -> {
                obj.put("add_to", "child_of_deformer")
                state.selectedDeformerId?.let { obj.put("parent_id", it) }
            }
            WarpAddTo.SPECIFY_PARENT -> {
                obj.put("add_to", "specify_parent")
                warpSpecifyParentId?.let { obj.put("parent_id", it) }
            }
        }
        putCreationPartId(obj, meshIds)
    }

    private fun putCreationPartId(
        obj: kotlinx.serialization.json.JsonObjectBuilder,
        meshIds: List<String>,
        fallbackDeformerPart: String? = null,
    ) {
        val part = warpCreatePartId
            ?: meshIds.firstOrNull()?.let { id ->
                model.partByDrawable()[org.umamo.runtime.model.DrawableId(id)]?.raw
            }
            ?: fallbackDeformerPart
        if (part != null) obj.put("part_id", part)
    }

    /**
     * After a successful Warp/Rotation create: select the new deformer and either stay armed
     * (sequential) or restore the mode the create session started from.
     */
    private fun finishCreateSession(newDeformerId: String) {
        vertices = emptySet()
        viewModel.selectDeformer(newDeformerId)
        if (sequentialCreate) {
            // Stay on the create tool; keep return mode for a later exit.
            return
        }
        val returnMode = createSessionReturnMode
        createSessionReturnMode = null
        if (returnMode != null && returnMode != hierarchyMode && returnMode != EditHierarchyMode.PAINT) {
            deferredMode = null
            hierarchyMode = returnMode
            if (returnMode == EditHierarchyMode.DEFORM && editLevel == 2) ensureBezierState()
        }
        tool = CanvasTool.SELECT
        clearHover()
    }

    /** True when the selection's shared parent is already a Warp (grid density is parent-aligned). */
    fun warpCreateParentIsWarp(): Boolean {
        val meshes = selectedCreateMeshIds()
        if (meshes.isEmpty()) return false
        val parents = model.drawables.filter { it.id.raw in meshes }.map { it.parentDeformerId }.distinct()
        if (parents.size != 1) return false
        val parent = parents.first() ?: return false
        return model.deformers.any { it.id == parent && it is Deformer.Warp }
    }

    /** Deformer and drawable ids affected by the current rotation create (scope preview). */
    fun rotationScopeIds(): Pair<Set<String>, Set<String>> {
        val place = placement?.takeIf { it.kind == CreatePlacementKind.ROTATION }
        if (place != null) {
            return when {
                place.anchorKind == "deformer" -> {
                    val under = mutableSetOf(place.anchorId)
                    val byParent = model.deformers.groupBy { it.parent?.raw }
                    val stack = ArrayDeque(listOf(place.anchorId))
                    while (stack.isNotEmpty()) {
                        val id = stack.removeFirst()
                        byParent[id].orEmpty().forEach { child ->
                            if (under.add(child.id.raw)) stack.add(child.id.raw)
                        }
                    }
                    under to model.drawables.filter { it.parentDeformerId?.raw in under }.map { it.id.raw }.toSet()
                }
                else -> emptySet<String>() to place.meshIds.toSet()
            }
        }
        val selected = selectedCreateMeshIds()
        if (selected.isEmpty()) return emptySet<String>() to emptySet()
        return emptySet<String>() to selected
    }

    private fun selectedCreateMeshIds(): Set<String> {
        val fromObjects = objects.mapNotNull { target(model, it, null)?.takeIf { t -> t.kind == "mesh" }?.id }
        if (fromObjects.isNotEmpty()) return fromObjects.toSet()
        return listOfNotNull(target()?.takeIf { it.kind == "mesh" }?.id).toSet()
    }

    fun createGlue(meshA: String, meshB: String) {
        val id = "Glue_${UUID.randomUUID()}"
        val cmd = buildJsonObject {
            put("op", "canvas_create_glue")
            put("id", id)
            put("name", "Glue")
            put("mesh_a", meshA)
            put("mesh_b", meshB)
            put("distance", glueDistance)
            put("pose", JsonObject(pose.mapValues { JsonPrimitive(it.value) }))
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
            if (drawable != null && seen.add("mesh:$layerId")) ring += HierarchyPick(layerId = layerId)
            var parent = drawable?.parentDeformerId
            while (parent != null) {
                val id = parent.raw

                if (!seen.add("deformer:$id")) break
                val deformer = source.deformers.firstOrNull { it.id == parent } ?: break
                if (target(source, null, id) == null) break
                ring += HierarchyPick(deformerId = id)
                parent = deformer.parent
            }
        }
        return ring
    }

    /**
     * What a click at [pos] picks.
     *
     * A plain click walks the stack the one-layer-per-click way the preview tab and [pickLayer] use.
     * Ctrl steps to the next node of the hierarchy instead, so the whole chain a part hangs off is
     * reachable without leaving the canvas; a Ctrl-click with nothing of the ring selected starts at
     * the top, which is what makes the first one land on the layer rather than skipping past it.
     *
     * A corner badge wins over both. It is the only way to reach a deformer that has no art of its own
     * under the pointer — which is most of them, since a deformer frames artwork rather than being it —
     * and having pointed at one deliberately, taking a step instead would be an odd answer. The
     * exception is a Ctrl-click that has somewhere to step *to*: badges are ignored so that Ctrl means
     * the same thing wherever it is pressed, rather than dead-ending on the deformer just selected.
     */
    fun objectPick(pos: Offset, viewport: CanvasViewport, ctrl: Boolean): HierarchyPick? {
        val ring = hierarchyRing(pos, viewport)
        if (!ctrl || ring.isEmpty()) {
            badgeAt(pos, viewport)?.let { return HierarchyPick(deformerId = it) }
        }
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
        if (viewModel.isSnappingParameters) return true
        this.viewport = viewport
        error = null; head = state.historySnapshot?.headNodeId; start = pos; previous = pos; dragStartPos = pos
        moved = false; additive = shift; subtractive = alt; pressedObject = null

        // 0. Paint Mode (L1 raster paint engine)
        if (hierarchyMode == EditHierarchyMode.PAINT && tool in PAINT_TOOLS) {
            val layerId = paintSession?.layerId ?: state.selectedLayerId ?: targetLayerId(paintTarget()) ?: return true
            val session = ensurePaintSession(layerId) ?: return true
            val t = target(layerId = layerId, deformerId = null) ?: paintTarget()

            targetAtPress = t
            original = model
            dragging = true
            isPainting = true
            paintStrokeStart = pos
            paintStrokeCurrent = pos
            lastPaintPoint = pos

            val canvasPos = screenToCanvasPixel(pos, viewport)

            when {
                // Picking a colour rather than drawing one: the eyedropper's own gesture, and what Alt
                // does to every paint tool. The pick follows the pointer for as long as the button is
                // down, so a colour can be scrubbed for instead of guessed at in one click.
                tool == CanvasTool.PAINT_EYEDROPPER || alt -> {
                    isSampling = true
                    // Alt means two different things here, as it does in Photoshop: on the eyedropper it
                    // fills in the secondary colour, while Alt over any other tool is the eyedropper
                    // itself, and that one picks into the foreground.
                    samplingSecondary = alt && tool == CanvasTool.PAINT_EYEDROPPER
                    pickColorAt(pos, viewport, secondary = samplingSecondary)
                }
                tool == CanvasTool.PAINT_BUCKET -> {
                    if (canvasPos != null) {
                        val clip = java.awt.Rectangle(0, 0, session.docWidth, session.docHeight)
                        LayerPaintEngine.floodFill(
                            image = session.workingImage,
                            startX = canvasPos.first,
                            startY = canvasPos.second,
                            fillColor = paintColor,
                            tolerance = paintTolerance,
                            clipRect = clip,
                            // The fill reports the ground it is about to cover, so the session can keep
                            // the pixels it replaces - and the fill stays one undoable action.
                            before = { session.willWrite(it) },
                        )
                        session.recordStroke(tr("editor.paint.strokeFill"))
                    }
                    isPainting = false
                    dragging = false
                }
                tool in PAINT_BRUSH_TOOLS -> {
                    // The tip paints as the pointer moves, the way every paint program does: what the
                    // canvas shows is the mark itself, not a stroke standing in for it. The bitmap is
                    // republished on the press whatever the throttle says: the first touch of a stroke
                    // is the one the user is watching for.
                    session.beginStroke()
                    lastPaintBitmapAt = 0L
                    applyLiveSegment(pos, pos, erase = tool == CanvasTool.PAINT_ERASER)
                }
                else -> {
                    // The shape tool: the live gesture is previewed on the overlay and lands on release,
                    // because a shape is committed when its second corner is.
                }
            }
            return true
        }

        // Creation: place-then-confirm ghost, or start path points.
        if (tool in setOf(CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION) && placement != null) {
            val handle = hitPlacementHandle(pos, viewport!!)
            if (handle != PlacementHandle.NONE) {
                placementHandle = handle
                placementDragStart = pos
                placementDragSnapshot = placement!!.copy()
                dragging = true
                return true
            }
            return true
        }
        if (tool in setOf(CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH) &&
            target()?.kind != "mesh" && placement == null) {
            pickLayer(pos, viewport)?.let { viewModel.selectLayer(it) }
            error = tr("editor.creationSelectFirst")
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
                if (drawingPath) {
                    draft = draft + local(pos, t, viewport, draft.lastOrNull() ?: (0.5f to 0.5f))
                    return true
                }
                if (beginPathInteraction(pos, t, viewport, ctrl)) return true
                activePath = null
                pathPoint = -1
                drawingPath = true
                draftPathId = null
                draft = listOf(local(pos, t, viewport))
                return true
            }
            return true
        }

        // 1b. The knife is a click tool, not a drag: each press drops one anchor and the polyline is
        //     committed with Enter. It never sets `dragging`, so no stray move can commit anything.
        if (tool == CanvasTool.KNIFE) {
            val t = target()
            if (t != null && t.kind == "mesh") {
                if (t.id != knifeDrawableId) {
                    knifeDraft = emptyList()
                    knifeDrawableId = t.id
                }
                val anchor = knifeAnchor(pos, t, viewport, shift)
                if (anchor != knifeDraft.lastOrNull()) knifeDraft = knifeDraft + anchor
            }
            return true
        }

        // 2. Level 2 Bezier Deformer in DEFORM mode
        if (hierarchyMode == EditHierarchyMode.DEFORM && editLevel == 2) {
            val t = target()
            if (t != null && t.kind == "warp") {
                if (hoveredBezierHandle != null || hoveredBezierAnchor != null) {
                    if (viewModel.snapToNearestKeys(t.kind, t.id) { press(pos, viewport, shift, alt, ctrl) }) return true
                }
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

        // 2b. A corner badge picks the deformer it belongs to, in every mode.
        //
        // Object and point selection are different operations rather than two candidates for one click:
        // the badge names *which* deformer is being worked on, and a click that is not on one falls
        // through to the points exactly as before. So the two never contend — the mark is small, it is
        // only on the corner, and pointing at it is a deliberate act.
        //
        // Behind the creation tools and behind the Bezier handles on purpose: those are clicks that mean
        // something already, and the handles in particular sit on the very corner a badge does. Ctrl is
        // left to object mode's own pick, where it means "step to the next node" and has to keep meaning
        // that wherever it is pressed.
        if (tool in SELECTION_TOOLS && !(ctrl && hierarchyMode == EditHierarchyMode.SELECT)) {
            badgeAt(pos, viewport)?.let { id ->
                applyObjectPick(HierarchyPick(deformerId = id), null)
                return true
            }
        }

        // 2c. The subdivide brush. It takes every edge whose two ends fall inside the radius, which is
        //     the same rule the panel button uses, so the two entry points cannot disagree. The mesh is
        //     frozen for the whole stroke - re-splitting an already-split edge would split the halves
        //     again - so the drag only accumulates a vertex set and the op runs once on release.
        if (tool == CanvasTool.SUBDIVIDE) {
            val t = target() ?: return true
            if (t.kind != "mesh") return true
            targetAtPress = t; original = model; dragging = true; subdividing = true
            subdivideEdges = edgesWithin(pos, pos, t, viewport)
            return true
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
        if (hierarchyMode == EditHierarchyMode.SELECT && tool == CanvasTool.SELECT) {
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

        // 6. Point Transform handles (SELECT tool in DEFORM/EDIT mode)
        if (tool == CanvasTool.SELECT) {
            val frame = transformFrame(viewport)
            val handle = frame?.let { transformRingAt(pos, it) } ?: BoundingHandle.NONE
            if (frame != null && handle != BoundingHandle.NONE) {
                val source = state.previewModel?.rig?.puppet ?: return true
                val targets = transformTargets(source)
                if (hierarchyMode == EditHierarchyMode.DEFORM) {
                    val refs = targets.map { it.kind to it.id }
                    if (viewModel.snapTargetsToNearestKeys(refs) { press(pos, viewport, shift, alt, ctrl) }) return true
                }
                beginTransformDrag(source, targets, handle, frame, viewport)
                return true
            }
        }

        // Path handles: EDIT rebinds, DEFORM deforms — always before mesh vertex picks.
        if (hierarchyMode == EditHierarchyMode.DEFORM || hierarchyMode == EditHierarchyMode.EDIT) {
            val pathTarget = target()?.takeIf { it.kind == "mesh" && paths().isNotEmpty() }
            if (pathTarget != null) {
                if (hierarchyMode == EditHierarchyMode.DEFORM) {
                    if (viewModel.snapToNearestKeys(pathTarget.kind, pathTarget.id) {
                        press(pos, viewport, shift, alt, ctrl)
                    }) return true
                }
                if (beginPathInteraction(pos, pathTarget, viewport, ctrl)) return true
            }
        }

        val editTarget = target() ?: return true
        // Mesh / topology gestures are separate from path handles — drop any lingering path-point grab.
        clearPathPointSelection()
        val brush = tool in DEFORM_BRUSH_TOOLS
        if (hierarchyMode == EditHierarchyMode.DEFORM && (brush || tool == CanvasTool.SELECT)) {
            if (viewModel.snapToNearestKeys(editTarget.kind, editTarget.id) {
                press(pos, viewport, shift, alt, ctrl)
            }) return true
        }
        targetAtPress = editTarget; original = model; dragging = true
        val points = screen(editTarget.geometry.points, editTarget, viewport)

        if (brush) {
            shrinkAtPress = inflateInvert xor alt
            if (editTarget.kind == "rotation") { dragging = false; error = io.github.psd2live.i18n.tr("editor.rotationBrush"); return true }
            val basePoints = editTarget.geometry.points.copyOf()
            val initialScreen = points
            val screenRadius = (radius * viewport.scale).toFloat()
            val weights = FloatArray(initialScreen.size)
            val affected = mutableSetOf<Int>()
            for (i in initialScreen.indices) {
                if (vertices.isNotEmpty() && i !in vertices) continue
                val p = initialScreen[i]
                val w = computeBrushWeight(p, pos, pos, screenRadius, hardness, brushShape, brushAngle, brushAspect) * strength
                if (w > 0.0001f) {
                    weights[i] = w
                    affected.add(i)
                }
            }
            activeBrushWeights = weights
            activeBrushCenter = pos
            brushInitialBase = basePoints
            brushInitialScreen = initialScreen
            brushAffectedIndices = affected
            return true
        }

        var picked = points.indices.filter { (points[it] - pos).getDistance() < 10f }.minByOrNull { (points[it] - pos).getDistance() }?.let { setOf(it) }.orEmpty()
        var pickedEdge: MeshElement.Edge? = null
        var pickedFace: Int? = null
        if (hierarchyMode == EditHierarchyMode.EDIT && editTarget.kind == "mesh") {
            when (elementMode) {
                1 -> {
                    pickedEdge = MeshTopology.uniqueEdges(editTarget.indices).minByOrNull { edge -> distanceToSegment(pos, points[edge.endpointLow], points[edge.endpointHigh]) }
                        ?.takeIf { distanceToSegment(pos, points[it.endpointLow], points[it.endpointHigh]) < 8f }
                    picked = pickedEdge?.let { setOf(it.endpointLow, it.endpointHigh) }.orEmpty()
                }
                2 -> {
                    pickedFace = (0 until editTarget.indices.size / 3).firstOrNull { face ->
                        insidePolygon(pos, (0..2).map { points[editTarget.indices[face * 3 + it]] })
                    }
                    picked = pickedFace?.let { MeshTopology.verticesOfTriangle(editTarget.indices, it) }.orEmpty()
                }
            }
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
            if (pickedEdge != null) {
                selectedEdges = when { alt -> selectedEdges - pickedEdge; shift -> selectedEdges + pickedEdge; else -> setOf(pickedEdge) }
                vertices = selectedEdges.flatMapTo(LinkedHashSet()) { listOf(it.endpointLow, it.endpointHigh) }
            } else if (pickedFace != null) {
                selectedFaces = when { alt -> selectedFaces - pickedFace; shift -> selectedFaces + pickedFace; else -> setOf(pickedFace) }
                vertices = selectedFaces.flatMapTo(LinkedHashSet()) { MeshTopology.verticesOfTriangle(editTarget.indices, it) }
            } else vertices = when {
                alt -> vertices - picked
                shift -> vertices + picked
                picked.all { it in vertices } -> vertices
                else -> picked
            }
        } else {
            marquee = listOf(pos, pos)
            if (!shift && !alt) { vertices = emptySet(); selectedEdges = emptySet(); selectedFaces = emptySet() }
        }
        if (editTarget.kind == "rotation") vertices = if (picked == setOf(1)) setOf(1) else setOf(0, 1)
        if (alt && picked.isNotEmpty() && editTarget.kind != "rotation") dragging = false
        return true
    }

    fun move(pos: Offset, viewport: CanvasViewport, shift: Boolean, alt: Boolean = false, ctrl: Boolean = false) {
        this.viewport = viewport
        updateHover(pos, viewport, ctrl, shift)
        shrinks = if (dragging && tool == CanvasTool.INFLATE) shrinkAtPress else inflateInvert xor alt
        if (!dragging || busy) return
        moved = moved || (pos - start).getDistance() > 2f
        if (!moved) return

        if (subdividing) {
            targetAtPress?.let { t -> subdivideEdges = subdivideEdges + edgesWithin(previous, pos, t, viewport) }
            previous = pos
            return
        }

        if (hierarchyMode == EditHierarchyMode.PAINT && isPainting) {
            paintStrokeCurrent = pos
            // A pick scrubs: the colour follows the pointer for as long as the button is held.
            if (isSampling) {
                pickColorAt(pos, viewport, secondary = samplingSecondary)
            } else if (tool in PAINT_BRUSH_TOOLS) {
                applyLiveSegment(lastPaintPoint ?: pos, pos, erase = tool == CanvasTool.PAINT_ERASER)
                lastPaintPoint = pos
            }
            return
        }

        if (placement != null && placementHandle != PlacementHandle.NONE && placementDragStart != null && placementDragSnapshot != null) {
            applyPlacementDrag(pos, viewport, shift)
            return
        }

        if (isCreatingWarp || isCreatingRotation) {
            creationCurrent = if (isCreatingRotation && shift) CanvasGestureGeometry.direction(creationStart!!, pos, snap = true) else pos
            return
        }

        if (activeBezierHandle != null) {
            val (br, bc, dir) = activeBezierHandle!!
            val t = targetAtPress ?: return; val source = original ?: return
            val (lx, ly) = local(pos, t, viewport)
            bezierState?.moveHandle(br, bc, dir, lx, ly, smooth = !alt)
            val warp = source.deformers.filterIsInstance<Deformer.Warp>().firstOrNull { it.id.raw == t.id } ?: return
            val evaluated = bezierState?.evaluateLattice(warp.rows, warp.columns) ?: return
            // Keep the authored handles when this lattice is committed. Reconstructing them
            // from sampled anchors would straighten the curves before the next gesture.
            bezierSourcePoints = evaluated.copyOf()
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
            bezierSourcePoints = evaluated.copyOf()
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

            if (pathDragging && activePath != null && pathPoint >= 0 && pathPointsInteractive()) {
                val path = source.deformPaths.firstOrNull { it.id == activePath }
                if (path != null && pathPoint in path.points.indices) {
                    val seed = DeformPathTools.positions(path, t.geometry.points)[pathPoint]
                    val dest = local(pos, t, viewport, seed)
                    if (hierarchyMode == EditHierarchyMode.DEFORM) {
                        val points = DeformPathTools.positions(path, t.geometry.points).toMutableList()
                        points[pathPoint] = dest
                        val cmd = geometryCommand(t, DeformPathTools.deform(t.geometry.points, source.deformPaths, path.id, points))
                        preview = RigAuthoringJournal.apply(source, cmd)
                        pending = cmd
                    } else {
                        // EDIT / create: rebind control point only — mesh geometry stays put.
                        val corner = path.points[pathPoint].corner
                        val rebound = DeformPathTools.bind(t.geometry.points, t.indices, dest.first, dest.second, corner)
                        val updated = path.copy(
                            points = path.points.mapIndexed { i, p -> if (i == pathPoint) rebound else p },
                        )
                        val cmd = DeformPathJournal.encode(updated)
                        preview = RigAuthoringJournal.apply(source, cmd)
                        pending = cmd
                    }
                    previous = pos
                    return
                }
            }

            if (t.kind == "rotation" && 0 in vertices) {
                // The pivot is parent-local, but the arm is a rigid model-unit offset.
                // Translating both as warp UV points changes the arm's angle and length.
                val base = t.geometry.points
                val pivot = screen(base, t, viewport)[0] + (pos - start)
                val moved = placementScreenToLocal(pivot, viewport, t.mapping, base[0] to base[1])
                val dx = moved.first - base[0]
                val dy = moved.second - base[1]
                val pts = floatArrayOf(moved.first, moved.second, base[2] + dx, base[3] + dy)
                val cmd = geometryCommand(t, pts)
                preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
                return
            }

            if (t.kind == "rotation" && vertices == setOf(1)) {
                val base = t.geometry.points
                val origin = Offset(base[0], base[1])
                val arm = Offset(base[2], base[3])
                val pointer = local(pos, t, viewport, base[2] to base[3]).let { Offset(it.first, it.second) }
                val endpoint = if (alt) {
                    origin + (arm - origin) * ((pointer - origin).getDistance().coerceAtLeast(1e-4f) / (arm - origin).getDistance().coerceAtLeast(1e-4f))
                } else CanvasGestureGeometry.direction(origin, pointer, (arm - origin).getDistance(), shift)
                if ((endpoint - origin).getDistance() < 1e-5f) return
                val pts = floatArrayOf(origin.x, origin.y, endpoint.x, endpoint.y)
                val cmd = if (alt) geometryCommand(t, pts) else JsonObject(geometryCommand(t, pts) + ("keep_scale" to JsonPrimitive(true)))
                preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
                return
            }

            val brush = tool in DEFORM_BRUSH_TOOLS
            val inflate = tool == CanvasTool.INFLATE
            val isDeformBrush = tool == CanvasTool.BRUSH && !shift
            val screenRadius = (radius * viewport.scale).toFloat()

            if (isDeformBrush) {
                val base = brushInitialBase ?: t.geometry.points
                val initScreen = brushInitialScreen ?: screen(base, t, viewport)
                val weights = activeBrushWeights
                val affected = brushAffectedIndices
                if (weights == null || affected.isEmpty()) { previous = pos; return }

                val totalDelta = pos - start
                val world = t.mapping.localToWorld(base)
                for (i in affected) {
                    val w = weights[i]
                    val destination = initScreen[i] + totalDelta * w
                    world[i * 2] = ((destination.x - viewport.offsetX) / viewport.scale).toFloat()
                    world[i * 2 + 1] = -((destination.y - viewport.offsetY) / viewport.scale).toFloat()
                }
                val cmd = geometryCommand(t, t.mapping.worldToLocal(world, base, affected))
                preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
                return
            }

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
        if (subdividing) {
            subdividing = false
            dragging = false
            val covered = subdivideEdges
            subdivideEdges = emptySet()
            targetAtPress = null; original = null
            if (covered.isNotEmpty()) topology("subdivide", edges = covered)
            return
        }

        if (hierarchyMode == EditHierarchyMode.PAINT && isPainting) {
            val session = paintSession
            val vp = viewport

            if (session != null && vp != null) {
                val clip = java.awt.Rectangle(0, 0, session.docWidth, session.docHeight)
                when {
                    isSampling -> Unit // a pick draws nothing, so there is no stroke to record
                    tool in PAINT_BRUSH_TOOLS -> {
                        // The pixels are already on the layer: this is what makes the gesture one
                        // stroke in the history rather than one per segment.
                        val toolName = when (tool) {
                            CanvasTool.PAINT_ERASER -> tr("editor.paint.strokeEraser")
                            CanvasTool.PAINT_PENCIL -> tr("editor.paint.strokePencil")
                            else -> tr("editor.paint.strokeBrush")
                        }
                        session.recordStroke(toolName)
                    }
                    tool == CanvasTool.PAINT_SHAPE -> {
                        val s = paintStrokeStart
                        val c = paintStrokeCurrent
                        // The corners are in canvas space and not clipped to the layer: a shape dragged
                        // off the edge is still the shape the artist drew, and the raster trims it.
                        val p0 = if (s != null) screenToCanvasPoint(s, vp) else null
                        val p1 = if (c != null) screenToCanvasPoint(c, vp) else null
                        val shape = paintShape
                        if (p0 != null && p1 != null) {
                            val x0 = floor(p0.first).toInt()
                            val y0 = floor(p0.second).toInt()
                            val x1 = floor(p1.first).toInt()
                            val y1 = floor(p1.second).toInt()
                            val shapeStrokeWidth = paintBrushSize.coerceAtLeast(1f)
                            val area = LayerPaintEngine.shapeArea(x0, y0, x1, y1, shapeStrokeWidth, clip)
                            session.edit(area) { image ->
                                LayerPaintEngine.drawShape(
                                    image = image,
                                    x0 = x0, y0 = y0,
                                    x1 = x1, y1 = y1,
                                    shape = shape,
                                    color = paintColor,
                                    opacity = paintOpacity,
                                    strokeWidth = shapeStrokeWidth,
                                    filled = paintShapeFilled && shape.canFill,
                                    clipRect = clip
                                )
                            }
                            session.recordStroke(tr(shape.strokeLabelKey))
                        }
                    }
                    else -> {}
                }
            }

            isSampling = false
            isPainting = false
            paintStrokeStart = null
            paintStrokeCurrent = null
            dragging = false
            targetAtPress = null
            original = null
            return
        }

        dragging = false; axis = null; activeHandle = BoundingHandle.NONE
        initialBounds = null
        initialScreenPoints = emptyList()
        boxDrag = false; dragIndices = emptyList()
        activeBrushWeights = null; activeBrushCenter = null
        brushInitialBase = null; brushInitialScreen = null; brushAffectedIndices = emptySet()
        endPathDrag()

        if (isCreatingWarp) {
            isCreatingWarp = false
            val s = creationStart; val e = creationCurrent
            creationStart = null; creationCurrent = null
            // Legacy drag-create only when no place-then-confirm session is active.
            if (placement == null && s != null && e != null && abs(s.x - e.x) > 10f && abs(s.y - e.y) > 10f && viewport != null) {
                createWarpFromBounds(s, e, viewport!!)
            }
            return
        }

        if (isCreatingRotation) {
            isCreatingRotation = false
            val s = creationStart; val e = creationCurrent
            creationStart = null; creationCurrent = null
            if (placement == null && s != null && e != null && (s - e).getDistance() > 10f && viewport != null) {
                createRotationFromPoints(s, e, viewport!!)
            }
            return
        }

        if (placementHandle != PlacementHandle.NONE) {
            placementHandle = PlacementHandle.NONE
            placementDragStart = null
            placementDragSnapshot = null
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
        val painting = paintBrushActive
        if (!painting && tool != CanvasTool.BRUSH && tool != CanvasTool.SMOOTH && tool != CanvasTool.INFLATE && tool != CanvasTool.SUBDIVIDE) return false
        adjustingBrush = true
        brushAxis = when {
            // A paint tip has no angle, so Shift latches its third parameter - the opacity - instead,
            // which is the same pairing Photoshop uses for its Shift + right-drag.
            painting && shift -> BrushAdjustAxis.OPACITY
            painting -> null
            tool == CanvasTool.SUBDIVIDE -> null
            shift && brushShape != BrushShape.CIRCLE -> BrushAdjustAxis.ANGLE
            else -> null
        }
        brushAnchor = pos
        brushRadiusAtStart = radius
        brushHardnessAtStart = hardness
        brushAngleAtStart = brushAngle
        paintSizeAtStart = paintSize
        paintHardnessAtStart = paintHardness
        paintOpacityAtStart = paintOpacity
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
        if (paintBrushActive) {
            when (brushAxis) {
                BrushAdjustAxis.RADIUS -> paintSize = (paintSizeAtStart * 1.2f.pow(dx / BRUSH_RADIUS_STEP_PX)).coerceIn(1f, 512f)
                BrushAdjustAxis.HARDNESS -> paintHardness = (paintHardnessAtStart + dy / BRUSH_HARDNESS_SPAN_PX).coerceIn(0f, 1f)
                BrushAdjustAxis.OPACITY -> paintOpacity = (paintOpacityAtStart + dy / BRUSH_HARDNESS_SPAN_PX).coerceIn(0.01f, 1f)
                BrushAdjustAxis.ANGLE, null -> Unit
            }
            return
        }
        when (brushAxis) {
            BrushAdjustAxis.RADIUS -> radius = (brushRadiusAtStart * 1.2f.pow(dx / BRUSH_RADIUS_STEP_PX)).coerceIn(4f, 500f)
            BrushAdjustAxis.HARDNESS -> hardness = (brushHardnessAtStart + dy / BRUSH_HARDNESS_SPAN_PX * 0.95f).coerceIn(0f, 0.95f)
            BrushAdjustAxis.ANGLE -> brushAngle = (brushAngleAtStart + dx * 0.75f).mod(360f)
            BrushAdjustAxis.OPACITY, null -> Unit
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
            paintSize = paintSizeAtStart
            paintHardness = paintHardnessAtStart
            paintOpacity = paintOpacityAtStart
        }
    }

    fun finishSelection(viewport: CanvasViewport) {
        if (marquee.isEmpty()) return
        // Object mode picks on press, so a marquee that never moved — a click, jitter included — is
        // already resolved and must not be re-derived here. Re-deriving it from a zero-area polygon
        // finds nothing and would blank the very selection the press just made, deformers especially,
        // which the marquee has no way to express at all.
        if (hierarchyMode == EditHierarchyMode.SELECT && !moved) {
            pressedObject = null; marquee = emptyList(); original = null; head = null; return
        }
        val polygon = if (selectionStyle == SelectionStyle.LASSO) marquee else listOf(marquee.first(), Offset(marquee.last().x, marquee.first().y), marquee.last(), Offset(marquee.first().x, marquee.last().y))
        if (hierarchyMode == EditHierarchyMode.SELECT) {
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
        selectedEdges = if (elementMode == 1) MeshTopology.edgesWithBothEndpointsSelected(t.indices, vertices) else emptySet()
        selectedFaces = if (elementMode == 2) MeshTopology.facesWithAllVerticesSelected(t.indices, vertices).mapTo(LinkedHashSet()) { it.triangleIndex } else emptySet()
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
