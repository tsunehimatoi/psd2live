package io.github.psd2live.ui.state

import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MeshEdgeMode
import io.github.psd2live.core.MeshFillAlgorithm
import io.github.psd2live.core.SemanticTag

import androidx.compose.runtime.Immutable
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.EditHierarchyMode
import org.umamo.runtime.model.ParameterId
import io.github.psd2live.ui.theme.CustomTheme
import io.github.psd2live.ui.theme.ThemeCatalog
import io.github.psd2live.ui.theme.ToolColors

import io.github.psd2live.core.defaultMeshSettings
import io.github.psd2live.core.minimumAtlasSize
import io.github.psd2live.project.WorkspaceHistorySnapshot
import io.github.psd2live.project.ParameterSnapshot
import io.github.psd2live.project.HistoryAnnotation

/** Canvas rendering mode; [EDIT] shows rig geometry for editing, [PREVIEW] runs the animation. */
enum class CanvasMode {
	EDIT,
	PREVIEW,
}

/** Dock module id of the first canvas. Extra canvases use `canvas:<uuid>`. */
internal const val PRIMARY_CANVAS_ID = "canvas"

internal const val DEFAULT_WORKSPACE_ID = "workspace"

/** Right-hand dock modules the title-bar inspector toggle shows and hides together. */
internal val INSPECTOR_DOCK_MODULES = setOf(
	"settings", "layers", "parameters", "tools", "mesh", "inspector", "animation", "physics", "simulation",
)

/** Modules a fresh workspace layout already contains. History is added from the window menu. */
internal val DEFAULT_DOCK_MODULES = setOf(
	PRIMARY_CANVAS_ID, "hierarchy", "skeleton", "log", "animationEditor",
) + INSPECTOR_DOCK_MODULES

/** The texture workspace's atlas page view and per-layer texture panel; like history, not in every layout. */
internal val TEXTURE_DOCK_MODULES = setOf("atlas", "texture")

/** Which edge of the dock a sidebar hugs, relative to the region holding the canvases. */
enum class SidebarSide { LEFT, TOP, BOTTOM, RIGHT }

fun isCanvasModule(id: String): Boolean = id == PRIMARY_CANVAS_ID || id.startsWith("canvas:")

/**
 * View options a fresh canvas of this mode starts with (and what "reset view options" restores).
 * A canvas opens in object mode without deformer guides; Deform mode's preset turns the warp guides on.
 */
fun CanvasMode.defaultViewOptions(): TabViewOptions = when (this) {
	CanvasMode.EDIT, CanvasMode.PREVIEW -> TabViewOptions.Default
}

/**
 * Display seeds for a hierarchy mode's first visit. Each mode then keeps its own toggles (see
 * [CanvasModeSession.withHierarchyView]), so a preset never overrides what the user set there.
 */
fun hierarchyModeViewPreset(mode: EditHierarchyMode, current: TabViewOptions): TabViewOptions = when (mode) {
	EditHierarchyMode.SELECT -> current.copy(showMesh = false)
	EditHierarchyMode.DEFORM -> current.copy(
		showMesh = true,
		showWarp = true,
		showDeformPaths = true,
	)
	EditHierarchyMode.EDIT -> current.copy(showMesh = true, showDeformPaths = true)
	// The weights are the subject: the wires stay, the deformer guides would only cover them.
	EditHierarchyMode.SIMULATE -> current.copy(
		showMesh = true,
		showWarp = false,
		showRotation = false,
		showDeformPaths = false,
	)
	// Bones over the art and nothing else, the way skeleton editing has always shown them.
	EditHierarchyMode.SKELETON -> current.copy(
		showMesh = false,
		showWarp = false,
		showRotation = false,
		warpShowIndices = false,
		showSkeleton = true,
	)
	EditHierarchyMode.PAINT -> current.copy(
		showMesh = false,
		showWarp = false,
		showRotation = false,
		showDeformPaths = false,
	)
}

/**
 * Per-tab canvas display options. Mirrors the View menu's canvas and annotation toggles so two
 * tabs can be inspected with different overlays at the same time.
 */
@Immutable
data class TabViewOptions(
	val showTexture: Boolean = true,
	val showMesh: Boolean = false,
	val showWarp: Boolean = false,
	val showRotation: Boolean = false,
	val showDeformPaths: Boolean = true,
	/** Object mode's faint armature, which is also what a click picks the skeleton by. */
	val showSkeleton: Boolean = true,
	val warpShowNames: Boolean = true,
	val warpShowIndices: Boolean = false,
	val pathShowWidth: Boolean = false,
	val pathShowHardness: Boolean = false,
	val filterSelectedOnly: Boolean = false,
	val dimUnselected: Boolean = true,
	val contextualWarp: Boolean = true,
	val showSelectionBounds: Boolean = false,
	/**
	 * Edit canvases sample each layer's own raster instead of its atlas tile, so the artwork shows at
	 * source resolution rather than at the packed density. The native preview always shows atlas pixels.
	 */
	val sourcePixels: Boolean = false,
	/** How a preview canvas shows a running reference simulation against the baked export. */
	val simulationView: SimulationView = SimulationView.REFERENCE,
) {
	/** Point indices are only painted together with the warp overlay they annotate. */
	fun normalized(): TabViewOptions = copy(showWarp = showWarp || warpShowIndices)

	companion object {
		val Default = TabViewOptions()
	}
}

/** How a baked simulation followed its reference over [motions]: per motion its check, or why it could not be made. */
data class SimulationCheck(
	val motions: List<String>,
	val results: List<io.github.psd2live.core.sim.SimMotionCheck>,
	val running: Boolean = false,
	val error: String? = null,
)

/**
 * What a preview canvas shows while a simulation's reference runs: the reference in place of the export, the
 * export alone (beside a canvas showing the reference), or the export with the reference drawn faded over it.
 */
enum class SimulationView(val jsonName: String) {
	REFERENCE("reference"), EXPORT("export"), OVERLAY("overlay");

	companion object {
		fun parse(text: String?) = entries.firstOrNull { it.jsonName == text } ?: REFERENCE
	}
}

/** Per-canvas camera, so two canvases in one workspace can keep their own zoom and pan. */
@Immutable
data class TabCamera(
	val zoom: Float = 1f,
	val panX: Float = 0f,
	val panY: Float = 0f,
)

/** State owned by one mode of a canvas. The document/model is shared by the workspace. */
@Immutable
data class CanvasModeSession(
	val view: TabViewOptions,
	val camera: TabCamera = TabCamera(),
	val presentation: CanvasPresentation = CanvasPresentation(),
	/** Hierarchy mode [view] belongs to. Null until the canvas first enters one. */
	val viewMode: EditHierarchyMode? = null,
	/** Toggles the other hierarchy modes were left with, restored when they are entered again. */
	val modeViews: Map<EditHierarchyMode, TabViewOptions> = emptyMap(),
) {
	/**
	 * Parks [view] under its mode and brings up [next]'s own toggles, seeded by its preset on first visit.
	 * A canvas opens in object mode, so the view it shows before any switch is object mode's as is.
	 */
	fun withHierarchyView(next: EditHierarchyMode): CanvasModeSession {
		val from = viewMode ?: return copy(viewMode = EditHierarchyMode.SELECT).withHierarchyView(next)
		if (from == next) return this
		return copy(
			view = modeViews[next] ?: hierarchyModeViewPreset(next, view),
			viewMode = next,
			modeViews = modeViews - next + (from to view),
		)
	}
}

/** One canvas pane with independent edit and preview sessions. */
@Immutable
data class CanvasWindowState(
	val id: String = PRIMARY_CANVAS_ID,
	val mode: CanvasMode = CanvasMode.EDIT,
	val editSession: CanvasModeSession = CanvasModeSession(CanvasMode.EDIT.defaultViewOptions()),
	val previewSession: CanvasModeSession = CanvasModeSession(CanvasMode.PREVIEW.defaultViewOptions()),
) {
	fun session(mode: CanvasMode = this.mode): CanvasModeSession =
		if (mode == CanvasMode.EDIT) editSession else previewSession

	val view: TabViewOptions get() = session().view
	val camera: TabCamera get() = session().camera
	val presentation: CanvasPresentation get() = session().presentation

	fun updateSession(mode: CanvasMode = this.mode, transform: (CanvasModeSession) -> CanvasModeSession): CanvasWindowState =
		if (mode == CanvasMode.EDIT) copy(editSession = transform(editSession))
		else copy(previewSession = transform(previewSession))
}

/**
 * What a workspace is for. A preset seeds the canvases, the dock arrangement (see
 * `presetDockLayout`) and which panels start hidden; "reset layout" returns to it.
 *
 * Every preset's dock tree holds every panel, so a hidden panel shown from the window menu
 * reappears where that task expects it rather than at an arbitrary edge. Only [HISTORY] and
 * [BLANK] start with their canvas hidden.
 */
enum class WorkspacePreset(
	/** Canvas modes in dock order. The first canvas takes [PRIMARY_CANVAS_ID]. */
	val canvasModes: List<CanvasMode>,
	val hiddenModules: Set<String>,
) {
	/** Deformers, keyforms and skeleton on one edit canvas, with every property panel at hand. */
	EDIT(listOf(CanvasMode.EDIT), emptySet()),

	/** Mesh topology: the hierarchy beside the edit canvas, the mesh panel on the right. */
	MESH(
		listOf(CanvasMode.EDIT),
		setOf("layers", "skeleton", "log", "animationEditor", "tools", "inspector", "settings", "parameters", "animation", "physics", "simulation"),
	),

	/** Binding parameters: the edit canvas and a live preview side by side, parameters always visible. */
	RIG(
		listOf(CanvasMode.EDIT, CanvasMode.PREVIEW),
		setOf("animationEditor", "settings", "layers", "mesh", "animation", "physics", "simulation"),
	),

	/** Authoring motions: motions left of the preview canvas, the animation editor below both, parameters on the right. */
	ANIMATION(
		listOf(CanvasMode.PREVIEW),
		setOf("hierarchy", "skeleton", "log", "settings", "layers", "tools", "mesh", "inspector", "physics", "simulation"),
	),

	/** Checking the finished model: a large preview with the motion list only. */
	PREVIEW(
		listOf(CanvasMode.PREVIEW),
		setOf("hierarchy", "skeleton", "log", "animationEditor", "settings", "layers", "tools", "mesh", "inspector", "parameters", "physics", "simulation"),
	),

	/** Tuning physics: the preview and parameters beside a wide physics panel, to shake the model while editing. */
	PHYSICS(
		listOf(CanvasMode.PREVIEW),
		setOf("hierarchy", "skeleton", "log", "animationEditor", "settings", "layers", "tools", "mesh", "inspector", "animation"),
	),

	/**
	 * Texture sizes: the atlas pages on the left, an edit canvas to compare atlas and source pixels, and the
	 * per-layer texture panel with the layer list on the right. Its two panels ([TEXTURE_DOCK_MODULES]) are
	 * docked by this preset only; other workspaces show them from the window menu like the history tree.
	 */
	TEXTURE(
		listOf(CanvasMode.EDIT),
		setOf("hierarchy", "skeleton", "log", "animationEditor", "settings", "tools", "mesh", "inspector", "parameters", "animation", "physics", "simulation"),
	),

	/**
	 * Browsing history: only the history tree and its operation list. Like [BLANK] it keeps one
	 * hidden edit canvas and the edit arrangement beside the history, for panels shown later.
	 */
	HISTORY(listOf(CanvasMode.EDIT), DEFAULT_DOCK_MODULES),

	/**
	 * An empty dock to build up from the window menu. It keeps one hidden edit canvas, so a canvas
	 * is always there to show, and the edit arrangement, so each panel shown lands where it usually sits.
	 */
	BLANK(listOf(CanvasMode.EDIT), DEFAULT_DOCK_MODULES),
	;

	fun title(): String = tr("workspace.preset.${name.lowercase()}")

	fun description(): String = tr("workspace.preset.${name.lowercase()}.desc")

	fun canvases(secondaryId: (Int) -> String = { "canvas:${java.util.UUID.randomUUID()}" }): List<CanvasWindowState> =
		canvasModes.mapIndexed { index, mode ->
			CanvasWindowState(id = if (index == 0) PRIMARY_CANVAS_ID else secondaryId(index), mode = mode)
		}
}

/**
 * A named arrangement of docked panels and canvases.
 *
 * The project session (model, edits, parameters) is shared. A workspace only remembers where
 * components sit, which of them are shown, and how each canvas is framed.
 */
@Immutable
data class EditorWorkspace(
	val id: String,
	/** Authoring pose and playback controls shared by every canvas in this workspace. */
	val pose: WorkspacePose? = null,
	val name: String = "",
	val preset: WorkspacePreset = WorkspacePreset.EDIT,
	/** Serialized dock tree. Null means the [preset]'s arrangement. */
	val layoutJson: String? = null,
	val hiddenModules: Set<String> = emptySet(),
	/** Panels each hidden sidebar had shown, keyed by [SidebarSide] name, so showing it again brings back the same ones. */
	val sidebarRestore: Map<String, Set<String>> = emptyMap(),
	/** Modules the user asked to show that are not in the layout yet. The dock consumes this list. */
	val placeModules: List<String> = emptyList(),
	val canvases: List<CanvasWindowState> = listOf(defaultEditCanvas()),
	val activeCanvasId: String = canvases.firstOrNull()?.id ?: PRIMARY_CANVAS_ID,
) {
	val activeCanvas: CanvasWindowState
		get() = canvases.firstOrNull { it.id == activeCanvasId }
			?: canvases.firstOrNull()
			?: defaultEditCanvas()
}

internal fun defaultEditCanvas(): CanvasWindowState = CanvasWindowState(
	id = PRIMARY_CANVAS_ID,
	mode = CanvasMode.EDIT,
)

internal fun defaultEditorWorkspace(): EditorWorkspace = EditorWorkspace(id = DEFAULT_WORKSPACE_ID)

/**
 * A fresh session opens one workspace per task preset, edit first. [BLANK][WorkspacePreset.BLANK]
 * is left out: it only exists to be built up. Ids are fixed so default states compare equal.
 */
internal fun defaultEditorWorkspaces(): List<EditorWorkspace> =
	WorkspacePreset.entries.filter { it != WorkspacePreset.BLANK }.map { preset ->
		if (preset == WorkspacePreset.EDIT) defaultEditorWorkspace()
		else {
			val id = "$DEFAULT_WORKSPACE_ID:${preset.name.lowercase()}"
			presetEditorWorkspace(id, preset, canvases = preset.canvases { index -> "canvas:$id:$index" })
		}
	}

internal fun presetEditorWorkspace(
	id: String,
	preset: WorkspacePreset,
	name: String = "",
	canvases: List<CanvasWindowState> = preset.canvases(),
): EditorWorkspace {
	return EditorWorkspace(
		id = id,
		name = name,
		preset = preset,
		hiddenModules = preset.hiddenModules,
		canvases = canvases,
		activeCanvasId = canvases.first().id,
	)
}

/** Blank names stay localized and follow the preset; a name the user typed is kept as written. */
fun EditorWorkspace.displayName(): String = name.ifBlank { preset.title() }


enum class LogSource {
	SYSTEM,
	MCP_SERVER,
	AGENT,

	/** A human at the editor: canvas authoring, history checkouts, the same acts an Agent host can ask for. */
	EDITOR,
}

/** How much a log line matters; the dock shows lines at or above a chosen [severity]. SUCCESS ranks with INFO. */
enum class LogLevel(val severity: Int) {
	/** Routine reads, such as an Agent inspecting the workspace: hidden unless the dock asks for them. */
	DEBUG(0),
	INFO(1),
	SUCCESS(1),
	WARNING(2),
	ERROR(3),
}

/** The log keeps the newest this many entries: a long session, or an agent's, would otherwise grow it for good. */
internal const val LOG_ENTRY_LIMIT = 1000

/** [this] log with [entries] appended, dropping the oldest past [LOG_ENTRY_LIMIT]. */
internal fun List<AppLogEntry>.appendingLog(entries: List<AppLogEntry>): List<AppLogEntry> =
	if (size + entries.size <= LOG_ENTRY_LIMIT) this + entries else (this + entries).takeLast(LOG_ENTRY_LIMIT)

@Immutable
data class AppLogEntry(
	val id: String = java.util.UUID.randomUUID().toString(),
	val timestamp: java.time.Instant = java.time.Instant.now(),
	val source: LogSource = LogSource.SYSTEM,
	val level: LogLevel = LogLevel.INFO,
	val tag: String = "",
	val message: String,
	val detail: String? = null,
	val imageBytes: ByteArray? = null,
	val imageLabel: String? = null,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is AppLogEntry) return false
		return id == other.id
	}

	override fun hashCode(): Int = id.hashCode()
}

enum class InspectorTab {
	LAYERS,
	PARAMETERS,
	TOOL_DETAILS,
	MESH,
	INSPECTOR,
	ANIMATION,
	PHYSICS,
}


@Immutable
data class PSD2LiveState(
    val canvasEditBusy: Boolean = false,
    val editorDraftBusy: Boolean = false,
    /** Authored pose changes the panels already show are still waiting for their commits, in order. */
    val poseCommitBusy: Boolean = false,
	val projectId: String? = null,
    val projectFile: String? = null,
	/** Recently opened .psd2live / PSD paths. Application preference, not part of the project. */
	val recentFiles: List<String> = AppSettings.recentFiles(),
    val projectDirty: Boolean = false,
    val projectSaving: Boolean = false,
    val projectSaveError: String? = null,
    val showProjectLocationDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val showExportPsdDialog: Boolean = false,
    /** The neutral export target whose dialog is open, or null. */
    val otherExportTarget: String? = null,
    val isExportingPsd: Boolean = false,
    val projectOpenGeneration: Long = 0,
    val projectEditVersion: Long = 0,
    val projectAuxiliaryVersion: Long = 0,
    val projectSourceName: String? = null,
    val historyZoom: Float = 1f,
    val historyPanX: Float = 0f,
    val historyPanY: Float = 0f,
    val historySearch: String = "",
    val historyShowHidden: Boolean = false,
    val hierarchyWidth: Float = 210f,
    val hierarchySearch: String = "",
    val drawOrderRulerWidth: Float = 24f,
    val workspaceSplitRatio: Float = 0.60f,
	/** One-shot request for DockWorkspaceView to select a dock module tab (e.g. "layers"). */
	val requestedDockModule: String? = null,
    val workspaces: List<EditorWorkspace> = defaultEditorWorkspaces(),
    val activeWorkspaceId: String = DEFAULT_WORKSPACE_ID,
    val historyAnnotations: Map<String, HistoryAnnotation> = emptyMap(),
    val inputPath: String = "",
	/** Input identity that produced [analysis]; remains stable while the user edits the next path field. */
	val loadedInputPath: String? = null,
	val loadedInputFileSignature: String? = null,
	val outputPath: String = "",
	val atlasSize: Int = 4096,
	val textureUpscale: io.github.psd2live.core.TextureUpscaleConfig = io.github.psd2live.core.TextureUpscaleConfig(),
	val meshSpacing: Int = 40,
	val meshOuterMargin: Float = 1.0f,
	val meshEdgeMode: MeshEdgeMode = MeshEdgeMode.SINGLE,
	val meshEdgeWidth: Float = 10.0f,
	val meshMaxEdgeDistance: Float = 6.0f,
	val meshInteriorDensity: Float = 40.0f,
	val meshFillAlgorithm: MeshFillAlgorithm = MeshFillAlgorithm.GRADED_POISSON,
	val meshSuppressBoundaryDiagonals: Boolean = false,
	val meshFillParameters: io.github.psd2live.core.MeshFillParameters = io.github.psd2live.core.MeshFillParameters(),
	val meshOverrides: Map<String, MeshSettings> = emptyMap(),
	/** New projects measure mesh lengths at the reference document size; older ones keep source pixels. */
	val meshUnits: io.github.psd2live.core.MeshUnits = io.github.psd2live.core.MeshUnits.DOCUMENT,
	val meshTrace: io.github.psd2live.core.MeshTrace = io.github.psd2live.core.MeshTrace.TEXTURE,
	val meshWrap: Float = 0f,
	val texturePadding: Int = 2,
	val alphaThreshold: Int = 8,
	val headStrength: Float = 1.0f,
	val bodyStrength: Float = 1.0f,
	val rigTuning: io.github.psd2live.core.RigTuning = io.github.psd2live.core.RigTuning(),
	val meshOnly: Boolean = false,
	val generateDeformers: Boolean = true,
	val featureDisplacementEnabled: Boolean = false,
	val mouthOutlineEnabled: Boolean = true,
	val mouthShape: String = "smile",
    val mouthCurve: io.github.psd2live.core.MouthCurve = io.github.psd2live.core.MouthCurve.preset("smile"),
    val mouthColor: Int? = null,
    val mouthThickness: Float = 1.5f,
	val exportMotions: Boolean = true,
	val motionBasic: Boolean = true,
	val motionIdle: Boolean = true,
	val motionBlink: Boolean = true,
	val motionNod: Boolean = true,
	val motionShake: Boolean = true,
	val motionSkeleton: Boolean = true,
	val generatePhysics: Boolean = true,
	val physicsFrontHair: Boolean = true,
	val physicsBackHair: Boolean = true,
	val physicsEyeJelly: Boolean = true,
	val hairSimulationFront: Boolean = false,
	val hairSimulationBack: Boolean = false,
	val exportCmo3: Boolean = true,
	val exportMoc3: Boolean = true,
	val exportJson: Boolean = true,
	val runtimeTarget: org.umamo.runtime.model.RuntimeTarget = org.umamo.runtime.model.RuntimeTarget.Cubism50,
	val exportHiddenParts: Boolean = false,
	val exportHiddenDrawables: Boolean = false,
	val exportGuideImageParts: Boolean = false,
	val exportIncludePhysics: Boolean = true,
	val exportIncludeUserData: Boolean = true,
	val exportIncludeDisplayInfo: Boolean = true,
	val exportPixelsPerUnit: Float? = null,
	val exportOptionsExpanded: Boolean = true,
	val motionSubExpanded: Boolean = false,
	val physicsSubExpanded: Boolean = false,
	val dynamicsSubExpanded: Boolean = false,
	val projectOutputsExpanded: Boolean = false,
	val simulationPresetsExpanded: Boolean = true,
	val strengthSubExpanded: Boolean = false,
	val rigTuningExpanded: Boolean = false,
	/** The rig values' Advanced folder is open. */
	val rigTuningAdvancedExpanded: Boolean = false,
	val advancedExpanded: Boolean = false,
	val isAnalyzing: Boolean = false,
	val isGenerating: Boolean = false,
	val isUpscaling: Boolean = false,
	val progress: Float = 0f,
	val isIndeterminateProgress: Boolean = false,
	val statusText: String = "",
	val logEntries: List<AppLogEntry> = emptyList(),
	val logPanelHeight: Float = 190f,
	val historySnapshot: WorkspaceHistorySnapshot? = null,
	val selectedHistoryNodeId: String? = null,
	val lightboxImage: ByteArray? = null,
	val lightboxTitle: String? = null,
	val analysis: PipelineAnalysis? = null,
	val previewModel: RigPreviewModel? = null,
	/**
	 * True while [previewModel] carries edits the document has not recorded yet.
	 *
	 * The inspector previews a field edit on the puppet immediately so the canvas stays live, and records
	 * it in the document when the field session ends. Anything compiling an authoring command has to know
	 * the difference: against a patched preview, a command writing the value already on screen looks like
	 * a no-op, and the edit would never reach the document. Cleared whenever the preview is rebuilt from
	 * the document. The canvas never patches the puppet, so its gestures always see false here.
	 */
	val previewModelDirty: Boolean = false,
	val selectedLayerId: String? = null,
	/** Object multi-selection of the focused canvas; [selectedLayerId] is its primary item. */
	val selectedLayerIds: Set<String> = emptySet(),
	val selectedDeformerId: String? = null,
	/** Source ArtMesh waiting for the next canvas/layer pick to become its clipping mask. UI-transient. */
	val clipMaskPickSourceId: String? = null,
	/** The prompts turned off with "Don't show again"; mirrors [AppSettings.mutedPrompts]. */
	val mutedPrompts: Set<AppPrompt> = AppSettings.mutedPrompts(),
	val hoveredLayerId: String? = null,
	val hoveredDeformerId: String? = null,
	val layerVisibility: Map<String, Boolean> = emptyMap(),
	/** The document's own layer visibility. [layerVisibility] is the focused canvas's local filter and never enters it. */
	val documentLayerVisibility: Map<String, Boolean> = emptyMap(),
	val deformerVisibility: Map<String, Boolean> = emptyMap(),
	val layerOverrides: Map<String, LayerClassificationOverride> = emptyMap(),
	val isolationSnapshot: Map<String, Boolean>? = null,
	val isolatedLayerId: String? = null,
	val lockedParameters: Set<ParameterId> = emptySet(),
	val parameterValues: Map<ParameterId, Float> = emptyMap(),
	val previewParameterValues: Map<ParameterId, Float> = emptyMap(),
	val parameterSearchQuery: String = "",
	/** Saved parameter snapshots, shown as the snapshot bar; project data, never history. */
	val parameterSnapshots: List<ParameterSnapshot> = emptyList(),
	val animationEnabled: Boolean = false,
	val mouseTrackingEnabled: Boolean = true,
	val smoothMouseTracking: Boolean = false,
	val sdkStatus: String? = null,
	val activeInspectorTab: InspectorTab = InspectorTab.LAYERS,
	val currentLanguage: AppLanguage = I18n.currentLanguage,
	val uiScale: Float = AppSettings.uiScale,
	val fontScale: Float = AppSettings.fontScale,
	/** Chrome palette. Like [uiScale], these are application preferences, not part of the project. */
	val themeId: String = AppSettings.themeId,
	val customThemes: List<CustomTheme> = AppSettings.customThemes(),
	/** The palette [themeId] resolves to, kept here so the tree reads one resolved value. */
	val toolColors: ToolColors = ThemeCatalog.resolve(themeId, customThemes),
	/** Shared by every canvas; an application preference, absent from WorkspaceStateCodec. */
	val canvasBackground: CanvasBackground = AppSettings.canvasBackground,
	val showSettingsDialog: Boolean = false,
	/** App-level texture upscale prompt; must not be mounted inside a Column (scrim is fillMaxSize). */
	val showTextureUpscaleDialog: Boolean = false,
	/** The texture workspace's page, overlays and drag draft; UI-transient, never saved. */
	val textureWorkspace: TextureWorkspaceState = TextureWorkspaceState(),
	/**
	 * Keyboard shortcuts. Not part of the project: like [uiScale] these are application preferences,
	 * so they are absent from WorkspaceStateCodec and no edit here may mark the project dirty.
	 */
	val keymap: Keymap = loadPersistedKeymap(),
	val keymapPreset: KeymapPreset = AppSettings.keymapPreset,
	val keyCapture: KeyCapture? = null,
	/** Bumped to pull focus back to the canvas when a modal that stole it closes. */
	val focusCanvasRequest: Int = 0,
	val deletedLayerIds: Set<String> = emptySet(),
	val parentOverrides: Map<String, String?> = emptyMap(),
	val drawOrderOverrides: Map<String, Float> = emptyMap(),
	/** Durable parameter/keyform edits replayed after each generated-rig rebuild. */
	val rigEdits: RigEditOverlay = RigEditOverlay.Empty,
	val generationSource: org.umamo.format.art.SourceArt? = null,
	val meshSource: org.umamo.format.art.SourceArt? = null,
	/** Projection of the document's per-layer texture overrides; kept so GUI commits do not drop them. */
	val textureOverrides: Map<String, io.github.psd2live.project.TextureOverride> = emptyMap(),
	/** Projection of the optional `atlas` budget setting; null keeps the legacy atlasSize/texturePadding behaviour. */
	val atlasBudget: io.github.psd2live.core.AtlasBudget? = null,
	/** Projection of the optional stored atlas arrangement; kept so GUI commits do not drop it. */
	val atlasArrangement: io.github.psd2live.project.AtlasArrangement? = null,
	/** The simulation the preview runs live over the rig, or null; the canvas then draws in software. */
	val simulationPreviewId: String? = null,
	val errorMessage: String? = null,
	/** The export that just finished, shown in the export-success dialog until it is closed. */
	val exportSuccess: ExportSuccess? = null,
) {
	val darkTheme: Boolean get() = toolColors.isDark

	val autoDetectMeshSplitsOnImport: Boolean get() = AppPrompt.START_SCREEN_ON_IMPORT !in mutedPrompts

	val activeWorkspace: EditorWorkspace
		get() = workspaces.firstOrNull { it.id == activeWorkspaceId }
			?: workspaces.firstOrNull()
			?: defaultEditorWorkspace()

	val activeCanvas: CanvasWindowState get() = activeWorkspace.activeCanvas

	/** A preview canvas is shown, so playback and live parameters should keep running. */
	val previewLive: Boolean
		get() = activeWorkspace.canvases.any { canvas ->
			canvas.mode == CanvasMode.PREVIEW && canvas.id !in activeWorkspace.hiddenModules
		}

	/**
	 * History is on screen, or the user just asked for it and the dock has not written the layout yet.
	 */
	val historyPanelShown: Boolean
		get() {
			val workspace = activeWorkspace
			if ("history" in workspace.hiddenModules) return false
			if ("history" in workspace.placeModules) return true
			val json = workspace.layoutJson ?: return workspace.preset == WorkspacePreset.HISTORY
			return "\"history\"" in json
		}

	val activeTabView: TabViewOptions get() = activeCanvas.view

	val showWarp: Boolean get() = activeTabView.showWarp
	val showRotation: Boolean get() = activeTabView.showRotation
	val showDeformPaths: Boolean get() = activeTabView.showDeformPaths
	val showMesh: Boolean get() = activeTabView.showMesh
	val showSkeleton: Boolean get() = activeTabView.showSkeleton
	val showTexture: Boolean get() = activeTabView.showTexture
	val warpShowNames: Boolean get() = activeTabView.warpShowNames
	val warpShowIndices: Boolean get() = activeTabView.warpShowIndices
	val pathShowWidth: Boolean get() = activeTabView.pathShowWidth
	val pathShowHardness: Boolean get() = activeTabView.pathShowHardness
	val filterSelectedOnly: Boolean get() = activeTabView.filterSelectedOnly
	val dimUnselected: Boolean get() = activeTabView.dimUnselected
	val contextualWarp: Boolean get() = activeTabView.contextualWarp
	val showSelectionBounds: Boolean get() = activeTabView.showSelectionBounds

	val canvasZoom: Float get() = activeCanvas.camera.zoom
	val canvasPanX: Float get() = activeCanvas.camera.panX
	val canvasPanY: Float get() = activeCanvas.camera.panY

	// Panel visibility lives in the active workspace's hidden modules; these read it back.
	val hierarchyCollapsed: Boolean get() = "hierarchy" in activeWorkspace.hiddenModules
	val logPanelExpanded: Boolean get() = "log" !in activeWorkspace.hiddenModules
	val inspectorCollapsed: Boolean get() = INSPECTOR_DOCK_MODULES.all { it in activeWorkspace.hiddenModules }

	fun updateWorkspace(id: String, transform: (EditorWorkspace) -> EditorWorkspace): PSD2LiveState =
		copy(workspaces = workspaces.map { if (it.id == id) transform(it) else it })

	fun updateActiveWorkspace(transform: (EditorWorkspace) -> EditorWorkspace): PSD2LiveState =
		updateWorkspace(activeWorkspace.id, transform)

	fun updateCanvas(canvasId: String, transform: (CanvasWindowState) -> CanvasWindowState): PSD2LiveState =
		updateActiveWorkspace { workspace ->
			workspace.copy(canvases = workspace.canvases.map { if (it.id == canvasId) transform(it) else it })
		}

	/** Whether the model presets keep generated motion [name]'s group, the basic motions or the skeleton presets. */
	fun motionPresetGroupOn(name: String): Boolean =
		if (io.github.psd2live.core.MotionClips.isSkeletonPreset(name)) motionSkeleton else motionBasic

	/** What generation consumes; the same raw-to-effective rule the document applies. */
	fun buildConfig(): PipelineConfig = io.github.psd2live.project.WorkspaceSettingsPolicy.effective(rawConfig())

	/** The settings as chosen, for a new document's raw settings. */
	fun rawConfig(): PipelineConfig {
		return PipelineConfig(
			atlasSize = atlasSize,
			textureUpscale = textureUpscale,
			texturePadding = texturePadding,
			meshSpacing = meshSpacing,
			meshOuterMargin = meshOuterMargin,
			meshEdgeMode = meshEdgeMode,
			meshEdgeWidth = meshEdgeWidth,
			meshMaxEdgeDistance = meshMaxEdgeDistance,
			meshInteriorDensity = meshInteriorDensity,
			meshFillAlgorithm = meshFillAlgorithm,
			meshSuppressBoundaryDiagonals = meshSuppressBoundaryDiagonals,
			meshFillParameters = meshFillParameters,
			meshOverrides = meshOverrides,
			meshUnits = meshUnits,
			meshTrace = meshTrace,
			meshWrap = meshWrap,
			alphaThreshold = alphaThreshold,
			headTurnStrength = headStrength,
			bodyStrength = bodyStrength,
			rigTuning = rigTuning,
			meshOnly = meshOnly,
			generateDeformers = generateDeformers,
			featureDisplacementEnabled = featureDisplacementEnabled,
			mouthOutlineEnabled = mouthOutlineEnabled,
			mouthShape = mouthShape,
            mouthCurve = mouthCurve,
            mouthColor = mouthColor,
            mouthThickness = mouthThickness,
			exportMotions = exportMotions,
			motionBasic = motionBasic,
			motionIdle = motionIdle,
			motionBlink = motionBlink,
			motionNod = motionNod,
			motionShake = motionShake,
			motionSkeleton = motionSkeleton,
			generatePhysics = generatePhysics,
			physicsFrontHair = physicsFrontHair,
			physicsBackHair = physicsBackHair,
			physicsEyeJelly = physicsEyeJelly,
			hairSimulationFront = hairSimulationFront,
			hairSimulationBack = hairSimulationBack,
			exportCmo3 = exportCmo3,
			exportMoc3 = exportMoc3,
			exportJson = exportJson,
			runtimeTarget = runtimeTarget,
			exportHiddenParts = exportHiddenParts,
			exportHiddenDrawables = exportHiddenDrawables,
			exportGuideImageParts = exportGuideImageParts,
			exportIncludePhysics = exportIncludePhysics,
			exportIncludeUserData = exportIncludeUserData,
			exportIncludeDisplayInfo = exportIncludeDisplayInfo,
			exportPixelsPerUnit = exportPixelsPerUnit,
			layerOverrides = layerOverrides,
			// Canvas visibility must never rewrite the shared model; only the document's own entries apply.
			layerVisibility = documentLayerVisibility,
			deletedLayerIds = deletedLayerIds,
			parentOverrides = parentOverrides,
			drawOrderOverrides = drawOrderOverrides,
			rigEdits = rigEdits,
			generationSource = generationSource,
			meshSource = meshSource,
			atlasBudget = atlasBudget,
			textureOverrides = textureOverrides.filterValues { !it.isDefault },
			atlasArrangement = atlasArrangement,
		)
	}

	fun getEffectiveDrawOrder(drawableId: String, layerId: String?, defaultOrder: Float): Float {
		if (layerId != null) {
			drawOrderOverrides[layerId]?.let { return it }
		}
		return drawOrderOverrides[drawableId] ?: defaultOrder
	}

	fun getDefaultMeshSettings(layerId: String?): MeshSettings = buildConfig().defaultMeshSettings(
        analysis?.layers?.firstOrNull { it.source.id.raw == layerId }?.semantic?.tag)

	fun getEffectiveMeshSettings(layerId: String?): MeshSettings {
		val defaultSettings = getDefaultMeshSettings(layerId)
		if (layerId != null) {
			meshOverrides[layerId]?.let { return it }
		}
		return defaultSettings
	}

	/** Old v1 overrides the hierarchy still shows; a later journal reparent of the same object supersedes its entry. */
	val hierarchyParentOverrides: Map<String, String?> by lazy {
		io.github.psd2live.application.WorkspaceHierarchyEdits.displayParentOverrides(
			parentOverrides, rigEdits.structureEdits, rigEdits.authoringJournal)
	}

	fun isLayerVisible(layerId: String, defaultVisible: Boolean = true): Boolean {
		layerVisibility[layerId]?.let { return it }
		val parentId = when {
			layerId.endsWith(":l") || layerId.endsWith(":r") -> layerId.dropLast(2)
			else -> null
		}
		return parentId?.let(layerVisibility::get) ?: defaultVisible
	}

	fun isDeformerVisible(deformerId: String, defaultVisible: Boolean = true): Boolean {
		return deformerVisibility[deformerId] ?: defaultVisible
	}

	val effectiveVisibleLayerIds: Set<String>
		get() {
			val model = previewModel ?: return emptySet()
			val hiddenDeformers = deformerVisibility.filterValues { !it }.keys
			val layerHiddenByDeformer: (String) -> Boolean = if (hiddenDeformers.isEmpty()) {
				{ false }
			} else {
				val drawableIdByLayerId = model.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
				val drawableById = model.rig.puppet.drawables.associateBy { it.id.raw }
				val deformerById = model.rig.puppet.deformers.associateBy { it.id.raw }
				fun isHidden(layerId: String): Boolean {
					val drawId = drawableIdByLayerId[layerId]
					var parent: String? = drawId?.let { hierarchyParentOverrides[it] ?: drawableById[it]?.parentDeformerId?.raw }
					val visited = mutableSetOf<String>()
					while (parent != null && visited.add(parent)) {
						if (parent in hiddenDeformers) {
							return true
						}
						parent = hierarchyParentOverrides[parent] ?: deformerById[parent]?.parent?.raw
					}
					return false
				}
				::isHidden
			}

			return model.analysis.layers
				.asSequence()
				.filter { isLayerVisible(it.source.id.raw, it.source.visible) && !layerHiddenByDeformer(it.source.id.raw) }
				.mapTo(linkedSetOf()) { it.source.id.raw }
		}

	val isBusy: Boolean
		get() = isAnalyzing || isGenerating || isUpscaling

	/** A workspace edit or a queued authored pose is still being committed; a new edit would start from a stale state. */
	val workspaceEditBusy: Boolean
		get() = canvasEditBusy || poseCommitBusy

	fun minRequiredAtlasSize(scale: Int = textureUpscale.scale): Int =
        minimumAtlasSize(previewModel?.analysis ?: analysis, scale, texturePadding)
}

/**
 * A finished export: [message] says what was written, [folder] is what Open folder opens, and [notes] (the
 * export's warnings or what the format could not keep) are listed under [notesTitle].
 */
data class ExportSuccess(
	val message: String,
	val folder: String,
	val notesTitle: String? = null,
	val notes: List<String> = emptyList(),
)
