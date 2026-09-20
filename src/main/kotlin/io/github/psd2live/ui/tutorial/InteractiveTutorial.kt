package io.github.psd2live.ui.tutorial

import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.WorkspaceTabKind

enum class BasicTutorialStep {
	OPEN_FILE_MENU,
	IMPORT_PSD,
	SWITCH_PREVIEW,
	ADJUST_LAYERS,
	INTRODUCE_LAYER_ROW,
	ADJUST_MODEL_SETTINGS,
	EXPORT,
	DONE,
	;

	val targetId: TutorialTargetId?
		get() = when (this) {
			OPEN_FILE_MENU -> TutorialTargetId.FILE_MENU
			IMPORT_PSD -> TutorialTargetId.FILE_IMPORT
			SWITCH_PREVIEW -> TutorialTargetId.PREVIEW_TAB
			ADJUST_LAYERS -> TutorialTargetId.LAYERS_DOCK
			INTRODUCE_LAYER_ROW -> TutorialTargetId.LAYER_ROW
			ADJUST_MODEL_SETTINGS -> TutorialTargetId.MODEL_SETTINGS
			EXPORT -> TutorialTargetId.FILE_EXPORT
			DONE -> null
		}

	/** Render the spotlight and coach inside the File menu popup. */
	val coachBesideMenu: Boolean
		get() = this == IMPORT_PSD || this == EXPORT

	val titleKey: String
		get() = when (this) {
			OPEN_FILE_MENU -> "tutorial.basic.step.openFile.title"
			IMPORT_PSD -> "tutorial.basic.step.import.title"
			SWITCH_PREVIEW -> "tutorial.basic.step.preview.title"
			ADJUST_LAYERS -> "tutorial.basic.step.layers.title"
			INTRODUCE_LAYER_ROW -> "tutorial.basic.step.layerRow.title"
			ADJUST_MODEL_SETTINGS -> "tutorial.basic.step.settings.title"
			EXPORT -> "tutorial.basic.step.export.title"
			DONE -> "tutorial.basic.step.done.title"
		}

	val bodyKey: String
		get() = when (this) {
			OPEN_FILE_MENU -> "tutorial.basic.step.openFile.body"
			IMPORT_PSD -> "tutorial.basic.step.import.body"
			SWITCH_PREVIEW -> "tutorial.basic.step.preview.body"
			ADJUST_LAYERS -> "tutorial.basic.step.layers.body"
			INTRODUCE_LAYER_ROW -> "tutorial.basic.step.layerRow.body"
			ADJUST_MODEL_SETTINGS -> "tutorial.basic.step.settings.body"
			EXPORT -> "tutorial.basic.step.export.body"
			DONE -> "tutorial.basic.step.done.body"
		}

	val forcesFileMenu: Boolean
		get() = this == IMPORT_PSD || this == EXPORT

	val allowsNext: Boolean
		get() = when (this) {
			OPEN_FILE_MENU, ADJUST_LAYERS, INTRODUCE_LAYER_ROW, ADJUST_MODEL_SETTINGS -> true
			IMPORT_PSD, SWITCH_PREVIEW, EXPORT, DONE -> false
		}

	val allowsPrevious: Boolean
		get() = this != OPEN_FILE_MENU && this != DONE

	/** Prefer placing the main coach to the side of the hole (end / start). */
	val preferSideBubble: Boolean
		get() = when (this) {
			SWITCH_PREVIEW, ADJUST_LAYERS, INTRODUCE_LAYER_ROW, ADJUST_MODEL_SETTINGS -> true
			else -> false
		}

	fun next(): BasicTutorialStep? = when (this) {
		OPEN_FILE_MENU -> IMPORT_PSD
		IMPORT_PSD -> SWITCH_PREVIEW
		SWITCH_PREVIEW -> ADJUST_LAYERS
		ADJUST_LAYERS -> INTRODUCE_LAYER_ROW
		INTRODUCE_LAYER_ROW -> ADJUST_MODEL_SETTINGS
		ADJUST_MODEL_SETTINGS -> EXPORT
		EXPORT -> DONE
		DONE -> null
	}

	fun previous(): BasicTutorialStep? = when (this) {
		IMPORT_PSD -> OPEN_FILE_MENU
		SWITCH_PREVIEW -> IMPORT_PSD
		ADJUST_LAYERS -> SWITCH_PREVIEW
		INTRODUCE_LAYER_ROW -> ADJUST_LAYERS
		ADJUST_MODEL_SETTINGS -> INTRODUCE_LAYER_ROW
		EXPORT -> ADJUST_MODEL_SETTINGS
		DONE -> EXPORT
		OPEN_FILE_MENU -> null
	}

	companion object {
		val actionableSteps: List<BasicTutorialStep> =
			entries.filter { it != DONE }
	}
}

data class InteractiveTutorialState(
	val active: Boolean = false,
	val step: BasicTutorialStep = BasicTutorialStep.OPEN_FILE_MENU,
	val titleBarMenuOpen: String? = null,
	/** Going back is for reviewing a step; an already completed action must not undo it. */
	val reviewing: Boolean = false,
)

fun InteractiveTutorialState.start(): InteractiveTutorialState = InteractiveTutorialState(active = true)

fun InteractiveTutorialState.stop(): InteractiveTutorialState = InteractiveTutorialState()

fun InteractiveTutorialState.advance(): InteractiveTutorialState {
	val next = step.next() ?: return stop()
	return copy(step = next, titleBarMenuOpen = null, reviewing = false)
}

fun InteractiveTutorialState.retreat(): InteractiveTutorialState {
	val prev = step.previous() ?: return this
	return copy(step = prev, titleBarMenuOpen = null, reviewing = true)
}

fun BasicTutorialStep.isComplete(
	appState: PSD2LiveState,
	tutorial: InteractiveTutorialState,
): Boolean = !tutorial.reviewing && when (this) {
	BasicTutorialStep.OPEN_FILE_MENU -> tutorial.titleBarMenuOpen == "file"
	BasicTutorialStep.IMPORT_PSD -> appState.previewModel != null
	BasicTutorialStep.SWITCH_PREVIEW -> appState.activeTabKind == WorkspaceTabKind.PREVIEW
	BasicTutorialStep.ADJUST_LAYERS -> false
	BasicTutorialStep.INTRODUCE_LAYER_ROW -> false
	BasicTutorialStep.ADJUST_MODEL_SETTINGS -> false
	BasicTutorialStep.EXPORT -> appState.showExportDialog
	BasicTutorialStep.DONE -> false
}
