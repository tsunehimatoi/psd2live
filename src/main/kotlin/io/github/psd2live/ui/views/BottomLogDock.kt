package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.CheckerboardBackground
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private enum class LogFilter {
	ALL,
	SYSTEM,
	EDITOR,
	AGENT_MCP,
	IMAGES_ONLY,
}

private data class FilterTabItem(
	val filter: LogFilter,
	val label: String,
	val badge: String? = null,
)

@Composable
fun BottomLogDock(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
	fillDock: Boolean = false,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current

	var currentFilter by remember { mutableStateOf(LogFilter.ALL) }
	var searchQuery by remember { mutableStateOf("") }
	var autoScroll by remember { mutableStateOf(true) }

	val listState = rememberLazyListState()

	val filteredEntries = remember(state.logEntries, currentFilter, searchQuery) {
		state.logEntries.filter { entry ->
			val matchesFilter = when (currentFilter) {
				LogFilter.ALL -> true
				LogFilter.SYSTEM -> entry.source == LogSource.SYSTEM
				LogFilter.EDITOR -> entry.source == LogSource.EDITOR
				LogFilter.AGENT_MCP -> entry.source == LogSource.MCP_SERVER || entry.source == LogSource.AGENT
				LogFilter.IMAGES_ONLY -> entry.imageBytes != null
			}
			val matchesSearch = if (searchQuery.isBlank()) true else {
				entry.message.contains(searchQuery, ignoreCase = true) ||
					entry.tag.contains(searchQuery, ignoreCase = true) ||
					entry.detail?.contains(searchQuery, ignoreCase = true) == true
			}
			matchesFilter && matchesSearch
		}
	}

	LaunchedEffect(filteredEntries.size, autoScroll) {
		if (autoScroll && filteredEntries.isNotEmpty()) {
			listState.scrollToItem(filteredEntries.size - 1)
		}
	}

	fun copyLogs() {
		val text = filteredEntries.joinToString("\n") { entry ->
			val time = TIME_FORMATTER.format(entry.timestamp)
			"[$time] [${entry.source}] [${entry.tag}] ${entry.message}"
		}
		val selection = StringSelection(text)
		Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
	}

	var splitterCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val expanded = state.logPanelExpanded || fillDock
	val imageCount = remember(state.logEntries) { state.logEntries.count { it.imageBytes != null } }
	val filterTabs = listOf(
		FilterTabItem(LogFilter.ALL, tr("log.dock.filter.all")),
		FilterTabItem(LogFilter.SYSTEM, tr("log.dock.filter.system")),
		FilterTabItem(LogFilter.EDITOR, tr("log.dock.filter.editor")),
		FilterTabItem(LogFilter.AGENT_MCP, tr("log.dock.filter.agent")),
		FilterTabItem(
			LogFilter.IMAGES_ONLY,
			tr("log.dock.filter.image"),
			badge = if (imageCount > 0) "$imageCount" else null,
		),
	)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.background(colors.panelBackground)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		if (state.logPanelExpanded && !fillDock) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp)
					.background(colors.divider)
					.onGloballyPositioned { splitterCoords = it }
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
					.pointerInput(density) {
						awaitEachGesture {
							val down = awaitFirstDown()
							val splitter = splitterCoords ?: return@awaitEachGesture
							if (!splitter.isAttached) return@awaitEachGesture
							val startMouseY = splitter.positionInWindow().y + down.position.y
							val startHeight = viewModel.state.value.logPanelHeight
							while (true) {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { it.id == down.id } ?: break
								if (!change.pressed) break
								change.consume()
								if (splitter.isAttached) {
									val currentMouseY = splitter.positionInWindow().y + change.position.y
									val deltaYPx = currentMouseY - startMouseY
									val deltaDp = with(density) { (-deltaYPx).toDp() }.value
									val newHeight = (startHeight + deltaDp).coerceIn(80f, 450f)
									viewModel.setLogPanelHeight(newHeight)
								}
							}
						}
					},
			)
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(28.dp)
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(start = if (fillDock) 4.dp else 6.dp, end = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (!fillDock) {
				CompactIconButton(
					onClick = { viewModel.setLogPanelExpanded(!state.logPanelExpanded) },
					size = 20.dp,
				) {
					IconChevron(
						expanded = state.logPanelExpanded,
						tint = colors.textPrimary,
					)
				}
				Spacer(Modifier.width(6.dp))
				Text(
					text = tr("log.dock.title"),
					style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
					maxLines = 1,
				)
			}

			if (expanded) {
				OverflowFilterTabs(
					items = filterTabs,
					selected = currentFilter,
					onSelect = { currentFilter = it },
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.padding(start = if (fillDock) 0.dp else 8.dp, end = 6.dp),
				)

				CompactTextField(
					value = searchQuery,
					onValueChange = { searchQuery = it },
					placeholder = tr("log.dock.search"),
					modifier = Modifier.width(96.dp).height(20.dp),
				)
				Spacer(Modifier.width(2.dp))
				LogHeaderIcon(
					tooltip = tr("log.dock.autoScroll"),
					active = autoScroll,
					onClick = { autoScroll = !autoScroll },
				) { tint ->
					IconLogAutoScroll(tint = tint)
				}
				LogHeaderIcon(
					tooltip = tr("log.dock.clear"),
					onClick = { viewModel.clearLogs() },
				) { tint ->
					IconLogClear(tint = tint)
				}
				LogHeaderIcon(
					tooltip = tr("log.dock.copy"),
					onClick = { copyLogs() },
				) { tint ->
					IconLogCopy(tint = tint)
				}
			} else {
				Spacer(Modifier.weight(1f))
			}
		}

		if (expanded) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.then(if (fillDock) Modifier.weight(1f) else Modifier.height(state.logPanelHeight.dp))
					.background(colors.inputBackground),
			) {
				if (filteredEntries.isEmpty()) {
					Box(
						modifier = Modifier.fillMaxSize(),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = tr("log.dock.empty"),
							style = typography.caption.copy(fontSize = 11.sp),
							color = colors.textMuted,
						)
					}
				} else {
					SelectionContainer(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
						LazyColumn(
							state = listState,
							modifier = Modifier.fillMaxSize(),
							verticalArrangement = Arrangement.spacedBy(3.dp),
						) {
							items(filteredEntries, key = { it.id }) { entry ->
								LogEntryRow(
									entry = entry,
									onImageClick = { bytes, label ->
										viewModel.openLightbox(bytes, label)
									},
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
private fun OverflowFilterTabs(
	items: List<FilterTabItem>,
	selected: LogFilter,
	onSelect: (LogFilter) -> Unit,
	modifier: Modifier = Modifier,
) {
	val density = LocalDensity.current
	val typography = LocalToolTypography.current
	val textMeasurer = rememberTextMeasurer()
	val measureStyle = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

	BoxWithConstraints(modifier) {
		val maxPx = constraints.maxWidth
		val horizontalPadPx = with(density) { 16.dp.roundToPx() }
		val badgeExtraPx = with(density) { 14.dp.roundToPx() }
		val ellipsisWidthPx = with(density) { 28.dp.roundToPx() }
		val widths = items.map { item ->
			textMeasurer.measure(text = item.label, style = measureStyle).size.width +
				horizontalPadPx +
				if (item.badge != null) badgeExtraPx else 0
		}
		val selectedIndex = items.indexOfFirst { it.filter == selected }.coerceAtLeast(0)
		val (visible, overflow) = remember(items, widths, maxPx, selectedIndex) {
			pickVisibleFilterTabs(items, widths, maxPx, ellipsisWidthPx, selectedIndex)
		}

		Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
			for (item in visible) {
				LogFilterTab(
					text = item.label,
					badge = item.badge,
					selected = item.filter == selected,
					onClick = { onSelect(item.filter) },
				)
			}
			if (overflow.isNotEmpty()) {
				OverflowEllipsis(
					highlighted = overflow.any { it.filter == selected },
					items = overflow,
					selected = selected,
					onSelect = onSelect,
				)
			}
		}
	}
}

private fun pickVisibleFilterTabs(
	items: List<FilterTabItem>,
	widths: List<Int>,
	maxPx: Int,
	ellipsisWidthPx: Int,
	selectedIndex: Int,
): Pair<List<FilterTabItem>, List<FilterTabItem>> {
	if (items.isEmpty() || maxPx <= 0) return emptyList<FilterTabItem>() to items
	if (widths.sum() <= maxPx) return items to emptyList()

	val budget = (maxPx - ellipsisWidthPx).coerceAtLeast(widths.getOrElse(0) { 0 })
	val visibleIdx = mutableListOf<Int>()
	var used = 0
	for (i in items.indices) {
		val width = widths[i]
		if (visibleIdx.isEmpty() || used + width <= budget) {
			visibleIdx += i
			used += width
		} else {
			break
		}
	}
	if (selectedIndex !in visibleIdx && selectedIndex in items.indices) {
		while (visibleIdx.size > 1 && used + widths[selectedIndex] > budget) {
			val dropAt = visibleIdx.indexOfLast { it != selectedIndex }
			if (dropAt < 0) break
			used -= widths[visibleIdx.removeAt(dropAt)]
		}
		if (selectedIndex !in visibleIdx) {
			val insertAt = visibleIdx.indexOfFirst { it > selectedIndex }.let { if (it < 0) visibleIdx.size else it }
			visibleIdx.add(insertAt, selectedIndex)
		}
	}
	val visibleSet = visibleIdx.toSet()
	return items.filterIndexed { index, _ -> index in visibleSet } to
		items.filterIndexed { index, _ -> index !in visibleSet }
}

@Composable
private fun LogFilterTab(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
	badge: String? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()

	Box(
		modifier = Modifier
			.fillMaxHeight()
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.drawBehind {
				if (selected) {
					val bar = 2.dp.toPx()
					drawRect(
						color = colors.accent,
						topLeft = Offset(0f, size.height - bar),
						size = Size(size.width, bar),
					)
				}
			}
			.padding(horizontal = 8.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = text,
				style = typography.caption.copy(
					fontSize = 11.sp,
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = when {
					selected -> colors.textPrimary
					hovered -> colors.textPrimary
					else -> colors.textMuted
				},
				maxLines = 1,
			)
			if (badge != null) {
				Text(
					text = badge,
					style = typography.monoSmall.copy(fontSize = 9.sp),
					color = if (selected) colors.accent else colors.textMuted,
				)
			}
		}
	}
}

@Composable
private fun OverflowEllipsis(
	highlighted: Boolean,
	items: List<FilterTabItem>,
	selected: LogFilter,
	onSelect: (LogFilter) -> Unit,
) {
	val colors = LocalToolColors.current
	var open by remember { mutableStateOf(false) }
	LaunchedEffect(items) {
		if (items.isEmpty()) open = false
	}

	Box(modifier = Modifier.fillMaxHeight()) {
		LogFilterTab(
			text = "⋯",
			selected = highlighted || open,
			onClick = { open = !open },
		)
		if (open) {
			Popup(
				alignment = Alignment.BottomStart,
				offset = IntOffset(0, 4),
				onDismissRequest = { open = false },
				properties = PopupProperties(focusable = true),
			) {
				Surface(
					color = colors.panelElevated,
					border = BorderStroke(1.dp, colors.border),
					shape = RoundedCornerShape(3.dp),
					elevation = 8.dp,
				) {
					Column(modifier = Modifier.widthIn(min = 140.dp, max = 220.dp)) {
						for (item in items) {
							AppMenuItem(
								text = item.label,
								isChecked = item.filter == selected,
								onClick = {
									onSelect(item.filter)
									open = false
								},
							)
						}
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogHeaderIcon(
	tooltip: String,
	onClick: () -> Unit,
	active: Boolean = false,
	content: @Composable (Color) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val tint = when {
		active -> colors.accent
		hovered -> colors.textPrimary
		else -> colors.textMuted
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
	) {
		Box(
			modifier = Modifier
				.size(22.dp)
				.hoverable(interactionSource)
				.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.background(
					when {
						active && hovered -> colors.accent.copy(alpha = 0.22f)
						active -> colors.accent.copy(alpha = 0.14f)
						hovered -> colors.controlHover
						else -> Color.Transparent
					},
					RoundedCornerShape(3.dp),
				),
			contentAlignment = Alignment.Center,
		) {
			content(tint)
		}
	}
}

@Composable
private fun IconLogAutoScroll(tint: Color) {
	Canvas(modifier = Modifier.size(12.dp)) {
		val stroke = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
		val cx = size.width / 2f
		val top = size.height * 0.12f
		val shaftEnd = size.height * 0.62f
		val tip = size.height * 0.78f
		val floor = size.height * 0.90f
		drawLine(tint, Offset(cx, top), Offset(cx, shaftEnd), stroke.width, cap = stroke.cap)
		val head = Path().apply {
			moveTo(size.width * 0.22f, shaftEnd)
			lineTo(cx, tip)
			lineTo(size.width * 0.78f, shaftEnd)
		}
		drawPath(head, tint, style = stroke)
		drawLine(
			tint,
			Offset(size.width * 0.16f, floor),
			Offset(size.width * 0.84f, floor),
			stroke.width,
			cap = stroke.cap,
		)
	}
}

@Composable
private fun IconLogClear(tint: Color) {
	Canvas(modifier = Modifier.size(12.dp)) {
		val stroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
		drawLine(tint, Offset(size.width * 0.18f, size.height * 0.30f), Offset(size.width * 0.82f, size.height * 0.30f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(size.width * 0.38f, size.height * 0.16f), Offset(size.width * 0.62f, size.height * 0.16f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(size.width * 0.28f, size.height * 0.30f), Offset(size.width * 0.34f, size.height * 0.86f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(size.width * 0.72f, size.height * 0.30f), Offset(size.width * 0.66f, size.height * 0.86f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(size.width * 0.34f, size.height * 0.86f), Offset(size.width * 0.66f, size.height * 0.86f), stroke.width, cap = stroke.cap)
	}
}

@Composable
private fun IconLogCopy(tint: Color) {
	Canvas(modifier = Modifier.size(12.dp)) {
		val stroke = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
		drawRect(
			color = tint,
			topLeft = Offset(size.width * 0.28f, size.height * 0.10f),
			size = Size(size.width * 0.58f, size.height * 0.58f),
			style = stroke,
		)
		drawRect(
			color = tint,
			topLeft = Offset(size.width * 0.10f, size.height * 0.32f),
			size = Size(size.width * 0.58f, size.height * 0.58f),
			style = stroke,
		)
	}
}

/**
 * Source-chip colors for a log row. Dark keeps the existing Nord-ish fills; light uses a soft wash
 * with a saturated label so the chip stays readable on a white panel.
 */
private fun logSourceBadge(source: LogSource, dark: Boolean): Triple<Color, Color, String> = when (source) {
	LogSource.SYSTEM -> if (dark) {
		Triple(Color(0xFF2E3440), Color(0xFF88C0D0), "SYSTEM")
	} else {
		Triple(Color(0xFFE8EEF4), Color(0xFF3A6B8C), "SYSTEM")
	}
	LogSource.MCP_SERVER -> if (dark) {
		Triple(Color(0xFF1E3A3A), Color(0xFF4EC9B0), "MCP")
	} else {
		Triple(Color(0xFFE3F4EF), Color(0xFF1F7A66), "MCP")
	}
	LogSource.AGENT -> if (dark) {
		Triple(Color(0xFF3B2E58), Color(0xFFDCDCAA), "AGENT")
	} else {
		Triple(Color(0xFFF1ECF8), Color(0xFF6B4FA0), "AGENT")
	}
	// Same blue family the history tree gives a "User" node.
	LogSource.EDITOR -> if (dark) {
		Triple(Color(0xFF1E3A5F), Color(0xFF9CDCFE), "EDITOR")
	} else {
		Triple(Color(0xFFE6F0FA), Color(0xFF1A5FA8), "EDITOR")
	}
}

@Composable
private fun LogEntryRow(
	entry: AppLogEntry,
	onImageClick: (ByteArray, String?) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val timeText = remember(entry.timestamp) {
		TIME_FORMATTER.format(entry.timestamp)
	}

	val (sourceBg, sourceFg, sourceLabel) = logSourceBadge(entry.source, colors.isDark)

	val textColor = when (entry.level) {
		LogLevel.ERROR -> colors.error
		LogLevel.WARNING -> colors.warning
		LogLevel.SUCCESS -> colors.success
		LogLevel.INFO -> colors.textPrimary
	}

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelBackground.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
			.padding(horizontal = 4.dp, vertical = 2.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text(
				text = timeText,
				style = typography.monoSmall.copy(fontSize = 10.sp),
				color = colors.textMuted,
			)

			Box(
				modifier = Modifier
					.clip(RoundedCornerShape(2.dp))
					.background(sourceBg)
					.padding(horizontal = 4.dp, vertical = 1.dp),
			) {
				Text(
					text = sourceLabel,
					style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
					color = sourceFg,
				)
			}

			if (entry.tag.isNotBlank()) {
				Box(
					modifier = Modifier
						.clip(RoundedCornerShape(2.dp))
						.background(colors.controlBackground)
						.border(BorderStroke(0.5.dp, colors.border), RoundedCornerShape(2.dp))
						.padding(horizontal = 4.dp, vertical = 1.dp),
				) {
					Text(
						text = entry.tag,
						style = typography.caption.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
					)
				}
			}

			Text(
				text = entry.message,
				style = typography.mono.copy(fontSize = 11.sp, lineHeight = 15.sp),
				color = textColor,
				modifier = Modifier.weight(1f),
			)
		}

		if (!entry.detail.isNullOrBlank()) {
			Text(
				text = entry.detail,
				style = typography.monoSmall.copy(fontSize = 10.sp),
				color = colors.textMuted,
				modifier = Modifier.padding(start = 54.dp, top = 2.dp),
			)
		}

		if (entry.imageBytes != null) {
			val imgBytes = entry.imageBytes
			val buffered = remember(imgBytes) {
				runCatching { ImageIO.read(ByteArrayInputStream(imgBytes)) }.getOrNull()
			}
			val bitmap = remember(buffered) {
				buffered?.toComposeImageBitmap()
			}

			if (bitmap != null) {
				Spacer(Modifier.height(4.dp))
				Row(
					modifier = Modifier
						.padding(start = 54.dp)
						.clip(RoundedCornerShape(4.dp))
						.background(colors.inputBackground)
						.border(BorderStroke(1.dp, colors.accent.copy(alpha = 0.4f)), RoundedCornerShape(4.dp))
						.clickable { onImageClick(imgBytes, entry.imageLabel ?: entry.message) }
						.padding(4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Box(
						modifier = Modifier
							.size(width = 90.dp, height = 64.dp)
							.clip(RoundedCornerShape(3.dp))
							.background(Color.Black),
						contentAlignment = Alignment.Center,
					) {
						CheckerboardBackground(
							modifier = Modifier.fillMaxSize(),
							squareSizePx = 8f,
						)
						Image(
							bitmap = bitmap,
							contentDescription = entry.imageLabel ?: "Log Image",
							modifier = Modifier.fillMaxSize().padding(2.dp),
						)
					}

					Column(modifier = Modifier.widthIn(max = 240.dp)) {
						Text(
							text = entry.imageLabel ?: "Image",
							style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
							color = colors.accent,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (buffered != null) {
							Text(
								text = "${buffered.width} × ${buffered.height} px · ${(imgBytes.size / 1024).coerceAtLeast(1)} KB",
								style = typography.caption.copy(fontSize = 9.5.sp),
								color = colors.textMuted,
							)
						}
						Text(
							text = "🔍 Click to inspect",
							style = typography.caption.copy(fontSize = 9.sp),
							color = colors.textMuted,
						)
					}
				}
			}
		}
	}
}
