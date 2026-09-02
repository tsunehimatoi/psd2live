package io.github.autolive2d.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.autolive2d.core.RigPreviewModel
import io.github.autolive2d.i18n.tr
import io.github.autolive2d.ui.ComponentPalette
import io.github.autolive2d.ui.components.CompactButton
import io.github.autolive2d.ui.components.CompactTabBar
import io.github.autolive2d.ui.components.IconChevron
import io.github.autolive2d.ui.components.IconPause
import io.github.autolive2d.ui.components.IconPlay
import io.github.autolive2d.ui.state.AutoLive2DState
import io.github.autolive2d.ui.state.AutoLive2DViewModel
import io.github.autolive2d.ui.state.WorkspaceTab
import io.github.autolive2d.ui.theme.LocalToolColors
import io.github.autolive2d.ui.theme.LocalToolTypography
import org.umamo.runtime.model.Deformer
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun WorkspaceView(
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val tabTitles = listOf(
		tr("tab.hierarchy"),
		tr("tab.topology"),
		tr("tab.preview"),
		tr("tab.log"),
	)
	val selectedTabIndex = when (state.activeWorkspaceTab) {
		WorkspaceTab.HIERARCHY -> 0
		WorkspaceTab.TOPOLOGY -> 1
		WorkspaceTab.PREVIEW -> 2
		WorkspaceTab.LOG -> 3
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
					0 -> WorkspaceTab.HIERARCHY
					1 -> WorkspaceTab.TOPOLOGY
					2 -> WorkspaceTab.PREVIEW
					else -> WorkspaceTab.LOG
				}
				viewModel.setWorkspaceTab(tab)
			},
		)

		// Active Content
		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			when (state.activeWorkspaceTab) {
				WorkspaceTab.HIERARCHY -> HierarchyView(state, viewModel)
				WorkspaceTab.TOPOLOGY -> CanvasViewportComposable(
					mode = WorkspaceTab.TOPOLOGY,
					state = state,
					viewModel = viewModel,
					onLayerClicked = { viewModel.selectLayer(it) },
				)
				WorkspaceTab.PREVIEW -> CanvasViewportComposable(
					mode = WorkspaceTab.PREVIEW,
					state = state,
					viewModel = viewModel,
					onLayerClicked = { viewModel.selectLayer(it) },
				)
				WorkspaceTab.LOG -> LogView(state)
			}
		}
	}
}

@Composable
private fun HierarchyView(
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val model = state.previewModel

	var treeWidth by remember { mutableStateOf(260.dp) }
	var isTreeCollapsed by remember { mutableStateOf(false) }

	Box(modifier = Modifier.fillMaxSize()) {
		Row(modifier = Modifier.fillMaxSize()) {
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
						Text(
							text = tr("canvas.hierarchy.root"),
							style = typography.header.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
							color = colors.textPrimary,
						)
						// Collapse button in header
						Box(
							modifier = Modifier
								.size(18.dp)
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
								.clickable { isTreeCollapsed = true },
							contentAlignment = Alignment.Center,
						) {
							IconChevron(expanded = true, modifier = Modifier.size(9.dp), tint = colors.textMuted)
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
						HierarchyTreeList(model, state, viewModel)
					}
				}

				// Resizable Splitter Handle
				Box(
					modifier = Modifier
						.width(4.dp)
						.fillMaxHeight()
						.background(colors.divider)
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
						.draggable(
							orientation = Orientation.Horizontal,
							state = rememberDraggableState { delta ->
								val deltaDp = with(density) { delta.toDp() }
								treeWidth = (treeWidth + deltaDp).coerceIn(140.dp, 600.dp)
							},
						),
				)
			}

			// Right: 2D Canvas Viewport
			Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
				CanvasViewportComposable(
					mode = WorkspaceTab.HIERARCHY,
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
							.clickable { isTreeCollapsed = false }
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
	}
}

@Composable
private fun HierarchyTreeList(
	model: RigPreviewModel,
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val expandedMap = remember(model) {
		mutableStateMapOf<String, Boolean>().apply {
			model.rig.puppet.deformers.forEach { put(it.id.raw, true) }
		}
	}

	val deformers = model.rig.puppet.deformers
	val drawables = model.rig.puppet.drawables

	// Build parent-child hierarchy
	val deformerChildrenMap = remember(deformers) {
		deformers.groupBy { it.parent?.raw }
	}
	val drawableChildrenMap = remember(drawables) {
		drawables.groupBy { it.parentDeformerId?.raw }
	}

	val rootDeformers = deformers.filter { it.parent == null }
	val rootDrawables = drawables.filter { it.parentDeformerId == null }

	val scrollState = rememberScrollState()

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(scrollState)
			.padding(vertical = 4.dp),
	) {
		for (deformer in rootDeformers) {
			DeformerTreeItem(
				deformer = deformer,
				depth = 0,
				model = model,
				state = state,
				viewModel = viewModel,
				expandedMap = expandedMap,
				deformerChildrenMap = deformerChildrenMap,
				drawableChildrenMap = drawableChildrenMap,
			)
		}
		for (drawable in rootDrawables) {
			DrawableTreeItem(
				drawable = drawable,
				depth = 0,
				model = model,
				state = state,
				viewModel = viewModel,
			)
		}
	}
}

@Composable
private fun DeformerTreeItem(
	deformer: Deformer,
	depth: Int,
	model: RigPreviewModel,
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
	expandedMap: MutableMap<String, Boolean>,
	deformerChildrenMap: Map<String?, List<Deformer>>,
	drawableChildrenMap: Map<String?, List<org.umamo.runtime.model.Drawable>>,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val deformerId = deformer.id.raw
	val isExpanded = expandedMap[deformerId] ?: true
	val isSelected = state.selectedDeformerId == deformerId
	val type = if (deformer is Deformer.Warp) "Warp" else "Rotation"

	val childDeformers = deformerChildrenMap[deformerId].orEmpty()
	val childDrawables = drawableChildrenMap[deformerId].orEmpty()
	val hasChildren = childDeformers.isNotEmpty() || childDrawables.isNotEmpty()

	val indentStep = 14.dp
	val indentStart = 8.dp
	val guideColor = colors.divider.copy(alpha = 0.5f)

	// Deformer Row with VS Code vertical tree guide lines
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(22.dp)
			.background(if (isSelected) colors.selection else Color.Transparent)
			.drawBehind {
				// Draw VS Code style vertical tree guide lines for each ancestor depth level
				for (level in 0 until depth) {
					val lineX = (indentStart + indentStep * level).toPx()
					drawLine(
						color = guideColor,
						start = Offset(lineX, 0f),
						end = Offset(lineX, size.height),
						strokeWidth = 1f,
					)
				}
				if (depth > 0) {
					// Draw horizontal connecting branch tick
					val parentLineX = (indentStart + indentStep * (depth - 1)).toPx()
					drawLine(
						color = guideColor,
						start = Offset(parentLineX, size.height * 0.5f),
						end = Offset(parentLineX + 8.dp.toPx(), size.height * 0.5f),
						strokeWidth = 1f,
					)
				}
			}
			.clickable {
				viewModel.selectDeformer(deformerId)
				val firstLayer = childDrawables.firstOrNull()?.let { model.rig.layerIdByDrawableId[it.id.raw] }
				if (firstLayer != null) viewModel.selectLayer(firstLayer)
			}
			.padding(start = indentStart + indentStep * depth, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Chevron for expand/collapse
		Box(
			modifier = Modifier
				.size(16.dp)
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.clickable(enabled = hasChildren) { expandedMap[deformerId] = !isExpanded },
			contentAlignment = Alignment.Center,
		) {
			if (hasChildren) {
				IconChevron(expanded = isExpanded, modifier = Modifier.size(8.5.dp), tint = colors.textMuted)
			}
		}
		Spacer(Modifier.width(2.dp))

		// Deformer Dot Icon
		val dotAwt = ComponentPalette.strong(deformerId)
		Box(
			modifier = Modifier
				.size(8.dp)
				.background(Color(dotAwt.red, dotAwt.green, dotAwt.blue), CircleShape),
		)
		Spacer(Modifier.width(6.dp))

		// Deformer Name
		Text(
			text = deformer.name,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
			color = if (isSelected) colors.selectionText else colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.width(4.dp))
		Text(
			text = "[$type]",
			style = typography.monoSmall.copy(fontSize = 9.sp),
			color = colors.textMuted,
		)
	}

	// Render children recursively
	if (isExpanded) {
		for (childDeformer in childDeformers) {
			DeformerTreeItem(
				deformer = childDeformer,
				depth = depth + 1,
				model = model,
				state = state,
				viewModel = viewModel,
				expandedMap = expandedMap,
				deformerChildrenMap = deformerChildrenMap,
				drawableChildrenMap = drawableChildrenMap,
			)
		}
		for (childDrawable in childDrawables) {
			DrawableTreeItem(
				drawable = childDrawable,
				depth = depth + 1,
				model = model,
				state = state,
				viewModel = viewModel,
			)
		}
	}
}

@Composable
private fun DrawableTreeItem(
	drawable: org.umamo.runtime.model.Drawable,
	depth: Int,
	model: RigPreviewModel,
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
	val isLayerSelected = layerId != null && state.selectedLayerId == layerId
	val isVisible = layerId == null || state.isLayerVisible(layerId)

	val indentStep = 14.dp
	val indentStart = 8.dp
	val guideColor = colors.divider.copy(alpha = 0.5f)

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(20.dp)
			.background(if (isLayerSelected) colors.selection else Color.Transparent)
			.drawBehind {
				for (level in 0 until depth) {
					val lineX = (indentStart + indentStep * level).toPx()
					drawLine(
						color = guideColor,
						start = Offset(lineX, 0f),
						end = Offset(lineX, size.height),
						strokeWidth = 1f,
					)
				}
				if (depth > 0) {
					val parentLineX = (indentStart + indentStep * (depth - 1)).toPx()
					drawLine(
						color = guideColor,
						start = Offset(parentLineX, size.height * 0.5f),
						end = Offset(parentLineX + 8.dp.toPx(), size.height * 0.5f),
						strokeWidth = 1f,
					)
				}
			}
			.clickable {
				if (layerId != null) {
					viewModel.selectLayer(layerId)
					drawable.parentDeformerId?.raw?.let(viewModel::selectDeformer)
				}
			}
			.padding(start = indentStart + indentStep * depth + 16.dp, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		val layerDotAwt = if (layerId != null) ComponentPalette.strong(layerId) else java.awt.Color.GRAY
		Box(
			modifier = Modifier
				.size(6.dp)
				.background(Color(layerDotAwt.red, layerDotAwt.green, layerDotAwt.blue), RoundedCornerShape(1.dp)),
		)
		Spacer(Modifier.width(6.dp))
		Text(
			text = drawable.name,
			style = typography.body.copy(fontSize = 11.sp),
			color = if (isVisible) (if (isLayerSelected) colors.selectionText else colors.textPrimary) else colors.textDisabled,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun LogView(state: AutoLive2DState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val listState = rememberLazyListState()

	LaunchedEffect(state.logLines.size) {
		if (state.logLines.isNotEmpty()) {
			listState.scrollToItem(state.logLines.size - 1)
		}
	}

	fun copyAllLogs() {
		val text = state.logLines.joinToString("\n")
		val selection = StringSelection(text)
		Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
	}

	Column(modifier = Modifier.fillMaxSize().background(colors.inputBackground)) {
		// Log Toolbar
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
			Text(
				text = "${state.logLines.size} lines",
				style = typography.caption.copy(fontSize = 10.5.sp),
				color = colors.textMuted,
			)
			CompactButton(
				text = "Copy Log",
				onClick = ::copyAllLogs,
				height = 20.dp,
			)
		}

		// Monospaced Log List
		SelectionContainer(modifier = Modifier.fillMaxSize().padding(6.dp)) {
			LazyColumn(
				state = listState,
				modifier = Modifier.fillMaxSize(),
			) {
				items(state.logLines) { line ->
					val isWarn = line.contains("warning", ignoreCase = true) || line.contains("警告")
					val isFail = line.contains("failed", ignoreCase = true) || line.contains("失败") || line.contains("error", ignoreCase = true)
					val textColor = when {
						isFail -> colors.error
						isWarn -> colors.warning
						else -> colors.textPrimary
					}
					Text(
						text = line,
						style = typography.mono.copy(fontSize = 11.sp, lineHeight = 16.sp),
						color = textColor,
					)
				}
			}
		}
	}
}
