package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.agent.AgentHistoryNodeSnapshot
import io.github.psd2live.agent.AgentHistorySnapshot
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.max

private const val NODE_WIDTH = 250f
private const val NODE_HEIGHT = 74f
private const val HORIZONTAL_GAP = 40f
private const val VERTICAL_GAP = 60f

private class TreeNodeLayout(
	val node: AgentHistoryNodeSnapshot,
	var x: Float = 0f,
	var y: Float = 0f,
	val children: MutableList<TreeNodeLayout> = mutableListOf(),
)

@Composable
fun HistoryTreeView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val historySnapshot = state.historySnapshot
	var searchQuery by remember { mutableStateOf("") }
	var selectedActorFilter by remember { mutableStateOf("all") }
	var scale by remember { mutableStateOf(1.0f) }
	var panOffset by remember { mutableStateOf(Offset(0f, 0f)) }

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

	// Calculate Tree Layout
	val (rootNodes, allLayoutNodes, boundsWidth, boundsHeight) = remember(historySnapshot) {
		calculateTreeLayout(historySnapshot.nodes)
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.windowBackground),
	) {
		// Toolbar
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
					text = "${historySnapshot.nodes.size} nodes · HEAD: ${historySnapshot.headNodeId.take(12)}…",
					style = typography.monoSmall.copy(fontSize = 10.sp),
					color = colors.accent,
				)
			}

			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				// Zoom Controls
				CompactButton(
					text = "-",
					onClick = { scale = (scale - 0.15f).coerceAtLeast(0.4f) },
					height = 20.dp,
				)
				Text(
					text = "${(scale * 100).toInt()}%",
					style = typography.monoSmall.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
				CompactButton(
					text = "+",
					onClick = { scale = (scale + 0.15f).coerceAtMost(2.0f) },
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

				Spacer(Modifier.width(4.dp))

				// Search
				CompactTextField(
					value = searchQuery,
					onValueChange = { searchQuery = it },
					placeholder = tr("history.search"),
					modifier = Modifier.width(140.dp).height(20.dp),
				)
			}
		}

		// Canvas & Inspection Split Pane
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
		) {
			// Interactive Tree Canvas
			Box(
				modifier = Modifier
					.fillMaxSize()
					.pointerInput(Unit) {
						detectDragGestures { change, dragAmount ->
							change.consume()
							panOffset = Offset(panOffset.x + dragAmount.x, panOffset.y + dragAmount.y)
						}
					},
			) {
				// Canvas Background Grid
				Canvas(modifier = Modifier.fillMaxSize()) {
					val gridSpacing = 24f * scale
					val ox = panOffset.x % gridSpacing
					val oy = panOffset.y % gridSpacing
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

					// Draw connecting Bezier curves between parent and child nodes
					for (layoutNode in allLayoutNodes) {
						val parent = layoutNode
						val px = (parent.x + NODE_WIDTH / 2f) * scale + panOffset.x + 40f * scale
						val py = (parent.y + NODE_HEIGHT) * scale + panOffset.y + 40f * scale

						for (child in parent.children) {
							val cx = (child.x + NODE_WIDTH / 2f) * scale + panOffset.x + 40f * scale
							val cy = child.y * scale + panOffset.y + 40f * scale

							val path = Path().apply {
								moveTo(px, py)
								cubicTo(
									px, py + (cy - py) * 0.5f,
									cx, cy - (cy - py) * 0.5f,
									cx, cy,
								)
							}

							val isBranchToHead = child.node.isHead
							val strokeColor = if (isBranchToHead) Color(0xFF4EC9B0) else Color(0x66555566)
							val strokeWidth = if (isBranchToHead) 2.5f * scale else 1.5f * scale

							drawPath(
								path = path,
								color = strokeColor,
								style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
							)

							// Arrow dot at child connector
							drawCircle(
								color = strokeColor,
								radius = 3.5f * scale,
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

					val cardX = ((layoutNode.x * scale + panOffset.x) + 40f * scale).toInt()
					val cardY = ((layoutNode.y * scale + panOffset.y) + 40f * scale).toInt()

					Box(
						modifier = Modifier
							.offset { IntOffset(cardX, cardY) }
							.size(width = (NODE_WIDTH * scale).dp, height = (NODE_HEIGHT * scale).dp)
							.clip(RoundedCornerShape((6 * scale).dp))
							.background(if (isSelected) colors.panelElevated else colors.panelBackground)
							.border(
								BorderStroke(
									width = if (isSelected || isHead) (2 * scale).dp else (1 * scale).dp,
									color = when {
										isHead -> Color(0xFF4EC9B0)
										isSelected -> colors.accent
										!matchesSearch -> colors.divider.copy(alpha = 0.3f)
										else -> colors.border
									},
								),
								RoundedCornerShape((6 * scale).dp),
							)
							.clickable {
								viewModel.selectHistoryNode(node.id)
							}
							.padding((6 * scale).dp),
					) {
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
									horizontalArrangement = Arrangement.spacedBy((4 * scale).dp),
								) {
									ActorBadge(actor = node.actor, scale = scale)
									Text(
										text = "#${node.id.takeLast(7)}",
										style = typography.monoSmall.copy(fontSize = (9 * scale).sp),
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
												style = typography.monoSmall.copy(fontSize = (8.5 * scale).sp, fontWeight = FontWeight.Bold),
												color = Color(0xFF4EC9B0),
											)
										}
									}
								}
							}

							// Summary
							Text(
								text = node.summary,
								style = typography.body.copy(
									fontSize = (11 * scale).sp,
									fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
									lineHeight = (14 * scale).sp,
								),
								color = if (!matchesSearch) colors.textMuted else colors.textPrimary,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)

							// Timestamp
							Text(
								text = node.createdAt.take(19).replace('T', ' '),
								style = typography.caption.copy(fontSize = (8.5 * scale).sp),
								color = colors.textMuted,
							)
						}
					}
				}
			}

			// Side / Floating Inspection Panel for Selected Node
			if (selectedNode != null) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(12.dp)
						.width(320.dp)
						.background(colors.panelElevated, RoundedCornerShape(6.dp))
						.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
						.padding(12.dp),
				) {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
							if (selectedNode.isHead) {
								Text(
									text = "● HEAD",
									style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
									color = Color(0xFF4EC9B0),
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
								text = "Copy ID",
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
 */
private data class TreeCalculationResult(
	val roots: List<TreeNodeLayout>,
	val allNodes: List<TreeNodeLayout>,
	val width: Float,
	val height: Float,
)

private fun calculateTreeLayout(nodes: List<AgentHistoryNodeSnapshot>): TreeCalculationResult {
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

	// Layout coordinates
	var currentRootX = 0f
	var maxDepth = 0

	fun layoutSubtree(node: TreeNodeLayout, depth: Int): Float {
		node.y = depth * (NODE_HEIGHT + VERTICAL_GAP)
		maxDepth = max(maxDepth, depth)

		if (node.children.isEmpty()) {
			return NODE_WIDTH
		}

		var totalChildrenWidth = 0f
		val childWidths = mutableListOf<Float>()
		for (child in node.children) {
			val w = layoutSubtree(child, depth + 1)
			childWidths.add(w)
			totalChildrenWidth += w
		}
		totalChildrenWidth += (node.children.size - 1) * HORIZONTAL_GAP

		return max(NODE_WIDTH, totalChildrenWidth)
	}

	fun assignXCoordinates(node: TreeNodeLayout, startX: Float, allocatedWidth: Float) {
		if (node.children.isEmpty()) {
			node.x = startX + (allocatedWidth - NODE_WIDTH) / 2f
			return
		}

		node.x = startX + (allocatedWidth - NODE_WIDTH) / 2f

		var curX = startX
		for (child in node.children) {
			val childSubtreeWidth = getSubtreeWidth(child)
			assignXCoordinates(child, curX, childSubtreeWidth)
			curX += childSubtreeWidth + HORIZONTAL_GAP
		}
	}

	for (root in roots) {
		val treeWidth = layoutSubtree(root, 0)
		assignXCoordinates(root, currentRootX, treeWidth)
		currentRootX += treeWidth + HORIZONTAL_GAP * 2
	}

	val allList = layoutNodeMap.values.toList()
	val totalWidth = allList.maxOfOrNull { it.x + NODE_WIDTH } ?: 400f
	val totalHeight = allList.maxOfOrNull { it.y + NODE_HEIGHT } ?: 300f

	return TreeCalculationResult(roots, allList, totalWidth, totalHeight)
}

private fun getSubtreeWidth(node: TreeNodeLayout): Float {
	if (node.children.isEmpty()) return NODE_WIDTH
	var total = 0f
	for (child in node.children) {
		total += getSubtreeWidth(child)
	}
	total += (node.children.size - 1) * HORIZONTAL_GAP
	return max(NODE_WIDTH, total)
}

