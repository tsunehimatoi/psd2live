package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppTitleBar(
	modifier: Modifier = Modifier,
	window: ComposeWindow? = null,
	windowState: WindowState,
	isBusy: Boolean,
	hasInput: Boolean,
	canOpenOutput: Boolean,
	canGenerate: Boolean,
	currentLanguage: AppLanguage,
	onOpenPsd: () -> Unit,
	onReanalyze: () -> Unit,
	onOpenOutput: () -> Unit,
	onGenerate: () -> Unit,
	onExportTo: () -> Unit,
	onClose: () -> Unit,
	onSetLanguage: (AppLanguage) -> Unit,
	onShowAbout: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	// Track which menu is open (null if none)
	var activeMenu by remember { mutableStateOf<String?>(null) }

	// Dragging state using absolute screen cursor coordinates
	var initialMouseLocation by remember { mutableStateOf<Point?>(null) }
	var initialWindowLocation by remember { mutableStateOf<Point?>(null) }

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(32.dp)
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// --- LEFT: Application Menus ---
		Row(
			modifier = Modifier.fillMaxHeight(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(1.dp),
		) {
			Spacer(modifier = Modifier.width(4.dp))

			// 1. File Menu
			TitleBarMenuItem(
				title = tr("menu.file"),
				isOpen = activeMenu == "file",
				onToggle = { activeMenu = if (activeMenu == "file") null else "file" },
				onHoverWhenActive = { if (activeMenu != null && activeMenu != "file") activeMenu = "file" },
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "file",
					onDismissRequest = { activeMenu = null },
					modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
				) {
					AppMenuItem(
						text = tr("menu.file.openPsd"),
						shortcut = "Ctrl+O",
						enabled = !isBusy,
						onClick = {
							activeMenu = null
							onOpenPsd()
						},
					)
					AppMenuItem(
						text = tr("menu.file.reanalyze"),
						shortcut = "Ctrl+R",
						enabled = hasInput && !isBusy,
						onClick = {
							activeMenu = null
							onReanalyze()
						},
					)
					AppMenuSeparator()
					AppMenuItem(
						text = tr("menu.file.openOutput"),
						enabled = canOpenOutput,
						onClick = {
							activeMenu = null
							onOpenOutput()
						},
					)
					AppMenuSeparator()
					AppMenuItem(
						text = tr("menu.file.generate"),
						shortcut = "Ctrl+G",
						enabled = canGenerate,
						onClick = {
							activeMenu = null
							onGenerate()
						},
					)
					AppMenuItem(
						text = tr("menu.file.exportTo"),
						shortcut = "Ctrl+Shift+G",
						enabled = canGenerate,
						onClick = {
							activeMenu = null
							onExportTo()
						},
					)
					AppMenuSeparator()
					AppMenuItem(
						text = tr("menu.file.exit"),
						onClick = {
							activeMenu = null
							onClose()
						},
					)
				}
			}

			// 2. Language Menu
			TitleBarMenuItem(
				title = tr("menu.language"),
				isOpen = activeMenu == "language",
				onToggle = { activeMenu = if (activeMenu == "language") null else "language" },
				onHoverWhenActive = { if (activeMenu != null && activeMenu != "language") activeMenu = "language" },
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "language",
					onDismissRequest = { activeMenu = null },
					modifier = Modifier.widthIn(min = 140.dp, max = 200.dp),
				) {
					for (lang in I18n.supportedLanguages) {
						AppMenuItem(
							text = tr(lang.displayNameKey),
							isChecked = lang == currentLanguage,
							onClick = {
								activeMenu = null
								onSetLanguage(lang)
							},
						)
					}
				}
			}

			// 3. Help Menu
			TitleBarMenuItem(
				title = tr("menu.help"),
				isOpen = activeMenu == "help",
				onToggle = { activeMenu = if (activeMenu == "help") null else "help" },
				onHoverWhenActive = { if (activeMenu != null && activeMenu != "help") activeMenu = "help" },
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "help",
					onDismissRequest = { activeMenu = null },
					modifier = Modifier.widthIn(min = 140.dp, max = 220.dp),
				) {
					AppMenuItem(
						text = tr("menu.about"),
						onClick = {
							activeMenu = null
							onShowAbout()
						},
					)
				}
			}
		}

		// --- CENTER: Draggable Window Area & App Title ---
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				.pointerInput(window, windowState) {
					detectTapGestures(
						onDoubleTap = {
							windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
								WindowPlacement.Floating
							} else {
								WindowPlacement.Maximized
							}
						}
					)
				}
				.pointerInput(window, windowState) {
					detectDragGestures(
						onDragStart = {
							initialMouseLocation = MouseInfo.getPointerInfo()?.location
							initialWindowLocation = window?.location
						},
						onDrag = { change, _ ->
							change.consume()
							val mouse = MouseInfo.getPointerInfo()?.location
							val initMouse = initialMouseLocation
							val initWin = initialWindowLocation
							if (mouse != null && initMouse != null && initWin != null && window != null) {
								if (windowState.placement == WindowPlacement.Maximized) {
									windowState.placement = WindowPlacement.Floating
								}
								window.setLocation(
									initWin.x + (mouse.x - initMouse.x),
									initWin.y + (mouse.y - initMouse.y),
								)
							}
						},
						onDragEnd = {
							initialMouseLocation = null
							initialWindowLocation = null
						},
						onDragCancel = {
							initialMouseLocation = null
							initialWindowLocation = null
						},
					)
				},
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = tr("app.title"),
				style = typography.caption.copy(fontSize = 11.5.sp),
				color = colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		// --- RIGHT: Window Control Buttons (Minimize, Maximize/Restore, Close) ---
		Row(
			modifier = Modifier.fillMaxHeight(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// Minimize
			WindowControlButton(
				onClick = { windowState.isMinimized = true },
			) {
				Canvas(modifier = Modifier.size(10.dp, 1.dp)) {
					drawRect(colors.textPrimary)
				}
			}

			// Maximize / Restore
			val isMaximized = windowState.placement == WindowPlacement.Maximized
			WindowControlButton(
				onClick = {
					windowState.placement = if (isMaximized) {
						WindowPlacement.Floating
					} else {
						WindowPlacement.Maximized
					}
				},
			) {
				Canvas(modifier = Modifier.size(10.dp)) {
					if (isMaximized) {
						// Restore icon (overlapping boxes)
						val stroke = 1.dp.toPx()
						// Back box
						drawRect(
							colors.textPrimary,
							topLeft = Offset(2.dp.toPx(), 0f),
							size = Size(8.dp.toPx(), 8.dp.toPx()),
							style = Stroke(stroke),
						)
						// Front box
						drawRect(
							colors.panelElevated,
							topLeft = Offset(0f, 2.dp.toPx()),
							size = Size(8.dp.toPx(), 8.dp.toPx()),
						)
						drawRect(
							colors.textPrimary,
							topLeft = Offset(0f, 2.dp.toPx()),
							size = Size(8.dp.toPx(), 8.dp.toPx()),
							style = Stroke(stroke),
						)
					} else {
						// Single box
						drawRect(
							colors.textPrimary,
							size = size,
							style = Stroke(1.dp.toPx()),
						)
					}
				}
			}

			// Close
			WindowControlButton(
				isClose = true,
				onClick = onClose,
			) {
				Canvas(modifier = Modifier.size(10.dp)) {
					val stroke = 1.25.dp.toPx()
					drawLine(
						colors.textPrimary,
						start = Offset(0f, 0f),
						end = Offset(size.width, size.height),
						strokeWidth = stroke,
					)
					drawLine(
						colors.textPrimary,
						start = Offset(size.width, 0f),
						end = Offset(0f, size.height),
						strokeWidth = stroke,
					)
				}
			}
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TitleBarMenuItem(
	title: String,
	isOpen: Boolean,
	onToggle: () -> Unit,
	onHoverWhenActive: () -> Unit,
	content: @Composable () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }

	Box(
		modifier = Modifier
			.fillMaxHeight()
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.onPointerEvent(PointerEventType.Enter) {
				isHovered = true
				onHoverWhenActive()
			}
			.onPointerEvent(PointerEventType.Exit) {
				isHovered = false
			}
			.background(
				when {
					isOpen -> colors.selection
					isHovered -> colors.controlHover
					else -> Color.Transparent
				},
				RoundedCornerShape(3.dp),
			)
			.clickable(onClick = onToggle)
			.padding(horizontal = 10.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = title,
			style = typography.body.copy(fontSize = 11.5.sp),
			color = if (isOpen) colors.selectionText else colors.textPrimary,
		)
		content()
	}
}

/**
 * Clean and seamless dropdown menu built on Compose Popup.
 * Zero gap from the title bar, zero excessive inner margins, and edge-to-edge hover highlights.
 */
@Composable
private fun AppSeamlessDropdownMenu(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	if (!expanded) return

	val colors = LocalToolColors.current
	val density = LocalDensity.current
	val titleBarHeightPx = with(density) { 32.dp.roundToPx() }

	Popup(
		alignment = Alignment.TopStart,
		offset = IntOffset(0, titleBarHeightPx),
		onDismissRequest = onDismissRequest,
		properties = PopupProperties(focusable = true),
	) {
		Surface(
			color = colors.panelElevated,
			border = BorderStroke(1.dp, colors.border),
			shape = RoundedCornerShape(0.dp),
			elevation = 4.dp,
		) {
			Column(
				modifier = modifier.padding(vertical = 0.dp),
			) {
				content()
			}
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppMenuItem(
	text: String,
	shortcut: String? = null,
	isChecked: Boolean? = null,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(28.dp)
			.background(
				when {
					!enabled -> Color.Transparent
					isHovered -> colors.selection
					else -> Color.Transparent
				}
			)
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.onPointerEvent(PointerEventType.Enter) { if (enabled) isHovered = true }
			.onPointerEvent(PointerEventType.Exit) { isHovered = false }
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			modifier = Modifier.weight(1f, fill = false),
		) {
			if (isChecked != null) {
				Text(
					text = if (isChecked) "✓" else " ",
					style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
					color = if (isChecked) colors.accent else Color.Transparent,
					modifier = Modifier.width(14.dp),
				)
			}
			Text(
				text = text,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = when {
					!enabled -> colors.textDisabled
					isHovered -> colors.selectionText
					else -> colors.textPrimary
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		if (!shortcut.isNullOrBlank()) {
			Spacer(modifier = Modifier.width(16.dp))
			Text(
				text = shortcut,
				style = typography.monoSmall.copy(fontSize = 10.sp),
				color = if (enabled) colors.textMuted else colors.textDisabled,
				maxLines = 1,
			)
		}
	}
}

@Composable
fun AppMenuSeparator() {
	val colors = LocalToolColors.current
	Divider(
		color = colors.divider,
		thickness = 1.dp,
		modifier = Modifier.fillMaxWidth(),
	)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowControlButton(
	isClose: Boolean = false,
	onClick: () -> Unit,
	icon: @Composable () -> Unit,
) {
	val colors = LocalToolColors.current
	var isHovered by remember { mutableStateOf(false) }

	Box(
		modifier = Modifier
			.width(44.dp)
			.fillMaxHeight()
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.onPointerEvent(PointerEventType.Enter) { isHovered = true }
			.onPointerEvent(PointerEventType.Exit) { isHovered = false }
			.background(
				when {
					isHovered && isClose -> Color(0xFFE81123)
					isHovered -> colors.controlHover
					else -> Color.Transparent
				}
			)
			.clickable(onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		icon()
	}
}
