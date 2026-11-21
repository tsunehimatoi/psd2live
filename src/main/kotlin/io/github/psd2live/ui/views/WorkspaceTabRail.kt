package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.EditorWorkspace
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.displayName
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.selectOrOpen
import io.github.psd2live.ui.views.texture.BarTooltip
import kotlinx.coroutines.launch

/** Width of the collapsible left workspace rail when expanded and collapsed. */
internal val WorkspaceRailExpandedWidth = 144.dp
internal val WorkspaceRailCollapsedWidth = 34.dp

/**
 * Collapsible vertical workspace tab rail on the left edge, matching the start hub's navigation rail.
 * Keeps every capability of [WorkspaceStrip]: page tabs, drag-to-reorder workspaces, inline rename,
 * context menu, middle-click close, preset creator, and the window modules menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WorkspaceTabRail(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	layoutModules: Set<String>,
	expanded: Boolean,
	onToggleExpanded: () -> Unit,
	onExpand: () -> Unit,
	onResetLayout: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val compact = !expanded
	val activePage = viewModel.activePage
	val itemSpacing = 2.dp
	val itemSpacingPx = with(LocalDensity.current) { itemSpacing.toPx() }
	// Laid-out top edge and height of each workspace item, for vertical drag reordering.
	val itemBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
	val itemSlides = remember { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
	val slideScope = rememberCoroutineScope()
	var draggingId by remember { mutableStateOf<String?>(null) }
	var settlingId by remember { mutableStateOf<String?>(null) }
	var dragPointerTop by remember { mutableFloatStateOf(0f) }
	var dragTop by remember { mutableFloatStateOf(0f) }

	fun slide(id: String) = itemSlides.getOrPut(id) { Animatable(0f) }

	fun slideFrom(id: String, shift: Float) {
		val offset = slide(id)
		slideScope.launch {
			offset.snapTo(offset.value - shift)
			offset.animateTo(0f, tween(Motion.Fold, easing = FastOutSlowInEasing))
		}
	}

	fun dragItem(id: String, amount: Float) {
		val workspaces = viewModel.state.value.workspaces
		val spans = workspaces.mapNotNull { itemBounds[it.id] }
		val height = itemBounds[id]?.second ?: return
		dragPointerTop += amount
		val minTop = spans.minOf { it.first }
		val maxTop = (spans.maxOf { it.first + it.second } - height).coerceAtLeast(minTop)
		dragTop = dragPointerTop.coerceIn(minTop, maxTop)
		var index = workspaces.indexOfFirst { it.id == id }
		val order = workspaces.toMutableList()
		while (true) {
			val (top, _) = itemBounds[id] ?: return
			val forward = order.getOrNull(index + 1)?.takeIf { next ->
				itemBounds[next.id]?.let { (t, h) -> dragTop + height > t + h / 2 } == true
			}
			val neighbour = forward ?: order.getOrNull(index - 1)?.takeIf { previous ->
				itemBounds[previous.id]?.let { (t, h) -> dragTop < t + h / 2 } == true
			} ?: return
			val (otherTop, otherHeight) = itemBounds.getValue(neighbour.id)
			val to = if (forward != null) index + 1 else index - 1
			viewModel.moveWorkspace(id, to)
			order.add(to, order.removeAt(index))
			index = to
			val ownTop = if (forward != null) top + otherHeight + itemSpacingPx else otherTop
			val otherNewTop = if (forward != null) top else otherTop + height + itemSpacingPx
			itemBounds[id] = ownTop to height
			itemBounds[neighbour.id] = otherNewTop to otherHeight
			slideFrom(neighbour.id, otherNewTop - otherTop)
		}
	}

	fun endDrag(id: String) {
		if (draggingId != id) return
		draggingId = null
		val slot = itemBounds[id]?.first ?: return
		settlingId = id
		val offset = slide(id)
		slideScope.launch {
			offset.snapTo(dragTop - slot)
			offset.animateTo(0f, tween(Motion.Fold, easing = FastOutSlowInEasing))
			if (settlingId == id) settlingId = null
		}
	}

	Column(modifier.fillMaxSize().background(LocalToolColors.current.windowBackground)) {
		RailHeader(compact = compact, onToggle = onToggleExpanded)
		Column(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(top = 4.dp, bottom = 6.dp),
			verticalArrangement = Arrangement.spacedBy(itemSpacing),
		) {
			AppPage.entries.forEach { page ->
				AnimatedVisibility(
					visible = page in viewModel.openPages,
					enter = fadeIn(tween(Motion.Fade, delayMillis = Motion.Instant)) +
						expandVertically(tween(Motion.Expand), expandFrom = Alignment.Top),
					exit = fadeOut(tween(Motion.Instant)) +
						shrinkVertically(tween(Motion.Expand), shrinkTowards = Alignment.Top),
				) {
					RailPageItem(
						page = page,
						isActive = activePage == page,
						compact = compact,
						onSelect = { viewModel.showPage(page) },
						onClose = if (page == AppPage.START) null else ({ viewModel.closePage(page) }),
					)
				}
			}
			AnimatedVisibility(
				visible = viewModel.openPages.isNotEmpty(),
				enter = fadeIn(tween(Motion.Fade, delayMillis = Motion.Instant)) +
					expandVertically(tween(Motion.Expand), expandFrom = Alignment.Top),
				exit = fadeOut(tween(Motion.Instant)) +
					shrinkVertically(tween(Motion.Expand), shrinkTowards = Alignment.Top),
			) {
				Box(
					Modifier.fillMaxWidth()
						.padding(horizontal = if (compact) 7.dp else 8.dp, vertical = 5.dp)
						.height(0.5.dp)
						.background(LocalToolColors.current.divider.copy(alpha = 0.65f)),
				)
			}
			val pageShown = activePage != null
			state.workspaces.forEachIndexed { index, item ->
				key(item.id) {
					val dragging = draggingId == item.id
					RailWorkspaceItem(
						workspace = item,
						isActive = !pageShown && item.id == state.activeWorkspaceId,
						compact = compact,
						canClose = state.workspaces.size > 1,
						canCloseBelow = index < state.workspaces.lastIndex,
						onSelect = { viewModel.setActiveWorkspace(item.id) },
						onClose = { viewModel.closeWorkspace(item.id) },
						onCloseOthers = { viewModel.closeOtherWorkspaces(item.id) },
						onCloseBelow = { viewModel.closeWorkspacesToRight(item.id) },
						onRename = { viewModel.renameWorkspace(item.id, it) },
						onDuplicate = { viewModel.duplicateWorkspace(item.id) },
						onExpand = onExpand,
						onDragStart = {
							val slot = itemBounds[item.id]?.first ?: 0f
							dragPointerTop = slot + slide(item.id).value
							dragTop = dragPointerTop
							settlingId = null
							draggingId = item.id
						},
						onDrag = { dragItem(item.id, it) },
						onDragEnd = { endDrag(item.id) },
						modifier = Modifier
							.onGloballyPositioned { itemBounds[item.id] = it.positionInParent().y to it.size.height.toFloat() }
							.zIndex(if (dragging || settlingId == item.id) 1f else 0f)
							.graphicsLayer {
								translationY = if (dragging) dragTop - (itemBounds[item.id]?.first ?: dragTop) else slide(item.id).value
							},
					)
					DisposableEffect(Unit) {
						onDispose {
							itemBounds.remove(item.id)
							itemSlides.remove(item.id)
						}
					}
				}
			}
			RailNewWorkspaceButton(compact = compact, onCreate = { viewModel.addWorkspace(it) })
		}
		RailWindowMenuFooter(
			state = state,
			viewModel = viewModel,
			layoutModules = layoutModules,
			compact = compact,
			onResetLayout = onResetLayout,
		)
	}
}

@Composable
private fun RailHeader(compact: Boolean, onToggle: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val tip = tr(if (compact) "workspace.tabs.expand" else "workspace.tabs.collapse")

	if (compact) {
		Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
			BarTooltip(tip) {
				Box(
					Modifier.size(22.dp)
						.clip(RoundedCornerShape(Corners.Control))
						.background(if (hovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent)
						.hoverable(interaction)
						.clickable(interactionSource = interaction, indication = null, onClick = onToggle)
						.handCursor(),
					contentAlignment = Alignment.Center,
				) {
					RailToggleIcon(collapsed = true, tint = if (hovered) colors.textPrimary else colors.textMuted)
				}
			}
		}
		return
	}

	Row(
		modifier = Modifier.fillMaxWidth().height(26.dp).padding(start = 9.dp, end = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = tr("workspace.tabs.title"),
			style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
			color = colors.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		BarTooltip(tip) {
			Box(
				Modifier.size(22.dp)
					.clip(RoundedCornerShape(Corners.Control))
					.background(if (hovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent)
					.hoverable(interaction)
					.clickable(interactionSource = interaction, indication = null, onClick = onToggle)
					.handCursor(),
				contentAlignment = Alignment.Center,
			) {
				RailToggleIcon(collapsed = false, tint = if (hovered) colors.textPrimary else colors.textMuted)
			}
		}
	}
}

@Composable
private fun RailPageItem(
	page: AppPage,
	isActive: Boolean,
	compact: Boolean,
	onSelect: () -> Unit,
	onClose: (() -> Unit)?,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val bg = when {
		isActive -> colors.controlBackground
		hovered -> colors.controlHover.copy(alpha = 0.6f)
		else -> Color.Transparent
	}
	val iconTint = if (isActive) colors.accent else colors.textMuted
	if (compact) {
		BarTooltip(pageTitle(page)) {
			Box(
				Modifier.fillMaxWidth().height(26.dp)
					.clip(RoundedCornerShape(Corners.Control))
					.background(bg)
					.hoverable(interaction)
					.clickable(interactionSource = interaction, indication = null, onClick = onSelect)
					.handCursor(),
				contentAlignment = Alignment.Center,
			) {
				if (isActive) {
					Box(
						Modifier.align(Alignment.CenterStart).padding(start = 2.dp)
							.width(2.dp).height(12.dp).clip(RoundedCornerShape(1.dp)).background(colors.accent),
					)
				}
				PageIcon(page, iconTint, Modifier.size(13.dp))
			}
		}
		return
	}

	val showClose = onClose != null && (hovered || isActive)
	Row(
		Modifier.fillMaxWidth().height(26.dp)
			.clip(RoundedCornerShape(Corners.Control))
			.background(bg)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, onClick = onSelect)
			.handCursor()
			.padding(start = 2.dp, end = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(2.dp).height(12.dp).clip(RoundedCornerShape(1.dp)).background(if (isActive) colors.accent else Color.Transparent))
		Spacer(Modifier.width(5.dp))
		Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
			PageIcon(page, iconTint, Modifier.size(13.dp))
		}
		Spacer(Modifier.width(7.dp))
		Text(
			text = pageTitle(page),
			style = typography.body.copy(fontSize = 11.5.sp, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal),
			color = if (isActive) colors.textPrimary else colors.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		if (showClose) {
			Spacer(Modifier.width(4.dp))
			val closeInteraction = remember { MutableInteractionSource() }
			val closeHovered by closeInteraction.collectIsHoveredAsState()
			Box(
				Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
					.background(if (closeHovered) colors.controlHover else Color.Transparent)
					.hoverable(closeInteraction)
					.clickable(interactionSource = closeInteraction, indication = null, onClick = onClose)
					.handCursor(),
				contentAlignment = Alignment.Center,
			) {
				IconClose(Modifier.size(7.dp), if (closeHovered) colors.textPrimary else colors.textMuted)
			}
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RailWorkspaceItem(
	workspace: EditorWorkspace,
	isActive: Boolean,
	compact: Boolean,
	canClose: Boolean,
	canCloseBelow: Boolean,
	onSelect: () -> Unit,
	onClose: () -> Unit,
	onCloseOthers: () -> Unit,
	onCloseBelow: () -> Unit,
	onRename: (String) -> Unit,
	onDuplicate: () -> Unit,
	onExpand: () -> Unit,
	onDragStart: () -> Unit,
	onDrag: (Float) -> Unit,
	onDragEnd: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember(workspace.id) { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var showMenu by remember(workspace.id) { mutableStateOf(false) }
	var renaming by remember(workspace.id) { mutableStateOf(false) }
	var draft by remember(workspace.id, workspace.name) { mutableStateOf(workspace.displayName()) }
	var renameArmed by remember(workspace.id) { mutableStateOf(false) }
	val focusRequester = remember(workspace.id) { FocusRequester() }
	val currentOnDragStart by rememberUpdatedState(onDragStart)
	val currentOnDrag by rememberUpdatedState(onDrag)
	val currentOnDragEnd by rememberUpdatedState(onDragEnd)

	val bg = when {
		isActive -> colors.controlBackground
		hovered -> colors.controlHover.copy(alpha = 0.6f)
		else -> Color.Transparent
	}
	val iconTint = if (isActive) colors.textPrimary else colors.textMuted

	val pointerAndDrag = Modifier
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
			detectVerticalDragGestures(
				onDragStart = { currentOnDragStart() },
				onDragEnd = { currentOnDragEnd() },
				onDragCancel = { currentOnDragEnd() },
			) { change, amount ->
				change.consume()
				currentOnDrag(amount)
			}
		}

	Box(modifier.fillMaxWidth()) {
		if (compact) {
			BarTooltip(workspace.displayName()) {
				Box(
					Modifier.fillMaxWidth().height(26.dp)
						.clip(RoundedCornerShape(Corners.Control))
						.background(bg)
						.then(pointerAndDrag)
						.hoverable(interaction)
						.clickable(interactionSource = interaction, indication = null, onClick = onSelect)
						.handCursor(),
					contentAlignment = Alignment.Center,
				) {
					if (isActive) {
						Box(
							Modifier.align(Alignment.CenterStart).padding(start = 2.dp)
								.width(2.dp).height(12.dp).clip(RoundedCornerShape(1.dp)).background(colors.accent),
						)
					}
					WorkspacePresetIcon(workspace.preset, tint = iconTint, modifier = Modifier.size(13.dp))
				}
			}
		} else {
			Row(
				Modifier.fillMaxWidth().height(26.dp)
					.clip(RoundedCornerShape(Corners.Control))
					.background(bg)
					.then(pointerAndDrag)
					.hoverable(interaction)
					.selectOrOpen(!renaming, onSelect) {
						draft = workspace.displayName()
						renaming = true
					}
					.handCursor()
					.padding(start = 2.dp, end = 5.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(Modifier.width(2.dp).height(12.dp).clip(RoundedCornerShape(1.dp)).background(if (isActive) colors.accent else Color.Transparent))
				Spacer(Modifier.width(5.dp))
				Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
					WorkspacePresetIcon(workspace.preset, tint = iconTint, modifier = Modifier.size(13.dp))
				}
				Spacer(Modifier.width(7.dp))
				if (renaming && isActive) {
					BasicTextField(
						value = draft,
						onValueChange = { draft = it },
						singleLine = true,
						textStyle = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary),
						cursorBrush = SolidColor(colors.accent),
						modifier = Modifier
							.weight(1f)
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
					LaunchedEffect(renaming, compact) {
						if (renaming && !compact) {
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
						modifier = Modifier.weight(1f),
					)
				}
				if (canClose && (isActive || hovered) && !renaming) {
					Spacer(Modifier.width(3.dp))
					val closeInteraction = remember { MutableInteractionSource() }
					val closeHovered by closeInteraction.collectIsHoveredAsState()
					Box(
						Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
							.background(if (closeHovered) colors.controlHover else Color.Transparent)
							.hoverable(closeInteraction)
							.clickable(interactionSource = closeInteraction, indication = null, onClick = onClose)
							.handCursor(),
						contentAlignment = Alignment.Center,
					) {
						IconClose(Modifier.size(7.dp), if (closeHovered) colors.textPrimary else iconTint)
					}
				}
			}
		}

		TabStripDropdown(expanded = showMenu, onDismissRequest = { showMenu = false }) {
			WorkspaceContextMenuContent(
				title = workspace.displayName(),
				canClose = canClose,
				canCloseTrailing = canCloseBelow,
				closeTrailingLabelKey = "workspace.closeBelow",
				onDismiss = { showMenu = false },
				onRename = {
					onSelect()
					if (compact) onExpand()
					draft = workspace.displayName()
					renaming = true
				},
				onDuplicate = onDuplicate,
				onClose = onClose,
				onCloseOthers = onCloseOthers,
				onCloseTrailing = onCloseBelow,
			)
		}
	}
}

@Composable
private fun RailNewWorkspaceButton(compact: Boolean, onCreate: (WorkspacePreset) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var expanded by remember { mutableStateOf(false) }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()

	Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
		if (compact) {
			BarTooltip(tr("workspace.new")) {
				Box(
					Modifier.fillMaxWidth().height(26.dp)
						.clip(RoundedCornerShape(Corners.Control))
						.background(if (hovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent)
						.hoverable(interaction)
						.clickable(interactionSource = interaction, indication = null) { expanded = true }
						.handCursor(),
					contentAlignment = Alignment.Center,
				) {
					IconAdd(Modifier.size(12.dp), if (hovered) colors.textPrimary else colors.textMuted)
				}
			}
		} else {
			Row(
				Modifier.fillMaxWidth().height(26.dp)
					.clip(RoundedCornerShape(Corners.Control))
					.background(if (hovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent)
					.hoverable(interaction)
					.clickable(interactionSource = interaction, indication = null) { expanded = true }
					.handCursor()
					.padding(start = 9.dp, end = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
					IconAdd(Modifier.size(11.dp), if (hovered) colors.textPrimary else colors.textMuted)
				}
				Spacer(Modifier.width(7.dp))
				Text(
					text = tr("workspace.new"),
					style = typography.body.copy(fontSize = 11.sp),
					color = if (hovered) colors.textPrimary else colors.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		NewWorkspaceMenu(expanded = expanded, onDismissRequest = { expanded = false }, onCreate = onCreate)
	}
}

@Composable
private fun RailWindowMenuFooter(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	layoutModules: Set<String>,
	compact: Boolean,
	onResetLayout: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var showWindowMenu by remember { mutableStateOf(false) }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()

	Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
		Box(
			Modifier.fillMaxWidth()
				.padding(horizontal = if (compact) 7.dp else 8.dp)
				.height(0.5.dp)
				.background(colors.divider.copy(alpha = 0.5f)),
		)
		Spacer(Modifier.height(4.dp))
		Box(Modifier.fillMaxWidth()) {
			if (compact) {
				Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
					BarTooltip(tr("workspace.windows")) {
						Box(
							Modifier.size(24.dp)
								.clip(RoundedCornerShape(Corners.Control))
								.background(if (hovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent)
								.hoverable(interaction)
								.clickable(interactionSource = interaction, indication = null) { showWindowMenu = true }
								.handCursor(),
							contentAlignment = Alignment.Center,
						) {
							WindowModulesIcon(if (hovered) colors.textPrimary else colors.textMuted)
						}
					}
				}
			} else {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.height(26.dp)
						.clip(RoundedCornerShape(Corners.Control))
						.background(if (hovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent)
						.hoverable(interaction)
						.clickable(interactionSource = interaction, indication = null) { showWindowMenu = true }
						.handCursor()
						.padding(start = 9.dp, end = 6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
						WindowModulesIcon(if (hovered) colors.textPrimary else colors.textMuted)
					}
					Spacer(Modifier.width(7.dp))
					Text(
						text = tr("workspace.windows"),
						style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
						color = if (hovered) colors.textPrimary else colors.textMuted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
					IconChevron(
						expanded = true,
						modifier = Modifier.size(9.dp),
						tint = if (hovered) colors.textPrimary else colors.textMuted,
					)
				}
			}
			TabStripDropdown(expanded = showWindowMenu, onDismissRequest = { showWindowMenu = false }) {
				WindowModulesMenuContent(
					state = state,
					viewModel = viewModel,
					layoutModules = layoutModules,
					onResetLayout = onResetLayout,
					onDismiss = { showWindowMenu = false },
				)
			}
		}
	}
}

/** Sidebar toggle icon: a framed panel with a left rail divider and a directional chevron. */
@Composable
private fun RailToggleIcon(collapsed: Boolean, tint: Color, modifier: Modifier = Modifier.size(13.dp)) {
	GridIcon(modifier, tint) {
		panel(2.0f, 3.0f, 14.0f, 12.0f, 1.5f)
		line(6.4f, 3.0f, 6.4f, 15.0f, ICON_FINE)
		if (collapsed) {
			chevron(12.2f, 9.0f, 1f, 0f, 2.2f, ICON_FINE)
		} else {
			chevron(10.2f, 9.0f, -1f, 0f, 2.2f, ICON_FINE)
		}
	}
}

/** Dock layout grid icon for the Window modules menu in the rail footer. */
@Composable
private fun WindowModulesIcon(tint: Color, modifier: Modifier = Modifier.size(13.dp)) {
	GridIcon(modifier, tint) {
		panel(2.0f, 2.6f, 14.0f, 12.8f, 1.5f)
		line(7.0f, 2.6f, 7.0f, 15.4f, ICON_FINE)
		line(7.0f, 9.0f, 16.0f, 9.0f, ICON_FINE)
	}
}
