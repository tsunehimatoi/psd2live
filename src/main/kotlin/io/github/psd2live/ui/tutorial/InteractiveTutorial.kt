package io.github.psd2live.ui.tutorial

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.WorkspaceTabKind

enum class TutorialId {
	BASIC,
	WORKSPACE,
	HIERARCHY,
	VARIANTS,
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
	;

	val i18nKey: String
		get() = when (this) {
			BASIC -> "basic"
			WORKSPACE -> "workspace"
			HIERARCHY -> "hierarchy"
			VARIANTS -> "variants"
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
		}

	val titleKey: String get() = "tutorial.$i18nKey.title"
	val descKey: String get() = "tutorial.$i18nKey.desc"

	val nextId: TutorialId?
		get() = when (this) {
			BASIC -> WORKSPACE
			WORKSPACE -> HIERARCHY
			HIERARCHY -> VARIANTS
			VARIANTS -> PARAMETERS
			PARAMETERS -> SELECT_MODE
			SELECT_MODE -> CREATE_DEFORMER
			CREATE_DEFORMER -> DEFORM_MODE
			DEFORM_MODE -> EDIT_MODE
			EDIT_MODE -> PAINT_MODE
			PAINT_MODE -> INSPECTOR
			INSPECTOR -> TOOL_DETAILS
			TOOL_DETAILS -> PROJECT_HISTORY
			PROJECT_HISTORY -> TEXTURE_UPSCALE
			TEXTURE_UPSCALE -> null
		}

	companion object {
		val progressiveOrder: List<TutorialId> = entries
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
	val setHierarchyMode: EditHierarchyMode? = null,
	/** Highlight the hierarchy and ask the user to pick a mesh/deformer before continuing. */
	val requireSelection: Boolean = false,
	/** Same as [requireSelection], but the pick must be a layer. */
	val requireLayerSelection: Boolean = false,
	/** Show the tinted “现在这样做” action block (omit for informational steps). */
	val showAction: Boolean = false,
	val isDone: Boolean = false,
) {
	fun titleKey(tutorialId: TutorialId): String = "tutorial.${tutorialId.i18nKey}.step.$key.title"
	fun bodyKey(tutorialId: TutorialId): String = "tutorial.${tutorialId.i18nKey}.step.$key.body"
	fun actionKey(tutorialId: TutorialId): String = "tutorial.${tutorialId.i18nKey}.step.$key.action"

	val allowsNext: Boolean
		get() = when (completion) {
			TutorialCompletion.MANUAL -> !isDone
			TutorialCompletion.OPEN_FILE_MENU -> true
			TutorialCompletion.HAS_PREVIEW_MODEL,
			TutorialCompletion.PREVIEW_TAB,
			TutorialCompletion.EDIT_TAB,
			TutorialCompletion.HISTORY_TAB,
			TutorialCompletion.EXPORT_DIALOG -> false
		}

	/** @deprecated Prefer [forcesMenu]; kept so call sites migrate cleanly. */
	val forcesFileMenu: Boolean get() = forcesMenu == "file"
}

data class TutorialDefinition(
	val id: TutorialId,
	val steps: List<TutorialStep>,
) {
	val actionableSteps: List<TutorialStep> get() = steps.filterNot { it.isDone }

	fun stepAt(index: Int): TutorialStep = steps[index.coerceIn(0, steps.lastIndex)]
}

val TutorialCatalog: Map<TutorialId, TutorialDefinition> = buildTutorialCatalog()

fun tutorialDefinition(id: TutorialId): TutorialDefinition =
	TutorialCatalog.getValue(id)

data class InteractiveTutorialState(
	val active: Boolean = false,
	val tutorialId: TutorialId = TutorialId.BASIC,
	val stepIndex: Int = 0,
	val titleBarMenuOpen: String? = null,
	/** Going back is for reviewing a step; an already completed action must not undo it. */
	val reviewing: Boolean = false,
) {
	val definition: TutorialDefinition get() = tutorialDefinition(tutorialId)
	val step: TutorialStep get() = definition.stepAt(stepIndex)
	val isFirstStep: Boolean get() = stepIndex <= 0
	val isDoneStep: Boolean get() = step.isDone
	val nextTutorialId: TutorialId? get() = tutorialId.nextId
}

fun InteractiveTutorialState.start(id: TutorialId = TutorialId.BASIC): InteractiveTutorialState =
	InteractiveTutorialState(active = true, tutorialId = id, stepIndex = 0)

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

fun InteractiveTutorialState.continueNextTutorial(): InteractiveTutorialState {
	val next = tutorialId.nextId ?: return stop()
	return InteractiveTutorialState(active = true, tutorialId = next, stepIndex = 0)
}

fun TutorialStep.isComplete(
	appState: PSD2LiveState,
	tutorial: InteractiveTutorialState,
): Boolean = !tutorial.reviewing && !isDone && when (completion) {
	TutorialCompletion.MANUAL -> false
	TutorialCompletion.OPEN_FILE_MENU -> tutorial.titleBarMenuOpen == "file"
	TutorialCompletion.HAS_PREVIEW_MODEL -> appState.previewModel != null
	TutorialCompletion.PREVIEW_TAB -> appState.activeTabKind == WorkspaceTabKind.PREVIEW
	TutorialCompletion.EDIT_TAB -> appState.activeTabKind == WorkspaceTabKind.EDIT
	TutorialCompletion.HISTORY_TAB -> appState.activeTabKind == WorkspaceTabKind.HISTORY
	TutorialCompletion.EXPORT_DIALOG -> appState.showExportDialog
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
			step("settings", TutorialTargetId.MODEL_SETTINGS, selectDock = "settings", expandModelSettings = true),
			step("export", TutorialTargetId.FILE_EXPORT, TutorialCompletion.EXPORT_DIALOG, coachBesideMenu = true, forcesMenu = "file", preferSideBubble = false, showAction = true),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.WORKSPACE to TutorialDefinition(
		TutorialId.WORKSPACE,
		listOf(
			step("tabs", TutorialTargetId.EDIT_TAB, ensureEditTab = true),
			step("hierarchyToggle", TutorialTargetId.LAYOUT_HIERARCHY_TOGGLE, showAction = true),
			step("docks", TutorialTargetId.LAYERS_DOCK, selectDock = "layers"),
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
			step("keyframes", TutorialTargetId.INSPECTOR_DOCK, selectDock = "inspector"),
			step("drawOrder", TutorialTargetId.DRAW_ORDER_RULER, ensureHierarchyVisible = true, selectDock = "hierarchy"),
			step("done", isDone = true, preferSideBubble = false),
		),
	),
	TutorialId.TOOL_DETAILS to TutorialDefinition(
		TutorialId.TOOL_DETAILS,
		listOf(
			step("open", TutorialTargetId.TOOLS_DOCK, selectDock = "tools", ensureEditTab = true, showAction = true),
			step("brushes", TutorialTargetId.TOOLS_DOCK, selectDock = "tools", ensureEditTab = true, setHierarchyMode = EditHierarchyMode.DEFORM),
			step("split", TutorialTargetId.CANVAS_TOOLBAR, ensureEditTab = true),
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
			step("done", isDone = true, preferSideBubble = false),
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
)
