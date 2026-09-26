package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuHeader
import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.AppMenuSeparator
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.EditorWorkspace
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.displayName
import io.github.psd2live.ui.state.isCanvasModule
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/** Dock modules the window menu can show or hide, in menu order. */
internal val WINDOW_MODULES = listOf(
	"hierarchy",
	"skeleton",
	"history",
	"log",
	"settings",
	"layers",
	"parameters",
	"tools",
	"mesh",
	"inspector",
	"animation",
	"physics",
)

/**
 * Workspace strip: each chip is a named arrangement of panels and canvases.
 * The window menu toggles components and adds canvases.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorkspaceStrip(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	layoutModules: Set<String>,
	modifier: Modifier = Modifier,
) {
	var showWindowMenu by remember { mutableStateOf(false) }
	val workspace = state.activeWorkspace

	Row(
		modifier = modifier.fillMaxHeight(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				.horizontalScroll(rememberScrollState()),
			verticalAlignment = Alignment.CenterVertically,
		) {
			state.workspaces.forEach { item ->
				WorkspaceChip(
					workspace = item,
					isActive = item.id == state.activeWorkspaceId,
					canClose = state.workspaces.size > 1,
					onSelect = { viewModel.setActiveWorkspace(item.id) },
					onClose = { viewModel.closeWorkspace(item.id) },
					onRename = { viewModel.renameWorkspace(item.id, it) },
					onDuplicate = { viewModel.duplicateWorkspace(item.id) },
				)
			}
			TabStripButton(label = "+") { viewModel.addWorkspace() }
		}

		Box {
			TabStripButton(label = "${tr("workspace.windows")} \u25BE") { showWindowMenu = true }
			TabStripDropdown(expanded = showWindowMenu, onDismissRequest = { showWindowMenu = false }) {
				AppMenuHeader(tr("workspace.windows"))
				WINDOW_MODULES.forEach { module ->
					val shown = module in layoutModules && module !in workspace.hiddenModules
					AppMenuItem(
						text = moduleTitle(module),
						isChecked = shown,
						onClick = { viewModel.setModuleVisible(module, !shown) },
					)
				}
				AppMenuSeparator()
				AppMenuHeader(tr("dock.canvas"))
				workspace.canvases.forEach { canvas ->
					val shown = canvas.id in layoutModules && canvas.id !in workspace.hiddenModules
					AppMenuItem(
						text = viewModel.canvasTitle(canvas, workspace),
						isChecked = shown,
						onClick = { viewModel.setModuleVisible(canvas.id, !shown) },
					)
				}
				AppMenuItem(text = tr("window.addEditCanvas"), onClick = {
					showWindowMenu = false
					viewModel.addCanvas(CanvasMode.EDIT)
				})
				AppMenuItem(text = tr("window.addPreviewCanvas"), onClick = {
					showWindowMenu = false
					viewModel.addCanvas(CanvasMode.PREVIEW)
				})
			}
		}
		Spacer(Modifier.width(2.dp))
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WorkspaceChip(
	workspace: EditorWorkspace,
	isActive: Boolean,
	canClose: Boolean,
	onSelect: () -> Unit,
	onClose: () -> Unit,
	onRename: (String) -> Unit,
	onDuplicate: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember(workspace.id) { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	var showMenu by remember(workspace.id) { mutableStateOf(false) }
	var renaming by remember(workspace.id) { mutableStateOf(false) }
	var draft by remember(workspace.id, workspace.name) { mutableStateOf(workspace.displayName()) }
	var renameArmed by remember(workspace.id) { mutableStateOf(false) }
	val focusRequester = remember(workspace.id) { FocusRequester() }

	val bg = when {
		isActive -> colors.panelBackground
		isHovered -> colors.controlHover
		else -> Color.Transparent
	}

	Box {
		Row(
			modifier = Modifier
				.fillMaxHeight()
				.background(bg)
				.drawBehind {
					if (isActive) drawRect(color = colors.accent, topLeft = Offset.Zero, size = Size(size.width, 2.dp.toPx()))
				}
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
					if (event.button == PointerButton.Secondary) {
						showMenu = true
						event.changes.forEach { it.consume() }
					}
				}
				.combinedClickable(
					interactionSource = interactionSource,
					indication = null,
					enabled = !renaming,
					onClick = onSelect,
					onDoubleClick = {
						onSelect()
						draft = workspace.displayName()
						renaming = true
					},
				)
				.border(BorderStroke(1.dp, if (isActive) colors.divider else Color.Transparent))
				.padding(start = 10.dp, end = 3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (renaming && isActive) {
				BasicTextField(
					value = draft,
					onValueChange = { draft = it },
					singleLine = true,
					textStyle = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary),
					cursorBrush = SolidColor(colors.accent),
					modifier = Modifier
						.widthIn(min = 48.dp, max = 160.dp)
						.focusRequester(focusRequester)
						.onFocusChanged { focus ->
							if (renameArmed && !focus.isFocused) {
								renameArmed = false
								renaming = false
								onRename(draft)
							}
						}
						.onPreviewKeyEvent { event ->
							if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
							when (event.key) {
								Key.Enter -> {
									renameArmed = false
									renaming = false
									onRename(draft)
									true
								}
								Key.Escape -> {
									renameArmed = false
									renaming = false
									draft = workspace.displayName()
									true
								}
								else -> false
							}
						},
				)
				LaunchedEffect(renaming) {
					if (renaming) {
						focusRequester.requestFocus()
						renameArmed = true
					}
				}
			} else {
				Text(
					text = workspace.displayName(),
					style = typography.body.copy(
						fontSize = 11.5.sp,
						fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
					),
					color = if (isActive) colors.textPrimary else colors.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = 160.dp),
				)
			}
			Spacer(Modifier.width(4.dp))
			Box(
				modifier = Modifier
					.size(14.dp)
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.clickable(enabled = canClose && (isActive || isHovered)) { onClose() },
				contentAlignment = Alignment.Center,
			) {
				if (canClose && (isActive || isHovered)) {
					IconClose(modifier = Modifier.size(8.dp), tint = if (isActive) colors.textPrimary else colors.textMuted)
				}
			}
		}

		TabStripDropdown(expanded = showMenu, onDismissRequest = { showMenu = false }) {
			AppMenuHeader(workspace.displayName())
			AppMenuItem(text = tr("workspace.rename"), onClick = {
				showMenu = false
				onSelect()
				draft = workspace.displayName()
				renaming = true
			})
			AppMenuItem(text = tr("workspace.duplicate"), onClick = {
				showMenu = false
				onDuplicate()
			})
			AppMenuItem(text = tr("workspace.close"), enabled = canClose, onClick = {
				showMenu = false
				onClose()
			})
		}
	}
}

@Composable
private fun TabStripButton(
	label: String,
	highlighted: Boolean = false,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()

	Box(
		modifier = Modifier
			.fillMaxHeight()
			.background(if (isHovered) colors.controlHover else Color.Transparent)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.clickable(interactionSource = interactionSource, indication = null) { onClick() }
			.drawBehind {
				if (highlighted) {
					drawCircle(color = colors.accent, radius = 2.5.dp.toPx(), center = Offset(size.width - 6.dp.toPx(), 8.dp.toPx()))
				}
			}
			.padding(horizontal = 7.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
			color = if (highlighted) colors.textPrimary else colors.textMuted,
			maxLines = 1,
		)
	}
}

@Composable
internal fun TabStripDropdown(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	DropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		modifier = Modifier
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.border))
			.widthIn(min = 210.dp, max = 280.dp),
		content = content,
	)
}

internal fun moduleTitle(id: String): String = when {
	isCanvasModule(id) -> tr("dock.canvas")
	else -> tr(when (id) {
		"hierarchy" -> "dock.hierarchy"
		"skeleton" -> "dock.skeleton"
		"history" -> "dock.history"
		"log" -> "dock.log"
		"settings" -> "dock.settings"
		"layers" -> "tab.layers"
		"parameters" -> "tab.parameters"
		"tools" -> "tab.toolDetails"
		"mesh" -> "tab.mesh"
		"inspector" -> "tab.inspector"
		"animation" -> "tab.animation"
		"physics" -> "tab.physics"
		else -> id
	})
}
