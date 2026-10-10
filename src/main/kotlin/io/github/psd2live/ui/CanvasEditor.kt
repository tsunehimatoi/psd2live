package io.github.psd2live.ui

import io.github.psd2live.core.RigInformationOverlay

import io.github.psd2live.core.RigCanvasSupport

import io.github.psd2live.core.CanvasViewport

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.psd2live.application.CanvasDraftScope
import io.github.psd2live.application.CanvasDraftSubmit
import io.github.psd2live.application.WorkspaceCanvasInputDraft
import io.github.psd2live.core.*
import io.github.psd2live.project.transform
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.tutorial.expandShortcutMarkup
import io.github.psd2live.ui.state.*
import kotlinx.serialization.json.*
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshRefinementOps
import org.umamo.format.art.SourceLayer
import org.umamo.render.eval.*
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
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
    /** 模拟: simulation weight groups and cloth / hair setup on the selected meshes. */
    SIMULATE,
    /** 骨骼: pose the armature or reshape it. The skeleton is the target for as long as the mode lasts. */
    SKELETON,
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
    ctrl: Boolean = false,
): JsonObject = buildJsonObject {
    val editMesh = kind == "mesh" && mode == EditHierarchyMode.EDIT
    val editWarp = kind == "warp" && (mode == EditHierarchyMode.EDIT || ctrl)
    put("op", "canvas_geometry"); put("kind", kind); put("id", id)
    val emptyKey = editMesh || (kind == "warp" && mode == EditHierarchyMode.EDIT) || (editWarp && coordinate.isEmpty())
    put("key", JsonObject((if (emptyKey) emptyMap() else coordinate).mapValues { JsonPrimitive(it.value) }))
    if (editMesh || editWarp) put("pose", JsonObject(coordinate.mapValues { JsonPrimitive(it.value) }))
    put("preserve_image", editMesh || editWarp)
    if (editWarp) put("preserve_children", true)
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
        EditHierarchyMode.SIMULATE -> "editor.mode.simulate"
        EditHierarchyMode.SKELETON -> "editor.mode.skeleton"
        EditHierarchyMode.PAINT -> "editor.mode.paint"
    }
)

/** Canvas tools, each pointing at the shortcut action that activates it. */
internal enum class CanvasTool(val action: ShortcutAction) {
    SELECT(ShortcutAction.TOOL_SELECT),
    TRANSFORM(ShortcutAction.TOOL_TRANSFORM),
    LASSO_SELECT(ShortcutAction.TOOL_LASSO_SELECT),
    BRUSH_SELECT(ShortcutAction.TOOL_BRUSH_SELECT),
    BRUSH(ShortcutAction.TOOL_BRUSH),
    SMOOTH(ShortcutAction.TOOL_SMOOTH),
    INFLATE(ShortcutAction.TOOL_INFLATE),
    SKELETON_POSE(ShortcutAction.TOOL_SKELETON_POSE),
    SKELETON_EDIT(ShortcutAction.TOOL_SKELETON_EDIT),
    CREATE_WARP(ShortcutAction.TOOL_CREATE_WARP),
    CREATE_ROTATION(ShortcutAction.TOOL_CREATE_ROTATION),
    CREATE_DEFORM_PATH(ShortcutAction.TOOL_CREATE_DEFORM_PATH),
    GLUE(ShortcutAction.TOOL_GLUE),
    /** Region subdivide: a radius brush over the mesh's edges. */
    SUBDIVIDE(ShortcutAction.TOOL_SUBDIVIDE),
    /** The knife: click anchors along a cut, connect them, commit with Enter. */
    KNIFE(ShortcutAction.TOOL_KNIFE),
    /** Paints a simulation vertex group (pin, stiffness, goal...) on the edited meshes. */
    WEIGHT_PAINT(ShortcutAction.TOOL_WEIGHT_PAINT),
    /** Drags a linear gradient into the same vertex group the weight brush paints. */
    WEIGHT_GRADIENT(ShortcutAction.TOOL_WEIGHT_GRADIENT),
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

internal enum class GlueSubTool { BRUSH, WEIGHT, REMERGE }

internal enum class SkeletonEditSubTool(val labelKey: String, val hintKey: String) {
    EDIT("skeleton.tool.edit", "skeleton.edit.hint"),
    NEW_BONE("skeleton.tool.new", "skeleton.tool.new.hint"),
    EXTRUDE("skeleton.tool.extrude", "skeleton.tool.extrude.hint"),
    BIND("skeleton.tool.bind", "skeleton.tool.bind.hint"),
    WEIGHTS("skeleton.tool.weights", "skeleton.tool.weights.hint"),
}

internal enum class SkeletonPoseSubTool(val labelKey: String) {
    AUTO("skeleton.pose.auto"), FK("skeleton.pose.fk"), IK("skeleton.pose.ik"),
}

internal enum class GlueWeightMode { BALANCE, A, B }

/** Glue's sub-tools and weight sides with the labels every picker shows them under. */
internal val GLUE_SUB_TOOL_LABELS = listOf(
    GlueSubTool.BRUSH to "editor.glueBrush",
    GlueSubTool.WEIGHT to "editor.glueWeight",
    GlueSubTool.REMERGE to "editor.glueRemerge",
)

internal val GLUE_WEIGHT_MODE_LABELS = listOf(
    GlueWeightMode.BALANCE to "A:B",
    GlueWeightMode.A to "A",
    GlueWeightMode.B to "B",
)

internal val GlueColorA = androidx.compose.ui.graphics.Color(0xFF5B8DEF)
internal val GlueColorB = androidx.compose.ui.graphics.Color(0xFFE07A3D)

/** A glued point - one point two meshes share - wherever it is drawn. */
internal val GlueColorWeld = androidx.compose.ui.graphics.Color(0xFF4CC38A)

/** Colours of the meshes Edit holds at once, in selection order: the first two are glue sides A and B. */
internal val EditMeshPalette = listOf(
    GlueColorA,
    GlueColorB,
    androidx.compose.ui.graphics.Color(0xFFB07BE0),
    androidx.compose.ui.graphics.Color(0xFFD9B43A),
    androidx.compose.ui.graphics.Color(0xFF3FBCD6),
)

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

/** Which handle is being dragged while placing: the ghost box (see [CanvasEditor.placementBoxHandle]), or a Rotation's pivot or tip. */
internal enum class PlacementHandle { NONE, BOX, PIVOT, TIP }

/**
 * The handles of a Warp or Layer ghost: the Transform tool's box without the turn - a Warp is created from an
 * upright rectangle of its parent and an imported layer from an upright canvas rectangle, so neither can stand turned.
 */
internal val PLACEMENT_HANDLES = TransformHandles(rotates = false)

/**
 * An in-progress create: target and relation are fixed from the tree/toolbar; the artist places and
 * sizes a ghost on the canvas, then confirms. Nothing is written to the model until [CanvasEditor.confirmPlacement].
 *
 * Bounds / origin / tip are stored in the new deformer parent-local space — the units the
 * `canvas_create_warp` / `canvas_create_rotation` commands take. Screen is display-only via
 * [DrawableSpaceMapping.localToWorld]; commit copies these fields as-is (no camera-world round-trip).
 */
internal data class CreatePlacement(
    val kind: CreatePlacementKind,
    val relation: CreateRelation,
    /** "mesh", "deformer", or "layer" */
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
    /** The ghost box's anchor as a point of the box (see [TransformFrame.anchorUv]); null on its centre. */
    val anchor: Offset? = null,
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
 * Every tool the left toolbar can show, in the order it shows them: each mode shows the tools of its palette, and
 * object mode the creation tools after them ([toolbarRows]).
 */
internal val TOOLBAR_TOOL_ORDER = listOf(
    CanvasTool.SELECT, CanvasTool.TRANSFORM, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT,
    CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE,
    CanvasTool.SKELETON_POSE, CanvasTool.SKELETON_EDIT,
    CanvasTool.SUBDIVIDE, CanvasTool.KNIFE, CanvasTool.GLUE,
    CanvasTool.WEIGHT_PAINT, CanvasTool.WEIGHT_GRADIENT,
    CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER,
    CanvasTool.PAINT_BUCKET, CanvasTool.PAINT_EYEDROPPER,
    CanvasTool.PAINT_SHAPE,
    CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH,
)

/** The rows of [mode]'s toolbar in the groups it separates with rules: the palette, then the creation tools where offered. */
internal fun toolbarRows(mode: EditHierarchyMode): List<List<CanvasTool>> =
    toolbarGroups(mode) + listOfNotNull(CREATE_GROUP_TOOLS.takeIf { createGroupOffered(mode) })

/**
 * The left toolbar's palette for [mode], in groups the toolbar separates with rules. The creation tools are not part
 * of it: they arm a placement rather than a mode's tool, and follow it in object mode ([toolbarRows]).
 *
 * Object mode is the one without the vertex tools. Deform mode edits points without changing topology.
 * Edit mode handles mesh topology (subdivide / knife). Simulate paints the simulation's vertex groups,
 * Skeleton poses or reshapes the armature, and Paint replaces layer pixels.
 */
internal fun toolbarGroups(mode: EditHierarchyMode): List<List<CanvasTool>> = when (mode) {
    EditHierarchyMode.SELECT -> listOf(
        listOf(CanvasTool.SELECT, CanvasTool.TRANSFORM, CanvasTool.LASSO_SELECT),
    )
    EditHierarchyMode.DEFORM -> listOf(
        listOf(CanvasTool.SELECT, CanvasTool.TRANSFORM, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT),
        listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE),
    )
    EditHierarchyMode.EDIT -> listOf(
        listOf(CanvasTool.SELECT, CanvasTool.TRANSFORM, CanvasTool.LASSO_SELECT, CanvasTool.BRUSH_SELECT),
        listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE),
        listOf(CanvasTool.SUBDIVIDE, CanvasTool.KNIFE, CanvasTool.GLUE),
    )
    EditHierarchyMode.SIMULATE -> listOf(
        listOf(CanvasTool.WEIGHT_PAINT, CanvasTool.WEIGHT_GRADIENT),
    )
    EditHierarchyMode.SKELETON -> listOf(
        listOf(CanvasTool.SKELETON_POSE, CanvasTool.SKELETON_EDIT),
    )
    EditHierarchyMode.PAINT -> listOf(
        listOf(CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER),
        listOf(CanvasTool.PAINT_BUCKET, CanvasTool.PAINT_EYEDROPPER),
        listOf(CanvasTool.PAINT_SHAPE),
    )
}

/** The toolbar's creation tools: each arms place-then-confirm on the part in hand, as the tree's Add menu does. */
internal val CREATE_GROUP_TOOLS = listOf(CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH)

/** The modes whose toolbar offers the Create group: object mode, where parts are picked whole. */
internal fun createGroupOffered(mode: EditHierarchyMode): Boolean = mode == EditHierarchyMode.SELECT

/** The simulation weight tools: both write the mesh's vertex group of the kind picked in the toolbar. */
internal val WEIGHT_TOOLS = setOf(CanvasTool.WEIGHT_PAINT, CanvasTool.WEIGHT_GRADIENT)

/**
 * The kinds the weight tools paint, in the order a simulation is usually set up. Wind moves nothing the
 * editor bakes or previews, so it is not offered.
 */
internal val PAINTED_GROUP_KINDS = listOf(
    VertexGroupKind.PIN, VertexGroupKind.STIFFNESS, VertexGroupKind.GOAL,
    VertexGroupKind.MASS, VertexGroupKind.DAMPING,
)

/** The two tools of Skeleton mode. */
internal val SKELETON_TOOLS = setOf(CanvasTool.SKELETON_POSE, CanvasTool.SKELETON_EDIT)

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

/** One gesture owns its pose, parent mapping and history HEAD until release. */
/** The faces a topology op created, and which drawable they belong to. See [CanvasEditor.topologyFills]. */
internal data class TopologyFill(val drawableId: String, val triangles: Set<Int>)

/** Tone for [CanvasEditor.statusBarMessage] in the app status bar. */
internal enum class CanvasStatusTone { NORMAL, WARNING, ERROR }

/** Text and tone the app status bar shows while an Edit tab is active. */
internal data class CanvasStatusMessage(val text: String, val tone: CanvasStatusTone)

internal class CanvasEditor(
    val viewModel: PSD2LiveViewModel,
    private val workspaceId: String = viewModel.state.value.activeWorkspace.id,
    private val canvasId: String = viewModel.state.value.activeCanvas.id,
) {
	/**
	 * The skeleton is a target of its own, like a drawable or a deformer: Object mode picks it and Skeleton
	 * mode poses or reshapes it. It is kept on this canvas rather than in the document selection because
	 * the armature is one per project; picking a layer or deformer anywhere drops it.
	 */
	var skeletonSelected by mutableStateOf(false)
		private set

	/** The Skeleton Edit tool's working copy of the armature. Leaving the tool writes it back as one history entry. */
	var skeletonDraft by mutableStateOf<io.github.psd2live.core.SkeletonSpec?>(null)
		private set
	/** The application session [skeletonDraft] mirrors; every draft change and the commit go through it. */
	private var skeletonSession: io.github.psd2live.application.WorkspaceSkeletonDraft? = null
	private var skeletonDraftOpening = false
	var selectedBoneIds by mutableStateOf<Set<String>>(emptySet())
		private set
	private var selectedBoneIdState by mutableStateOf<String?>(null)
	var selectedBoneId: String?
		get() = selectedBoneIdState
		private set(value) { selectedBoneIdState = value; selectedBoneIds = setOfNotNull(value) }
	var skeletonEditSubTool by mutableStateOf(SkeletonEditSubTool.EDIT)
	var skeletonPoseSubTool by mutableStateOf(SkeletonPoseSubTool.AUTO)
	var transformBoneDescendants by mutableStateOf(false)
	var editBonesSymmetrically by mutableStateOf(false)
	var transferCopiedBoneBindings by mutableStateOf(false)
	var pendingSkeletonDrawableIds by mutableStateOf<Set<String>>(emptySet())
		private set
	var skeletonWeightDrawableId by mutableStateOf<String?>(null)
		private set
	var skeletonWeightSourceId by mutableStateOf<String?>(null)
	var skeletonWeightBrushMode by mutableStateOf(io.github.psd2live.core.SkeletonWeightBrushMode.ADD)
	var skeletonWeightRadius by mutableStateOf(40f)
	var skeletonWeightStrength by mutableStateOf(0.2f)
	var skeletonWeightReplaceValue by mutableStateOf(1f)
	var skeletonWeightInfluences by mutableStateOf(2)
	var skeletonWeightCutoff by mutableStateOf(0.001f)
	var skeletonWeightTransferMode by mutableStateOf(io.github.psd2live.core.SkeletonWeightTransferMode.INTERPOLATE)
	var skeletonWeightTransferTolerance by mutableStateOf(10f)
	var mirrorSkeletonWeights by mutableStateOf(false)
	var skeletonWeightBoneMapping by mutableStateOf<Map<String, String>>(emptyMap())
		private set

	/** The authored armature, enabled or not, once it has bones. */
	val committedSkeleton: io.github.psd2live.core.SkeletonSpec?
		get() = state.rigEdits.skeleton?.takeIf { it.bones.isNotEmpty() }
			?: state.previewModel?.config?.rigEdits?.skeleton?.takeIf { it.bones.isNotEmpty() }

	/** Makes the skeleton the target, dropping any layer or deformer selection, and keeps the current mode if it still applies. */
	fun selectSkeleton(boneId: String? = null) {
		if (!takeSkeleton(boneId)) return
		settleModeOnTarget()
	}

	/**
	 * The target half of [selectSkeleton], without re-fitting the mode: entering Skeleton mode does that itself.
	 * Skeleton mode may also take the skeleton before there is one, [allowEmpty], and offers to create it.
	 */
	private fun takeSkeleton(boneId: String? = null, allowEmpty: Boolean = false): Boolean {
		val spec = committedSkeleton
		if (spec == null && !allowEmpty) return false
		if (!skeletonSelected) {
			skeletonSelected = true
			objects = emptySet()
			selection = emptyMap()
			viewModel.updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) {
				it.copy(selectedLayerId = null, selectedLayerIds = emptySet(), selectedDeformerId = null)
			}
		}
		selectedBoneId = (skeletonDraft ?: spec)?.let { s -> boneId?.takeIf { s.bone(it) != null } ?: selectedBoneId?.takeIf { s.bone(it) != null }
			?: s.bones.firstOrNull { !it.role.anchor }?.id }
		return true
	}

	/** Drops the skeleton target; an open edit is kept, not thrown away. */
	fun deselectSkeleton() {
		if (!skeletonSelected) return
		commitSkeletonDraft()
		skeletonSelected = false
		settleModeOnTarget()
	}

	/** Enters Skeleton mode on the Edit tool; without an armature the canvas offers to create one instead. */
	fun beginSkeletonEdit() {
		if (busy) return
		if (placement != null) cancelPlacement()
		skeletonEntrySerial++
		enterSkeletonMode(CanvasTool.SKELETON_EDIT)
	}

	/** An armature is being proposed and written; the create button waits for it. */
	var skeletonCreating by mutableStateOf(false)
		private set

	/** Proposes an armature from the layers' tags and opens it in the Edit tool; an existing one is just opened. */
	fun createSkeleton() {
		if (busy || skeletonCreating) return
		if (placement != null) cancelPlacement()
		ensureSkeleton { takeSkeleton(allowEmpty = true); enterSkeletonMode(CanvasTool.SKELETON_EDIT) }
	}

	/**
	 * Removes the whole armature as one history entry, dropping an open edit. Skeleton mode stays, now offering
	 * to create a new armature.
	 */
	fun deleteSkeleton() {
		if (busy || committedSkeleton == null) return
		skeletonEntrySerial++
		skeletonSession?.let { session -> runCatching { viewModel.skeletonDraftPort?.cancelSkeletonDraft(session.id) } }
		skeletonDraft = null
		skeletonSession = null
		selectedBoneId = null
		pendingSkeletonDrawableIds = emptySet()
		skeletonWeightDrawableId = null
		if (hierarchyMode != EditHierarchyMode.SKELETON) skeletonSelected = false
		else if (tool == CanvasTool.SKELETON_POSE) tool = CanvasTool.SKELETON_EDIT
		error = null
		clearHover()
		viewModel.deleteSkeleton { failure -> if (failure != null) error = failure }
	}

	/** Enters Skeleton mode on the Pose tool, which turns the bones of an enabled armature. */
	fun beginSkeletonPose() {
		if (busy) return
		skeletonEntrySerial++
		if (committedSkeleton?.enabled != true) { error = tr("skeleton.pose.none"); return }
		enterSkeletonMode(CanvasTool.SKELETON_POSE)
	}

	/** Proposes an armature from the layers' tags when the project has none yet. */
	private var skeletonEntrySerial = 0L

	private fun ensureSkeleton(onReady: () -> Unit) {
		val serial = ++skeletonEntrySerial
		if (committedSkeleton != null) { onReady(); return }
		val started = viewModel.uiState.value
		val expected = viewModel.currentWorkspaceState() ?: return
		val spec = state.previewModel?.let { io.github.psd2live.core.SkeletonAutoBuilder.build(it.analysis, it.rig) } ?: return
		if (spec.bones.isEmpty()) { error = tr("skeleton.create.empty"); return }
		skeletonCreating = true
		viewModel.setSkeleton(spec.copy(enabled = true), expected) { failure ->
			skeletonCreating = false
			val current = viewModel.uiState.value
			if (serial != skeletonEntrySerial || current.projectId != started.projectId ||
				current.projectOpenGeneration != started.projectOpenGeneration || current.activeWorkspace.id != workspaceId ||
				current.workspaces.none { it.id == workspaceId && it.canvases.any { canvas -> canvas.id == canvasId } }) return@setSkeleton
			if (failure != null) error = failure
			else if (committedSkeleton != null) onReady()
		}
	}

	private fun enterSkeletonMode(next: CanvasTool) {
		if (hierarchyMode != EditHierarchyMode.SKELETON) {
			modeBeforeSkeleton = hierarchyMode
			enterMode(EditHierarchyMode.SKELETON)
		}
		if (hierarchyMode == EditHierarchyMode.SKELETON) switchSkeletonTool(next)
	}

	/**
	 * Moves between the two Skeleton tools. The Edit tool works on a draft of the armature on the rest pose;
	 * going back to Pose writes the draft back, the way leaving the mode does.
	 */
	private fun switchSkeletonTool(next: CanvasTool) {
		error = null
		if (next == CanvasTool.SKELETON_POSE) {
			if (committedSkeleton?.enabled != true) { error = tr("skeleton.pose.none"); return }
			commitSkeletonDraft()
			tool = next
		} else {
			tool = CanvasTool.SKELETON_EDIT
			if (skeletonDraft == null) openSkeletonDraft()
		}
		clearHover()
	}

	/** The mode Skeleton mode was entered from, where cancelling an edit of a disabled armature returns. */
	private var modeBeforeSkeleton: EditHierarchyMode? = null

	/**
	 * Bones are placed on the rest pose. The application session resets it as its own commit and the draft builds
	 * on that state, so a pose or document change made meanwhile makes the final commit conflict.
	 */
	private fun openSkeletonDraft() {
		if (committedSkeleton == null || skeletonDraftOpening) return
		skeletonDraftOpening = true
		pendingSkeletonDrawableIds = emptySet()
		val started = viewModel.uiState.value
		viewModel.openSkeletonDraft done@{ opened, failure ->
			skeletonDraftOpening = false
			val current = viewModel.uiState.value
			val wanted = skeletonDraft == null && tool == CanvasTool.SKELETON_EDIT && hierarchyMode == EditHierarchyMode.SKELETON &&
				current.projectId == started.projectId && current.projectOpenGeneration == started.projectOpenGeneration &&
				current.activeWorkspace.id == workspaceId
			if (opened == null) { if (wanted && failure != null) error = failure; return@done }
			if (!wanted) { runCatching { viewModel.skeletonDraftPort?.cancelSkeletonDraft(opened.id) }; return@done }
			skeletonSession = opened
			skeletonDraft = opened.draft
			if (selectedBoneId == null || opened.draft.bone(selectedBoneId!!) == null) selectedBoneId = opened.draft.bones.firstOrNull { !it.role.anchor }?.id
		}
	}

	/** Applies [intents] to the session's draft, all or none; the editor's draft is only ever the session's result. */
	private fun editSkeletonDraft(vararg intents: SkeletonDraftIntent): io.github.psd2live.application.WorkspaceSkeletonDraft? {
		val session = skeletonSession ?: return null
		val port = viewModel.skeletonDraftPort ?: return null
		return try {
			port.editSkeletonDraft(session.id, session.state, session.sessionState, intents.toList()).also {
				skeletonSession = it
				skeletonDraft = it.draft
			}
		} catch (failure: Exception) {
			error = failure.message
			null
		}
	}

	/** Writes the draft back on the session's own lineage; an untouched draft is simply closed. */
	fun commitSkeletonDraft() {
		val session = skeletonSession
		skeletonDraft = null
		skeletonSession = null
		if (session == null) return
		if (session.revision == 0L) runCatching { viewModel.skeletonDraftPort?.cancelSkeletonDraft(session.id) }
		else viewModel.commitSkeletonDraft(session)
	}

	/** Ends the edit, keeping it: on to posing when the armature is enabled, else back where the mode was entered from. */
	fun finishSkeletonEdit() {
		val enabled = skeletonDraft?.enabled ?: return
		commitSkeletonDraft()
		leaveSkeletonEdit(enabled)
	}

	/** Ends the edit and throws it away. */
	fun cancelSkeletonEdit() {
		if (skeletonDraft == null) return
		skeletonSession?.let { session -> runCatching { viewModel.skeletonDraftPort?.cancelSkeletonDraft(session.id) } }
		skeletonDraft = null
		skeletonSession = null
		leaveSkeletonEdit(committedSkeleton?.enabled == true)
	}

	private fun leaveSkeletonEdit(enabled: Boolean) {
		// The commit may still be landing, so the draft's own flag decides rather than the committed armature.
		if (enabled) { error = null; tool = CanvasTool.SKELETON_POSE; clearHover() }
		else setHierarchyMode(modeBeforeSkeleton?.takeIf { it != EditHierarchyMode.SKELETON } ?: EditHierarchyMode.SELECT)
	}

	/**
	 * Turns the skeleton off or back on. The bones are kept either way, so turning it back on loses nothing. While
	 * the Edit tool is open the flag belongs to its draft, which commits it with the rest of the edit.
	 */
	fun setSkeletonEnabled(enabled: Boolean) {
		if (skeletonSession != null) { editSkeletonDraft(SkeletonDraftIntent.Enabled(enabled)); return }
		val committed = committedSkeleton ?: return
		if (committed.enabled != enabled) viewModel.setSkeleton(committed.copy(enabled = enabled))
	}

	fun selectBone(id: String?, additive: Boolean = false) {
		if (!additive || id == null) { selectedBoneId = id; return }
		val next = if (id in selectedBoneIds) selectedBoneIds - id else selectedBoneIds + id
		selectedBoneId = id.takeIf { it in next } ?: next.lastOrNull()
		selectedBoneIds = next
	}

	fun selectBones(ids: Set<String>, additive: Boolean = false) {
		val valid = ids.filterTo(linkedSetOf()) { skeletonDraft?.bone(it) != null }
		val next = if (additive) selectedBoneIds + valid else valid
		selectedBoneId = next.lastOrNull()
		selectedBoneIds = next
	}

	fun transformSelectedBones(dx: Float = 0f, dy: Float = 0f, degrees: Float = 0f, scale: Float = 1f) {
		if (skeletonDraft == null) return
		editSkeletonDraft(SkeletonDraftIntent.Transform(selectedBoneIds, dx, dy, degrees, scale, transformBoneDescendants, editBonesSymmetrically))
	}

	fun restoreSkeletonDraft(spec: io.github.psd2live.core.SkeletonSpec) { if (skeletonDraft != null) editSkeletonDraft(SkeletonDraftIntent.Restore(spec)) }

	fun selectSkeletonBindingDrawables(ids: Set<String>, additive: Boolean = false) {
		pendingSkeletonDrawableIds = if (additive) pendingSkeletonDrawableIds + ids else ids
	}

	fun toggleSkeletonBindingDrawable(id: String) {
		pendingSkeletonDrawableIds = if (id in pendingSkeletonDrawableIds) pendingSkeletonDrawableIds - id else pendingSkeletonDrawableIds + id
	}

	fun applySkeletonBindingBatch(unbind: Boolean = false) {
		if (skeletonDraft == null) return
		val bone = if (unbind) null else selectedBoneId ?: return
		val valid = model.drawables.map { it.id.raw }.toSet()
		editSkeletonDraft(SkeletonDraftIntent.Bind(pendingSkeletonDrawableIds.intersect(valid), bone))
		pendingSkeletonDrawableIds = emptySet()
	}

	fun selectSkeletonWeightDrawable(id: String?) {
		skeletonWeightDrawableId = id
		skeletonWeightBoneMapping = emptyMap()
	}

	fun activeSkeletonWeights(): io.github.psd2live.core.SkeletonWeightMap? = skeletonDraft?.let { spec ->
		skeletonWeightDrawableId?.let { io.github.psd2live.core.SkeletonManualWeights.capture(spec, model, it) }
	}

	fun prepareSkeletonWeightStroke(): Boolean {
		val spec = skeletonDraft ?: return false
		val id = skeletonWeightDrawableId ?: return false
		val bone = selectedBoneId ?: return false
		if (bone !in io.github.psd2live.core.SkeletonManualWeights.treeIds(spec, id)) return false
		// A stroke starts from the mesh's current weights, resampled once.
		return editSkeletonDraft(SkeletonDraftIntent.PaintWeights(id, bone, emptyList(), skeletonWeightRadius, skeletonWeightStrength,
			skeletonWeightBrushMode, skeletonWeightReplaceValue, capture = true)) != null
	}

	fun paintSkeletonWeights(pos: Offset, viewport: CanvasViewport) {
		val spec = skeletonDraft ?: return
		val id = skeletonWeightDrawableId ?: return
		val bone = selectedBoneId ?: return
		if (bone !in io.github.psd2live.core.SkeletonManualWeights.treeIds(spec, id) || spec.manualWeights[id] == null) return
		editSkeletonDraft(SkeletonDraftIntent.PaintWeights(id, bone, listOf(viewport.canvasX(pos.x) to viewport.canvasY(pos.y)),
			skeletonWeightRadius, skeletonWeightStrength, skeletonWeightBrushMode, skeletonWeightReplaceValue, capture = false))
	}

	fun cleanupSkeletonWeights() {
		if (skeletonDraft == null || activeSkeletonWeights() == null) return
		editSkeletonDraft(SkeletonDraftIntent.CleanupWeights(skeletonWeightDrawableId ?: return, skeletonWeightInfluences, skeletonWeightCutoff))
	}

	fun resetSkeletonWeights() { skeletonWeightDrawableId?.let { id -> if (skeletonDraft != null) editSkeletonDraft(SkeletonDraftIntent.ClearWeights(id)) } }

	fun setSkeletonWeightBoneMapping(source: String, target: String) { skeletonWeightBoneMapping = skeletonWeightBoneMapping + (source to target) }

	fun effectiveSkeletonWeightBoneMapping(): Map<String, String> {
		val spec = skeletonDraft ?: return emptyMap()
		return SkeletonDraftEdits.weightMapping(spec, skeletonWeightSourceId ?: return emptyMap(), skeletonWeightDrawableId ?: return emptyMap(),
			skeletonWeightBoneMapping, mirrorSkeletonWeights)
	}

	private fun skeletonWeightTransfer(): SkeletonDraftIntent.TransferWeights? = SkeletonDraftIntent.TransferWeights(
		skeletonWeightSourceId ?: return null, skeletonWeightDrawableId ?: return null, skeletonWeightTransferMode,
		skeletonWeightTransferTolerance, mirrorSkeletonWeights, skeletonWeightBoneMapping)

	/** What the transfer would write, computed by the session on its own model, as the apply will be. */
	fun skeletonWeightTransferPreview(): io.github.psd2live.core.SkeletonManualWeights.Transfer? {
		val session = skeletonSession ?: return null
		val transfer = skeletonWeightTransfer() ?: return null
		return runCatching { viewModel.skeletonDraftPort?.previewSkeletonWeightTransfer(session.id, transfer)?.second }.getOrNull()
	}

	fun applySkeletonWeightTransfer() {
		if (skeletonDraft == null) return
		editSkeletonDraft(skeletonWeightTransfer() ?: return)
	}

	fun setBoneSymmetryAxis(x: Float) { if (x.isFinite() && skeletonDraft != null) editSkeletonDraft(SkeletonDraftIntent.SymmetryAxis(x)) }

	/** Match opposite-side layers only when their semantic/name match identifies one drawable. */
	fun boneMirrorDrawables(): Map<String, String> = state.previewModel?.let(SkeletonDraftEdits::mirrorDrawables).orEmpty()

	fun duplicateSelectedBones(mirror: Boolean = false) {
		if (skeletonDraft == null || selectedBoneIds.isEmpty()) return
		val result = editSkeletonDraft(SkeletonDraftIntent.Duplicate(selectedBoneIds, transformBoneDescendants, transferCopiedBoneBindings,
			mirror, tr(if (mirror) "skeleton.structure.mirrorSuffix" else "skeleton.structure.copySuffix"))) ?: return
		selectBones(result.selected.orEmpty())
	}

	fun subdivideSelectedBone(segments: Int) {
		if (skeletonDraft == null) return
		val result = editSkeletonDraft(SkeletonDraftIntent.Subdivide(selectedBoneId ?: return, segments)) ?: return
		selectBones(result.selected.orEmpty())
	}

	fun dissolveSelectedBone() {
		val draft = skeletonDraft ?: return
		val id = selectedBoneId ?: return
		if (!io.github.psd2live.core.SkeletonAuthoring.canDissolve(draft, id)) return
		val result = editSkeletonDraft(SkeletonDraftIntent.Dissolve(id)) ?: return
		selectBones(result.selected.orEmpty())
	}

	fun renameBone(id: String, name: String) {
		if (name.isBlank() || name.any(Char::isISOControl) || skeletonDraft?.bone(id) == null) return
		editSkeletonDraft(SkeletonDraftIntent.Rename(id, name))
	}

	fun setSelectedBoneParent(parentId: String?, connect: Boolean = false) {
		if (skeletonDraft == null) return
		editSkeletonDraft(SkeletonDraftIntent.Parent(selectedBoneId ?: return, parentId, connect))
	}

	fun moveBoneJoint(id: String, end: io.github.psd2live.core.BoneEnd, x: Float, y: Float) {
		if (skeletonDraft?.bone(id) == null) return
		editSkeletonDraft(SkeletonDraftIntent.MoveJoint(id, end, x, y, editBonesSymmetrically)) ?: return
		selectedBoneId = id
	}

	/** Half width of the selected bone's joint blend band in canvas pixels; null returns it to automatic. */
	fun setBoneBlendWidth(width: Float?) {
		val draft = skeletonDraft ?: return
		val bone = draft.bone(selectedBoneId ?: return) ?: return
		editSkeletonDraft(SkeletonDraftIntent.BlendWidth(bone.id, width))
	}

	/** The selected bone's joint limits in parameter degrees; each is kept on its own side of rest. */
	fun setBoneLimits(min: Float, max: Float) {
		val draft = skeletonDraft ?: return
		val bone = draft.bone(selectedBoneId ?: return) ?: return
		editSkeletonDraft(SkeletonDraftIntent.Limits(bone.id, min, max))
	}

	/** The skeleton the rig was built with, which is what the pose tool drives. */
	val bakedSkeleton: io.github.psd2live.core.SkeletonSpec?
		get() = (ikTargetPoseDraft ?: state.previewModel?.config?.rigEdits?.skeleton)?.takeIf { it.enabled }

	/** Bone under the pointer while the pose tool is armed, and the bone being dragged. */
	var poseHover by mutableStateOf<BoneHit?>(null)
	var poseDrag by mutableStateOf<BoneHit?>(null)
	private var draggingIkTargetId: String? = null
	private var ikTargetDragOrigin: io.github.psd2live.core.SkeletonSpec? = null
	private var ikTargetDragValues: Map<ParameterId, Float>? = null
	private var ikTargetPoseDraft: io.github.psd2live.core.SkeletonSpec? = null

	/** Whether the pose tool shades each skinned mesh by the bones it follows. */
	var showSkeletonWeights by mutableStateOf(false)

	private var posedCache: Triple<PuppetModel, Map<ParameterId, Float>, List<PosedBone>>? = null
	private var posedCacheSpec: io.github.psd2live.core.SkeletonSpec? = null

	/** The bones where the current pose holds them, cached per model and pose: hover asks on every move. */
	fun posedBones(): List<PosedBone> {
		val puppet = model
		val values = viewModel.parameterScrubPose(state, state.parameterValues)
		posedCache?.let { (m, v, bones) -> if (m === puppet && v == values && posedCacheSpec === bakedSkeleton) return bones }
		return SkeletonPoseTool.posed(puppet, bakedSkeleton, values).also { posedCache = Triple(puppet, values, it); posedCacheSpec = bakedSkeleton }
	}

	fun beginPose(pos: Offset, viewport: CanvasViewport): Boolean {
		val targets = bakedSkeleton?.ikTargets.orEmpty()
		val target = targets.entries.firstOrNull { (_, t) -> t.enabled &&
			(Offset(viewport.x(t.x).toFloat(), (viewport.offsetY + t.y * viewport.scale).toFloat()) - pos).getDistance() <= 10f }
		if (target != null) {
			draggingIkTargetId = target.key; ikTargetDragOrigin = committedSkeleton
			ikTargetDragValues = state.parameterValues
			poseDrag = BoneHit(target.key, true); selectedBoneId = target.key
			viewModel.beginParameterScrub(); return true
		}
		poseDrag = SkeletonPoseTool.hit(posedBones(), pos, viewport)
		poseDrag?.let { selectedBoneId = it.boneId }
		if (poseDrag != null) viewModel.beginParameterScrub()
		return poseDrag != null
	}

	fun dragPose(pos: Offset, viewport: CanvasViewport, ik: Boolean) {
		draggingIkTargetId?.let { id ->
			val next = (ikTargetDragOrigin ?: return).withIkTarget(id, io.github.psd2live.core.SkeletonIkTarget(viewport.canvasX(pos.x), viewport.canvasY(pos.y)))
			ikTargetPoseDraft = next
			viewModel.setParameterValues(io.github.psd2live.core.SkeletonPoseSolver.solveTargets(model, next, viewModel.parameterScrubPose(state, state.parameterValues)))
			return
		}
		val hit = poseDrag ?: return
		val spec = bakedSkeleton ?: return
		val effectiveIk = skeletonPoseSubTool == SkeletonPoseSubTool.IK ||
			(skeletonPoseSubTool == SkeletonPoseSubTool.AUTO && (hit.tip || ik))
		viewModel.poseGestureTarget(hit.boneId, viewport.canvasX(pos.x), viewport.canvasY(pos.y), effectiveIk)
		val values = SkeletonPoseTool.drag(spec, posedBones(), hit, viewport.canvasX(pos.x), viewport.canvasY(pos.y), viewModel.parameterScrubPose(state, state.parameterValues),
			ik = ik, mode = skeletonPoseSubTool)
		if (values.isNotEmpty()) viewModel.setParameterValues(values)
	}

	fun endPose() {
		if (poseDrag != null) {
			poseDrag = null
			val targetId = draggingIkTargetId
			val target = targetId?.let { (ikTargetPoseDraft ?: ikTargetDragOrigin)?.ikTargets?.get(it) }
			draggingIkTargetId = null; ikTargetDragOrigin = null; ikTargetDragValues = null; ikTargetPoseDraft = null
			if (targetId != null) viewModel.endIkTargetScrub(targetId, target) else viewModel.endParameterScrub()
		}
	}

	/** The pose tool is armed and has a baked skeleton to drive. */
	fun posing(): Boolean = tool == CanvasTool.SKELETON_POSE && skeletonSelected &&
		hierarchyMode == EditHierarchyMode.SKELETON && bakedSkeleton != null

	fun hoverPose(pos: Offset, viewport: CanvasViewport) {
		poseHover = SkeletonPoseTool.hit(posedBones(), pos, viewport)
	}

	/** The bone under [pos] that an Object mode click would pick; a hidden armature picks nothing. */
	private fun objectModeBoneHit(pos: Offset, viewport: CanvasViewport): BoneHit? =
		bakedSkeleton?.takeIf { state.showSkeleton }?.let { SkeletonPoseTool.hit(posedBones(), pos, viewport) }

	/** Every bone and leg pose back at rest. */
	fun resetSkeletonPose() {
		viewModel.setParameterValues(SkeletonPoseTool.rest(bakedSkeleton))
	}

	fun saveSkeletonPose(name: String) {
		if (name.isBlank() || name.any(Char::isISOControl)) return
		val spec = committedSkeleton ?: return
		val values = model.parameters.associate { p -> p.id.raw to (state.parameterValues[p.id] ?: p.default).coerceIn(p.min, p.max) }
		viewModel.setSkeletonPoseMetadata(spec.withSavedPose(name, values))
	}

	fun applySavedSkeletonPose(name: String) {
		val saved = committedSkeleton?.savedPoses?.get(name) ?: return
		viewModel.setParameterValues(model.parameters.mapNotNull { p -> saved[p.id.raw]?.let { p.id to it.coerceIn(p.min, p.max) } }.toMap())
	}

	fun deleteSavedSkeletonPose(name: String) {
		val spec = committedSkeleton ?: return
		viewModel.setSkeletonPoseMetadata(spec.withoutSavedPose(name))
	}

	fun setSelectedBoneIk(settings: io.github.psd2live.core.SkeletonIkSettings) {
		val spec = skeletonDraft ?: committedSkeleton ?: return
		val bone = spec.bone(selectedBoneId ?: return) ?: return
		if (skeletonDraft != null) editSkeletonDraft(SkeletonDraftIntent.Ik(bone.id, settings)) else {
			viewModel.editBoneIk(bone.id, settings)
		}
	}

	fun pinSelectedBone() {
		val posed = posedBones().firstOrNull { it.bone.id == selectedBoneId } ?: return
		updateIkTarget(posed.bone.id, io.github.psd2live.core.SkeletonIkTarget(posed.tailX, posed.tailY))
	}

	fun updateIkTarget(id: String, target: io.github.psd2live.core.SkeletonIkTarget?) {
		if (committedSkeleton == null) return
		viewModel.editIkTarget(id, target)
	}

	fun bindDrawableToSelectedBone(drawableId: String) {
		if (skeletonDraft == null) return
		editSkeletonDraft(SkeletonDraftIntent.Bind(setOf(drawableId), selectedBoneId))
	}

	fun unbindSkeletonDrawable(drawableId: String) {
		if (skeletonDraft == null) return
		editSkeletonDraft(SkeletonDraftIntent.Bind(setOf(drawableId), null))
	}

	fun addBone() {
		val draft = skeletonDraft ?: return
		val parent = draft.bone(selectedBoneId ?: return) ?: return
		createBone(parent.tailX, parent.tailY, parent.tailX, parent.tailY + 60f, parent.id)
	}

	/** Creation stays in the edit draft, sharing its finish/cancel and history behavior. */
	fun createBone(headX: Float, headY: Float, tailX: Float, tailY: Float, parentId: String? = null) {
		if (skeletonDraft == null) return
		val result = editSkeletonDraft(SkeletonDraftIntent.CreateBone(headX, headY, tailX, tailY, parentId)) ?: return
		result.selected?.singleOrNull()?.let { selectedBoneId = it }
	}

	fun removeSelectedBone() {
		val draft = skeletonDraft ?: return
		val bone = draft.bone(selectedBoneId ?: return) ?: return
		if (bone.role.anchor || bone.role.body) return
		editSkeletonDraft(SkeletonDraftIntent.RemoveBone(bone.id)) ?: return
		selectedBoneId = bone.parentId
	}

	fun setOptionalSkeletonChain(role: io.github.psd2live.core.BoneRole, enabled: Boolean) {
		require(role == io.github.psd2live.core.BoneRole.TAIL || role == io.github.psd2live.core.BoneRole.WING)
		if (skeletonDraft == null) return
		val result = editSkeletonDraft(SkeletonDraftIntent.OptionalChain(role, enabled)) ?: return
		if (selectedBoneId != null && result.draft.bone(selectedBoneId!!) == null) selectedBoneId = io.github.psd2live.core.SkeletonSpec.LOWER_BODY_ID
	}
    val state: PSD2LiveState
        get() = viewModel.uiState.value.forCanvas(canvasId, workspaceId, CanvasMode.EDIT)
    internal fun selectLayer(id: String?) {
		if (id != null && viewModel.tryApplyClipMaskPick(id)) return
        viewModel.updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) {
            it.copy(
                selectedLayerId = id,
                selectedLayerIds = if (id == null) emptySet() else objects.takeIf { id in it } ?: setOf(id),
                selectedDeformerId = if (id != null) null else it.selectedDeformerId,
            )
        }
        viewModel.noteSelectionAnchor(id)
    }
    private fun selectDeformer(id: String?) = viewModel.updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) {
        it.copy(selectedDeformerId = id, selectedLayerId = if (id != null) null else it.selectedLayerId,
            selectedLayerIds = if (id != null) emptySet() else it.selectedLayerIds)
    }
    // Hover is an input-frame detail. Keeping it on this editor prevents a mouse move in one
    // canvas from emitting a document-wide state update and recomposing all dock panels.
    var hoveredLayerId by mutableStateOf<String?>(null)
        private set
    var hoveredDeformerId by mutableStateOf<String?>(null)
        private set

    private fun setHoveredItem(layerId: String?, deformerId: String?) {
        if (hoveredLayerId != layerId) hoveredLayerId = layerId
        if (hoveredDeformerId != deformerId) hoveredDeformerId = deformerId
    }
    var viewport: CanvasViewport? = null
    var tool by mutableStateOf(CanvasTool.SELECT)

    // Top-right Hierarchy & Level state
    private var hierarchyModeState by mutableStateOf(EditHierarchyMode.SELECT)

    /** Every mode change, whichever path makes it, brings up that mode's own display toggles. */
    var hierarchyMode: EditHierarchyMode
        get() = hierarchyModeState
        set(value) {
            if (value == hierarchyModeState) return
            hierarchyModeState = value
            viewModel.switchHierarchyModeView(value, canvasId, workspaceId)
        }
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
            tool == CanvasTool.WEIGHT_PAINT -> "editor.weightHint"
            tool == CanvasTool.WEIGHT_GRADIENT -> "editor.weightGradientHint"
            hierarchyMode == EditHierarchyMode.PAINT -> "editor.paintHint"
            hierarchyMode == EditHierarchyMode.SELECT && tool == CanvasTool.SELECT -> "editor.objectHint"
            hierarchyMode == EditHierarchyMode.EDIT && tool == CanvasTool.SELECT &&
                target?.kind == "mesh" &&
                source.deformPaths.any { it.drawableId.raw == target.id && it.editLevel == pathLevel } ->
                "editor.pathBindHint"
            // drawsTransformBox reads model via target(); only evaluate once a puppet exists.
            tool == CanvasTool.TRANSFORM -> "editor.transformHint"
            tool == CanvasTool.CREATE_WARP -> if (placement != null) "editor.placementDragHint" else "editor.createWarpHint"
            tool == CanvasTool.CREATE_ROTATION -> if (placement != null) "editor.placementRotationHint" else "editor.createRotationHint"
            else -> "editor.hint"
        }
        val glueCount = if (tool == CanvasTool.GLUE) glueMeshCount() else -1
        val hint = when {
            tool == CanvasTool.GLUE && glueCount == 2 -> tr("editor.glueReadyHint")
            tool == CanvasTool.GLUE -> tr("editor.glueNeedTwo", glueCount)
            else -> tr(hintKey)
        }.let { expandShortcutMarkup(it, state.keymap) }
        val text = tr("editor.selectionCount", objects.size, vertices.size) + "   ·   " + hint
        val tone = if (tool == CanvasTool.GLUE && glueCount != 2) CanvasStatusTone.WARNING else CanvasStatusTone.NORMAL
        return CanvasStatusMessage(text, tone)
    }

    // Painting system state (L1)
    var paintColor by mutableStateOf(androidx.compose.ui.graphics.Color.Black)
    var paintSecondaryColor by mutableStateOf(androidx.compose.ui.graphics.Color.White)
    var paintBrushSize by mutableStateOf(16f)
    var paintPencilSize by mutableStateOf(4f)
    var paintEraserSize by mutableStateOf(24f)
    /** The line width of the shape tool; its own, so drawing a thin outline leaves the brush tip as it was. */
    var paintShapeSize by mutableStateOf(16f)

    /** The open document's longest side in pixels; 0 with nothing open. */
    val documentLongSide: Int
        get() = state.let { it.analysis ?: it.previewModel?.analysis }?.source?.let { maxOf(it.widthPx, it.heightPx) } ?: 0

    /**
     * The largest size any brush takes: never below [MIN_BRUSH_SIZE_LIMIT], and otherwise the document's longest
     * side, since a brush wider than the document has nothing more to cover - counted in the painted layer's own
     * pixels while painting one denser than the canvas.
     */
    val brushSizeLimit: Float
        get() = maxOf(MIN_BRUSH_SIZE_LIMIT, documentLongSide * (if (hierarchyMode == EditHierarchyMode.PAINT) paintPixelsPerShownUnit.coerceAtLeast(1f) else 1f))

    /**
     * How much larger brushes start on this document: 1 up to [io.github.psd2live.core.MeshResolution.REFERENCE_SIDE],
     * in proportion above it, so a default stroke covers the same share of a large document as of a small one.
     */
    val brushScale: Float
        get() = (documentLongSide.toFloat() / io.github.psd2live.core.MeshResolution.REFERENCE_SIDE).coerceAtLeast(1f)

    private var brushScaleApplied = 1f

    /**
     * Scales every brush size - paint, pencil, eraser, the deform and weight brushes - by the change in [brushScale]
     * since the last call, so the defaults suit a newly opened document and sizes the user picked keep their share
     * of it. Nothing happens with no document open.
     */
    fun fitBrushesToDocument() {
        if (documentLongSide <= 0) return
        val scale = brushScale
        if (scale == brushScaleApplied) return
        val k = scale / brushScaleApplied
        val limit = brushSizeLimit
        paintBrushSize = (paintBrushSize * k).coerceIn(1f, limit)
        paintPencilSize = (paintPencilSize * k).coerceIn(1f, limit)
        paintEraserSize = (paintEraserSize * k).coerceIn(1f, limit)
        paintShapeSize = (paintShapeSize * k).coerceIn(1f, limit)
        radius = (radius * k).coerceIn(1f, limit)
        skeletonWeightRadius = (skeletonWeightRadius * k).coerceIn(1f, limit)
        brushScaleApplied = scale
    }

    /** Edge softness of the paint and erase tips: 1 is a pen, 0 fades the whole tip to nothing. */
    var paintHardness by mutableStateOf(0.85f)

    /** The size the active paint tool draws at: pencil, eraser and shapes each keep their own. */
    var paintSize: Float
        get() = when (tool) {
            CanvasTool.PAINT_PENCIL -> paintPencilSize
            CanvasTool.PAINT_ERASER -> paintEraserSize
            CanvasTool.PAINT_SHAPE -> paintShapeSize
            else -> paintBrushSize
        }
        set(value) {
            when (tool) {
                CanvasTool.PAINT_PENCIL -> paintPencilSize = value
                CanvasTool.PAINT_ERASER -> paintEraserSize = value
                CanvasTool.PAINT_SHAPE -> paintShapeSize = value
                else -> paintBrushSize = value
            }
        }

    /**
     * The painted layer's own pixels per unit of its frame - the unit strokes are handed to the session in. Paint sizes
     * count those pixels, so a brush of 16 px paints 16 of the layer's pixels; 1 with no paint session.
     */
    val paintPixelsPerUnit: Float
        get() = paintSession?.let { sqrt(it.scaleX * it.scaleY) }?.takeIf { it.isFinite() && it > 0f } ?: 1f

    /**
     * Canvas units per unit of the painted layer's frame as the canvas shows it: the scale of the layer's transform,
     * which the tip ring and shape previews are drawn through. 1 with no paint session or an unscaled layer.
     */
    val paintShownScale: Float
        get() = paintSession?.frame?.let { sqrt(abs(it.a * it.d - it.b * it.c)) }?.takeIf { it.isFinite() && it > 0f } ?: 1f

    /** The painted layer's pixels per canvas unit as shown: how large a pixel of it looks against the canvas. */
    val paintPixelsPerShownUnit: Float
        get() = paintPixelsPerUnit / paintShownScale

    /** [paintPixelsPerShownUnit] the paint sizes were last chosen at: 1, the canvas, until a layer is painted. */
    private var paintSizeDensity = 1f

    /**
     * Converts every paint size to the layer just taken up so the tip keeps the size it had on screen: a layer with
     * ten pixels to each canvas unit takes ten times the pixels, so a brush picked on the canvas does not arrive as a
     * speck. The sizes stay in the layer's pixels and the readouts say so.
     */
    private fun keepPaintSizesOnScreen() {
        val density = paintPixelsPerShownUnit
        if (abs(density - paintSizeDensity) <= 1e-3f * paintSizeDensity) return
        val k = density / paintSizeDensity
        val limit = brushSizeLimit
        paintBrushSize = (paintBrushSize * k).coerceIn(1f, limit)
        paintPencilSize = (paintPencilSize * k).coerceIn(1f, limit)
        paintEraserSize = (paintEraserSize * k).coerceIn(1f, limit)
        paintShapeSize = (paintShapeSize * k).coerceIn(1f, limit)
        paintSizeDensity = density
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
        radius = paintSize / paintPixelsPerUnit / 2f,
        hardness = if (tool == CanvasTool.PAINT_PENCIL) 1f else paintHardness,
        antialias = tool != CanvasTool.PAINT_PENCIL,
        minRadius = 0.5f / paintPixelsPerUnit,
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

    /** Black in hand and white behind it, as Photoshop's default colours. */
    fun resetPaintColors() {
        paintColor = androidx.compose.ui.graphics.Color.Black
        paintSecondaryColor = androidx.compose.ui.graphics.Color.White
    }

    var isPainting by mutableStateOf(false)
    /** True while the pointer is picking a colour rather than drawing one: the eyedropper's own
     *  gesture, or an Alt + left press over any paint tool. */
    var isSampling by mutableStateOf(false)

    /**
     * True while Alt is held. The pointer reports its modifiers only when it moves, so the key itself
     * latches this - it is what the eyedropper pointer follows, and a pointer that waited for the mouse
     * to twitch would not be there when the artist looks for it. Pointer events then correct it, since a
     * release that lands outside the canvas never reaches the key handler.
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
    /** The box handle a [PlacementHandle.BOX] drag holds. */
    private var placementBoxHandle = BoundingHandle.NONE
    /** The ghost box handle under the pointer, or the one a drag holds. */
    var placementHover by mutableStateOf(BoundingHandle.NONE)
        private set
    private var placementDragStart: Offset? = null
    private var placementDragSnapshot: CreatePlacement? = null
    var glueDistance by mutableStateOf(6f)
    /** When true, mesh A and mesh B are swapped relative to selection order. */
    var glueSwapped by mutableStateOf(false)
    var glueSubTool by mutableStateOf(GlueSubTool.BRUSH)
    var glueWeightMode by mutableStateOf(GlueWeightMode.BALANCE)
    var glueStrokeA by mutableStateOf<Set<Int>>(emptySet())
    var glueStrokeB by mutableStateOf<Set<Int>>(emptySet())
    private var glueStroking = false
    private var glueErasing = false

    /** The kind of vertex group the weight brush paints; each mesh has one group of each kind. */
    var weightGroupKind by mutableStateOf(VertexGroupKind.PIN)
    /** How strokes and gradients combine with the group; Alt swaps adding and subtracting for one stroke. */
    var weightPaintMode by mutableStateOf(WeightPaintMode.ADD)
    private var weightStroking = false
    /** The mode the stroke in hand runs in, Alt included; the options bar keeps showing [weightPaintMode]. */
    var weightStrokeMode by mutableStateOf(WeightPaintMode.ADD)
        private set
    /** Per edited mesh, the strongest reach the current stroke or gradient has at each vertex. */
    var weightStroke by mutableStateOf<Map<String, FloatArray>>(emptyMap())
        private set
    /** Alt as of the last pointer move, so the brush ring shows a subtracting stroke before it starts. */
    private var weightAltHeld by mutableStateOf(false)

    /** The mode the stroke in hand runs in, or the one a press right now would start. */
    fun weightStrokeModeShown(): WeightPaintMode =
        if (weightStroking) weightStrokeMode else WeightPaint.effective(weightPaintMode, weightAltHeld)

    /** The gradient being dragged, in screen space: start and end. */
    var weightGradient by mutableStateOf<Pair<Offset, Offset>?>(null)
        private set
    var brushSelecting by mutableStateOf(false)

    /**
     * The vertex selection of every edited target, keyed by target id: the drawable id for a mesh, the
     * deformer id for a lattice or a rotation. Edit mode edits all selected meshes at once, so one map
     * holds them all, glued points always whole (see [WeldGroups]). Outside Edit it only ever holds
     * the primary target.
     */
    var selection by mutableStateOf<Map<String, Set<Int>>>(emptyMap())

    /** The primary target's slice of [selection] - all the single-target tools ever need. */
    var vertices: Set<Int>
        get() = target()?.id?.let { selection[it] }.orEmpty()
        set(value) {
            val id = target()?.id
            val rest = if (hierarchyMode == EditHierarchyMode.EDIT) selection else emptyMap()
            selection = when {
                id == null -> emptyMap()
                value.isEmpty() -> rest - id
                else -> rest + (id to value)
            }
        }
    var radius by mutableStateOf(48f)
    var strength by mutableStateOf(0.5f)
    var hardness by mutableStateOf(0.35f)
    var brushShape by mutableStateOf(BrushShape.CIRCLE)
    var brushAngle by mutableStateOf(0f)
    var brushAspect by mutableStateOf(1f)
    /** How the deform brushes fade from core to rim. */
    var brushFalloff by mutableStateOf(BrushFalloff.SMOOTH)
    /** The deform brushes only reach what is joined by edges to the part under the pointer. */
    var connectedOnly by mutableStateOf(false)
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
    var pathWidth by mutableStateOf(DeformPath.DEFAULT_WIDTH)
    var pathLevel by mutableStateOf(2)
    var pathHardness by mutableStateOf(DeformPath.DEFAULT_HARDNESS)
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
    private var deformationParameters by mutableStateOf<List<String>>(emptyList())
    var parameter: String?
        get() = deformationParameters.firstOrNull()
        set(value) { deformationParameters = listOfNotNull(value) }

    internal fun selectDeformationParameters(ids: Collection<ParameterId>) {
        deformationParameters = ids.map { it.raw }.distinct()
    }
    private var activeElementMode by mutableStateOf(0)
    var elementMode: Int
        get() = activeElementMode
        set(value) {
            if (activeElementMode != value) { selectedEdges = emptySet(); selectedFaces = emptySet() }
            activeElementMode = value.coerceIn(0, 2)
        }
    /** Whether the mode picks a mesh's points by vertex, edge or face ([elementMode]): Deform and Edit alike. */
    fun pointElementModes(): Boolean = hierarchyMode == EditHierarchyMode.EDIT || hierarchyMode == EditHierarchyMode.DEFORM

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

    /**
     * What the open Warp/Rotation placement, knife cut and path were started on. Their later inputs and confirm
     * are read against this capture, so an edit made elsewhere meanwhile is a conflict, not a reinterpretation.
     */
    private var placementInput: WorkspaceCanvasInputDraft<Unit, DrawableSpaceMapping>? = null
    private var knifeInput: WorkspaceCanvasInputDraft<MeshRefinementOps.KnifeAnchor, CanvasTarget>? = null
    private var pathInput: WorkspaceCanvasInputDraft<Pair<Float, Float>, CanvasTarget>? = null

    private fun draftScope() = viewModel.uiState.value.let {
        CanvasDraftScope(it.projectId, it.projectOpenGeneration, it.activeWorkspace.id, canvasId)
    }

    private fun <I, F> startInput(targetId: String, frame: F, inputs: List<I>): WorkspaceCanvasInputDraft<I, F>? {
        val expected = viewModel.currentWorkspaceState() ?: return null
        val source = state.previewModel ?: return null
        return WorkspaceCanvasInputDraft(expected, draftScope(), source, state.parameterValues.toMap(), targetId, frame, inputs)
    }

    /** The only say the artist has over snapping: how close, in screen pixels, counts as "on" a vertex or an
     *  edge. Snapping itself is not optional - outside this radius a click always drops a new point. */
    var knifeSnapRadius by mutableStateOf(10f)

    /** Where the next click would land: the snapped vertex or edge, or the pointer itself. */
    var knifeHover by mutableStateOf<Offset?>(null)

    /** "vertex" or "edge" while [knifeHover] sits on a snap target, null while it is a free point. */
    var knifeSnapKind by mutableStateOf<String?>(null)

    fun undoDraftPoint() {
        if (busy) return
        if (tool == CanvasTool.KNIFE) {
            knifeInput?.dropLast()
            knifeDraft = knifeDraft.dropLast(1)
            if (knifeDraft.isEmpty()) knifeInput = null
        } else if (drawingPath) {
            pathInput?.dropLast()
            draft = draft.dropLast(1)
            if (draft.isEmpty()) pathInput = null
        }
        error = null
    }

    /** The vertices the subdivide brush is currently covering; the stroke's union, not the last radius. */
    var subdivideEdges by mutableStateOf<Set<MeshElement.Edge>>(emptySet())
    private var subdividing = false
    val objectMode get() = hierarchyMode == EditHierarchyMode.SELECT
    private var selectedObjects by mutableStateOf<Set<String>>(emptySet())
    var objects: Set<String>
        get() = selectedObjects
        set(value) {
            val ordered = LinkedHashSet(value)
            if (selectedObjects == ordered) return
            glueSwapped = false
            selectedObjects = ordered
            viewModel.updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) { current ->
                current.copy(
                    selectedLayerIds = ordered,
                    selectedLayerId = current.selectedLayerId?.takeIf { it in ordered } ?: ordered.lastOrNull(),
                )
            }
        }
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
     * The anchor the box turns about and an Alt scale grows from, as a point of the box ([TransformFrame.anchorUv]),
     * with the selection it was placed on. Null, or another selection, leaves it on the pivot. Like [frameAngle] it
     * lasts for the tool session; being a point of the box, it rides along through every gesture.
     */
    private var transformAnchor by mutableStateOf<Pair<Any, Offset>?>(null)
    /** The anchor as a point of the box at press, and where it was on the screen, so a drag's box carries it along. */
    private var anchorUvAtPress = Offset(0.5f, 0.5f)
    private var frameAnchorAtPress = Offset.Zero
    /** True while the live gesture drags the anchor. */
    private var anchorDragging = false

    /** What the anchor belongs to: a different selection drops it back on the pivot. */
    private fun anchorKey(): Any = listOf(objectMode, objects, state.selectedLayerId, state.selectedDeformerId, selection, vertices)

    private fun currentAnchor(): Offset? = transformAnchor?.takeIf { it.first == anchorKey() }?.second

    /**
     * The layer SELECT picked on press. A marquee started on top of an object is still a marquee, so a
     * release that catches nothing has to fall back to this rather than replace the pick with the empty
     * set — a plain click jitters a few raw pixels, which is enough to count as a drag.
     */
    private var pressedObject: String? = null
    private var initialScreenPoints = emptyList<List<Offset>>()
    private var objectTargets = emptyList<CanvasTarget>()
    private var pendingObjects = emptyList<JsonObject>()
    /** The rectangle an object-mode box drag moves a file-imported layer to, when the drag only moves and scales it. */
    private var start = Offset.Zero
    private var previous = Offset.Zero
    private var targetAtPress: CanvasTarget? = null
    private var original: PuppetModel? = null
    private var pending: JsonObject? = null
    private var gestureState: String? = null
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
    /** State, so chrome that lasts only as long as the gesture (the drag guide) leaves on the release itself. */
    private var dragging by mutableStateOf(false)
    private var ctrlAtPress = false

    /** Active brush deformation vertex weights [0f..1f] for target points; non-null while a brush stroke is live. */
    var activeBrushWeights by mutableStateOf<FloatArray?>(null)
    /** Screen position where the active brush stroke was pressed. */
    var activeBrushCenter by mutableStateOf<Offset?>(null)

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
    val pose get() = viewModel.canvasPose(state).mapKeys { it.key.raw }

    /** Only the transform tool owns the box and its handles. */
    val drawsTransformBox get() =
        tool == CanvasTool.TRANSFORM && (objectMode || selectionHasExtent)

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
            if (objectMode) return transformTargets(model).isNotEmpty()
            if (hierarchyMode !in setOf(EditHierarchyMode.DEFORM, EditHierarchyMode.EDIT)) return false
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
            if (objectMode) return transformTargets(model).isNotEmpty()
            if (hierarchyMode !in setOf(EditHierarchyMode.DEFORM, EditHierarchyMode.EDIT)) return false
            val t = target() ?: return false
            return t.kind == "rotation" || vertices.any { it in 0 until t.count }
        }
    val editable get() = !busy && !state.workspaceEditBusy && !state.isGenerating && !state.isAnalyzing && state.historySnapshot != null

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
            kind = "mesh"; id = drawable.id.raw; parent = drawable.parentDeformerId; indices = drawable.mesh!!.indices
        } else return null
        if (cachedSource !== resolvedSource || cachedPose != state.parameterValues) {
            cachedSource = resolvedSource; cachedPose = state.parameterValues; cachedTargets.clear()
            val defaults = resolvedSource.parameters.associate { it.id to it.default }
            cachedWorlds = buildDeformerWorlds(resolvedSource.deformers, { p -> state.parameterValues[p] ?: defaults[p] ?: 0f }, { defaults[it] ?: 0f })
        }
        val worlds = cachedWorlds
        if (parent != null && worlds[parent] == null) return null
        return try {
            cachedTargets.getOrPut("$kind:$id") {
                CanvasTarget(
                    kind, id,
                    RigGeometryTools.geometry(resolvedSource, kind, id, sanitizedPose(resolvedSource)),
                    DrawableSpaceMapping(parent?.let { worlds[it] }),
                    indices,
                )
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            null
        }
    }

    /** Pose keys the current puppet does not have, or values outside its range, must not reach geometry(). */
    private fun sanitizedPose(model: PuppetModel): Map<String, Float> {
        val raw = state.parameterValues
        if (raw.isEmpty()) return emptyMap()
        val byId = model.parameters.associateBy { it.id.raw }
        val out = LinkedHashMap<String, Float>(raw.size)
        for ((id, value) in raw) {
            val parameter = byId[id.raw] ?: continue
            val finite = if (value.isFinite()) value else parameter.default
            out[id.raw] = finite.coerceIn(parameter.min, parameter.max)
        }
        return out
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
        if (t.kind != "rotation") return emptyList()
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
        selection = emptyMap()
        selectedEdges = emptySet()
        selectedFaces = emptySet()
    }

    /**
     * Drops index selections the document no longer has. A commit from another canvas, an undo or a
     * panel can change the mesh this canvas was editing.
     */
    fun reconcileWithDocument() {
        if (busy || dragging) return
        val layerIds = state.previewModel?.analysis?.layers?.mapTo(HashSet()) { it.source.id.raw }
        if (layerIds != null) {
            val nextObjects = objects.filterTo(LinkedHashSet()) { it in layerIds }
            if (nextObjects.size != objects.size) objects = nextObjects
        }
        val paths = state.previewModel?.rig?.puppet?.deformPaths?.mapTo(HashSet()) { it.id }
        if (paths != null && activePath != null && activePath !in paths) {
            activePath = null
            pathPoint = -1
        }
        val t = target()
        val counts = (preview ?: state.previewModel?.rig?.puppet)?.drawables.orEmpty()
            .associate { it.id.raw to (it.mesh?.vertexCount ?: 0) }
        val nextSelection = selection.mapValues { (id, set) ->
            val count = if (id == t?.id) t.count else counts[id] ?: 0
            set.filterTo(LinkedHashSet()) { it in 0 until count }
        }.filterValues { it.isNotEmpty() }
        if (nextSelection != selection) selection = nextSelection
        val faces = (t?.indices?.size ?: 0) / 3
        val nextFaces = selectedFaces.filterTo(LinkedHashSet()) { it in 0 until faces }
        if (nextFaces.size != selectedFaces.size) selectedFaces = nextFaces
        if (t == null || t.kind != "mesh") {
            if (selectedEdges.isNotEmpty()) selectedEdges = emptySet()
            return
        }
        val live = MeshTopology.uniqueEdges(t.indices)
        val nextEdges = selectedEdges.filterTo(LinkedHashSet()) { it in live }
        if (nextEdges.size != selectedEdges.size) selectedEdges = nextEdges
    }
    private fun coordinate(t: CanvasTarget) = canvasDeformationCoordinate(model, t.geometry.axes, pose, deformationParameters)

    /** Only structural mesh editing moves UVs; deformation writes the current pose. */
    private fun geometryCommand(t: CanvasTarget, points: FloatArray, ctrl: Boolean = false) =
        canvasGeometryCommand(hierarchyMode, t.kind, t.id, coordinate(t), points, ctrl)

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
                selectLayer(hit)
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
        // The session paints in the layer's own frame; the canvas shows it where the layer was moved, scaled or turned to.
        val frame = state.previewModel?.analysis?.source?.layers?.singleOrNull { it.id.raw == targetLid }?.transform
            ?: io.github.psd2live.project.LayerTransform.IDENTITY
        val handle = viewModel.beginPaintSession(targetLid) ?: return null
        val newSession = PaintSession(handle).also { it.frame = frame }
        paintSession = newSession
        keepPaintSizesOnScreen()
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
        paintSession?.dismiss()
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
        session.clear(tr("editor.paint.strokeClear"))
    }

    fun promptCommitPaintSession() {
        val session = paintSession ?: return
        if (!session.isDirty) return
        if (DepthSplit.isFrontLayer(state.previewModel, session.layerId)) {
            commitPaintSession(rebuildMesh = false)
            return
        }
        showRebuildMeshDialog = true
    }

    fun commitPaintSession(
        rebuildMesh: Boolean,
        summary: String? = null,
        preserveSourceRaster: Boolean = false,
    ) {
        val session = paintSession ?: return
        val currentPreview = state.previewModel ?: return
        val rebuild = rebuildMesh && !DepthSplit.isFrontLayer(currentPreview, session.layerId)
        showRebuildMeshDialog = false
        viewModel.savePaintSession(session.handle, rebuild, preserveSourceRaster,
            summary ?: tr("editor.paint.commitSummary", session.layerName)) {
            if (rebuild) viewModel.offerMeshSplit(listOf(session.layerId))
            paintSession = startPaintSession(session.layerId, forceReload = true)
            isPainting = false
            paintStrokeStart = null
            paintStrokeCurrent = null
            showRebuildMeshDialog = false
        }
    }

    fun screenToCanvasPixel(pos: Offset, viewport: CanvasViewport): Pair<Int, Int>? {
        val session = paintSession ?: return null
        val (fx, fy) = session.toFrame(viewport.canvasX(pos.x).toFloat(), viewport.canvasY(pos.y).toFloat())
        val cx = kotlin.math.floor(fx).toInt()
        val cy = kotlin.math.floor(fy).toInt()
        if (cx !in 0 until session.canvasWidth || cy !in 0 until session.canvasHeight) return null
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
        val argb = session.sample(pixel.first, pixel.second)
        if (((argb ushr 24) and 0xFF) == 0) return null
        return androidx.compose.ui.graphics.Color(
            red = ((argb ushr 16) and 0xFF) / 255f,
            green = ((argb ushr 8) and 0xFF) / 255f,
            blue = (argb and 0xFF) / 255f,
            alpha = ((argb ushr 24) and 0xFF) / 255f,
        )
    }

    /**
     * Whether the pointer is the eyedropper right now, Photoshop's way: the eyedropper tool always is,
     * and Alt turns any paint tool into one for as long as it is held - so the pointer shows the pipette
     * and a left click takes a colour without leaving the brush. A stroke already under way stays a
     * stroke, and Alt + right-drag is the tip being retuned, which shows the tip instead.
     */
    val eyedropperArmed: Boolean
        get() = hierarchyMode == EditHierarchyMode.PAINT && tool in PAINT_TOOLS && !adjustingBrush &&
            (tool == CanvasTool.PAINT_EYEDROPPER || isSampling || (altHeld && !dragging))

    /**
     * Where the sampling ring belongs, or null when no pick is under way. The ring is the pick in
     * progress, as in Photoshop: it appears with the button and follows the pointer until the button
     * comes up, while an armed eyedropper is shown by the pointer itself.
     */
    fun pickCursor(): Offset? = cursor?.takeIf {
        hierarchyMode == EditHierarchyMode.PAINT && tool in PAINT_TOOLS && isSampling
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
        val session = paintSession ?: return null
        return session.toFrame(viewport.canvasX(pos.x).toFloat(), viewport.canvasY(pos.y).toFloat())
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
        session.segment(start.first, start.second, end.first, end.second, paintTip(), paintColor, paintOpacity, erase)
        refreshPaintPreview()
    }

    /** Repaints the preview at most every ~40 ms: a gesture fires far more moves than frames. */
    private fun refreshPaintPreview() {
        val now = System.nanoTime()
        if (lastPaintBitmapAt != 0L && now - lastPaintBitmapAt < 40_000_000L) return
        lastPaintBitmapAt = now
        paintSession?.refreshPreview()
    }

    fun cancel() {
        skeletonEntrySerial++
        if (busy) return
        if (poseDrag != null) {
            poseDrag = null; draggingIkTargetId = null; ikTargetDragOrigin = null; ikTargetDragValues = null; ikTargetPoseDraft = null
            viewModel.cancelParameterScrub()
        }
        // Every tip edits pixels as it goes, so an abandoned gesture has to give them back.
        val session = paintSession
        if (isPainting && session != null) {
            session.abandonStroke()
            session.refreshPreview()
        }
        endBrushAdjust(cancel = true)
        preview = null; pending = null; dragging = false; targetAtPress = null; original = null; topologyFills = null
        swingHandle = null
        knifeDraft = emptyList(); knifeDrawableId = null; subdividing = false; subdivideEdges = emptySet()
        knifeHover = null; knifeSnapKind = null
        placementInput = null; knifeInput = null; pathInput = null
        placement = null; placementHandle = PlacementHandle.NONE; placementDragStart = null; placementDragSnapshot = null
        glueStroking = false
        weightStroking = false
        weightStroke = emptyMap()
        weightGradient = null
        weightStrokePose = null
        weightStrokePoints.clear()
        glueStrokeA = emptySet()
        glueStrokeB = emptySet()
        poseDrag = null
        activeBezierAnchor = null; activeBezierHandle = null
        activeBrushWeights = null; activeBrushCenter = null; endMeshStroke()
        marquee = emptyList(); draft = emptyList(); draftPathId = null; drawingPath = false
        pathDragging = false
        axis = null; gestureState = null; objectTargets = emptyList(); pendingObjects = emptyList()
        activeHandle = BoundingHandle.NONE
        initialBounds = null
        boxDrag = false; dragIndices = emptyList()
        endTransformBox()
        anchorDragging = false
        isPainting = false; isSampling = false; paintStrokeStart = null; paintStrokeCurrent = null
        // The frame belongs to the tool session, so cancelling is the only thing that drops it, anchor and all.
        frameAngle = 0f
        transformAnchor = null
        initialScreenPoints = emptyList()
    }

    fun resetSelection() {
        if (!busy) cancel()
        selection = emptyMap(); selectedEdges = emptySet(); selectedFaces = emptySet()
        activePath = null; pathPoint = -1; pathDragging = false
    }

    /**
     * Arms [next]. Creation tools ride the create strip and do not switch [hierarchyMode] (except
     * leaving paint). Other tools whose mode palette does not offer them still enter their mode first.
     */
    fun activateTool(next: CanvasTool) {
        if (busy) return
        skeletonEntrySerial++
        endTemporarySelection()
        if (next in CREATION_TOOLS) {
            activateCreationTool(next)
            return
        }
        createSessionReturnMode = null
        if (next in SKELETON_TOOLS) {
            // The skeleton tools are how the skeleton is reached, so arming one enters Skeleton mode on it.
            if (next == CanvasTool.SKELETON_EDIT) beginSkeletonEdit() else beginSkeletonPose()
            return
        }
        if (next !in palette()) {
            val mode = modeForTool(next)
            if (skeletonSelected) deselectSkeleton()
            if (!hasPartFor(mode)) { deferMode(mode, next); return }
            enterMode(mode)
        }
        cancel()
        tool = next
        error = null
        if (next == CanvasTool.LASSO_SELECT) selectionStyle = SelectionStyle.LASSO
        else if (next == CanvasTool.SELECT) selectionStyle = SelectionStyle.BOX
        if (next == CanvasTool.SELECT && objectMode) selection = emptyMap()
        clearHover()
    }

    /**
     * Arms a creation tool. With a valid selection, enters place-then-confirm; otherwise waits for a pick.
     */
    private fun activateCreationTool(next: CanvasTool) {
        // Creation works on drawables and deformers; the skeleton target gives way, keeping its edit.
        if (skeletonSelected) deselectSkeleton()
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
            selectDeformer(anchorId)
            when (kind) {
                CreatePlacementKind.PATH -> return // paths attach via other entry points
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
            if (layer != null) selectLayer(layer)
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

    /**
     * Selects an imported image for the transform tool in object mode: the import is committed with its mesh, and moving,
     * scaling or rotating it is the same mesh edit as for any object.
     */
    fun selectImportedLayer(layerId: String) {
        cancel()
        deferredMode = null
        hierarchyMode = EditHierarchyMode.SELECT
        tool = CanvasTool.TRANSFORM
        selectLayer(layerId)
        error = null
        clearHover()
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
        placementInput = null; pathInput = null
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
        if (kind == CreatePlacementKind.WARP || kind == CreatePlacementKind.ROTATION) {
            placementInput = startInput(anchorId, placementMapping(spaceParentId), emptyList<Unit>())
        }
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

    fun cancelPlacement() = clearPlacementUi()

    private fun clearPlacementUi() {
        placement = null
        placementInput = null; pathInput = null
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

    /** A new deformer's ID made from what creates it (StableIds): the same gesture on the same rig names the same object. */
    private fun newDeformerId(prefix: String, vararg inputs: Any?): String =
        StableIds.fresh(StableIds.stem(prefix, *inputs)) { id -> model.deformers.any { it.id.raw == id } }

    private fun commitPlacedWarp(p: CreatePlacement) {
        val id = newDeformerId("Warp_", p)
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", id)
            put("name", p.name)
            put("rows", p.rows)
            put("columns", p.cols)
            if (p.partId != null) put("part_id", p.partId!!)
            // Bounds already parent-local, as the command takes them.
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
        val input = placementInput ?: return
        val controls = try {
            RigBezierJournal.prepare(CanvasEdits.apply(input.model.rig.puppet, cmd), input.model.config.rigEdits, "divisions", buildJsonObject {
                put("target", "warp:$id"); put("rows", p.bezierRows); put("columns", p.bezierCols)
            })
        } catch (failure: Exception) { error = failure.message; return }
        commitPlacedInput(input, listOf(cmd, controls), id)
    }

    private fun commitPlacedRotation(p: CreatePlacement) {
        val id = newDeformerId("Rotation_", p)
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
        commitPlacedInput(placementInput ?: return, listOf(cmd), id)
    }

    /** The ghost stays up until the write lands, so a refused one can be adjusted or cancelled. */
    private fun commitPlacedInput(input: WorkspaceCanvasInputDraft<Unit, DrawableSpaceMapping>, commands: List<JsonObject>, id: String) {
        commitInput(input, commands) {
            if (placementInput === input) { placementInput = null; placement = null }
            finishCreateSession(id)
        }
    }

    /** Confirms a multi-input draft against its own start; a refused write leaves the draft open for Esc. */
    private fun <I, F> commitInput(input: WorkspaceCanvasInputDraft<I, F>, commands: List<JsonObject>, onSuccess: () -> Unit) {
        topologyFills = null
        if (!editable || !input.open) return
        when (val submit = input.submit(draftScope(), JsonArray(commands))) {
            is CanvasDraftSubmit.Rejected -> error = submit.failure
            CanvasDraftSubmit.Unchanged -> onSuccess()
            is CanvasDraftSubmit.Write -> {
                preview = submit.preview; busy = true; error = null
                viewModel.saveAuthoringEdits(submit.state, submit.edits) { failure ->
                    busy = false; preview = null; error = failure
                    if (input.settle(failure)) onSuccess()
                }
            }
        }
    }

    /** Mapping for [CreatePlacement.spaceParentId]; root uses identity (model with camera Y-flip). */
    private fun placementMapping(spaceParentId: String?): DrawableSpaceMapping {
        placementInput?.takeIf { placement?.spaceParentId == spaceParentId }?.let { return it.frame }
        val source = model
        if (cachedSource !== source || cachedPose != state.parameterValues) {
            cachedSource = source
            cachedPose = state.parameterValues
            cachedTargets.clear()
            val defaults = source.parameters.associate { it.id to it.default }
            cachedWorlds = buildDeformerWorlds(
                source.deformers,
                { p -> state.parameterValues[p] ?: defaults[p] ?: 0f },
                { defaults[it] ?: 0f },
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
        return when (p.kind) {
            CreatePlacementKind.WARP -> placementScreenRectOf(p, viewport)
            else -> null
        }
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

    /** The Warp ghost as a transform box: upright, centred on its rectangle, with its anchor. */
    fun placementFrame(viewport: CanvasViewport): TransformFrame? = placement?.let { placementFrameOf(it, viewport) }

    private fun placementFrameOf(p: CreatePlacement, viewport: CanvasViewport): TransformFrame? {
        val r = placementScreenRectOf(p, viewport) ?: return null
        return TransformFrame(BoundingBox(r.left, r.top, r.right, r.bottom), r.center, 0f).withAnchor(p.anchor)
    }

    private fun hitPlacementHandle(pos: Offset, viewport: CanvasViewport): PlacementHandle {
        val p = placement ?: return PlacementHandle.NONE
        when (p.kind) {
            CreatePlacementKind.WARP -> {
                val frame = placementFrameOf(p, viewport) ?: return PlacementHandle.NONE
                placementBoxHandle = transformHandleAt(pos, frame, PLACEMENT_HANDLES)
                return if (placementBoxHandle == BoundingHandle.NONE) PlacementHandle.NONE else PlacementHandle.BOX
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
     * Drag edits parent-local state. The ghost box drags as the Transform tool's box does - its corners and edges
     * scale (Shift keeps the aspect, Alt grows from the anchor) and its anchor moves - on the screen rectangle, which
     * a Warp then inverts with corner-matched seeds (same reach-uncapped inverse as [local]); body/tip move via
     * local deltas.
     */
    private fun applyPlacementDrag(pos: Offset, viewport: CanvasViewport, shift: Boolean, alt: Boolean) {
        val start = placementDragStart ?: return
        val snap = placementDragSnapshot ?: return
        val p = placement ?: return
        val mapping = placementMapping(snap.spaceParentId)
        if (placementHandle == PlacementHandle.BOX && placementBoxHandle != BoundingHandle.BODY) {
            val frame = placementFrameOf(snap, viewport) ?: return
            if (placementBoxHandle == BoundingHandle.ANCHOR) {
                placement = p.copy(anchor = frame.anchorDraggedTo(pos))
                return
            }
            val box = TransformDrag(placementBoxHandle, frame.bounds, frame.pivot, 0f, start, frame.anchor)
                .apply(pos, null, shift, alt).bounds
            when (p.kind) {
                CreatePlacementKind.WARP -> {
                    val local = screenAabbToLocalBounds(Rect(box.minX, box.minY, box.maxX, box.maxY), viewport, snap, mapping)
                    placement = p.copy(localX = local[0], localY = local[1], localW = local[2], localH = local[3])
                }
                else -> {}
            }
            return
        }
        when (p.kind) {
            CreatePlacementKind.WARP -> {
                val seed = (snap.localX + snap.localW / 2f) to (snap.localY + snap.localH / 2f)
                val a = placementScreenToLocal(start, viewport, mapping, seed)
                val b = placementScreenToLocal(pos, viewport, mapping, seed)
                placement = p.copy(
                    localX = snap.localX + (b.first - a.first),
                    localY = snap.localY + (b.second - a.second),
                )
            }
            CreatePlacementKind.ROTATION -> {
                when (placementHandle) {
                    PlacementHandle.PIVOT -> {
                        val seed = snap.originX to snap.originY
                        val a = placementScreenToLocal(start, viewport, mapping, seed)
                        val b = placementScreenToLocal(pos, viewport, mapping, seed)
                        val rdx = b.first - a.first
                        val rdy = b.second - a.second
                        placement = p.copy(
                            originX = snap.originX + rdx, originY = snap.originY + rdy,
                            tipX = snap.tipX + rdx, tipY = snap.tipY + rdy,
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
            CreatePlacementKind.PATH -> {}
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
        selection = emptyMap()
        clearHover()
    }

    /** Wait in Select for a mesh pick, then re-arm [tool]. */
    private fun deferCreation(tool: CanvasTool) {
        val request = DeferredMode(EditHierarchyMode.SELECT, tool)
        if (hierarchyMode != EditHierarchyMode.SELECT) {
            deferredMode = null
            cancel()
            hierarchyMode = EditHierarchyMode.SELECT
            selection = emptyMap()
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
        if (next != EditHierarchyMode.SKELETON) skeletonEntrySerial++
        endTemporarySelection()
        if (next == EditHierarchyMode.SKELETON) {
            if (hierarchyMode == EditHierarchyMode.SKELETON) return
            if (placement != null) cancelPlacement()
            // Without an armature the mode waits on the canvas's create button rather than proposing one.
            enterSkeletonMode(if (committedSkeleton?.enabled == true) CanvasTool.SKELETON_POSE else CanvasTool.SKELETON_EDIT)
            return
        }
        if (!hasPartFor(next)) { deferMode(next, null); return }
        enterMode(next)
    }

    private data class TemporarySelection(
        val mode: EditHierarchyMode,
        val tool: CanvasTool,
        val selection: Map<String, Set<Int>>,
        val objects: Set<String>,
        val layerId: String?,
        val deformerId: String?,
        val skeleton: Boolean,
        val deferred: DeferredMode?,
    )

    private var temporarySelection: TemporarySelection? = null
    internal val temporarilySelecting get() = temporarySelection != null

    /** Suspend the mode without ending paint or skeleton drafts. Key repeat must not overwrite it. */
    internal fun beginTemporarySelection(): Boolean {
        if (temporarilySelecting) return true
        if (busy || inGesture || adjustingBrush || drawingPath || placement != null || state.previewModel == null) return false
        temporarySelection = TemporarySelection(hierarchyMode, tool, selection, objects,
            state.selectedLayerId, state.selectedDeformerId, skeletonSelected, deferredMode)
        deferredMode = null
        hierarchyMode = EditHierarchyMode.SELECT
        tool = CanvasTool.SELECT
        selection = emptyMap()
        clearHover()
        return true
    }

    /** Keep the new object pick, restoring the old mode and tool whenever the new target supports them. */
    internal fun endTemporarySelection() {
        val previous = temporarySelection ?: return
        temporarySelection = null
        if (inGesture) cancel()
        val sameTarget = objects == previous.objects && state.selectedLayerId == previous.layerId &&
            state.selectedDeformerId == previous.deformerId && skeletonSelected == previous.skeleton
        hierarchyMode = previous.mode
        tool = previous.tool
        selection = if (sameTarget) previous.selection else emptyMap()
        deferredMode = previous.deferred
        if (previous.mode == EditHierarchyMode.SKELETON && !skeletonSelected) {
            if (takeSkeleton(allowEmpty = true)) switchSkeletonTool(previous.tool)
            else hierarchyMode = EditHierarchyMode.SELECT
        }
        if (previous.deferred != null) {
            resolveDeferredMode()
        } else if (!hasPartFor(previous.mode)) {
            deferMode(previous.mode, previous.tool)
        } else if (previous.mode == EditHierarchyMode.PAINT) {
            startPaintSession(forceReload = false)
        }
        clearHover()
    }

    internal fun toggleQuickPreview() {
        if (busy || inGesture || adjustingBrush || drawingPath || placement != null) return
        endTemporarySelection()
        val current = viewModel.uiState.value.activeWorkspace.canvases.firstOrNull { it.id == canvasId } ?: return
        showCanvasMode(if (current.mode == CanvasMode.PREVIEW) CanvasMode.EDIT else CanvasMode.PREVIEW)
        viewModel.requestCanvasFocus(canvasId)
    }

    /** Switches this canvas between editing and preview, as the canvas mode menu's Preview row does. */
    fun showCanvasMode(mode: CanvasMode) {
        if (busy) return
        viewModel.setCanvasMode(canvasId, mode)
    }

    /** The left toolbar's tools for the current mode. */
    fun palette(): List<CanvasTool> = toolbarGroups(hierarchyMode).flatten()

    /**
     * Re-fits the mode to a target that changed kind — the skeleton picked, or dropped for a layer. The
     * mode stays if it still has something to act on, and falls back to Object mode otherwise.
     */
    private fun settleModeOnTarget() {
        if (busy) return
        // Skeleton mode is the skeleton as the target; once something else is picked, it has nothing left to do.
        if (hierarchyMode == EditHierarchyMode.SKELETON && !skeletonSelected) { enterMode(EditHierarchyMode.SELECT); return }
        if (!hasPartFor(hierarchyMode)) { enterMode(EditHierarchyMode.SELECT); return }
        if (tool !in palette()) {
            cancel()
            tool = palette().first()
        }
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
        // Skeleton mode brings its own target, offering to create an armature when there is none.
        mode == EditHierarchyMode.SKELETON -> state.previewModel != null
        // With the skeleton picked there is no drawable for the other modes to work on.
        skeletonSelected -> false
        // No rig, no part: the canvas that would pick one is not there either, and there is no model for
        // [target] to read. The request waits, which is what it does anyway.
        state.previewModel == null -> false
        // Paint repaints one layer's pixels. A deformer is a target these modes can edit but not paint, and
        // entering paint mode on one would leave the session and the tools alike with nothing to draw on.
        mode == EditHierarchyMode.PAINT -> target(deformerId = null)?.kind == "mesh"
        // Vertex groups live on meshes.
        mode == EditHierarchyMode.SIMULATE -> target()?.kind == "mesh"
        else -> target() != null
    }

    /**
     * The mode a tool belongs to when the current palette does not offer it.
     * Creation tools are handled separately and never force Edit.
     */
    internal fun modeForTool(tool: CanvasTool): EditHierarchyMode = when {
        tool == CanvasTool.TRANSFORM -> EditHierarchyMode.SELECT
        tool in SKELETON_TOOLS -> EditHierarchyMode.SKELETON
        tool in PAINT_TOOLS -> EditHierarchyMode.PAINT
        tool in WEIGHT_TOOLS -> EditHierarchyMode.SIMULATE
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
                activateCreationTool(request.tool)
                return
            }
            if (request.tool == CanvasTool.CREATE_WARP && warpAddTo == WarpAddTo.CHILD_OF_SELECTED_DEFORMER) {
                if (state.selectedDeformerId == null) return
                deferredMode = null
                activateCreationTool(request.tool)
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
        // Leaving Skeleton mode keeps an open edit, and gives the skeleton up as the target unless the mode
        // gone to is Object mode, which can hold it - when there is one. Entering it takes the skeleton.
        if (next != EditHierarchyMode.SKELETON) {
            commitSkeletonDraft()
            if (skeletonSelected && (next != EditHierarchyMode.SELECT || committedSkeleton == null)) skeletonSelected = false
        } else if (!takeSkeleton(allowEmpty = true)) {
            return
        }
        hierarchyMode = next
        // Only Edit edits several meshes; any other mode keeps the primary's slice alone.
        if (next != EditHierarchyMode.EDIT) selection = target()?.id?.let { id -> selection.filterKeys { it == id } }.orEmpty()
        if (prev == EditHierarchyMode.PAINT && next != EditHierarchyMode.PAINT) {
            discardPaintSession()
        }
        if (next == EditHierarchyMode.PAINT) {
            startPaintSession(forceReload = true)
        } else if (next == EditHierarchyMode.SELECT) {
            selection = emptyMap()
        } else if (next == EditHierarchyMode.DEFORM) {
            if (editLevel == 2) ensureBezierState()
        }
        if (tool !in palette()) {
            tool = if (next == EditHierarchyMode.SKELETON && committedSkeleton?.enabled != true) CanvasTool.SKELETON_EDIT else palette().first()
            if (objectMode) selection = emptyMap()
        }
        if (next == EditHierarchyMode.SKELETON && tool == CanvasTool.SKELETON_EDIT && skeletonDraft == null) openSkeletonDraft()
        clearHover()
    }

    /**
     * Whether the deform levels apply to the target in hand: only a warp has a lattice of its own for each, its grid
     * points at level 1 and its Bezier handles at level 2; everything else deforms alike at either.
     */
    fun deformLevelsShown(): Boolean = hierarchyMode == EditHierarchyMode.DEFORM && target()?.kind == "warp"

    @JvmName("changeEditLevel")
    fun setEditLevel(level: Int) {
        editLevel = level
        // The lattice's points take no edits at the Bezier level, so a selection of them would only be moved by
        // the transform box unseen.
        if (level == 2 && target()?.kind == "warp") selection = emptyMap()
        if (level == 2 && hierarchyMode == EditHierarchyMode.DEFORM) {
            ensureBezierState()
        }
        clearHover()
    }

    val warpBezierDivisions: Map<String, Pair<Int, Int>> get() = model.deformers.filterIsInstance<Deformer.Warp>().associate {
        it.id.raw to RigBezierJournal.divisions(state.previewModel?.config?.rigEdits ?: state.rigEdits, it.id.raw)
    }
    private var bezierTargetId: String? = null
    private var bezierSourcePoints: FloatArray? = null
    private var bezierResidual = FloatArray(0)

    private fun bezierRequest(t: CanvasTarget) = buildJsonObject {
        put("target", "warp:${t.id}")
        put("coordinate", buildJsonObject { coordinate(t).forEach { (id, value) -> put(id, value) } })
        put("pose", buildJsonObject { pose.forEach { (id, value) -> put(id, value) } })
    }

    fun setBezierDivisionsLive(token: String, rows: Int, columns: Int) {
        val t = target()?.takeIf { it.kind == "warp" } ?: return
        viewModel.applyWarpControlField(token, "warp_bezier_divisions", JsonObject(bezierRequest(t) + buildJsonObject {
            put("rows", rows); put("columns", columns)
        }))
    }

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
                    val controls = RigBezierJournal.read(model, state.previewModel?.config?.rigEdits ?: state.rigEdits,
                        t.id, coordinate(t), pose)
                    bezierState = controls.state
                    bezierResidual = controls.residual
                    bezierTargetId = t.id
                    bezierSourcePoints = t.geometry.points.copyOf()
                }
            }
        } else {
            bezierState = null
        }
    }

	/** Refit Level-2 Bezier anchors and handles from the current warp lattice and persist the sampled result. */
	fun resetBezierControlPoints() {
		val t = target() ?: return
		if (t.kind != "warp" || !editable || busy) return
		val warp = model.deformers.filterIsInstance<Deformer.Warp>().firstOrNull { it.id.raw == t.id } ?: return
		clearHover()
		commit(RigBezierJournal.prepare(model, state.previewModel?.config?.rigEdits ?: state.rigEdits, "reset", bezierRequest(t)))
	}

    fun clearHover() {
        cursor = null
        altHeld = false
        hoveredVertex = null; hoveredMeshVertex = null
        hoveredPathPoint = null
        hoveredHandle = BoundingHandle.NONE
        placementHover = BoundingHandle.NONE
        hoveredBezierAnchor = null
        hoveredBezierHandle = null
        poseHover = null
        isHoveringObject = false
        // The hierarchy panel publishes the same pair from its own hover, so only retract a highlight
        // this editor actually put up — a tool switch must not blink out the panel's.
        if (hoveredPick != null) setHoveredItem(null, null)
        hoveredPick = null
        shrinks = inflateInvert
    }

    /**
     * What a live box drag is doing, for the readout beside the pointer: how far it has turned, how much it has
     * scaled each side by, or how far it has moved in canvas pixels. Null outside a box drag that has moved.
     */
    fun transformReadout(viewport: CanvasViewport): String? {
        if (!boxDrag || !moved || tool != CanvasTool.TRANSFORM) return null
        val b0 = initialBounds ?: return null
        val b = currentDragBounds ?: return null
        return when {
            activeHandle == BoundingHandle.ROTATE -> {
                val turned = ((frameAngle - frameAngleAtPress) % 360f + 540f) % 360f - 180f
                String.format(java.util.Locale.ROOT, "%+.1f°", turned)
            }
            activeHandle.scales -> {
                fun percent(now: Float, was: Float) = if (was <= 1e-3f) 100f else now / was * 100f
                String.format(java.util.Locale.ROOT, "%.0f%% × %.0f%%", percent(b.width, b0.width), percent(b.height, b0.height))
            }
            activeHandle == BoundingHandle.BODY -> {
                // The box travels in the frame; turned back out of it, that is the screen move the points made.
                val travel = Offset(b.minX - b0.minX, b.minY - b0.minY).rotateVector(frameAngleAtPress) / viewport.scale.toFloat()
                String.format(java.util.Locale.ROOT, "Δ %.1f, %.1f", travel.x, travel.y)
            }
            else -> null
        }
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
        if (tool != CanvasTool.TRANSFORM) return null
        val bounds = currentDragBounds
        if (bounds != null) return TransformFrame(bounds, framePivotAtPress, frameAngle).withAnchor(anchorUvAtPress)
        return selectionFrame(viewport)?.withAnchor(currentAnchor())
    }

    /**
     * The frame the selected points make, in the tool's own frame orientation.
     *
     * The frame hugs exactly the selected vertices rather than the whole mesh they belong to, which is
     * what lets a box around three points of a cheek read as those three points.
     */
    private fun selectionFrame(viewport: CanvasViewport): TransformFrame? {
        if (objectMode) {
            val points = transformTargets(model).flatMap { screen(it.geometry.points, it, viewport) }
            return frameOf(points, points.indices.toSet(), frameAngle)
        }
        if (editsMeshes()) {
            // One box around the selected points of every edited mesh. A glued point is one point, so
            // it counts once: a lone glued point has no box, exactly like a lone vertex, instead of a
            // hair-thin box whose scale handles sit on the point and tear its two sides apart.
            val welds = weldGroups()
            val seen = HashSet<MeshVertex>()
            val points = editScreens(viewport).flatMap { (t, screen) ->
                selection[t.id].orEmpty().mapNotNull { index ->
                    screen.getOrNull(index)?.takeIf { seen.add(welds.members(MeshVertex(t.id, index)).first()) }
                }
            }
            return frameOf(points, points.indices.toSet(), frameAngle)
        }
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
        if (objectMode || t.kind == "rotation") (0 until t.count).toSet()
        else selection[t.id].orEmpty().filter { it in 0 until t.count }.toSet()

    private var cachedGeometrySource: PuppetModel? = null
    private var cachedGeometryPose = emptyMap<ParameterId, Float>()
    private var cachedGeometry: DeformedGeometry? = null

    /** Bounds of [cachedGeometry]'s drawables; the hover asks for them on every pointer move. */
    private var cachedBoundsGeometry: DeformedGeometry? = null
    private var cachedBounds = emptyMap<String, Bounds>()

    private var cachedCornersPoints: Map<String, FloatArray>? = null
    private var cachedCornersViewport: CanvasViewport? = null
    private var cachedCorners = emptyMap<String, java.awt.geom.Area>()

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
        val pose = viewModel.canvasPose(state)
        if (cachedGeometrySource !== drawn.rig.puppet || cachedGeometryPose != pose) {
            cachedGeometrySource = drawn.rig.puppet
            cachedGeometryPose = pose
            cachedGeometry = RigCanvasSupport.evaluate(drawn, pose)
        }
        return cachedGeometry
    }

    /** Layers under [pos], in the order a Ctrl-click cycles them. Empty when nothing is pickable. */
    private fun layerCandidates(pos: Offset, viewport: CanvasViewport): List<String> {
        val source = state.previewModel ?: return emptyList()
        val geometry = evaluatedGeometry() ?: return emptyList()
        if (cachedBoundsGeometry !== geometry) {
            cachedBounds = RigCanvasSupport.boundsByDrawable(geometry)
            cachedBoundsGeometry = geometry
        }
        return RigCanvasSupport.hitLayers(
            source,
            cachedBounds,
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
        // The marks are constructive Areas, rebuilt only when the lattices or the camera move, not per hover.
        val points = warpOutlinePoints(source, ids)
        if (cachedCornersPoints !== points || cachedCornersViewport != viewport) {
            cachedCorners = RigCanvasSupport.deformerCorners(RigCanvasSupport.deformerOutlines(source, points), viewport)
            cachedCornersPoints = points
            cachedCornersViewport = viewport
        }
        return cachedCorners
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
        if (skeletonSelected) return emptySet()
        val preview = drawnPreview ?: return emptySet()
        val ids = visibleCanvasGuideIds(preview, state, warp = true)
        // Deform and Edit draw the warp in hand on the overlay, as editable points; the guide's copy of it would
        // lie under those in other colours and sizes.
        val held = target()?.takeIf { it.kind == "warp" && (hierarchyMode == EditHierarchyMode.DEFORM || hierarchyMode == EditHierarchyMode.EDIT) }
        return if (held != null) ids - held.id else ids
    }

    /**
     * The rotations the canvas is drawing global guides for right now.
     * [TabViewOptions.showRotation] owns the channel — hierarchy mode only seeds presets, never forces.
     */
    fun activeRotationIds(): Set<String> {
        if (skeletonSelected) return emptySet()
        val preview = drawnPreview ?: return emptySet()
        return visibleCanvasGuideIds(preview, state, warp = false)
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
        if (posing()) {
            hoverPose(pos, viewport)
            return
        }
        hoveredBezierAnchor = null
        hoveredBezierHandle = null
        hoveredVertex = null; hoveredMeshVertex = null
        hoveredPathPoint = null
        hoveredHandle = BoundingHandle.NONE
        isHoveringObject = false
        hoveredPick = null
        // A swing session locks the canvas, so nothing under the cursor is advertised as pickable.
        val swing = viewModel.swingSession
        if (swing != null) {
            setHoveredItem(null, null)
            swingHover = swingHandle ?: if (swing.busy) null else hitSwingHandle(pos, viewport)
            return
        }
        swingHover = null

        // A Warp or Layer ghost owns the canvas while it is placed, as its press does.
        placementHover = BoundingHandle.NONE
        placement?.takeIf { it.kind == CreatePlacementKind.WARP }?.let { p ->
            placementHover = placementFrameOf(p, viewport)?.let { transformHandleAt(pos, it, PLACEMENT_HANDLES) } ?: BoundingHandle.NONE
            return
        }

        if (tool in CREATION_TOOLS) {
            if (tool == CanvasTool.GLUE) {
                // The glue brush works on the edit set; hovering it must not advertise object picks.
            } else if (tool == CanvasTool.CREATE_DEFORM_PATH) {
                val t = target()
                if (t != null && t.kind == "mesh") {
                    hoveredPathPoint = pathControlHit(pos, t, viewport)?.second
                }
            }
            return
        }

        if (tool == CanvasTool.TRANSFORM) {
            val frame = transformFrame(viewport)
            hoveredHandle = frame?.let { transformHandleAt(pos, it) } ?: BoundingHandle.NONE
            return
        }

        // Resolve object hover through the same mesh hit test as a press.
        if (hierarchyMode == EditHierarchyMode.SELECT) {
            if (tool == CanvasTool.SELECT) {
                val bone = objectModeBoneHit(pos, viewport)
                if (poseHover?.boneId != bone?.boneId || poseHover?.tip != bone?.tip) poseHover = bone
                if (bone != null) {
                    isHoveringObject = true
                    setHoveredItem(null, null)
                    return
                }
                val pick = objectPick(pos, viewport, ctrl)
                hoveredPick = pick
                isHoveringObject = pick != null
                setHoveredItem(pick?.layerId, pick?.deformerId)
            } else if (tool in SELECTION_TOOLS) {
                val hit = layerCandidates(pos, viewport).firstOrNull()
                isHoveringObject = hit != null
                setHoveredItem(hit, null)
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
        if (editsMeshes()) {
            val frame = transformFrame(viewport)
            hoveredHandle = frame?.let { transformRingAt(pos, it) } ?: BoundingHandle.NONE
            val hit = if (hoveredHandle != BoundingHandle.NONE) null else pickEditVertex(pos, viewport)
            hoveredMeshVertex = hit
            hoveredVertex = hit?.takeIf { it.mesh == t?.id }?.index
            return
        }
        hoveredMeshVertex = null
        val points = if (t != null) screen(t.geometry.points, t, viewport) else emptyList()
        // A rotation deformer never gets a box, so its points are screened for the vertex hover alone.
        val frame = transformFrame(viewport)
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
        // A swing session locks the canvas to its handles, so the tool's own pointer never applies.
        if (viewModel.swingSession != null) return if (swingHandle != null || swingHover != null) hand else arrow
        if (eyedropperArmed) return CanvasCursors.eyedropper
        if (dragging) {
            if (placementHandle == PlacementHandle.BOX) return CanvasCursors.transform(placementBoxHandle, 0f)
            if (marquee.isNotEmpty()) return cross
            if (tool in DEFORM_BRUSH_TOOLS || tool == CanvasTool.BRUSH_SELECT || tool == CanvasTool.SUBDIVIDE || tool == CanvasTool.KNIFE) return cross
            if (boxDrag || anchorDragging) return CanvasCursors.transform(activeHandle, frameAngle)
            if (activeBezierAnchor != null || activeBezierHandle != null || poseDrag != null) return hand
            return move
        }
        if (placementHover != BoundingHandle.NONE) return CanvasCursors.transform(placementHover, 0f)
        if (tool in CREATION_TOOLS) {
            return if (tool == CanvasTool.GLUE) hand else cross
        }
        if (tool == CanvasTool.BRUSH_SELECT || tool in DEFORM_BRUSH_TOOLS || tool == CanvasTool.SUBDIVIDE || tool == CanvasTool.KNIFE) return cross
        if (tool == CanvasTool.LASSO_SELECT) return cross
        // Painting gets no crosshair: the cursor is exactly where the tip ring already is, and a cross
        // over the pixels being judged is worse than no mark at all.

        if (hoveredBezierHandle != null || hoveredBezierAnchor != null || poseHover != null) return hand
        if (hoveredHandle != BoundingHandle.NONE) return CanvasCursors.transform(hoveredHandle, frameAngle)
        if (hoveredVertex != null || hoveredMeshVertex != null || hoveredPathPoint != null) return hand

        if (hierarchyMode == EditHierarchyMode.SELECT) {
            if (tool == CanvasTool.SELECT) return if (isHoveringObject) hand else arrow
        } else {
            if (tool == CanvasTool.SELECT) return arrow
        }
        return arrow
    }

    fun commit(command: JsonObject) {
        commitBatch(listOf(command))
    }

    /** The layers a Select-mode box drag moves as wholes, and the canvas transform it applies to all of them. */
    private class LayerMove(val layerIds: List<String>, val transform: io.github.psd2live.project.LayerTransform)
    private var pendingLayers: LayerMove? = null

    /**
     * The whole-layer move a box drag makes, or null when it moves anything else: every mesh of each layer it touches,
     * all their points, at rest. Such a drag is a [io.github.psd2live.application.WorkspaceLayerTransform] - the meshes
     * move and the layer remembers where its pixels now show, so painting follows - rather than a bare mesh edit.
     */
    private fun wholeLayerTransform(targets: List<CanvasTarget>, worlds: List<FloatArray>, movedSets: List<Set<Int>>): LayerMove? {
        if (targets.isEmpty() || targets.any { it.kind != "mesh" }) return null
        if (targets.indices.any { movedSets[it].size != targets[it].count || targets[it].count < 3 }) return null
        val model = state.previewModel ?: return null
        val atRest = model.rig.puppet.parameters.all { p -> abs((pose[p.id.raw] ?: p.default) - p.default) < 1e-4f }
        if (!atRest) return null
        val layerOf = model.rig.layerIdByDrawableId
        val layers = targets.map { layerOf[it.id] ?: return null }.distinct()
        val ids = targets.mapTo(HashSet()) { it.id }
        if (layers.any { layer -> layerOf.any { (mesh, owner) -> owner == layer && mesh !in ids } }) return null
        // Canvas points (y down) before and after the drag, fitted with one affine map.
        val before = ArrayList<Float>(); val after = ArrayList<Float>()
        targets.forEachIndexed { index, item ->
            val start = item.mapping.localToWorld(item.geometry.points)
            for (i in 0 until start.size / 2) {
                before += start[i * 2]; before += -start[i * 2 + 1]
                after += worlds[index][i * 2]; after += -worlds[index][i * 2 + 1]
            }
        }
        val fit = io.github.psd2live.core.AffineFit.fit(before.toFloatArray(), after.toFloatArray()) ?: return null
        val transform = runCatching { io.github.psd2live.project.LayerTransform.of(fit.toList()) }.getOrNull() ?: return null
        return LayerMove(layers, transform)
    }

    /** Commits a whole-layer move; an edit the workspace refuses leaves the layers where they were, with its reason. */
    private fun commitLayerTransform(move: LayerMove) {
        if (!editable) { preview = null; endTransformBox(); return }
        val expected = gestureState ?: viewModel.currentWorkspaceState() ?: run { preview = null; endTransformBox(); return }
        val names = move.layerIds.map { id -> state.previewModel?.analysis?.source?.layers?.singleOrNull { it.id.raw == id }?.name ?: id }
        val operations = move.layerIds.map { id ->
            io.github.psd2live.application.WorkspaceDocumentOperation(io.github.psd2live.application.WorkspaceLayerTransform.OP, buildJsonObject {
                put("layer_id", id); put("matrix", JsonArray(move.transform.toList().map(::JsonPrimitive)))
            })
        }
        busy = true; error = null
        viewModel.saveDocumentEdits(expected, tr("editor.layerTransformed", names.joinToString(", ")), operations) { failure ->
            busy = false
            preview = null; pending = null; gestureState = null; endTransformBox()
            if (failure != null) error = failure
        }
    }

    /** Returns false when the edit never reached history, so the caller can drop its preview. */
    private fun commitBatch(commands: List<JsonObject>, onSuccess: (() -> Unit)? = null): Boolean {
        // The provisional fill describes faces of the mesh as it stands; after any commit those indices
        // may mean something else, so it lives exactly as long as the op that set it. topology() sets it
        // after calling through here, which is why clearing on the way in is enough.
        topologyFills = null
        if (!editable) { endTransformBox(); return false }
        val expected = gestureState ?: viewModel.currentWorkspaceState()
        if (expected == null) { endTransformBox(); return false }
        try {
            val result = RigAuthoringJournal.compile(state.previewModel!!.rig.puppet, JsonArray(commands))
            // A gesture that ends where it started compiles to nothing: dragging a vertex back onto
            // itself, or a numeric transform applied with its identity values. Dispatching that would ask
            // the workspace for an edit that cannot change anything, so drop it here and let the caller's
            // `false` clear the preview. This is also what keeps the gesture from flipping canvasEditBusy
            // and queueing a pointless save.
            if (result.second.isEmpty()) { preview = null; pending = null; gestureState = null; endTransformBox(); return false }
            preview = result.first; busy = true; error = null
            // The pending edit is only settled here, so this is where the box a drag was holding gives
            // way to one computed from what the model actually became.
            viewModel.saveAuthoringEdits(expected, JsonArray(result.second)) { failure ->
                busy = false; preview = null; pending = null; gestureState = null; error = failure
                endTransformBox()
                if (failure == null) onSuccess?.invoke()
                if (failure == null) commands.lastOrNull { it["op"]?.jsonPrimitive?.content in setOf("canvas_create_warp", "canvas_create_rotation") }?.let {
                    finishCreateSession(it.getValue("id").jsonPrimitive.content)
                }
            }
        } catch (e: Exception) { error = e.message; preview = null; pending = null; gestureState = null; endTransformBox() }
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
    fun topology(action: String, picked: Set<Int>? = null, edges: Set<MeshElement.Edge> = emptySet()) {
        val t = target() ?: return
        if (t.kind != "mesh" || !editable) return
        gestureState = null
        // Vertex-wise actions run on every edited mesh with a selection, as one history step. Merge and
        // connect join vertices of one mesh, and brushed edges belong to the primary, so those stay there.
        if (picked == null && editsMeshes() && edges.isEmpty() &&
            action in setOf("delete", "duplicate", "subdivide", "split")
        ) {
            val meshes = editMeshTargets().filter { selection[it.id].orEmpty().isNotEmpty() }
            if (meshes.size > 1 || meshes.singleOrNull()?.id?.let { it != t.id } == true) {
                commitBatch(meshes.map { mesh ->
                    buildJsonObject {
                        put("op", "canvas_topology"); put("id", mesh.id); put("action", action)
                        put("vertices", JsonArray(selection.getValue(mesh.id).sorted().map(::JsonPrimitive)))
                    }
                }) {
                    selection = emptyMap()
                    selectedEdges = emptySet(); selectedFaces = emptySet(); topologyFills = null
                }
                return
            }
        }
        val selected = picked ?: vertices
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
        val input = knifeInput ?: return
        if (!editable || t.kind != "mesh" || t.id != knifeDrawableId || input.targetId != t.id || input.inputs.size < 2) return
        val anchors = input.inputs
        // The anchors were resolved against the mesh the first click saw, so the cut is built on that one too.
        val mesh = input.model.rig.puppet.drawables.firstOrNull { it.id.raw == t.id }?.mesh
        val outcome = mesh?.let { runCatching { CanvasTopology.build(it, "knife", emptySet(), anchors) }.getOrNull() }
        if (outcome == null) {
            error = tr("editor.knifeCannotConnect")
            return
        }
        commitInput(input, listOf(buildJsonObject {
            put("op", "canvas_topology"); put("id", t.id); put("action", "knife")
            put("vertices", JsonArray(emptyList()))
            put("anchors", CanvasTopology.encodeAnchors(anchors))
        })) {
            if (knifeInput === input) knifeInput = null
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
        val input = pathInput ?: return
        val frame = input.frame
        try {
            val previous = input.model.rig.puppet.deformPaths.firstOrNull { it.id == draftPathId }
            val points = input.inputs.mapIndexed { i, p -> DeformPathTools.bind(frame.geometry.points, frame.indices, p.first, p.second, previous?.points?.getOrNull(i)?.corner ?: false) }
            val path = previous?.copy(points = points, closed = if (previous.closed) previous.closed else pathClosed)
                ?: DeformPath(
                    StableIds.fresh(StableIds.stem("path_", frame.id, input.inputs)) { id -> input.model.rig.puppet.deformPaths.any { it.id == id } },
                    DrawableId(frame.id),
                    points,
                    pathWidth,
                    hardness = pathHardness,
                    closed = pathClosed && points.size >= 3,
                    editLevel = pathLevel,
                )
            commitInput(input, listOf(DeformPathJournal.encode(path))) {
                if (pathInput === input) pathInput = null
                activePath = path.id; drawingPath = false; draft = emptyList()
                placement = null
                // After binding, enter EDIT so dragging control points rebinds without deforming.
                createSessionReturnMode = null
                hierarchyMode = EditHierarchyMode.EDIT
                tool = CanvasTool.SELECT
                pathClosed = false
                clearHover()
            }
        } catch (e: Exception) { error = e.message }
    }

    fun changePath(update: (DeformPath) -> DeformPath) {
        try { selectedPath()?.let { gestureState = null; commit(DeformPathJournal.encode(update(it))) } }
        catch (failure: IllegalArgumentException) { error = failure.message }
    }

    fun extendPath() {
        val t = target() ?: return; val path = selectedPath() ?: return
        if (path.closed) return
        draft = DeformPathTools.positions(path, t.geometry.points); draftPathId = path.id; drawingPath = true; tool = CanvasTool.CREATE_DEFORM_PATH
        pathInput = startInput(t.id, t, draft)
    }

    fun preciseTransform(vp: CanvasViewport? = null, first: Float, second: Float = 0f, scaleMode: Boolean = false, rotateMode: Boolean = false) {
        if (!editable) return
        val viewport = vp ?: this.viewport ?: return
        val targets = transformTargets(model)
        val indexSets = targets.map { gestureIndices(it) }
        val chosen = targets.flatMapIndexed { k, item -> screen(item.geometry.points, item, viewport).filterIndexed { i, _ -> i in indexSets[k] } }
        if (chosen.isEmpty()) return
        // The same anchor the transform box uses, so the panel and a canvas drag turn and scale about one point.
        // A rotation deformer is the exception: it turns about its origin, which is its first axis point.
        val center = when {
            targets.size == 1 && targets[0].kind == "rotation" -> screen(targets[0].geometry.points, targets[0], viewport)[0]
            else -> currentAnchor()?.let { uv -> selectionFrame(viewport)?.withAnchor(uv)?.anchor } ?: selectionPivot(chosen)
        }
        val worlds = targets.map { it.mapping.localToWorld(it.geometry.points) }
        val movedSets = targets.mapIndexed { itemIndex, item ->
            val indices = indexSets[itemIndex]
            val world = worlds[itemIndex]
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
            indices.toHashSet()
        }
        if (editsMeshGeometry()) keepWeldsTogether(targets, worlds, movedSets)
        val commands = targets.mapIndexed { itemIndex, item ->
            geometryCommand(item, item.mapping.worldToLocal(worlds[itemIndex], item.geometry.points, movedSets[itemIndex]))
        }
        gestureState = null; commitBatch(commands)
    }

    fun deletePathPoint() {
        val p = selectedPath() ?: return
        if (pathPoint >= 0 && p.points.size > 2) changePath { it.copy(points = it.points.filterIndexed { i, _ -> i != pathPoint }) }
        else { gestureState = null; commit(buildJsonObject { put("op", "path_delete"); put("id", p.id) }); activePath = null }
        pathPoint = -1
    }

    fun selectAll(invert: Boolean = false) {
        if (objectMode) {
            objects = state.effectiveVisibleLayerIds.filter { (!invert || it !in objects) && target(model, it, null) != null }.toSet()
            selectLayer(objects.lastOrNull())
        } else if (editsMeshes()) {
            val all = editMeshTargets().associate { t ->
                t.id to (0 until t.count).filterTo(LinkedHashSet()) { !invert || it !in selection[t.id].orEmpty() }
            }
            selection = weldGroups().expand(all).filterValues { it.isNotEmpty() }
        } else target()?.let { t ->
            if (pointElementModes() && t.kind == "mesh" && elementMode == 1) {
                selectedEdges = MeshTopology.uniqueEdges(t.indices).filterTo(LinkedHashSet()) { !invert || it !in selectedEdges }
                vertices = selectedEdges.flatMapTo(LinkedHashSet()) { listOf(it.endpointLow, it.endpointHigh) }
            } else if (pointElementModes() && t.kind == "mesh" && elementMode == 2) {
                selectedFaces = (0 until t.indices.size / 3).filterTo(LinkedHashSet()) { !invert || it !in selectedFaces }
                vertices = selectedFaces.flatMapTo(LinkedHashSet()) { MeshTopology.verticesOfTriangle(t.indices, it) }
            } else {
                vertices = (0 until t.count).filter { !invert || it !in vertices }.toSet()
            }
        }
    }

    fun selectLinked() {
        if (editsMeshes()) {
            // Linked reaches across glue: a glued point joins the pieces of both meshes it sits on.
            val targets = editMeshTargets().associateBy { it.id }
            val adjacency = targets.mapValues { (_, t) -> MeshTopology.buildVertexAdjacency(t.count, t.indices) }
            var current = weldGroups().expand(selection)
            while (true) {
                val grown = current.mapValues { (id, set) ->
                    val links = adjacency[id] ?: return@mapValues set
                    set.flatMapTo(LinkedHashSet()) { MeshTopology.connectedVertices(links, it) }
                }
                val next = weldGroups().expand(grown)
                if (next == current) break
                current = next
            }
            selection = current.filterValues { it.isNotEmpty() }
            return
        }
        val t = target() ?: return; if (t.indices.isEmpty()) return
        val adjacency = MeshTopology.buildVertexAdjacency(t.count, t.indices)
        vertices = vertices.flatMap { MeshTopology.connectedVertices(adjacency, it) }.toSet()
    }

    /** The swing handle under a drag; the swing session itself lives on the view model. */
    var swingHandle by mutableStateOf<io.github.psd2live.core.SwingGizmo.Handle?>(null)
        private set

    /** The swing handle under the pointer; state, so the handle lights up and the cursor follows it. */
    var swingHover by mutableStateOf<io.github.psd2live.core.SwingGizmo.Handle?>(null)
        private set

    /** Whether a swing handle is being dragged; its preview updates must not read as the document changing. */
    val swingDragging: Boolean get() = swingHandle != null

    /** A canvas-pixel point (Y down, as the deformer cascade outputs) on screen. */
    fun swingScreen(point: Pair<Float, Float>, viewport: CanvasViewport) =
        Offset(viewport.x(point.first).toFloat(), (viewport.offsetY + point.second * viewport.scale).toFloat())

    /** The swing handles on screen, in hit priority: the tip, middle and corners sit above the pivots. */
    fun swingHandles(viewport: CanvasViewport): List<Pair<io.github.psd2live.core.SwingGizmo.Handle, Offset>> {
        val gizmo = viewModel.swingSession?.gizmo ?: return emptyList()
        return gizmo.handles().entries.sortedBy { if (it.key.name.startsWith("PIVOT")) 1 else 0 }
            .map { it.key to swingScreen(it.value, viewport) }
    }

    private fun hitSwingHandle(pos: Offset, viewport: CanvasViewport) =
        swingHandles(viewport).firstOrNull { (it.second - pos).getDistance() <= 10f }?.first

    private fun dragSwing(handle: io.github.psd2live.core.SwingGizmo.Handle, pos: Offset, viewport: CanvasViewport) {
        val gizmo = viewModel.swingSession?.gizmo ?: return
        viewModel.updateSwingSettings(gizmo.drag(handle, viewport.canvasX(pos.x) to viewport.canvasY(pos.y)))
    }

    /** The selection as swing targets: its meshes and Warps. */
    fun swingTargets(): List<String> {
        val t = target() ?: return emptyList()
        return objects.mapNotNull { target(model, it, null) }.ifEmpty { listOf(t) }
            .filter { it.kind == "mesh" || it.kind == "warp" }.map { it.id }.distinct()
    }

    /**
     * After a successful Warp/Rotation create: select the new deformer and either stay armed
     * (sequential) or restore the mode the create session started from.
     */
    private fun finishCreateSession(newDeformerId: String) {
        selection = emptyMap()
        selectDeformer(newDeformerId)
        if (sequentialCreate) {
            // Stay on the create tool; keep return mode for a later exit.
            return
        }
        val returnMode = createSessionReturnMode
        createSessionReturnMode = null
        if (returnMode != null && returnMode != hierarchyMode && returnMode != EditHierarchyMode.PAINT) {
            deferredMode = null
            hierarchyMode = returnMode
            if (returnMode == EditHierarchyMode.DEFORM) {
                if (editLevel == 2) ensureBezierState()
            }
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

    /** Drawable ids of the selected art meshes, in the order they were selected. */
    fun selectedMeshIds(): List<String> {
        val ordered = LinkedHashSet(objects)
        state.selectedLayerId?.let(ordered::add)
        return ordered.mapNotNull { meshDrawableId(it) }.distinct()
    }

    private fun meshDrawableId(layerOrDrawableId: String): String? {
        val puppet = preview ?: state.previewModel?.rig?.puppet ?: return null
        val rig = state.previewModel?.rig ?: return null
        val byLayer = puppet.drawables.firstOrNull { rig.layerIdByDrawableId[it.id.raw] == layerOrDrawableId && it.mesh != null }
        if (byLayer != null) return byLayer.id.raw
        return puppet.drawables.firstOrNull { it.id.raw == layerOrDrawableId && it.mesh != null }?.id?.raw
    }

    // ---- The Edit session: every selected mesh is edited at once ----

    /**
     * The meshes Edit mode is editing: every selected layer that resolves to a mesh, primary included,
     * in selection order. Empty outside Edit, and when the primary target is a deformer.
     */
    fun editMeshTargets(source: PuppetModel? = preview ?: state.previewModel?.rig?.puppet): List<CanvasTarget> {
        if (hierarchyMode != EditHierarchyMode.EDIT && hierarchyMode != EditHierarchyMode.SIMULATE) return emptyList()
        val primary = target(source) ?: return emptyList()
        if (primary.kind != "mesh") return emptyList()
        val layers = LinkedHashSet(objects).apply { state.selectedLayerId?.let(::add) }
        val found = layers.mapNotNull { layer -> target(source, layer, null)?.takeIf { it.kind == "mesh" } }.distinctBy { it.id }
        return found.ifEmpty { listOf(primary) }
    }

    /**
     * Whether the point tools act on the whole edit set. Edge and face modes stay on the primary mesh:
     * an edge or a face never spans two meshes.
     */
    fun editsMeshes(): Boolean =
        hierarchyMode == EditHierarchyMode.EDIT && elementMode == 0 && target()?.kind == "mesh"

    /**
     * Whether a gesture edits rest meshes, whatever the element mode. Every such gesture moves a glued
     * point as the one point it is: edge and face picks stay on the primary mesh, but what they move
     * still carries the glued partners along.
     */
    private fun editsMeshGeometry(): Boolean =
        hierarchyMode == EditHierarchyMode.EDIT && target()?.kind == "mesh"

    /** A mesh target for [drawableId], edited set or not; null when it is hidden or has no mesh. */
    private fun meshTarget(source: PuppetModel?, drawableId: String): CanvasTarget? {
        val layer = layerIdForDrawable(drawableId) ?: return null
        return target(source, layer, null)?.takeIf { it.kind == "mesh" && it.id == drawableId }
    }

    private var weldSource: List<org.umamo.runtime.model.Glue>? = null
    private var weldCached = WeldGroups.EMPTY

    /** The model's glued points, one group per point. */
    fun weldGroups(): WeldGroups {
        val source = preview ?: state.previewModel?.rig?.puppet ?: return WeldGroups.EMPTY
        if (weldSource !== source.glues) {
            val counts = source.drawables.associate { it.id.raw to (it.mesh?.vertexCount ?: 0) }
            weldCached = WeldGroups.of(source.glues) { it.index in 0 until (counts[it.mesh] ?: 0) }
            weldSource = source.glues
        }
        return weldCached
    }

    /** [found] merged into the selection the way a click or a marquee merges it, glued points whole. */
    private fun mergedSelection(found: Map<String, Set<Int>>, add: Boolean, subtract: Boolean): Map<String, Set<Int>> {
        val welds = weldGroups()
        val grown = welds.expand(found)
        val next = when {
            subtract -> selection.mapValues { (id, set) -> set - grown[id].orEmpty() }
            add -> (selection.keys + grown.keys).associateWith { selection[it].orEmpty() + grown[it].orEmpty() }
            else -> grown
        }
        return welds.expand(next).filterValues { it.isNotEmpty() }
    }

    /** The screen points of every edited mesh, keyed by drawable id, for picking and marquees. */
    private fun editScreens(viewport: CanvasViewport): List<Pair<CanvasTarget, List<Offset>>> =
        editMeshTargets().map { it to screen(it.geometry.points, it, viewport) }

    /** The edited vertex nearest [pos] within [reach] screen pixels. The primary mesh wins a tie. */
    private fun pickEditVertex(pos: Offset, viewport: CanvasViewport, reach: Float = 10f): MeshVertex? {
        val primary = target()?.id
        val nearest = editScreens(viewport).mapNotNull { (t, points) ->
            points.indices.minByOrNull { (points[it] - pos).getDistance() }
                ?.let { i -> Triple(t.id, i, (points[i] - pos).getDistance()) }
                ?.takeIf { it.third <= reach }
        }
        val closest = nearest.minByOrNull { it.third } ?: return null
        // Overlapping meshes put points on top of each other; the one on the primary mesh wins a near tie.
        val chosen = nearest.firstOrNull { it.first == primary && it.third <= closest.third + 0.5f } ?: closest
        return MeshVertex(chosen.first, chosen.second)
    }

    /** Every edited vertex whose screen point [inside] accepts, per mesh. */
    private fun editVerticesWhere(viewport: CanvasViewport, inside: (Offset) -> Boolean): Map<String, Set<Int>> =
        editScreens(viewport).associate { (t, points) -> t.id to points.indices.filterTo(LinkedHashSet()) { inside(points[it]) } }
            .filterValues { it.isNotEmpty() }

    /**
     * Moves the primary to [drawableId]'s layer within the same edit set. The vertex selection stays: the
     * set did not change, only which mesh the single-mesh tools (knife, paths, edges) act on.
     */
    private fun makePrimary(drawableId: String) {
        val layer = layerIdForDrawable(drawableId) ?: return
        if (state.selectedLayerId == layer || layer !in objects) return
        viewModel.updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) { it.copy(selectedLayerId = layer) }
    }

    /** The glued point [vertex] belongs to is hovered, picked and moved as one. */
    var hoveredMeshVertex by mutableStateOf<MeshVertex?>(null)

    private var meshStroke: CanvasDeformStroke.Session? = null

    /** Live deform-brush weights of each edited mesh, for the overlay's wash. */
    var activeMeshBrushWeights by mutableStateOf<Map<String, FloatArray>>(emptyMap())

    /** Transform selections also include the unselected members of their welded points. */
    private fun strokeTargets(source: PuppetModel?): List<CanvasTarget> {
        val edited = editMeshTargets(source)
        val ids = edited.mapTo(LinkedHashSet()) { it.id }
        val partners = LinkedHashSet<String>()
        for (group in weldGroups().groups) {
            if (group.none { it.mesh in ids }) continue
            group.forEach { if (it.mesh !in ids) partners += it.mesh }
        }
        return edited + partners.mapNotNull { meshTarget(source, it) }
    }

    private fun keepWeldsTogether(targets: List<CanvasTarget>, worlds: List<FloatArray>, moved: List<MutableSet<Int>>) =
        CanvasDeformStroke.keepWeldsTogether(targets.map { it.id }, targets.map { it.count }, worlds, moved, model.glues)

    /** Freeze canvas pose, destination coordinates and vertex selection independently of the viewport. */
    private fun deformStrokeRequest(source: PuppetModel, targets: List<CanvasTarget>, editedSet: Boolean): CanvasDeformStroke.Request {
        val capturedPose = pose.toMap()
        val selected = editedSet && selection.values.any { it.isNotEmpty() }
        return CanvasDeformStroke.Request(
            CanvasDeformStroke.Action.valueOf(tool.name),
            if (hierarchyMode == EditHierarchyMode.EDIT) CanvasDeformStroke.Mode.EDIT else CanvasDeformStroke.Mode.DEFORM,
            targets.map { target ->
                val geometry = RigGeometryTools.geometry(source, target.kind, target.id, capturedPose)
                val key = canvasDeformationCoordinate(source, geometry.axes, capturedPose, deformationParameters)
                val allowed = if (editedSet) (if (selected) selection[target.id].orEmpty().toSet() else null)
                    else vertices.takeIf { it.isNotEmpty() }?.toSet()
                CanvasDeformStroke.Target(target.kind, target.id, key, allowed)
            }, capturedPose, weightTip(), strength, connectedOnly, shrinkAtPress)
    }

    fun brushPreviewWeights(viewport: CanvasViewport): Map<String, FloatArray> {
        val center = cursor ?: return emptyMap()
        val source = state.previewModel?.rig?.puppet ?: return emptyMap()
        val edited = editsMeshes()
        val targets = if (edited) editMeshTargets(source) else listOfNotNull(target(source)?.takeIf { it.kind != "rotation" })
        if (targets.isEmpty() || tool !in DEFORM_BRUSH_TOOLS) return emptyMap()
        return try {
            CanvasDeformStroke.begin(source, deformStrokeRequest(source, targets, edited),
                CanvasDeformStroke.Sample(weightCanvasPoint(center, viewport))).weights.mapKeys { it.key.substringAfter(':') }
        } catch (_: IllegalArgumentException) { emptyMap() }
    }

    /** A brush weight [w] as the share of a full dab it is, 0..1, for the reach rings. */
    fun reachShown(w: Float): Float = if (w <= 0.0001f) 0f else if (strength > 0.001f) (w / strength).coerceIn(0f, 1f) else w.coerceIn(0f, 1f)

    private var hoverReachKey: List<Any?>? = null
    private var hoverReach: Map<String, FloatArray> = emptyMap()

    /**
     * What a deform brush pressed at the pointer would move, for the hover preview, by target id: nothing while a
     * stroke is in hand or the tip is being retuned, which show their own. Kept while nothing it reads changes, as
     * the overlay asks on every frame.
     */
    fun brushHoverReach(viewport: CanvasViewport): Map<String, FloatArray> {
        if (tool !in DEFORM_BRUSH_TOOLS || dragging || adjustingBrush || meshStroke != null || cursor == null) return emptyMap()
        val key = listOf(cursor, viewport, state.previewModel?.rig?.puppet, pose, radius, hardness, brushShape, brushAngle,
            brushAspect, brushFalloff, strength, connectedOnly, tool, hierarchyMode, selection, vertices, target()?.id)
        if (key != hoverReachKey) {
            hoverReach = runCatching { brushPreviewWeights(viewport) }.getOrDefault(emptyMap()).filterValues { w -> w.any { it > 0.0001f } }
            hoverReachKey = key
        }
        return hoverReach
    }

    private fun beginDeformStroke(pos: Offset, viewport: CanvasViewport, source: PuppetModel, targets: List<CanvasTarget>, editedSet: Boolean) {
        val session = CanvasDeformStroke.begin(source, deformStrokeRequest(source, targets, editedSet),
            CanvasDeformStroke.Sample(weightCanvasPoint(pos, viewport)))
        meshStroke = session
        activeMeshBrushWeights = if (editedSet && tool == CanvasTool.BRUSH) session.weights.mapKeys { it.key.substringAfter(':') } else emptyMap()
        activeBrushWeights = if (!editedSet && tool == CanvasTool.BRUSH) session.weights[targets.single().let { "${it.kind}:${it.id}" }] else null
        activeBrushCenter = pos
        original = source
        dragging = true
    }

    private fun beginMeshStroke(pos: Offset, viewport: CanvasViewport, source: PuppetModel) =
        beginDeformStroke(pos, viewport, source, editMeshTargets(source), true)

    private fun moveMeshStroke(pos: Offset, viewport: CanvasViewport, shift: Boolean, ctrl: Boolean) {
        val session = meshStroke ?: return
        preview = session.step(CanvasDeformStroke.Sample(weightCanvasPoint(pos, viewport),
            smooth = session.request.action == CanvasDeformStroke.Action.BRUSH && shift, preserveChildren = ctrl))
        previous = pos
    }

    private fun finishDeformStroke() {
        val session = meshStroke ?: return
        val expected = gestureState
        val changed = moved && session.commands.isNotEmpty() && session.capturedSamples().size > 1
        meshStroke = null; dragging = false; ctrlAtPress = false
        activeMeshBrushWeights = emptyMap(); activeBrushWeights = null; activeBrushCenter = null
        pending = null; pendingObjects = emptyList(); objectTargets = emptyList(); targetAtPress = null; original = null
        if (!changed || expected == null) {
            session.cancel(); preview = null; gestureState = null; endTransformBox()
            return
        }
        val operation = io.github.psd2live.application.WorkspaceCanvasDeformEdits.operation(session)
        busy = true; error = null
        viewModel.saveDocumentEdits(expected, "Deformed canvas geometry", listOf(operation)) { failure ->
            busy = false; preview = null; gestureState = null; error = failure
            endTransformBox()
        }
    }

    private fun endMeshStroke() {
        meshStroke?.cancel(); meshStroke = null
        activeMeshBrushWeights = emptyMap()
    }

    fun glueMeshCount(): Int = selectedMeshIds().size

    /**
     * The two meshes glue will bind. The primary (last selected) mesh is B unless [glueSwapped].
     * Null unless the selection is exactly two art meshes.
     */
    fun glueMeshPair(): Pair<String, String>? {
        val meshes = selectedMeshIds()
        if (meshes.size != 2) return null
        val (first, second) = meshes
        return if (glueSwapped) second to first else first to second
    }

    fun glueAlreadyBound(): Boolean {
        val (meshA, meshB) = glueMeshPair() ?: return false
        return model.glues.any { glue ->
            (glue.meshA.raw == meshA && glue.meshB.raw == meshB) ||
                (glue.meshA.raw == meshB && glue.meshB.raw == meshA)
        }
    }

    fun meshLabel(drawableId: String): String =
        model.drawables.firstOrNull { it.id.raw == drawableId }?.name ?: drawableId

    fun swapGlueEnds() {
        if (glueMeshPair() == null) return
        glueSwapped = !glueSwapped
    }

    /**
     * Enter / Create glue: welds the two outlines wherever they overlap or come within the matching
     * distance. Existing pairs are kept, so this also extends an existing glue.
     */
    fun applyGlue() {
        val pair = glueMeshPair()
        if (pair == null) {
            error = tr("editor.glueNeedTwo", glueMeshCount())
            return
        }
        if (gluePreviewPoints().isEmpty()) {
            error = tr("editor.glueNoPairs")
            return
        }
        error = null
        // Welds are made where the meshes rest; the preview the user confirmed is shown there first.
        if (viewModel.snapPoseToDefaults { applyGlue() }) return
        commitGlueEdit("brush", glueOutline(pair.first), glueOutline(pair.second))
    }

    /** Ctrl+G in edit mode: glue the vertices selected on the primary mesh to the other mesh. */
    fun glueSelectedVertices() {
        if (hierarchyMode != EditHierarchyMode.EDIT) return
        val pair = glueMeshPair()
        if (pair == null) {
            error = tr("editor.glueNeedTwo", glueMeshCount())
            return
        }
        // Both meshes are edited together, so the selection on either side takes part.
        val hitsA = selection[pair.first].orEmpty()
        val hitsB = selection[pair.second].orEmpty()
        if (hitsA.isEmpty() && hitsB.isEmpty()) {
            error = tr("editor.glueNoVertexSelection")
            return
        }
        if (viewModel.snapPoseToDefaults { glueSelectedVertices() }) return
        commitGlueEdit("brush", hitsA, hitsB)
    }

    /** Drops every pair and welds both outlines again from scratch. */
    fun remergeGlue() {
        if (glueMeshPair() == null) {
            error = tr("editor.glueNeedTwo", glueMeshCount())
            return
        }
        error = null
        if (viewModel.snapPoseToDefaults { remergeGlue() }) return
        commitGlueEdit("remerge", emptySet(), emptySet())
    }

    private fun commitGlueEdit(action: String, hitsA: Set<Int>, hitsB: Set<Int>) {
        val (a, b) = glueMeshPair() ?: return
        val existingId = model.glues.firstOrNull { glue ->
            (glue.meshA.raw == a && glue.meshB.raw == b) || (glue.meshA.raw == b && glue.meshB.raw == a)
        }?.id
        val delta = (if (glueErasing) -1f else 1f) * strength.coerceIn(0.05f, 1f)
        val mode = when (glueWeightMode) {
            GlueWeightMode.A -> "a"
            GlueWeightMode.B -> "b"
            GlueWeightMode.BALANCE -> "balance"
        }
        val cmd = buildJsonObject {
            // A new Glue is named by the edit from its mesh pair (WorkspaceCanvasWeightEdits).
            existingId?.let { put("id", it) }
            put("action", action)
            put("mesh_a", a)
            put("mesh_b", b)
            put("distance", glueDistance)
            put("weight_mode", mode)
            put("delta", delta)
            put("hits_a", JsonArray(hitsA.sorted().map(::JsonPrimitive)))
            put("hits_b", JsonArray(hitsB.sorted().map(::JsonPrimitive)))
        }
        commitWeightOperations(listOf(io.github.psd2live.application.WorkspaceDocumentOperation("canvas_glue_edit", cmd)))
    }

    /** Preview neutral requests locally; their final materialization uses the captured candidate. */
    private fun commitWeightOperations(operations: List<io.github.psd2live.application.WorkspaceDocumentOperation>) {
        if (!editable || operations.isEmpty()) return
        val expected = gestureState ?: viewModel.currentWorkspaceState() ?: return
        try {
            val base = state.previewModel?.rig?.puppet ?: return
            var evaluated = base
            for (operation in operations) {
                val commands = io.github.psd2live.application.WorkspaceCanvasWeightEdits.commands(evaluated, operation)
                evaluated = RigAuthoringJournal.compile(evaluated, commands).first
            }
            preview = evaluated; busy = true; error = null
            viewModel.saveDocumentEdits(expected, "Edited canvas weights", operations) { failure ->
                busy = false; preview = null; pending = null; gestureState = null; error = failure
                endTransformBox()
            }
        } catch (failure: Exception) {
            error = failure.message; preview = null; pending = null; gestureState = null; endTransformBox()
        }
    }

    private fun glueOutline(drawableId: String): Set<Int> {
        val mesh = model.drawables.firstOrNull { it.id.raw == drawableId }?.mesh ?: return emptySet()
        return io.github.psd2live.core.outlineVertices(mesh.indices, mesh.vertexCount)
    }

    /** Per-vertex weld weight of one mesh, for the texture wash. Null when the mesh is not glued. */
    fun glueWeights(drawableId: String): FloatArray? {
        val id = org.umamo.runtime.model.DrawableId(drawableId)
        if (model.glues.none { it.meshA == id || it.meshB == id }) return null
        val mesh = model.drawables.firstOrNull { it.id == id }?.mesh ?: return null
        return io.github.psd2live.core.glueVertexWeights(model, id, mesh.vertexCount)
    }

    fun glueRoleColor(drawableId: String): androidx.compose.ui.graphics.Color? = when (drawableId) {
        glueMeshPair()?.first -> GlueColorA
        glueMeshPair()?.second -> GlueColorB
        else -> null
    }

    /**
     * Each edited mesh's own colour while Edit holds more than one, so overlapping meshes never read as
     * one; glued points take [GlueColorWeld], a third colour. Empty for a single mesh, which keeps the
     * accent. The first two follow the glue sides A and B, swap included.
     */
    fun editMeshColors(): Map<String, androidx.compose.ui.graphics.Color> {
        val meshes = editMeshTargets()
        if (meshes.size < 2) return emptyMap()
        val pair = glueMeshPair()
        return meshes.withIndex().associate { (index, t) ->
            t.id to (glueRoleColor(t.id).takeIf { pair != null } ?: EditMeshPalette[index % EditMeshPalette.size])
        }
    }

    internal fun layerIdForDrawable(drawableId: String): String? =
        state.previewModel?.rig?.layerIdByDrawableId[drawableId]

    /** [drawableId]'s group of the brushed kind: the first one, as the simulation reads it. */
    private fun paintedGroup(drawableId: String): VertexGroup? =
        model.vertexGroups.firstOrNull { it.drawableId.raw == drawableId && it.kind == weightGroupKind }

    /** The name [drawableId]'s group of the brushed kind has, or gets: the kind's own, numbered past other kinds' groups. */
    private fun paintedGroupName(drawableId: String): String = paintedGroup(drawableId)?.name ?: run {
        val taken = model.vertexGroups.filter { it.drawableId.raw == drawableId }.mapTo(HashSet()) { it.name }
        val stem = weightGroupKind.jsonName
        if (stem !in taken) stem else generateSequence(2) { it + 1 }.map { "$stem$it" }.first { it !in taken }
    }

    /** [drawableId]'s current weights in the brushed group, or null when it has no such group. */
    fun vertexGroupWeights(drawableId: String): FloatArray? {
        val mesh = model.drawables.firstOrNull { it.id.raw == drawableId }?.mesh ?: return null
        return paintedGroup(drawableId)?.weights?.takeIf { it.size == mesh.vertexCount }
    }

    /** What [drawableId]'s group becomes when the current stroke or gradient is released; its current weights between them. */
    fun paintedWeights(drawableId: String): FloatArray? {
        val reach = weightStroke[drawableId] ?: return vertexGroupWeights(drawableId)
        val base = vertexGroupWeights(drawableId) ?: FloatArray(reach.size)
        val neighbors = if (weightStrokeMode == WeightPaintMode.SMOOTH) {
            editMeshTargets().firstOrNull { it.id == drawableId }?.let(::neighbors)
        } else null
        return WeightPaint.apply(base, reach, weightStrokeMode, strength, neighbors)
    }

    /** The weight brush's tip, in screen pixels. */
    private fun weightTip() = io.github.psd2live.core.CanvasBrushTip(radius, hardness,
        io.github.psd2live.core.CanvasBrushShape.valueOf(brushShape.name), brushAngle, brushAspect,
        io.github.psd2live.core.CanvasBrushFalloff.valueOf(brushFalloff.name))

    private fun weightCanvasPoint(pos: Offset, viewport: CanvasViewport) =
        io.github.psd2live.core.CanvasBrushPoint(viewport.canvasX(pos.x), viewport.canvasY(pos.y))

    private val weightStrokePoints = mutableListOf<io.github.psd2live.core.CanvasBrushPoint>()
    private var weightStrokePose: Map<String, Float>? = null
    private fun weightPose(source: PuppetModel): Map<String, Float> = weightStrokePose ?: pose.mapNotNull { (id, value) ->
        source.parameters.firstOrNull { it.id.raw == id }?.let {
            id to (if (value.isFinite()) value else it.default).coerceIn(it.min, it.max)
        }
    }.toMap()

    /** How strongly a dab from [from] to [to] reaches each vertex of each edited mesh. */
    private fun weightReach(from: Offset, to: Offset, viewport: CanvasViewport): Map<String, FloatArray> {
        val targets = editMeshTargets()
        if (targets.isEmpty()) return emptyMap()
        val source = preview ?: state.previewModel?.rig?.puppet ?: return emptyMap()
        val surfaces = io.github.psd2live.core.CanvasWeightAuthoring.surfaces(source,
            targets.map { DrawableId(it.id) }, weightPose(source), connectedOnly)
        val reached = io.github.psd2live.core.canvasBrushWeights(surfaces, weightCanvasPoint(from, viewport),
            weightCanvasPoint(to, viewport), weightTip(), connectedOnly)
        return targets.indices.associate { targets[it].id to reached[it] }
    }

    /**
     * What a press at the pointer would reach right now, for the hover preview. Nothing while a stroke or a
     * gradient is in hand: those show their own result.
     */
    fun weightBrushPreview(viewport: CanvasViewport): Map<String, FloatArray> {
        if (tool != CanvasTool.WEIGHT_PAINT || weightStroking || weightGradient != null) return emptyMap()
        val center = cursor ?: return emptyMap()
        return weightReach(center, center, viewport).filterValues { w -> w.any { it > 0.0001f } }
    }

    /** The vertex nearest the pointer on the edited meshes and its weight, for the readout beside the cursor. */
    fun weightUnderCursor(viewport: CanvasViewport): Pair<Offset, Float>? {
        val center = cursor ?: return null
        var best: Pair<Offset, Float>? = null
        var bestDistance = WEIGHT_READOUT_REACH_PX
        for (t in editMeshTargets()) {
            val weights = paintedWeights(t.id)
            val points = screen(t.geometry.points, t, viewport)
            for (i in points.indices) {
                val d = (points[i] - center).getDistance()
                if (d < bestDistance) {
                    bestDistance = d
                    best = points[i] to (weights?.getOrNull(i) ?: 0f)
                }
            }
        }
        return best
    }

    private fun beginWeightTool(pos: Offset, viewport: CanvasViewport, alt: Boolean): Boolean {
        if (editMeshTargets().isEmpty()) {
            error = tr("editor.weightNeedMesh")
            return true
        }
        weightStrokeMode = WeightPaint.effective(weightPaintMode, alt)
        weightStroke = emptyMap()
        weightStrokePoints.clear()
        weightStrokePose = null
        weightStrokePose = weightPose(model).toMap()
        weightStroking = true
        dragging = true
        if (tool == CanvasTool.WEIGHT_GRADIENT) {
            weightGradient = pos to pos
        } else {
            accumulateWeightStroke(pos, pos, viewport)
        }
        return true
    }

    private fun dragWeightTool(from: Offset, to: Offset, viewport: CanvasViewport) {
        val gradient = weightGradient
        if (gradient == null) {
            accumulateWeightStroke(from, to, viewport)
            return
        }
        weightGradient = gradient.first to to
        val targets = editMeshTargets()
        val source = preview ?: state.previewModel?.rig?.puppet ?: return
        val surfaces = io.github.psd2live.core.CanvasWeightAuthoring.surfaces(source,
            targets.map { DrawableId(it.id) }, weightPose(source), connected = false)
        weightStroke = targets.indices.associate { at ->
            targets[at].id to io.github.psd2live.core.CanvasWeightAuthoring.gradient(
                surfaces[at].points,
                weightCanvasPoint(gradient.first, viewport), weightCanvasPoint(to, viewport))
        }
    }

    private fun accumulateWeightStroke(from: Offset, to: Offset, viewport: CanvasViewport) {
        if (weightStrokePoints.isEmpty()) weightStrokePoints += weightCanvasPoint(from, viewport)
        weightStrokePoints += weightCanvasPoint(to, viewport)
        val reached = weightReach(from, to, viewport)
        val next = weightStroke.toMutableMap()
        for ((id, w) in reached) {
            if (w.none { it > 0f }) continue
            val merged = next[id]?.copyOf() ?: FloatArray(w.size)
            for (i in 0 until minOf(merged.size, w.size)) merged[i] = maxOf(merged[i], w[i])
            next[id] = merged
        }
        weightStroke = next
    }

    private fun commitWeightStroke() {
        val targets = editMeshTargets().map { it.id }
        val vp = viewport ?: return
        val gradient = weightGradient
        val points = weightStrokePoints.toList()
        val strokePose = weightPose(model)
        val request = buildJsonObject {
            put("action", if (gradient == null) "brush" else "gradient")
            put("targets", JsonArray(targets.map(::JsonPrimitive))); put("kind", weightGroupKind.jsonName)
            put("mode", weightStrokeMode.name.lowercase()); put("strength", strength)
            put("pose", JsonObject(strokePose.mapValues { JsonPrimitive(it.value) }))
            fun point(p: io.github.psd2live.core.CanvasBrushPoint) = JsonArray(listOf(JsonPrimitive(p.x), JsonPrimitive(p.y)))
            if (gradient == null) {
                put("points", JsonArray(points.map(::point))); put("radius", radius); put("hardness", hardness)
                put("shape", brushShape.name.lowercase()); put("angle", brushAngle); put("aspect", brushAspect)
                put("falloff", brushFalloff.name.lowercase()); put("connected_only", connectedOnly)
            } else { put("from", point(weightCanvasPoint(gradient.first, vp))); put("to", point(weightCanvasPoint(gradient.second, vp))) }
        }
        weightStroke = emptyMap()
        weightGradient = null
        weightStrokePoints.clear()
        weightStrokePose = null
        if (targets.isEmpty() || (gradient == null && points.isEmpty())) return
        commitWeightOperations(listOf(io.github.psd2live.application.WorkspaceDocumentOperation("vertex_group_paint", buildJsonObject { put("edit", request) })))
    }

    /** Fills or clears the brushed group on every edited mesh at once. */
    fun fillVertexGroup(value: Float) {
        val commands = editMeshTargets().mapNotNull { t ->
            val mesh = model.drawables.firstOrNull { it.id.raw == t.id }?.mesh ?: return@mapNotNull null
            VertexGroupJournal.encode(VertexGroup(paintedGroupName(t.id), DrawableId(t.id), weightGroupKind, FloatArray(mesh.vertexCount) { value.coerceIn(0f, 1f) }))
        }
        if (commands.isEmpty()) return
        gestureState = null
        commitBatch(commands)
    }

    /** Flips the brushed group (w -> 1 - w) on every edited mesh that has it. */
    fun invertVertexGroup() {
        val targets = editMeshTargets().map { it.id }
        if (targets.isEmpty()) return
        commitWeightOperations(listOf(io.github.psd2live.application.WorkspaceDocumentOperation("vertex_group_paint", buildJsonObject { put("edit", buildJsonObject {
            put("action", "invert"); put("targets", JsonArray(targets.map(::JsonPrimitive))); put("kind", weightGroupKind.jsonName)
        }) })))
    }

    /** Removes the brushed group from every edited mesh that has it. */
    fun deleteVertexGroup() {
        val commands = editMeshTargets().mapNotNull { t -> paintedGroup(t.id)?.let { VertexGroupJournal.delete(t.id, it.name) } }
        if (commands.isEmpty()) return
        gestureState = null
        commitBatch(commands)
    }

    private fun accumulateGlueHits(from: Offset, to: Offset, viewport: CanvasViewport) {
        val pair = glueMeshPair() ?: return
        val radius = (radius * viewport.scale).toFloat()
        fun hits(drawableId: String): Set<Int> {
            val layerId = layerIdForDrawable(drawableId) ?: return emptySet()
            val item = target(model, layerId, null) ?: return emptySet()
            return screen(item.geometry.points, item, viewport).mapIndexedNotNull { index, point ->
                if (distanceToSegment(point, from, to) <= radius) index else null
            }.toSet()
        }
        glueStrokeA = glueStrokeA + hits(pair.first)
        glueStrokeB = glueStrokeB + hits(pair.second)
    }

    private var gluePreviewKey: String? = null
    private var gluePreviewCached: List<Pair<Float, Float>> = emptyList()

    /**
     * World points where Create glue would weld the two outlines, from the same planner the reducer
     * runs. Each direction is planned against the current meshes, so the count is a close preview
     * rather than an exact one.
     */
    fun gluePreviewPoints(): List<Pair<Float, Float>> {
        val pair = glueMeshPair() ?: return emptyList()
        val geometry = evaluatedGeometry() ?: return emptyList()
        val key = "${pair.first}|${pair.second}|$glueDistance|${cachedGeometrySource?.hashCode()}|${cachedGeometryPose.hashCode()}|${model.glues.hashCode()}"
        if (key == gluePreviewKey) return gluePreviewCached
        val idA = org.umamo.runtime.model.DrawableId(pair.first)
        val idB = org.umamo.runtime.model.DrawableId(pair.second)
        val a = geometry.worldPositions[idA] ?: return emptyList()
        val b = geometry.worldPositions[idB] ?: return emptyList()
        val meshA = model.drawables.firstOrNull { it.id == idA }?.mesh ?: return emptyList()
        val meshB = model.drawables.firstOrNull { it.id == idB }?.mesh ?: return emptyList()
        val glue = model.glues.firstOrNull { (it.meshA == idA && it.meshB == idB) || (it.meshA == idB && it.meshB == idA) }
        val usedA = glue?.pairs?.mapTo(HashSet()) { if (glue.meshA == idA) it.indexA else it.indexB }.orEmpty()
        val usedB = glue?.pairs?.mapTo(HashSet()) { if (glue.meshA == idA) it.indexB else it.indexA }.orEmpty()
        val fromA = io.github.psd2live.core.planGlueWelds(
            a, glueOutline(pair.first) - usedA, b, meshB.indices, glueDistance, usedB,
        )
        val fromB = io.github.psd2live.core.planGlueWelds(
            b, glueOutline(pair.second) - usedB - fromA.mapNotNull { it.existing }.toSet(), a, meshA.indices,
            glueDistance, usedA + fromA.map { it.seed },
        )
        val points = (fromA + fromB).map { it.x to it.y }
        gluePreviewKey = key
        gluePreviewCached = points
        return points
    }

    fun gluePreviewMarks(viewport: CanvasViewport): List<Offset> =
        gluePreviewPoints().map { (x, y) -> Offset(viewport.x(x).toFloat(), viewport.yFromWorld(y).toFloat()) }

    /**
     * The layer a click at [pos] picks. Clicking a stack walks it one layer per click — the same rule
     * [RigCanvasSupport.hitLayer] applies in the preview tab — so the layer under the pointer can be
     * reached without naming it in the hierarchy first. Null when nothing pickable is there.
     */
    private fun pickLayer(pos: Offset, viewport: CanvasViewport): String? =
        RigCanvasSupport.nextLayer(layerCandidates(pos, viewport), state.selectedLayerId)

	fun pickSkeletonDrawable(pos: Offset, viewport: CanvasViewport): String? {
		val layerId = pickLayer(pos, viewport) ?: return null
		val preview = state.previewModel ?: return null
		val geometry = RigCanvasSupport.evaluate(preview)
		val x = viewport.canvasX(pos.x); val y = -viewport.canvasY(pos.y)
		return preview.rig.puppet.drawables.asReversed().firstOrNull { drawable ->
			if ((preview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw) != layerId) return@firstOrNull false
			val positions = geometry.worldPositions[drawable.id] ?: return@firstOrNull false
			val indices = drawable.mesh?.indices ?: return@firstOrNull false
			fun cross(a: Int, b: Int) = (positions[b * 2] - positions[a * 2]) * (y - positions[a * 2 + 1]) -
				(positions[b * 2 + 1] - positions[a * 2 + 1]) * (x - positions[a * 2])
			indices.indices.step(3).any { i ->
				val a = indices[i]; val b = indices[i + 1]; val c = indices[i + 2]
				val ab = cross(a, b); val bc = cross(b, c); val ca = cross(c, a)
				(ab >= 0f && bc >= 0f && ca >= 0f) || (ab <= 0f && bc <= 0f && ca <= 0f)
			}
		}?.id?.raw
	}

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
            if (state.selectedDeformerId != pick.deformerId) selectDeformer(pick.deformerId)
            return
        }
        objects = when (add) {
            true -> objects + layer
            false -> objects - layer
            null -> if (layer in objects) objects else setOf(layer)
        }
        if (add != false && state.selectedLayerId != layer) selectLayer(layer)
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
     * What a transform gesture edits: in Edit, every mesh with selected points - glue partners included,
     * so a glued point moves on both sides - and otherwise the one mesh or deformer the point tools are
     * working on. Object mode transforms every point of the selected layers or deformer.
     */
    private fun transformTargets(source: PuppetModel): List<CanvasTarget> {
        if (objectMode) {
            if (state.selectedDeformerId != null) return listOfNotNull(target(source))
            return objects.mapNotNull { target(source, it, null) }.ifEmpty { listOfNotNull(target(source)) }
        }
        if (!editsMeshGeometry()) return listOfNotNull(target(source))
        // A gesture starts here, so this is where a selection made in edge or face mode - which only
        // names the primary's vertices - is completed with the glued partners it moves.
        selection = weldGroups().expand(selection).filterValues { it.isNotEmpty() }
        return strokeTargets(source).filter { selection[it.id].orEmpty().isNotEmpty() }
    }

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
        anchorUvAtPress = frame?.anchorUv ?: Offset(0.5f, 0.5f)
        frameAnchorAtPress = frame?.anchor ?: pivot
        boxDrag = true
        dragging = true
    }

    fun press(pos: Offset, viewport: CanvasViewport, shift: Boolean, alt: Boolean, ctrl: Boolean = false): Boolean {
        if (adjustingBrush) endBrushAdjust(cancel = false)
        if (!editable) return true
        if (space) return false
        if (viewModel.isSnappingParameters) return true
        this.viewport = viewport
        error = null; gestureState = viewModel.currentWorkspaceState(); start = pos; previous = pos; dragStartPos = pos
        moved = false; additive = shift; subtractive = alt; pressedObject = null
        ctrlAtPress = ctrl

        // Place-then-confirm sessions own the canvas until Confirm/Esc (Warp / Rotation / Layer).
        val activePlacement = placement
        if (activePlacement != null && activePlacement.kind != CreatePlacementKind.PATH) {
            val handle = hitPlacementHandle(pos, viewport)
            if (handle != PlacementHandle.NONE) {
                placementHandle = handle
                placementDragStart = pos
                placementDragSnapshot = activePlacement.copy()
                dragging = true
            }
            return true
        }

        // A swing session owns the canvas until Apply/Esc: a press off its handles does nothing, so a
        // near miss cannot select or drag another object. More targets are registered from the tree.
        val swing = viewModel.swingSession
        if (swing != null) {
            val handle = if (swing.busy) null else hitSwingHandle(pos, viewport)
            if (handle != null) {
                if (handle.name.startsWith("PIVOT")) dragSwing(handle, pos, viewport)
                else { swingHandle = handle; dragging = true }
            }
            return true
        }

        // 0. The pose tool turns bones; a press off every bone does nothing, so a stray click cannot
        //    select or move points while the tool is armed.
        if (posing()) {
            if (beginPose(pos, viewport)) dragging = true
            return true
        }

        // 0. Paint Mode (L1 raster paint engine). Paint has no select/transform tool — anything else
        //    that somehow stays armed is a no-op rather than falling into point-edit gestures.
        if (hierarchyMode == EditHierarchyMode.PAINT) {
            if (tool !in PAINT_TOOLS) return true
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
            // Set on every press, so a pick whose release never arrived cannot turn the next stroke into one.
            isSampling = false

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
                        session.bucket(canvasPos.first, canvasPos.second, paintColor, paintTolerance,
                            tr("editor.paint.strokeFill"))
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

        // Creation: place-then-confirm ghost already handled above; path points still start here.
        if (tool in setOf(CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION) && placement != null) {
            return true
        }
        if (tool in setOf(CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH) &&
            target()?.kind != "mesh" && placement == null) {
            pickLayer(pos, viewport)?.let { selectLayer(it) }
            error = tr("editor.creationSelectFirst")
            return true
        }
        if (tool in WEIGHT_TOOLS) return beginWeightTool(pos, viewport, alt)
        if (tool == CanvasTool.GLUE) {
            if (glueMeshPair() == null) {
                error = tr("editor.glueNeedTwo", glueMeshCount())
                return true
            }
            // A weld is made where the meshes rest, as Cubism's glue: the pose goes back to its defaults first, so the
            // stroke picks the vertices it welds. Weights and ungluing change only pairs and work at any pose.
            val welds = glueSubTool == GlueSubTool.REMERGE || glueSubTool == GlueSubTool.BRUSH && !alt
            if (welds && viewModel.snapPoseToDefaults { press(pos, viewport, shift, alt, ctrl) }) return true
            glueErasing = alt
            glueStrokeA = emptySet()
            glueStrokeB = emptySet()
            glueStroking = true
            dragging = true
            accumulateGlueHits(pos, pos, viewport)
            return true
        }
        if (tool == CanvasTool.CREATE_DEFORM_PATH) {
            val t = target()
            if (t != null && t.kind == "mesh") {
                if (drawingPath) {
                    // Every point is read through the target the path started on, which is what it binds to.
                    val input = pathInput
                    val point = local(pos, input?.frame ?: t, viewport, draft.lastOrNull() ?: (0.5f to 0.5f))
                    if (input == null) pathInput = startInput(t.id, t, draft + point) else if (!input.append(point)) return true
                    draft = draft + point
                    return true
                }
                if (beginPathInteraction(pos, t, viewport, ctrl)) return true
                activePath = null
                pathPoint = -1
                drawingPath = true
                draftPathId = null
                draft = listOf(local(pos, t, viewport))
                pathInput = startInput(t.id, t, draft)
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
                    knifeDraft = emptyList(); knifeInput = null
                    knifeDrawableId = t.id
                }
                // Later clicks snap to the mesh the first one did, so every vertex index names the same mesh.
                val input = knifeInput
                val anchor = knifeAnchor(pos, input?.frame ?: t, viewport, shift)
                if (anchor != knifeDraft.lastOrNull()) {
                    if (input == null) knifeInput = startInput(t.id, t, listOf(anchor)) else if (!input.append(anchor)) return true
                    knifeDraft = knifeDraft + anchor
                }
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
        // At the Bezier level a warp is edited through its anchors and handles alone; the lattice under them is
        // shown for reference and takes no edits.
        val bezierOnly = hierarchyMode == EditHierarchyMode.DEFORM && editLevel == 2 && target()?.kind == "warp" &&
            tool !in CREATION_TOOLS

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
        // Not while Edit holds several meshes: picking a deformer there would drop the whole edit set for
        // a click that was almost always meant for a vertex near the badge.
        if (tool in SELECTION_TOOLS && !(ctrl && hierarchyMode == EditHierarchyMode.SELECT) && editMeshTargets().size < 2) {
            badgeAt(pos, viewport)?.let { id ->
                applyObjectPick(HierarchyPick(deformerId = id), null)
                return true
            }
        }
        if (bezierOnly) return true

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
            if (editsMeshes()) {
                val r = (radius * viewport.scale).toFloat()
                selection = mergedSelection(editVerticesWhere(viewport) { (it - pos).getDistance() <= r }, add = !alt, subtract = alt)
            } else if (t != null) {
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

        // With nothing selected the transform tool has no box to hold, so it picks the way the select tool does:
        // a click takes what is under it, a drag frames a selection - and the box appears on it.
        val transformPicks = tool == CanvasTool.TRANSFORM && transformFrame(viewport) == null
        val selects = tool == CanvasTool.SELECT || transformPicks
        if (tool == CanvasTool.TRANSFORM && !transformPicks) {
            val frame = transformFrame(viewport) ?: return true
            val handle = transformHandleAt(pos, frame)
            if (handle == BoundingHandle.NONE) return true
            // The anchor moves on its own: nothing is edited, so nothing is snapped or frozen.
            if (handle == BoundingHandle.ANCHOR) {
                activeHandle = handle
                anchorDragging = true
                dragging = true
                return true
            }
            val source = state.previewModel?.rig?.puppet ?: return true
            val targets = transformTargets(source)
            if (!objectMode && hierarchyMode == EditHierarchyMode.DEFORM &&
                viewModel.snapTargetsToNearestKeys(targets.map { it.kind to it.id }) {
                    press(pos, viewport, shift, alt, ctrl)
                }) return true
            beginTransformDrag(source, targets, handle, frame, viewport)
            return true
        }

        // Select picks objects or starts a marquee; only Transform drags the object box.
        if (hierarchyMode == EditHierarchyMode.SELECT && selects) {
            // A bone sits over the art it moves, so it is tried first: clicking one picks the skeleton.
            objectModeBoneHit(pos, viewport)?.let { hit ->
                selectSkeleton(hit.boneId)
                return true
            }
            val pick = objectPick(pos, viewport, ctrl)
            pressedObject = pick?.layerId
            if (pick != null || (!shift && !alt)) deselectSkeleton()
            if (pick != null) {
                applyObjectPick(pick, when { alt -> false; shift -> true; else -> null })
            } else if (!shift && !alt) {
                // Empty canvas clears the lot. Both calls are needed: each one only drops the other
                // half when it is given a non-null id, so neither alone clears a deformer selection.
                objects = emptySet()
                selectLayer(null)
                selectDeformer(null)
            }
            marquee = listOf(pos, pos)
            dragging = true
            return true
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

        if (editsMeshes() && (selects || tool in DEFORM_BRUSH_TOOLS)) {
            clearPathPointSelection()
            return pressEditMeshes(pos, viewport, shift, alt)
        }

        val editTarget = target() ?: return true
        // Mesh / topology gestures are separate from path handles — drop any lingering path-point grab.
        clearPathPointSelection()
        val brush = tool in DEFORM_BRUSH_TOOLS
        if (hierarchyMode == EditHierarchyMode.DEFORM && (brush || selects)) {
            if (viewModel.snapToNearestKeys(editTarget.kind, editTarget.id) {
                press(pos, viewport, shift, alt, ctrl)
            }) return true
        }
        targetAtPress = editTarget; original = model; dragging = true
        val points = screen(editTarget.geometry.points, editTarget, viewport)

        if (brush) {
            shrinkAtPress = inflateInvert xor alt
            if (editTarget.kind == "rotation") { dragging = false; error = io.github.psd2live.i18n.tr("editor.rotationBrush"); return true }
            try { beginDeformStroke(pos, viewport, model, listOf(editTarget), false) }
            catch (failure: Exception) {
                endMeshStroke(); dragging = false; preview = null; gestureState = null
                targetAtPress = null; original = null; error = failure.message
            }
            return true
        }

        var picked = points.indices.filter { (points[it] - pos).getDistance() < 10f }.minByOrNull { (points[it] - pos).getDistance() }?.let { setOf(it) }.orEmpty()
        var pickedEdge: MeshElement.Edge? = null
        var pickedFace: Int? = null
        if (pointElementModes() && editTarget.kind == "mesh") {
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
            if (!shift && !alt) { selection = emptyMap(); selectedEdges = emptySet(); selectedFaces = emptySet() }
        }
        if (editTarget.kind == "rotation") vertices = if (picked == setOf(1)) setOf(1) else setOf(0, 1)
        if (alt && picked.isNotEmpty() && editTarget.kind != "rotation") dragging = false
        // Edge and face picks on a rest mesh drag through the transform gesture too, which is what moves
        // the glued partners of the vertices they cover instead of tearing them off.
        if (selects && picked.isNotEmpty() && !alt && editsMeshGeometry()) {
            val source = state.previewModel?.rig?.puppet ?: return true
            beginTransformDrag(source, transformTargets(source), BoundingHandle.BODY, transformFrame(viewport), viewport)
        }
        return true
    }

    /**
     * A press of the select tool or a deform brush while Edit holds its mesh set. Points of every edited
     * mesh are picked alike, and a glued point is always picked, framed and moved as one.
     */
    private fun pressEditMeshes(pos: Offset, viewport: CanvasViewport, shift: Boolean, alt: Boolean): Boolean {
        val source = state.previewModel?.rig?.puppet ?: return true
        if (tool in DEFORM_BRUSH_TOOLS) {
            shrinkAtPress = inflateInvert xor alt
            try { beginMeshStroke(pos, viewport, source) } catch (failure: Exception) {
                endMeshStroke(); dragging = false; preview = null; gestureState = null
                targetAtPress = null; original = null; error = failure.message
            }
            return true
        }
        val picked = pickEditVertex(pos, viewport)
        val pickedSelected = picked != null &&
            weldGroups().members(picked).all { it.index in selection[it.mesh].orEmpty() }
        if (pickedSelected && !shift && !alt) {
            beginTransformDrag(source, transformTargets(source), BoundingHandle.BODY, selectionFrame(viewport), viewport)
            return true
        }
        if (picked == null) {
            marquee = listOf(pos, pos)
            if (!shift && !alt) selection = emptyMap()
            original = model
            dragging = true
            return true
        }
        selection = mergedSelection(mapOf(picked.mesh to setOf(picked.index)), add = shift, subtract = alt)
        if (alt) return true
        makePrimary(picked.mesh)
        // A press on a point grabs it straight away, as it does on a single mesh.
        beginTransformDrag(source, transformTargets(source), BoundingHandle.BODY, selectionFrame(viewport), viewport)
        return true
    }

    fun move(pos: Offset, viewport: CanvasViewport, shift: Boolean, alt: Boolean = false, ctrl: Boolean = false) {
        val effectiveCtrl = ctrl || ctrlAtPress
        this.viewport = viewport
        updateHover(pos, viewport, ctrl, shift)
        shrinks = if (dragging && tool == CanvasTool.INFLATE) shrinkAtPress else inflateInvert xor alt
        weightAltHeld = alt
        if (!dragging || busy) return
        swingHandle?.let { handle -> dragSwing(handle, pos, viewport); previous = pos; return }
        moved = moved || (pos - start).getDistance() > 2f
        if (!moved) return

        if (poseDrag != null) {
            dragPose(pos, viewport, ik = shift)
            previous = pos
            return
        }

        if (anchorDragging) {
            selectionFrame(viewport)?.let { frame ->
                transformAnchor = frame.anchorDraggedTo(pos)?.let { anchorKey() to it }
            }
            return
        }

        if (subdividing) {
            targetAtPress?.let { t -> subdivideEdges = subdivideEdges + edgesWithin(previous, pos, t, viewport) }
            previous = pos
            return
        }

        if (glueStroking) {
            accumulateGlueHits(previous, pos, viewport)
            previous = pos
            return
        }

        if (weightStroking) {
            cursor = pos
            dragWeightTool(previous, pos, viewport)
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
            applyPlacementDrag(pos, viewport, shift, alt)
            return
        }

        if (activeBezierHandle != null) {
            val (br, bc, dir) = activeBezierHandle!!
            val t = targetAtPress ?: return; val source = original ?: return
            val (lx, ly) = local(pos, t, viewport)
            bezierState?.moveHandle(br, bc, dir, lx, ly, smooth = !alt)
            val warp = source.deformers.filterIsInstance<Deformer.Warp>().firstOrNull { it.id.raw == t.id } ?: return
            val evaluated = bezierState?.evaluateLattice(warp.rows, warp.columns)?.also { points ->
                require(points.size == bezierResidual.size); for (index in points.indices) points[index] += bezierResidual[index]
            } ?: return
            // Keep the authored handles when this lattice is committed. Reconstructing them
            // from sampled anchors would straighten the curves before the next gesture.
            bezierSourcePoints = evaluated.copyOf()
            val cmd = RigBezierJournal.materialize(source, t.id, coordinate(t), pose,
                RigBezierJournal.Controls(requireNotNull(bezierState), bezierResidual), preserveChildren = effectiveCtrl)
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
            val evaluated = bezierState?.evaluateLattice(warp.rows, warp.columns)?.also { points ->
                require(points.size == bezierResidual.size); for (index in points.indices) points[index] += bezierResidual[index]
            } ?: return
            bezierSourcePoints = evaluated.copyOf()
            val cmd = RigBezierJournal.materialize(source, t.id, coordinate(t), pose,
                RigBezierJournal.Controls(requireNotNull(bezierState), bezierResidual), preserveChildren = effectiveCtrl)
            preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
            return
        }

        if (tool == CanvasTool.BRUSH_SELECT && editsMeshes()) {
            val r = (radius * viewport.scale).toFloat()
            val from = previous
            val hits = editVerticesWhere(viewport) { (it - pos).getDistance() <= r || distanceToSegment(it, from, pos) <= r }
            selection = mergedSelection(hits, add = !alt, subtract = alt)
            previous = pos
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

        if (meshStroke != null) {
            try { moveMeshStroke(pos, viewport, shift, effectiveCtrl) } catch (failure: Exception) {
                endMeshStroke(); dragging = false; preview = null; pending = null; pendingObjects = emptyList()
                targetAtPress = null; original = null; gestureState = null; error = failure.message
            }
            return
        }

        val t = targetAtPress ?: return; val source = original ?: return

        try {
            if (boxDrag) {
                val targets = objectTargets.ifEmpty { listOfNotNull(t) }
                if (targets.isEmpty() || initialScreenPoints.size != targets.size) return
                val editing = editsMeshGeometry()
                // A selection that spans one point - a vertex, or a glued point - has no box; it is dragged
                // directly, which is only meaningful for the Edit point tools.
                val b0 = initialBounds
                if (b0 == null && !editing) return
                val result = b0?.let {
                    TransformDrag(activeHandle, it, framePivotAtPress, frameAngleAtPress, start, frameAnchorAtPress).apply(pos, axis, shift, alt)
                }
                if (result != null) {
                    currentDragBounds = result.bounds
                    frameAngle = result.frameAngle
                }
                val delta = pos - start
                val worlds = targets.map { it.mapping.localToWorld(it.geometry.points) }
                val movedSets = targets.mapIndexed { itemIndex, item ->
                    val indices = dragIndices.getOrElse(itemIndex) { (0 until item.count).toSet() }
                    val world = worlds[itemIndex]
                    val pressPoints = initialScreenPoints[itemIndex]
                    val moved = HashSet<Int>()
                    for (i in indices) {
                        if (i !in pressPoints.indices) continue
                        val dest = result?.destination(pressPoints[i]) ?: (pressPoints[i] + delta)
                        world[i * 2] = ((dest.x - viewport.offsetX) / viewport.scale).toFloat()
                        world[i * 2 + 1] = -((dest.y - viewport.offsetY) / viewport.scale).toFloat()
                        moved += i
                    }
                    moved
                }
                // A glued point is one point: all of its members land on one spot, whatever the gesture.
                if (editing) keepWeldsTogether(targets, worlds, movedSets)
                pendingObjects = targets.mapIndexed { itemIndex, item ->
                    geometryCommand(item, item.mapping.worldToLocalLinearized(worlds[itemIndex], item.geometry.points, item.geometry.points, movedSets[itemIndex]), effectiveCtrl)
                }
                pendingLayers = if (objectMode && !editing) wholeLayerTransform(targets, worlds, movedSets) else null
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
                        val cmd = geometryCommand(t, DeformPathTools.deform(t.geometry.points, source.deformPaths, path.id, points, org.umamo.render.eval.DeformPathMetrics.canvasScale(source, source.drawables.single { it.id == path.drawableId })))
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

            // Brush gestures use the captured neutral session above. This is the select-tool translation.
            val base = t.geometry.points
            val points = screen(base, t, viewport); val world = t.mapping.localToWorld(base)
            val affected = vertices.filter { it in points.indices }.toSet()
            val delta = pos - start
            for (i in affected) {
                val destination = points[i] + delta
                world[i * 2] = ((destination.x - viewport.offsetX) / viewport.scale).toFloat()
                world[i * 2 + 1] = -((destination.y - viewport.offsetY) / viewport.scale).toFloat()
            }
            if (affected.isEmpty()) { previous = pos; return }
            val cmd = geometryCommand(t, t.mapping.worldToLocal(world, base, affected), effectiveCtrl)
            preview = RigAuthoringJournal.apply(source, cmd); pending = cmd; previous = pos
        } catch (e: Exception) { error = e.message }
    }

    fun release() {
        if (!dragging) return
        if (anchorDragging) {
            anchorDragging = false
            dragging = false
            activeHandle = BoundingHandle.NONE
            return
        }
        if (swingHandle != null) {
            swingHover = swingHandle
            swingHandle = null
            dragging = false
            return
        }
        if (poseDrag != null) {
            endPose()
            dragging = false
            return
        }
        if (subdividing) {
            subdividing = false
            dragging = false
            val covered = subdivideEdges
            subdivideEdges = emptySet()
            targetAtPress = null; original = null
            if (covered.isNotEmpty()) topology("subdivide", edges = covered)
            return
        }

        if (weightStroking) {
            weightStroking = false
            dragging = false
            commitWeightStroke()
            return
        }

        if (glueStroking) {
            glueStroking = false
            dragging = false
            val hitsA = glueStrokeA
            val hitsB = glueStrokeB
            glueStrokeA = emptySet()
            glueStrokeB = emptySet()
            if (hitsA.isEmpty() && hitsB.isEmpty()) return
            // Weights and ungluing only touch pairs that exist; without a glue there is nothing to do.
            val onlyPairs = glueSubTool == GlueSubTool.WEIGHT || (glueSubTool == GlueSubTool.BRUSH && glueErasing)
            if (onlyPairs && !glueAlreadyBound()) return
            when (glueSubTool) {
                GlueSubTool.WEIGHT -> commitGlueEdit("weights", hitsA, hitsB)
                GlueSubTool.REMERGE -> commitGlueEdit("remerge", hitsA, hitsB)
                GlueSubTool.BRUSH -> commitGlueEdit(if (glueErasing) "unglue" else "brush", hitsA, hitsB)
            }
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
                            val shapeStrokeWidth = (paintSize / paintPixelsPerUnit).coerceIn(1f, 512f)
                            session.shape(x0, y0, x1, y1, shape, paintColor, paintOpacity,
                                shapeStrokeWidth, paintShapeFilled && shape.canFill, tr(shape.strokeLabelKey))
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

        if (meshStroke != null) { finishDeformStroke(); return }

        dragging = false; ctrlAtPress = false; axis = null; activeHandle = BoundingHandle.NONE
        initialBounds = null
        initialScreenPoints = emptyList()
        boxDrag = false; dragIndices = emptyList()
        activeBrushWeights = null; activeBrushCenter = null; endMeshStroke()
        endPathDrag()

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
                preview = null; gestureState = null
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
        val layers = pendingLayers; pendingLayers = null
        if (moved && layers != null) commitLayerTransform(layers)
        else if (moved && pendingObjects.isNotEmpty()) { if (!commitBatch(pendingObjects)) preview = null }
        else if (moved && cmd != null) { if (!commitBatch(listOf(cmd))) preview = null }
        else { preview = null; gestureState = null; endTransformBox() }
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
        if (!painting && tool != CanvasTool.BRUSH && tool != CanvasTool.SMOOTH && tool != CanvasTool.INFLATE &&
            tool != CanvasTool.SUBDIVIDE && tool != CanvasTool.BRUSH_SELECT && tool != CanvasTool.GLUE &&
            tool != CanvasTool.WEIGHT_PAINT
        ) return false
        adjustingBrush = true
        brushAxis = when {
            // A paint tip has no angle, so Shift latches its third parameter - the opacity - instead,
            // which is the same pairing Photoshop uses for its Shift + right-drag.
            painting && shift -> BrushAdjustAxis.OPACITY
            painting -> null
            tool == CanvasTool.SUBDIVIDE || tool == CanvasTool.BRUSH_SELECT || tool == CanvasTool.GLUE -> null
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
            val ax = abs(dx); val ay = abs(dy)
            val travel = max(ax, ay)
            if (travel < BRUSH_AXIS_LOCK_PX) return
            // A diagonal start waits until one direction clearly dominates; only a long diagonal drag is
            // settled by the larger component.
            val clear = ax >= ay * BRUSH_AXIS_DOMINANCE || ay >= ax * BRUSH_AXIS_DOMINANCE
            if (!clear && travel < BRUSH_AXIS_FORCE_PX) return
            brushAxis = if (ax >= ay) BrushAdjustAxis.RADIUS else BrushAdjustAxis.HARDNESS
            // The adjustment starts from where the axis was decided, so the travel spent deciding does not
            // land as a jump.
            brushAnchor = pos
            return
        }
        if (paintBrushActive) {
            when (brushAxis) {
                BrushAdjustAxis.RADIUS -> paintSize = (paintSizeAtStart * 1.2f.pow(dx / BRUSH_RADIUS_STEP_PX)).coerceIn(1f, brushSizeLimit)
                BrushAdjustAxis.HARDNESS -> paintHardness = (paintHardnessAtStart + dy / BRUSH_HARDNESS_SPAN_PX).coerceIn(0f, 1f)
                BrushAdjustAxis.OPACITY -> paintOpacity = (paintOpacityAtStart + dy / BRUSH_HARDNESS_SPAN_PX).coerceIn(0.01f, 1f)
                BrushAdjustAxis.ANGLE, null -> Unit
            }
            return
        }
        when (brushAxis) {
            BrushAdjustAxis.RADIUS -> radius = (brushRadiusAtStart * 1.2f.pow(dx / BRUSH_RADIUS_STEP_PX)).coerceIn(4f, brushSizeLimit)
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
        val pickingObjects = hierarchyMode == EditHierarchyMode.SELECT
        if (pickingObjects && !moved) {
            pressedObject = null; marquee = emptyList(); original = null; gestureState = null; return
        }
        val polygon = if (selectionStyle == SelectionStyle.LASSO) marquee else listOf(marquee.first(), Offset(marquee.last().x, marquee.first().y), marquee.last(), Offset(marquee.first().x, marquee.last().y))
        if (pickingObjects) {
            val found = state.effectiveVisibleLayerIds.filter { id -> target(model, id, null)?.let { item -> screen(item.geometry.points, item, viewport).any { insidePolygon(it, polygon) } } == true }.toSet()
            objects = when {
                subtractive -> objects - found
                additive -> objects + found
                found.isEmpty() && pressedObject != null -> objects
                else -> found
            }
            selectLayer(objects.lastOrNull()); pressedObject = null; marquee = emptyList(); original = null; gestureState = null; return
        }
        if (editsMeshes()) {
            val found = editVerticesWhere(viewport) { insidePolygon(it, polygon) }
            selection = mergedSelection(found, add = additive, subtract = subtractive)
            selectedEdges = emptySet(); selectedFaces = emptySet()
            pressedObject = null; marquee = emptyList(); targetAtPress = null; original = null; gestureState = null
            return
        }
        val t = targetAtPress ?: target() ?: return
        val found = screen(t.geometry.points, t, viewport).mapIndexedNotNull { i, p -> if (insidePolygon(p, polygon)) i else null }.toSet()
        vertices = when { subtractive -> vertices - found; additive -> vertices + found; else -> found }
        selectedEdges = if (elementMode == 1) MeshTopology.edgesWithBothEndpointsSelected(t.indices, vertices) else emptySet()
        selectedFaces = if (elementMode == 2) MeshTopology.facesWithAllVerticesSelected(t.indices, vertices).mapTo(LinkedHashSet()) { it.triangleIndex } else emptySet()
        pressedObject = null; marquee = emptyList(); targetAtPress = null; original = null; gestureState = null
    }

    private fun neighbors(t: CanvasTarget): List<IntArray> = if (t.kind == "mesh") MeshTopology.buildVertexAdjacency(t.count, t.indices) else {
        val columns = t.geometry.columns!! + 1
        (0 until t.count).map { i -> listOfNotNull(if (i % columns > 0) i - 1 else null, if (i % columns < columns - 1) i + 1 else null, if (i >= columns) i - columns else null, if (i + columns < t.count) i + columns else null).toIntArray() }
    }
}

internal fun brushWeight(distance: Float, radius: Float, hardness: Float, falloff: BrushFalloff = BrushFalloff.SMOOTH, seed: Int = 0): Float {
    val x = ((distance / radius.coerceAtLeast(1f) - hardness) / (1f - hardness.coerceAtMost(0.95f))).coerceIn(0f, 1f)
    return falloff.weight(1f - x, seed)
}

/** Travel in raw px that equals one `]` press (one 1.2x step) in the Alt + right-drag radius gesture. */
private const val BRUSH_RADIUS_STEP_PX = 12f

/** The brush size limit on documents smaller than this. */
private const val MIN_BRUSH_SIZE_LIMIT = 512f

/** Vertical travel in raw px that spans the whole 0f..0.95f hardness range in the same gesture. */
private const val BRUSH_HARDNESS_SPAN_PX = 200f

/** Drag distance before the gesture commits to radius or hardness; below it nothing is adjusted. */
private const val BRUSH_AXIS_LOCK_PX = 12f

/** How many times larger one component of the drag must be than the other before it decides the axis. */
private const val BRUSH_AXIS_DOMINANCE = 2f

/** Drag distance past which an ambiguous diagonal is settled by its larger component anyway. */
private const val BRUSH_AXIS_FORCE_PX = 40f

/** How far from a vertex the weight readout still names it, in screen pixels. */
private const val WEIGHT_READOUT_REACH_PX = 24f

/**
 * Radially pushes [point] away from the closest point on the stroke segment [from]→[to], by [amount] pixels.
 * Degenerate directions return [Offset.Zero] rather than a NaN — a NaN here would be committed to history.
 * The epsilon (instead of an exact zero test) also stops the sign from strobing as the cursor sweeps a vertex.
 */
internal fun inflateOffset(point: Offset, from: Offset, to: Offset, amount: Float): Offset {
    val result = CanvasDeformStroke.inflateOffset(CanvasBrushPoint(point.x, point.y), CanvasBrushPoint(from.x, from.y),
        CanvasBrushPoint(to.x, to.y), amount)
    return Offset(result.x, result.y)
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
