package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CheckerboardBackground
import io.github.psd2live.ui.components.CompactButton
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
	AGENT_MCP,
	IMAGES_ONLY,
}

@Composable
fun BottomLogDock(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
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

	Column(
		modifier = modifier
			.fillMaxWidth()
			.background(colors.panelBackground)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		// 1. Resizable Splitter Handle
		if (state.logPanelExpanded) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp)
					.background(colors.divider)
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
					.draggable(
						orientation = Orientation.Vertical,
						state = rememberDraggableState { deltaY ->
							// Dragging up increases height (deltaY is negative)
							val newHeight = state.logPanelHeight - deltaY
							viewModel.setLogPanelHeight(newHeight)
						},
					),
			)
		}

		val imageCount = remember(state.logEntries) { state.logEntries.count { it.imageBytes != null } }

		// 2. Dock Header / Toolbar
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(28.dp)
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			// Left: Expand/Collapse toggle & Title & Count
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				CompactIconButton(
					onClick = { viewModel.setLogPanelExpanded(!state.logPanelExpanded) },
					size = 20.dp,
				) {
					IconChevron(
						expanded = state.logPanelExpanded,
						tint = colors.textPrimary,
					)
				}

				Text(
					text = tr("log.dock.title"),
					style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
				)

				Text(
					text = tr("log.dock.summary", state.logEntries.size, imageCount),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
			}

			// Right: Filter Chips, Search, and Action Buttons
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				if (state.logPanelExpanded) {
					// Filter Chips
					FilterChip(
						text = tr("log.dock.filter.all"),
						selected = currentFilter == LogFilter.ALL,
						onClick = { currentFilter = LogFilter.ALL },
					)
					FilterChip(
						text = tr("log.dock.filter.system"),
						selected = currentFilter == LogFilter.SYSTEM,
						onClick = { currentFilter = LogFilter.SYSTEM },
					)
					FilterChip(
						text = tr("log.dock.filter.agent"),
						selected = currentFilter == LogFilter.AGENT_MCP,
						onClick = { currentFilter = LogFilter.AGENT_MCP },
					)
					FilterChip(
						text = tr("log.dock.filter.image"),
						selected = currentFilter == LogFilter.IMAGES_ONLY,
						badge = if (imageCount > 0) "$imageCount" else null,
						onClick = { currentFilter = LogFilter.IMAGES_ONLY },
					)

					Spacer(Modifier.width(4.dp))

					// Search Box
					CompactTextField(
						value = searchQuery,
						onValueChange = { searchQuery = it },
						placeholder = "Filter…",
						modifier = Modifier.width(100.dp).height(20.dp),
					)

					Spacer(Modifier.width(4.dp))

					// Auto-scroll toggle
					CompactButton(
						text = if (autoScroll) "Auto Scroll: ON" else "Auto Scroll: OFF",
						onClick = { autoScroll = !autoScroll },
						height = 20.dp,
						isPrimary = autoScroll,
					)

					// Clear Button
					CompactButton(
						text = tr("log.dock.clear"),
						onClick = { viewModel.clearLogs() },
						height = 20.dp,
					)

					// Copy Logs Button
					CompactButton(
						text = tr("log.dock.copy"),
						onClick = ::copyLogs,
						height = 20.dp,
					)
				}
			}
		}

		// 3. Scrollable Log Body (when expanded)
		if (state.logPanelExpanded) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(state.logPanelHeight.dp)
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
private fun FilterChip(
	text: String,
	selected: Boolean,
	badge: String? = null,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(3.dp))
			.background(if (selected) colors.accent.copy(alpha = 0.25f) else colors.controlBackground)
			.border(
				BorderStroke(1.dp, if (selected) colors.accent else colors.border),
				RoundedCornerShape(3.dp),
			)
			.clickable(onClick = onClick)
			.padding(horizontal = 6.dp, vertical = 2.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			Text(
				text = text,
				style = typography.caption.copy(fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
				color = if (selected) colors.accent else colors.textMuted,
			)
			if (badge != null) {
				Box(
					modifier = Modifier
						.clip(RoundedCornerShape(8.dp))
						.background(colors.accent)
						.padding(horizontal = 4.dp, vertical = 0.5.dp),
				) {
					Text(
						text = badge,
						style = typography.monoSmall.copy(fontSize = 8.5.sp, color = Color.White),
					)
				}
			}
		}
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

	val (sourceBg, sourceFg, sourceLabel) = when (entry.source) {
		LogSource.SYSTEM -> Triple(Color(0xFF2E3440), Color(0xFF88C0D0), "SYSTEM")
		LogSource.MCP_SERVER -> Triple(Color(0xFF1E3A3A), Color(0xFF4EC9B0), "MCP")
		LogSource.AGENT -> Triple(Color(0xFF3B2E58), Color(0xFFDCDCAA), "AGENT")
	}

	val textColor = when (entry.level) {
		LogLevel.ERROR -> colors.error
		LogLevel.WARNING -> colors.warning
		LogLevel.SUCCESS -> Color(0xFF4EC9B0)
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
			// Timestamp
			Text(
				text = timeText,
				style = typography.monoSmall.copy(fontSize = 10.sp),
				color = colors.textMuted,
			)

			// Source Badge
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

			// Tag
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

			// Message
			Text(
				text = entry.message,
				style = typography.mono.copy(fontSize = 11.sp, lineHeight = 15.sp),
				color = textColor,
				modifier = Modifier.weight(1f),
			)
		}

		// Detail if present
		if (!entry.detail.isNullOrBlank()) {
			Text(
				text = entry.detail,
				style = typography.monoSmall.copy(fontSize = 10.sp),
				color = colors.textMuted,
				modifier = Modifier.padding(start = 54.dp, top = 2.dp),
			)
		}

		// Inline Image Thumbnail Rendering
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
						.background(Color(0xFF141416))
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

