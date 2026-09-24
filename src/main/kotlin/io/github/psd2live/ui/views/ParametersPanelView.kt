package io.github.psd2live.ui.views

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ParameterKeyBoundComponent
import io.github.psd2live.ui.ParameterKeyMarks
import io.github.psd2live.ui.ParameterKeyOwner
import io.github.psd2live.ui.components.ColorPickerPopupContent
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconCollapseAll
import io.github.psd2live.ui.components.IconDragHandle
import io.github.psd2live.ui.components.IconExpandAll
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconParameterLink
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.components.InlineEditorRegions
import io.github.psd2live.ui.components.LocalInlineEditorRegions
import io.github.psd2live.ui.components.SliderKeyMark
import io.github.psd2live.ui.components.SliderKeyShape
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.componentsAtParameterKey
import io.github.psd2live.ui.componentsAtParameterKeys
import io.github.psd2live.ui.selectedParameterOwner
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLabelColor
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.umamo.edit.materializedParameterTree
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/** One visible row in the parameter panel (folder header, single slider, or combined 2D pad). */
internal sealed interface ParameterPanelRow {
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
		val folderLabelColor: ParameterLabelColor = ParameterLabelColor.None,
	) : ParameterPanelRow

	data class Linked(
		val horizontal: Parameter,
		val vertical: Parameter,
		val depth: Int,
		val parentGroupId: String?,
		val folderLabelColor: ParameterLabelColor = ParameterLabelColor.None,
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
 * extended with before/after destinations for live sibling reorder previews.
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

/** Hovered parameter key and the components bound at that key, for the left-side float panel. */
private data class ParameterKeyOwnersHover(
	val keyLabel: String,
	val components: List<ParameterKeyBoundComponent>,
	val panelY: Float,
)

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

/** Shift sibling rows to expose the pending slot without changing hit-test coordinates. */
private fun parameterDragShift(
	row: ParamItemLayout,
	drag: ParameterDragState,
	bounds: Collection<ParamItemLayout>,
): Float {
	val source = drag.draggedItem ?: return 0f
	val target = drag.dropTarget ?: return 0f
	if (source.isFolder || row.key == source.key) return 0f
	val measured = bounds.firstOrNull { it.key == row.key } ?: return 0f
	fun groupEnd(groupId: String?): Float? {
		if (groupId == null) return bounds.maxOfOrNull { it.bottom }
		val group = bounds.firstOrNull { it.id == groupId && it.isFolder }
		return bounds.filter {
			it.key == group?.key || it.parentGroupId == groupId || it.parentGroupId in group?.descendantGroupIds.orEmpty()
		}.maxOfOrNull { it.bottom }
	}
	val destination = when (target) {
		is ParamDropTarget.Before -> bounds.firstOrNull { it.id == target.id && it.kind == target.kind }?.top
		is ParamDropTarget.Nest -> if (source.parentGroupId == target.folderId) null else groupEnd(target.folderId)
		is ParamDropTarget.Append -> groupEnd(target.parentGroupId)
		ParamDropTarget.Root -> if (source.parentGroupId == null) null else groupEnd(null)
	} ?: return 0f
	val sourceBounds = bounds.firstOrNull { it.key == source.key } ?: source
	val height = sourceBounds.bottom - sourceBounds.top
	return when {
		destination <= sourceBounds.top && measured.top >= destination && measured.top < sourceBounds.top -> height
		destination >= sourceBounds.bottom && measured.top > sourceBounds.top && measured.top < destination -> -height
		else -> 0f
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
	if (dragged.isFolder && (hit.id == dragged.id || hit.id in dragged.descendantGroupIds ||
		hit.parentGroupId == dragged.id || hit.parentGroupId in dragged.descendantGroupIds)) return null

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
    var creatingParameter by remember { mutableStateOf(false) }
	var creatingUnderGroupId by remember { mutableStateOf<String?>(null) }
    if (creatingParameter && puppet != null) {
        ParameterDefinitionDialog(
			null,
			state,
			viewModel,
			parentGroupId = creatingUnderGroupId,
		) {
			creatingParameter = false
			creatingUnderGroupId = null
		}
    }
	val owner = remember(puppet, state.selectedLayerId, state.selectedDeformerId, model?.rig?.layerIdByDrawableId) {
        puppet?.selectedParameterOwner(state.selectedLayerId, state.selectedDeformerId, model.rig.layerIdByDrawableId.orEmpty())
    }
    val keyMarksByParameter = remember(puppet) { puppet?.parameterKeyMarks().orEmpty() }
    val selectedKeyMarks = remember(puppet, owner) { if (owner == null) emptyMap() else puppet?.parameterKeyMarks(owner).orEmpty() }
    val relatedIds = selectedKeyMarks.keys
    var relatedOnly by remember { mutableStateOf(false) }
    val activeRelatedFilter = relatedOnly && owner != null
	val query = state.parameterSearchQuery.trim().lowercase()
	val openOverrides = remember { mutableStateMapOf<String, Boolean>() }
	var renamingGroupId by remember { mutableStateOf<String?>(null) }
	var renameDraft by remember { mutableStateOf("") }
	var renameOriginal by remember { mutableStateOf("") }
	var renameSettled by remember { mutableStateOf(false) }
	var folderMenuFor by remember { mutableStateOf<String?>(null) }
	var folderMenuOffset by remember { mutableStateOf(Offset.Zero) }
	val focusManager = LocalFocusManager.current
	val renameEditorRegions = remember { InlineEditorRegions() }
	fun startFolderRename(groupId: String, name: String) {
		renamingGroupId = groupId
		renameDraft = name
		renameOriginal = name
		renameSettled = false
	}
	fun commitFolderRename() {
		if (renameSettled) return
		val id = renamingGroupId ?: return
		renameSettled = true
		val trimmed = renameDraft.trim()
		if (trimmed.isNotEmpty() && trimmed != renameOriginal) {
			viewModel.renameParameterGroup(id, trimmed)
		}
		renamingGroupId = null
	}
	fun cancelFolderRename() {
		if (renameSettled) return
		renameSettled = true
		renamingGroupId = null
	}

	val dragState = remember { ParameterDragState() }
	val dragPreview = rememberGraphicsLayer()
	val itemBoundsMap = remember { mutableStateMapOf<String, ParamItemLayout>() }
	var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var keyOwnersHover by remember { mutableStateOf<ParameterKeyOwnersHover?>(null) }
	var keyOwnersPopupHovered by remember { mutableStateOf(false) }
	var shownKeyOwnersHover by remember { mutableStateOf<ParameterKeyOwnersHover?>(null) }
	LaunchedEffect(keyOwnersHover, keyOwnersPopupHovered) {
		if (keyOwnersHover != null) {
			shownKeyOwnersHover = keyOwnersHover
		} else if (!keyOwnersPopupHovered) {
			delay(140)
			if (keyOwnersHover == null && !keyOwnersPopupHovered) {
				shownKeyOwnersHover = null
			}
		}
	}

	val layerIdByDrawableId = model?.rig?.layerIdByDrawableId.orEmpty()
	fun selectBoundComponent(component: ParameterKeyBoundComponent) {
		when (component.kind) {
			"mesh" -> viewModel.selectLayer(layerIdByDrawableId[component.id] ?: component.id)
			"deformer" -> viewModel.selectDeformer(component.id)
			else -> Unit
		}
	}
	fun reportKeyHover(
		keyLabel: String,
		components: List<ParameterKeyBoundComponent>,
		trackCoords: LayoutCoordinates?,
		localY: Float,
	) {
		val parent = containerCoordinates
		if (trackCoords == null || parent == null || !trackCoords.isAttached || !parent.isAttached) {
			keyOwnersHover = null
			return
		}
		val panelY = parent.localPositionOf(trackCoords, Offset(0f, localY)).y
		keyOwnersHover = ParameterKeyOwnersHover(keyLabel, components, panelY)
	}
	fun clearKeyHover() {
		keyOwnersHover = null
	}

	val rows = remember(puppet, query, openOverrides.toMap(), activeRelatedFilter, relatedIds) {
		if (puppet == null) emptyList() else buildParameterPanelRows(puppet, query, openOverrides, if (activeRelatedFilter) relatedIds else null)
	}
	val listState = rememberLazyListState()
	val visibleCount = rows.sumOf { row ->
		when (row) {
			is ParameterPanelRow.Folder -> 0
			is ParameterPanelRow.Single -> 1
			is ParameterPanelRow.Linked -> 2
		}
	}

	Column(modifier = Modifier.fillMaxSize()) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated)
				.padding(horizontal = 6.dp, vertical = 4.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				CompactTextField(
					value = state.parameterSearchQuery,
					onValueChange = { viewModel.setParameterSearchQuery(it) },
					placeholder = tr("parameters.search"),
					leadingIcon = { IconSearch(tint = colors.textMuted) },
					trailingIcon = {
						if (state.parameterSearchQuery.isNotEmpty()) {
							CompactIconButton(
								onClick = { viewModel.setParameterSearchQuery("") },
								tooltip = tr("parameters.clearSearch"), size = 16.dp,
							) { IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted) }
						}
					},
					modifier = Modifier.weight(1f),
					height = 22.dp,
				)
				Text(
					text = (if (state.activeWorkspace.canvases.size > 1) "${viewModel.canvasTitle(state.activeCanvas)} · " else "") +
						tr("parameters.count", visibleCount, allParameters.size, state.lockedParameters.size),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
					maxLines = 1,
				)
			}

			val inPreview = state.previewLive
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(3.dp),
			) {
				if (inPreview) {
					CompactIconButton(
						onClick = { viewModel.unlockAllParameters() },
						enabled = state.lockedParameters.isNotEmpty(),
						size = 22.dp,
						tooltip = tr("parameters.unlockAll"),
					) {
						IconLock(locked = false, modifier = Modifier.size(11.dp), tint = colors.textPrimary)
					}
				}
				CompactIconButton(
					onClick = {
						for (id in collectParameterGroupIds(puppet)) {
							openOverrides[id] = true
						}
					},
					enabled = puppet != null,
					size = 22.dp,
					tooltip = tr("canvas.hierarchy.expandAll"),
				) {
					IconExpandAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
				}
				CompactIconButton(
					onClick = {
						for (id in collectParameterGroupIds(puppet)) {
							openOverrides[id] = false
						}
					},
					enabled = puppet != null,
					size = 22.dp,
					tooltip = tr("canvas.hierarchy.collapseAll"),
				) {
					IconCollapseAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
				}

                io.github.psd2live.ui.components.CompactButton(
                    text = tr("parameters.create"),
                    onClick = {
						creatingUnderGroupId = null
						creatingParameter = true
					},
                    enabled = puppet != null && state.historySnapshot != null && !state.canvasEditBusy,
                    height = 22.dp,
                )
				CompactIconButton(
					onClick = { viewModel.createParameterGroup(tr("parameters.newFolderName")) },
					enabled = puppet != null,
					size = 22.dp,
					tooltip = tr("parameters.newFolder"),
				) {
					IconFolder(modifier = Modifier.size(12.dp), tint = colors.textPrimary)
				}
				CompactIconButton(
					onClick = { viewModel.resetAllParameters() },
					enabled = allParameters.isNotEmpty(),
					size = 22.dp,
					tooltip = tr("parameters.resetAll"),
				) {
					IconReset(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
				}
			}
		}

        if (owner != null) {
            Row(
                Modifier.fillMaxWidth().background(colors.panelElevated).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                io.github.psd2live.ui.components.CompactButton(
                    text = tr("parameters.relatedOnly"),
                    onClick = { relatedOnly = !relatedOnly },
                    isPrimary = activeRelatedFilter,
                    height = 22.dp,
                )
                Text(tr("parameters.relatedCount", relatedIds.size), style = typography.caption, color = colors.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
		Divider(color = colors.divider)

		if (rows.isEmpty()) {
			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					text = if (allParameters.isEmpty()) tr("parameters.empty") else if (activeRelatedFilter) tr("parameters.noRelated") else tr("parameters.noResults"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textMuted,
					modifier = Modifier.padding(12.dp),
				)
			}
		} else {
			CompositionLocalProvider(LocalInlineEditorRegions provides renameEditorRegions) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.onGloballyPositioned { containerCoordinates = it }
						.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) {
							if (renamingGroupId != null) renameEditorRegions.pressedInside = false
						}
						.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Final) {
							if (renamingGroupId != null && !renameEditorRegions.pressedInside) {
								focusManager.clearFocus(force = true)
							}
						}
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
				LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 10.dp)) {
					items(rows, key = { row -> rowKey(row) }) { row ->
						val key = rowKey(row)
						val layout = rowToLayout(row)
                        val related = when (row) {
                            is ParameterPanelRow.Single -> row.parameter.id in relatedIds
                            is ParameterPanelRow.Linked -> row.horizontal.id in relatedIds || row.vertical.id in relatedIds
                            is ParameterPanelRow.Folder -> row.group.containsParameter(relatedIds)
                        }
						val folderTint = when (row) {
							is ParameterPanelRow.Single -> row.folderLabelColor.displayArgb()
							is ParameterPanelRow.Linked -> row.folderLabelColor.displayArgb()
							is ParameterPanelRow.Folder -> null
						}?.let { Color(it).copy(alpha = 0.10f) }
						val isDragged = dragState.isDragging && dragState.draggedKey == key
						val shift by animateFloatAsState(
							targetValue = if (dragState.isDragging) parameterDragShift(layout, dragState, itemBoundsMap.values) else 0f,
							animationSpec = tween(if (dragState.isDragging) 120 else 0),
						)

						Column(
								modifier = Modifier
									.fillMaxWidth()
                                    .background(
										when {
											related -> colors.selection.copy(alpha = 0.35f)
											folderTint != null -> folderTint
											else -> Color.Transparent
										},
									)
									.drawWithContent {
										if (isDragged) {
											dragPreview.record {
												drawRect(colors.panelElevated)
												this@drawWithContent.drawContent()
											}
										} else {
											translate(top = shift) {
                                                this@drawWithContent.drawContent()
                                                if (related) drawRect(colors.accent, size = Size(2.dp.toPx(), size.height))
                                            }
										}
									}

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
							Box(Modifier.fillMaxWidth()) {
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
												startFolderRename(row.group.id.raw, row.group.name)
											},
											onCommitRename = { commitFolderRename() },
											onCancelRename = { cancelFolderRename() },
											onToggle = {
												if (dragState.suppressClick) {
													dragState.suppressClick = false
													return@ParameterFolderRow
												}
												openOverrides[row.group.id.raw] = !row.open
											},
											onDelete = { viewModel.deleteParameterGroup(row.group.id.raw) },
											onNewChildParameter = {
												openOverrides[row.group.id.raw] = true
												creatingUnderGroupId = row.group.id.raw
												creatingParameter = true
											},
											onNewChildFolder = {
												viewModel.createParameterGroup(tr("parameters.newFolderName"), row.group.id.raw)
											},
											onLabelColor = { color ->
												viewModel.setParameterGroupLabelColor(row.group.id.raw, color)
											},
											canCreateParameter = state.historySnapshot != null && !state.canvasEditBusy,
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
                                            related = row.parameter.id in relatedIds,
                                            selectedKeys = selectedKeyMarks[row.parameter.id]?.allKeys.orEmpty(),
											nextSiblingParam = row.nextSiblingParam,
											onLinkWith = { targetParamId ->
												viewModel.setParameterLink(row.parameter.id.raw, targetParamId, true)
											},
											onKeyHover = { key, trackCoords, localY ->
												if (key == null || puppet == null) {
													clearKeyHover()
												} else {
													reportKeyHover(
														keyLabel = "${row.parameter.name} = ${formatAxisValue(key)}",
														components = puppet.componentsAtParameterKey(row.parameter.id, key),
														trackCoords = trackCoords,
														localY = localY,
													)
												}
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
                                            highlightedX = selectedKeyMarks[row.horizontal.id]?.allKeys.orEmpty(),
                                            highlightedY = selectedKeyMarks[row.vertical.id]?.allKeys.orEmpty(),
                                            relatedIds = relatedIds,
											verticalKeys = keyMarksByParameter[row.vertical.id],
											onUnlink = {
												viewModel.setParameterLink(row.horizontal.id.raw, row.vertical.id.raw, false)
											},
											onKeyHover = { xKey, yKey, trackCoords, localY ->
												if (xKey == null || yKey == null || puppet == null) {
													clearKeyHover()
												} else {
													reportKeyHover(
														keyLabel = "${row.horizontal.name}=${formatAxisValue(xKey)} · ${row.vertical.name}=${formatAxisValue(yKey)}",
														components = puppet.componentsAtParameterKeys(
															listOf(row.horizontal.id to xKey, row.vertical.id to yKey),
														),
														trackCoords = trackCoords,
														localY = localY,
													)
												}
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

				VerticalScrollbar(
					adapter = rememberScrollbarAdapter(listState),
					modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp),
				)

				if (dragState.isDragging && dragState.draggedItem != null) {
					val dragged = dragState.draggedItem!!
					// Draw the actual row, without adding a second interactive copy of its controls.
					Canvas(Modifier.fillMaxSize()) {
						val top = dragged.top + dragState.currentMousePos.y - dragState.pressPos.y
						translate(top = top) { drawLayer(dragPreview) }
					}
				}

				val hover = shownKeyOwnersHover
				if (hover != null) {
					ParameterKeyOwnersFloat(
						hover = hover,
						selectedOwner = owner,
						onHoverChange = { keyOwnersPopupHovered = it },
						onSelect = { component ->
							selectBoundComponent(component)
							clearKeyHover()
							keyOwnersPopupHovered = false
							shownKeyOwnersHover = null
						},
					)
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

private fun collectParameterGroupIds(puppet: PuppetModel?): List<String> {
	if (puppet == null) return emptyList()
	val result = ArrayList<String>()
	fun walk(nodes: List<ParameterNode>) {
		for (node in nodes) {
			if (node is ParameterNode.Group) {
				result += node.id.raw
				walk(node.children)
			}
		}
	}
	walk(puppet.materializedParameterTree())
	return result
}

internal fun buildParameterPanelRows(
	puppet: PuppetModel,
	query: String,
	openOverrides: Map<String, Boolean>,
    relatedIds: Set<ParameterId>? = null,
): List<ParameterPanelRow> {
	val byId = puppet.parameters.associateBy { it.id }
	val linkByHorizontal = puppet.parameterLinks.associateBy { it.horizontal }
	val verticalIds = puppet.parameterLinks.map { it.vertical }.toSet()
	val rows = ArrayList<ParameterPanelRow>()
	val filtering = query.isNotEmpty() || relatedIds != null

	fun matches(parameter: Parameter): Boolean =
		(relatedIds == null || parameter.id in relatedIds) &&
            (query.isEmpty() || parameter.name.lowercase().contains(query) || parameter.id.raw.lowercase().contains(query))

	fun walk(nodes: List<ParameterNode>, depth: Int, parentGroupId: String?, parentLabelColor: ParameterLabelColor) {
		var index = 0
		while (index < nodes.size) {
			when (val node = nodes[index]) {
				is ParameterNode.Group -> {
					val open = filtering || (openOverrides[node.id.raw] ?: node.initiallyOpen)
					val childRowsStart = rows.size
					if (open || filtering) {
						walk(node.children, depth + 1, node.id.raw, node.labelColor)
					}
					val hasVisibleChildren = rows.size > childRowsStart ||
						(!filtering && node.children.isNotEmpty())
					val selfMatches = relatedIds == null && query.isNotEmpty() && node.name.lowercase().contains(query)
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
								folderLabelColor = parentLabelColor,
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
							folderLabelColor = parentLabelColor,
						)
					}
					index++
				}
			}
		}
	}

	walk(puppet.materializedParameterTree(), 0, null, ParameterLabelColor.None)
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
			.size(16.dp)
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
	onCancelRename: () -> Unit,
	onToggle: () -> Unit,
	onDelete: () -> Unit,
	onNewChildParameter: () -> Unit,
	onNewChildFolder: () -> Unit,
	onLabelColor: (ParameterLabelColor) -> Unit,
	canCreateParameter: Boolean,
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
	val renameFocus = remember { FocusRequester() }
	var customColorOpen by remember { mutableStateOf(false) }
	var customDraftArgb by remember { mutableStateOf(0xFFB3D4FF.toInt()) }
	val density = LocalDensity.current
	LaunchedEffect(renaming) {
		if (renaming) runCatching { renameFocus.requestFocus() }
	}
	val labelArgb = row.group.labelColor.displayArgb()
	val labelTint = labelArgb?.let { Color(it).copy(alpha = if (isHovered) 0.34f else 0.20f) }
	val folderIconTint = labelArgb?.let { Color(it) } ?: colors.accent

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.background(
				labelTint
					?: when {
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
			.padding(start = (6 + row.depth * 12).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconChevron(expanded = row.open, tint = colors.textMuted, modifier = Modifier.size(10.dp))
		Spacer(Modifier.width(4.dp))
		IconFolder(tint = folderIconTint, modifier = Modifier.size(12.dp))
		Spacer(Modifier.width(4.dp))
		if (renaming) {
			CompactTextField(
				value = renameDraft,
				onValueChange = onRenameDraft,
				modifier = Modifier
					.weight(1f)
					.focusRequester(renameFocus)
					.onPreviewKeyEvent { event ->
						if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
							onCancelRename()
							true
						} else {
							false
						}
					},
				height = 20.dp,
				selectAllOnFocus = true,
				endEditOnSettle = false,
				onCommit = onCommitRename,
				onFocusLost = onCommitRename,
			)
		} else {
			Text(
				text = row.group.name,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
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
			text = tr("parameters.newChildParameter"),
			onClick = { onMenuOpenChange(false, null); onNewChildParameter() },
			enabled = canCreateParameter,
		)
		CompactMenuItem(
			text = tr("parameters.newChildFolder"),
			onClick = { onMenuOpenChange(false, null); onNewChildFolder() },
		)
		CompactMenuSection(tr("parameters.labelColor"))
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			ParameterLabelSwatch(
				fill = Color.Transparent,
				border = colors.border,
				selected = row.group.labelColor is ParameterLabelColor.None,
				onClick = {
					onLabelColor(ParameterLabelColor.None)
					onMenuOpenChange(false, null)
				},
			)
			for (kind in ParameterLabelColor.Preset.Kind.entries) {
				val selected = (row.group.labelColor as? ParameterLabelColor.Preset)?.kind == kind
				ParameterLabelSwatch(
					fill = Color(kind.swatchArgb),
					border = if (selected) colors.accent else colors.border.copy(alpha = 0.5f),
					selected = selected,
					onClick = {
						onLabelColor(ParameterLabelColor.Preset(kind))
						onMenuOpenChange(false, null)
					},
				)
			}
		}
		CompactMenuItem(
			text = tr("parameters.labelColorCustom"),
			onClick = {
				customDraftArgb = labelArgb ?: 0xFFB3D4FF.toInt()
				onMenuOpenChange(false, null)
				customColorOpen = true
			},
			active = row.group.labelColor is ParameterLabelColor.Custom,
		)
		CompactMenuItem(
			text = tr("parameters.deleteFolder"),
			onClick = { onMenuOpenChange(false, null); onDelete() },
		)
	}

	if (customColorOpen) {
		Popup(
			alignment = Alignment.TopStart,
			offset = IntOffset(0, with(density) { 24.dp.roundToPx() }),
			onDismissRequest = {
				onLabelColor(ParameterLabelColor.Custom(customDraftArgb or 0xFF000000.toInt()))
				customColorOpen = false
			},
			properties = PopupProperties(focusable = true),
		) {
			ColorPickerPopupContent(
				initialColor = customDraftArgb and 0x00FFFFFF,
				sampledColor = null,
				onColorChanged = { rgb ->
					customDraftArgb = rgb or 0xFF000000.toInt()
				},
				onDismiss = {
					onLabelColor(ParameterLabelColor.Custom(customDraftArgb or 0xFF000000.toInt()))
					customColorOpen = false
				},
			)
		}
	}
}

@Composable
private fun ParameterLabelSwatch(
	fill: Color,
	border: Color,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Box(
		modifier = Modifier
			.size(16.dp)
			.background(fill, RoundedCornerShape(3.dp))
			.border(
				BorderStroke(if (selected) 1.5.dp else 1.dp, border),
				RoundedCornerShape(3.dp),
			)
			.clickable(onClick = onClick)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
	)
}

/** Cubism-style single-line name (no id clutter). */
@Composable
private fun ParameterName(param: Parameter, locked: Boolean = false, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(
		text = param.name,
		style = typography.body.copy(
			fontSize = 11.sp,
			fontWeight = if (locked) FontWeight.SemiBold else FontWeight.Normal,
		),
		color = if (locked) colors.accent else colors.textPrimary,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier,
	)
}

@Composable
private fun ParameterValueInput(param: Parameter, value: Float, onValueChange: (Float) -> Unit) {
	val focusManager = LocalFocusManager.current
	var focused by remember(param.id) { mutableStateOf(false) }
	var draft by remember(param.id) { mutableStateOf(formatParamValue(value)) }
	LaunchedEffect(value, focused) {
		if (!focused) draft = formatParamValue(value)
	}
	CompactTextField(
		value = draft,
		onValueChange = { draft = it },
		isMono = true,
		onCommit = { focusManager.clearFocus() },
		modifier = Modifier.width(44.dp)
			.semantics { contentDescription = param.name + " (" + param.id.raw + ")" }
			.onFocusChanged { focus ->
				if (focused && !focus.isFocused) {
					draft.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.let {
						onValueChange(it.coerceIn(param.min, param.max))
					}
					draft = formatParamValue(value)
				} else if (!focused && focus.isFocused) {
					draft = value.toString()
				}
				focused = focus.isFocused
			},
		height = 18.dp,
	)
}

private val ParamRowLinkWidth = 14.dp
private val ParamRowLinkSpacer = 2.dp
private val ParamRowLockWidth = 16.dp
private val ParamRowLockSpacer = 4.dp
private val ParamRowNameWidth = 88.dp
private val ParamRowBeforeTrackSpacer = 6.dp

private val ParamRowAfterTrackSpacer = 6.dp
private val ParamRowInputWidth = 44.dp
private val ParamRowInputSpacer = 2.dp
private val ParamRowResetWidth = 16.dp
private val ParamRowHandleWidth = 16.dp

private val ParamTrackInsetHorizontal = 4.dp
private val ParamKeyRadius = 2.8.dp
private val ParamThumbRadius = 5.2.dp

/**
 * Cubism Parameter palette track: thin line + hollow key dots + live2d handle (blue with white core on-key).
 * Left drag = free scrub. Hover a key then right-click = snap to that key.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParameterTrack(
	value: Float,
	onValueChange: (Float) -> Unit,
	valueRange: ClosedFloatingPointRange<Float>,
	keyMarks: List<SliderKeyMark>,
    highlightedKeys: List<Float> = emptyList(),
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	thumbShape: SliderKeyShape = SliderKeyShape.Circle,
	onHoverKey: ((key: Float?, trackCoords: LayoutCoordinates?, localY: Float) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val labelMeasurer = rememberTextMeasurer()
	val changeValue by rememberUpdatedState(onValueChange)
	val hoverKeyCb by rememberUpdatedState(onHoverKey)
	val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 1e-6f } ?: 1f
	val marks = remember(keyMarks, valueRange) {
		keyMarks
			.filter { it.value >= valueRange.start - 1e-4f && it.value <= valueRange.endInclusive + 1e-4f }
			.distinctBy { it.value }
	}
	val marksState by rememberUpdatedState(marks)
	var hoverKey by remember { mutableStateOf<Float?>(null) }
	var trackCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	val insetDp = ParamTrackInsetHorizontal
	val keyRadiusDp = ParamKeyRadius
	val thumbRadiusDp = ParamThumbRadius

	fun xOf(width: Float, v: Float, inset: Float): Float {
		val usable = (width - 2f * inset).coerceAtLeast(1f)
		return inset + ((v - valueRange.start) / span).coerceIn(0f, 1f) * usable
	}

	fun valueOf(x: Float, width: Float, inset: Float): Float {
		val usable = (width - 2f * inset).coerceAtLeast(1f)
		return valueRange.start + ((x - inset) / usable).coerceIn(0f, 1f) * span
	}

	fun hitKey(x: Float, width: Float, inset: Float, radius: Float): Float? {
		var best: Float? = null
		var bestDist = radius
		for (mark in marksState) {
			val dist = abs(xOf(width, mark.value, inset) - x)
			if (dist <= bestDist) {
				bestDist = dist
				best = mark.value
			}
		}
		return best
	}

	Canvas(
		modifier = modifier
			.height(28.dp)
			.onGloballyPositioned { trackCoords = it }
			.pointerHoverIcon(
				if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR))
				else PointerIcon.Default,
			)
			.onPointerEvent(PointerEventType.Move) { event ->
				if (!enabled) return@onPointerEvent
				val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
				val inset = insetDp.toPx()
				val key = hitKey(x, size.width.toFloat(), inset, 10.dp.toPx())
				hoverKey = key
				hoverKeyCb?.invoke(key, trackCoords, size.height * 0.62f)
			}
			.onPointerEvent(PointerEventType.Exit) {
				hoverKey = null
				hoverKeyCb?.invoke(null, null, 0f)
			}
			.onPointerEvent(PointerEventType.Press) { event ->
				if (!enabled || event.button != PointerButton.Secondary) return@onPointerEvent
				val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
				val inset = insetDp.toPx()
				val key = hitKey(x, size.width.toFloat(), inset, 10.dp.toPx()) ?: return@onPointerEvent
				changeValue(key)
				event.changes.forEach { it.consume() }
			}
			.pointerInput(valueRange, enabled) {
				if (!enabled) return@pointerInput
				val inset = insetDp.toPx()
				awaitEachGesture {
					val down = awaitFirstDown()
					if (currentEvent.button != null && currentEvent.button != PointerButton.Primary) {
						down.consume()
						do {
							val event = awaitPointerEvent()
							event.changes.forEach { it.consume() }
						} while (event.changes.any { it.pressed })
						return@awaitEachGesture
					}
                    val clickedKey = hitKey(down.position.x, size.width.toFloat(), inset, 7.dp.toPx())
                    changeValue(clickedKey ?: valueOf(down.position.x, size.width.toFloat(), inset))
					down.consume()
					drag(down.id) { change ->
						change.consume()
						changeValue(valueOf(change.position.x, size.width.toFloat(), inset))
					}
				}
			},
	) {
		val inset = insetDp.toPx()
		val keyR = keyRadiusDp.toPx()
		val thumbR = thumbRadiusDp.toPx()
		val cy = size.height - thumbR - 1.dp.toPx()
		val trackColor = colors.textMuted.copy(alpha = 0.55f)
		val keyStroke = colors.textMuted.copy(alpha = 0.85f)
		val onKey = marks.any { abs(it.value - value) < EPS_KEY }

		// Horizontal track line
		drawLine(
			color = trackColor,
			start = Offset(inset, cy),
			end = Offset(size.width - inset, cy),
			strokeWidth = 1.2.dp.toPx(),
			cap = StrokeCap.Round,
		)

		for (mark in marks) {
            val keyStroke = if (highlightedKeys.any { abs(it - mark.value) < EPS_KEY }) colors.accent else keyStroke
			val mx = xOf(size.width, mark.value, inset)
			val hovered = hoverKey != null && abs(hoverKey!! - mark.value) < 1e-4f
			val r = if (hovered) keyR * 1.35f else keyR

			when (mark.shape) {
				SliderKeyShape.Circle -> {
					if (hovered) drawCircle(colors.accent.copy(alpha = 0.22f), r * 1.8f, Offset(mx, cy))
					// Hollow key dot: mask background so track line doesn't cut through, then hollow stroke
					drawCircle(colors.panelBackground, r, Offset(mx, cy))
					drawCircle(keyStroke, r, Offset(mx, cy), style = Stroke(width = 1.15.dp.toPx()))
				}
				SliderKeyShape.Square -> {
					val tl = Offset(mx - r, cy - r)
					val sz = Size(r * 2f, r * 2f)
					val cr = CornerRadius(r * 0.25f)
					drawRoundRect(colors.panelBackground, tl, sz, cr)
					drawRoundRect(keyStroke, tl, sz, cr, style = Stroke(width = 1.15.dp.toPx()))
				}
			}
		}
		val labelStyle = TextStyle(color = colors.textMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
		var labelRight = Float.NEGATIVE_INFINITY
		for (mark in marks.sortedBy { it.value }) {
			val label = formatAxisValue(mark.value)
			val layout = labelMeasurer.measure(label, labelStyle)
			val x = xOf(size.width, mark.value, inset) - layout.size.width / 2f
			val hovered = hoverKey != null && abs(hoverKey!! - mark.value) < 1e-4f
			if (!hovered && x < labelRight + 1.dp.toPx()) continue
			drawText(labelMeasurer, label, topLeft = Offset(x, 0f), style = labelStyle)
			labelRight = x + layout.size.width
		}

		val thumbX = xOf(size.width, value, inset)
		if (!enabled) {
			drawCircle(colors.textDisabled, thumbR, Offset(thumbX, cy))
		} else {
			// Thumb: solid accent circle + white center dot if onKey (Cubism keyform indicator)
			when (thumbShape) {
				SliderKeyShape.Circle -> {
					drawCircle(colors.accent, thumbR, Offset(thumbX, cy))
					if (onKey) {
						drawCircle(Color.White, thumbR * 0.42f, Offset(thumbX, cy))
					}
				}
				SliderKeyShape.Square -> {
					val tl = Offset(thumbX - thumbR, cy - thumbR)
					val sz = Size(thumbR * 2f, thumbR * 2f)
					val cr = CornerRadius(thumbR * 0.25f)
					drawRoundRect(colors.accent, tl, sz, cr)
					if (onKey) {
						val innerR = thumbR * 0.42f
						drawRoundRect(Color.White, Offset(thumbX - innerR, cy - innerR), Size(innerR * 2f, innerR * 2f), CornerRadius(innerR * 0.25f))
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParameterLinkSlot(
	linked: Boolean,
	enabled: Boolean,
	tooltip: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	tall: Boolean = false,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val content = @Composable {
		Box(
			modifier = modifier
				.size(width = ParamRowLinkWidth, height = if (tall) 36.dp else 16.dp)
				.hoverable(interaction)
				.clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
				.pointerHoverIcon(
					if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
					else PointerIcon.Default,
				),
			contentAlignment = Alignment.Center,
		) {
			IconParameterLink(
				linked = linked,
				modifier = Modifier.size(width = 12.dp, height = if (tall) 24.dp else 16.dp),
				tint = when {
					!enabled -> colors.textDisabled.copy(alpha = 0.35f)
					linked -> if (hovered) colors.accentHover else colors.accent
					else -> if (hovered) colors.textPrimary else colors.textMuted.copy(alpha = 0.72f)
				},
			)
		}
	}
	TooltipArea(
		tooltip = {
			Surface(
				color = colors.panelElevated,
				shape = RoundedCornerShape(3.dp),
				border = BorderStroke(1.dp, colors.border),
				elevation = 4.dp,
			) {
				Text(
					text = tooltip,
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textPrimary,
					modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
				)
			}
		},
		delayMillis = 400,
	) { content() }
}

@Composable
private fun ParameterRowItem(
	param: Parameter,
	depth: Int,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	keyMarks: ParameterKeyMarks?,
    related: Boolean,
    selectedKeys: List<Float>,
	nextSiblingParam: Parameter?,
	onLinkWith: (String) -> Unit,
	onKeyHover: (key: Float?, trackCoords: LayoutCoordinates?, localY: Float) -> Unit,
	onDragPress: (localPos: Offset, rowCoords: LayoutCoordinates) -> Unit,
) {
	val colors = LocalToolColors.current
	val isLocked = param.id in state.lockedParameters
	val currentValue = liveValue(param, state)
	val sliderMarks = remember(keyMarks) { keyMarks.toSliderMarks() }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val canLink = nextSiblingParam != null

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.background(if (isLocked) colors.selection.copy(alpha = 0.22f) else Color.Transparent)
			.padding(start = (4 + depth * 12).dp, end = 2.dp, top = 1.dp, bottom = 1.dp)
			.height(30.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Cubism: faint single chain link to the left of the name.
		ParameterLinkSlot(
			linked = false,
			enabled = canLink,
			tooltip = tr("parameters.linkTooltip"),
			onClick = { nextSiblingParam?.let { onLinkWith(it.id.raw) } },
			modifier = Modifier.width(ParamRowLinkWidth),
		)
		Spacer(Modifier.width(ParamRowLinkSpacer))
		CompactIconButton(
			onClick = { viewModel.toggleParameterLock(param.id, currentValue) },
			size = ParamRowLockWidth,
			tooltip = if (isLocked) tr("parameters.unlockTooltip") else tr("parameters.lockTooltip"),
		) {
			IconLock(
				locked = isLocked,
				modifier = Modifier.size(9.dp),
				tint = if (isLocked) colors.accent else colors.textMuted,
			)
		}
		Spacer(Modifier.width(ParamRowLockSpacer))
		EditableParameterName(param, isLocked, state, viewModel, related)
		Spacer(Modifier.width(ParamRowBeforeTrackSpacer))
		ParameterTrack(
			value = currentValue.coerceIn(param.min, param.max),
			onValueChange = { viewModel.setParameterValue(param.id, it) },
			valueRange = param.min..param.max,
			keyMarks = sliderMarks,
            highlightedKeys = selectedKeys,
			modifier = Modifier.weight(1f),
			thumbShape = if (param.kind == ParameterKind.BLEND_SHAPE) SliderKeyShape.Square else SliderKeyShape.Circle,
			onHoverKey = onKeyHover,
		)
		Spacer(Modifier.width(ParamRowAfterTrackSpacer))
		ParameterValueInput(param, currentValue, { viewModel.setParameterValue(param.id, it) })
		Spacer(Modifier.width(ParamRowInputSpacer))
		CompactIconButton(
			onClick = { viewModel.resetParameter(param.id) },
			enabled = isLocked || abs(currentValue - param.default) > 0.001f,
			size = ParamRowResetWidth,
			tooltip = tr("parameters.resetTooltip"),
		) {
			IconReset(modifier = Modifier.size(8.dp), tint = if (isLocked) colors.accent else colors.textMuted)
		}
		ParameterDragHandle(onDragPress = onDragPress, rowCoords = rowCoords)
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
    highlightedX: List<Float>,
    highlightedY: List<Float>,
    relatedIds: Set<ParameterId>,
	verticalKeys: ParameterKeyMarks?,
	onUnlink: () -> Unit,
	onKeyHover: (xKey: Float?, yKey: Float?, trackCoords: LayoutCoordinates?, localY: Float) -> Unit,
	onDragPress: (localPos: Offset, rowCoords: LayoutCoordinates) -> Unit,
) {
	val colors = LocalToolColors.current
	val xLocked = horizontal.id in state.lockedParameters
	val yLocked = vertical.id in state.lockedParameters
	val xValue = liveValue(horizontal, state)
	val yValue = liveValue(vertical, state)
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.padding(start = (4 + depth * 12).dp, end = 2.dp, top = 3.dp, bottom = 3.dp)
			.height(84.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Cubism: tall interlocking two-chain link spanning both axis rows.
		ParameterLinkSlot(
			linked = true,
			enabled = true,
			tooltip = tr("parameters.unlinkTooltip"),
			onClick = onUnlink,
			tall = true,
			modifier = Modifier.width(ParamRowLinkWidth),
		)
		Spacer(Modifier.width(ParamRowLinkSpacer))
		Column(
			modifier = Modifier.width(ParamRowLockWidth + ParamRowLockSpacer + ParamRowNameWidth),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				CompactIconButton(
					onClick = { viewModel.toggleParameterLock(horizontal.id, xValue) },
					size = ParamRowLockWidth,
					tooltip = if (xLocked) tr("parameters.unlockTooltip") else tr("parameters.lockTooltip"),
				) {
					IconLock(
						locked = xLocked,
						modifier = Modifier.size(9.dp),
						tint = if (xLocked) colors.accent else colors.textMuted,
					)
				}
				Spacer(Modifier.width(ParamRowLockSpacer))
				EditableParameterName(horizontal, xLocked, state, viewModel, horizontal.id in relatedIds)
			}
			Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				CompactIconButton(
					onClick = { viewModel.toggleParameterLock(vertical.id, yValue) },
					size = ParamRowLockWidth,
					tooltip = if (yLocked) tr("parameters.unlockTooltip") else tr("parameters.lockTooltip"),
				) {
					IconLock(
						locked = yLocked,
						modifier = Modifier.size(9.dp),
						tint = if (yLocked) colors.accent else colors.textMuted,
					)
				}
				Spacer(Modifier.width(ParamRowLockSpacer))
				EditableParameterName(vertical, yLocked, state, viewModel, vertical.id in relatedIds)
			}
		}
		Spacer(Modifier.width(ParamRowBeforeTrackSpacer))
		ParameterPad2D(
			horizontal = horizontal,
			vertical = vertical,
			xValue = xValue,
			yValue = yValue,
			xLocked = xLocked,
			yLocked = yLocked,
			horizontalKeys = horizontalKeys,
            highlightedX = highlightedX,
            highlightedY = highlightedY,
			verticalKeys = verticalKeys,
			modifier = Modifier.weight(1f).fillMaxHeight(),
			onChange = { x, y ->
				if (!xLocked) viewModel.setParameterValue(horizontal.id, x)
				if (!yLocked) viewModel.setParameterValue(vertical.id, y)
			},
			onHoverKey = onKeyHover,
		)
		Spacer(Modifier.width(ParamRowAfterTrackSpacer))
		Column(
			modifier = Modifier.width(ParamRowInputWidth),
			verticalArrangement = Arrangement.spacedBy(8.dp),
			horizontalAlignment = Alignment.End,
		) {
			ParameterValueInput(horizontal, xValue) { viewModel.setParameterValue(horizontal.id, it) }
			ParameterValueInput(vertical, yValue) { viewModel.setParameterValue(vertical.id, it) }
		}
		Spacer(Modifier.width(ParamRowInputSpacer))
		Column(
			modifier = Modifier.width(ParamRowResetWidth),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactIconButton(
				onClick = { viewModel.resetParameter(horizontal.id) },
				enabled = xLocked || abs(xValue - horizontal.default) > 0.001f,
				size = ParamRowResetWidth,
				tooltip = tr("parameters.resetTooltip"),
			) {
				IconReset(modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
			CompactIconButton(
				onClick = { viewModel.resetParameter(vertical.id) },
				enabled = yLocked || abs(yValue - vertical.default) > 0.001f,
				size = ParamRowResetWidth,
				tooltip = tr("parameters.resetTooltip"),
			) {
				IconReset(modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
		}
		ParameterDragHandle(onDragPress = onDragPress, rowCoords = rowCoords)
	}
}

/**
 * Cubism combined-parameter pad: dashed border, key grid, hollow key dots, live2d handle (blue with white core on-key).
 * Left drag free; hover key + right-click snaps to that intersection.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParameterPad2D(
	horizontal: Parameter,
	vertical: Parameter,
	xValue: Float,
	yValue: Float,
	xLocked: Boolean,
	yLocked: Boolean,
	horizontalKeys: ParameterKeyMarks?,
    highlightedX: List<Float>,
    highlightedY: List<Float>,
	verticalKeys: ParameterKeyMarks?,
	modifier: Modifier,
	onChange: (Float, Float) -> Unit,
	onHoverKey: ((xKey: Float?, yKey: Float?, trackCoords: LayoutCoordinates?, localY: Float) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val xKeyList = remember(horizontal, horizontalKeys) {
		horizontalKeys?.allKeys.orEmpty()
			.filter { it >= horizontal.min - 1e-4f && it <= horizontal.max + 1e-4f }
			.distinct().sorted()
	}
	val yKeyList = remember(vertical, verticalKeys) {
		verticalKeys?.allKeys.orEmpty()
			.filter { it >= vertical.min - 1e-4f && it <= vertical.max + 1e-4f }
			.distinct().sorted()
	}
	val blendX = remember(horizontalKeys) { horizontalKeys?.blendKeys.orEmpty().toSet() }
	val blendY = remember(verticalKeys) { verticalKeys?.blendKeys.orEmpty().toSet() }

	val onChangeState by rememberUpdatedState(onChange)
	val hoverKeyCb by rememberUpdatedState(onHoverKey)
	val xLockedState by rememberUpdatedState(xLocked)
	val yLockedState by rememberUpdatedState(yLocked)
	val xValueState by rememberUpdatedState(xValue)
	val yValueState by rememberUpdatedState(yValue)
	val xKeysState by rememberUpdatedState(xKeyList)
	val yKeysState by rememberUpdatedState(yKeyList)
	val hMin by rememberUpdatedState(horizontal.min)
	val hMax by rememberUpdatedState(horizontal.max)
	val vMin by rememberUpdatedState(vertical.min)
	val vMax by rememberUpdatedState(vertical.max)

	var hoverKey by remember { mutableStateOf<Pair<Float, Float>?>(null) }
	var padCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val labelMeasurer = rememberTextMeasurer()

	val insetHorizontalDp = 18.dp
	val insetVerticalDp = 14.dp
	val keyRadiusDp = ParamKeyRadius
	val thumbRadiusDp = ParamThumbRadius

	Canvas(
		modifier = modifier
			.onGloballyPositioned { padCoords = it }
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)))
			.onPointerEvent(PointerEventType.Move) { event ->
				val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
				val insetX = insetHorizontalDp.toPx()
				val insetY = insetVerticalDp.toPx()
				val w = (size.width - 2f * insetX).coerceAtLeast(1f)
				val h = (size.height - 2f * insetY).coerceAtLeast(1f)
				val spanX = (hMax - hMin).takeIf { it > 1e-4f } ?: 1f
				val spanY = (vMax - vMin).takeIf { it > 1e-4f } ?: 1f
				val hitR = 10.dp.toPx()
				var best: Pair<Float, Float>? = null
				var bestDist = hitR
				for (kx in xKeysState) for (ky in yKeysState) {
					val px = insetX + ((kx - hMin) / spanX).coerceIn(0f, 1f) * w
					val py = insetY + ((vMax - ky) / spanY).coerceIn(0f, 1f) * h
					val dist = hypot(px - pos.x, py - pos.y)
					if (dist <= bestDist) {
						bestDist = dist
						best = kx to ky
					}
				}
				hoverKey = best
				if (best != null) {
					val py = insetY + ((vMax - best.second) / spanY).coerceIn(0f, 1f) * h
					hoverKeyCb?.invoke(best.first, best.second, padCoords, py)
				} else {
					hoverKeyCb?.invoke(null, null, null, 0f)
				}
			}
			.onPointerEvent(PointerEventType.Exit) {
				hoverKey = null
				hoverKeyCb?.invoke(null, null, null, 0f)
			}
			.onPointerEvent(PointerEventType.Press) { event ->
				if (event.button != PointerButton.Secondary) return@onPointerEvent
				val hit = hoverKey ?: return@onPointerEvent
				onChangeState(
					if (!xLockedState) hit.first else xValueState,
					if (!yLockedState) hit.second else yValueState,
				)
				event.changes.forEach { it.consume() }
			}
			.pointerInput(horizontal.id, vertical.id) {
				val insetX = insetHorizontalDp.toPx()
				val insetY = insetVerticalDp.toPx()
				awaitEachGesture {
					val down = awaitFirstDown()
					if (currentEvent.button != null && currentEvent.button != PointerButton.Primary) {
						down.consume()
						do {
							val event = awaitPointerEvent()
							event.changes.forEach { it.consume() }
						} while (event.changes.any { it.pressed })
						return@awaitEachGesture
					}
					fun freeAt(pos: Offset) {
						val w = (size.width - 2f * insetX).coerceAtLeast(1f)
						val h = (size.height - 2f * insetY).coerceAtLeast(1f)
						val nx = ((pos.x - insetX) / w).coerceIn(0f, 1f)
						val ny = ((pos.y - insetY) / h).coerceIn(0f, 1f)
						onChangeState(
							if (!xLockedState) hMin + nx * (hMax - hMin) else xValueState,
							if (!yLockedState) vMax - ny * (vMax - vMin) else yValueState,
						)
					}
					freeAt(down.position)
					down.consume()
					drag(down.id) { change ->
						change.consume()
						freeAt(change.position)
					}
				}
			},
	) {
		val insetX = insetHorizontalDp.toPx()
		val insetY = insetVerticalDp.toPx()
		val padW = (size.width - 2f * insetX).coerceAtLeast(1f)
		val padH = (size.height - 2f * insetY).coerceAtLeast(1f)
		val spanX = (horizontal.max - horizontal.min).takeIf { it > 1e-4f } ?: 1f
		val spanY = (vertical.max - vertical.min).takeIf { it > 1e-4f } ?: 1f
		val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx()), 0f)
		val borderColor = colors.textMuted.copy(alpha = 0.50f)

		fun xPx(v: Float) = insetX + ((v - horizontal.min) / spanX).coerceIn(0f, 1f) * padW
		fun yPx(v: Float) = insetY + ((vertical.max - v) / spanY).coerceIn(0f, 1f) * padH

		// Dashed boundary rectangle matching Cubism 2D Pad (clean, no tinted fill background)
		drawRect(
			color = borderColor,
			topLeft = Offset(insetX, insetY),
			size = Size(padW, padH),
			style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
		)

		// Inner grid dashed lines (only between boundaries)
		val gridColor = colors.textMuted.copy(alpha = 0.40f)
		for (kx in xKeyList) {
			val x = xPx(kx)
			if (abs(x - insetX) > 2.5f && abs(x - (insetX + padW)) > 2.5f) {
				drawLine(gridColor, Offset(x, insetY), Offset(x, insetY + padH), 1.dp.toPx(), pathEffect = dash)
			}
		}
		for (ky in yKeyList) {
			val y = yPx(ky)
			if (abs(y - insetY) > 2.5f && abs(y - (insetY + padH)) > 2.5f) {
				drawLine(gridColor, Offset(insetX, y), Offset(insetX + padW, y), 1.dp.toPx(), pathEffect = dash)
			}
		}

		val labelStyle = TextStyle(color = colors.textMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
		for (kx in xKeyList) {
			val label = formatAxisValue(kx)
			val layout = labelMeasurer.measure(label, labelStyle)
			drawText(labelMeasurer, label, topLeft = Offset(xPx(kx) - layout.size.width / 2f, 1.dp.toPx()), style = labelStyle)
		}
		for (ky in yKeyList) {
			val label = formatAxisValue(ky)
			val layout = labelMeasurer.measure(label, labelStyle)
			drawText(labelMeasurer, label, topLeft = Offset(1.dp.toPx(), yPx(ky) - layout.size.height / 2f), style = labelStyle)
		}

		val hx = xPx(xValue)
		val hy = yPx(yValue)
		val keyStroke = colors.textMuted.copy(alpha = 0.85f)
		val keyR = keyRadiusDp.toPx()
		val thumbR = thumbRadiusDp.toPx()
		val onKey = xKeyList.any { abs(it - xValue) < EPS_KEY } &&
			yKeyList.any { abs(it - yValue) < EPS_KEY }

		// Draw hollow key dots at all intersections
		for (kx in xKeyList) {
			val isBlendX = kx in blendX
			for (ky in yKeyList) {
                val keyStroke = if (highlightedX.any { abs(it - kx) < EPS_KEY } && highlightedY.any { abs(it - ky) < EPS_KEY }) colors.accent else keyStroke
				val px = xPx(kx)
				val py = yPx(ky)
				val isBlend = isBlendX || ky in blendY
				val hovered = hoverKey?.let { abs(it.first - kx) < 1e-4f && abs(it.second - ky) < 1e-4f } == true
				val r = if (hovered) keyR * 1.35f else keyR

				if (hovered) drawCircle(colors.accent.copy(alpha = 0.22f), r * 1.8f, Offset(px, py))
				if (isBlend) {
					val tl = Offset(px - r, py - r)
					val sz = Size(r * 2f, r * 2f)
					val cr = CornerRadius(r * 0.25f)
					drawRoundRect(colors.panelBackground, tl, sz, cr)
					drawRoundRect(keyStroke, tl, sz, cr, style = Stroke(width = 1.15.dp.toPx()))
				} else {
					drawCircle(colors.panelBackground, r, Offset(px, py))
					drawCircle(keyStroke, r, Offset(px, py), style = Stroke(width = 1.15.dp.toPx()))
				}
			}
		}

		// Handle (thumb): solid accent circle + white core when on-key
		val handleColor = if (xLocked && yLocked) colors.textDisabled else colors.accent
		drawCircle(handleColor, thumbR, Offset(hx, hy))
		if (onKey && (!xLocked || !yLocked)) {
			drawCircle(Color.White, thumbR * 0.42f, Offset(hx, hy))
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
	if (state.previewLive && state.animationEnabled) {
		state.previewParameterValues[param.id] ?: state.parameterValues[param.id] ?: param.default
	} else {
		state.parameterValues[param.id] ?: param.default
	}

private fun formatParamValue(value: Float): String =
	if (abs(value) >= 10f) "%.1f".format(value) else "%.2f".format(value)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EditableParameterName(param: Parameter, locked: Boolean, state: PSD2LiveState, viewModel: PSD2LiveViewModel, related: Boolean) {
    var editing by remember(param.id) { mutableStateOf(false) }
    val editable = state.historySnapshot != null && !state.canvasEditBusy
    ParameterName(
        param,
        locked = locked,
        modifier = Modifier
            .width(ParamRowNameWidth)
            .semantics {
                contentDescription = param.name + " — " + tr("parameters.properties") +
                    if (related) " — " + tr("parameters.related") else ""
            }
            .clickable(enabled = editable, onClickLabel = tr("parameters.properties")) { editing = true }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Secondary && editable) {
                    editing = true
                    event.changes.forEach { it.consume() }
                }
            },
    )
    if (editing) ParameterDefinitionDialog(param, state, viewModel) { editing = false }
}

private fun ParameterNode.Group.containsParameter(ids: Set<ParameterId>): Boolean = children.any {
    when (it) {
        is ParameterNode.Param -> it.id in ids
        is ParameterNode.Group -> it.containsParameter(ids)
    }
}

/**
 * Floating list of components keyed at the hovered parameter key, anchored just left of the parameter panel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParameterKeyOwnersFloat(
	hover: ParameterKeyOwnersHover,
	selectedOwner: ParameterKeyOwner?,
	onHoverChange: (Boolean) -> Unit,
	onSelect: (ParameterKeyBoundComponent) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val panelY = hover.panelY
	val positionProvider = remember(panelY, density) {
		object : PopupPositionProvider {
			override fun calculatePosition(
				anchorBounds: IntRect,
				windowSize: IntSize,
				layoutDirection: LayoutDirection,
				popupContentSize: IntSize,
			): IntOffset {
				val gap = with(density) { 8.dp.roundToPx() }
				val margin = with(density) { 6.dp.roundToPx() }
				val x = (anchorBounds.left - popupContentSize.width - gap)
					.coerceAtLeast(margin)
				val preferredY = anchorBounds.top + panelY.roundToInt() - popupContentSize.height / 2
				val y = preferredY.coerceIn(
					margin,
					(windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin),
				)
				return IntOffset(x, y)
			}
		}
	}
	Popup(
		popupPositionProvider = positionProvider,
		properties = PopupProperties(focusable = false, clippingEnabled = false),
	) {
		Surface(
			color = colors.panelElevated,
			shape = RoundedCornerShape(6.dp),
			border = BorderStroke(1.dp, colors.border),
			elevation = 8.dp,
			modifier = Modifier
				.widthIn(min = 140.dp, max = 220.dp)
				.onPointerEvent(PointerEventType.Enter) { onHoverChange(true) }
				.onPointerEvent(PointerEventType.Exit) { onHoverChange(false) },
		) {
			Column(
				modifier = Modifier
					.padding(horizontal = 8.dp, vertical = 6.dp)
					.heightIn(max = 220.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = tr("parameters.keyOwnersTitle", hover.keyLabel),
					style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textMuted,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (hover.components.isEmpty()) {
					Text(
						text = tr("parameters.keyOwnersEmpty"),
						style = typography.caption.copy(fontSize = 11.sp),
						color = colors.textDisabled,
						modifier = Modifier.padding(vertical = 4.dp),
					)
				} else {
					Text(
						text = tr("parameters.keyOwnersCount", hover.components.size),
						style = typography.caption.copy(fontSize = 9.sp),
						color = colors.textDisabled,
					)
					for (component in hover.components) {
						key(component.kind, component.id) {
							val selectable = component.kind == "mesh" || component.kind == "deformer"
							val selected = selectedOwner == component.owner
							val interaction = remember { MutableInteractionSource() }
							val rowHovered by interaction.collectIsHoveredAsState()
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.background(
										when {
											selected -> colors.selection.copy(alpha = 0.45f)
											rowHovered && selectable -> colors.controlHover.copy(alpha = 0.55f)
											else -> Color.Transparent
										},
										RoundedCornerShape(3.dp),
									)
									.then(
										if (selectable) {
											Modifier
												.hoverable(interaction)
												.clickable(
													interactionSource = interaction,
													indication = null,
													onClick = { onSelect(component) },
												)
												.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
										} else Modifier.hoverable(interaction),
									)
									.padding(horizontal = 4.dp, vertical = 3.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(5.dp),
							) {
								ParameterKeyOwnerIcon(component, if (selected) colors.accent else colors.textMuted)
								Text(
									text = component.name,
									style = typography.body.copy(fontSize = 11.sp),
									color = if (selected) colors.accent else colors.textPrimary,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
									modifier = Modifier.weight(1f),
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun ParameterKeyOwnerIcon(component: ParameterKeyBoundComponent, tint: Color) {
	val modifier = Modifier.size(12.dp)
	when (component.kind) {
		"mesh" -> IconMeshWireframe(tint = tint, modifier = modifier)
		"deformer" -> when (component.subtype) {
			"rotation" -> IconRotationDeformer(tint = tint, modifier = modifier)
			else -> IconWarpDeformer(tint = tint, modifier = modifier)
		}
		"part" -> IconFolder(tint = tint, modifier = modifier)
		else -> IconParameterLink(linked = false, tint = tint, modifier = modifier)
	}
}

