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

import io.github.psd2live.agent.AgentHistorySnapshot

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
	"settings", "layers", "parameters", "tools", "mesh", "inspector", "animation", "physics",
)

/** Modules a fresh workspace layout already contains. History is added from the window menu. */
internal val DEFAULT_DOCK_MODULES = setOf(
	PRIMARY_CANVAS_ID, "hierarchy", "log",
) + INSPECTOR_DOCK_MODULES

fun isCanvasModule(id: String): Boolean = id == PRIMARY_CANVAS_ID || id.startsWith("canvas:")

/**
 * View options a fresh canvas of this mode starts with (and what "reset view options" restores).
 * An edit canvas opens with the warp guides enabled (rotation guides stay off until toggled); they fade while nothing is selected.
 */
fun CanvasMode.defaultViewOptions(): TabViewOptions = when (this) {
	CanvasMode.EDIT -> TabViewOptions.Default.copy(showWarp = true)
	CanvasMode.PREVIEW -> TabViewOptions.Default
}

/**
 * Display seeds for a hierarchy mode's first visit. Each mode then keeps its own toggles (see
 * [CanvasModeSession.withHierarchyView]), so a preset never overrides what the user set there.
 */
fun hierarchyModeViewPreset(mode: EditHierarchyMode, current: TabViewOptions): TabViewOptions = when (mode) {
	// Object mode shows every mesh wire faintly; only the selection draws at full strength.
	EditHierarchyMode.SELECT -> current.copy(showMesh = true)
	EditHierarchyMode.DEFORM -> current.copy(
		showMesh = true,
		showWarp = true,
		showDeformPaths = true,
	)
	EditHierarchyMode.EDIT -> current.copy(showMesh = true, showDeformPaths = true)
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
) {
	/** Point indices are only painted together with the warp overlay they annotate. */
	fun normalized(): TabViewOptions = copy(showWarp = showWarp || warpShowIndices)

	companion object {
		val Default = TabViewOptions()
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
	/** Parks [view] under its mode and brings up [next]'s own toggles, seeded by its preset on first visit. */
	fun withHierarchyView(next: EditHierarchyMode): CanvasModeSession {
		val from = viewMode ?: return copy(viewMode = EditHierarchyMode.SELECT,
			view = hierarchyModeViewPreset(EditHierarchyMode.SELECT, view)).withHierarchyView(next)
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
 * A named arrangement of docked panels and canvases.
 *
 * The project session (model, edits, parameters) is shared. A workspace only remembers where
 * components sit, which of them are shown, and how each canvas is framed.
 */
@Immutable
data class EditorWorkspace(
	val id: String,
	val name: String = "",
	/** Serialized dock tree. Null means the built-in arrangement. */
	val layoutJson: String? = null,
	val hiddenModules: Set<String> = emptySet(),
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

/** Blank names stay localized; a name the user typed is kept as written. */
fun EditorWorkspace.displayName(): String = name.ifBlank { tr("workspace.default") }

/** Title-bar toggles projected from this workspace's hidden modules. */
internal fun EditorWorkspace.panelFlags(): Triple<Boolean, Boolean, Boolean> = Triple(
	"hierarchy" in hiddenModules,
	"log" !in hiddenModules,
	INSPECTOR_DOCK_MODULES.all { it in hiddenModules },
)

enum class LogSource {
	SYSTEM,
	MCP_SERVER,
	AGENT,

	/** A human at the editor: canvas authoring, history checkouts, the same acts an Agent host can ask for. */
	EDITOR,
}

enum class LogLevel {
	INFO,
	SUCCESS,
	WARNING,
	ERROR,
}

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

data class HistoryAnnotation(val title: String = "", val note: String = "", val hidden: Boolean = false)

@Immutable
data class PSD2LiveState(
    val canvasEditBusy: Boolean = false,
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
    val hierarchyCollapsed: Boolean = false,
    val hierarchySearch: String = "",
    val drawOrderRulerWidth: Float = 24f,
    val modelSettingsExpanded: Boolean = true,
    val workspaceSplitRatio: Float = 0.60f,
    /** When true, the right inspector / parameters sidebar is hidden. */
    val inspectorCollapsed: Boolean = false,
	/** One-shot request for DockWorkspaceView to select a dock module tab (e.g. "layers"). */
	val requestedDockModule: String? = null,
    val workspaces: List<EditorWorkspace> = listOf(defaultEditorWorkspace()),
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
	val texturePadding: Int = 2,
	val alphaThreshold: Int = 8,
	val headStrength: Float = 1.0f,
	val bodyStrength: Float = 1.0f,
	val meshOnly: Boolean = false,
	val generateDeformers: Boolean = true,
	val featureDisplacementEnabled: Boolean = false,
	val mouthOutlineEnabled: Boolean = true,
	val mouthShape: String = "smile",
    val mouthCurve: io.github.psd2live.core.MouthCurve = io.github.psd2live.core.MouthCurve.preset("smile"),
    val mouthColor: Int? = null,
    val mouthThickness: Float = 1.5f,
	val exportMotions: Boolean = true,
	val motionIdle: Boolean = true,
	val motionBlink: Boolean = true,
	val motionNod: Boolean = true,
	val motionShake: Boolean = true,
	val motionSkeleton: Boolean = true,
	val generatePhysics: Boolean = true,
	val physicsFrontHair: Boolean = true,
	val physicsBackHair: Boolean = true,
	val physicsEyeJelly: Boolean = true,
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
	val textureSubExpanded: Boolean = false,
	val strengthSubExpanded: Boolean = false,
	val advancedExpanded: Boolean = false,
	val isAnalyzing: Boolean = false,
	val isGenerating: Boolean = false,
	val isUpscaling: Boolean = false,
	val progress: Float = 0f,
	val isIndeterminateProgress: Boolean = false,
	val statusText: String = "",
	val logLines: List<String> = emptyList(),
	val logEntries: List<AppLogEntry> = emptyList(),
	val logPanelExpanded: Boolean = true,
	val logPanelHeight: Float = 190f,
	val historySnapshot: AgentHistorySnapshot? = null,
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
	val clickToSelectLayer: Boolean = AppSettings.clickToSelectLayer,
	val autoDetectMeshSplitsOnImport: Boolean = AppSettings.autoDetectMeshSplitsOnImport,
	val hoveredLayerId: String? = null,
	val hoveredDeformerId: String? = null,
	val layerVisibility: Map<String, Boolean> = emptyMap(),
	val deformerVisibility: Map<String, Boolean> = emptyMap(),
	val layerOverrides: Map<String, LayerClassificationOverride> = emptyMap(),
	val isolationSnapshot: Map<String, Boolean>? = null,
	val isolatedLayerId: String? = null,
	val lockedParameters: Set<ParameterId> = emptySet(),
	val parameterValues: Map<ParameterId, Float> = emptyMap(),
	val previewParameterValues: Map<ParameterId, Float> = emptyMap(),
	val parameterSearchQuery: String = "",
	val animationEnabled: Boolean = false,
	val mouseTrackingEnabled: Boolean = true,
	val sdkStatus: String? = null,
	val activeInspectorTab: InspectorTab = InspectorTab.LAYERS,
	val currentLanguage: AppLanguage = I18n.currentLanguage,
	val uiScale: Float = AppSettings.uiScale,
	val fontScale: Float = AppSettings.fontScale,
	/** Chrome palette. Like [uiScale], this is an application preference, not part of the project. */
	val darkTheme: Boolean = AppSettings.darkTheme,
	val showSettingsDialog: Boolean = false,
	/** App-level texture upscale prompt; must not be mounted inside a Column (scrim is fillMaxSize). */
	val showTextureUpscaleDialog: Boolean = false,
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
	val errorMessage: String? = null,
	val successExportMessage: String? = null,
) {
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
			val json = workspace.layoutJson ?: return false
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

	fun updateWorkspace(id: String, transform: (EditorWorkspace) -> EditorWorkspace): PSD2LiveState {
		val next = copy(workspaces = workspaces.map { if (it.id == id) transform(it) else it })
		if (id != next.activeWorkspaceId) return next
		val (hierarchy, log, inspector) = next.activeWorkspace.panelFlags()
		return next.copy(
			hierarchyCollapsed = hierarchy,
			logPanelExpanded = log,
			inspectorCollapsed = inspector,
		)
	}

	fun updateActiveWorkspace(transform: (EditorWorkspace) -> EditorWorkspace): PSD2LiveState =
		updateWorkspace(activeWorkspace.id, transform)

	fun updateCanvas(canvasId: String, transform: (CanvasWindowState) -> CanvasWindowState): PSD2LiveState =
		updateActiveWorkspace { workspace ->
			workspace.copy(canvases = workspace.canvases.map { if (it.id == canvasId) transform(it) else it })
		}

	fun buildConfig(): PipelineConfig {
		val hasAnyMotion = motionIdle || motionBlink || motionNod || motionShake || motionSkeleton
		val hasAnyPhysics = physicsFrontHair || physicsBackHair || physicsEyeJelly || rigEdits.physicsEdits.isNotEmpty()
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
			alphaThreshold = alphaThreshold,
			headTurnStrength = headStrength,
			bodyStrength = bodyStrength,
			meshOnly = meshOnly,
			generateDeformers = !meshOnly,
			featureDisplacementEnabled = featureDisplacementEnabled,
			mouthOutlineEnabled = mouthOutlineEnabled,
			mouthShape = mouthShape,
            mouthCurve = mouthCurve,
            mouthColor = mouthColor,
            mouthThickness = mouthThickness,
			exportMotions = !meshOnly && hasAnyMotion,
			motionIdle = motionIdle,
			motionBlink = motionBlink,
			motionNod = motionNod,
			motionShake = motionShake,
			motionSkeleton = motionSkeleton,
			generatePhysics = generatePhysics && !meshOnly && hasAnyPhysics,
			physicsFrontHair = physicsFrontHair,
			physicsBackHair = physicsBackHair,
			physicsEyeJelly = physicsEyeJelly,
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
			layerVisibility = emptyMap(), // Canvas visibility must never rewrite the shared model.
			deletedLayerIds = deletedLayerIds,
			parentOverrides = parentOverrides,
			drawOrderOverrides = drawOrderOverrides,
			rigEdits = rigEdits,
		)
	}

	fun getEffectiveDrawOrder(drawableId: String, layerId: String?, defaultOrder: Float): Float {
		if (layerId != null) {
			drawOrderOverrides[layerId]?.let { return it }
		}
		return drawOrderOverrides[drawableId] ?: defaultOrder
	}

	fun getDefaultMeshSettings(layerId: String?): MeshSettings {
		val layer = if (layerId != null) {
			analysis?.layers?.firstOrNull { it.source.id.raw == layerId }
		} else null
		val semanticDensity = when (layer?.semantic?.tag) {
			SemanticTag.FACE, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR -> 0.65f
			SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYEBROW,
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
			SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE -> 0.45f
			else -> 1f
		}
		// Face keeps the pre-edgeMode dual envelope; other parts follow the global mode.
		val edgeMode = if (layer?.semantic?.tag == SemanticTag.FACE) MeshEdgeMode.DOUBLE else meshEdgeMode
		return MeshSettings(
			outerMargin = meshOuterMargin,
			edgeMode = edgeMode,
			edgeWidth = meshEdgeWidth,
			maxEdgeDistance = kotlin.math.max(12f, meshMaxEdgeDistance * semanticDensity),
			interiorDensity = kotlin.math.max(12f, meshInteriorDensity * semanticDensity),
			fillAlgorithm = meshFillAlgorithm,
			suppressBoundaryDiagonals = meshSuppressBoundaryDiagonals,
			fillParameters = meshFillParameters,
		)
	}

	fun getEffectiveMeshSettings(layerId: String?): MeshSettings {
		val defaultSettings = getDefaultMeshSettings(layerId)
		if (layerId != null) {
			meshOverrides[layerId]?.let { return it }
		}
		return defaultSettings
	}

	fun getLayerDrawOrder(layerId: String): Float? {
		drawOrderOverrides[layerId]?.let { return it }
		val model = previewModel ?: return null
		val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
		val drawable = model.rig.puppet.drawables.firstOrNull { it.id.raw == drawableId }
		return drawable?.drawOrder
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
					var parent: String? = drawId?.let { parentOverrides[it] ?: drawableById[it]?.parentDeformerId?.raw }
					val visited = mutableSetOf<String>()
					while (parent != null && visited.add(parent)) {
						if (parent in hiddenDeformers) {
							return true
						}
						parent = parentOverrides[parent] ?: deformerById[parent]?.parent?.raw
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

	fun minRequiredAtlasSize(scale: Int = textureUpscale.scale): Int {
		val effectiveLayers = previewModel?.analysis?.layers ?: analysis?.layers ?: return 1024
		val valid = effectiveLayers.filter { it.source.raster.width > 0 && it.source.raster.height > 0 && it.opaquePixels > 0 }
		if (valid.isEmpty()) return 1024
		val largest = valid.maxOfOrNull {
			maxOf(it.source.raster.width * scale, it.source.raster.height * scale) + texturePadding * 2
		} ?: 1024
		var size = 256
		while (size < largest && size < 16384) {
			size = size shl 1
		}
		return size.coerceIn(256, 16384)
	}
}
