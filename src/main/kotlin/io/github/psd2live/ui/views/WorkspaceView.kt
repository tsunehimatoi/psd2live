package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.Divider
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ComponentPalette
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactTabBar
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.components.DrawOrderRuler
import io.github.psd2live.ui.components.DrawOrderInputDialog
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspaceTab
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.model.Deformer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun WorkspaceView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val tabTitles = listOf(
		tr("tab.topology"),
		tr("tab.preview"),
		tr("tab.history"),
	)
	val selectedTabIndex = when (state.activeWorkspaceTab) {
		WorkspaceTab.TOPOLOGY -> 0
		WorkspaceTab.PREVIEW -> 1
		WorkspaceTab.HISTORY -> 2
		else -> 1
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.panelBackground)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		// Tab Bar
		CompactTabBar(
			tabs = tabTitles,
			selectedIndex = selectedTabIndex,
			onTabSelected = { index ->
				val tab = when (index) {
					0 -> WorkspaceTab.TOPOLOGY
					1 -> WorkspaceTab.PREVIEW
					else -> WorkspaceTab.HISTORY
				}
				viewModel.setWorkspaceTab(tab)
			},
		)

		// Upper Main Workspace Area: Topology / Preview (with persistent hierarchy sidebar), History
		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			when (state.activeWorkspaceTab) {
				WorkspaceTab.HISTORY -> HistoryTreeView(state, viewModel)
				else -> HierarchyView(state, viewModel)
			}
		}

		// Independent Bottom Log Dock (underneath Hierarchy / Topology / Preview / History)
		BottomLogDock(
			state = state,
			viewModel = viewModel,
		)
	}
}

@Composable
private fun HierarchyView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val model = state.previewModel

	val treeWidth = state.hierarchyWidth.dp
	val isTreeCollapsed = state.hierarchyCollapsed

	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var splitterCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var activeDrawOrderTarget by remember { mutableStateOf<DrawOrderDialogTarget?>(null) }

	Box(modifier = Modifier.fillMaxSize()) {
		Row(
			modifier = Modifier
				.fillMaxSize()
				.onGloballyPositioned { rowCoords = it },
		) {
			// Left: Hierarchy Tree (when expanded)
			if (!isTreeCollapsed) {
				Column(
					modifier = Modifier
						.width(treeWidth)
						.fillMaxHeight()
						.background(colors.panelBackground)
						.border(BorderStroke(1.dp, colors.divider)),
				) {
					// Tree Header
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.height(26.dp)
							.background(colors.panelElevated)
							.border(BorderStroke(1.dp, colors.divider))
							.padding(horizontal = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							Text(
								text = tr("canvas.hierarchy.root"),
								style = typography.header.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
								color = colors.textPrimary,
							)
							if (model != null) {
								val dCount = model.rig.puppet.deformers.size
								val lCount = model.rig.puppet.drawables.size
								Text(
									text = tr("canvas.hierarchy.stats", dCount, lCount),
									style = typography.caption.copy(fontSize = 9.5.sp),
									color = colors.textMuted,
								)
							}
						}
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							if (state.parentOverrides.isNotEmpty()) {
								Box(
									modifier = Modifier
										.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
										.clickable { viewModel.resetHierarchyOverrides() }
										.padding(2.dp),
									contentAlignment = Alignment.Center,
								) {
									IconReset(modifier = Modifier.size(11.dp), tint = colors.accent)
								}
							}
							// Collapse button in header
							Box(
								modifier = Modifier
									.size(18.dp)
									.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
									.clickable { viewModel.setHierarchyView(collapsed = true) },
								contentAlignment = Alignment.Center,
							) {
								IconChevron(expanded = true, modifier = Modifier.size(9.dp), tint = colors.textMuted)
							}
						}
					}

					if (model == null) {
						Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
							Text(
								text = tr("canvas.hierarchy.empty"),
								style = typography.caption.copy(fontSize = 11.sp),
								color = colors.textMuted,
								modifier = Modifier.padding(12.dp),
							)
						}
					} else {
						HierarchyTreeList(
							model = model,
							state = state,
							viewModel = viewModel,
							onRequestSetOrder = { targetId, name, currentOrder, defaultOrder, isOverridden ->
								activeDrawOrderTarget = DrawOrderDialogTarget(targetId, name, currentOrder, defaultOrder, isOverridden)
							},
						)
					}
				}

				// Resizable Splitter Handle
				Box(
					modifier = Modifier
						.width(4.dp)
						.fillMaxHeight()
						.background(colors.divider)
						.onGloballyPositioned { splitterCoords = it }
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
						.pointerInput(density) {
							awaitEachGesture {
								val down = awaitFirstDown()
								val grabOffset = down.position.x
								while (true) {
									val event = awaitPointerEvent()
									val change = event.changes.firstOrNull { it.id == down.id } ?: break
									if (!change.pressed) break
									change.consume()
									val row = rowCoords
									val splitter = splitterCoords
									if (row != null && splitter != null && row.isAttached && splitter.isAttached) {
										val mouseInRow = row.localPositionOf(splitter, change.position)
										val splitterLeftPx = mouseInRow.x - grabOffset
										val widthDp = with(density) { splitterLeftPx.toDp() }
										viewModel.setHierarchyView(width = widthDp.value.coerceIn(140f, 600f))
									}
								}
							}
						},
				)
			}

			// Right: 2D Canvas Viewport
			Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
				CanvasViewportComposable(
					mode = if (state.activeWorkspaceTab == WorkspaceTab.TOPOLOGY) WorkspaceTab.TOPOLOGY else WorkspaceTab.PREVIEW,
					state = state,
					viewModel = viewModel,
					modifier = Modifier.fillMaxSize(),
					onLayerClicked = { viewModel.selectLayer(it) },
				)

				// Ear Tab (外挂耳朵标签) when collapsed
				if (isTreeCollapsed) {
					Box(
						modifier = Modifier
							.align(Alignment.TopStart)
							.padding(top = 8.dp)
							.background(colors.panelElevated, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
							.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
							.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
							.clickable { viewModel.setHierarchyView(collapsed = false) }
							.padding(horizontal = 8.dp, vertical = 5.dp),
						contentAlignment = Alignment.Center,
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							IconChevron(expanded = false, modifier = Modifier.size(9.dp), tint = colors.accent)
							Text(
								text = tr("tab.hierarchy"),
								style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
								color = colors.textPrimary,
							)
						}
					}
				}
			}
		}

		// Modal Dialog for setting Draw Order
		if (activeDrawOrderTarget != null) {
			val target = activeDrawOrderTarget!!
			DrawOrderInputDialog(
				targetId = target.id,
				targetName = target.name,
				initialOrder = target.currentOrder,
				defaultOrder = target.defaultOrder,
				isOverridden = target.isOverridden,
				onConfirm = { newOrder ->
					viewModel.setLayerDrawOrder(target.id, newOrder)
					activeDrawOrderTarget = null
				},
				onReset = {
					viewModel.resetLayerDrawOrder(target.id)
					activeDrawOrderTarget = null
				},
				onDismiss = {
					activeDrawOrderTarget = null
				},
			)
		}
	}
}

private data class DrawOrderDialogTarget(
	val id: String,
	val name: String,
	val currentOrder: Float,
	val defaultOrder: Float,
	val isOverridden: Boolean,
)

private const val TREE_ROW_HEIGHT_DP = 20
private const val TREE_INDENT_STEP_DP = 10
private const val TREE_BASE_PADDING_DP = 4
private const val TREE_DOT_OFFSET_DP = 4
private const val TREE_CHEVRON_WIDTH_DP = 10

private data class CompactedDeformerChain(
	val deformers: List<Deformer>,
) {
	val head: Deformer get() = deformers.first()
	val tail: Deformer get() = deformers.last()
	val isChained: Boolean get() = deformers.size > 1
	val displayName: String get() = deformers.joinToString("\\") { it.name }
}

private fun resolveCompactedChain(
	start: Deformer,
	deformerChildrenMap: Map<String?, List<Deformer>>,
	drawableChildrenMap: Map<String?, List<org.umamo.runtime.model.Drawable>>,
): CompactedDeformerChain {
	val list = mutableListOf(start)
	var current = start
	while (true) {
		val cDefs = deformerChildrenMap[current.id.raw].orEmpty()
		val cDraws = drawableChildrenMap[current.id.raw].orEmpty()
		if (cDefs.size == 1 && cDraws.isEmpty()) {
			val next = cDefs.first()
			list.add(next)
			current = next
		} else {
			break
		}
	}
	return CompactedDeformerChain(list)
}

private fun isDescendantOf(deformerId: String, potentialAncestorId: String, deformers: List<Deformer>, parentOverrides: Map<String, String?>): Boolean {
	var current: String? = deformerId
	val deformerById = deformers.associateBy { it.id.raw }
	val visited = mutableSetOf<String>()
	while (current != null) {
		if (current == potentialAncestorId) return true
		if (!visited.add(current)) break
		current = parentOverrides[current] ?: deformerById[current]?.parent?.raw
	}
	return false
}

private data class ItemLayoutInfo(
	val id: String,
	val targetId: String,
	val name: String,
	val isDeformer: Boolean,
	val currentParentId: String?,
	val top: Float,
	val bottom: Float,
)

private class TreeDragState {
	var draggedItem by mutableStateOf<ItemLayoutInfo?>(null)
	var hoverTargetId by mutableStateOf<String?>(null)
	var isDragging by mutableStateOf(false)
	var isPressed by mutableStateOf(false)
	var pressPos by mutableStateOf(Offset.Zero)
	var currentMousePos by mutableStateOf(Offset.Zero)

	val draggedId: String? get() = draggedItem?.id

	fun onPress(item: ItemLayoutInfo, pos: Offset) {
		this.draggedItem = item
		this.pressPos = pos
		this.currentMousePos = pos
		this.hoverTargetId = null
		this.isDragging = false
		this.isPressed = true
	}

	fun onMove(pos: Offset, itemBounds: Collection<ItemLayoutInfo>, deformers: List<Deformer>, parentOverrides: Map<String, String?>) {
		if (!isPressed) return
		this.currentMousePos = pos
		if (!isDragging && (pos - pressPos).getDistance() > 4f) {
			isDragging = true
		}
		if (isDragging && draggedItem != null) {
			val dItem = draggedItem!!
			// Hit-test against all visible rows
			val hit = itemBounds.firstOrNull { pos.y >= it.top && pos.y <= it.bottom }
			if (hit != null) {
				if (hit.id == "ROOT") {
					hoverTargetId = "ROOT"
				} else if (hit.isDeformer) {
					// Check cycle prevention
					val canDrop = if (dItem.isDeformer) {
						hit.targetId != dItem.id && !isDescendantOf(hit.targetId, dItem.id, deformers, parentOverrides)
					} else {
						true
					}
					hoverTargetId = if (canDrop) hit.targetId else null
				} else {
					hoverTargetId = null
				}
			} else {
				// If not over any item: check if below all items
				val maxBottom = itemBounds.maxOfOrNull { it.bottom } ?: 0f
				if (pos.y > maxBottom || pos.y < 35f) {
					hoverTargetId = "ROOT"
				} else {
					hoverTargetId = null
				}
			}
		}
	}

	fun onRelease(viewModel: PSD2LiveViewModel, onSelectItem: (ItemLayoutInfo) -> Unit) {
		val item = draggedItem
		val target = hoverTargetId
		val wasDragging = isDragging

		if (wasDragging && item != null && target != null) {
			val newParent = if (target == "ROOT") null else target
			if (newParent != item.currentParentId) {
				viewModel.reparentItem(item.id, newParent)
			}
		} else if (!wasDragging && item != null) {
			onSelectItem(item)
		}
		clear()
	}

	fun clear() {
		draggedItem = null
		hoverTargetId = null
		isDragging = false
		isPressed = false
		pressPos = Offset.Zero
		currentMousePos = Offset.Zero
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HierarchyTreeList(
	model: RigPreviewModel,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	onRequestSetOrder: ((targetId: String, name: String, currentOrder: Float, defaultOrder: Float, isOverridden: Boolean) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val expandedMap = remember(model) {
		mutableStateMapOf<String, Boolean>().apply {
			model.rig.puppet.deformers.forEach { put(it.id.raw, true) }
		}
	}

	val searchQuery = state.hierarchySearch
	val treeDragState = remember { TreeDragState() }
	val itemBoundsMap = remember { mutableStateMapOf<String, ItemLayoutInfo>() }
	var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

	val deformers = model.rig.puppet.deformers
	val drawables = model.rig.puppet.drawables

	val deformerChildrenMap = remember(deformers, state.parentOverrides) {
		deformers.groupBy { it.parent?.raw }
	}
	val drawableChildrenMap = remember(drawables, state.parentOverrides) {
		drawables.groupBy { it.parentDeformerId?.raw }
	}

	val rootDeformers = deformers.filter { it.parent == null }
	val rootDrawables = drawables.filter { it.parentDeformerId == null }

	// Resolve compacted chains for root deformers
	val rootChains = remember(rootDeformers, deformerChildrenMap, drawableChildrenMap) {
		rootDeformers.map { resolveCompactedChain(it, deformerChildrenMap, drawableChildrenMap) }
	}

	val scrollState = rememberScrollState()

	Column(modifier = Modifier.fillMaxSize()) {
		// Visibility & Deformer Overlay Controls (merged from canvas viewport)
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 6.dp, vertical = 5.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			// Row 1: Visibility Channels (Warp, Mesh, Texture)
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				CompactToggleChip(
					text = tr("canvas.visibility.warp"),
					selected = state.showWarp,
					onToggle = { viewModel.setShowWarp(!state.showWarp) },
					modifier = Modifier.weight(1f),
				)
				CompactToggleChip(
					text = tr("canvas.visibility.mesh"),
					selected = state.showMesh,
					onToggle = { viewModel.setShowMesh(!state.showMesh) },
					modifier = Modifier.weight(1f),
				)
				CompactToggleChip(
					text = tr("canvas.visibility.texture"),
					selected = state.showTexture,
					onToggle = { viewModel.setShowTexture(!state.showTexture) },
					modifier = Modifier.weight(1f),
				)
			}

			// Row 2: Deformer & Filter Sub-options (Selected Only, Names, Indices)
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				CompactToggleChip(
					text = tr("canvas.information.selectedOnly"),
					selected = state.filterSelectedOnly,
					onToggle = { viewModel.setFilterSelectedOnly(!state.filterSelectedOnly) },
					modifier = Modifier.weight(1.15f),
				)
				CompactToggleChip(
					text = tr("canvas.information.names"),
					selected = state.warpShowNames,
					onToggle = { viewModel.setWarpShowNames(!state.warpShowNames) },
					enabled = state.showWarp,
					modifier = Modifier.weight(0.9f),
				)
				CompactToggleChip(
					text = tr("canvas.information.indices"),
					selected = state.warpShowIndices,
					onToggle = { viewModel.setWarpShowIndices(!state.warpShowIndices) },
					enabled = state.showWarp,
					modifier = Modifier.weight(0.95f),
				)
			}
		}

		// Search & Expand/Collapse toolbar
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(26.dp)
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 6.dp, vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			CompactTextField(
				value = searchQuery,
				onValueChange = { viewModel.setHierarchyView(search = it) },
				placeholder = tr("canvas.hierarchy.search"),
				modifier = Modifier.weight(1f),
				height = 20.dp,
				leadingIcon = { IconSearch(modifier = Modifier.size(10.dp), tint = colors.textMuted) },
				trailingIcon = if (searchQuery.isNotEmpty()) {
					{
						Box(
							modifier = Modifier
								.size(14.dp)
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
								.clickable { viewModel.setHierarchyView(search = "") },
							contentAlignment = Alignment.Center,
						) {
							IconClose(modifier = Modifier.size(8.dp), tint = colors.textMuted)
						}
					}
				} else null,
			)

			CompactButton(
				text = tr("canvas.hierarchy.expandAll"),
				onClick = { deformers.forEach { expandedMap[it.id.raw] = true } },
				height = 20.dp,
			)
			CompactButton(
				text = tr("canvas.hierarchy.collapseAll"),
				onClick = { deformers.forEach { expandedMap[it.id.raw] = false } },
				height = 20.dp,
			)
		}

		// Tree Body with Container Hit-Testing & Overlay and Draw Order Ruler
		Row(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
		) {
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxHeight()
				.onGloballyPositioned { containerCoordinates = it }
				.onPointerEvent(PointerEventType.Move) { event ->
					val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
					treeDragState.onMove(pos, itemBoundsMap.values, deformers, state.parentOverrides)
				}
				.onPointerEvent(PointerEventType.Release) { event ->
					if (event.button == PointerButton.Primary && treeDragState.isPressed) {
						treeDragState.onRelease(viewModel) { clickedItem ->
							if (clickedItem.isDeformer) {
								if (state.selectedDeformerId == clickedItem.targetId) {
									viewModel.selectDeformer(null)
								} else {
									viewModel.selectDeformer(clickedItem.targetId)
								}
							} else {
								if (state.selectedLayerId == clickedItem.id) {
									viewModel.selectLayer(null)
								} else {
									viewModel.selectLayer(clickedItem.id)
								}
							}
						}
					}
				}
				.pointerHoverIcon(
					PointerIcon(
						if (treeDragState.isDragging) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
						else Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
					)
				),
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(scrollState)
					.padding(vertical = 2.dp),
			) {
				// Root drop zone banner (visible while dragging)
				val isRootTarget = treeDragState.isDragging && treeDragState.hoverTargetId == "ROOT"
				if (treeDragState.isDragging) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 6.dp, vertical = 2.dp)
							.background(
								if (isRootTarget) colors.accent.copy(alpha = 0.28f) else colors.panelElevated.copy(alpha = 0.5f),
								RoundedCornerShape(3.dp),
							)
							.border(
								BorderStroke(1.2.dp, if (isRootTarget) colors.accent else colors.divider),
								RoundedCornerShape(3.dp),
							)
							.onGloballyPositioned { bannerCoords ->
								val parent = containerCoordinates
								if (parent != null && parent.isAttached && bannerCoords.isAttached) {
									val topLeft = parent.localPositionOf(bannerCoords, Offset.Zero)
									itemBoundsMap["ROOT"] = ItemLayoutInfo(
										id = "ROOT",
										targetId = "ROOT",
										name = tr("canvas.hierarchy.root"),
										isDeformer = true,
										currentParentId = null,
										top = topLeft.y,
										bottom = topLeft.y + bannerCoords.size.height,
									)
								}
							}
							.padding(horizontal = 8.dp, vertical = 3.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							Box(
								modifier = Modifier
									.size(6.dp)
									.background(if (isRootTarget) colors.accent else colors.textMuted, CircleShape),
							)
							Text(
								text = if (isRootTarget) tr("canvas.hierarchy.dropToRoot") else tr("canvas.hierarchy.root"),
								style = typography.caption.copy(
									fontSize = 10.5.sp,
									fontWeight = if (isRootTarget) FontWeight.Bold else FontWeight.Medium,
								),
								color = if (isRootTarget) colors.accent else colors.textPrimary,
							)
						}
						if (isRootTarget) {
							Text(
								text = "↳ ${tr("canvas.hierarchy.moveToRoot")}",
								style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
								color = colors.accent,
							)
						}
					}
				}

				for ((index, chain) in rootChains.withIndex()) {
					val isLast = index == rootChains.lastIndex && rootDrawables.isEmpty()
					DeformerTreeItem(
						chain = chain,
						depth = 0,
						isLastChild = isLast,
						ancestorHasNextSibling = emptyList(),
						model = model,
						state = state,
						viewModel = viewModel,
						expandedMap = expandedMap,
						deformerChildrenMap = deformerChildrenMap,
						drawableChildrenMap = drawableChildrenMap,
						treeDragState = treeDragState,
						containerCoordinates = containerCoordinates,
						itemBoundsMap = itemBoundsMap,
						searchQuery = searchQuery,
						onRequestSetOrder = onRequestSetOrder,
					)
				}
				for ((index, drawable) in rootDrawables.withIndex()) {
					val isLast = index == rootDrawables.lastIndex
					DrawableTreeItem(
						drawable = drawable,
						depth = 0,
						isLastChild = isLast,
						ancestorHasNextSibling = emptyList(),
						model = model,
						state = state,
						viewModel = viewModel,
						treeDragState = treeDragState,
						containerCoordinates = containerCoordinates,
						itemBoundsMap = itemBoundsMap,
						searchQuery = searchQuery,
						onRequestSetOrder = onRequestSetOrder,
					)
				}

				// Empty area at the bottom also accepts dropping onto ROOT
				Spacer(
					modifier = Modifier
						.fillMaxWidth()
						.height(60.dp),
				)
			}

			// Floating drag preview avatar (follows mouse cursor directly, NO bottom banner jitter)
			if (treeDragState.isDragging && treeDragState.draggedItem != null) {
				val dragItem = treeDragState.draggedItem!!
				val hoverTarget = treeDragState.hoverTargetId
				val hoverLabel = when (hoverTarget) {
					null -> "⊘"
					"ROOT" -> "→ ${tr("canvas.hierarchy.root")}"
					else -> "→ " + (deformers.find { it.id.raw == hoverTarget }?.name ?: hoverTarget)
				}
				val isValid = hoverTarget != null

				Box(
					modifier = Modifier
						.offset {
							IntOffset(
								x = (treeDragState.currentMousePos.x + 14).roundToInt(),
								y = (treeDragState.currentMousePos.y + 14).roundToInt(),
							)
						}
						.background(colors.panelElevated, RoundedCornerShape(4.dp))
						.border(
							BorderStroke(1.dp, if (isValid) colors.accent else colors.textMuted),
							RoundedCornerShape(4.dp),
						)
						.padding(horizontal = 8.dp, vertical = 4.dp),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Box(
							modifier = Modifier
								.size(6.dp)
								.background(
									if (dragItem.isDeformer) colors.accent else colors.textPrimary,
									if (dragItem.isDeformer) CircleShape else RoundedCornerShape(1.dp),
								),
						)
						Text(
							text = dragItem.name,
							style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
							color = colors.textPrimary,
							maxLines = 1,
						)
						Text(
							text = hoverLabel,
							style = typography.caption.copy(
								fontSize = 10.sp,
								fontWeight = FontWeight.Bold,
							),
							color = if (isValid) colors.accent else colors.error,
							maxLines = 1,
						)
					}
				}
			}
		}

		// Draw Order Ruler
		DrawOrderRuler(
			model = model,
			state = state,
			viewModel = viewModel,
			onRequestSetOrder = onRequestSetOrder,
		)
	}
}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeformerTreeItem(
	chain: CompactedDeformerChain,
	depth: Int,
	isLastChild: Boolean,
	ancestorHasNextSibling: List<Boolean>,
	model: RigPreviewModel,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	expandedMap: MutableMap<String, Boolean>,
	deformerChildrenMap: Map<String?, List<Deformer>>,
	drawableChildrenMap: Map<String?, List<org.umamo.runtime.model.Drawable>>,
	treeDragState: TreeDragState,
	containerCoordinates: LayoutCoordinates?,
	itemBoundsMap: MutableMap<String, ItemLayoutInfo>,
	searchQuery: String = "",
	onRequestSetOrder: ((targetId: String, name: String, currentOrder: Float, defaultOrder: Float, isOverridden: Boolean) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val headDeformer = chain.head
	val tailDeformer = chain.tail
	val headId = headDeformer.id.raw
	val tailId = tailDeformer.id.raw

	val isExpanded = expandedMap[headId] ?: true
	val isSelected = chain.deformers.any { it.id.raw == state.selectedDeformerId }
	val type = if (tailDeformer is Deformer.Warp) "Warp" else "Rotation"

	val childDeformers = deformerChildrenMap[tailId].orEmpty()
	val childDrawables = drawableChildrenMap[tailId].orEmpty()
	val hasChildren = childDeformers.isNotEmpty() || childDrawables.isNotEmpty()

	// Compacted child chains
	val childChains = remember(childDeformers, deformerChildrenMap, drawableChildrenMap) {
		childDeformers.map { resolveCompactedChain(it, deformerChildrenMap, drawableChildrenMap) }
	}

	val matchesQuery = searchQuery.isBlank() || chain.deformers.any { it.name.contains(searchQuery, ignoreCase = true) }
	val hasMatchingChild = searchQuery.isNotBlank() && (
		childDeformers.any { it.name.contains(searchQuery, ignoreCase = true) } ||
		childDrawables.any { it.name.contains(searchQuery, ignoreCase = true) }
	)
	if (searchQuery.isNotBlank() && !matchesQuery && !hasMatchingChild) {
		return
	}
	if (hasMatchingChild && !isExpanded) {
		chain.deformers.forEach { expandedMap[it.id.raw] = true }
	}

	val guideColor = Color(0xFFE4E7EC).copy(alpha = 0.42f)
	val activeGuideColor = if (isSelected) colors.selectionText.copy(alpha = 0.9f) else guideColor

	val isCurrentDragged = treeDragState.isDragging && treeDragState.draggedId == headId
	val isHoverTarget = treeDragState.isDragging && treeDragState.hoverTargetId == tailId

	var isHovered by remember { mutableStateOf(false) }
	var showMenu by remember { mutableStateOf(false) }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	val startX = (TREE_BASE_PADDING_DP + depth * TREE_INDENT_STEP_DP).dp

	Box(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(TREE_ROW_HEIGHT_DP.dp)
				.background(
					when {
						isHoverTarget -> colors.accent.copy(alpha = 0.28f)
						isSelected -> colors.selection
						isCurrentDragged -> colors.panelElevated.copy(alpha = 0.45f)
						isHovered -> colors.controlHover.copy(alpha = 0.3f)
						else -> Color.Transparent
					}
				)
				.then(
					if (isHoverTarget) {
						Modifier.border(BorderStroke(1.2.dp, colors.accent), RoundedCornerShape(2.dp))
					} else Modifier
				)
				.onGloballyPositioned { coords ->
					rowCoords = coords
					val parent = containerCoordinates
					if (parent != null && parent.isAttached && coords.isAttached) {
						val topLeft = parent.localPositionOf(coords, Offset.Zero)
						itemBoundsMap[tailId] = ItemLayoutInfo(
							id = headId,
							targetId = tailId,
							name = chain.displayName,
							isDeformer = true,
							currentParentId = headDeformer.parent?.raw,
							top = topLeft.y,
							bottom = topLeft.y + coords.size.height,
						)
					}
				}
				.drawBehind {
					val midY = size.height * 0.5f

					// Ancestor guidelines
					for (a in 0 until depth - 1) {
						if (a < ancestorHasNextSibling.size && ancestorHasNextSibling[a]) {
							val ancDotX = (TREE_BASE_PADDING_DP + a * TREE_INDENT_STEP_DP + TREE_DOT_OFFSET_DP).dp.toPx()
							drawLine(
								color = guideColor,
								start = Offset(ancDotX, 0f),
								end = Offset(ancDotX, size.height),
								strokeWidth = 1.2f,
							)
						}
					}

					// Immediate parent connection
					if (depth > 0) {
						val parentDotX = (TREE_BASE_PADDING_DP + (depth - 1) * TREE_INDENT_STEP_DP + TREE_DOT_OFFSET_DP).dp.toPx()
						val verticalEndY = if (isLastChild) midY else size.height

						// Vertical trunk (terminates at midY for last child forming 'L')
						drawLine(
							color = guideColor,
							start = Offset(parentDotX, 0f),
							end = Offset(parentDotX, verticalEndY),
							strokeWidth = 1.2f,
						)

						// Horizontal branch into dot
						val branchEndX = (startX + 1.dp).toPx()
						drawLine(
							color = activeGuideColor,
							start = Offset(parentDotX, midY),
							end = Offset(branchEndX, midY),
							strokeWidth = 1.2f,
						)
					}

					// When expanded with children, draw line from bottom of dot down to row bottom
					if (isExpanded && hasChildren) {
						val myDotX = (TREE_BASE_PADDING_DP + depth * TREE_INDENT_STEP_DP + TREE_DOT_OFFSET_DP).dp.toPx()
						drawLine(
							color = guideColor,
							start = Offset(myDotX, midY + 3.dp.toPx()),
							end = Offset(myDotX, size.height),
							strokeWidth = 1.2f,
						)
					}
				}
				.onPointerEvent(PointerEventType.Enter) { isHovered = true }
				.onPointerEvent(PointerEventType.Exit) { isHovered = false }
				.onPointerEvent(PointerEventType.Press) { event ->
					if (event.button == PointerButton.Secondary) {
						showMenu = true
					} else if (event.button == PointerButton.Primary) {
						val parent = containerCoordinates
						val coords = rowCoords
						if (parent != null && coords != null && parent.isAttached && coords.isAttached) {
							val localPos = event.changes.firstOrNull()?.position ?: Offset.Zero
							val containerPos = parent.localPositionOf(coords, localPos)
							val info = ItemLayoutInfo(
								id = headId,
								targetId = tailId,
								name = chain.displayName,
								isDeformer = true,
								currentParentId = headDeformer.parent?.raw,
								top = containerPos.y - localPos.y,
								bottom = containerPos.y - localPos.y + coords.size.height,
							)
							treeDragState.onPress(info, containerPos)
						}
					}
				}
				.padding(start = startX, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// 1. Deformer Dot Icon (aligned directly with the vertical guideline)
			Spacer(Modifier.width(1.dp))
			val dotAwt = ComponentPalette.strong(tailId)
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(Color(dotAwt.red, dotAwt.green, dotAwt.blue), CircleShape),
			)

			// 2. Folding symbol (BEHIND the dot, in BLUE, with spacing)
			Spacer(Modifier.width(2.dp))
			Box(
				modifier = Modifier
					.size(TREE_CHEVRON_WIDTH_DP.dp)
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.clickable(enabled = hasChildren) {
						treeDragState.clear()
						val nextExpanded = !isExpanded
						chain.deformers.forEach { expandedMap[it.id.raw] = nextExpanded }
					},
				contentAlignment = Alignment.Center,
			) {
				if (hasChildren) {
					val chevronTint = if (isSelected) colors.selectionText else colors.accent
					IconChevron(expanded = isExpanded, modifier = Modifier.size(7.dp), tint = chevronTint)
				}
			}
			Spacer(Modifier.width(2.dp))

			// 3. Deformer Chain Display Name with distinctly blue fold separator '\' and surrounding spaces
			val isDeformerVis = chain.deformers.all { state.isDeformerVisible(it.id.raw) }
			val annotatedDisplayName = remember(chain, isSelected, isDeformerVis, matchesQuery, searchQuery, colors) {
				buildAnnotatedString {
					val isHighlightQuery = matchesQuery && searchQuery.isNotEmpty()
					val textColor = when {
						!isDeformerVis -> colors.textDisabled
						isSelected -> colors.selectionText
						isHighlightQuery -> colors.accent
						else -> colors.textPrimary
					}
					val slashColor = if (!isDeformerVis) colors.textDisabled else if (isSelected) colors.selectionText else colors.accent

					chain.deformers.forEachIndexed { index, def ->
						if (index > 0) {
							withStyle(SpanStyle(color = slashColor, fontWeight = FontWeight.Normal)) {
								append(" \\ ")
							}
						}
						withStyle(
							SpanStyle(
								color = textColor,
								fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
							)
						) {
							append(def.name)
						}
					}
				}
			}

			Text(
				text = annotatedDisplayName,
				style = typography.body.copy(fontSize = 11.sp),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)

			Text(
				text = "[$type]",
				style = typography.monoSmall.copy(fontSize = 9.sp),
				color = colors.textMuted,
			)

			Spacer(Modifier.width(4.dp))
			Box(
				modifier = Modifier
					.size(16.dp)
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.clickable {
						treeDragState.clear()
						val nextVis = !isDeformerVis
						chain.deformers.forEach { viewModel.setDeformerVisibility(it.id.raw, nextVis) }
					},
				contentAlignment = Alignment.Center,
			) {
				IconEye(
					visible = isDeformerVis,
					modifier = Modifier.size(12.dp),
					tint = if (isDeformerVis) (if (isSelected) colors.selectionText else colors.textMuted) else colors.textDisabled.copy(alpha = 0.5f),
				)
			}
		}

		// Streamlined Context menu
		DropdownMenu(
			expanded = showMenu,
			onDismissRequest = { showMenu = false },
			modifier = Modifier
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.border))
				.widthIn(min = 160.dp, max = 220.dp),
		) {
			val isAlreadyRoot = headDeformer.parent == null
			DropdownMenuItem(
				onClick = {
					viewModel.reparentItem(headId, null)
					showMenu = false
				},
				enabled = !isAlreadyRoot,
			) {
				Text(
					tr("canvas.hierarchy.moveToRoot"),
					style = typography.body.copy(fontSize = 11.sp),
					color = if (!isAlreadyRoot) colors.textPrimary else colors.textDisabled,
				)
			}

			if (chain.deformers.any { state.parentOverrides.containsKey(it.id.raw) }) {
				DropdownMenuItem(onClick = {
					chain.deformers.forEach { viewModel.resetItemHierarchy(it.id.raw) }
					showMenu = false
				}) {
					Text(tr("canvas.hierarchy.resetItem"), style = typography.body.copy(fontSize = 11.sp), color = colors.accent)
				}
			}

			Divider(color = colors.divider.copy(alpha = 0.5f), thickness = 0.5.dp)

			DropdownMenuItem(onClick = {
				chain.deformers.forEach { expandedMap[it.id.raw] = true }
				fun expandRecursive(dId: String) {
					expandedMap[dId] = true
					deformerChildrenMap[dId]?.forEach { expandRecursive(it.id.raw) }
				}
				expandRecursive(tailId)
				showMenu = false
			}) {
				Text(tr("canvas.hierarchy.expandBranch"), style = typography.body.copy(fontSize = 11.sp), color = colors.textPrimary)
			}

			DropdownMenuItem(onClick = {
				chain.deformers.forEach { expandedMap[it.id.raw] = false }
				showMenu = false
			}) {
				Text(tr("canvas.hierarchy.collapseBranch"), style = typography.body.copy(fontSize = 11.sp), color = colors.textPrimary)
			}
		}
	}

	// Render children recursively
	if (isExpanded) {
		val totalChildren = childChains.size + childDrawables.size
		for ((cIndex, childChain) in childChains.withIndex()) {
			val isLast = (cIndex == childChains.lastIndex && childDrawables.isEmpty())
			val nextAncestors = ancestorHasNextSibling + (!isLast)
			DeformerTreeItem(
				chain = childChain,
				depth = depth + 1,
				isLastChild = isLast,
				ancestorHasNextSibling = nextAncestors,
				model = model,
				state = state,
				viewModel = viewModel,
				expandedMap = expandedMap,
				deformerChildrenMap = deformerChildrenMap,
				drawableChildrenMap = drawableChildrenMap,
				treeDragState = treeDragState,
				containerCoordinates = containerCoordinates,
				itemBoundsMap = itemBoundsMap,
				searchQuery = searchQuery,
				onRequestSetOrder = onRequestSetOrder,
			)
		}
		for ((dIndex, childDrawable) in childDrawables.withIndex()) {
			val isLast = (dIndex == childDrawables.lastIndex)
			val nextAncestors = ancestorHasNextSibling + (!isLast)
			DrawableTreeItem(
				drawable = childDrawable,
				depth = depth + 1,
				isLastChild = isLast,
				ancestorHasNextSibling = nextAncestors,
				model = model,
				state = state,
				viewModel = viewModel,
				treeDragState = treeDragState,
				containerCoordinates = containerCoordinates,
				itemBoundsMap = itemBoundsMap,
				searchQuery = searchQuery,
				onRequestSetOrder = onRequestSetOrder,
			)
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DrawableTreeItem(
	drawable: org.umamo.runtime.model.Drawable,
	depth: Int,
	isLastChild: Boolean,
	ancestorHasNextSibling: List<Boolean>,
	model: RigPreviewModel,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	treeDragState: TreeDragState,
	containerCoordinates: LayoutCoordinates?,
	itemBoundsMap: MutableMap<String, ItemLayoutInfo>,
	searchQuery: String = "",
	onRequestSetOrder: ((targetId: String, name: String, currentOrder: Float, defaultOrder: Float, isOverridden: Boolean) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
	val itemId = layerId ?: drawable.id.raw
	val isLayerSelected = layerId != null && state.selectedLayerId == layerId
	val isSelfVisible = layerId == null || state.isLayerVisible(layerId)
	val isEffectiveVisible = layerId == null || layerId in state.effectiveVisibleLayerIds

	val matchesQuery = searchQuery.isBlank() || drawable.name.contains(searchQuery, ignoreCase = true)
	if (searchQuery.isNotBlank() && !matchesQuery) {
		return
	}

	val guideColor = Color(0xFFE4E7EC).copy(alpha = 0.42f)
	val activeGuideColor = if (isLayerSelected) colors.selectionText.copy(alpha = 0.9f) else guideColor

	val isCurrentDragged = treeDragState.isDragging && treeDragState.draggedId == itemId

	var isHovered by remember { mutableStateOf(false) }
	var showMenu by remember { mutableStateOf(false) }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	val startX = (TREE_BASE_PADDING_DP + depth * TREE_INDENT_STEP_DP).dp

	Box(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(TREE_ROW_HEIGHT_DP.dp)
				.background(
					when {
						isLayerSelected -> colors.selection
						isCurrentDragged -> colors.panelElevated.copy(alpha = 0.45f)
						isHovered -> colors.controlHover.copy(alpha = 0.3f)
						else -> Color.Transparent
					}
				)
				.onGloballyPositioned { coords ->
					rowCoords = coords
					val parent = containerCoordinates
					if (parent != null && parent.isAttached && coords.isAttached) {
						val topLeft = parent.localPositionOf(coords, Offset.Zero)
						itemBoundsMap[itemId] = ItemLayoutInfo(
							id = itemId,
							targetId = itemId,
							name = drawable.name,
							isDeformer = false,
							currentParentId = drawable.parentDeformerId?.raw,
							top = topLeft.y,
							bottom = topLeft.y + coords.size.height,
						)
					}
				}
				.drawBehind {
					val midY = size.height * 0.5f

					// Ancestor guidelines
					for (a in 0 until depth - 1) {
						if (a < ancestorHasNextSibling.size && ancestorHasNextSibling[a]) {
							val ancDotX = (TREE_BASE_PADDING_DP + a * TREE_INDENT_STEP_DP + TREE_DOT_OFFSET_DP).dp.toPx()
							drawLine(
								color = guideColor,
								start = Offset(ancDotX, 0f),
								end = Offset(ancDotX, size.height),
								strokeWidth = 1.2f,
							)
						}
					}

					// Immediate parent connection
					if (depth > 0) {
						val parentDotX = (TREE_BASE_PADDING_DP + (depth - 1) * TREE_INDENT_STEP_DP + TREE_DOT_OFFSET_DP).dp.toPx()
						val verticalEndY = if (isLastChild) midY else size.height

						// Vertical trunk (stops at midY for last child forming 'L')
						drawLine(
							color = guideColor,
							start = Offset(parentDotX, 0f),
							end = Offset(parentDotX, verticalEndY),
							strokeWidth = 1.2f,
						)

						// Horizontal branch into square dot
						val branchEndX = (startX + 1.dp).toPx()
						drawLine(
							color = activeGuideColor,
							start = Offset(parentDotX, midY),
							end = Offset(branchEndX, midY),
							strokeWidth = 1.2f,
						)
					}
				}
				.onPointerEvent(PointerEventType.Enter) { isHovered = true }
				.onPointerEvent(PointerEventType.Exit) { isHovered = false }
				.onPointerEvent(PointerEventType.Press) { event ->
					if (event.button == PointerButton.Secondary) {
						showMenu = true
					} else if (event.button == PointerButton.Primary) {
						val parent = containerCoordinates
						val coords = rowCoords
						if (parent != null && coords != null && parent.isAttached && coords.isAttached) {
							val localPos = event.changes.firstOrNull()?.position ?: Offset.Zero
							val containerPos = parent.localPositionOf(coords, localPos)
							val info = ItemLayoutInfo(
								id = itemId,
								targetId = itemId,
								name = drawable.name,
								isDeformer = false,
								currentParentId = drawable.parentDeformerId?.raw,
								top = containerPos.y - localPos.y,
								bottom = containerPos.y - localPos.y + coords.size.height,
							)
							treeDragState.onPress(info, containerPos)
						}
					}
				}
				.padding(start = startX, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// 1. Square Dot Icon (aligned directly with deformer dot at startX + 1.dp)
			Spacer(Modifier.width(1.dp))
			val layerDotAwt = if (layerId != null) ComponentPalette.strong(layerId) else java.awt.Color.GRAY
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(Color(layerDotAwt.red, layerDotAwt.green, layerDotAwt.blue), RoundedCornerShape(1.dp)),
			)

			// Spacer matching the Chevron slot (2.dp + 10.dp + 2.dp = 14.dp) so layer text aligns with deformer text
			Spacer(Modifier.width(14.dp))

			Text(
				text = drawable.name,
				style = typography.body.copy(fontSize = 11.sp),
				color = if (isEffectiveVisible) (if (isLayerSelected) colors.selectionText else (if (matchesQuery && searchQuery.isNotEmpty()) colors.accent else colors.textPrimary)) else colors.textDisabled,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)

			// Delete Layer Icon Button on Hover or when Selected
			if ((isHovered || isLayerSelected) && layerId != null) {
				Box(
					modifier = Modifier
						.size(16.dp)
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
						.clickable {
							treeDragState.clear()
							viewModel.deleteLayer(layerId)
						}
						.padding(1.dp),
					contentAlignment = Alignment.Center,
				) {
					IconTrash(
						modifier = Modifier.size(11.dp),
						tint = colors.error.copy(alpha = 0.85f),
					)
				}
			}

			// Visibility Eye icon
			if (layerId != null) {
				Spacer(Modifier.width(4.dp))
				Box(
					modifier = Modifier
						.size(16.dp)
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
						.clickable {
							treeDragState.clear()
							viewModel.toggleLayerVisibility(layerId)
						},
					contentAlignment = Alignment.Center,
				) {
					IconEye(
						visible = isSelfVisible,
						modifier = Modifier.size(12.dp),
						tint = if (isSelfVisible) (if (!isEffectiveVisible) colors.textDisabled else if (isLayerSelected) colors.selectionText else colors.textMuted) else colors.textDisabled.copy(alpha = 0.5f),
					)
				}
			}
		}

		// Streamlined Context Menu
		DropdownMenu(
			expanded = showMenu,
			onDismissRequest = { showMenu = false },
			modifier = Modifier
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.border))
				.widthIn(min = 160.dp, max = 220.dp),
		) {
			val isAlreadyRoot = drawable.parentDeformerId == null

			DropdownMenuItem(
				onClick = {
					viewModel.reparentItem(itemId, null)
					showMenu = false
				},
				enabled = !isAlreadyRoot,
			) {
				Text(
					tr("canvas.hierarchy.moveToRoot"),
					style = typography.body.copy(fontSize = 11.sp),
					color = if (!isAlreadyRoot) colors.textPrimary else colors.textDisabled,
				)
			}

			if (state.parentOverrides.containsKey(itemId)) {
				DropdownMenuItem(onClick = {
					viewModel.resetItemHierarchy(itemId)
					showMenu = false
				}) {
					Text(tr("canvas.hierarchy.resetItem"), style = typography.body.copy(fontSize = 11.sp), color = colors.accent)
				}
			}

			if (layerId != null) {
				Divider(color = colors.divider.copy(alpha = 0.5f), thickness = 0.5.dp)

				val effectiveOrder = state.getEffectiveDrawOrder(drawable.id.raw, layerId, drawable.drawOrder)
				val isOverridden = state.drawOrderOverrides.containsKey(layerId) || state.drawOrderOverrides.containsKey(drawable.id.raw)

				DropdownMenuItem(onClick = {
					showMenu = false
					onRequestSetOrder?.invoke(layerId, drawable.name, effectiveOrder, drawable.drawOrder, isOverridden)
				}) {
					Text(tr("canvas.hierarchy.setDrawOrder"), style = typography.body.copy(fontSize = 11.sp), color = colors.textPrimary)
				}

				if (isOverridden) {
					DropdownMenuItem(onClick = {
						viewModel.resetLayerDrawOrder(layerId)
						showMenu = false
					}) {
						Text(tr("canvas.drawOrder.reset"), style = typography.body.copy(fontSize = 11.sp), color = colors.accent)
					}
				}

				DropdownMenuItem(onClick = {
					viewModel.deleteLayer(layerId)
					showMenu = false
				}) {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						IconTrash(modifier = Modifier.size(11.dp), tint = colors.error)
						Text(tr("canvas.hierarchy.deleteLayer"), style = typography.body.copy(fontSize = 11.sp), color = colors.error)
					}
				}
				DropdownMenuItem(onClick = {
					viewModel.toggleLayerVisibility(layerId)
					showMenu = false
				}) {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						IconEye(visible = !isSelfVisible, modifier = Modifier.size(12.dp), tint = colors.textMuted)
						Text(if (isSelfVisible) tr("canvas.hierarchy.hideLayer") else tr("canvas.hierarchy.showLayer"), style = typography.body.copy(fontSize = 11.sp), color = colors.textPrimary)
					}
				}
			}
		}
	}
}
