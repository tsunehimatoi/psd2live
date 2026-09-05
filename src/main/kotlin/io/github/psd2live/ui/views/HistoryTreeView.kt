package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.agent.AgentHistoryNodeSnapshot
import io.github.psd2live.agent.AgentHistorySnapshot
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.max
import kotlin.math.roundToInt

private const val NODE_WIDTH_DP = 250f
private const val NODE_HEIGHT_DP = 80f
private const val HORIZONTAL_GAP_DP = 44f
private const val VERTICAL_GAP_DP = 60f
private const val CANVAS_PADDING_DP = 40f
private const val MIN_SCALE = 0.25f
private const val MAX_SCALE = 2.5f

internal class TreeNodeLayout(
	val node: AgentHistoryNodeSnapshot,
	var x: Float = 0f,
	var y: Float = 0f,
	val children: MutableList<TreeNodeLayout> = mutableListOf(),
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryTreeView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val densityFactor = density.density

	var showHidden by remember(state.projectOpenGeneration) { mutableStateOf(state.historyShowHidden) }
	val fullHistory = state.historySnapshot
	val hiddenIds = remember(fullHistory, state.historyAnnotations, showHidden) {
		val hidden = if (showHidden) mutableSetOf() else state.historyAnnotations.filterValues { it.hidden }.keys.toMutableSet()
		val children = fullHistory?.nodes.orEmpty().groupBy { it.parentId }
		val byId = fullHistory?.nodes.orEmpty().associateBy { it.id }
		val pending = java.util.ArrayDeque(hidden)
		while (pending.isNotEmpty()) children[pending.removeFirst()].orEmpty().forEach { if (hidden.add(it.id)) pending.add(it.id) }
		var cursor = fullHistory?.headNodeId
		while (cursor != null) { hidden.remove(cursor); cursor = byId[cursor]?.parentId }
		hidden
	}
	val historySnapshot = fullHistory?.copy(nodes = fullHistory.nodes.filterNot { it.id in hiddenIds })
	var searchQuery by remember(state.projectOpenGeneration) { mutableStateOf(state.historySearch) }
	var scale by remember(state.projectOpenGeneration) { mutableStateOf(state.historyZoom.coerceIn(MIN_SCALE, MAX_SCALE)) }
	var panOffset by remember(state.projectOpenGeneration) { mutableStateOf(Offset(state.historyPanX, state.historyPanY)) }
	var isDragging by remember { mutableStateOf(false) }
	var viewportSize by remember { mutableStateOf(IntSize(800, 600)) }
	var isInspectionPanelOpen by remember { mutableStateOf(true) }

	LaunchedEffect(scale, panOffset, searchQuery, showHidden) {
		viewModel.setHistoryView(scale, panOffset.x, panOffset.y, searchQuery, showHidden)
	}

	val selectedNodeId = state.selectedHistoryNodeId ?: historySnapshot?.headNodeId
	val selectedNode = remember(historySnapshot, selectedNodeId) {
		historySnapshot?.nodes?.firstOrNull { it.id == selectedNodeId }
	}

	if (historySnapshot == null || historySnapshot.nodes.isEmpty()) {
		Box(
			modifier = modifier
				.fillMaxSize()
				.background(colors.windowBackground),
			contentAlignment = Alignment.Center,
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = tr("history.title"),
					style = typography.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
					color = colors.textPrimary,
				)
				Text(
					text = tr("history.empty"),
					style = typography.body.copy(fontSize = 12.sp),
					color = colors.textMuted,
				)
			}
		}
		return
	}

	// Calculate Tree Layout in world DP units
	val (rootNodes, allLayoutNodes, boundsWidth, boundsHeight) = remember(historySnapshot) {
		calculateTreeLayout(historySnapshot.nodes)
	}

	fun worldToScreenX(worldXDp: Float): Float {
		return (worldXDp * scale + CANVAS_PADDING_DP * scale) * densityFactor + panOffset.x
	}

	fun worldToScreenY(worldYDp: Float): Float {
		return (worldYDp * scale + CANVAS_PADDING_DP * scale) * densityFactor + panOffset.y
	}

	fun fitToView() {
		val vpW = viewportSize.width.toFloat()
		val vpH = viewportSize.height.toFloat()
		if (boundsWidth <= 0f || boundsHeight <= 0f || vpW <= 0f || vpH <= 0f) return
		val marginPx = 40f * densityFactor
		val availableW = (vpW - marginPx * 2f).coerceAtLeast(100f)
		val availableH = (vpH - marginPx * 2f).coerceAtLeast(100f)
		val totalContentWDp = boundsWidth + CANVAS_PADDING_DP * 2f
		val totalContentHDp = boundsHeight + CANVAS_PADDING_DP * 2f
		val targetScale = minOf(
			availableW / (totalContentWDp * densityFactor),
			availableH / (totalContentHDp * densityFactor),
		).coerceIn(MIN_SCALE, 1.2f)
		scale = targetScale
		val drawnContentWPx = totalContentWDp * targetScale * densityFactor
		val targetPanX = ((vpW - drawnContentWPx) * 0.5f).coerceAtLeast(0f)
		val targetPanY = marginPx
		panOffset = Offset(targetPanX, targetPanY)
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.windowBackground)
			.clipToBounds(),
	) {
		// Unified Toolbar
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(32.dp)
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					text = tr("history.title"),
					style = typography.title.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
					color = colors.textPrimary,
				)
				Text(
					text = "${historySnapshot.nodes.size} nodes · HEAD: ${historySnapshot.headNodeId.take(10)}…",
					style = typography.monoSmall.copy(fontSize = 10.sp),
					color = colors.accent,
				)
				Box(
					modifier = Modifier
						.width(1.dp)
						.height(14.dp)
						.background(colors.divider),
				)
				CompactButton(
					text = tr("project.undo"),
					onClick = viewModel::undoHistory,
					height = 20.dp,
				)
				CompactButton(
					text = tr("project.redo"),
					onClick = viewModel::redoHistory,
					height = 20.dp,
				)
				CompactCheckbox(
					checked = showHidden,
					onCheckedChange = { showHidden = it },
					label = tr("project.historyShow"),
				)
			}

			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				// Zoom Controls
				CompactButton(
					text = "-",
					onClick = { scale = (scale / 1.15f).coerceIn(MIN_SCALE, MAX_SCALE) },
					height = 20.dp,
				)
				Text(
					text = "${(scale * 100).toInt()}%",
					style = typography.monoSmall.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
				CompactButton(
					text = "+",
					onClick = { scale = (scale * 1.15f).coerceIn(MIN_SCALE, MAX_SCALE) },
					height = 20.dp,
				)
				CompactButton(
					text = tr("history.resetView"),
					onClick = {
						scale = 1.0f
						panOffset = Offset(0f, 0f)
					},
					height = 20.dp,
				)
				CompactButton(
					text = tr("history.fitView"),
					onClick = { fitToView() },
					height = 20.dp,
				)

				Spacer(Modifier.width(4.dp))

				// Search
				CompactTextField(
					value = searchQuery,
					onValueChange = { searchQuery = it },
					placeholder = tr("history.search"),
					modifier = Modifier.width(130.dp).height(20.dp),
				)
			}
		}

		// Canvas & Inspection Split Pane (Strictly clipped to avoid UI leakage)
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.clipToBounds(),
		) {
			// Interactive Tree Canvas
			Box(
				modifier = Modifier
					.fillMaxSize()
					.clipToBounds()
					.onSizeChanged { viewportSize = it }
					.pointerHoverIcon(
						PointerIcon(
							if (isDragging) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
							else Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
						)
					)
					.pointerInput(Unit) {
						detectDragGestures(
							onDragStart = { isDragging = true },
							onDragEnd = { isDragging = false },
							onDragCancel = { isDragging = false },
							onDrag = { change, dragAmount ->
								change.consume()
								panOffset = Offset(panOffset.x + dragAmount.x, panOffset.y + dragAmount.y)
							},
						)
					}
					.onPointerEvent(PointerEventType.Scroll) { event ->
						val change = event.changes.firstOrNull() ?: return@onPointerEvent
						val delta = change.scrollDelta.y
						if (delta != 0f) {
							val mouseX = change.position.x
							val mouseY = change.position.y
							val oldScale = scale
							val zoomFactor = if (delta < 0) 1.15f else 1f / 1.15f
							val nextScale = (scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
							if (nextScale != oldScale) {
								val worldX = (mouseX - panOffset.x) / (oldScale * densityFactor) - CANVAS_PADDING_DP
								val worldY = (mouseY - panOffset.y) / (oldScale * densityFactor) - CANVAS_PADDING_DP
								scale = nextScale
								panOffset = Offset(
									mouseX - (worldX + CANVAS_PADDING_DP) * nextScale * densityFactor,
									mouseY - (worldY + CANVAS_PADDING_DP) * nextScale * densityFactor,
								)
							}
						}
					},
			) {
				// Canvas Background Grid & Connecting Lines
				Canvas(modifier = Modifier.fillMaxSize()) {
					val gridSpacing = 24f * scale * densityFactor
					if (gridSpacing >= 12f) {
						val ox = (panOffset.x % gridSpacing + gridSpacing) % gridSpacing
						val oy = (panOffset.y % gridSpacing + gridSpacing) % gridSpacing
						var x = ox
						while (x < size.width) {
							drawLine(Color(0x0CFFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
							x += gridSpacing
						}
						var y = oy
						while (y < size.height) {
							drawLine(Color(0x0CFFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
							y += gridSpacing
						}
					}

					// Draw connecting Bezier curves between parent and child nodes
					for (layoutNode in allLayoutNodes) {
						val parent = layoutNode
						val px = worldToScreenX(parent.x + NODE_WIDTH_DP / 2f)
						val py = worldToScreenY(parent.y + NODE_HEIGHT_DP)

						for (child in parent.children) {
							val cx = worldToScreenX(child.x + NODE_WIDTH_DP / 2f)
							val cy = worldToScreenY(child.y)

							val path = Path().apply {
								moveTo(px, py)
								cubicTo(
									px, py + (cy - py) * 0.5f,
									cx, cy - (cy - py) * 0.5f,
									cx, cy,
								)
							}

							val isBranchToHead = child.node.isHead
							val strokeColor = if (isBranchToHead) Color(0xFF4EC9B0) else Color(0x66778899)
							val strokeWidth = (if (isBranchToHead) 2.5f else 1.5f) * scale.coerceIn(0.6f, 1.8f) * densityFactor

							drawPath(
								path = path,
								color = strokeColor,
								style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
							)

							// Arrow dot at child connector
							drawCircle(
								color = strokeColor,
								radius = 3.5f * scale.coerceIn(0.6f, 1.5f) * densityFactor,
								center = Offset(cx, cy),
							)
						}
					}
				}

				// Place Node Cards
				for (layoutNode in allLayoutNodes) {
					val node = layoutNode.node
					val isHead = node.isHead
					val isSelected = node.id == selectedNodeId
					val matchesSearch = if (searchQuery.isBlank()) true else {
						node.summary.contains(searchQuery, ignoreCase = true) ||
							node.id.contains(searchQuery, ignoreCase = true) ||
							node.actor.contains(searchQuery, ignoreCase = true)
					}

					val cardX = worldToScreenX(layoutNode.x).roundToInt()
					val cardY = worldToScreenY(layoutNode.y).roundToInt()
					val cardWidthDp = (NODE_WIDTH_DP * scale).dp
					val cardHeightDp = (NODE_HEIGHT_DP * scale).dp

					// Culling outside viewport
					if (cardX + (NODE_WIDTH_DP * scale * densityFactor) < -100 ||
						cardX > viewportSize.width + 100 ||
						cardY + (NODE_HEIGHT_DP * scale * densityFactor) < -100 ||
						cardY > viewportSize.height + 100
					) {
						continue
					}

					val isCompact = scale < 0.65f
					val cornerRadius = (6 * scale.coerceIn(0.5f, 1.2f)).dp

					Box(
						modifier = Modifier
							.offset { IntOffset(cardX, cardY) }
							.size(width = cardWidthDp, height = cardHeightDp)
							.clip(RoundedCornerShape(cornerRadius))
							.background(
								if (isSelected) colors.panelElevated
								else colors.panelBackground.copy(alpha = if (matchesSearch) 0.95f else 0.35f)
							)
							.border(
								BorderStroke(
									width = if (isSelected || isHead) (2 * scale.coerceIn(0.6f, 1.2f)).dp else (1 * scale.coerceIn(0.6f, 1.2f)).dp,
									color = when {
										isHead -> Color(0xFF4EC9B0)
										isSelected -> colors.accent
										!matchesSearch -> colors.divider.copy(alpha = 0.2f)
										else -> colors.border
									},
								),
								RoundedCornerShape(cornerRadius),
							)
							.clickable {
								viewModel.selectHistoryNode(node.id)
								isInspectionPanelOpen = true
							}
							.padding((6 * scale.coerceIn(0.6f, 1.0f)).dp),
					) {
						if (isCompact) {
							// Compact View when zoomed out
							Column(
								modifier = Modifier.fillMaxSize(),
								verticalArrangement = Arrangement.SpaceBetween,
							) {
								Row(
									modifier = Modifier.fillMaxWidth(),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.SpaceBetween,
								) {
									Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
										ActorBadge(actor = node.actor, scale = scale.coerceIn(0.5f, 0.8f))
										Text(
											text = "#${node.id.takeLast(6)}",
											style = typography.monoSmall.copy(fontSize = (8 * scale.coerceIn(0.6f, 1.0f)).sp),
											color = colors.textMuted,
										)
									}
									if (isHead) {
										Box(
											modifier = Modifier
												.size((6 * scale.coerceIn(0.6f, 1.2f)).dp)
												.clip(CircleShape)
												.background(Color(0xFF4EC9B0))
										)
									}
								}
								Text(
									text = state.historyAnnotations[node.id]?.title?.takeIf { it.isNotBlank() } ?: node.summary,
									style = typography.body.copy(
										fontSize = (9 * scale.coerceIn(0.6f, 1.0f)).sp,
										fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
									),
									color = if (!matchesSearch) colors.textMuted else colors.textPrimary,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						} else {
							// Detailed View when normal or zoomed in
							Column(
								modifier = Modifier.fillMaxSize(),
								verticalArrangement = Arrangement.SpaceBetween,
							) {
								// Card Header: Actor Chip + Short ID + HEAD badge
								Row(
									modifier = Modifier.fillMaxWidth(),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.SpaceBetween,
								) {
									Row(
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy((4 * scale.coerceIn(0.7f, 1.0f)).dp),
									) {
										ActorBadge(actor = node.actor, scale = scale.coerceIn(0.7f, 1.0f))
										Text(
											text = "#${node.id.takeLast(7)}",
											style = typography.monoSmall.copy(fontSize = (9 * scale.coerceIn(0.7f, 1.1f)).sp),
											color = colors.textMuted,
										)
									}

									if (isHead) {
										Box(
											modifier = Modifier
												.clip(RoundedCornerShape((4 * scale).dp))
												.background(Color(0xFF1B4D3E))
												.border(BorderStroke((1 * scale).dp, Color(0xFF4EC9B0)), RoundedCornerShape((4 * scale).dp))
												.padding(horizontal = (4 * scale).dp, vertical = (1 * scale).dp),
										) {
											Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((3 * scale).dp)) {
												Box(modifier = Modifier.size((5 * scale).dp).clip(CircleShape).background(Color(0xFF4EC9B0)))
												Text(
													text = tr("history.head"),
													style = typography.monoSmall.copy(fontSize = (8.5 * scale.coerceIn(0.7f, 1.1f)).sp, fontWeight = FontWeight.Bold),
													color = Color(0xFF4EC9B0),
												)
											}
										}
									}
								}

								// Summary
								Text(
									text = state.historyAnnotations[node.id]?.title?.takeIf { it.isNotBlank() } ?: node.summary,
									style = typography.body.copy(
										fontSize = (10.5 * scale.coerceIn(0.75f, 1.2f)).sp,
										fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
										lineHeight = (13.5 * scale.coerceIn(0.75f, 1.2f)).sp,
									),
									color = if (!matchesSearch) colors.textMuted else colors.textPrimary,
									maxLines = 2,
									overflow = TextOverflow.Ellipsis,
								)

								// Timestamp
								Text(
									text = node.createdAt.take(19).replace('T', ' '),
									style = typography.caption.copy(fontSize = (8.5 * scale.coerceIn(0.75f, 1.1f)).sp),
									color = colors.textMuted,
								)
							}
						}
					}
				}
			}

			// Side / Floating Inspection Panel for Selected Node
			if (selectedNode != null && isInspectionPanelOpen) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(12.dp)
						.width(320.dp)
						.heightIn(max = 480.dp)
						.clip(RoundedCornerShape(8.dp))
						.background(colors.panelElevated)
						.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
						.padding(12.dp),
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.verticalScroll(rememberScrollState()),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Text(
								text = selectedNode.summary,
								style = typography.title.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
								color = colors.textPrimary,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f),
							)
							Spacer(Modifier.width(6.dp))
							if (selectedNode.isHead) {
								Text(
									text = "● HEAD",
									style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
									color = Color(0xFF4EC9B0),
								)
								Spacer(Modifier.width(6.dp))
							}
							CompactButton(
								text = "✕",
								onClick = { isInspectionPanelOpen = false },
								height = 20.dp,
							)
						}

						val annotation = state.historyAnnotations[selectedNode.id] ?: io.github.psd2live.ui.state.HistoryAnnotation()
						var title by remember(selectedNode.id, annotation) { mutableStateOf(annotation.title) }
						var note by remember(selectedNode.id, annotation) { mutableStateOf(annotation.note) }
						var hidden by remember(selectedNode.id, annotation) { mutableStateOf(annotation.hidden) }

						Column(
							modifier = Modifier
								.fillMaxWidth()
								.background(colors.inputBackground, RoundedCornerShape(4.dp))
								.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp))
								.padding(8.dp),
							verticalArrangement = Arrangement.spacedBy(6.dp),
						) {
							Text(
								text = tr("project.historyTitle"),
								style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
								color = colors.textMuted,
							)
							CompactTextField(
								value = title,
								onValueChange = { title = it },
								placeholder = tr("project.historyTitle"),
								modifier = Modifier.fillMaxWidth(),
								height = 22.dp,
							)
							Text(
								text = tr("project.historyNote"),
								style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
								color = colors.textMuted,
							)
							CompactTextField(
								value = note,
								onValueChange = { note = it },
								placeholder = tr("project.historyNote"),
								modifier = Modifier.fillMaxWidth(),
								height = 22.dp,
							)
							Row(
								modifier = Modifier.fillMaxWidth(),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween,
							) {
								CompactCheckbox(
									checked = hidden,
									onCheckedChange = { hidden = it },
									label = tr("project.historyHide"),
								)
								CompactButton(
									text = tr("project.historyApply"),
									onClick = { viewModel.editHistoryAnnotation(selectedNode.id, title, note, hidden) },
									height = 20.dp,
									isPrimary = true,
								)
							}
						}
						Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
							DetailRow(label = tr("history.nodeId"), value = selectedNode.id)
							DetailRow(label = tr("history.parentId"), value = selectedNode.parentId ?: "root")
							DetailRow(label = tr("history.revisionId"), value = selectedNode.revisionId.take(16))
							DetailRow(label = "Actor", value = selectedNode.actor)
							DetailRow(label = tr("history.time"), value = selectedNode.createdAt.take(19).replace('T', ' '))
						}

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically,
						) {
							CompactButton(
								text = tr("history.copyId"),
								onClick = {
									val sel = StringSelection(selectedNode.id)
									Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
								},
								height = 24.dp,
							)

							if (!selectedNode.isHead) {
								CompactButton(
									text = tr("history.checkout"),
									onClick = { viewModel.checkoutHistoryNode(selectedNode.id) },
									isPrimary = true,
									height = 24.dp,
								)
							} else {
								Text(
									text = tr("history.current"),
									style = typography.caption.copy(fontSize = 10.5.sp),
									color = Color(0xFF4EC9B0),
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
private fun ActorBadge(actor: String, scale: Float = 1f) {
	val (bg, fg, label) = when (actor.lowercase()) {
		"agent" -> Triple(Color(0xFF3B2E58), Color(0xFFDCDCAA), "Agent")
		"user" -> Triple(Color(0xFF1E3A5F), Color(0xFF9CDCFE), "User")
		else -> Triple(Color(0xFF2E3440), Color(0xFFD8DEE9), "System")
	}

	Box(
		modifier = Modifier
			.clip(RoundedCornerShape((3 * scale).dp))
			.background(bg)
			.padding(horizontal = (4 * scale).dp, vertical = (1 * scale).dp),
	) {
		Text(
			text = label,
			style = LocalToolTypography.current.monoSmall.copy(
				fontSize = (8.5 * scale).sp,
				fontWeight = FontWeight.Bold,
			),
			color = fg,
		)
	}
}

@Composable
private fun DetailRow(label: String, value: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
		)
		Text(
			text = value,
			style = typography.monoSmall.copy(fontSize = 10.sp),
			color = colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * Hierarchical tree positioning algorithm for rooted append-only DAG.
 * Uses DP units to remain completely independent of display density.
 */
internal data class TreeCalculationResult(
	val roots: List<TreeNodeLayout>,
	val allNodes: List<TreeNodeLayout>,
	val width: Float,
	val height: Float,
)

internal fun calculateTreeLayout(nodes: List<AgentHistoryNodeSnapshot>): TreeCalculationResult {
	if (nodes.isEmpty()) return TreeCalculationResult(emptyList(), emptyList(), 0f, 0f)

	val layoutNodeMap = nodes.associate { it.id to TreeNodeLayout(it) }
	val roots = mutableListOf<TreeNodeLayout>()

	for (node in nodes) {
		val layoutNode = layoutNodeMap.getValue(node.id)
		val parent = node.parentId?.let(layoutNodeMap::get)
		if (parent != null) {
			parent.children.add(layoutNode)
		} else {
			roots.add(layoutNode)
		}
	}

	// Two iterative passes keep long MCP histories stack-safe and linear in node count.
	val traversal = mutableListOf<TreeNodeLayout>()
	val pending = java.util.ArrayDeque<TreeNodeLayout>()
	val visited = mutableSetOf<String>()

	roots.forEach { pending.add(it) }
	while (pending.isNotEmpty()) {
		val node = pending.removeFirst()
		if (!visited.add(node.node.id)) continue
		traversal.add(node)
		node.children.forEach {
			it.y = node.y + NODE_HEIGHT_DP + VERTICAL_GAP_DP
			pending.add(it)
		}
	}

	// Handle any detached/cyclic nodes safely so they don't overlap at (0, 0)
	for (node in layoutNodeMap.values) {
		if (node.node.id !in visited) {
			roots.add(node)
			pending.add(node)
			while (pending.isNotEmpty()) {
				val n = pending.removeFirst()
				if (!visited.add(n.node.id)) continue
				traversal.add(n)
				n.children.forEach {
					it.y = n.y + NODE_HEIGHT_DP + VERTICAL_GAP_DP
					pending.add(it)
				}
			}
		}
	}

	val widths = mutableMapOf<String, Float>()
	traversal.asReversed().forEach { node ->
		val childrenWidthSum = node.children.sumOf { widths.getValue(it.node.id).toDouble() }.toFloat()
		val totalGaps = (node.children.size - 1).coerceAtLeast(0) * HORIZONTAL_GAP_DP
		widths[node.node.id] = max(NODE_WIDTH_DP, childrenWidthSum + totalGaps)
	}

	val starts = mutableMapOf<String, Float>()
	var currentRootX = 0f
	roots.forEach {
		starts[it.node.id] = currentRootX
		currentRootX += widths.getValue(it.node.id) + HORIZONTAL_GAP_DP * 2f
	}

	traversal.forEach { node ->
		val nodeWidth = widths.getValue(node.node.id)
		var start = starts.getValue(node.node.id)
		node.x = start + (nodeWidth - NODE_WIDTH_DP) / 2f
		node.children.forEach { child ->
			starts[child.node.id] = start
			start += widths.getValue(child.node.id) + HORIZONTAL_GAP_DP
		}
	}

	val allList = layoutNodeMap.values.toList()
	val maxX = allList.maxOfOrNull { it.x + NODE_WIDTH_DP } ?: 0f
	val maxY = allList.maxOfOrNull { it.y + NODE_HEIGHT_DP } ?: 0f
	return TreeCalculationResult(roots, allList, maxX, maxY)
}

