package io.github.psd2live.ui.views

import io.github.psd2live.ui.components.AppDropdownMenu
import io.github.psd2live.ui.theme.Corners
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuHeader
import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.AppMenuSeparator
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.pageOfModule
import io.github.psd2live.ui.state.EditorWorkspace
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.displayName
import io.github.psd2live.ui.state.isCanvasModule
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.selectOrOpen
import kotlinx.coroutines.launch

/** Dock modules the window menu can show or hide, in menu order. */
internal val WINDOW_MODULES = listOf(
	"hierarchy",
	"skeleton",
	"history",
	"log",
	"animationEditor",
	"layers",
	"parameters",
	"tools",
	"mesh",
	"inspector",
	"animation",
	"physics",
	"modifiers",
	"atlas",
	"texture",
	"materials",
)

/**
 * Workspace strip: the fixed Start tab, then a chip per named arrangement of panels and canvases.
 * The window menu toggles components and adds canvases.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorkspaceStrip(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	layoutModules: Set<String>,
	onResetLayout: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var showWindowMenu by remember { mutableStateOf(false) }
	val workspace = state.activeWorkspace
	val activePage = viewModel.activePage
	val chipSpacing = 2.dp
	val chipSpacingPx = with(LocalDensity.current) { chipSpacing.toPx() }
	// Laid-out left edge and width of each chip in the strip, for dragging past its neighbours.
	val chipBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
	// How far each chip is drawn from its slot while it slides into it after a swap or a drop.
	val chipSlides = remember { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
	val slideScope = rememberCoroutineScope()
	var draggingId by remember { mutableStateOf<String?>(null) }
	var settlingId by remember { mutableStateOf<String?>(null) }
	// Where the pointer has taken the dragged chip's left edge, and that edge kept within the workspaces' span.
	// Both are in the strip's coordinates, not the chip's slot, so the chip stays under the pointer while the
	// slots move for other reasons: the start page's tab folding away as the press leaves it, a page closing.
	var dragPointerLeft by remember { mutableFloatStateOf(0f) }
	var dragLeft by remember { mutableFloatStateOf(0f) }

	fun slide(id: String) = chipSlides.getOrPut(id) { Animatable(0f) }

	// Draws [id] where it was before its slot moved by [shift], then slides it into the slot.
	fun slideFrom(id: String, shift: Float) {
		val offset = slide(id)
		slideScope.launch {
			offset.snapTo(offset.value - shift)
			offset.animateTo(0f, tween(Motion.Fold, easing = FastOutSlowInEasing))
		}
	}

	// The dragged chip follows the pointer; each neighbour whose centre its leading edge passes swaps with it
	// and slides into the slot it left. The leading edge, not the chip's centre: held within the span, a chip
	// narrower than the end one could never get its centre past that one's, and stayed off the ends.
	fun dragChip(id: String, amount: Float) {
		// Read the live order: several drag events can land before the strip recomposes.
		val workspaces = viewModel.state.value.workspaces
		val spans = workspaces.mapNotNull { chipBounds[it.id] }
		val width = chipBounds[id]?.second ?: return
		dragPointerLeft += amount
		dragLeft = dragPointerLeft.coerceIn(spans.minOf { it.first }, (spans.maxOf { it.first + it.second } - width).coerceAtLeast(spans.minOf { it.first }))
		var index = workspaces.indexOfFirst { it.id == id }
		val order = workspaces.toMutableList()
		while (true) {
			val (left, _) = chipBounds[id] ?: return
			val forward = order.getOrNull(index + 1)?.takeIf { next ->
				chipBounds[next.id]?.let { (l, w) -> dragLeft + width > l + w / 2 } == true
			}
			val neighbour = forward ?: order.getOrNull(index - 1)?.takeIf { previous ->
				chipBounds[previous.id]?.let { (l, w) -> dragLeft < l + w / 2 } == true
			} ?: return
			val (otherLeft, otherWidth) = chipBounds.getValue(neighbour.id)
			val to = if (forward != null) index + 1 else index - 1
			viewModel.moveWorkspace(id, to)
			order.add(to, order.removeAt(index))
			index = to
			// The layout catches up before the next frame draws; move the edges now so one fast drag passes
			// several chips, and the dragged chip's offset from its slot holds.
			val ownLeft = if (forward != null) left + otherWidth + chipSpacingPx else otherLeft
			val otherNewLeft = if (forward != null) left else otherLeft + width + chipSpacingPx
			chipBounds[id] = ownLeft to width
			chipBounds[neighbour.id] = otherNewLeft to otherWidth
			slideFrom(neighbour.id, otherNewLeft - otherLeft)
		}
	}

	fun endDrag(id: String) {
		if (draggingId != id) return
		draggingId = null
		val slot = chipBounds[id]?.first ?: return
		// Let go between slots, the chip glides into its own instead of jumping there.
		settlingId = id
		val offset = slide(id)
		slideScope.launch {
			offset.snapTo(dragLeft - slot)
			offset.animateTo(0f, tween(Motion.Fold, easing = FastOutSlowInEasing))
			if (settlingId == id) settlingId = null
		}
	}

	Row(
		modifier = modifier.fillMaxHeight(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				// The first tab lines up with the panels' edge below it.
				.horizontalScroll(rememberScrollState()),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(chipSpacing),
		) {
			// A page's tab lives while the page is open: the start page's folds away once a project takes over, help's
			// and settings' when closed, and each opens out again when its page is asked for.
			AppPage.entries.forEach { page ->
				androidx.compose.animation.AnimatedVisibility(
					visible = page in viewModel.openPages,
					enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Fade, delayMillis = Motion.Instant)) +
						androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(Motion.Expand), expandFrom = Alignment.Start),
					exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Instant)) +
						androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(Motion.Expand), shrinkTowards = Alignment.Start),
				) {
					PageChip(
						page = page,
						isActive = activePage == page,
						onSelect = { viewModel.showPage(page) },
						onClose = if (page == AppPage.START) null else ({ viewModel.closePage(page) }),
					)
				}
			}
			// Keeps the pages apart from the workspaces, which can be reordered among themselves.
			// It folds with the last page's tab, so the workspaces glide over together instead of jumping at the end.
			androidx.compose.animation.AnimatedVisibility(
				visible = viewModel.openPages.isNotEmpty(),
				enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Fade, delayMillis = Motion.Instant)) +
					androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(Motion.Expand), expandFrom = Alignment.Start),
				exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Instant)) +
					androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(Motion.Expand), shrinkTowards = Alignment.Start),
			) {
				Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(14.dp).background(LocalToolColors.current.divider))
			}
			val pageShown = activePage != null
			state.workspaces.forEachIndexed { index, item ->
				// Keyed by workspace, so a swap moves the dragged chip's gesture along with it instead of
				// handing its slot to the neighbour, which restarted the gesture and ended the drag.
				key(item.id) {
					val dragging = draggingId == item.id
					WorkspaceChip(
						workspace = item,
						isActive = !pageShown && item.id == state.activeWorkspaceId,
						canClose = state.workspaces.size > 1,
						canCloseRight = index < state.workspaces.lastIndex,
						onSelect = { viewModel.setActiveWorkspace(item.id) },
						onClose = { viewModel.closeWorkspace(item.id) },
						onCloseOthers = { viewModel.closeOtherWorkspaces(item.id) },
						onCloseRight = { viewModel.closeWorkspacesToRight(item.id) },
						onRename = { viewModel.renameWorkspace(item.id, it) },
						onDuplicate = { viewModel.duplicateWorkspace(item.id) },
						onDragStart = {
							val slot = chipBounds[item.id]?.first ?: 0f
							// Picked up mid-slide, the chip starts from where it is drawn.
							dragPointerLeft = slot + slide(item.id).value
							dragLeft = dragPointerLeft
							settlingId = null
							draggingId = item.id
						},
						onDrag = { dragChip(item.id, it) },
						onDragEnd = { endDrag(item.id) },
						modifier = Modifier
							.onGloballyPositioned { chipBounds[item.id] = it.positionInParent().x to it.size.width.toFloat() }
							.zIndex(if (dragging || settlingId == item.id) 1f else 0f)
							.graphicsLayer {
								translationX = if (dragging) dragLeft - (chipBounds[item.id]?.first ?: dragLeft) else slide(item.id).value
							},
					)
					DisposableEffect(Unit) {
						onDispose {
							chipBounds.remove(item.id)
							chipSlides.remove(item.id)
						}
					}
				}
			}
			NewWorkspaceButton(onCreate = { viewModel.addWorkspace(it) })
		}

		Box {
			TabStripButton(label = "${tr("workspace.windows")} \u25BE") { showWindowMenu = true }
			TabStripDropdown(expanded = showWindowMenu, onDismissRequest = { showWindowMenu = false }) {
				AppMenuHeader(tr("workspace.windows"))
				// A page lists its own panels; the editor's belong to the workspaces.
				if (activePage != null) {
					val hidden = viewModel.pageArrangement(activePage).hiddenModules
					activePage.modules.forEach { module ->
						AppMenuItem(
							text = pageModuleTitle(module, viewModel),
							isChecked = module !in hidden,
							onClick = { viewModel.setPageModuleVisible(activePage, module, module in hidden) },
						)
					}
					AppMenuSeparator()
					AppMenuItem(text = tr("dock.reset"), onClick = {
						showWindowMenu = false
						onResetLayout()
					})
					return@TabStripDropdown
				}
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
				AppMenuSeparator()
				AppMenuItem(text = tr("dock.reset"), onClick = {
					showWindowMenu = false
					onResetLayout()
				})
			}
		}
		Spacer(Modifier.width(2.dp))
	}
}

/**
 * An app page's tab, ahead of the workspaces: its icon and name, never renamed or dragged. Help's and settings' show
 * a close button while hovered or shown; the start page's folds away by itself once a project opens.
 */
@Composable
private fun PageChip(page: AppPage, isActive: Boolean, onSelect: () -> Unit, onClose: (() -> Unit)?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val showClose = onClose != null && (isHovered || isActive)
	run {
		Row(
			modifier = Modifier
				.padding(end = 2.dp)
				.height(22.dp)
				.clip(RoundedCornerShape(Corners.Control))
				.background(when {
					isActive -> colors.controlBackground
					isHovered -> colors.controlHover.copy(alpha = 0.6f)
					else -> Color.Transparent
				})
				.hoverable(interactionSource)
				.handCursor()
				.clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
				.padding(start = 8.dp, end = if (showClose) 3.dp else 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			PageIcon(page, if (isActive) colors.accent else colors.textMuted, Modifier.size(12.dp))
			run {
				Spacer(Modifier.width(5.dp))
				Text(
					text = pageTitle(page),
					style = typography.body.copy(fontSize = 11.5.sp, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal),
					color = if (isActive) colors.textPrimary else colors.textMuted,
					maxLines = 1,
				)
			}
			if (showClose) {
				Spacer(Modifier.width(3.dp))
				val closeInteraction = remember { MutableInteractionSource() }
				val closeHovered by closeInteraction.collectIsHoveredAsState()
				Box(
					Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
						.background(if (closeHovered) colors.controlHover else Color.Transparent)
						.hoverable(closeInteraction)
						.clickable(interactionSource = closeInteraction, indication = null, onClick = onClose!!),
					contentAlignment = Alignment.Center,
				) {
					IconClose(Modifier.size(7.dp), if (closeHovered) colors.textPrimary else colors.textMuted)
				}
			}
		}
	}
}

@Composable
private fun NewWorkspaceButton(onCreate: (WorkspacePreset) -> Unit) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		TabStripButton(label = "+") { expanded = true }
		NewWorkspaceMenu(expanded = expanded, onDismissRequest = { expanded = false }, onCreate = onCreate)
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WorkspaceChip(
	workspace: EditorWorkspace,
	isActive: Boolean,
	canClose: Boolean,
	canCloseRight: Boolean,
	onSelect: () -> Unit,
	onClose: () -> Unit,
	onCloseOthers: () -> Unit,
	onCloseRight: () -> Unit,
	onRename: (String) -> Unit,
	onDuplicate: () -> Unit,
	onDragStart: () -> Unit,
	onDrag: (Float) -> Unit,
	onDragEnd: () -> Unit,
	modifier: Modifier = Modifier,
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
	val currentOnDragStart by rememberUpdatedState(onDragStart)
	val currentOnDrag by rememberUpdatedState(onDrag)
	val currentOnDragEnd by rememberUpdatedState(onDragEnd)

	// Workspace tabs are top-level pills; dock tabs below stay flat and join their panel.
	val bg = when {
		isActive -> colors.controlBackground
		isHovered -> colors.controlHover.copy(alpha = 0.6f)
		else -> Color.Transparent
	}

	Box(modifier) {
		Row(
			modifier = Modifier
				.height(22.dp)
				.clip(RoundedCornerShape(Corners.Control))
				.background(bg)
				.handCursor()
				.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
					when {
						event.button == PointerButton.Secondary -> {
							showMenu = true
							event.changes.forEach { it.consume() }
						}
						event.button == PointerButton.Tertiary && !renaming -> {
							if (canClose) onClose()
							event.changes.forEach { it.consume() }
						}
					}
				}
				.pointerInput(workspace.id, renaming) {
					if (renaming) return@pointerInput
					detectHorizontalDragGestures(
						onDragStart = { currentOnDragStart() },
						onDragEnd = { currentOnDragEnd() },
						onDragCancel = { currentOnDragEnd() },
					) { change, amount ->
						change.consume()
						currentOnDrag(amount)
					}
				}
				.hoverable(interactionSource)
				.selectOrOpen(!renaming, onSelect) {
					draft = workspace.displayName()
					renaming = true
				}
				.padding(start = 8.dp, end = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			WorkspacePresetIcon(
				workspace.preset,
				tint = if (isActive) colors.textPrimary else colors.textMuted,
				modifier = Modifier.size(12.dp),
			)
			Spacer(Modifier.width(5.dp))
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
					.handCursor()
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
			AppMenuSeparator()
			AppMenuItem(text = tr("workspace.close"), enabled = canClose, onClick = {
				showMenu = false
				onClose()
			})
			AppMenuItem(text = tr("workspace.closeOthers"), enabled = canClose, onClick = {
				showMenu = false
				onCloseOthers()
			})
			AppMenuItem(text = tr("workspace.closeRight"), enabled = canCloseRight, onClick = {
				showMenu = false
				onCloseRight()
			})
		}
	}
}

@Composable
internal fun TabStripButton(
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
			.height(22.dp)
			.clip(RoundedCornerShape(Corners.Control))
			.background(if (isHovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent)
			.handCursor()
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
	AppDropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		modifier = Modifier.widthIn(min = 210.dp, max = 280.dp),
		content = content,
	)
}

internal fun moduleTitle(id: String): String = when {
	isCanvasModule(id) -> tr("dock.canvas")
	pageOfModule(id) != null -> tr("dock.module.$id")
	else -> tr(when (id) {
		"hierarchy" -> "dock.hierarchy"
		"skeleton" -> "dock.skeleton"
		"history" -> "dock.history"
		"log" -> "dock.log"
		"animationEditor" -> "dock.animationEditor"
		"layers" -> "tab.layers"
		"parameters" -> "tab.parameters"
		"tools" -> "tab.toolDetails"
		"mesh" -> "tab.mesh"
		"inspector" -> "tab.inspector"
		"animation" -> "tab.animation"
		"physics" -> "tab.physics"
		"modifiers" -> "modifiers.title"
		"atlas" -> "dock.atlas"
		"texture" -> "dock.texture"
		"materials" -> "dock.materials"
		else -> id
	})
}
