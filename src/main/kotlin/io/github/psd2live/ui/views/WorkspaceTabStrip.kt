package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import io.github.psd2live.ui.components.ViewOptionsMenuItems
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspaceTabKind
import io.github.psd2live.ui.state.WorkspaceTabState
import io.github.psd2live.ui.state.canvasMode
import io.github.psd2live.ui.state.defaultViewOptions
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import java.awt.Cursor

/**
 * Browser-style workspace tab strip: pinned Edit / Preview tabs, closable added tabs, an add menu
 * and a per-tab view-options menu. Every canvas tab keeps its own toggles and camera.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorkspaceTabStrip(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	var showAddMenu by remember { mutableStateOf(false) }
	var showOptionsMenu by remember { mutableStateOf(false) }

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
			state.workspaceTabs.forEach { tab ->
				WorkspaceTabChip(
					tab = tab,
					title = viewModel.tabTitle(tab),
					isActive = tab.id == state.activeWorkspaceTabId,
					onSelect = { viewModel.setActiveTab(tab.id) },
					onClose = { viewModel.closeTab(tab.id) },
					onDuplicate = { viewModel.duplicateTab(tab.id) },
					modifier = if (tab.kind == WorkspaceTabKind.PREVIEW) {
						Modifier.tutorialTarget(TutorialTargetId.PREVIEW_TAB)
					} else {
						Modifier
					},
				)
			}

			// Add tab -- it sits right behind the last tab, browser-style, so it scrolls with the
			// strip instead of floating at the far end of an otherwise empty bar.
			Box {
				TabStripButton(label = "+") { showAddMenu = true }
				TabStripDropdown(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
					AppMenuHeader(tr("menu.view.tabs"))
					AppMenuItem(text = tr("tab.new.edit"), onClick = {
						showAddMenu = false
						viewModel.addTab(WorkspaceTabKind.EDIT)
					})
					AppMenuItem(text = tr("tab.new.preview"), onClick = {
						showAddMenu = false
						viewModel.addTab(WorkspaceTabKind.PREVIEW)
					})
					AppMenuItem(text = tr("tab.new.history"), onClick = {
						showAddMenu = false
						viewModel.openHistoryTab()
					})
				}
			}
		}

		// Per-tab view options (canvas tabs only; the history tab has no canvas options)
		if (state.activeTabKind.canvasMode != null) {
			Box {
				TabStripButton(
					label = "${tr("tab.options.short")} \u25BE",
					highlighted = state.activeTabView != state.activeTabKind.defaultViewOptions(),
				) { showOptionsMenu = true }
				TabStripDropdown(expanded = showOptionsMenu, onDismissRequest = { showOptionsMenu = false }) {
					ViewOptionsMenuItems(
						options = state.activeTabView,
						onOptionsChange = viewModel::setTabViewOptions,
						onDismiss = { showOptionsMenu = false },
						showHeaders = true,
						// Only the Edit canvas paints path guides, so only it offers their toggles.
						showPathGuides = state.activeTabKind == WorkspaceTabKind.EDIT,
						onReset = {
							showOptionsMenu = false
							viewModel.resetActiveTabViewOptions()
						},
					)
				}
			}
		}

		Spacer(Modifier.width(2.dp))
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WorkspaceTabChip(
	tab: WorkspaceTabState,
	title: String,
	isActive: Boolean,
	onSelect: () -> Unit,
	onClose: () -> Unit,
	onDuplicate: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember(tab.id) { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	var showMenu by remember(tab.id) { mutableStateOf(false) }

	val bg = when {
		isActive -> colors.panelBackground
		isHovered -> colors.controlHover
		else -> Color.Transparent
	}

	Box(modifier = modifier) {
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
						// Keep the right click from also activating the tab through clickable.
						event.changes.forEach { it.consume() }
					}
				}
				.clickable(interactionSource = interactionSource, indication = null) { onSelect() }
				.border(BorderStroke(1.dp, if (isActive) colors.divider else Color.Transparent))
				.padding(start = 10.dp, end = if (tab.pinned) 10.dp else 3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = title,
				style = typography.body.copy(
					fontSize = 11.5.sp,
					fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = if (isActive) colors.textPrimary else colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 160.dp),
			)
			if (!tab.pinned) {
				Spacer(Modifier.width(4.dp))
				// The slot is always reserved so hovering never shifts the tab title.
				Box(
					modifier = Modifier
						.size(14.dp)
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
						.clickable(enabled = isActive || isHovered) { onClose() },
					contentAlignment = Alignment.Center,
				) {
					if (isActive || isHovered) {
						IconClose(modifier = Modifier.size(8.dp), tint = if (isActive) colors.textPrimary else colors.textMuted)
					}
				}
			}
		}

		TabStripDropdown(expanded = showMenu, onDismissRequest = { showMenu = false }) {
			AppMenuHeader(title)
			AppMenuItem(
				text = tr("tab.duplicate"),
				// A second history tab would be an empty promise: the view is a singleton.
				enabled = tab.kind != WorkspaceTabKind.HISTORY,
				onClick = {
					showMenu = false
					onDuplicate()
				},
			)
			AppMenuItem(
				text = tr("tab.close"),
				enabled = !tab.pinned,
				onClick = {
					showMenu = false
					onClose()
				},
			)
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
private fun TabStripDropdown(
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
