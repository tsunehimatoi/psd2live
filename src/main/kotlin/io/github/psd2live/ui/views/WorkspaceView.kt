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
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CreatePlacementKind
import io.github.psd2live.ui.CreateRelation
import io.github.psd2live.ui.ComponentPalette
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuHeader
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconCollapseAll
import io.github.psd2live.ui.components.IconCollapseBranch
import io.github.psd2live.ui.components.IconDeformPath
import io.github.psd2live.ui.components.IconDrawOrder
import io.github.psd2live.ui.components.IconExpandAll
import io.github.psd2live.ui.components.IconExpandBranch
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconMoveToRoot
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.components.DrawOrderRuler
import io.github.psd2live.ui.components.DrawOrderInputDialog
import io.github.psd2live.ui.components.MeshSettingsDialog
import io.github.psd2live.ui.components.MeshSettingsDialogTarget
import io.github.psd2live.ui.components.IconContextualWarp
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.RebuildMeshPromptDialog
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspaceTabKind
import io.github.psd2live.ui.state.canvasMode
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import org.umamo.runtime.model.Deformer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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

	Box(modifier = modifier.fillMaxSize()) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.background(colors.panelBackground)
				.border(BorderStroke(1.dp, colors.divider)),
		) {
			// Browser-style tab strip with the integrated deform-path tool
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(26.dp)
					.background(colors.windowBackground)
					.border(BorderStroke(1.dp, colors.divider)),
				verticalAlignment = Alignment.CenterVertically,
			) {
				WorkspaceTabStrip(
					state = state,
					viewModel = viewModel,
					modifier = Modifier.weight(1f),
				)

			}

			// Main workspace area: the active tab owns its canvas mode and view options.
			// The hierarchy sidebar is a full-height sibling of (canvas + log), so the log
			// dock never sits under the tree.
			val activeTab = state.activeWorkspaceTab
			Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
				key(activeTab.id) {
					when (activeTab.kind) {
						WorkspaceTabKind.HISTORY -> Column(modifier = Modifier.fillMaxSize()) {
							HistoryTreeView(
								state = state,
								viewModel = viewModel,
								modifier = Modifier.weight(1f).fillMaxWidth(),
							)
							BottomLogDock(
								state = state,
								viewModel = viewModel,
							)
						}
						WorkspaceTabKind.EDIT, WorkspaceTabKind.PREVIEW -> HierarchyView(
							state = state,
							viewModel = viewModel,
							canvasMode = activeTab.kind.canvasMode ?: CanvasMode.EDIT,
							onRequestOpenDeformPaths = { layerId ->
								viewModel.selectLayer(layerId)
								viewModel.requestCanvasPathTool()
							},
							onRequestCreate = { kind, relation, isDeformer, id ->
								viewModel.canvasEditor.beginTreeCreate(kind, relation, isDeformer, id)
							},
						)
					}
				}
			}
		}

		val editor = viewModel.canvasEditor
		if (editor.showRebuildMeshDialog) {
			RebuildMeshPromptDialog(
				layerName = editor.paintSession?.layerName ?: "",
				onConfirmRebuild = { editor.commitPaintSession(rebuildMesh = true) },
				onKeepExisting = { editor.commitPaintSession(rebuildMesh = false) },
				onDismiss = { editor.showRebuildMeshDialog = false },
			)
		}
	}
}

@Composable
private fun HierarchyView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	canvasMode: CanvasMode,
	onRequestOpenDeformPaths: ((String) -> Unit)? = null,
	onRequestCreate: ((CreatePlacementKind, CreateRelation, Boolean, String) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val model = state.previewModel

	val treeWidth = state.hierarchyWidth.dp
	val isTreeCollapsed = state.hierarchyCollapsed

	val meshPreviewHover = remember(model) { mutableStateOf<MeshPreviewHover?>(null) }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var splitterCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var activeDrawOrderTarget by remember { mutableStateOf<DrawOrderDialogTarget?>(null) }
	var activeMeshSettingsTarget by remember { mutableStateOf<MeshSettingsDialogTarget?>(null) }

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
						CompositionLocalProvider(LocalMeshPreviewHover provides meshPreviewHover) {
						HierarchyTreeList(
							model = model,
							state = state,
							viewModel = viewModel,
							onRequestSetOrder = { targetId, name, currentOrder, defaultOrder, isOverridden ->
								activeDrawOrderTarget = DrawOrderDialogTarget(targetId, name, currentOrder, defaultOrder, isOverridden)
							},
							onRequestSetMeshSettings = { target ->
								activeMeshSettingsTarget = target
							},
							onRequestOpenDeformPaths = onRequestOpenDeformPaths,
							onRequestCreate = onRequestCreate,
						)
						}
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
										viewModel.setHierarchyView(width = widthDp.value.coerceIn(100f, 600f))
									}
								}
							}
						},
				)
			}

			// Right: canvas stacked above the shared log dock. The tree to the left
			// keeps the remaining height, so expanding the log only shrinks the viewport.
			Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
				Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
					CanvasViewportComposable(
						mode = canvasMode,
						state = state,
						viewModel = viewModel,
						modifier = Modifier.fillMaxSize(),
						onLayerClicked = { viewModel.selectLayer(it) },
					)
				}
				BottomLogDock(
					state = state,
					viewModel = viewModel,
				)
			}
		}

		if (!isTreeCollapsed && model != null) {
			meshPreviewHover.value?.let { hover ->
				val originY = rowCoords?.takeIf { it.isAttached }?.positionInRoot()?.y ?: 0f
				HierarchyMeshPreview(model, hover.drawableId, treeWidth + 4.dp, hover.centerYInRoot - originY)
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

		// Modal Dialog for setting Part Mesh Settings
		if (activeMeshSettingsTarget != null) {
			val target = activeMeshSettingsTarget!!
			MeshSettingsDialog(
				target = target,
				onConfirm = { newSettings ->
					viewModel.setPartMeshSettings(target.layerId, newSettings)
					activeMeshSettingsTarget = null
				},
				onReset = {
					viewModel.resetPartMeshSettings(target.layerId)
					activeMeshSettingsTarget = null
				},
				onDismiss = {
					activeMeshSettingsTarget = null
				},
			)
		}
	}
}

@Composable
internal fun DockHierarchyView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	canvasMode: CanvasMode,
	onRequestOpenDeformPaths: ((String) -> Unit)? = null,
	onRequestCreate: ((CreatePlacementKind, CreateRelation, Boolean, String) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val model = state.previewModel

	val meshPreviewHover = remember(model) { mutableStateOf<MeshPreviewHover?>(null) }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var splitterCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var activeDrawOrderTarget by remember { mutableStateOf<DrawOrderDialogTarget?>(null) }
	var activeMeshSettingsTarget by remember { mutableStateOf<MeshSettingsDialogTarget?>(null) }

	Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().onGloballyPositioned { rowCoords = it }) {
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
						CompositionLocalProvider(LocalMeshPreviewHover provides meshPreviewHover) {
						HierarchyTreeList(
							model = model,
							state = state,
							viewModel = viewModel,
							onRequestSetOrder = { targetId, name, currentOrder, defaultOrder, isOverridden ->
								activeDrawOrderTarget = DrawOrderDialogTarget(targetId, name, currentOrder, defaultOrder, isOverridden)
							},
							onRequestSetMeshSettings = { target ->
								activeMeshSettingsTarget = target
							},
							onRequestOpenDeformPaths = onRequestOpenDeformPaths,
							onRequestCreate = onRequestCreate,
						)
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

		// Modal Dialog for setting Part Mesh Settings
		if (activeMeshSettingsTarget != null) {
			val target = activeMeshSettingsTarget!!
			MeshSettingsDialog(
				target = target,
				onConfirm = { newSettings ->
					viewModel.setPartMeshSettings(target.layerId, newSettings)
					activeMeshSettingsTarget = null
				},
				onReset = {
					viewModel.resetPartMeshSettings(target.layerId)
					activeMeshSettingsTarget = null
				},
				onDismiss = {
					activeMeshSettingsTarget = null
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
private const val TREE_INDENT_STEP_DP = 14
private const val TREE_BASE_PADDING_DP = 4
/** Horizontal center of the type icon: 1.dp leading spacer + half of [TREE_ICON_SIZE_DP]. */
private const val TREE_DOT_OFFSET_DP = 7
private const val TREE_ICON_SIZE_DP = 12
private const val TREE_CHEVRON_WIDTH_DP = 10

private enum class HierarchyIconKind { WARP, ROTATION, MESH }

@Composable
private fun HierarchyTypeIcon(
	kind: HierarchyIconKind,
	tint: Color,
	modifier: Modifier = Modifier.size(TREE_ICON_SIZE_DP.dp),
) {
	when (kind) {
		HierarchyIconKind.WARP -> IconWarpDeformer(tint = tint, modifier = modifier)
		HierarchyIconKind.ROTATION -> IconRotationDeformer(modifier = modifier, tint = tint)
		HierarchyIconKind.MESH -> IconMeshWireframe(tint = tint, modifier = modifier)
	}
}

private fun deformerIconKind(deformer: Deformer): HierarchyIconKind =
	if (deformer is Deformer.Warp) HierarchyIconKind.WARP else HierarchyIconKind.ROTATION

private data class CompactedDeformerChain(
	val deformers: List<Deformer>,
) {
	val head: Deformer get() = deformers.first()
	val tail: Deformer get() = deformers.last()
	val isChained: Boolean get() = deformers.size > 1
	val displayName: String get() = deformers.joinToString("\\") { it.name }
}

/** Effective parent after applying workspace reparent overrides (`null` = root). */
private fun effectiveParent(id: String, defaultParent: String?, overrides: Map<String, String?>): String? =
	if (overrides.containsKey(id)) overrides[id] else defaultParent

/**
 * Precomputed hierarchy search filter.
 * - [query]: trimmed search text used for substring highlighting
 * - [matchedIds]: name matches
 * - [visibleIds]: matches + ancestors (path) + descendants of matches (context under a hit)
 * - [expandIds]: nodes that must be expanded so matches remain reachable
 */
private data class HierarchySearchFilter(
	val active: Boolean,
	val query: String,
	val matchedIds: Set<String>,
	val visibleIds: Set<String>,
	val expandIds: Set<String>,
) {
	fun isVisible(id: String): Boolean = !active || id in visibleIds
	fun isMatch(id: String): Boolean = active && id in matchedIds
	fun isChainVisible(chain: CompactedDeformerChain): Boolean =
		!active || chain.deformers.any { it.id.raw in visibleIds }
	fun isChainMatch(chain: CompactedDeformerChain): Boolean =
		active && chain.deformers.any { it.id.raw in matchedIds }

	companion object {
		val Inactive = HierarchySearchFilter(
			active = false,
			query = "",
			matchedIds = emptySet(),
			visibleIds = emptySet(),
			expandIds = emptySet(),
		)
	}
}

/** Build labeled text that accents every case-insensitive occurrence of [query]. */
private fun highlightSearchMatches(
	text: String,
	query: String,
	baseColor: Color,
	highlightColor: Color,
): AnnotatedString {
	if (query.isEmpty()) {
		return AnnotatedString(text, spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = baseColor), 0, text.length)))
	}
	val lowerText = text.lowercase()
	val lowerQuery = query.lowercase()
	return buildAnnotatedString {
		var start = 0
		while (start < text.length) {
			val hit = lowerText.indexOf(lowerQuery, startIndex = start)
			if (hit < 0) {
				withStyle(SpanStyle(color = baseColor)) {
					append(text.substring(start))
				}
				break
			}
			if (hit > start) {
				withStyle(SpanStyle(color = baseColor)) {
					append(text.substring(start, hit))
				}
			}
			withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold)) {
				append(text.substring(hit, hit + query.length))
			}
			start = hit + query.length
		}
	}
}

private fun buildHierarchyChildrenMaps(
	deformers: List<Deformer>,
	drawables: List<org.umamo.runtime.model.Drawable>,
	overrides: Map<String, String?>,
): Pair<Map<String?, List<Deformer>>, Map<String?, List<org.umamo.runtime.model.Drawable>>> {
	val deformerChildren = deformers.groupBy { d ->
		effectiveParent(d.id.raw, d.parent?.raw, overrides)
	}
	val drawableChildren = drawables.groupBy { d ->
		effectiveParent(d.id.raw, d.parentDeformerId?.raw, overrides)
	}
	return deformerChildren to drawableChildren
}

private fun buildHierarchySearchFilter(
	queryRaw: String,
	deformers: List<Deformer>,
	drawables: List<org.umamo.runtime.model.Drawable>,
	deformerChildren: Map<String?, List<Deformer>>,
	drawableChildren: Map<String?, List<org.umamo.runtime.model.Drawable>>,
	overrides: Map<String, String?>,
): HierarchySearchFilter {
	val query = queryRaw.trim()
	if (query.isEmpty()) return HierarchySearchFilter.Inactive

	val deformerById = deformers.associateBy { it.id.raw }
	val drawableById = drawables.associateBy { it.id.raw }

	val matched = linkedSetOf<String>()
	for (d in deformers) {
		if (d.name.contains(query, ignoreCase = true)) matched += d.id.raw
	}
	for (d in drawables) {
		if (d.name.contains(query, ignoreCase = true)) matched += d.id.raw
	}
	if (matched.isEmpty()) {
		return HierarchySearchFilter(
			active = true,
			query = query,
			matchedIds = emptySet(),
			visibleIds = emptySet(),
			expandIds = emptySet(),
		)
	}

	fun parentOf(id: String): String? {
		deformerById[id]?.let { return effectiveParent(id, it.parent?.raw, overrides) }
		drawableById[id]?.let { return effectiveParent(id, it.parentDeformerId?.raw, overrides) }
		return null
	}

	val visible = matched.toMutableSet()
	val expand = linkedSetOf<String>()

	for (id in matched) {
		var parent = parentOf(id)
		val seen = mutableSetOf<String>()
		while (parent != null && seen.add(parent)) {
			visible += parent
			expand += parent
			parent = parentOf(parent)
		}
	}

	fun addDescendants(id: String) {
		for (child in deformerChildren[id].orEmpty()) {
			val childId = child.id.raw
			if (visible.add(childId)) addDescendants(childId)
		}
		for (child in drawableChildren[id].orEmpty()) {
			visible += child.id.raw
		}
	}

	for (id in matched) {
		if (id in deformerById) {
			val hasKids = deformerChildren[id].orEmpty().isNotEmpty() || drawableChildren[id].orEmpty().isNotEmpty()
			if (hasKids) expand += id
			addDescendants(id)
		}
	}

	return HierarchySearchFilter(
		active = true,
		query = query,
		matchedIds = matched,
		visibleIds = visible,
		expandIds = expand,
	)
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
	/** Specific item to select on click (compact-chain segment or layer). */
	val selectId: String,
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
	onRequestSetMeshSettings: ((MeshSettingsDialogTarget) -> Unit)? = null,
	onRequestOpenDeformPaths: ((String) -> Unit)? = null,
	onRequestCreate: ((CreatePlacementKind, CreateRelation, Boolean, String) -> Unit)? = null,
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
	var treeRowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	var rulerSplitterCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val density = LocalDensity.current

	val deformers = model.rig.puppet.deformers
	val drawables = model.rig.puppet.drawables
	val parentOverrides = state.parentOverrides

	val (deformerChildrenMap, drawableChildrenMap) = remember(deformers, drawables, parentOverrides) {
		buildHierarchyChildrenMaps(deformers, drawables, parentOverrides)
	}

	val rootDeformers = remember(deformers, parentOverrides) {
		deformers.filter { effectiveParent(it.id.raw, it.parent?.raw, parentOverrides) == null }
	}
	val rootDrawables = remember(drawables, parentOverrides) {
		drawables.filter { effectiveParent(it.id.raw, it.parentDeformerId?.raw, parentOverrides) == null }
	}

	// Resolve compacted chains for root deformers
	val rootChains = remember(rootDeformers, deformerChildrenMap, drawableChildrenMap) {
		rootDeformers.map { resolveCompactedChain(it, deformerChildrenMap, drawableChildrenMap) }
	}

	val searchFilter = remember(searchQuery, deformers, drawables, deformerChildrenMap, drawableChildrenMap, parentOverrides) {
		buildHierarchySearchFilter(
			queryRaw = searchQuery,
			deformers = deformers,
			drawables = drawables,
			deformerChildren = deformerChildrenMap,
			drawableChildren = drawableChildrenMap,
			overrides = parentOverrides,
		)
	}

	// Expand ancestors/matches outside composition so search can reveal nested hits.
	LaunchedEffect(searchFilter.expandIds, searchFilter.active) {
		if (!searchFilter.active) return@LaunchedEffect
		for (id in searchFilter.expandIds) {
			expandedMap[id] = true
		}
	}

	val scrollState = rememberScrollState()

	Column(modifier = Modifier.fillMaxSize()) {
		// Search & Expand/Collapse toolbar
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(26.dp)
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 6.dp, vertical = 2.dp)
				.tutorialTarget(TutorialTargetId.HIERARCHY_TOOLBAR),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			CompactTextField(
				value = searchQuery,
				onValueChange = { viewModel.setHierarchySearch(it) },
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
								.clickable { viewModel.setHierarchySearch("") },
							contentAlignment = Alignment.Center,
						) {
							IconClose(modifier = Modifier.size(8.dp), tint = colors.textMuted)
						}
					}
				} else null,
			)

			CompactIconButton(
				onClick = { deformers.forEach { expandedMap[it.id.raw] = true } },
				size = 20.dp,
				tooltip = tr("canvas.hierarchy.expandAll"),
			) {
				IconExpandAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
			}
			CompactIconButton(
				onClick = { deformers.forEach { expandedMap[it.id.raw] = false } },
				size = 20.dp,
				tooltip = tr("canvas.hierarchy.collapseAll"),
			) {
				IconCollapseAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
			}
		}

		// Tree Body with Container Hit-Testing & Overlay and Draw Order Ruler
		Row(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.tutorialTarget(TutorialTargetId.HIERARCHY_TREE)
				.onGloballyPositioned { treeRowCoords = it },
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
								if (state.selectedDeformerId == clickedItem.selectId) {
									viewModel.selectDeformer(null)
								} else {
									viewModel.selectDeformer(clickedItem.selectId)
								}
							} else {
								if (state.selectedLayerId == clickedItem.selectId) {
									viewModel.selectLayer(null)
								} else {
									viewModel.selectLayer(clickedItem.selectId)
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
										selectId = "ROOT",
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
							IconMoveToRoot(
								modifier = Modifier.size(11.dp),
								tint = if (isRootTarget) colors.accent else colors.textMuted,
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

				val selectedAncestorDeformerIds = remember(model, state.selectedLayerId, state.selectedDeformerId, state.parentOverrides) {
					val ancestors = mutableSetOf<String>()
					val deformerById = model.rig.puppet.deformers.associateBy { it.id.raw }
					fun collectAncestors(startParent: String?) {
						var parent = startParent
						val seen = mutableSetOf<String>()
						while (parent != null && seen.add(parent)) {
							ancestors.add(parent)
							parent = state.parentOverrides[parent] ?: deformerById[parent]?.parent?.raw
						}
					}
					state.selectedLayerId?.let { layerId ->
						val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
						val drawable = drawableId?.let { id -> model.rig.puppet.drawables.firstOrNull { it.id.raw == id } }
						collectAncestors(drawable?.let { state.parentOverrides[it.id.raw] ?: it.parentDeformerId?.raw })
					}
					state.selectedDeformerId?.let { deformerId ->
						val deformer = deformerById[deformerId]
						collectAncestors(state.parentOverrides[deformerId] ?: deformer?.parent?.raw)
					}
					ancestors
				}

				val selectedDescendantLabelByAncestor = remember(
					model, state.selectedLayerId, state.selectedDeformerId, state.parentOverrides, selectedAncestorDeformerIds,
				) {
					if (selectedAncestorDeformerIds.isEmpty()) emptyMap()
					else {
						val label = when {
							state.selectedLayerId != null -> {
								val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == state.selectedLayerId }?.key
								model.rig.puppet.drawables.firstOrNull { it.id.raw == drawableId }?.name
									?: state.selectedLayerId
							}
							state.selectedDeformerId != null ->
								model.rig.puppet.deformers.firstOrNull { it.id.raw == state.selectedDeformerId }?.name
									?: state.selectedDeformerId
							else -> null
						}
						if (label == null) emptyMap()
						else selectedAncestorDeformerIds.associateWith { label }
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
						searchFilter = searchFilter,
						selectedAncestorDeformerIds = selectedAncestorDeformerIds,
						selectedDescendantLabelByAncestor = selectedDescendantLabelByAncestor,
						onRequestSetOrder = onRequestSetOrder,
						onRequestSetMeshSettings = onRequestSetMeshSettings,
						onRequestOpenDeformPaths = onRequestOpenDeformPaths,
						onRequestCreate = onRequestCreate,
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
						searchFilter = searchFilter,
						onRequestSetOrder = onRequestSetOrder,
						onRequestSetMeshSettings = onRequestSetMeshSettings,
						onRequestOpenDeformPaths = onRequestOpenDeformPaths,
						onRequestCreate = onRequestCreate,
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
						val dragKind = if (dragItem.isDeformer) {
							val def = deformers.find { it.id.raw == dragItem.targetId }
								?: deformers.find { it.id.raw == dragItem.id }
							def?.let { deformerIconKind(it) } ?: HierarchyIconKind.WARP
						} else {
							HierarchyIconKind.MESH
						}
						HierarchyTypeIcon(
							kind = dragKind,
							tint = if (dragItem.isDeformer) colors.accent else colors.textPrimary,
							modifier = Modifier.size(11.dp),
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

			// Resizable Splitter Handle between Tree List and Draw Order Ruler
			Box(
				modifier = Modifier
					.width(3.dp)
					.fillMaxHeight()
					.background(colors.divider)
					.onGloballyPositioned { rulerSplitterCoords = it }
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
								val row = treeRowCoords
								val splitter = rulerSplitterCoords
								if (row != null && splitter != null && row.isAttached && splitter.isAttached) {
									val mouseInRow = row.localPositionOf(splitter, change.position)
									val mouseX = mouseInRow.x - grabOffset
									val rulerWidthPx = row.size.width - mouseX
									val widthDp = with(density) { rulerWidthPx.toDp() }
									viewModel.setDrawOrderRulerWidth(widthDp.value)
								}
							}
						}
					},
			)

			// Draw Order Ruler
			DrawOrderRuler(
				model = model,
				state = state,
				viewModel = viewModel,
				width = state.drawOrderRulerWidth.dp,
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
	searchFilter: HierarchySearchFilter = HierarchySearchFilter.Inactive,
	selectedAncestorDeformerIds: Set<String> = emptySet(),
	selectedDescendantLabelByAncestor: Map<String, String> = emptyMap(),
	onRequestSetOrder: ((targetId: String, name: String, currentOrder: Float, defaultOrder: Float, isOverridden: Boolean) -> Unit)? = null,
	onRequestSetMeshSettings: ((MeshSettingsDialogTarget) -> Unit)? = null,
	onRequestOpenDeformPaths: ((String) -> Unit)? = null,
	onRequestCreate: ((CreatePlacementKind, CreateRelation, Boolean, String) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val headDeformer = chain.head
	val tailDeformer = chain.tail
	val headId = headDeformer.id.raw
	val tailId = tailDeformer.id.raw

	if (!searchFilter.isChainVisible(chain)) {
		return
	}

	val isExpanded = expandedMap[headId] ?: true
	val selectedInChain = chain.deformers.firstOrNull { it.id.raw == state.selectedDeformerId }
	val isSelected = selectedInChain != null
	val isAncestorOfSelected = !isSelected && chain.deformers.any { it.id.raw in selectedAncestorDeformerIds }
	val collapsedSelectedLabel = if (!isExpanded && isAncestorOfSelected) {
		chain.deformers.asReversed().firstNotNullOfOrNull { selectedDescendantLabelByAncestor[it.id.raw] }
	} else null
	val type = if (tailDeformer is Deformer.Warp) "Warp" else "Rotation"

	val childDeformers = deformerChildrenMap[tailId].orEmpty()
	val childDrawables = drawableChildrenMap[tailId].orEmpty()
	val hasChildren = childDeformers.isNotEmpty() || childDrawables.isNotEmpty()

	// Compacted child chains
	val childChains = remember(childDeformers, deformerChildrenMap, drawableChildrenMap) {
		childDeformers.map { resolveCompactedChain(it, deformerChildrenMap, drawableChildrenMap) }
	}

	val visibleChildChains = if (searchFilter.active) {
		childChains.filter { searchFilter.isChainVisible(it) }
	} else {
		childChains
	}
	val visibleChildDrawables = if (searchFilter.active) {
		childDrawables.filter { searchFilter.isVisible(it.id.raw) }
	} else {
		childDrawables
	}

	val guideColor = Color(0xFFE4E7EC).copy(alpha = 0.42f)
	val activeGuideColor = if (isSelected) colors.selectionText.copy(alpha = 0.9f) else guideColor

	val isCurrentDragged = treeDragState.isDragging && treeDragState.draggedId == headId
	val isHoverTarget = treeDragState.isDragging && treeDragState.hoverTargetId == tailId

	var isHovered by remember { mutableStateOf(false) }
	var hoveredSegmentId by remember { mutableStateOf<String?>(null) }
	var showMenu by remember { mutableStateOf(false) }
	var menuClickOffset by remember { mutableStateOf(Offset.Zero) }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val segmentBoundsInRow = remember { mutableStateMapOf<String, Rect>() }
	val fallbackSelectId = selectedInChain?.id?.raw ?: tailId

	fun resolveSegmentId(rowLocalPos: Offset): String {
		segmentBoundsInRow.entries.firstOrNull { (_, rect) ->
			rowLocalPos.x >= rect.left && rowLocalPos.x <= rect.right &&
				rowLocalPos.y >= rect.top && rowLocalPos.y <= rect.bottom
		}?.key?.let { return it }
		return hoveredSegmentId ?: fallbackSelectId
	}

	fun beginSegmentPress(segmentId: String, rowLocalPos: Offset) {
		val parent = containerCoordinates
		val coords = rowCoords
		if (parent == null || coords == null || !parent.isAttached || !coords.isAttached) return
		val containerPos = parent.localPositionOf(coords, rowLocalPos)
		val topLeft = parent.localPositionOf(coords, Offset.Zero)
		treeDragState.onPress(
			ItemLayoutInfo(
				id = headId,
				targetId = tailId,
				selectId = segmentId,
				name = chain.displayName,
				isDeformer = true,
				currentParentId = effectiveParent(headId, headDeformer.parent?.raw, state.parentOverrides),
				top = topLeft.y,
				bottom = topLeft.y + coords.size.height,
			),
			containerPos,
		)
	}

	val startX = (TREE_BASE_PADDING_DP + depth * TREE_INDENT_STEP_DP).dp
	val menuIconAwt = ComponentPalette.strong((selectedInChain ?: tailDeformer).id.raw)

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
						isAncestorOfSelected -> colors.accent.copy(alpha = 0.14f)
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
							selectId = fallbackSelectId,
							name = chain.displayName,
							isDeformer = true,
							currentParentId = effectiveParent(headId, headDeformer.parent?.raw, state.parentOverrides),
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

						// Horizontal branch into type icon
						val branchEndX = (startX + 1.dp).toPx()
						drawLine(
							color = activeGuideColor,
							start = Offset(parentDotX, midY),
							end = Offset(branchEndX, midY),
							strokeWidth = 1.2f,
						)
					}

					// When expanded with children, draw line from bottom of icon down to row bottom
					if (isExpanded && hasChildren) {
						val myDotX = (TREE_BASE_PADDING_DP + depth * TREE_INDENT_STEP_DP + TREE_DOT_OFFSET_DP).dp.toPx()
						drawLine(
							color = guideColor,
							start = Offset(myDotX, midY + (TREE_ICON_SIZE_DP / 2f).dp.toPx()),
							end = Offset(myDotX, size.height),
							strokeWidth = 1.2f,
						)
					}
				}
				.onPointerEvent(PointerEventType.Enter) {
					isHovered = true
					val pos = it.changes.firstOrNull()?.position
					val segmentId = if (pos != null) resolveSegmentId(pos) else fallbackSelectId
					hoveredSegmentId = segmentId
					viewModel.setHoveredItem(layerId = null, deformerId = segmentId)
				}
				.onPointerEvent(PointerEventType.Move) {
					if (!isHovered) return@onPointerEvent
					val pos = it.changes.firstOrNull()?.position ?: return@onPointerEvent
					val segmentId = resolveSegmentId(pos)
					if (hoveredSegmentId != segmentId) {
						hoveredSegmentId = segmentId
						viewModel.setHoveredItem(layerId = null, deformerId = segmentId)
					}
				}
				.onPointerEvent(PointerEventType.Exit) {
					isHovered = false
					hoveredSegmentId = null
					viewModel.setHoveredItem(null, null)
				}
				.onPointerEvent(PointerEventType.Press) { event ->
					if (event.changes.any { it.isConsumed }) return@onPointerEvent
					if (event.button == PointerButton.Secondary) {
						val clickPos = event.changes.firstOrNull()?.position ?: Offset.Zero
						menuClickOffset = clickPos
						val selectId = resolveSegmentId(clickPos)
						if (state.selectedDeformerId != selectId) {
							viewModel.selectDeformer(selectId)
						}
						treeDragState.clear()
						event.changes.firstOrNull()?.consume()
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
								selectId = resolveSegmentId(localPos),
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
			// 1. Leading type icon — first (outer) deformer in the compact chain, aligned with guideline
			Spacer(Modifier.width(1.dp))
			val headAwt = ComponentPalette.strong(headId)
			HierarchyTypeIcon(
				kind = deformerIconKind(headDeformer),
				tint = Color(headAwt.red, headAwt.green, headAwt.blue),
			)

			// 2. Folding symbol
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

			// 3. Compact chain: each compacted segment gets its own type icon (VS Code compact folders)
			val isDeformerVis = chain.deformers.all { state.isDeformerVisible(it.id.raw) }
			val selectedSegmentId = selectedInChain?.id?.raw
			val slashColor = when {
				!isDeformerVis -> colors.textDisabled
				isSelected -> colors.selectionText.copy(alpha = 0.55f)
				else -> colors.accent
			}

			Row(
				modifier = Modifier.weight(1f),
				verticalAlignment = Alignment.CenterVertically,
			) {
				chain.deformers.forEachIndexed { index, def ->
					val segmentId = def.id.raw
					val segmentSelected = segmentId == selectedSegmentId
					val segmentHovered = !segmentSelected && segmentId == hoveredSegmentId
					val textColor = when {
						!isDeformerVis -> colors.textDisabled
						segmentSelected -> colors.selectionText
						segmentHovered && isSelected -> colors.selectionText
						segmentHovered -> colors.accentHover
						isSelected -> colors.selectionText.copy(alpha = 0.62f)
						else -> colors.textPrimary
					}
					val iconTint = when {
						!isDeformerVis -> colors.textDisabled
						segmentSelected -> colors.selectionText
						segmentHovered -> colors.accentHover
						else -> {
							val awt = ComponentPalette.strong(segmentId)
							Color(awt.red, awt.green, awt.blue)
						}
					}

					if (index > 0) {
						Text(
							text = " \\ ",
							style = typography.body.copy(fontSize = 11.sp),
							color = slashColor,
							maxLines = 1,
						)
					}

					Row(
						modifier = Modifier
							.then(if (index == chain.deformers.lastIndex) Modifier.weight(1f, fill = false) else Modifier)
							.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
							.onGloballyPositioned { coords ->
								val row = rowCoords
								if (row != null && row.isAttached && coords.isAttached) {
									val topLeft = row.localPositionOf(coords, Offset.Zero)
									segmentBoundsInRow[segmentId] = Rect(
										offset = topLeft,
										size = Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
									)
								}
							}
							.onPointerEvent(PointerEventType.Enter) {
								hoveredSegmentId = segmentId
								viewModel.setHoveredItem(layerId = null, deformerId = segmentId)
							}
							.onPointerEvent(PointerEventType.Press) { event ->
								if (event.button == PointerButton.Secondary) {
									menuClickOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
									if (state.selectedDeformerId != segmentId) {
										viewModel.selectDeformer(segmentId)
									}
									treeDragState.clear()
									event.changes.firstOrNull()?.consume()
									showMenu = true
								} else if (event.button == PointerButton.Primary) {
									val row = rowCoords
									val localInSegment = event.changes.firstOrNull()?.position ?: Offset.Zero
									val rowLocal = if (row != null && row.isAttached) {
										segmentBoundsInRow[segmentId]?.let { it.topLeft + localInSegment } ?: localInSegment
									} else localInSegment
									beginSegmentPress(segmentId, rowLocal)
									event.changes.firstOrNull()?.consume()
								}
							},
						verticalAlignment = Alignment.CenterVertically,
					) {
						if (index > 0) {
							HierarchyTypeIcon(
								kind = deformerIconKind(def),
								tint = iconTint,
								modifier = Modifier.size(10.dp),
							)
							Spacer(Modifier.width(2.dp))
						}
						Text(
							text = highlightSearchMatches(
								text = def.name,
								query = if (searchFilter.isMatch(segmentId)) searchFilter.query else "",
								baseColor = textColor,
								highlightColor = if (segmentSelected || isSelected) colors.selectionText else colors.accent,
							),
							style = typography.body.copy(
								fontSize = 11.sp,
								fontWeight = if (segmentSelected || segmentHovered) FontWeight.SemiBold else FontWeight.Normal,
								textDecoration = if (segmentHovered) TextDecoration.Underline else TextDecoration.None,
							),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}

			if (collapsedSelectedLabel != null) {
				Text(
					text = collapsedSelectedLabel,
					style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
					color = colors.accent,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = 88.dp),
				)
				Spacer(Modifier.width(3.dp))
			} else if (isAncestorOfSelected) {
				Text(
					text = "[${tr("canvas.hierarchy.ancestorBadge")}]",
					style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
					color = colors.accent,
				)
				Spacer(Modifier.width(3.dp))
			}

			if (tailDeformer !is Deformer.Warp) {
				Text(
					text = "[$type]",
					style = typography.monoSmall.copy(fontSize = 9.sp),
					color = if (isSelected) colors.selectionText.copy(alpha = 0.7f) else colors.textMuted,
				)
			}

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

		// Deformer context menu: add → hierarchy → view → delete
		val menuFocus = selectedInChain ?: tailDeformer
		val menuType = if (menuFocus is Deformer.Warp) "Warp" else "Rotation"
		TreeContextMenu(
			expanded = showMenu,
			onDismissRequest = { showMenu = false },
			clickOffset = menuClickOffset,
		) {
			CompactMenuHeader(
				name = menuFocus.name,
				badge = menuType,
				icon = {
					HierarchyTypeIcon(
						kind = deformerIconKind(menuFocus),
						tint = Color(menuIconAwt.red, menuIconAwt.green, menuIconAwt.blue),
						modifier = Modifier.size(13.dp),
					)
				},
			)

			CompactMenuSection(tr("canvas.hierarchy.menuAdd"))
			CompactMenuItem(
				text = tr("editor.treeAddWarpParent"),
				onClick = {
					onRequestCreate?.invoke(CreatePlacementKind.WARP, CreateRelation.AS_PARENT, true, menuFocus.id.raw)
					showMenu = false
				},
				icon = { IconContextualWarp(tint = colors.textMuted, modifier = Modifier.size(13.dp)) },
			)
			CompactMenuItem(
				text = tr("editor.treeAddWarpChild"),
				onClick = {
					onRequestCreate?.invoke(CreatePlacementKind.WARP, CreateRelation.AS_CHILD, true, menuFocus.id.raw)
					showMenu = false
				},
				icon = { IconWarpDeformer(tint = colors.textMuted, modifier = Modifier.size(13.dp)) },
			)
			CompactMenuItem(
				text = tr("editor.treeAddRotationParent"),
				onClick = {
					onRequestCreate?.invoke(CreatePlacementKind.ROTATION, CreateRelation.AS_PARENT, true, menuFocus.id.raw)
					showMenu = false
				},
				icon = { IconRotationDeformer(tint = colors.textMuted, modifier = Modifier.size(13.dp)) },
			)

			CompactMenuDivider()
			CompactMenuSection(tr("canvas.hierarchy.menuHierarchy"))
			val isAlreadyRoot = headDeformer.parent == null
			CompactMenuItem(
				text = tr("canvas.hierarchy.moveToRoot"),
				onClick = {
					viewModel.reparentItem(headId, null)
					showMenu = false
				},
				enabled = !isAlreadyRoot,
				icon = { IconMoveToRoot(tint = if (!isAlreadyRoot) colors.textMuted else colors.textDisabled, modifier = Modifier.size(13.dp)) },
			)
			if (chain.deformers.any { state.parentOverrides.containsKey(it.id.raw) }) {
				CompactMenuItem(
					text = tr("canvas.hierarchy.resetItem"),
					onClick = {
						chain.deformers.forEach { viewModel.resetItemHierarchy(it.id.raw) }
						showMenu = false
					},
					trailingBadge = {
						Box(
							modifier = Modifier
								.background(colors.accent.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
								.padding(horizontal = 4.dp, vertical = 1.dp)
						) {
							Text(
								text = tr("settings.reset").ifEmpty { "RESET" },
								style = typography.monoSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold),
								color = colors.accent,
							)
						}
					},
					icon = { IconReset(modifier = Modifier.size(13.dp), tint = colors.accent) },
				)
			}

			CompactMenuDivider()
			CompactMenuSection(tr("canvas.hierarchy.menuView"))
			CompactMenuItem(
				text = tr("canvas.hierarchy.expandBranch"),
				onClick = {
					chain.deformers.forEach { expandedMap[it.id.raw] = true }
					fun expandRecursive(dId: String) {
						expandedMap[dId] = true
						deformerChildrenMap[dId]?.forEach { expandRecursive(it.id.raw) }
					}
					expandRecursive(tailId)
					showMenu = false
				},
				icon = { IconExpandBranch(tint = colors.textMuted, modifier = Modifier.size(13.dp)) },
			)
			CompactMenuItem(
				text = tr("canvas.hierarchy.collapseBranch"),
				onClick = {
					chain.deformers.forEach { expandedMap[it.id.raw] = false }
					showMenu = false
				},
				icon = { IconCollapseBranch(tint = colors.textMuted, modifier = Modifier.size(13.dp)) },
			)

			CompactMenuDivider()
			CompactMenuItem(
				text = tr("canvas.hierarchy.deleteDeformer"),
				onClick = {
					viewModel.deleteDeformers(chain.deformers.asReversed().map { it.id.raw })
					showMenu = false
				},
				danger = true,
				icon = { IconTrash(modifier = Modifier.size(12.dp), tint = colors.error) },
			)
		}
	}

	// Render children recursively
	if (isExpanded) {
		for ((cIndex, childChain) in visibleChildChains.withIndex()) {
			val isLast = (cIndex == visibleChildChains.lastIndex && visibleChildDrawables.isEmpty())
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
				searchFilter = searchFilter,
				selectedAncestorDeformerIds = selectedAncestorDeformerIds,
				selectedDescendantLabelByAncestor = selectedDescendantLabelByAncestor,
				onRequestSetOrder = onRequestSetOrder,
				onRequestSetMeshSettings = onRequestSetMeshSettings,
				onRequestOpenDeformPaths = onRequestOpenDeformPaths,
				onRequestCreate = onRequestCreate,
			)
		}
		for ((dIndex, childDrawable) in visibleChildDrawables.withIndex()) {
			val isLast = (dIndex == visibleChildDrawables.lastIndex)
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
				searchFilter = searchFilter,
				onRequestSetOrder = onRequestSetOrder,
				onRequestSetMeshSettings = onRequestSetMeshSettings,
				onRequestOpenDeformPaths = onRequestOpenDeformPaths,
				onRequestCreate = onRequestCreate,
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
	searchFilter: HierarchySearchFilter = HierarchySearchFilter.Inactive,
	onRequestSetOrder: ((targetId: String, name: String, currentOrder: Float, defaultOrder: Float, isOverridden: Boolean) -> Unit)? = null,
	onRequestSetMeshSettings: ((MeshSettingsDialogTarget) -> Unit)? = null,
	onRequestOpenDeformPaths: ((String) -> Unit)? = null,
	onRequestCreate: ((CreatePlacementKind, CreateRelation, Boolean, String) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val meshPreviewHover = LocalMeshPreviewHover.current
	DisposableEffect(drawable.id, meshPreviewHover) {
		onDispose {
			if (meshPreviewHover?.value?.drawableId == drawable.id.raw) meshPreviewHover.value = null
		}
	}
	val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
	val itemId = layerId ?: drawable.id.raw
	val isLayerSelected = layerId != null && state.selectedLayerId == layerId
	val isSelfVisible = layerId == null || state.isLayerVisible(layerId)
	val isEffectiveVisible = layerId == null || layerId in state.effectiveVisibleLayerIds

	if (!searchFilter.isVisible(drawable.id.raw)) {
		return
	}

	val guideColor = Color(0xFFE4E7EC).copy(alpha = 0.42f)
	val activeGuideColor = if (isLayerSelected) colors.selectionText.copy(alpha = 0.9f) else guideColor

	val isCurrentDragged = treeDragState.isDragging && treeDragState.draggedId == itemId

	var isHovered by remember { mutableStateOf(false) }
	var showMenu by remember { mutableStateOf(false) }
	var menuClickOffset by remember { mutableStateOf(Offset.Zero) }
	var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

	val startX = (TREE_BASE_PADDING_DP + depth * TREE_INDENT_STEP_DP).dp
	val layerDotAwt = if (layerId != null) ComponentPalette.strong(layerId) else java.awt.Color.GRAY

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
					if (meshPreviewHover?.value?.drawableId == drawable.id.raw) {
						meshPreviewHover.value = MeshPreviewHover(drawable.id.raw, coords.positionInRoot().y + coords.size.height / 2f)
					}
					val parent = containerCoordinates
					if (parent != null && parent.isAttached && coords.isAttached) {
						val topLeft = parent.localPositionOf(coords, Offset.Zero)
						itemBoundsMap[itemId] = ItemLayoutInfo(
							id = itemId,
							targetId = itemId,
							selectId = itemId,
							name = drawable.name,
							isDeformer = false,
							currentParentId = effectiveParent(drawable.id.raw, drawable.parentDeformerId?.raw, state.parentOverrides),
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

						// Horizontal branch into type icon
						val branchEndX = (startX + 1.dp).toPx()
						drawLine(
							color = activeGuideColor,
							start = Offset(parentDotX, midY),
							end = Offset(branchEndX, midY),
							strokeWidth = 1.2f,
						)
					}
				}
				.onPointerEvent(PointerEventType.Enter) {
					isHovered = true
					meshPreviewHover?.value = rowCoords?.takeIf { it.isAttached && drawable.mesh != null }?.let {
						MeshPreviewHover(drawable.id.raw, it.positionInRoot().y + it.size.height / 2f)
					}
					viewModel.setHoveredItem(layerId = layerId, deformerId = null)
				}
				.onPointerEvent(PointerEventType.Exit) {
					isHovered = false
					if (meshPreviewHover?.value?.drawableId == drawable.id.raw) meshPreviewHover.value = null
					viewModel.setHoveredItem(null, null)
				}
				.onPointerEvent(PointerEventType.Press) { event ->
					meshPreviewHover?.value = null
					if (event.button == PointerButton.Secondary) {
						val clickPos = event.changes.firstOrNull()?.position ?: Offset.Zero
						menuClickOffset = clickPos
						if (layerId != null && state.selectedLayerId != layerId) {
							viewModel.selectLayer(layerId)
						}
						treeDragState.clear()
						event.changes.firstOrNull()?.consume()
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
								selectId = itemId,
								name = drawable.name,
								isDeformer = false,
								currentParentId = effectiveParent(drawable.id.raw, drawable.parentDeformerId?.raw, state.parentOverrides),
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
			// 1. Drawable / ArtMesh type icon (aligned with deformer icons)
			Spacer(Modifier.width(1.dp))
			HierarchyTypeIcon(
				kind = HierarchyIconKind.MESH,
				tint = Color(layerDotAwt.red, layerDotAwt.green, layerDotAwt.blue),
			)

			// Spacer matching the Chevron slot (2.dp + 10.dp + 2.dp = 14.dp) so layer text aligns with deformer text
			Spacer(Modifier.width(14.dp))

			Text(
				text = highlightSearchMatches(
					text = drawable.name,
					query = if (searchFilter.isMatch(drawable.id.raw)) searchFilter.query else "",
					baseColor = when {
						!isEffectiveVisible -> colors.textDisabled
						isLayerSelected -> colors.selectionText
						else -> colors.textPrimary
					},
					highlightColor = if (isLayerSelected) colors.selectionText else colors.accent,
				),
				style = typography.body.copy(fontSize = 11.sp),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)

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

		// Drawable context menu: view → add → settings → hierarchy → delete
		TreeContextMenu(
			expanded = showMenu,
			onDismissRequest = { showMenu = false },
			clickOffset = menuClickOffset,
		) {
			val isAlreadyRoot = drawable.parentDeformerId == null
			val itemTypeBadge = if (drawable.mesh != null) "ArtMesh" else "Layer"

			CompactMenuHeader(
				name = drawable.name,
				badge = itemTypeBadge,
				icon = {
					HierarchyTypeIcon(
						kind = HierarchyIconKind.MESH,
						tint = Color(layerDotAwt.red, layerDotAwt.green, layerDotAwt.blue),
						modifier = Modifier.size(13.dp),
					)
				},
			)

			if (layerId != null) {
				val isIsolated = state.isolatedLayerId == layerId
				CompactMenuSection(tr("canvas.hierarchy.menuView"))
				CompactMenuItem(
					text = if (isIsolated) tr("canvas.hierarchy.unsoloLayer") else tr("canvas.hierarchy.soloLayer"),
					onClick = {
						viewModel.isolateLayer(layerId)
						showMenu = false
					},
					active = isIsolated,
					trailingBadge = if (isIsolated) {
						{
							Box(
								modifier = Modifier
									.background(colors.accent.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
									.padding(horizontal = 4.dp, vertical = 1.dp)
							) {
								Text(
									text = "SOLO",
									style = typography.monoSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
									color = colors.accent,
								)
							}
						}
					} else null,
					icon = {
						IconEye(
							visible = true,
							modifier = Modifier.size(13.dp),
							tint = if (isIsolated) colors.accent else colors.textMuted,
						)
					},
				)
				CompactMenuItem(
					text = if (isSelfVisible) tr("canvas.hierarchy.hideLayer") else tr("canvas.hierarchy.showLayer"),
					onClick = {
						viewModel.toggleLayerVisibility(layerId)
						showMenu = false
					},
					icon = {
						IconEye(
							visible = !isSelfVisible,
							modifier = Modifier.size(13.dp),
							tint = if (!isSelfVisible) colors.accent else colors.textMuted,
						)
					},
				)
				CompactMenuDivider()
			}

			if (layerId != null) {
				CompactMenuSection(tr("canvas.hierarchy.menuAdd"))
				CompactMenuItem(
					text = tr("editor.treeAddWarpParent"),
					onClick = {
						onRequestCreate?.invoke(CreatePlacementKind.WARP, CreateRelation.AS_PARENT, false, drawable.id.raw)
						showMenu = false
					},
					icon = { IconWarpDeformer(tint = colors.textMuted, modifier = Modifier.size(13.dp)) },
				)
				CompactMenuItem(
					text = tr("editor.treeAddRotationParent"),
					onClick = {
						onRequestCreate?.invoke(CreatePlacementKind.ROTATION, CreateRelation.AS_PARENT, false, drawable.id.raw)
						showMenu = false
					},
					icon = { IconRotationDeformer(tint = colors.textMuted, modifier = Modifier.size(13.dp)) },
				)
				if (drawable.mesh != null) {
					CompactMenuItem(
						text = tr("editor.treeAddPath"),
						onClick = {
							onRequestCreate?.invoke(CreatePlacementKind.PATH, CreateRelation.AS_CHILD, false, drawable.id.raw)
							showMenu = false
						},
						icon = { IconDeformPath(modifier = Modifier.size(13.dp), tint = colors.textMuted) },
					)
				}
				CompactMenuDivider()

				CompactMenuSection(tr("canvas.hierarchy.menuSettings"))
				val effectiveOrder = state.getEffectiveDrawOrder(drawable.id.raw, layerId, drawable.drawOrder)
				val isOverridden = state.drawOrderOverrides.containsKey(layerId) || state.drawOrderOverrides.containsKey(drawable.id.raw)
				CompactMenuItem(
					text = tr("canvas.hierarchy.setDrawOrder"),
					onClick = {
						showMenu = false
						onRequestSetOrder?.invoke(layerId, drawable.name, effectiveOrder, drawable.drawOrder, isOverridden)
					},
					trailingBadge = {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							if (isOverridden) {
								Box(
									modifier = Modifier
										.size(5.dp)
										.background(colors.accent, CircleShape)
								)
							}
							Text(
								text = effectiveOrder.roundToInt().toString(),
								style = typography.monoSmall.copy(
									fontSize = 10.sp,
									fontWeight = if (isOverridden) FontWeight.SemiBold else FontWeight.Normal,
								),
								color = if (isOverridden) colors.accent else colors.textMuted,
							)
						}
					},
					icon = { IconDrawOrder(tint = if (isOverridden) colors.accent else colors.textMuted, modifier = Modifier.size(13.dp)) },
				)
				if (isOverridden) {
					CompactMenuItem(
						text = tr("canvas.drawOrder.reset"),
						onClick = {
							viewModel.resetLayerDrawOrder(layerId)
							showMenu = false
						},
						trailingBadge = {
							Box(
								modifier = Modifier
									.background(colors.accent.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
									.padding(horizontal = 4.dp, vertical = 1.dp)
							) {
								Text(
									text = tr("settings.reset").ifEmpty { "RESET" },
									style = typography.monoSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold),
									color = colors.accent,
								)
							}
						},
						icon = { IconReset(modifier = Modifier.size(13.dp), tint = colors.accent) },
					)
				}
				val isMeshOverridden = state.meshOverrides.containsKey(layerId)
				val effectiveMesh = state.getEffectiveMeshSettings(layerId)
				val defaultMesh = state.getDefaultMeshSettings(layerId)
				CompactMenuItem(
					text = tr("canvas.hierarchy.meshSettings"),
					onClick = {
						showMenu = false
						onRequestSetMeshSettings?.invoke(
							MeshSettingsDialogTarget(
								layerId = layerId,
								layerName = drawable.name,
								currentSettings = effectiveMesh,
								defaultSettings = defaultMesh,
								isOverridden = isMeshOverridden,
							)
						)
					},
					trailingBadge = if (isMeshOverridden) {
						{
							Box(
								modifier = Modifier
									.size(5.dp)
									.background(colors.accent, CircleShape)
							)
						}
					} else null,
					icon = { IconMeshWireframe(tint = if (isMeshOverridden) colors.accent else colors.textMuted, modifier = Modifier.size(13.dp)) },
				)
				if (drawable.mesh != null) {
					CompactMenuItem(
						text = tr("path.title"),
						onClick = {
							showMenu = false
							onRequestOpenDeformPaths?.invoke(layerId)
						},
						icon = { IconDeformPath(modifier = Modifier.size(13.dp), tint = colors.accent) },
					)
				}
				if (isMeshOverridden) {
					CompactMenuItem(
						text = tr("canvas.hierarchy.resetMeshSettings"),
						onClick = {
							viewModel.resetPartMeshSettings(layerId)
							showMenu = false
						},
						trailingBadge = {
							Box(
								modifier = Modifier
									.background(colors.accent.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
									.padding(horizontal = 4.dp, vertical = 1.dp)
							) {
								Text(
									text = tr("settings.reset").ifEmpty { "RESET" },
									style = typography.monoSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold),
									color = colors.accent,
								)
							}
						},
						icon = { IconReset(modifier = Modifier.size(13.dp), tint = colors.accent) },
					)
				}
				CompactMenuDivider()
			}

			CompactMenuSection(tr("canvas.hierarchy.menuHierarchy"))
			CompactMenuItem(
				text = tr("canvas.hierarchy.moveToRoot"),
				onClick = {
					viewModel.reparentItem(itemId, null)
					showMenu = false
				},
				enabled = !isAlreadyRoot,
				icon = { IconMoveToRoot(tint = if (!isAlreadyRoot) colors.textMuted else colors.textDisabled, modifier = Modifier.size(13.dp)) },
			)
			if (state.parentOverrides.containsKey(itemId)) {
				CompactMenuItem(
					text = tr("canvas.hierarchy.resetItem"),
					onClick = {
						viewModel.resetItemHierarchy(itemId)
						showMenu = false
					},
					trailingBadge = {
						Box(
							modifier = Modifier
								.background(colors.accent.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
								.padding(horizontal = 4.dp, vertical = 1.dp)
						) {
							Text(
								text = tr("settings.reset").ifEmpty { "RESET" },
								style = typography.monoSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold),
								color = colors.accent,
							)
						}
					},
					icon = { IconReset(modifier = Modifier.size(13.dp), tint = colors.accent) },
				)
			}

			if (layerId != null) {
				CompactMenuDivider()
				CompactMenuItem(
					text = tr("canvas.hierarchy.deleteLayer"),
					onClick = {
						viewModel.deleteLayer(layerId)
						showMenu = false
					},
					danger = true,
					icon = { IconTrash(modifier = Modifier.size(12.dp), tint = colors.error) },
				)
			}
		}
	}
}
