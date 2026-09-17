package io.github.psd2live.ui.components

import androidx.compose.runtime.Composable
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.TabViewOptions

/**
 * The single source of truth for the per-tab canvas and annotation toggles, rendered by the tab
 * strip's "view options" dropdown.
 *
 * Deform path entries are `showPathGuides`-gated because path guides are an Edit-tab overlay:
 * offering the toggle on a Preview tab would advertise a switch that cannot change anything.
 */
@Composable
fun ViewOptionsMenuItems(
	options: TabViewOptions,
	onOptionsChange: (TabViewOptions) -> Unit,
	onDismiss: () -> Unit,
	showHeaders: Boolean = true,
	showPathGuides: Boolean = true,
	onHover: (() -> Unit)? = null,
) {
	fun apply(updated: TabViewOptions) {
		onOptionsChange(updated.normalized())
		onDismiss()
	}

	if (showHeaders) AppMenuHeader(tr("menu.view.category.canvas"))

	AppMenuItem(
		text = tr("canvas.visibility.texture"),
		isChecked = options.showTexture,
		onHover = onHover,
		onClick = { apply(options.copy(showTexture = !options.showTexture)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.mesh"),
		isChecked = options.showMesh,
		onHover = onHover,
		onClick = { apply(options.copy(showMesh = !options.showMesh)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.warp"),
		isChecked = options.showWarp,
		onHover = onHover,
		onClick = { apply(options.copy(showWarp = !options.showWarp)) },
	)
	if (showPathGuides) {
		AppMenuItem(
			text = tr("canvas.visibility.paths"),
			isChecked = options.showDeformPaths,
			onHover = onHover,
			onClick = { apply(options.copy(showDeformPaths = !options.showDeformPaths)) },
		)
	}

	AppMenuSeparator()

	if (showHeaders) AppMenuHeader(tr("menu.view.category.overlays"))

	AppMenuItem(
		text = tr("canvas.information.contextualWarp"),
		isChecked = options.contextualWarp,
		onHover = onHover,
		onClick = { apply(options.copy(contextualWarp = !options.contextualWarp)) },
	)
	AppMenuItem(
		text = tr("canvas.information.selectedOnly"),
		isChecked = options.filterSelectedOnly,
		onHover = onHover,
		onClick = { apply(options.copy(filterSelectedOnly = !options.filterSelectedOnly)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.dimUnselected"),
		isChecked = options.dimUnselected,
		onHover = onHover,
		onClick = { apply(options.copy(dimUnselected = !options.dimUnselected)) },
	)

	AppMenuSeparator()

	AppMenuItem(
		text = tr("canvas.information.selectionBounds"),
		isChecked = options.showSelectionBounds,
		onHover = onHover,
		onClick = { apply(options.copy(showSelectionBounds = !options.showSelectionBounds)) },
	)
	AppMenuItem(
		text = tr("canvas.information.names"),
		isChecked = options.warpShowNames,
		onHover = onHover,
		onClick = { apply(options.copy(warpShowNames = !options.warpShowNames)) },
	)
	AppMenuItem(
		text = tr("canvas.information.indices"),
		isChecked = options.warpShowIndices,
		onHover = onHover,
		onClick = { apply(options.copy(warpShowIndices = !options.warpShowIndices)) },
	)
	if (showPathGuides) {
		AppMenuItem(
			text = tr("canvas.information.pathWidth"),
			isChecked = options.pathShowWidth,
			onHover = onHover,
			onClick = { apply(options.copy(pathShowWidth = !options.pathShowWidth)) },
		)
		AppMenuItem(
			text = tr("canvas.information.pathHardness"),
			isChecked = options.pathShowHardness,
			onHover = onHover,
			onClick = { apply(options.copy(pathShowHardness = !options.pathShowHardness)) },
		)
	}
}
