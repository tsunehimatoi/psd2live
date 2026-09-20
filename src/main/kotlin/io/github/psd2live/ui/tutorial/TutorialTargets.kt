package io.github.psd2live.ui.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

enum class TutorialTargetId {
	FILE_MENU,
	FILE_IMPORT,
	FILE_EXPORT,
	FILE_MENU_BODY,
	PREVIEW_TAB,
	EDIT_TAB,
	VIEW_OPTIONS_MENU,
	LAYOUT_HIERARCHY_TOGGLE,
	LAYERS_DOCK,
	LAYER_ROW,
	MODEL_SETTINGS,
	PARAMETERS_DOCK,
	INSPECTOR_DOCK,
	TOOLS_DOCK,
	HIERARCHY_DOCK,
	HIERARCHY_TOOLBAR,
	HIERARCHY_TREE,
	DRAW_ORDER_RULER,
	MODE_BAR,
	CANVAS_TOOLBAR,
	VIEW_OPTIONS_BAR,
	STATUS_BAR,
}

class TutorialTargetRegistry {
	private val bounds = mutableStateMapOf<TutorialTargetId, Rect>()
	private val owners = mutableMapOf<TutorialTargetId, Any>()

	fun updateOwned(id: TutorialTargetId, owner: Any, rect: Rect) {
		owners[id] = owner
		update(id, rect)
	}

	fun clearOwned(id: TutorialTargetId, owner: Any) {
		if (owners[id] === owner) {
			owners.remove(id)
			clear(id)
		}
	}

	fun update(id: TutorialTargetId, rect: Rect?) {
		if (rect == null || rect.width <= 0f || rect.height <= 0f) {
			bounds.remove(id)
		} else {
			bounds[id] = rect
		}
	}

	fun clear(id: TutorialTargetId) {
		bounds.remove(id)
	}

	fun boundsOf(id: TutorialTargetId): Rect? = bounds[id]
}

val LocalTutorialTargets = compositionLocalOf<TutorialTargetRegistry?> { null }

@Composable
fun rememberTutorialTargetRegistry(): TutorialTargetRegistry = remember { TutorialTargetRegistry() }

fun Modifier.tutorialTarget(id: TutorialTargetId): Modifier = composed {
	val registry = LocalTutorialTargets.current
	if (registry == null) return@composed this
	val owner = remember(id, registry) { Any() }
	DisposableEffect(id, registry, owner) {
		onDispose { registry.clearOwned(id, owner) }
	}
	onGloballyPositioned { coordinates ->
		if (!coordinates.isAttached) {
			registry.clearOwned(id, owner)
			return@onGloballyPositioned
		}
		val rect = coordinates.boundsInWindow()
		registry.updateOwned(id, owner, rect)
	}
}
