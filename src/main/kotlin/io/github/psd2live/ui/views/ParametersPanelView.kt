package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ParameterKeyMarks
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconDragHandle
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconMoveToRoot
import io.github.psd2live.ui.components.IconParameterLink
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.components.SliderKeyMark
import io.github.psd2live.ui.components.SliderKeyShape
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspaceTabKind
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.edit.materializedParameterTree
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One visible row in the parameter panel (folder header, single slider, or combined 2D pad). */
private sealed interface ParameterPanelRow {
	data class Folder(
		val group: ParameterNode.Group,
		val depth: Int,
		val open: Boolean,
		val parentGroupId: String?,
		val descendantGroupIds: Set<String>,
	) : ParameterPanelRow

	data class Single(
		val parameter: Parameter,
		val depth: Int,
		val parentGroupId: String?,
		val nextSiblingParam: Parameter?,
	) : ParameterPanelRow

	data class Linked(
		val horizontal: Parameter,
		val vertical: Parameter,
		val depth: Int,
		val parentGroupId: String?,
	) : ParameterPanelRow
}

/** Layout bounds of one parameter-panel row, in the drag container's local coordinates. */
private data class ParamItemLayout(
	val key: String,
	val id: String,
	val kind: String,
	val name: String,
	val parentGroupId: String?,
	val isFolder: Boolean,
	val descendantGroupIds: Set<String>,
	val top: Float,
	val bottom: Float,
)

/**
 * Drop destination resolved while dragging — mirrors hierarchy-tree press→threshold→hit-test→release,
 * extended with before/after insert lines for sibling reorder.
 */
private sealed interface ParamDropTarget {
	data object Root : ParamDropTarget
	data class Nest(val folderId: String, val folderName: String) : ParamDropTarget
	data class Before(
		val id: String,
		val kind: String,
		val parentGroupId: String?,
		val label: String,
	) : ParamDropTarget
	data class Append(val parentGroupId: String?, val label: String) : ParamDropTarget
}

/** Hierarchy-tree-style drag state for the parameter panel. */
private class ParameterDragState {
	var draggedItem by mutableStateOf<ParamItemLayout?>(null)
	var dropTarget by mutableStateOf<ParamDropTarget?>(null)
	var isDragging by mutableStateOf(false)
	var isPressed by mutableStateOf(false)
	var pressPos by mutableStateOf(Offset.Zero)
	var currentMousePos by mutableStateOf(Offset.Zero)
	/** True after a gesture that crossed the drag threshold — suppresses the following click. */
	var suppressClick by mutableStateOf(false)

	val draggedKey: String? get() = draggedItem?.key

	fun onPress(item: ParamItemLayout, pos: Offset) {
		draggedItem = item
		pressPos = pos
		currentMousePos = pos
		dropTarget = null
		isDragging = false
		isPressed = true
		suppressClick = false
	}

	fun onMove(pos: Offset, itemBounds: Collection<ParamItemLayout>) {
		if (!isPressed) return
		currentMousePos = pos
		if (!isDragging && (pos - pressPos).getDistance() > 4f) {
			isDragging = true
			suppressClick = true
		}
		if (!isDragging) return
		val dragged = draggedItem ?: return
		val ordered = itemBounds
			.filter { it.key != "ROOT" }
			.sortedBy { it.top }
		val hit = ordered.firstOrNull { pos.y >= it.top && pos.y <= it.bottom }
		dropTarget = resolveDropTarget(dragged, hit, ordered, pos.y, itemBounds)
	}

	fun onRelease(viewModel: PSD2LiveViewModel) {
		val item = draggedItem
		val target = dropTarget
		val wasDragging = isDragging
		if (wasDragging && item != null && target != null) {
			applyDrop(viewModel, item, target)
		}
		clear(keepSuppress = wasDragging)
	}

	fun clear(keepSuppress: Boolean = false) {
		draggedItem = null
		dropTarget = null
		isDragging = false
		isPressed = false
		pressPos = Offset.Zero
		currentMousePos = Offset.Zero
		if (!keepSuppress) suppressClick = false
	}
}

private fun resolveDropTarget(
	dragged: ParamItemLayout,
	hit: ParamItemLayout?,
	ordered: List<ParamItemLayout>,
	y: Float,
	allBounds: Collection<ParamItemLayout>,
): ParamDropTarget? {
	if (hit == null) {
		val maxBottom = ordered.maxOfOrNull { it.bottom } ?: 0f
		val minTop = ordered.minOfOrNull { it.top } ?: 0f
		val overRoot = allBounds.any { it.key == "ROOT" && y >= it.top && y <= it.bottom }
		return if (overRoot || y > maxBottom || y < minTop - 4f) ParamDropTarget.Root else null
	}
	if (hit.key == dragged.key) return null
	// Block dropping a folder onto itself or any of its descendants (cycle prevention, same as hierarchy tree).
	if (dragged.isFolder && (hit.id == dragged.id || hit.id in dragged.descendantGroupIds)) return null

	val height = (hit.bottom - hit.top).coerceAtLeast(1f)
	val rel = ((y - hit.top) / height).coerceIn(0f, 1f)

	if (hit.isFolder) {
		return when {
			rel < 0.28f -> ParamDropTarget.Before(hit.id, hit.kind, hit.parentGroupId, hit.name)
			rel > 0.72f -> insertAfter(dragged, hit, ordered)
			else -> ParamDropTarget.Nest(hit.id, hit.name)
		}
	}

	return if (rel < 0.5f) {
		ParamDropTarget.Before(hit.id, hit.kind, hit.parentGroupId, hit.name)
	} else {
		insertAfter(dragged, hit, ordered)
	}
}

private fun insertAfter(
	dragged: ParamItemLayout,
	hit: ParamItemLayout,
	ordered: List<ParamItemLayout>,
): ParamDropTarget {
	val idx = ordered.indexOfFirst { it.key == hit.key }
	val next = ordered.getOrNull(idx + 1)
	return when {
		next == null -> ParamDropTarget.Append(hit.parentGroupId, hit.name)
		next.key == dragged.key -> {
			val afterDragged = ordered.getOrNull(idx + 2)
			when {
				afterDragged == null -> ParamDropTarget.Append(hit.parentGroupId, hit.name)
				afterDragged.parentGroupId == hit.parentGroupId ->
					ParamDropTarget.Before(afterDragged.id, afterDragged.kind, hit.parentGroupId, afterDragged.name)
				else -> ParamDropTarget.Append(hit.parentGroupId, hit.name)
			}
		}
		next.parentGroupId == hit.parentGroupId ->
			ParamDropTarget.Before(next.id, next.kind, hit.parentGroupId, next.name)
		else -> ParamDropTarget.Append(hit.parentGroupId, hit.name)
	}
}

private fun applyDrop(viewModel: PSD2LiveViewModel, item: ParamItemLayout, target: ParamDropTarget) {
	when (target) {
		is ParamDropTarget.Root -> {
			if (item.parentGroupId != null) {
				viewModel.moveParameterPanelNode(item.kind, item.id, null, null, null)
			}
		}
		is ParamDropTarget.Nest -> {
			if (item.parentGroupId != target.folderId) {
				viewModel.moveParameterPanelNode(item.kind, item.id, target.folderId, null, null)
			}
		}
		is ParamDropTarget.Before -> {
			if (item.id == target.id && item.kind == target.kind) return
			viewModel.moveParameterPanelNode(item.kind, item.id, target.parentGroupId, target.id, target.kind)
		}
		is ParamDropTarget.Append -> {
			viewModel.moveParameterPanelNode(item.kind, item.id, target.parentGroupId, null, null)
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ParametersListView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val model = state.previewModel
	val puppet = model?.rig?.puppet
	val allParameters = puppet?.parameters.orEmpty()
	val keyMarksByParameter = remember(puppet) { puppet?.parameterKeyMarks().orEmpty() }
	val query = state.parameterSearchQuery.trim().lowercase()
	val openOverrides = remember { mutableStateMapOf<String, Boolean>() }
	var renamingGroupId by remember { mutableStateOf<String?>(null) }
	var renameDraft by remember { mutableStateOf("") }
	var folderMenuFor by remember { mutableStateOf<String?>(null) }
	var folderMenuOffset by remember { mutableStateOf(Offset.Zero) }

	val dragState = remember { ParameterDragState() }
	val itemBoundsMap = remember { mutableStateMapOf<String, ParamItemLayout>() }
	var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

	val rows = remember(puppet, query, openOverrides.toMap()) {
		if (puppet == null) emptyList() else buildParameterPanelRows(puppet, query, openOverrides)
	}
	val visibleCount = rows.count { it !is ParameterPanelRow.Folder }

	Column(modifier = Modifier.fillMaxSize()) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 6.dp, vertical = 4.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactTextField(
					value = state.parameterSearchQuery,
					onValueChange = { viewModel.setParameterSearchQuery(it) },
					placeholder = tr("parameters.search"),
					leadingIcon = { IconSearch(tint = colors.textMuted) },
					modifier = Modifier.weight(1f),
					height = 22.dp,
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = tr("parameters.count", visibleCount, allParameters.size, state.lockedParameters.size),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
			}

			val inPreview = state.activeTabKind == WorkspaceTabKind.PREVIEW
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				val isAnim = state.animationEnabled
				val isMouseTracking = state.mouseTrackingEnabled

				if (inPreview) {
					CompactButton(
						text = if (isAnim) tr("preview.animation.pause") else tr("preview.animation.play"),
						onClick = { viewModel.setAnimationEnabled(!isAnim) },
						leadingIcon = {
							if (isAnim) IconPause(tint = colors.textPrimary) else IconPlay(tint = colors.accent)
						},
						enabled = model != null,
						height = 22.dp,
					)
					CompactButton(
						text = if (isMouseTracking) tr("preview.mouseTracking.on") else tr("preview.mouseTracking.off"),
						onClick = { viewModel.setMouseTrackingEnabled(!isMouseTracking) },
						leadingIcon = {
							IconMouse(
								active = isMouseTracking,
								tint = if (isMouseTracking) colors.accent else colors.textDisabled,
							)
						},
						enabled = model != null,
						height = 22.dp,
					)
					Spacer(Modifier.weight(1f))
					if (state.lockedParameters.isNotEmpty()) {
						CompactButton(
							text = tr("parameters.unlockAll"),
							onClick = { viewModel.unlockAllParameters() },
							leadingIcon = { IconLock(locked = false, tint = colors.textPrimary) },
							height = 22.dp,
						)
					}
				} else {
					CompactButton(
						text = tr("preview.animation.play"),
						onClick = { viewModel.setAnimationEnabled(true) },
						leadingIcon = { IconPlay(tint = colors.accent) },
						enabled = model != null,
						height = 22.dp,
					)
					Spacer(Modifier.weight(1f))
				}

				CompactButton(
					text = tr("parameters.newFolder"),
					onClick = { viewModel.createParameterGroup(tr("parameters.newFolderName")) },
					enabled = puppet != null,
					leadingIcon = { IconFolder(tint = colors.textPrimary) },
					height = 22.dp,
				)
				CompactButton(
					text = tr("parameters.resetAll"),
					onClick = { viewModel.resetAllParameters() },
					enabled = allParameters.isNotEmpty(),
					leadingIcon = { IconReset(tint = colors.textPrimary) },
					height = 22.dp,
				)
			}
		}

		if (allParameters.isEmpty()) {
			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					text = tr("parameters.empty"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textMuted,
					modifier = Modifier.padding(12.dp),
				)
			}
		} else {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.onGloballyPositioned { containerCoordinates = it }
					.onPointerEvent(PointerEventType.Move) { event ->
						val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
						dragState.onMove(pos, itemBoundsMap.values)
					}
					.onPointerEvent(PointerEventType.Release) { event ->
						if (event.button == PointerButton.Primary && dragState.isPressed) {
							dragState.onRelease(viewModel)
						}
					}
					.pointerHoverIcon(
						PointerIcon(
							if (dragState.isDragging) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
							else Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR),
						),
					),
			) {
				LazyColumn(modifier = Modifier.fillMaxSize()) {
					if (dragState.isDragging) {
						item(key = "ROOT") {
							val isRootTarget = dragState.dropTarget is ParamDropTarget.Root
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 6.dp, vertical = 2.dp)
									.background(
										if (isRootTarget) colors.accent.copy(alpha = 0.28f)
										else colors.panelElevated.copy(alpha = 0.5f),
										RoundedCornerShape(3.dp),
									)
									.border(
										BorderStroke(1.2.dp, if (isRootTarget) colors.accent else colors.divider),
										RoundedCornerShape(3.dp),
									)
									.onGloballyPositioned { coords ->
										val parent = containerCoordinates
										if (parent != null && parent.isAttached && coords.isAttached) {
											val topLeft = parent.localPositionOf(coords, Offset.Zero)
											itemBoundsMap["ROOT"] = ParamItemLayout(
												key = "ROOT",
												id = "ROOT",
												kind = "param_group",
												name = tr("parameters.folderRoot"),
												parentGroupId = null,
												isFolder = true,
												descendantGroupIds = emptySet(),
												top = topLeft.y,
												bottom = topLeft.y + coords.size.height,
											)
										}
									}
									.padding(horizontal = 8.dp, vertical = 4.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(6.dp),
							) {
								IconMoveToRoot(
									modifier = Modifier.size(11.dp),
									tint = if (isRootTarget) colors.accent else colors.textMuted,
								)
								Text(
									text = tr("parameters.dragRoot"),
									style = typography.caption.copy(fontSize = 11.sp),
									color = if (isRootTarget) colors.accent else colors.textMuted,
								)
							}
						}
					}

					items(rows, key = { row -> rowKey(row) }) { row ->
						val key = rowKey(row)
						val layout = rowToLayout(row)
						val isDragged = dragState.isDragging && dragState.draggedKey == key
						val isNestTarget = dragState.isDragging &&
							(dragState.dropTarget as? ParamDropTarget.Nest)?.folderId == layout.id &&
							layout.isFolder
						val insertBeforeHere = dragState.isDragging &&
							(dragState.dropTarget as? ParamDropTarget.Before)?.let {
								it.id == layout.id && it.kind == layout.kind
							} == true

						Column(modifier = Modifier.fillMaxWidth()) {
							if (insertBeforeHere) {
								Box(
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 6.dp)
										.height(2.dp)
										.background(colors.accent, RoundedCornerShape(1.dp)),
								)
							}
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.then(if (isDragged) Modifier.background(colors.panelElevated.copy(alpha = 0.35f)) else Modifier)
									.then(
										if (isNestTarget) {
											Modifier
												.background(colors.accent.copy(alpha = 0.22f))
												.border(BorderStroke(1.2.dp, colors.accent), RoundedCornerShape(2.dp))
										} else Modifier
									)
									.onGloballyPositioned { coords ->
										val parent = containerCoordinates
										if (parent != null && parent.isAttached && coords.isAttached) {
											val topLeft = parent.localPositionOf(coords, Offset.Zero)
											itemBoundsMap[key] = layout.copy(
												top = topLeft.y,
												bottom = topLeft.y + coords.size.height,
											)
										}
									},
							) {
								DisposableEffect(key) {
									onDispose { itemBoundsMap.remove(key) }
								}
								when (row) {
									is ParameterPanelRow.Folder -> {
										ParameterFolderRow(
											row = row,
											renaming = renamingGroupId == row.group.id.raw,
											renameDraft = renameDraft,
											onRenameDraft = { renameDraft = it },
											onStartRename = {
												renamingGroupId = row.group.id.raw
												renameDraft = row.group.name
											},
											onCommitRename = {
												val id = renamingGroupId
												if (id != null && renameDraft.isNotBlank()) {
													viewModel.renameParameterGroup(id, renameDraft)
												}
												renamingGroupId = null
											},
											onToggle = {
												if (dragState.suppressClick || dragState.isDragging || dragState.isPressed) {
													dragState.suppressClick = false
													return@ParameterFolderRow
												}
												val next = !row.open
												openOverrides[row.group.id.raw] = next
												viewModel.setParameterGroupOpen(row.group.id.raw, next)
											},
											onDelete = { viewModel.deleteParameterGroup(row.group.id.raw) },
											onNewChildFolder = {
												viewModel.createParameterGroup(tr("parameters.newFolderName"), row.group.id.raw)
											},
											menuOpen = folderMenuFor == row.group.id.raw,
											menuOffset = folderMenuOffset,
											onMenuOpenChange = { open, offset ->
												folderMenuFor = if (open) row.group.id.raw else null
												if (offset != null) folderMenuOffset = offset
											},
											onDragPress = { localPos, rowCoords ->
												val parent = containerCoordinates
												if (parent != null && rowCoords.isAttached && parent.isAttached) {
													val containerPos = parent.localPositionOf(rowCoords, localPos)
													val topLeft = parent.localPositionOf(rowCoords, Offset.Zero)
													dragState.onPress(
														layout.copy(
															top = topLeft.y,
															bottom = topLeft.y + rowCoords.size.height,
														),
														containerPos,
													)
												}
											},
										)
									}
									is ParameterPanelRow.Single -> {
										ParameterRowItem(
											param = row.parameter,
											depth = row.depth,
											state = state,
											viewModel = viewModel,
											keyMarks = keyMarksByParameter[row.parameter.id],
											nextSiblingParam = row.nextSiblingParam,
											onLinkWith = { targetParamId ->
												viewModel.setParameterLink(row.parameter.id.raw, targetParamId, true)
											},
											onDragPress = { localPos, rowCoords ->
												val parent = containerCoordinates
												if (parent != null && rowCoords.isAttached && parent.isAttached) {
													val containerPos = parent.localPositionOf(rowCoords, localPos)
													val topLeft = parent.localPositionOf(rowCoords, Offset.Zero)
													dragState.onPress(
														layout.copy(
															top = topLeft.y,
															bottom = topLeft.y + rowCoords.size.height,
														),
														containerPos,
													)
												}
											},
										)
									}
									is ParameterPanelRow.Linked -> {
										LinkedParameterPad(
											horizontal = row.horizontal,
											vertical = row.vertical,
											depth = row.depth,
											state = state,
											viewModel = viewModel,
											horizontalKeys = keyMarksByParameter[row.horizontal.id],
											verticalKeys = keyMarksByParameter[row.vertical.id],
											onUnlink = {
												viewModel.setParameterLink(row.horizontal.id.raw, row.vertical.id.raw, false)
											},
											onDragPress = { localPos, rowCoords ->
												val parent = containerCoordinates
												if (parent != null && rowCoords.isAttached && parent.isAttached) {
													val containerPos = parent.localPositionOf(rowCoords, localPos)
													val topLeft = parent.localPositionOf(rowCoords, Offset.Zero)
													dragState.onPress(
														layout.copy(
															top = topLeft.y,
															bottom = topLeft.y + rowCoords.size.height,
														),
														containerPos,
													)
												}
											},
										)
									}
								}
							}
							Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
						}
					}
				}

				if (dragState.isDragging && dragState.draggedItem != null) {
					val dragItem = dragState.draggedItem!!
					val target = dragState.dropTarget
					val hoverLabel = when (target) {
						null -> "⊘"
						is ParamDropTarget.Root -> "→ ${tr("parameters.folderRoot")}"
						is ParamDropTarget.Nest -> "→ ${target.folderName}"
						is ParamDropTarget.Before -> "⤒ ${target.label}"
						is ParamDropTarget.Append -> "⤓ ${target.label}"
					}
					val isValid = target != null
					Box(
						modifier = Modifier
							.offset {
								IntOffset(
									x = (dragState.currentMousePos.x + 14).roundToInt(),
									y = (dragState.currentMousePos.y + 14).roundToInt(),
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
							if (dragItem.isFolder) {
								IconFolder(modifier = Modifier.size(11.dp), tint = colors.accent)
							} else {
								IconDragHandle(modifier = Modifier.size(11.dp), tint = colors.textMuted)
							}
							Text(
								text = dragItem.name,
								style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
								color = colors.textPrimary,
								maxLines = 1,
							)
							Text(
								text = hoverLabel,
								style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
								color = if (isValid) colors.accent else colors.error,
								maxLines = 1,
							)
						}
					}
				}
			}
		}
	}
}

private fun rowKey(row: ParameterPanelRow): String = when (row) {
	is ParameterPanelRow.Folder -> "g:${row.group.id.raw}"
	is ParameterPanelRow.Single -> "p:${row.parameter.id.raw}"
	is ParameterPanelRow.Linked -> "l:${row.horizontal.id.raw}"
}

private fun rowToLayout(row: ParameterPanelRow): ParamItemLayout = when (row) {
	is ParameterPanelRow.Folder -> ParamItemLayout(
		key = rowKey(row),
		id = row.group.id.raw,
		kind = "param_group",
		name = row.group.name,
		parentGroupId = row.parentGroupId,
		isFolder = true,
		descendantGroupIds = row.descendantGroupIds,
		top = 0f,
		bottom = 0f,
	)
	is ParameterPanelRow.Single -> ParamItemLayout(
		key = rowKey(row),
		id = row.parameter.id.raw,
		kind = "parameter",
		name = row.parameter.name,
		parentGroupId = row.parentGroupId,
		isFolder = false,
		descendantGroupIds = emptySet(),
		top = 0f,
		bottom = 0f,
	)
	is ParameterPanelRow.Linked -> ParamItemLayout(
		key = rowKey(row),
		id = row.horizontal.id.raw,
		kind = "parameter",
		name = "${row.horizontal.name} × ${row.vertical.name}",
		parentGroupId = row.parentGroupId,
		isFolder = false,
		descendantGroupIds = emptySet(),
		top = 0f,
		bottom = 0f,
	)
}

private fun collectDescendantGroupIds(group: ParameterNode.Group): Set<String> {
	val result = LinkedHashSet<String>()
	fun walk(nodes: List<ParameterNode>) {
		for (node in nodes) {
			if (node is ParameterNode.Group) {
				result += node.id.raw
				walk(node.children)
			}
		}
	}
	walk(group.children)
	return result
}

private fun buildParameterPanelRows(
	puppet: PuppetModel,
	query: String,
	openOverrides: Map<String, Boolean>,
): List<ParameterPanelRow> {
	val byId = puppet.parameters.associateBy { it.id }
	val linkByHorizontal = puppet.parameterLinks.associateBy { it.horizontal }
	val verticalIds = puppet.parameterLinks.map { it.vertical }.toSet()
	val rows = ArrayList<ParameterPanelRow>()
	val filtering = query.isNotEmpty()

	fun matches(parameter: Parameter): Boolean =
		!filtering || parameter.name.lowercase().contains(query) || parameter.id.raw.lowercase().contains(query)

	fun walk(nodes: List<ParameterNode>, depth: Int, parentGroupId: String?) {
		var index = 0
		while (index < nodes.size) {
			when (val node = nodes[index]) {
				is ParameterNode.Group -> {
					val open = openOverrides[node.id.raw] ?: node.initiallyOpen || filtering
					val childRowsStart = rows.size
					if (open || filtering) {
						walk(node.children, depth + 1, node.id.raw)
					}
					val hasVisibleChildren = rows.size > childRowsStart ||
						(!filtering && node.children.isNotEmpty())
					val selfMatches = filtering && node.name.lowercase().contains(query)
					if (!filtering || hasVisibleChildren || selfMatches) {
						rows.add(
							childRowsStart,
							ParameterPanelRow.Folder(
								group = node,
								depth = depth,
								open = open,
								parentGroupId = parentGroupId,
								descendantGroupIds = collectDescendantGroupIds(node),
							),
						)
					}
					index++
				}
				is ParameterNode.Param -> {
					if (node.id in verticalIds) {
						index++
						continue
					}
					val parameter = byId[node.id]
					if (parameter == null) {
						index++
						continue
					}
					val link = linkByHorizontal[node.id]
					val next = nodes.getOrNull(index + 1)
					if (link != null) {
						val vertical = byId[link.vertical]
						if (vertical != null && (matches(parameter) || matches(vertical))) {
							rows += ParameterPanelRow.Linked(
								horizontal = parameter,
								vertical = vertical,
								depth = depth,
								parentGroupId = parentGroupId,
							)
						}
						index++
						continue
					}
					val nextParam = (next as? ParameterNode.Param)?.let { byId[it.id] }
						?.takeIf { it.id !in verticalIds && it.id !in linkByHorizontal }
					if (matches(parameter)) {
						rows += ParameterPanelRow.Single(
							parameter = parameter,
							depth = depth,
							parentGroupId = parentGroupId,
							nextSiblingParam = nextParam,
						)
					}
					index++
				}
			}
		}
	}

	walk(puppet.materializedParameterTree(), 0, null)
	return rows
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParameterDragHandle(
	onDragPress: (localPos: Offset, rowCoords: LayoutCoordinates) -> Unit,
	rowCoords: LayoutCoordinates?,
) {
	val colors = LocalToolColors.current
	var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	Box(
		modifier = Modifier
			.size(18.dp)
			.onGloballyPositioned { handleCoords = it }
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
			.onPointerEvent(PointerEventType.Press) { event ->
				if (event.button != PointerButton.Primary) return@onPointerEvent
				val row = rowCoords ?: return@onPointerEvent
				val handle = handleCoords
				val localInHandle = event.changes.firstOrNull()?.position ?: Offset.Zero
				val localInRow = if (handle != null && handle.isAttached && row.isAttached) {
					row.localPositionOf(handle, localInHandle)
				} else {
					localInHandle
				}
				onDragPress(localInRow, row)
				event.changes.firstOrNull()?.consume()
			},
		contentAlignment = Alignment.Center,
	) {
		IconDragHandle(modifier = Modifier.size(11.dp), tint = colors.textMuted)
	}
}
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParameterFolderRow(
	row: ParameterPanelRow.Folder,
	renaming: Boolean,
	renameDraft: String,
	onRenameDraft: (String) -> Unit,
	onStartRename: () -> Unit,
	onCommitRename: () -> Unit,
	onToggle: () -> Unit,
	onDelete: () -> Unit,
	onNewChildFolder: () -> Unit,
	menuOpen: Boolean,
	menuOffset: Offset,
	onMenuOpenChange: (Boolean, Offset?) -> Unit,
	onDragPress: (localPos: Offset, rowCoords: LayoutCoordinates) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.background(
				when {
					isHovered -> colors.controlHover.copy(alpha = 0.55f)
					else -> colors.panelElevated.copy(alpha = 0.55f)
				},
			)
			.hoverable(interactionSource)
			.onPointerEvent(PointerEventType.Press) { event ->
				if (event.button == PointerButton.Secondary) {
					val clickPos = event.changes.firstOrNull()?.position ?: Offset.Zero
					onMenuOpenChange(true, clickPos)
					event.changes.firstOrNull()?.consume()
				}
			}
			.then(
				if (!renaming) {
					Modifier
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
						.clickable(
							interactionSource = interactionSource,
							indication = null,
							onClick = onToggle,
						)
				} else Modifier,
			)
			.padding(start = (6 + row.depth * 12).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconChevron(expanded = row.open, tint = colors.textMuted, modifier = Modifier.size(10.dp))
		Spacer(Modifier.width(4.dp))
		IconFolder(tint = colors.accent, modifier = Modifier.size(12.dp))
		Spacer(Modifier.width(4.dp))
		if (renaming) {
			CompactTextField(
				value = renameDraft,
				onValueChange = onRenameDraft,
				modifier = Modifier.weight(1f),
				height = 20.dp,
				onEditEnd = onCommitRename,
			)
		} else {
			Text(
				text = row.group.name,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.weight(1f)
					.pointerInput(row.group.id) {
						detectTapGestures(onDoubleTap = { onStartRename() })
					},
			)
		}
		ParameterDragHandle(onDragPress = onDragPress, rowCoords = rowCoords)
	}

	TreeContextMenu(
		expanded = menuOpen,
		onDismissRequest = { onMenuOpenChange(false, null) },
		clickOffset = menuOffset,
	) {
		CompactMenuItem(
			text = tr("parameters.renameFolder"),
			onClick = { onMenuOpenChange(false, null); onStartRename() },
		)
		CompactMenuItem(
			text = tr("parameters.newChildFolder"),
			onClick = { onMenuOpenChange(false, null); onNewChildFolder() },
		)
		CompactMenuItem(
			text = tr("parameters.deleteFolder"),
			onClick = { onMenuOpenChange(false, null); onDelete() },
		)
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParameterRowItem(
	param: Parameter,
	depth: Int,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	keyMarks: ParameterKeyMarks?,
	nextSiblingParam: Parameter?,
	onLinkWith: (String) -> Unit,
	onDragPress: (localPos: Offset, rowCoords: LayoutCoordinates) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isLocked = param.id in state.lockedParameters
	val currentValue = liveValue(param, state)
	val valueText = formatParamValue(currentValue)
	val sliderMarks = remember(keyMarks) { keyMarks.toSliderMarks() }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.background(if (isLocked) colors.selection.copy(alpha = 0.25f) else Color.Transparent)
			.padding(start = (6 + depth * 12).dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			CompactIconButton(
				onClick = { viewModel.toggleParameterLock(param.id, currentValue) },
				size = 18.dp,
				tooltip = if (isLocked) tr("parameters.unlockTooltip") else tr("parameters.lockTooltip"),
			) {
				IconLock(
					locked = isLocked,
					modifier = Modifier.size(11.dp),
					tint = if (isLocked) colors.accent else colors.textMuted,
				)
			}
			Spacer(Modifier.width(5.dp))
			Text(
				text = param.name,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = if (isLocked) FontWeight.SemiBold else FontWeight.Normal),
				color = if (isLocked) colors.selectionText else colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Spacer(Modifier.width(4.dp))
			Text(
				text = param.id.raw,
				style = typography.monoSmall.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = valueText,
				style = typography.mono.copy(
					fontSize = 10.5.sp,
					fontWeight = if (isLocked) FontWeight.Bold else FontWeight.Normal,
					color = if (isLocked) colors.accent else colors.textPrimary,
				),
			)
			if (isLocked || abs(currentValue - param.default) > 0.001f) {
				Spacer(Modifier.width(4.dp))
				CompactIconButton(
					onClick = { viewModel.resetParameter(param.id) },
					size = 18.dp,
					tooltip = tr("parameters.resetTooltip"),
				) {
					IconReset(modifier = Modifier.size(10.dp), tint = if (isLocked) colors.accent else colors.textMuted)
				}
			}
			if (nextSiblingParam != null) {
				Spacer(Modifier.width(4.dp))
				CompactIconButton(
					onClick = { onLinkWith(nextSiblingParam.id.raw) },
					size = 18.dp,
					tooltip = tr("parameters.linkTooltip"),
				) {
					IconParameterLink(linked = false, tint = colors.accent, modifier = Modifier.size(11.dp))
				}
			}
			Spacer(Modifier.width(2.dp))
			ParameterDragHandle(onDragPress = onDragPress, rowCoords = rowCoords)
		}
		Row(
			modifier = Modifier.fillMaxWidth().height(18.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "%.0f".format(param.min),
				style = typography.monoSmall.copy(fontSize = 9.sp),
				color = colors.textMuted,
				modifier = Modifier.width(22.dp),
			)
			CompactSlider(
				value = currentValue.coerceIn(param.min, param.max),
				onValueChange = { viewModel.setParameterValue(param.id, it) },
				valueRange = param.min..param.max,
				modifier = Modifier.weight(1f),
				keyMarks = sliderMarks,
				thumbShape = if (param.kind == ParameterKind.BLEND_SHAPE) SliderKeyShape.Square else SliderKeyShape.Circle,
			)
			Text(
				text = "%.0f".format(param.max),
				style = typography.monoSmall.copy(fontSize = 9.sp),
				color = colors.textMuted,
				textAlign = TextAlign.Right,
				modifier = Modifier.width(22.dp),
			)
		}
	}
}

@Composable
private fun LinkedParameterPad(
	horizontal: Parameter,
	vertical: Parameter,
	depth: Int,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	horizontalKeys: ParameterKeyMarks?,
	verticalKeys: ParameterKeyMarks?,
	onUnlink: () -> Unit,
	onDragPress: (localPos: Offset, rowCoords: LayoutCoordinates) -> Unit,
) {
	val colors = LocalToolColors.current
	val xLocked = horizontal.id in state.lockedParameters
	val yLocked = vertical.id in state.lockedParameters
	val xValue = liveValue(horizontal, state)
	val yValue = liveValue(vertical, state)
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.clipToBounds()
			.background(colors.panelElevated.copy(alpha = 0.45f))
			.border(BorderStroke(0.5.dp, colors.divider.copy(alpha = 0.5f)))
			.padding(start = (6 + depth * 12).dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		LinkedAxisValueRow(
			axisLabel = "X",
			axisColor = colors.accent,
			param = horizontal,
			value = xValue,
			locked = xLocked,
			showUnlink = true,
			showDragHandle = true,
			onLock = { viewModel.toggleParameterLock(horizontal.id, xValue) },
			onReset = { viewModel.resetParameter(horizontal.id) },
			onUnlink = onUnlink,
			onDragPress = onDragPress,
			rowCoords = rowCoords,
		)
		Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
		LinkedAxisValueRow(
			axisLabel = "Y",
			axisColor = Color(0xFFA78BFA),
			param = vertical,
			value = yValue,
			locked = yLocked,
			showUnlink = false,
			showDragHandle = false,
			onLock = { viewModel.toggleParameterLock(vertical.id, yValue) },
			onReset = { viewModel.resetParameter(vertical.id) },
			onUnlink = {},
			onDragPress = { _, _ -> },
			rowCoords = null,
		)
		Spacer(Modifier.height(4.dp))
		CombinedParameterPad(
			horizontal = horizontal,
			vertical = vertical,
			xValue = xValue,
			yValue = yValue,
			xLocked = xLocked,
			yLocked = yLocked,
			horizontalKeys = horizontalKeys,
			verticalKeys = verticalKeys,
			modifier = Modifier
				.fillMaxWidth()
				.height(84.dp)
				.clipToBounds()
				.clip(RoundedCornerShape(3.dp)),
			onChange = { x, y ->
				if (!xLocked) viewModel.setParameterValue(horizontal.id, x)
				if (!yLocked) viewModel.setParameterValue(vertical.id, y)
			},
		)
	}
}

@Composable
private fun LinkedAxisValueRow(
	axisLabel: String,
	axisColor: Color,
	param: Parameter,
	value: Float,
	locked: Boolean,
	showUnlink: Boolean,
	showDragHandle: Boolean,
	onLock: () -> Unit,
	onReset: () -> Unit,
	onUnlink: () -> Unit,
	onDragPress: (localPos: Offset, rowCoords: LayoutCoordinates) -> Unit,
	rowCoords: LayoutCoordinates?,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(if (locked) colors.selection.copy(alpha = 0.2f) else Color.Transparent)
			.padding(vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.size(16.dp)
				.background(axisColor.copy(alpha = 0.16f), RoundedCornerShape(2.dp)),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = axisLabel,
				style = typography.mono.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
				color = axisColor,
			)
		}
		Spacer(Modifier.width(4.dp))
		CompactIconButton(
			onClick = onLock,
			size = 18.dp,
			tooltip = if (locked) tr("parameters.unlockTooltip") else tr("parameters.lockTooltip"),
		) {
			IconLock(
				locked = locked,
				modifier = Modifier.size(11.dp),
				tint = if (locked) colors.accent else colors.textMuted,
			)
		}
		Spacer(Modifier.width(4.dp))
		Text(
			text = param.name,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Spacer(Modifier.width(4.dp))
		Text(
			text = param.id.raw,
			style = typography.monoSmall.copy(fontSize = 9.5.sp),
			color = colors.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = formatParamValue(value),
			style = typography.mono.copy(fontSize = 10.5.sp, color = if (locked) colors.accent else colors.textPrimary),
		)
		if (locked || abs(value - param.default) > 0.001f) {
			Spacer(Modifier.width(4.dp))
			CompactIconButton(
				onClick = onReset,
				size = 18.dp,
				tooltip = tr("parameters.resetTooltip"),
			) {
				IconReset(modifier = Modifier.size(10.dp), tint = if (locked) colors.accent else colors.textMuted)
			}
		}
		if (showUnlink) {
			Spacer(Modifier.width(4.dp))
			CompactIconButton(
				onClick = onUnlink,
				size = 18.dp,
				tooltip = tr("parameters.unlinkTooltip"),
			) {
				IconParameterLink(linked = true, tint = colors.accent, modifier = Modifier.size(11.dp))
			}
		}
		if (showDragHandle) {
			Spacer(Modifier.width(2.dp))
			ParameterDragHandle(onDragPress = onDragPress, rowCoords = rowCoords)
		}
	}
}

@Composable
private fun CombinedParameterPad(
	horizontal: Parameter,
	vertical: Parameter,
	xValue: Float,
	yValue: Float,
	xLocked: Boolean,
	yLocked: Boolean,
	horizontalKeys: ParameterKeyMarks?,
	verticalKeys: ParameterKeyMarks?,
	modifier: Modifier,
	onChange: (Float, Float) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val xKeyList = remember(horizontal, horizontalKeys) {
		val keys = horizontalKeys?.allKeys.orEmpty()
		if (keys.isNotEmpty()) keys else listOf(horizontal.min, horizontal.default, horizontal.max).distinct().sorted()
	}
	val yKeyList = remember(vertical, verticalKeys) {
		val keys = verticalKeys?.allKeys.orEmpty()
		if (keys.isNotEmpty()) keys else listOf(vertical.min, vertical.default, vertical.max).distinct().sorted()
	}

	fun projectToValues(pos: Offset, w: Float, h: Float): Pair<Float, Float> {
		val nx = (pos.x / w).coerceIn(0f, 1f)
		val ny = (pos.y / h).coerceIn(0f, 1f)
		val rawX = horizontal.min + nx * (horizontal.max - horizontal.min)
		val rawY = vertical.max - ny * (vertical.max - vertical.min)
		return rawX to rawY
	}

	fun snapValues(rawX: Float, rawY: Float, w: Float, h: Float): Pair<Float, Float> {
		val curX = if (xLocked) xValue else rawX
		val curY = if (yLocked) yValue else rawY
		val snapRadiusPx = 10f
		var bestDist = Float.MAX_VALUE
		var snappedX = curX
		var snappedY = curY
		val spanX = (horizontal.max - horizontal.min).takeIf { it > 1e-4f } ?: 1f
		val spanY = (vertical.max - vertical.min).takeIf { it > 1e-4f } ?: 1f
		for (kx in xKeyList) {
			for (ky in yKeyList) {
				val px = ((kx - horizontal.min) / spanX) * w
				val py = ((vertical.max - ky) / spanY) * h
				val curPx = ((curX - horizontal.min) / spanX) * w
				val curPy = ((vertical.max - curY) / spanY) * h
				val dist = hypot(px - curPx, py - curPy)
				if (dist < snapRadiusPx && dist < bestDist) {
					bestDist = dist
					snappedX = if (!xLocked) kx else xValue
					snappedY = if (!yLocked) ky else yValue
				}
			}
		}
		return snappedX to snappedY
	}

	Box(
		modifier = modifier
			.clipToBounds()
			.clip(RoundedCornerShape(3.dp))
			.background(colors.inputBackground)
			.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR))),
	) {
		Canvas(
			modifier = Modifier
				.fillMaxSize()
				.pointerInput(horizontal.id, vertical.id, xLocked, yLocked, horizontal.min, horizontal.max, vertical.min, vertical.max) {
					detectTapGestures(
						onDoubleTap = { offset ->
							var closestDist = Float.MAX_VALUE
							var targetX = xValue
							var targetY = yValue
							val spanX = (horizontal.max - horizontal.min).takeIf { it > 1e-4f } ?: 1f
							val spanY = (vertical.max - vertical.min).takeIf { it > 1e-4f } ?: 1f
							for (kx in xKeyList) {
								for (ky in yKeyList) {
									val px = ((kx - horizontal.min) / spanX) * size.width
									val py = ((vertical.max - ky) / spanY) * size.height
									val dist = hypot(px - offset.x, py - offset.y)
									if (dist < closestDist) {
										closestDist = dist
										targetX = kx
										targetY = ky
									}
								}
							}
							onChange(if (!xLocked) targetX else xValue, if (!yLocked) targetY else yValue)
						},
						onTap = { offset ->
							val (rx, ry) = projectToValues(offset, size.width.toFloat(), size.height.toFloat())
							val (sx, sy) = snapValues(rx, ry, size.width.toFloat(), size.height.toFloat())
							onChange(sx, sy)
						},
					)
				}
				.pointerInput(horizontal.id, vertical.id, xLocked, yLocked, horizontal.min, horizontal.max, vertical.min, vertical.max) {
					detectDragGestures(
						onDragStart = { offset ->
							val (rx, ry) = projectToValues(offset, size.width.toFloat(), size.height.toFloat())
							val (sx, sy) = snapValues(rx, ry, size.width.toFloat(), size.height.toFloat())
							onChange(sx, sy)
						},
						onDrag = { change, _ ->
							change.consume()
							val (rx, ry) = projectToValues(change.position, size.width.toFloat(), size.height.toFloat())
							val (sx, sy) = snapValues(rx, ry, size.width.toFloat(), size.height.toFloat())
							onChange(sx, sy)
						},
					)
				},
		) {
			val w = size.width
			val h = size.height
			val guideColor = colors.divider.copy(alpha = 0.65f)
			val spanX = (horizontal.max - horizontal.min).takeIf { it > 1e-4f } ?: 1f
			val spanY = (vertical.max - vertical.min).takeIf { it > 1e-4f } ?: 1f

			val zeroNormX = if (0f in horizontal.min..horizontal.max) (0f - horizontal.min) / spanX else 0.5f
			val zeroNormY = if (0f in vertical.min..vertical.max) (vertical.max - 0f) / spanY else 0.5f
			val ox = (zeroNormX * w).coerceIn(0f, w)
			val oy = (zeroNormY * h).coerceIn(0f, h)
			drawLine(guideColor, Offset(ox, 0f), Offset(ox, h), strokeWidth = 1f)
			drawLine(guideColor, Offset(0f, oy), Offset(w, oy), strokeWidth = 1f)

			val curNormX = ((xValue - horizontal.min) / spanX).coerceIn(0f, 1f)
			val curNormY = ((vertical.max - yValue) / spanY).coerceIn(0f, 1f)
			val hx = curNormX * w
			val hy = curNormY * h
			val crossColor = colors.accent.copy(alpha = 0.28f)
			drawLine(crossColor, Offset(hx, 0f), Offset(hx, h), strokeWidth = 1f)
			drawLine(crossColor, Offset(0f, hy), Offset(w, hy), strokeWidth = 1f)

			val blendKeysX = horizontalKeys?.blendKeys.orEmpty().toSet()
			val blendKeysY = verticalKeys?.blendKeys.orEmpty().toSet()
			val dotFill = colors.controlBackground
			val dotStroke = colors.textMuted.copy(alpha = 0.75f)

			for (kx in xKeyList) {
				val kxNorm = ((kx - horizontal.min) / spanX).coerceIn(0f, 1f)
				val kxPx = kxNorm * w
				val isBlendX = kx in blendKeysX
				for (ky in yKeyList) {
					val kyNorm = ((vertical.max - ky) / spanY).coerceIn(0f, 1f)
					val kyPx = kyNorm * h
					val isBlend = isBlendX || ky in blendKeysY
					val isDefault = abs(kx - horizontal.default) < 1e-4f && abs(ky - vertical.default) < 1e-4f
					val isSnapped = hypot(kxPx - hx, kyPx - hy) < 3.5f
					if (isSnapped) {
						drawCircle(colors.accent.copy(alpha = 0.35f), radius = 5.dp.toPx(), center = Offset(kxPx, kyPx))
					}
					val markSize = if (isDefault) 2.0.dp.toPx() else 1.4.dp.toPx()
					if (isBlend) {
						drawRect(
							color = if (isSnapped) colors.accent else dotFill,
							topLeft = Offset(kxPx - markSize, kyPx - markSize),
							size = Size(markSize * 2f, markSize * 2f),
							style = Fill,
						)
						drawRect(
							color = if (isSnapped) colors.accent else dotStroke,
							topLeft = Offset(kxPx - markSize, kyPx - markSize),
							size = Size(markSize * 2f, markSize * 2f),
							style = Stroke(width = 1f),
						)
					} else {
						drawCircle(
							color = if (isSnapped) colors.accent else dotFill,
							radius = markSize,
							center = Offset(kxPx, kyPx),
							style = Fill,
						)
						drawCircle(
							color = if (isSnapped) colors.accent else dotStroke,
							radius = markSize,
							center = Offset(kxPx, kyPx),
							style = Stroke(width = 1f),
						)
					}
				}
			}

			val handleCenter = Offset(hx, hy)
			drawCircle(colors.accent.copy(alpha = 0.22f), radius = 6.5.dp.toPx(), center = handleCenter)
			drawCircle(Color(0xFF1E2127), radius = 4.dp.toPx(), center = handleCenter, style = Fill)
			drawCircle(colors.accent, radius = 4.dp.toPx(), center = handleCenter, style = Stroke(width = 1.4.dp.toPx()))
			drawCircle(Color.White, radius = 1.8.dp.toPx(), center = handleCenter, style = Fill)
		}

		Text(
			text = "%.0f".format(vertical.max),
			style = typography.monoSmall.copy(fontSize = 8.sp),
			color = colors.textMuted.copy(alpha = 0.45f),
			modifier = Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 2.dp),
		)
		Text(
			text = "%.0f".format(horizontal.max),
			style = typography.monoSmall.copy(fontSize = 8.sp),
			color = colors.textMuted.copy(alpha = 0.45f),
			modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 2.dp),
		)
		Box(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(start = 3.dp, bottom = 2.dp)
				.background(colors.panelBackground.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
				.border(BorderStroke(0.5.dp, colors.divider.copy(alpha = 0.4f)), RoundedCornerShape(2.dp))
				.padding(horizontal = 4.dp, vertical = 1.dp),
		) {
			Text(
				text = "X: ${formatParamValue(xValue)}  Y: ${formatParamValue(yValue)}",
				style = typography.monoSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary.copy(alpha = 0.9f),
			)
		}
	}
}

private fun ParameterKeyMarks?.toSliderMarks(): List<SliderKeyMark> {
	if (this == null) return emptyList()
	return buildList {
		gridKeys.forEach { add(SliderKeyMark(it, SliderKeyShape.Circle)) }
		blendKeys.forEach { add(SliderKeyMark(it, SliderKeyShape.Square)) }
	}
}

private fun liveValue(param: Parameter, state: PSD2LiveState): Float =
	if (state.activeTabKind == WorkspaceTabKind.PREVIEW && state.animationEnabled) {
		state.previewParameterValues[param.id] ?: state.parameterValues[param.id] ?: param.default
	} else {
		state.parameterValues[param.id] ?: param.default
	}

private fun formatParamValue(value: Float): String =
	if (abs(value) >= 10f) "%.1f".format(value) else "%.2f".format(value)
