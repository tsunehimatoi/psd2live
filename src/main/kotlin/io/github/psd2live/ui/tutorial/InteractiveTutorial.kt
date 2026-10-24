package io.github.psd2live.ui.tutorial

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.CanvasMode

enum class TutorialId {
	BASIC,
	LIVE2D_BRIDGE,
	WORKSPACE,
	HIERARCHY,
	VARIANTS,
	LAYOUT,
	PARAMETERS,
	SELECT_MODE,
	CREATE_DEFORMER,
	DEFORM_MODE,
	EDIT_MODE,
	PAINT_MODE,
	INSPECTOR,
	TOOL_DETAILS,
	PROJECT_HISTORY,
	TEXTURE_UPSCALE,
	SKELETON,
	ANIMATION,
	PHYSICS,
	SIMULATION,
	;

	val i18nKey: String
		get() = when (this) {
			BASIC -> "basic"
			LIVE2D_BRIDGE -> "live2dBridge"
			WORKSPACE -> "workspace"
			HIERARCHY -> "hierarchy"
			VARIANTS -> "variants"
			LAYOUT -> "layout"
			PARAMETERS -> "parameters"
			SELECT_MODE -> "select"
			CREATE_DEFORMER -> "create"
			DEFORM_MODE -> "deform"
			EDIT_MODE -> "edit"
			PAINT_MODE -> "paint"
			INSPECTOR -> "inspector"
			TOOL_DETAILS -> "tools"
			PROJECT_HISTORY -> "project"
			TEXTURE_UPSCALE -> "upscale"
			SKELETON -> "skeleton"
			ANIMATION -> "animation"
			PHYSICS -> "physics"
			SIMULATION -> "simulation"
		}

	val titleKey: String get() = "tutorial.$i18nKey.title"
	val descKey: String get() = "tutorial.$i18nKey.desc"

	/** Every chapter but the basic one, which teaches the import itself, works on an open model. */
	val requiresModel: Boolean get() = this != BASIC

	companion object {
		/** Computed on access to avoid a TutorialId <-> TutorialPath enum initialization cycle. */
		val progressiveOrder: List<TutorialId>
			get() = TutorialPath.entries.flatMap { it.chapters }.distinct()
	}
}

/** The two paths intentionally teach different mental models instead of only changing the first page. */
enum class TutorialPath(val i18nKey: String, val chapters: List<TutorialId>) {
	BEGINNER(
		"beginner",
		listOf(
			TutorialId.BASIC, TutorialId.WORKSPACE, TutorialId.HIERARCHY, TutorialId.VARIANTS, TutorialId.LAYOUT,
			TutorialId.PARAMETERS, TutorialId.SELECT_MODE, TutorialId.CREATE_DEFORMER,
			TutorialId.DEFORM_MODE, TutorialId.EDIT_MODE, TutorialId.PAINT_MODE,
			TutorialId.INSPECTOR, TutorialId.TOOL_DETAILS, TutorialId.SKELETON, TutorialId.ANIMATION,
			TutorialId.PHYSICS, TutorialId.SIMULATION, TutorialId.PROJECT_HISTORY, TutorialId.TEXTURE_UPSCALE,
		),
	),
	EXPERIENCED(
		"experienced",
		listOf(
			TutorialId.LIVE2D_BRIDGE, TutorialId.WORKSPACE, TutorialId.HIERARCHY, TutorialId.LAYOUT,
			TutorialId.PARAMETERS, TutorialId.DEFORM_MODE, TutorialId.EDIT_MODE,
			TutorialId.INSPECTOR, TutorialId.SKELETON, TutorialId.ANIMATION,
			TutorialId.PHYSICS, TutorialId.SIMULATION, TutorialId.PROJECT_HISTORY, TutorialId.TEXTURE_UPSCALE,
		),
	),
	;

	val titleKey: String get() = "tutorial.path.$i18nKey.title"
	val descKey: String get() = "tutorial.path.$i18nKey.desc"
	fun nextAfter(id: TutorialId): TutorialId? = chapters.getOrNull(chapters.indexOf(id) + 1)

	companion object {
		fun defaultFor(id: TutorialId): TutorialPath =
			if (id == TutorialId.LIVE2D_BRIDGE) EXPERIENCED else BEGINNER
	}
}

enum class TutorialCompletion {
	MANUAL,
	OPEN_FILE_MENU,
	HAS_PREVIEW_MODEL,
	PREVIEW_TAB,
	EDIT_TAB,
	HISTORY_TAB,
	EXPORT_DIALOG,
	/** At least one simulation body exists, made by a model preset or by hand. */
	HAS_SIMULATION,
}

data class TutorialStep(
	val key: String,
	val targetId: TutorialTargetId? = null,
	val completion: TutorialCompletion = TutorialCompletion.MANUAL,
	val coachBesideMenu: Boolean = false,
	/** Force-open a title-bar menu: `"file"` or `"tools"`. */
	val forcesMenu: String? = null,
	val preferSideBubble: Boolean = true,
	val selectDock: String? = null,
	val ensureEditTab: Boolean = false,
	val ensureHistoryTab: Boolean = false,
	val ensureHierarchyVisible: Boolean = false,
	val expandModelSettings: Boolean = false,
	/** Unfold the Physics & Simulation group of the model presets panel. */
	val expandSimulationPresets: Boolean = false,
	val setHierarchyMode: EditHierarchyMode? = null,
	/** Highlight the hierarchy and ask the user to pick a mesh/deformer before continuing. */
	val requireSelection: Boolean = false,
	/** Same as [requireSelection], but the pick must be a layer. */
	val requireLayerSelection: Boolean = false,
	/** Show the tinted “现在这样做” action block (omit for informational steps). */
	val showAction: Boolean = false,
	val isDone: Boolean = false,
	/** Without it the step can only be completed or the tutorial left. */
	val skippable: Boolean = true,
	/** Text scope shared across chapters; the chapter's own scope otherwise. */
	val i18nScope: String? = null,
	/** Switch to this workspace preset (opening one when none of it exists) before the step shows. */
	val openWorkspace: io.github.psd2live.ui.state.WorkspacePreset? = null,
) {
	fun titleKey(tutorialId: TutorialId): String = "tutorial.${i18nScope ?: tutorialId.i18nKey}.step.$key.title"
	fun bodyKey(tutorialId: TutorialId): String = "tutorial.${i18nScope ?: tutorialId.i18nKey}.step.$key.body"
	fun actionKey(tutorialId: TutorialId): String = "tutorial.${i18nScope ?: tutorialId.i18nKey}.step.$key.action"

	val allowsNext: Boolean
		get() = when (completion) {
			TutorialCompletion.MANUAL -> !isDone
			TutorialCompletion.OPEN_FILE_MENU -> true
			TutorialCompletion.HAS_PREVIEW_MODEL,
			TutorialCompletion.PREVIEW_TAB,
			TutorialCompletion.EDIT_TAB,
			TutorialCompletion.HISTORY_TAB,
			TutorialCompletion.EXPORT_DIALOG,
			TutorialCompletion.HAS_SIMULATION -> false
		}
}

data class TutorialDefinition(
	val id: TutorialId,
	val steps: List<TutorialStep>,
) {
	val actionableSteps: List<TutorialStep> get() = steps.filterNot { it.isDone }

	fun stepAt(index: Int): TutorialStep = steps[index.coerceIn(0, steps.lastIndex)]
}

val TutorialCatalog: Map<TutorialId, TutorialDefinition> = buildTutorialCatalog()

/** Put in front of a chapter that needs a model when it starts without one: import a PSD or open a project. */
val OpenModelStep = TutorialStep(
	key = "openModel",
	targetId = TutorialTargetId.FILE_MENU_BODY,
	completion = TutorialCompletion.HAS_PREVIEW_MODEL,
	coachBesideMenu = true,
	forcesMenu = "file",
	preferSideBubble = false,
	showAction = true,
	skippable = false,
	i18nScope = "common",
)

fun tutorialDefinition(id: TutorialId): TutorialDefinition =
	TutorialCatalog.getValue(id)

data class InteractiveTutorialState(
	val active: Boolean = false,
	val path: TutorialPath = TutorialPath.BEGINNER,
	val tutorialId: TutorialId = TutorialId.BASIC,
	val stepIndex: Int = 0,
	val titleBarMenuOpen: String? = null,
	/** Going back is for reviewing a step; an already completed action must not undo it. */
	val reviewing: Boolean = false,
	/** Decided when the chapter starts, so loading the model does not shift the steps under the user. */
	val openModelFirst: Boolean = false,
) {
	val definition: TutorialDefinition
		get() = tutorialDefinition(tutorialId).let { if (openModelFirst) it.copy(steps = listOf(OpenModelStep) + it.steps) else it }
	val step: TutorialStep get() = definition.stepAt(stepIndex)
	val isFirstStep: Boolean get() = stepIndex <= 0
	val isDoneStep: Boolean get() = step.isDone
	val nextTutorialId: TutorialId? get() = path.nextAfter(tutorialId)
}

fun InteractiveTutorialState.start(
	id: TutorialId = TutorialId.BASIC,
	path: TutorialPath = TutorialPath.defaultFor(id),
	hasModel: Boolean = true,
): InteractiveTutorialState =
	InteractiveTutorialState(active = true, path = path, tutorialId = id, stepIndex = 0, openModelFirst = id.requiresModel && !hasModel)

fun InteractiveTutorialState.stop(): InteractiveTutorialState = InteractiveTutorialState()

fun InteractiveTutorialState.advance(): InteractiveTutorialState {
	val nextIndex = stepIndex + 1
	if (nextIndex > definition.steps.lastIndex) return stop()
	return copy(stepIndex = nextIndex, titleBarMenuOpen = null, reviewing = false)
}

fun InteractiveTutorialState.retreat(): InteractiveTutorialState {
	if (stepIndex <= 0) return this
	return copy(stepIndex = stepIndex - 1, titleBarMenuOpen = null, reviewing = true)
}

fun InteractiveTutorialState.continueNextTutorial(hasModel: Boolean = true): InteractiveTutorialState {
	val next = nextTutorialId ?: return stop()
	return start(next, path, hasModel)
}

fun TutorialStep.isComplete(
	appState: PSD2LiveState,
	tutorial: InteractiveTutorialState,
): Boolean = !tutorial.reviewing && !isDone && when (completion) {
	TutorialCompletion.MANUAL -> false
	TutorialCompletion.OPEN_FILE_MENU -> tutorial.titleBarMenuOpen == "file"
	TutorialCompletion.HAS_PREVIEW_MODEL -> appState.previewModel != null
	TutorialCompletion.PREVIEW_TAB -> appState.activeCanvas.mode == CanvasMode.PREVIEW
	TutorialCompletion.EDIT_TAB -> appState.activeCanvas.mode == CanvasMode.EDIT
	TutorialCompletion.HISTORY_TAB -> appState.historyPanelShown
	TutorialCompletion.EXPORT_DIALOG -> appState.showExportDialog
	TutorialCompletion.HAS_SIMULATION -> appState.rigEdits.simEdits.isNotEmpty()
}

fun TutorialStep.prerequisiteMet(appState: PSD2LiveState): Boolean = when {
	requireLayerSelection -> appState.selectedLayerId != null
	requireSelection -> appState.selectedLayerId != null || appState.selectedDeformerId != null
	else -> true
}

/** Spotlight target when a selection prerequisite is unmet — always the hierarchy tree. */
fun TutorialStep.effectiveTargetId(appState: PSD2LiveState): TutorialTargetId? =
	if (!prerequisiteMet(appState) && (requireSelection || requireLayerSelection)) {
		TutorialTargetId.HIERARCHY_TREE
	} else {
		targetId
	}

private fun step(
	key: String,
	targetId: TutorialTargetId? = null,
	completion: TutorialCompletion = TutorialCompletion.MANUAL,
	coachBesideMenu: Boolean = false,
	forcesMenu: String? = null,
	preferSideBubble: Boolean = true,
	selectDock: String? = null,
	ensureEditTab: Boolean = false,
	ensureHistoryTab: Boolean = false,
	ensureHierarchyVisible: Boolean = false,
	expandModelSettings: Boolean = false,
	expandSimulationPresets: Boolean = false,
	setHierarchyMode: EditHierarchyMode? = null,
	requireSelection: Boolean = false,
	requireLayerSelection: Boolean = false,
	showAction: Boolean = false,
	isDone: Boolean = false,
) = TutorialStep(
	key = key,
	targetId = targetId,
	completion = completion,
	coachBesideMenu = coachBesideMenu,
	forcesMenu = forcesMenu,
	preferSideBubble = preferSideBubble,
	selectDock = selectDock,
	ensureEditTab = ensureEditTab,
	ensureHistoryTab = ensureHistoryTab,
	ensureHierarchyVisible = ensureHierarchyVisible,
	expandModelSettings = expandModelSettings,
	expandSimulationPresets = expandSimulationPresets,
	setHierarchyMode = setHierarchyMode,
	requireSelection = requireSelection,
	requireLayerSelection = requireLayerSelection,
	showAction = showAction,
	isDone = isDone,
)

private fun buildTutorialCatalog(): Map<TutorialId, TutorialDefinition> = mapOf(
	TutorialId.BASIC to TutorialDefinition(
		TutorialId.BASIC,
		listOf(
			step("openFile", TutorialTargetId.FILE_MENU, TutorialCompletion.OPEN_FILE_MENU, preferSideBubble = false, showAction = true),
			step("import", TutorialTargetId.FILE_IMPORT, TutorialCompletion.HAS_PREVIEW_MODEL, coachBesideMenu = true, forcesMenu = "file", preferSideBubble = false, showAction = true),
			step("preview", TutorialTargetId.PREVIEW_TAB, TutorialCompletion.PREVIEW_TAB, showAction = true),
			step("layers", TutorialTargetId.LAYERS_DOCK, selectDock = "layers"),
			step("layerRow", TutorialTargetId.LAYER_ROW, selectDock = "layers"),
			step("naming", TutorialTargetId.LAYERS_DOCK, selectDock = "layers", showAction = true),
			step("settings", TutorialTargetId.MODEL_SETTINGS, selectDock = "modifiers", expandModelSettings = true),
			step("export", TutorialTargetId.FILE_EXPORT, TutorialCompletion.EXPORT_DIALOG, coachBesideMenu = true, forcesMenu = "file", preferSideBubble = false, showAction = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.LIVE2D_BRIDGE to TutorialDefinition(
		TutorialId.LIVE2D_BRIDGE,
		listOf(
			step("workspace", TutorialTargetId.EDIT_TAB, ensureEditTab = true),
			step("structure", TutorialTargetId.HIERARCHY_DOCK, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("modes", TutorialTargetId.MODE_BAR, ensureEditTab = true),
			step("parameters", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters"),
			step("export", TutorialTargetId.FILE_EXPORT, coachBesideMenu = true, forcesMenu = "file", preferSideBubble = false),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.WORKSPACE to TutorialDefinition(
		TutorialId.WORKSPACE,
		listOf(
			step("presets", TutorialTargetId.WORKSPACE_STRIP, showAction = true),
			step("switch", TutorialTargetId.WORKSPACE_STRIP, showAction = true),
			step("texture", TutorialTargetId.WORKSPACE_STRIP),
			step("dockTabs", TutorialTargetId.DOCK_AREA, ensureEditTab = true, showAction = true),
			step("split", TutorialTargetId.DOCK_AREA, ensureEditTab = true),
			step("float", TutorialTargetId.DOCK_AREA, ensureEditTab = true),
			step("memory", TutorialTargetId.WORKSPACE_STRIP),
			step("sidebars", TutorialTargetId.LAYOUT_SIDEBAR_TOGGLES, showAction = true),
			step("viewMenu", TutorialTargetId.VIEW_OPTIONS_MENU, ensureEditTab = true, showAction = true),
			step("camera", TutorialTargetId.MODE_BAR, ensureEditTab = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.HIERARCHY to TutorialDefinition(
		TutorialId.HIERARCHY,
		listOf(
			step("openTree", TutorialTargetId.HIERARCHY_DOCK, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("toolbar", TutorialTargetId.HIERARCHY_TOOLBAR, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("treeBody", TutorialTargetId.HIERARCHY_TREE, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy", showAction = true),
			step("dragParent", TutorialTargetId.HIERARCHY_TREE, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("contextDeformer", TutorialTargetId.HIERARCHY_TREE, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("contextLayer", TutorialTargetId.HIERARCHY_TREE, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("importLayer", TutorialTargetId.HIERARCHY_TREE, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("placeSession", TutorialTargetId.MODE_BAR, ensureEditTab = true),
			step("drawOrder", TutorialTargetId.DRAW_ORDER_RULER, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("modeBar", TutorialTargetId.MODE_BAR, ensureEditTab = true, showAction = true),
			step("modeExtras", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.DEFORM),
			step("gesturePreview", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SELECT),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.VARIANTS to TutorialDefinition(
		TutorialId.VARIANTS,
		listOf(
			step("types", TutorialTargetId.LAYERS_DOCK, selectDock = "layers"),
			step("toggleWhy", TutorialTargetId.LAYER_ROW, selectDock = "layers"),
			step("toggleSetup", TutorialTargetId.LAYER_ROW, selectDock = "layers", showAction = true),
			step("switchWhy", TutorialTargetId.LAYERS_DOCK, selectDock = "layers"),
			step("switchSetup", TutorialTargetId.LAYERS_DOCK, selectDock = "layers", showAction = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.PARAMETERS to TutorialDefinition(
		TutorialId.PARAMETERS,
		listOf(
			step("findTab", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters", showAction = true),
			step("panel", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters"),
			step("drag", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters", showAction = true),
			step("keys", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters"),
			step("operate", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters"),
			step("link", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters"),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.SELECT_MODE to TutorialDefinition(
		TutorialId.SELECT_MODE,
		listOf(
			step("mode", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SELECT, showAction = true),
			step("tools", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SELECT, showAction = true),
			step("gestures", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SELECT),
			step("sync", TutorialTargetId.HIERARCHY_TREE, ensureEditTab = true, ensureHierarchyVisible = true, selectDock = "hierarchy", setHierarchyMode = EditHierarchyMode.SELECT, showAction = true),
			step("extras", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SELECT),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.CREATE_DEFORMER to TutorialDefinition(
		TutorialId.CREATE_DEFORMER,
		listOf(
			step(
				"selectFirst",
				TutorialTargetId.HIERARCHY_TREE,
				ensureEditTab = true,
				ensureHierarchyVisible = true,
				selectDock = "hierarchy",
				requireSelection = true,
				showAction = true,
			),
			step(
				"contextDeformer",
				TutorialTargetId.HIERARCHY_TREE,
				ensureEditTab = true,
				ensureHierarchyVisible = true,
				selectDock = "hierarchy",
				requireSelection = true,
				showAction = true,
			),
			step(
				"contextLayer",
				TutorialTargetId.HIERARCHY_TREE,
				ensureEditTab = true,
				ensureHierarchyVisible = true,
				selectDock = "hierarchy",
				requireSelection = true,
				showAction = true,
			),
			step(
				"placement",
				TutorialTargetId.PLACEMENT_PANEL,
				ensureEditTab = true,
				preferSideBubble = false,
			),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.DEFORM_MODE to TutorialDefinition(
		TutorialId.DEFORM_MODE,
		listOf(
			step(
				"enter",
				TutorialTargetId.MODE_BAR,
				ensureEditTab = true,
				ensureHierarchyVisible = true,
				selectDock = "hierarchy",
				setHierarchyMode = EditHierarchyMode.DEFORM,
				requireSelection = true,
				showAction = true,
			),
			step("levels", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.DEFORM, requireSelection = true, showAction = true),
			step("brushes", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.DEFORM, requireSelection = true, showAction = true),
			step("shortcuts", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.DEFORM, requireSelection = true),
			step("keys", TutorialTargetId.PARAMETERS_DOCK, selectDock = "parameters", setHierarchyMode = EditHierarchyMode.DEFORM, requireSelection = true),
			step("undo", TutorialTargetId.MODE_BAR, preferSideBubble = false),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.EDIT_MODE to TutorialDefinition(
		TutorialId.EDIT_MODE,
		listOf(
			step("enter", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.EDIT, requireSelection = true, ensureHierarchyVisible = true, selectDock = "hierarchy", showAction = true),
			step("topo", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.EDIT, requireSelection = true),
			step("delete", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.EDIT, requireSelection = true),
			step("viewAids", TutorialTargetId.VIEW_OPTIONS_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.EDIT),
			step("when", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.EDIT),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.PAINT_MODE to TutorialDefinition(
		TutorialId.PAINT_MODE,
		listOf(
			step("layerFirst", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.PAINT, requireLayerSelection = true, ensureHierarchyVisible = true, selectDock = "hierarchy", showAction = true),
			step("tools", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.PAINT, requireLayerSelection = true, showAction = true),
			step("shortcuts", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.PAINT, requireLayerSelection = true),
			step("session", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.PAINT, requireLayerSelection = true),
			step("return", TutorialTargetId.MODE_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SELECT, showAction = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.INSPECTOR to TutorialDefinition(
		TutorialId.INSPECTOR,
		listOf(
			step("open", TutorialTargetId.INSPECTOR_DOCK, selectDock = "inspector", showAction = true),
			step("artmesh", TutorialTargetId.INSPECTOR_DOCK, selectDock = "inspector"),
			step("deformers", TutorialTargetId.INSPECTOR_DOCK, selectDock = "inspector"),
			step("drawOrder", TutorialTargetId.DRAW_ORDER_RULER, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.TOOL_DETAILS to TutorialDefinition(
		TutorialId.TOOL_DETAILS,
		listOf(
			step("open", TutorialTargetId.TOOL_OPTIONS_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.DEFORM, requireSelection = true, showAction = true),
			step("brushes", TutorialTargetId.TOOL_OPTIONS_BAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.DEFORM, requireSelection = true),
			step("split", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true),
			step("advanced", TutorialTargetId.TOOLS_DOCK, selectDock = "tools", ensureEditTab = true),
			step("shortcuts", TutorialTargetId.TOOLS_DOCK, selectDock = "tools"),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.PROJECT_HISTORY to TutorialDefinition(
		TutorialId.PROJECT_HISTORY,
		listOf(
			step("save", TutorialTargetId.FILE_MENU, preferSideBubble = false),
			step("historyTab", TutorialTargetId.HISTORY_TAB, TutorialCompletion.HISTORY_TAB, ensureHistoryTab = true, showAction = true),
			step("restore", TutorialTargetId.HISTORY_TAB, ensureHistoryTab = true),
			step("branch", TutorialTargetId.HISTORY_TAB, ensureHistoryTab = true),
			step("regenerate", TutorialTargetId.TOOLS_MENU, preferSideBubble = false),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.LAYOUT to TutorialDefinition(
		TutorialId.LAYOUT,
		listOf(
			TutorialStep("workspace", TutorialTargetId.WORKSPACE_STRIP, openWorkspace = io.github.psd2live.ui.state.WorkspacePreset.LAYOUT),
			TutorialStep("import", TutorialTargetId.MATERIALS_DOCK, selectDock = "materials", showAction = true),
			TutorialStep("browse", TutorialTargetId.MATERIALS_DOCK, selectDock = "materials"),
			TutorialStep("place", TutorialTargetId.CANVAS_VIEWPORT, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SELECT, showAction = true),
			TutorialStep("tags", TutorialTargetId.LAYERS_DOCK, selectDock = "layers", showAction = true),
			TutorialStep("replace", TutorialTargetId.MATERIALS_DOCK, selectDock = "materials"),
			TutorialStep("generate", TutorialTargetId.CANVAS_VIEWPORT, ensureEditTab = true, showAction = true),
			TutorialStep("again", TutorialTargetId.CANVAS_VIEWPORT, ensureEditTab = true),
			TutorialStep("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.TEXTURE_UPSCALE to TutorialDefinition(
		TutorialId.TEXTURE_UPSCALE,
		listOf(
			step(
				"entry",
				TutorialTargetId.TOOLS_TEXTURE_UPSCALE,
				coachBesideMenu = true,
				forcesMenu = "tools",
				preferSideBubble = false,
				showAction = true,
			),
			step("scale", TutorialTargetId.MODE_BAR, preferSideBubble = false),
			step("check", TutorialTargetId.EDIT_TAB, ensureEditTab = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.SKELETON to TutorialDefinition(
		TutorialId.SKELETON,
		listOf(
			step("panel", TutorialTargetId.SKELETON_DOCK, selectDock = "skeleton", ensureEditTab = true, showAction = true),
			step("mode", TutorialTargetId.MODE_BAR, ensureEditTab = true, showAction = true),
			step("build", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true),
			step("bind", TutorialTargetId.CANVAS_VIEWPORT, selectDock = "skeleton", ensureEditTab = true),
			step("pose", TutorialTargetId.CANVAS_VIEWPORT, selectDock = "skeleton", ensureEditTab = true, showAction = true),
			step("weights", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true),
			step("snapshots", TutorialTargetId.TOOLS_DOCK, selectDock = "tools", ensureEditTab = true),
			step("sampling", TutorialTargetId.SKELETON_DOCK, selectDock = "skeleton", ensureEditTab = true, showAction = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.ANIMATION to TutorialDefinition(
		TutorialId.ANIMATION,
		listOf(
			step("motions", TutorialTargetId.ANIMATION_DOCK, selectDock = "animation", showAction = true),
			step("editor", TutorialTargetId.ANIMATION_EDITOR_DOCK, selectDock = "animationEditor"),
			step("autoKey", TutorialTargetId.ANIMATION_EDITOR_DOCK, selectDock = "animationEditor", showAction = true),
			step("keys", TutorialTargetId.ANIMATION_EDITOR_DOCK, selectDock = "animationEditor", showAction = true),
			step("preview", TutorialTargetId.CANVAS_VIEWPORT),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.PHYSICS to TutorialDefinition(
		TutorialId.PHYSICS,
		listOf(
			step("groups", TutorialTargetId.PHYSICS_DOCK, selectDock = "physics", showAction = true),
			step("pendulum", TutorialTargetId.CANVAS_VIEWPORT, selectDock = "physics"),
			step("io", TutorialTargetId.PHYSICS_DOCK, selectDock = "physics"),
			step("test", TutorialTargetId.CANVAS_VIEWPORT, selectDock = "physics", showAction = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.SIMULATION to TutorialDefinition(
		TutorialId.SIMULATION,
		listOf(
			step("presets", TutorialTargetId.MODEL_SETTINGS, selectDock = "modifiers", expandModelSettings = true, expandSimulationPresets = true),
			step("generate", TutorialTargetId.MODEL_SETTINGS, TutorialCompletion.HAS_SIMULATION, selectDock = "modifiers", expandModelSettings = true, expandSimulationPresets = true, showAction = true),
			step("hairModes", TutorialTargetId.MODEL_SETTINGS, selectDock = "modifiers", expandModelSettings = true, expandSimulationPresets = true),
			step("clothing", TutorialTargetId.MODEL_SETTINGS, selectDock = "modifiers", expandModelSettings = true, expandSimulationPresets = true),
			step("bodies", TutorialTargetId.SIMULATION_DOCK, selectDock = "modifiers", showAction = true),
			step("weights", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SIMULATE, requireLayerSelection = true, ensureHierarchyVisible = true, selectDock = "hierarchy", showAction = true),
			step("weightKinds", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true, setHierarchyMode = EditHierarchyMode.SIMULATE, requireLayerSelection = true),
			step("glue", TutorialTargetId.MODIFIERS_DOCK, selectDock = "modifiers", requireLayerSelection = true),
			step("material", TutorialTargetId.MODIFIERS_DOCK, selectDock = "modifiers", requireLayerSelection = true, showAction = true),
			step("materialValues", TutorialTargetId.MODIFIERS_DOCK, selectDock = "modifiers", requireLayerSelection = true),
			step("inputs", TutorialTargetId.MODIFIERS_DOCK, selectDock = "modifiers", requireLayerSelection = true),
			step("bakeSettings", TutorialTargetId.MODIFIERS_DOCK, selectDock = "modifiers", requireLayerSelection = true),
			step("bake", TutorialTargetId.MODIFIERS_DOCK, selectDock = "modifiers", requireLayerSelection = true, showAction = true),
			step("preview", TutorialTargetId.MODIFIERS_DOCK, selectDock = "modifiers", requireLayerSelection = true, showAction = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
)
