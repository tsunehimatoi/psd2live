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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import io.github.psd2live.ui.utils.DesktopUtils
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
	uiScale: Float = 1.0f,
	fontScale: Float = 1.0f,
	onOpenPsd: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onSaveProjectAs: () -> Unit,
    projectTitle: String,
	onReanalyze: () -> Unit,
	onReexportPsd: () -> Unit,
	onOpenOutput: () -> Unit,
	onGenerate: () -> Unit,
	onExportTo: () -> Unit,
	onClose: () -> Unit,
	onSetLanguage: (AppLanguage) -> Unit,
	onZoomIn: () -> Unit = {},
	onZoomOut: () -> Unit = {},
	onResetZoom: () -> Unit = {},
	onSetUiScale: (Float) -> Unit = {},
	onSetFontScale: (Float) -> Unit = {},
	clickToSelectLayer: Boolean = true,
	showTexture: Boolean = true,
	showMesh: Boolean = false,
	showWarp: Boolean = false,
	contextualWarp: Boolean = true,
	filterSelectedOnly: Boolean = false,
	dimUnselected: Boolean = true,
	showSelectionBounds: Boolean = true,
	warpShowNames: Boolean = true,
	warpShowIndices: Boolean = false,
	onToggleClickToSelectLayer: () -> Unit = {},
	onToggleShowTexture: () -> Unit = {},
	onToggleShowMesh: () -> Unit = {},
	onToggleShowWarp: () -> Unit = {},
	onToggleContextualWarp: () -> Unit = {},
	onToggleFilterSelectedOnly: () -> Unit = {},
	onToggleDimUnselected: () -> Unit = {},
	onToggleShowSelectionBounds: () -> Unit = {},
	onToggleWarpShowNames: () -> Unit = {},
	onToggleWarpShowIndices: () -> Unit = {},
	onShowSettings: () -> Unit = {},
	onShowAgentConnection: () -> Unit,
	onShowTextureUpscale: () -> Unit,
	onShowHistory: () -> Unit,
	onShowAbout: () -> Unit,
	onShowHelp: (HelpTab) -> Unit = { onShowAbout() },
	onOpenUrl: (String) -> Unit = { DesktopUtils.openBrowser(it) },
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	// Track which menu is open (null if none)
	var activeMenu by remember { mutableStateOf<String?>(null) }
	var activeSubmenu by remember { mutableStateOf<String?>(null) }

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
		// --- LEFT: Tool Menus ---
		Row(
			modifier = Modifier.fillMaxHeight(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Spacer(modifier = Modifier.width(6.dp))

			// 1. File Menu
			TitleBarMenuItem(
				title = tr("menu.file"),
				isOpen = activeMenu == "file",
				onToggle = {
					activeSubmenu = null
					activeMenu = if (activeMenu == "file") null else "file"
				},
				onHoverWhenActive = {
					if (activeMenu != null && activeMenu != "file") {
						activeMenu = "file"
						activeSubmenu = null
					}
				},
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "file",
					onDismissRequest = {
						activeMenu = null
						activeSubmenu = null
					},
					modifier = Modifier.widthIn(min = 220.dp, max = 280.dp),
				) {
					// 1. 工程管理 (Project)
					AppMenuHeader(tr("menu.file.category.project"))
					AppMenuItem(text = tr("project.open"), shortcut = "Ctrl+O", enabled = !isBusy, onClick = { activeMenu = null; onOpenProject() })
					AppMenuItem(text = tr("project.save"), shortcut = "Ctrl+S", enabled = hasInput, onClick = { activeMenu = null; onSaveProject() })
					AppMenuItem(text = tr("project.saveAs"), shortcut = "Ctrl+Shift+S", enabled = hasInput, onClick = { activeMenu = null; onSaveProjectAs() })

					AppMenuSeparator()

					// 2. PSD 原画 (Source PSD)
					AppMenuHeader(tr("menu.file.category.psd"))
					AppMenuItem(
						text = tr("menu.file.openPsd"),
						shortcut = "Ctrl+Shift+O",
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
					AppMenuItem(
						text = tr("menu.file.reexportPsd"),
						shortcut = "Ctrl+Shift+E",
						enabled = hasInput && !isBusy,
						onClick = {
							activeMenu = null
							onReexportPsd()
						},
					)

					AppMenuSeparator()

					// 3. 模型导出 (Model Export)
					AppMenuHeader(tr("menu.file.category.export"))
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
					AppMenuItem(
						text = tr("menu.file.openOutput"),
						enabled = canOpenOutput,
						onClick = {
							activeMenu = null
							onOpenOutput()
						},
					)

					AppMenuSeparator()

					// 4. 首选项与系统 (Preferences & Lifecycle)
					AppMenuItem(
						text = tr("menu.file.settings"),
						shortcut = "Ctrl+,",
						onClick = {
							activeMenu = null
							onShowSettings()
						},
					)
					AppMenuItem(
						text = tr("menu.file.exit"),
						shortcut = "Alt+F4",
						onClick = {
							activeMenu = null
							onClose()
						},
					)
				}
			}

			// 2. View Menu (视图)
			TitleBarMenuItem(
				title = tr("menu.view"),
				isOpen = activeMenu == "view",
				onToggle = {
					activeSubmenu = null
					activeMenu = if (activeMenu == "view") null else "view"
				},
				onHoverWhenActive = {
					if (activeMenu != null && activeMenu != "view") {
						activeMenu = "view"
						activeSubmenu = null
					}
				},
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "view",
					onDismissRequest = {
						activeMenu = null
						activeSubmenu = null
					},
					modifier = Modifier.widthIn(min = 210.dp, max = 280.dp),
				) {
					// 1. 画布与模型 (Canvas & Model)
					AppMenuHeader(tr("menu.view.category.canvas"))
					AppMenuItem(
						text = tr("menu.view.clickToSelectLayer"),
						isChecked = clickToSelectLayer,
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onToggleClickToSelectLayer()
						},
					)
					AppMenuItem(
						text = tr("canvas.visibility.texture"),
						isChecked = showTexture,
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onToggleShowTexture()
						},
					)
					AppMenuItem(
						text = tr("canvas.visibility.mesh"),
						isChecked = showMesh,
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onToggleShowMesh()
						},
					)
					AppMenuItem(
						text = tr("canvas.visibility.warp"),
						isChecked = showWarp,
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onToggleShowWarp()
						},
					)

					// 二级菜单: 辅助显示与标记 (Overlays)
					AppSubmenuItem(
						text = tr("menu.view.overlays"),
						isOpen = activeSubmenu == "overlays",
						onOpen = { activeSubmenu = "overlays" },
						onDismiss = { if (activeSubmenu == "overlays") activeSubmenu = null },
					) {
						AppMenuHeader(tr("menu.view.category.focus"))
						AppMenuItem(
							text = tr("canvas.information.contextualWarp"),
							isChecked = contextualWarp,
							onClick = {
								activeMenu = null
								activeSubmenu = null
								onToggleContextualWarp()
							},
						)
						AppMenuItem(
							text = tr("canvas.information.selectedOnly"),
							isChecked = filterSelectedOnly,
							onClick = {
								activeMenu = null
								activeSubmenu = null
								onToggleFilterSelectedOnly()
							},
						)
						AppMenuItem(
							text = tr("canvas.visibility.dimUnselected"),
							isChecked = dimUnselected,
							onClick = {
								activeMenu = null
								activeSubmenu = null
								onToggleDimUnselected()
							},
						)

						AppMenuSeparator()

						AppMenuHeader(tr("menu.view.category.overlays"))
						AppMenuItem(
							text = tr("canvas.information.selectionBounds"),
							isChecked = showSelectionBounds,
							onClick = {
								activeMenu = null
								activeSubmenu = null
								onToggleShowSelectionBounds()
							},
						)
						AppMenuItem(
							text = tr("canvas.information.names"),
							isChecked = warpShowNames,
							onClick = {
								activeMenu = null
								activeSubmenu = null
								onToggleWarpShowNames()
							},
						)
						AppMenuItem(
							text = tr("canvas.information.indices"),
							isChecked = warpShowIndices,
							onClick = {
								activeMenu = null
								activeSubmenu = null
								onToggleWarpShowIndices()
							},
						)
					}

					AppMenuSeparator()

					// 2. 界面与缩放调整 (Zoom & Scale - 放后面)
					AppMenuHeader(tr("menu.view.category.zoom"))
					AppMenuItem(
						text = tr("menu.view.zoomIn"),
						shortcut = "Ctrl+=",
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onZoomIn()
						},
					)
					AppMenuItem(
						text = tr("menu.view.zoomOut"),
						shortcut = "Ctrl+-",
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onZoomOut()
						},
					)
					AppMenuItem(
						text = tr("menu.view.zoomReset"),
						shortcut = "Ctrl+0",
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onResetZoom()
						},
					)

					// 二级菜单: 界面缩放比例 (Interface Scale)
					val currentUiPercent = (uiScale * 100).toInt()
					AppSubmenuItem(
						text = "${tr("menu.view.uiScale")} ($currentUiPercent%)",
						isOpen = activeSubmenu == "uiScale",
						onOpen = { activeSubmenu = "uiScale" },
						onDismiss = { if (activeSubmenu == "uiScale") activeSubmenu = null },
					) {
						val uiScales = listOf(1.0f, 1.15f, 1.25f, 1.35f, 1.50f, 1.75f, 2.00f, 2.50f)
						for (scale in uiScales) {
							val percent = (scale * 100).toInt()
							val label = when (scale) {
								1.0f -> "$percent% (${tr("settings.scale.standard")})"
								1.25f -> "$percent% (2K)"
								1.5f -> "$percent% (2K/4K)"
								1.75f -> "$percent% (4K)"
								2.0f -> "$percent% (4K)"
								else -> "$percent%"
							}
							val isSelected = kotlin.math.abs(uiScale - scale) < 0.03f
							AppMenuItem(
								text = label,
								isChecked = isSelected,
								onClick = {
									activeMenu = null
									activeSubmenu = null
									onSetUiScale(scale)
								},
							)
						}
					}

					// 二级菜单: 字体大小 (Font Size)
					val currentFontPercent = (fontScale * 100).toInt()
					AppSubmenuItem(
						text = "${tr("menu.view.fontScale")} ($currentFontPercent%)",
						isOpen = activeSubmenu == "fontScale",
						onOpen = { activeSubmenu = "fontScale" },
						onDismiss = { if (activeSubmenu == "fontScale") activeSubmenu = null },
					) {
						val fontScales = listOf(
							0.90f to tr("settings.font.compact"),
							1.00f to tr("settings.font.standard"),
							1.15f to tr("settings.font.large"),
							1.30f to tr("settings.font.extraLarge"),
						)
						for ((scale, label) in fontScales) {
							val isSelected = kotlin.math.abs(fontScale - scale) < 0.04f
							AppMenuItem(
								text = label,
								isChecked = isSelected,
								onClick = {
									activeMenu = null
									activeSubmenu = null
									onSetFontScale(scale)
								},
							)
						}
					}

					AppMenuSeparator()

					// 3. 首选项与设置 (Preferences)
					AppMenuItem(
						text = tr("menu.view.settings"),
						shortcut = "Ctrl+,",
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowSettings()
						},
					)
				}
			}

			// 2. Tools Menu
			TitleBarMenuItem(
				title = tr("menu.tools"),
				isOpen = activeMenu == "tools",
				onToggle = {
					activeSubmenu = null
					activeMenu = if (activeMenu == "tools") null else "tools"
				},
				onHoverWhenActive = {
					if (activeMenu != null && activeMenu != "tools") {
						activeMenu = "tools"
						activeSubmenu = null
					}
				},
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "tools",
					onDismissRequest = {
						activeMenu = null
						activeSubmenu = null
					},
					modifier = Modifier.widthIn(min = 180.dp, max = 240.dp),
				) {
					AppMenuItem(
						text = tr("menu.tools.textureUpscale"),
						shortcut = "Ctrl+U",
						enabled = hasInput && !isBusy,
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowTextureUpscale()
						},
					)
				}
			}

			// 3. Agent / MCP Menu (Dedicated top-level menu)
			TitleBarMenuItem(
				title = tr("menu.agent"),
				isOpen = activeMenu == "agent",
				onToggle = {
					activeSubmenu = null
					activeMenu = if (activeMenu == "agent") null else "agent"
				},
				onHoverWhenActive = {
					if (activeMenu != null && activeMenu != "agent") {
						activeMenu = "agent"
						activeSubmenu = null
					}
				},
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "agent",
					onDismissRequest = {
						activeMenu = null
						activeSubmenu = null
					},
					modifier = Modifier.widthIn(min = 180.dp, max = 260.dp),
				) {
					AppMenuItem(
						text = tr("menu.agent.connection"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowAgentConnection()
						},
					)
					AppMenuItem(
						text = tr("menu.agent.history"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowHistory()
						},
					)
				}
			}

			// 3. Language Menu
			TitleBarMenuItem(
				title = tr("menu.language"),
				isOpen = activeMenu == "language",
				onToggle = {
					activeSubmenu = null
					activeMenu = if (activeMenu == "language") null else "language"
				},
				onHoverWhenActive = {
					if (activeMenu != null && activeMenu != "language") {
						activeMenu = "language"
						activeSubmenu = null
					}
				},
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "language",
					onDismissRequest = {
						activeMenu = null
						activeSubmenu = null
					},
					modifier = Modifier.widthIn(min = 140.dp, max = 200.dp),
				) {
					for (lang in I18n.supportedLanguages) {
						AppMenuItem(
							text = tr(lang.displayNameKey),
							isChecked = lang == currentLanguage,
							onClick = {
								activeMenu = null
								activeSubmenu = null
								onSetLanguage(lang)
							},
						)
					}
				}
			}

			// 4. Help Menu
			TitleBarMenuItem(
				title = tr("menu.help"),
				isOpen = activeMenu == "help",
				onToggle = {
					activeSubmenu = null
					activeMenu = if (activeMenu == "help") null else "help"
				},
				onHoverWhenActive = {
					if (activeMenu != null && activeMenu != "help") {
						activeMenu = "help"
						activeSubmenu = null
					}
				},
			) {
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "help",
					onDismissRequest = {
						activeMenu = null
						activeSubmenu = null
					},
					modifier = Modifier.widthIn(min = 180.dp, max = 250.dp),
				) {
					AppMenuItem(
						text = tr("menu.help.tutorial"),
						shortcut = "F1",
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowHelp(HelpTab.QUICK_START)
						},
					)
					AppMenuItem(
						text = tr("menu.help.psd_spec"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowHelp(HelpTab.PSD_SPEC)
						},
					)
					AppMenuItem(
						text = tr("menu.help.shortcuts"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowHelp(HelpTab.SHORTCUTS)
						},
					)
					AppMenuSeparator()
					AppMenuItem(
						text = tr("menu.help.github"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onOpenUrl(DesktopUtils.GITHUB_REPO_URL)
						},
					)
					AppMenuItem(
						text = tr("menu.help.issues"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onOpenUrl(DesktopUtils.GITHUB_ISSUES_URL)
						},
					)
					AppMenuItem(
						text = tr("menu.help.releases"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onOpenUrl(DesktopUtils.GITHUB_RELEASES_URL)
						},
					)
					AppMenuItem(
						text = tr("menu.help.docs"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onOpenUrl(DesktopUtils.GITHUB_DOCS_URL)
						},
					)
					AppMenuSeparator()
					AppMenuItem(
						text = tr("menu.about"),
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowHelp(HelpTab.ABOUT)
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
				text = "PSD2Live — $projectTitle",
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
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val titleBarHeightPx = with(density) { 32.dp.roundToPx() }

	Popup(
		alignment = Alignment.TopStart,
		offset = IntOffset(0, titleBarHeightPx),
		onDismissRequest = onDismissRequest,
		properties = PopupProperties(focusable = true),
	) {
		androidx.compose.runtime.CompositionLocalProvider(
			LocalDensity provides density,
			LocalToolColors provides colors,
			LocalToolTypography provides typography,
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
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppMenuItem(
	text: String,
	shortcut: String? = null,
	isChecked: Boolean? = null,
	enabled: Boolean = true,
	onHover: (() -> Unit)? = null,
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
			.onPointerEvent(PointerEventType.Enter) {
				if (enabled) {
					isHovered = true
					onHover?.invoke()
				}
			}
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppSubmenuItem(
	text: String,
	isOpen: Boolean,
	onOpen: () -> Unit,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	var isHovered by remember { mutableStateOf(false) }
	var itemWidthPx by remember { mutableStateOf(0) }

	Box(
		modifier = modifier
			.fillMaxWidth()
			.onGloballyPositioned { itemWidthPx = it.size.width }
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(28.dp)
				.background(
					when {
						isOpen || isHovered -> colors.selection
						else -> Color.Transparent
					}
				)
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.onPointerEvent(PointerEventType.Enter) {
					isHovered = true
					onOpen()
				}
				.onPointerEvent(PointerEventType.Exit) { isHovered = false }
				.clickable(onClick = onOpen)
				.padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = text,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = if (isOpen || isHovered) colors.selectionText else colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			IconChevron(
				expanded = false,
				modifier = Modifier.size(9.dp),
				tint = if (isOpen || isHovered) colors.selectionText else colors.textMuted,
			)
		}

		if (isOpen) {
			Popup(
				alignment = Alignment.TopStart,
				offset = IntOffset(itemWidthPx - 1, 0),
				onDismissRequest = onDismiss,
				properties = PopupProperties(focusable = false),
			) {
				androidx.compose.runtime.CompositionLocalProvider(
					LocalDensity provides density,
					LocalToolColors provides colors,
					LocalToolTypography provides typography,
				) {
					Surface(
						color = colors.panelElevated,
						border = BorderStroke(1.dp, colors.border),
						shape = RoundedCornerShape(0.dp),
						elevation = 6.dp,
					) {
						Column(
							modifier = Modifier
								.widthIn(min = 180.dp, max = 260.dp)
								.padding(vertical = 0.dp),
						) {
							content()
						}
					}
				}
			}
		}
	}
}

@Composable
fun AppMenuSeparator() {
	val colors = LocalToolColors.current
	Divider(
		color = colors.divider,
		thickness = 1.dp,
		modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
	)
}

@Composable
fun AppMenuHeader(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(
		text = text,
		style = typography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
		color = colors.textMuted.copy(alpha = 0.7f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp, vertical = 3.dp),
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
