package io.github.psd2live.ui.views

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
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
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconDragHandle
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconParameterLink
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSelectedOnly
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
import io.github.psd2live.ui.state.AppSettings
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

private data class ParameterPhysicsStatus(
	val outputs: Set<String> = emptySet(),
	val controlled: Set<String> = emptySet(),
)

private val LocalParameterPhysicsStatus = compositionLocalOf { ParameterPhysicsStatus() }

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
	val physicsGroups = viewModel.physicsGroups(state)
	val physicsOutputs = physicsGroups.flatMapTo(HashSet()) { it.setting.outputParameters }
	// Physics drives these sliders whenever a preview runs it, whichever canvas has focus.
	val physicsLive = state.previewLive && state.activeWorkspace.pose?.authoringPose != true && state.generatePhysics && !state.meshOnly
	val physicsControlled = if (physicsLive) physicsGroups.filter { it.active }
		.flatMapTo(HashSet()) { it.setting.outputParameters }
		.minus(state.lockedParameters.map { it.raw }) else emptySet()
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
	val padHeights = remember { mutableStateMapOf<Pair<ParameterId, ParameterId>, Dp>() }
	var renamingGroupId by remember { mutableStateOf<String?>(null) }
	var renameDraft by remember { mutableStateOf("") }
	var renameOriginal by remember { mutableStateOf("") }
	var renameSettled by remember { mutableStateOf(false) }
	var renamingSnapshotId by remember { mutableStateOf<String?>(null) }
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

	var nameWidth by remember { mutableStateOf(AppSettings.parameterNameWidth.dp) }
	// The divider sits at one x for every row: deeper rows give their indent back out of the name column.
	val maxDepth = rows.maxOfOrNull {
		when (it) {
			is ParameterPanelRow.Single -> it.depth
			is ParameterPanelRow.Linked -> it.depth
			is ParameterPanelRow.Folder -> 0
		}
	} ?: 0
	val nameWidthRange = (ParamRowNameWidthRange.start + (maxDepth * ParamRowDepthIndent).dp).let { min ->
		min..maxOf(min, ParamRowNameWidthRange.endInclusive)
	}
	val shownNameWidth = nameWidth.coerceIn(nameWidthRange.start, nameWidthRange.endInclusive)
	val density = LocalDensity.current
	val dividerCenter = with(density) { (ParamRowNameStart + shownNameWidth + ParamRowDividerWidth / 2).toPx() }
	val dividerHalfWidth = with(density) { (ParamRowDividerWidth / 2).toPx() }
	var dividerHovered by remember { mutableStateOf(false) }
	var dividerDrag by remember { mutableStateOf<Pair<Float, Dp>?>(null) }
	CompositionLocalProvider(
		LocalParameterNameWidth provides shownNameWidth,
		LocalInlineEditorRegions provides renameEditorRegions,
		LocalParameterPhysicsStatus provides ParameterPhysicsStatus(physicsOutputs, physicsControlled),
	) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) {
				if (renamingGroupId != null || renamingSnapshotId != null) renameEditorRegions.pressedInside = false
			}
			.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Final) {
				if ((renamingGroupId != null || renamingSnapshotId != null) && !renameEditorRegions.pressedInside) {
					focusManager.clearFocus(force = true)
				}
			},
	) {
		val editable = puppet != null && state.historySnapshot != null && !state.canvasEditBusy
		// Labels appear in this order as the panel widens, each only once everything before it fits.
		val labels = listOf(tr("parameters.newParameterShort"), tr("parameters.newFolderShort"), tr("parameters.relatedOnly"))
		PanelToolbar(
			labels = labels,
			iconCount = if (state.previewLive) 7 else 6,
			search = PanelSearch(state.parameterSearchQuery, viewModel::setParameterSearchQuery, tr("parameters.search")),
			secondary = { ParameterSnapshotBar(state, viewModel, renamingSnapshotId) { renamingSnapshotId = it } },
		) { labelsShown ->
			PanelToolButton(
				label = labels[0],
				showLabel = labelsShown > 0,
				onClick = {
					creatingUnderGroupId = null
					creatingParameter = true
				},
				enabled = editable,
				tooltip = tr("parameters.create"),
			) {
				IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
			}
			PanelToolButton(
				label = labels[1],
				showLabel = labelsShown > 1,
				onClick = { viewModel.createParameterGroup(tr("parameters.newFolderName")) },
				enabled = puppet != null,
				tooltip = tr("parameters.newFolder"),
			) {
				IconFolder(modifier = Modifier.size(12.dp), tint = colors.textPrimary)
			}
			PanelToolbarSeparator()
			PanelToolButton(
				label = labels[2],
				showLabel = labelsShown > 2,
				onClick = { relatedOnly = !relatedOnly },
				enabled = owner != null,
				active = activeRelatedFilter,
				tooltip = if (owner != null) tr("parameters.relatedOnly") + " · " + tr("parameters.relatedCount", relatedIds.size)
				else tr("parameters.relatedOnly"),
			) {
				IconSelectedOnly(
					tint = when {
						owner == null -> colors.textDisabled
						activeRelatedFilter -> colors.accent
						else -> colors.textMuted
					},
					modifier = Modifier.size(12.dp),
				)
			}
			Spacer(Modifier.weight(1f))
			PanelExpandCollapseButtons(
				onExpandAll = { for (id in collectParameterGroupIds(puppet)) openOverrides[id] = true },
				onCollapseAll = { for (id in collectParameterGroupIds(puppet)) openOverrides[id] = false },
				enabled = puppet != null,
			)
			if (state.previewLive) {
				PanelIconButton(
					onClick = { viewModel.unlockAllParameters() },
					enabled = state.lockedParameters.isNotEmpty(),
					tooltip = tr("parameters.unlockAll") +
						if (state.lockedParameters.isNotEmpty()) " (${state.lockedParameters.size})" else "",
				) {
					IconLock(locked = false, modifier = Modifier.size(11.dp), tint = colors.textPrimary)
				}
			}
			PanelResetButton(onClick = { viewModel.resetAllParameters() }, enabled = allParameters.isNotEmpty(), tooltip = tr("parameters.resetAll"))
		}

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
				Box(
					modifier = Modifier
						.fillMaxSize()
						.onGloballyPositioned { containerCoordinates = it }
						// The name divider is one column through every row, so the list hovers and drags it
						// as a whole and the highlight runs unbroken across folders and row gaps.
						.onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
							val change = event.changes.firstOrNull() ?: return@onPointerEvent
							val drag = dividerDrag
							if (drag != null) {
								val next = drag.second + with(density) { (change.position.x - drag.first).toDp() }
								nameWidth = next.coerceIn(nameWidthRange.start, nameWidthRange.endInclusive)
								AppSettings.parameterNameWidth = nameWidth.value
								change.consume()
							} else {
								val rowsBottom = itemBoundsMap.values.maxOfOrNull { it.bottom } ?: 0f
								dividerHovered = !dragState.isPressed && change.position.y <= rowsBottom &&
									abs(change.position.x - dividerCenter) <= dividerHalfWidth
							}
						}
						.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
							val change = event.changes.firstOrNull() ?: return@onPointerEvent
							if (dividerHovered && event.button == PointerButton.Primary) {
								dividerDrag = change.position.x to shownNameWidth
								change.consume()
							}
						}
						.onPointerEvent(PointerEventType.Release, pass = PointerEventPass.Initial) { event ->
							if (dividerDrag != null) {
								dividerDrag = null
								event.changes.forEach { it.consume() }
							}
						}
						.onPointerEvent(PointerEventType.Exit) {
							if (dividerDrag == null) dividerHovered = false
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
								when {
									dividerHovered || dividerDrag != null -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
									dragState.isDragging -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
									else -> Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
								},
							),
							overrideDescendants = dividerHovered || dividerDrag != null,
						),
				) {
				LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
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
											padHeight = padHeights[row.horizontal.id to row.vertical.id]
												?: AppSettings.parameterPadHeight(row.horizontal.id.raw, row.vertical.id.raw).dp,
											onPadHeightChange = {
												padHeights[row.horizontal.id to row.vertical.id] = it
												AppSettings.setParameterPadHeight(row.horizontal.id.raw, row.vertical.id.raw, it.value)
											},
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

				// A faint line at rest shows the name column can be dragged wider; the accent while hovered or dragged.
				if (!dragState.isDragging && itemBoundsMap.isNotEmpty()) {
					val active = dividerHovered || dividerDrag != null
					val rowsTop = itemBoundsMap.values.minOfOrNull { it.top }?.coerceAtLeast(0f) ?: 0f
					val rowsBottom = itemBoundsMap.values.maxOfOrNull { it.bottom } ?: 0f
					Canvas(Modifier.fillMaxSize()) {
						drawLine(
							if (active) colors.accent else colors.divider.copy(alpha = 0.6f),
							Offset(dividerCenter, rowsTop),
							Offset(dividerCenter, rowsBottom.coerceAtMost(size.height)),
							strokeWidth = (if (active) 1.5.dp else 1.dp).toPx(),
						)
					}
				}

				VerticalScrollbar(
					adapter = rememberScrollbarAdapter(listState),
					modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
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
			.size(ParamRowHandleWidth)
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
			.padding(start = (4 + row.depth * ParamRowDepthIndent).dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
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
	val controlled = param.id.raw in LocalParameterPhysicsStatus.current.controlled
	Text(
		text = param.name,
		style = typography.body.copy(
			fontSize = 11.sp,
			fontWeight = if (locked) FontWeight.SemiBold else FontWeight.Normal,
		),
		color = if (controlled) colors.textDisabled else if (locked) colors.accent else colors.textPrimary,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier,
	)
}

/**
 * The value box of a parameter row. It follows the live pose while a motion plays, so until it is clicked or
 * focused it is plain text in the field's frame: a text field composed again on every frame costs more than the
 * rest of the row. Focus (a click or Tab) swaps in the editable field with the exact value.
 */
@Composable
private fun ParameterValueInput(param: Parameter, value: () -> Float, onValueChange: (Float) -> Unit) {
	val enabled = param.id.raw !in LocalParameterPhysicsStatus.current.controlled
	var editing by remember(param.id) { mutableStateOf(false) }
	if (editing) {
		ParameterValueEditor(param, value, enabled, onValueChange) { editing = false }
		return
	}
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val currentValue by rememberUpdatedState(value)
	val shown by remember(param.id) { derivedStateOf { formatParamValue(currentValue()) } }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Box(
		modifier = Modifier.width(44.dp).height(18.dp)
			.semantics { contentDescription = param.name + " (" + param.id.raw + ")" }
			.background(colors.inputBackground, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, if (hovered && enabled) colors.borderHover else colors.border), RoundedCornerShape(2.dp))
			.hoverable(interaction, enabled)
			.onFocusChanged { if (it.isFocused && enabled) editing = true }
			.focusable(enabled, interaction)
			.pointerHoverIcon(if (enabled) PointerIcon.Text else PointerIcon.Default)
			.pointerInput(enabled) { if (enabled) detectTapGestures(onPress = { editing = true }) }
			.padding(horizontal = 6.dp),
		contentAlignment = Alignment.CenterStart,
	) {
		Text(
			text = shown,
			style = typography.mono.copy(color = if (enabled) colors.textPrimary else colors.textDisabled, fontSize = 11.5.sp),
			maxLines = 1,
			softWrap = false,
		)
	}
}

/** The value box being edited: focused as it appears, it commits on Enter or when focus leaves, then closes. */
@Composable
private fun ParameterValueEditor(param: Parameter, value: () -> Float, enabled: Boolean, onValueChange: (Float) -> Unit, onClose: () -> Unit) {
	val enabledState by rememberUpdatedState(enabled)
	val focusManager = LocalFocusManager.current
	val focusRequester = remember { FocusRequester() }
	var focused by remember(param.id) { mutableStateOf(false) }
	var draft by remember(param.id) { mutableStateOf(value().toString()) }
	LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() }.onFailure { onClose() } }
	CompactTextField(
		value = draft,
		enabled = enabled,
		onValueChange = { draft = it },
		isMono = true,
		onCommit = { focusManager.clearFocus() },
		modifier = Modifier.width(44.dp)
			.semantics { contentDescription = param.name + " (" + param.id.raw + ")" }
			.focusRequester(focusRequester)
			.onFocusChanged { focus ->
				if (focused && !focus.isFocused) {
					if (enabledState) draft.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.let {
						onValueChange(it.coerceIn(param.min, param.max))
					}
					onClose()
				}
				focused = focus.isFocused
			},
		height = 18.dp,
	)
}

private val ParamRowLinkWidth = 12.dp
private val ParamRowLinkSpacer = 1.dp
private val ParamRowNameWidth = AppSettings.DEFAULT_PARAMETER_NAME_WIDTH.dp
private val ParamRowNameWidthRange = 24.dp..240.dp
private val ParamRowDividerWidth = 7.dp
private val ParamRowInputWidth = 44.dp
private val ParamRowInputSpacer = 2.dp
private val ParamRowResetWidth = 14.dp
private val ParamRowHandleWidth = 14.dp
private val ParamRowDepthIndent = 8
/** Where the name column starts in a depth-0 row: row padding, then the link slot. */
private val ParamRowNameStart = 2.dp + ParamRowLinkWidth + ParamRowLinkSpacer

private val ParamTrackInsetHorizontal = 6.dp
private val ParamPadInsetVertical = 14.dp
private val ParamKeyRadius = 2.8.dp
private val ParamThumbRadius = 5.2.dp

/**
 * Cubism Parameter palette track: thin line + hollow key dots + live2d handle (blue with white core on-key).
 * Left drag = free scrub. Hover a key then right-click = snap to that key.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParameterTrack(
	/** Read where the track draws: a live value moves the thumb without composing the row again. */
	value: () -> Float,
	onValueChange: (Float) -> Unit,
	valueRange: ClosedFloatingPointRange<Float>,
	keyMarks: List<SliderKeyMark>,
    highlightedKeys: List<Float> = emptyList(),
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	thumbShape: SliderKeyShape = SliderKeyShape.Circle,
	onHoverKey: ((key: Float?, trackCoords: LayoutCoordinates?, localY: Float) -> Unit)? = null,
	onGestureStart: () -> Unit = {},
	onGestureEnd: () -> Unit = {},
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
					try {
						onGestureStart()
						val clickedKey = hitKey(down.position.x, size.width.toFloat(), inset, 7.dp.toPx())
						changeValue(clickedKey ?: valueOf(down.position.x, size.width.toFloat(), inset))
						down.consume()
						drag(down.id) { change ->
							change.consume()
							changeValue(valueOf(change.position.x, size.width.toFloat(), inset))
						}
					} finally {
						onGestureEnd()
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
		val value = value().coerceIn(valueRange.start, valueRange.endInclusive)
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
		tooltip = { ParameterTooltip(tooltip) },
		delayMillis = 400,
	) { content() }
}

@Composable
internal fun ParameterTooltip(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Surface(
		color = colors.panelElevated,
		shape = RoundedCornerShape(3.dp),
		border = BorderStroke(1.dp, colors.border),
		elevation = 4.dp,
	) {
		Text(
			text = text,
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textPrimary,
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
		)
	}
}

/** Width of the name column at depth 0; deeper rows are narrower by their indent so the divider lines up. */
private val LocalParameterNameWidth = compositionLocalOf { ParamRowNameWidth }

/** Thin line between the names and the tracks; the list hovers and drags it for every row at once. */
@Composable
private fun ParameterNameDivider(pad: Boolean = false) {
	val colors = LocalToolColors.current
	Box(
		modifier = Modifier
			.width(ParamRowDividerWidth)
			.fillMaxHeight(),
		contentAlignment = Alignment.Center,
	) {
		Box(
			Modifier
				.width(0.5.dp)
				.then(if (pad) Modifier.fillMaxHeight().padding(
					top = ParamPadInsetVertical,
					bottom = ParamPadInsetVertical + ParamRowDividerWidth,
				) else Modifier.fillMaxHeight(0.7f))
				.background(colors.divider),
		)
	}
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
	val controlled = param.id.raw in LocalParameterPhysicsStatus.current.controlled
	val currentValue = { liveValue(param, state, viewModel) }
	val currentState by rememberUpdatedState(state)
	val changed by remember(param) { derivedStateOf { abs(liveValue(param, currentState, viewModel) - param.default) > 0.001f } }
	val sliderMarks = remember(keyMarks) { keyMarks.toSliderMarks() }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val canLink = nextSiblingParam != null

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.background(if (isLocked) colors.selection.copy(alpha = 0.22f) else Color.Transparent)
			.padding(start = (2 + depth * ParamRowDepthIndent).dp, end = 0.dp, top = 1.dp, bottom = 1.dp)
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
		EditableParameterName(param, depth, isLocked, currentValue, state, viewModel, related)
		ParameterNameDivider()
		ParameterTrack(
			value = currentValue,
			enabled = !controlled,
			onValueChange = { viewModel.setParameterValueFromPanel(param.id, it) },
			valueRange = param.min..param.max,
			keyMarks = sliderMarks,
            highlightedKeys = selectedKeys,
			modifier = Modifier.weight(1f).alpha(if (controlled) 0.45f else 1f),
			thumbShape = if (param.kind == ParameterKind.BLEND_SHAPE) SliderKeyShape.Square else SliderKeyShape.Circle,
			onHoverKey = onKeyHover,
			onGestureStart = viewModel::beginParameterScrub,
			onGestureEnd = viewModel::endParameterScrub,
		)
		ParameterValueInput(param, currentValue, { viewModel.setParameterValueFromPanel(param.id, it) })
		Spacer(Modifier.width(ParamRowInputSpacer))
		CompactIconButton(
			onClick = { viewModel.resetParameter(param.id) },
			enabled = !controlled && (isLocked || changed),
			size = ParamRowResetWidth,
			tooltip = tr("parameters.resetTooltip"),
		) {
			IconReset(modifier = Modifier.size(8.dp), tint = if (isLocked) colors.accent else colors.textMuted)
		}
		ParameterDragHandle(onDragPress = onDragPress, rowCoords = rowCoords)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkedParameterPad(
	horizontal: Parameter,
	vertical: Parameter,
	padHeight: Dp,
	onPadHeightChange: (Dp) -> Unit,
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
	val xControlled = horizontal.id.raw in LocalParameterPhysicsStatus.current.controlled
	val yControlled = vertical.id.raw in LocalParameterPhysicsStatus.current.controlled
	val xValue = { liveValue(horizontal, state, viewModel) }
	val yValue = { liveValue(vertical, state, viewModel) }
	val currentState by rememberUpdatedState(state)
	val xChanged by remember(horizontal) { derivedStateOf { abs(liveValue(horizontal, currentState, viewModel) - horizontal.default) > 0.001f } }
	val yChanged by remember(vertical) { derivedStateOf { abs(liveValue(vertical, currentState, viewModel) - vertical.default) > 0.001f } }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val density = LocalDensity.current
	val currentPadHeight by rememberUpdatedState(padHeight)
	val changePadHeight by rememberUpdatedState(onPadHeightChange)
	var resizeCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val resizeInteraction = remember { MutableInteractionSource() }
	val resizeHovered by resizeInteraction.collectIsHoveredAsState()
	var resizing by remember { mutableStateOf(false) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { rowCoords = it }
			.padding(start = (2 + depth * ParamRowDepthIndent).dp, end = 0.dp, top = 3.dp, bottom = 3.dp)
			.height(padHeight),
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
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			EditableParameterName(horizontal, depth, xLocked, xValue, state, viewModel, horizontal.id in relatedIds)
			EditableParameterName(vertical, depth, yLocked, yValue, state, viewModel, vertical.id in relatedIds)
		}
		ParameterNameDivider(pad = true)
		Column(Modifier.weight(1f).fillMaxHeight()) {
			ParameterPad2D(
				horizontal = horizontal,
				vertical = vertical,
				xValue = xValue,
				yValue = yValue,
				xLocked = xLocked || xControlled,
				yLocked = yLocked || yControlled,
				horizontalKeys = horizontalKeys,
	            highlightedX = highlightedX,
	            highlightedY = highlightedY,
				verticalKeys = verticalKeys,
				modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (xControlled && yControlled) 0.45f else 1f),
				onChange = { x, y ->
					val values = buildMap {
						if (!xLocked && !xControlled) put(horizontal.id, x)
						if (!yLocked && !yControlled) put(vertical.id, y)
					}
					viewModel.setParameterValuesFromPanel(values)
				},
				onHoverKey = onKeyHover,
				onGestureStart = viewModel::beginParameterScrub,
				onGestureEnd = viewModel::endParameterScrub,
			)
			TooltipArea(tooltip = { ParameterTooltip(tr("parameters.padHeightTooltip")) }) {
				Box(
					Modifier.fillMaxWidth().height(ParamRowDividerWidth)
						.onGloballyPositioned { resizeCoords = it }
						.hoverable(resizeInteraction)
						.semantics { contentDescription = tr("parameters.padHeightTooltip") }
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)))
						.pointerInput(density) {
							awaitEachGesture {
								val down = awaitFirstDown()
								val splitter = resizeCoords ?: return@awaitEachGesture
								if (!splitter.isAttached) return@awaitEachGesture
								val startMouseY = splitter.positionInWindow().y + down.position.y
								val startHeight = currentPadHeight
								down.consume()
								resizing = true
								try {
									while (true) {
										val event = awaitPointerEvent()
										val change = event.changes.firstOrNull { it.id == down.id } ?: break
										if (!change.pressed) break
										change.consume()
										if (splitter.isAttached) {
											val mouseY = splitter.positionInWindow().y + change.position.y
											val delta = with(density) { (mouseY - startMouseY).toDp() }
											changePadHeight((startHeight + delta).coerceIn(64.dp, 320.dp))
										}
									}
								} finally {
									resizing = false
								}
							}
						},
					contentAlignment = Alignment.Center,
				) {
					Box(Modifier.fillMaxWidth().padding(horizontal = ParamTrackInsetHorizontal)
						.height(if (resizeHovered || resizing) 1.5.dp else 0.5.dp)
						.background(if (resizeHovered || resizing) colors.accent else colors.divider))
				}
			}
		}
		Column(
			modifier = Modifier.width(ParamRowInputWidth),
			verticalArrangement = Arrangement.spacedBy(8.dp),
			horizontalAlignment = Alignment.End,
		) {
			ParameterValueInput(horizontal, xValue) { viewModel.setParameterValueFromPanel(horizontal.id, it) }
			ParameterValueInput(vertical, yValue) { viewModel.setParameterValueFromPanel(vertical.id, it) }
		}
		Spacer(Modifier.width(ParamRowInputSpacer))
		Column(
			modifier = Modifier.width(ParamRowResetWidth),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactIconButton(
				onClick = { viewModel.resetParameter(horizontal.id) },
				enabled = !xControlled && (xLocked || xChanged),
				size = ParamRowResetWidth,
				tooltip = tr("parameters.resetTooltip"),
			) {
				IconReset(modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
			CompactIconButton(
				onClick = { viewModel.resetParameter(vertical.id) },
				enabled = !yControlled && (yLocked || yChanged),
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
	/** Read where the pad draws and when a gesture needs them, like [ParameterTrack]'s value. */
	xValue: () -> Float,
	yValue: () -> Float,
	xLocked: Boolean,
	yLocked: Boolean,
	horizontalKeys: ParameterKeyMarks?,
    highlightedX: List<Float>,
    highlightedY: List<Float>,
	verticalKeys: ParameterKeyMarks?,
	modifier: Modifier,
	onChange: (Float, Float) -> Unit,
	onHoverKey: ((xKey: Float?, yKey: Float?, trackCoords: LayoutCoordinates?, localY: Float) -> Unit)? = null,
	onGestureStart: () -> Unit = {},
	onGestureEnd: () -> Unit = {},
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
	val xValueNow by rememberUpdatedState(xValue)
	val yValueNow by rememberUpdatedState(yValue)
	val xKeysState by rememberUpdatedState(xKeyList)
	val yKeysState by rememberUpdatedState(yKeyList)
	val hMin by rememberUpdatedState(horizontal.min)
	val hMax by rememberUpdatedState(horizontal.max)
	val vMin by rememberUpdatedState(vertical.min)
	val vMax by rememberUpdatedState(vertical.max)

	var hoverKey by remember { mutableStateOf<Pair<Float, Float>?>(null) }
	var padCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val labelMeasurer = rememberTextMeasurer()

	// Same horizontal inset as ParameterTrack so the pad's x range lines up with the sliders above and below.
	val insetHorizontalDp = ParamTrackInsetHorizontal
	val insetVerticalDp = ParamPadInsetVertical
	val keyRadiusDp = ParamKeyRadius
	val thumbRadiusDp = ParamThumbRadius

	Canvas(
		modifier = modifier
			.onGloballyPositioned { padCoords = it }
			.pointerHoverIcon(if (xLocked && yLocked) PointerIcon.Default
				else PointerIcon(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)))
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
				if ((xLockedState && yLockedState) || event.button != PointerButton.Secondary) return@onPointerEvent
				val hit = hoverKey ?: return@onPointerEvent
				onChangeState(
					if (!xLockedState) hit.first else xValueNow(),
					if (!yLockedState) hit.second else yValueNow(),
				)
				event.changes.forEach { it.consume() }
			}
			.pointerInput(horizontal.id, vertical.id, xLocked && yLocked) {
				if (xLocked && yLocked) return@pointerInput
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
							if (!xLockedState) hMin + nx * (hMax - hMin) else xValueNow(),
							if (!yLockedState) vMax - ny * (vMax - vMin) else yValueNow(),
						)
					}
					try {
						onGestureStart()
						freeAt(down.position)
						down.consume()
						drag(down.id) { change ->
							change.consume()
							freeAt(change.position)
						}
					} finally {
						onGestureEnd()
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
			drawText(labelMeasurer, label, topLeft = Offset(insetX + ParamKeyRadius.toPx() + 2.dp.toPx(), yPx(ky) - layout.size.height / 2f), style = labelStyle)
		}

		val xValue = xValue()
		val yValue = yValue()
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

/**
 * What the canvases show for [param]: the slider being dragged, else the evaluated frame (animation, the pointer's
 * look, paused physics, or the open motion at the playhead), else the authored pose. Whichever canvas has focus,
 * the sliders move with the model. Reads [PSD2LiveViewModel.livePoseOf]; a row reads it where it shows it (the
 * track's and pad's draw, the value box's text, whether reset applies), so a live value redraws those parts only.
 */
private fun liveValue(param: Parameter, state: PSD2LiveState, viewModel: PSD2LiveViewModel): Float {
	viewModel.parameterScrubValueOf(param.id)?.let { return it }
	val document = state.parameterValues[param.id] ?: param.default
	return viewModel.livePoseOf(param.id) ?: document
}

private fun formatParamValue(value: Float): String =
	if (abs(value) >= 10f) "%.1f".format(value) else "%.2f".format(value)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun EditableParameterName(
    param: Parameter,
    depth: Int,
    locked: Boolean,
    value: () -> Float,
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    related: Boolean,
) {
    var editing by remember(param.id) { mutableStateOf(false) }
    val editable = state.historySnapshot != null && !state.canvasEditBusy
    val physics = LocalParameterPhysicsStatus.current
    val physicsOutput = param.id.raw in physics.outputs
    val controlled = param.id.raw in physics.controlled
    val colors = LocalToolColors.current
    TooltipArea(
        tooltip = { ParameterTooltip(param.name + if (physicsOutput) " · " +
            tr(if (controlled) "parameters.physicsControlled" else "parameters.physicsOutput") else "") },
        modifier = Modifier.width(LocalParameterNameWidth.current - (depth * ParamRowDepthIndent).dp),
        delayMillis = 400,
    ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
    if (physicsOutput) {
        IconPhysics(active = controlled, modifier = Modifier.size(10.dp), tint = if (controlled) colors.textDisabled else colors.textMuted)
        Spacer(Modifier.width(2.dp))
    }
    ParameterName(
        param,
        locked = locked,
        modifier = Modifier
            .weight(1f)
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
    }
    }
    if (editing) ParameterDefinitionDialog(param, state, viewModel, lockValue = value()) { editing = false }
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

