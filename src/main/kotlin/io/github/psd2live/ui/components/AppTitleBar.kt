package io.github.psd2live.ui.components

import io.github.psd2live.ui.views.texture.LocalMenuGlide
import io.github.psd2live.ui.views.texture.glideRow
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.menuPanel
import io.github.psd2live.ui.views.texture.menuAppearance
import io.github.psd2live.ui.theme.Corners
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import io.github.psd2live.ui.state.RecentFileKind
import io.github.psd2live.ui.state.rememberExistingRecentFiles
import io.github.psd2live.ui.tutorial.LocalTutorialTargets
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialOverlay
import io.github.psd2live.ui.tutorial.TutorialStep
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.WindowState
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.WindowMode
import io.github.psd2live.ui.utils.LocalNativeTitleBar
import io.github.psd2live.ui.utils.MainWindowModes
import io.github.psd2live.ui.utils.NativeTitleBar
import androidx.compose.ui.input.pointer.PointerEventPass
import io.github.psd2live.ui.views.PageIcon
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.DesktopUtils
import io.github.psd2live.ui.state.SidebarSide
import java.awt.MouseInfo
import java.awt.Point
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor

/** One title-bar layout toggle: the panels docked on [side] of the canvases. */
@Immutable
/** A folder the File menu opens; [path] is null, or not a folder, when there is none to open. */
data class MenuFolder(val label: String, val path: String?, val startsGroup: Boolean = false)

/** The web pages the Help menu opens, in its order: label key to address. */
internal val HelpLinks = listOf(
	"menu.help.github" to DesktopUtils.GITHUB_REPO_URL,
	"menu.help.docs" to DesktopUtils.GITHUB_DOCS_URL,
	"menu.help.examples" to DesktopUtils.EXAMPLES_REPO_URL,
	"menu.help.releases" to DesktopUtils.GITHUB_RELEASES_URL,
	"menu.help.issues" to DesktopUtils.GITHUB_ISSUES_URL,
)

data class SidebarToggle(
	val side: SidebarSide,
	/** Whether any panel on that side is shown. */
	val active: Boolean,
	/** Titles of the panels on that side, listed in the tooltip. */
	val modules: List<String>,
	/** The tutorial's hierarchy step points at whichever toggle hides the hierarchy. */
	val hostsHierarchy: Boolean = false,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppTitleBar(
	modifier: Modifier = Modifier,
	window: ComposeWindow? = null,
	windowState: WindowState,
	isBusy: Boolean,
	hasInput: Boolean,
	folders: List<MenuFolder> = emptyList(),
	currentLanguage: AppLanguage,
	darkTheme: Boolean = true,
	keymap: Keymap = Keymap.DEFAULT,
	/** Only the sides the active workspace docks panels on; a side with none gets no button. */
	sidebarToggles: List<SidebarToggle> = emptyList(),
	onToggleSidebar: (SidebarSide) -> Unit = {},
	onOpenPsd: () -> Unit,
    onReplaceCmo3: () -> Unit = {},
    onNewCmo3: () -> Unit = {},
    onNewLayout: () -> Unit = {},
    onOpenProject: () -> Unit,
	/** Recently opened projects and PSDs, newest first; File > Open Recent lists those that still exist. */
	recentFiles: List<String> = emptyList(),
	onOpenRecent: (String) -> Unit = {},
	onClearRecent: () -> Unit = {},
    onSaveProject: () -> Unit,
    onSaveProjectAs: () -> Unit,
    projectTitle: String,
	onReanalyze: () -> Unit,
	onReexportPsd: () -> Unit,
	onOpenFolder: (String) -> Unit = {},
	onShowExport: () -> Unit,
	/** Opens the export dialog of one neutral target, by id (File > Export as). */
	onExportAs: (String) -> Unit = {},
	onClose: () -> Unit,
	onSetLanguage: (AppLanguage) -> Unit,
	onZoomIn: () -> Unit = {},
	onZoomOut: () -> Unit = {},
	onResetZoom: () -> Unit = {},
	onToggleTheme: () -> Unit = {},
	/** Whether the settings page is the one shown: its title-bar button reads as pressed. */
	settingsShown: Boolean = false,
	onShowSettings: () -> Unit = {},
	onShowTextureUpscale: () -> Unit,
	onStartScreen: () -> Unit = {},
	onMeshSplitScan: () -> Unit = {},
	onFindReplace: () -> Unit = {},
	onConvertIds: () -> Unit = {},
	/** Tools > Upgrade split records: enabled while the document holds version 1 split records. */
	canUpdateGeneration: Boolean = false,
	onUpdateGeneration: () -> Unit = {},
	onPreviewGenerationUpdate: () -> Unit = {},
	onShowHistory: () -> Unit,
	onNewEditTab: () -> Unit = {},
	onNewPreviewTab: () -> Unit = {},
	onDuplicateTab: () -> Unit = {},
	onCloseTab: () -> Unit = {},
	onNextTab: () -> Unit = {},
	onPrevTab: () -> Unit = {},
	/** Opens the help page on the topic last read there. */
	onOpenHelp: () -> Unit = {},
	tutorialMenuForce: String? = null,
	tutorialHighlightTarget: TutorialTargetId? = null,
	tutorialId: TutorialId? = null,
	tutorialStep: TutorialStep? = null,
	tutorialStepIndex: Int = 0,
	tutorialReviewing: Boolean = false,
	tutorialIsFirstStep: Boolean = false,
	onTutorialNext: () -> Unit = {},
	onTutorialPrevious: () -> Unit = {},
	onTutorialSkip: () -> Unit = {},
	onTutorialExit: () -> Unit = {},
	onTutorialMenuChanged: (String?) -> Unit = {},
	onOpenUrl: (String) -> Unit = { DesktopUtils.openBrowser(it) },
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val windowMode by AppSettings.windowModeFlow.collectAsState()

	// Track which menu is open (null if none)
	var activeMenu by remember { mutableStateOf<String?>(null) }
	var activeSubmenu by remember { mutableStateOf<String?>(null) }

	// A forced menu must release focus when the tutorial moves on or exits.
	// Otherwise its popup keeps intercepting clicks on the next step's controls.
	var previousTutorialStep by remember { mutableStateOf<TutorialStep?>(null) }
	LaunchedEffect(tutorialStep, tutorialMenuForce) {
		if (tutorialStep != null || previousTutorialStep != null) {
			activeMenu = tutorialMenuForce
			activeSubmenu = null
		}
		previousTutorialStep = tutorialStep
	}
	LaunchedEffect(activeMenu) {
		onTutorialMenuChanged(activeMenu)
	}

	// Dragging state using absolute screen cursor coordinates
	var initialMouseLocation by remember { mutableStateOf<Point?>(null) }
	var initialWindowLocation by remember { mutableStateOf<Point?>(null) }

	// With the native frame, the system draws the window buttons and owns the bar's empty middle (drag, double-click,
	// Snap); the bar tells it its height and leaves room for the buttons it reports.
	val nativeTitleBar = LocalNativeTitleBar.current
	val density = LocalDensity.current
	var nativeInsets by remember(nativeTitleBar) { mutableStateOf(0.dp to 0.dp) }
	// The runtime lays its buttons out after a height or size change, so their room is read once that has run.
	val readNativeInsets = {
		if (nativeTitleBar != null) java.awt.EventQueue.invokeLater {
			val scale = window?.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
			val toDp = { inset: Float -> with(density) { (inset * scale).toDp() } }
			nativeInsets = toDp(nativeTitleBar.leftInset) to toDp(nativeTitleBar.rightInset)
		}
	}
	if (nativeTitleBar != null) {
		LaunchedEffect(nativeTitleBar, darkTheme) { nativeTitleBar.setDarkControls(darkTheme) }
		DisposableEffect(nativeTitleBar, window) {
			val resized = object : java.awt.event.ComponentAdapter() {
				override fun componentResized(e: java.awt.event.ComponentEvent) = readNativeInsets()
			}
			window?.addComponentListener(resized)
			readNativeInsets()
			onDispose { window?.removeComponentListener(resized) }
		}
	}
	// Homebrew JDK cannot report the traffic-light inset. The buttons overlay this bar, so leave their width.
	val macChrome = io.github.psd2live.ui.utils.MacShell.active && window?.isUndecorated == false && nativeTitleBar == null
	val trafficInset = if (macChrome && windowMode != WindowMode.FULLSCREEN) 78.dp else 0.dp
	// The title is centered on the window, not in the gap between the menus and the toolbar buttons.
	var macBarWidth by remember { mutableStateOf(0) }
	var macSlotStart by remember { mutableStateOf(0) }
	var macSlotWidth by remember { mutableStateOf(0) }

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(32.dp)
			// The bar is the window's backdrop, like the gaps between the panels below it.
			.background(colors.windowBackground)
			.then(if (!macChrome) Modifier else Modifier.drawBehind {
				val y = size.height - 0.5f
				drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
			})
			.onGloballyPositioned { if (macChrome) macBarWidth = it.size.width }
			.then(if (nativeTitleBar == null) Modifier else Modifier
				.onGloballyPositioned { bar ->
					// The runtime takes the height in the window's units: pixels over the display scale.
					val scale = window?.graphicsConfiguration?.defaultTransform?.scaleY?.toFloat() ?: 1f
					nativeTitleBar.height = bar.size.height / scale
					readNativeInsets()
				}
				.titleBarHitTest(nativeTitleBar, client = true)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// --- LEFT: Tool Menus ---
		Row(
			modifier = Modifier.fillMaxHeight(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Spacer(modifier = Modifier.width(6.dp + nativeInsets.first + trafficInset))

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
				modifier = Modifier.tutorialTarget(TutorialTargetId.FILE_MENU),
			) {
				val showMenuCoach = tutorialId != null && tutorialStep != null &&
					(tutorialHighlightTarget == TutorialTargetId.FILE_IMPORT ||
						tutorialHighlightTarget == TutorialTargetId.FILE_EXPORT ||
						tutorialHighlightTarget == TutorialTargetId.FILE_MENU_BODY)
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "file" || tutorialMenuForce == "file",
					onDismissRequest = {
						if (tutorialMenuForce != "file") {
							activeMenu = null
							activeSubmenu = null
						}
					},
					modifier = Modifier
						.widthIn(min = 220.dp, max = 280.dp)
						.tutorialTarget(TutorialTargetId.FILE_MENU_BODY),
					tutorialOverlay = if (showMenuCoach) {
						{
							LocalTutorialTargets.current?.let { registry ->
								TutorialOverlay(
									tutorialId = tutorialId,
									step = tutorialStep,
									stepIndex = tutorialStepIndex,
									registry = registry,
									keymap = keymap,
									reviewing = tutorialReviewing,
									isFirstStep = tutorialIsFirstStep,
									onNext = onTutorialNext,
									onPrevious = onTutorialPrevious,
									onSkip = onTutorialSkip,
									onExit = onTutorialExit,
									onFinish = onTutorialExit,
								)
							}
						}
					} else null,
				) {
					AppMenuItem(text = tr("menu.file.startPage"), onHover = { activeSubmenu = null }, onClick = { activeMenu = null; onStartScreen() })
					AppMenuSeparator()
					// Project
					AppMenuItem(text = tr("project.open"), shortcut = keymap.labelFor(ShortcutAction.OPEN_PROJECT), enabled = !isBusy, onHover = { activeSubmenu = null }, onClick = { activeMenu = null; onOpenProject() })
					val recent = rememberExistingRecentFiles(recentFiles)
					AppSubmenuItem(
						text = tr("menu.file.recent"),
						isOpen = activeSubmenu == "recent",
						onOpen = { activeSubmenu = "recent" },
						onDismiss = { if (activeSubmenu == "recent") activeSubmenu = null },
					) {
						if (recent.isNullOrEmpty()) {
							AppMenuItem(text = tr(if (recent == null) "menu.file.recent.checking" else "menu.file.recent.empty"), enabled = false, onClick = {})
						} else {
							recent.forEach { file ->
								AppMenuItem(
									text = file.name,
									// The folder tells apart files of one name; the start screen shows the whole path.
									shortcut = file.directory.substringAfterLast('\\').substringAfterLast('/'),
									enabled = !isBusy,
									icon = { tint ->
										when (file.kind) {
											RecentFileKind.PROJECT -> IconFolder(Modifier.size(14.dp), tint)
											RecentFileKind.PSD -> GridIcon(Modifier.size(14.dp), tint) { document() }
										}
									},
									onClick = { activeMenu = null; activeSubmenu = null; onOpenRecent(file.path) },
								)
							}
						}
						AppMenuSeparator()
						AppMenuItem(
							text = tr("menu.file.recent.clear"),
							enabled = recentFiles.isNotEmpty(),
							onClick = { activeMenu = null; activeSubmenu = null; onClearRecent() },
						)
					}
					AppMenuItem(text = tr("project.save"), shortcut = keymap.labelFor(ShortcutAction.SAVE_PROJECT), enabled = hasInput, onHover = { activeSubmenu = null }, onClick = { activeMenu = null; onSaveProject() })
					AppMenuItem(text = tr("project.saveAs"), shortcut = keymap.labelFor(ShortcutAction.SAVE_PROJECT_AS), enabled = hasInput, onHover = { activeSubmenu = null }, onClick = { activeMenu = null; onSaveProjectAs() })

					AppMenuSeparator()

					// New: a project from a PSD, a cmo3 or an empty layout canvas.
					AppSubmenuItem(
						text = tr("menu.file.new"),
						isOpen = activeSubmenu == "import" ||
							tutorialMenuForce == "file" && tutorialHighlightTarget == TutorialTargetId.FILE_IMPORT,
						onOpen = { activeSubmenu = "import" },
						onDismiss = { if (activeSubmenu == "import") activeSubmenu = null },
					) {
						AppMenuItem(
							text = tr("menu.file.openPsd"),
							shortcut = keymap.labelFor(ShortcutAction.OPEN_PSD),
							highlighted = tutorialHighlightTarget == TutorialTargetId.FILE_IMPORT,
							modifier = Modifier.tutorialTarget(TutorialTargetId.FILE_IMPORT),
							onClick = { activeMenu = null; activeSubmenu = null; onOpenPsd() },
						)
						AppMenuItem(text = tr("cmo3.new"), enabled = !isBusy, onClick = { activeMenu = null; activeSubmenu = null; onNewCmo3() })
						AppMenuItem(text = tr("menu.file.newLayout"), enabled = !isBusy, onClick = { activeMenu = null; activeSubmenu = null; onNewLayout() })
					}

					AppMenuSeparator()

					// Export: the Live2D model, then every other format by kind.
					AppMenuItem(
						text = tr("menu.file.export"),
						shortcut = keymap.labelFor(ShortcutAction.GENERATE),
						enabled = hasInput && !isBusy,
						highlighted = tutorialHighlightTarget == TutorialTargetId.FILE_EXPORT,
						modifier = Modifier.tutorialTarget(TutorialTargetId.FILE_EXPORT),
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; onShowExport() },
					)
					AppSubmenuItem(
						text = tr("menu.file.exportAs"),
						isOpen = activeSubmenu == "exportAs",
						onOpen = { activeSubmenu = "exportAs" },
						onDismiss = { if (activeSubmenu == "exportAs") activeSubmenu = null },
					) {
						exportMenuGroups.forEachIndexed { index, (group, targets) ->
							if (index > 0) AppMenuSeparator()
							AppMenuHeader(tr(group))
							for (target in targets) AppMenuItem(
								text = tr("export.menu.$target"),
								enabled = hasInput && !isBusy,
								onClick = { activeMenu = null; activeSubmenu = null; onExportAs(target) },
							)
						}
						AppMenuItem(
							text = tr("export.menu.sourcePsd"),
							shortcut = keymap.labelFor(ShortcutAction.REEXPORT_PSD),
							enabled = hasInput && !isBusy,
							onClick = { activeMenu = null; activeSubmenu = null; onReexportPsd() },
						)
					}
					AppSubmenuItem(
						text = tr("menu.file.openFolder"),
						isOpen = activeSubmenu == "folders",
						onOpen = { activeSubmenu = "folders" },
						onDismiss = { if (activeSubmenu == "folders") activeSubmenu = null },
					) {
						folders.forEach { folder ->
							if (folder.startsGroup) AppMenuSeparator()
							AppMenuItem(
								text = folder.label,
								enabled = folder.path != null,
								icon = { tint -> IconFolder(Modifier.size(14.dp), tint) },
								onClick = { activeMenu = null; activeSubmenu = null; folder.path?.let(onOpenFolder) },
							)
						}
					}

					AppMenuSeparator()

					AppMenuItem(
						text = tr("menu.file.exit"),
						shortcut = "Alt+F4",
						onHover = { activeSubmenu = null },
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
					// 1. 标签页 (Tabs). The per-tab canvas options live in the tab strip's ▾ menu.
					AppMenuHeader(tr("menu.view.tabs"))
					AppMenuItem(
						text = tr("tab.new.edit"),
						shortcut = keymap.labelFor(ShortcutAction.NEW_EDIT_TAB),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onNewEditTab()
						},
					)
					AppMenuItem(
						text = tr("tab.new.preview"),
						shortcut = keymap.labelFor(ShortcutAction.NEW_PREVIEW_TAB),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onNewPreviewTab()
						},
					)
					AppMenuItem(
						text = tr("tab.new.history"),
						shortcut = keymap.labelFor(ShortcutAction.OPEN_HISTORY_TAB),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onShowHistory()
						},
					)
					AppMenuSeparator()
					AppMenuItem(
						text = tr("tab.duplicate"),
						shortcut = keymap.labelFor(ShortcutAction.DUPLICATE_TAB),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onDuplicateTab()
						},
					)
					AppMenuItem(
						text = tr("tab.close"),
						shortcut = keymap.labelFor(ShortcutAction.CLOSE_TAB),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onCloseTab()
						},
					)
					AppMenuItem(
						text = tr("tab.next"),
						shortcut = keymap.labelFor(ShortcutAction.NEXT_TAB),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onNextTab()
						},
					)
					AppMenuItem(
						text = tr("tab.prev"),
						shortcut = keymap.labelFor(ShortcutAction.PREV_TAB),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onPrevTab()
						},
					)

					AppMenuSeparator()

					// 3. 界面与缩放调整 (Zoom & Scale)
					AppMenuHeader(tr("menu.view.category.zoom"))
					AppMenuItem(
						text = tr("menu.view.zoomIn"),
						shortcut = keymap.labelFor(ShortcutAction.ZOOM_IN),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onZoomIn()
						},
					)
					AppMenuItem(
						text = tr("menu.view.zoomOut"),
						shortcut = keymap.labelFor(ShortcutAction.ZOOM_OUT),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onZoomOut()
						},
					)
					AppMenuItem(
						text = tr("menu.view.zoomReset"),
						shortcut = keymap.labelFor(ShortcutAction.ZOOM_RESET),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							onResetZoom()
						},
					)
					AppMenuItem(
						text = tr(if (windowMode == WindowMode.FULLSCREEN) "menu.view.exitFullscreen" else "menu.view.fullscreen"),
						shortcut = keymap.labelFor(ShortcutAction.TOGGLE_FULLSCREEN),
						onHover = { activeSubmenu = null },
						onClick = {
							activeMenu = null
							activeSubmenu = null
							AppSettings.toggleFullscreen()
						},
					)

				}
			}

			// 3. Model menu: operations on the open model as a whole. Its id stays "tools" for the tutorials.
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
				modifier = Modifier.tutorialTarget(TutorialTargetId.TOOLS_MENU),
			) {
				val showToolsCoach = tutorialId != null && tutorialStep != null &&
					tutorialHighlightTarget == TutorialTargetId.TOOLS_TEXTURE_UPSCALE
				AppSeamlessDropdownMenu(
					expanded = activeMenu == "tools" || tutorialMenuForce == "tools",
					onDismissRequest = {
						if (tutorialMenuForce != "tools") {
							activeMenu = null
							activeSubmenu = null
						}
					},
					modifier = Modifier.widthIn(min = 180.dp, max = 240.dp),
					tutorialOverlay = if (showToolsCoach) {
						{
							LocalTutorialTargets.current?.let { registry ->
								TutorialOverlay(
									tutorialId = tutorialId,
									step = tutorialStep,
									stepIndex = tutorialStepIndex,
									registry = registry,
									keymap = keymap,
									reviewing = tutorialReviewing,
									isFirstStep = tutorialIsFirstStep,
									onNext = onTutorialNext,
									onPrevious = onTutorialPrevious,
									onSkip = onTutorialSkip,
									onExit = onTutorialExit,
									onFinish = onTutorialExit,
								)
							}
						}
					} else null,
				) {
					AppMenuItem(
						text = tr("menu.file.reanalyze"),
						shortcut = keymap.labelFor(ShortcutAction.REANALYZE),
						enabled = hasInput && !isBusy,
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onReanalyze() },
					)
					AppMenuItem(
						text = tr("cmo3.replace"),
						enabled = !isBusy,
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onReplaceCmo3() },
					)

					AppMenuSeparator()

					AppMenuItem(
						text = tr("menu.tools.meshSplit"),
						enabled = hasInput && !isBusy,
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onMeshSplitScan() },
					)
					AppMenuItem(
						text = tr("menu.tools.textureUpscale"),
						shortcut = keymap.labelFor(ShortcutAction.TEXTURE_UPSCALE),
						enabled = hasInput && !isBusy,
						highlighted = tutorialHighlightTarget == TutorialTargetId.TOOLS_TEXTURE_UPSCALE,
						modifier = Modifier.tutorialTarget(TutorialTargetId.TOOLS_TEXTURE_UPSCALE),
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onShowTextureUpscale() },
					)

					AppMenuSeparator()

					AppMenuItem(
						text = tr("objectOps.findReplace"),
						enabled = hasInput && !isBusy,
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onFindReplace() },
					)
					AppMenuItem(
						text = tr("objectOps.convertIds"),
						enabled = hasInput && !isBusy,
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onConvertIds() },
					)

					AppMenuSeparator()

					AppMenuItem(
						text = tr("menu.tools.updateGeneration"),
						enabled = hasInput && !isBusy && canUpdateGeneration,
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onUpdateGeneration() },
					)
					AppMenuItem(
						text = tr("menu.tools.previewGenerationUpdate"),
						enabled = hasInput && !isBusy && canUpdateGeneration,
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onPreviewGenerationUpdate() },
					)
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
					modifier = Modifier.widthIn(min = 160.dp, max = 240.dp),
				) {
					AppMenuItem(
						text = tr("menu.help.open"),
						shortcut = keymap.labelFor(ShortcutAction.OPEN_HELP),
						onHover = { activeSubmenu = null },
						onClick = { activeMenu = null; activeSubmenu = null; onOpenHelp() },
					)
					AppSubmenuItem(
						text = tr("menu.help.online"),
						isOpen = activeSubmenu == "online",
						onOpen = { activeSubmenu = "online" },
						onDismiss = { if (activeSubmenu == "online") activeSubmenu = null },
					) {
						for ((key, url) in HelpLinks) AppMenuItem(
							text = tr(key),
							onClick = { activeMenu = null; activeSubmenu = null; onOpenUrl(url) },
						)
					}
				}
			}
		}

		// --- CENTER: Draggable Window Area & App Title ---
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				.onGloballyPositioned {
					if (macChrome) {
						macSlotStart = it.positionInParent().x.toInt()
						macSlotWidth = it.size.width
					}
				}
				.then(if (nativeTitleBar != null) Modifier.titleBarHitTest(nativeTitleBar, client = false) else Modifier
				.pointerInput(window) {
					detectTapGestures(onDoubleTap = { AppSettings.toggleMaximized() })
				}
				.pointerInput(window) {
					detectDragGestures(
						onDragStart = {
							initialMouseLocation = MouseInfo.getPointerInfo()?.location
							initialWindowLocation = window?.location
						},
						onDrag = { change, _ ->
							change.consume()
							val mouse = MouseInfo.getPointerInfo()?.location
							val initMouse = initialMouseLocation
							var initWin = initialWindowLocation
							// A full-screen window stays put; a maximized one comes off the edge under the pointer.
							if (mouse != null && initMouse != null && initWin != null && window != null && windowMode != WindowMode.FULLSCREEN) {
								if (windowMode == WindowMode.MAXIMIZED) {
									if (mouse.distance(initMouse) < 4.0) return@detectDragGestures
									initWin = MainWindowModes.restoreForDrag(window, mouse)
									initialWindowLocation = initWin
									initialMouseLocation = mouse
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
				}),
			contentAlignment = Alignment.Center,
		) {
			val macTitleShift = if (macChrome && macBarWidth > 0 && macSlotWidth > 0) {
				macBarWidth / 2 - (macSlotStart + macSlotWidth / 2)
			} else 0
			Text(
				text = if (macChrome) projectTitle.substringAfterLast('/').substringAfterLast('\\') else "PSD2Live — $projectTitle",
				style = if (macChrome) typography.body.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, fontWeight = FontWeight.Medium)
					else typography.caption.copy(fontSize = 11.5.sp),
				color = if (macChrome) colors.textPrimary else colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.widthIn(max = 360.dp)
					.offset { IntOffset(macTitleShift, 0) }
					.padding(horizontal = 8.dp),
			)
		}

		// --- RIGHT: Layout toggles + Window Control Buttons ---
		Row(
			modifier = Modifier.fillMaxHeight(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Row(Modifier.tutorialTarget(TutorialTargetId.LAYOUT_SIDEBAR_TOGGLES)) {
				SidebarSide.entries.forEach { side ->
					val toggle = sidebarToggles.firstOrNull { it.side == side }
					// A button leaving the bar keeps its last look while it slides out.
					val last = remember(side) { arrayOfNulls<SidebarToggle>(1) }
					if (toggle != null) last[0] = toggle
					AnimatedVisibility(
						visible = toggle != null,
						enter = fadeIn(tween(Motion.Fold)) + expandHorizontally(tween(Motion.Expand), expandFrom = Alignment.Start),
						exit = fadeOut(tween(Motion.Fade)) + shrinkHorizontally(tween(Motion.Expand), shrinkTowards = Alignment.Start),
					) {
						val shown = toggle ?: last[0] ?: return@AnimatedVisibility
						TitleBarLayoutToggle(
							tooltip = sidebarTooltip(shown),
							active = shown.active,
							side = shown.side,
							onClick = { onToggleSidebar(shown.side) },
							modifier = if (shown.hostsHierarchy) Modifier.tutorialTarget(TutorialTargetId.LAYOUT_HIERARCHY_TOGGLE) else Modifier,
						)
					}
				}
			}
			TitleBarLanguageButton(
				currentLanguage = currentLanguage,
				expanded = activeMenu == "language",
				onToggle = {
					activeSubmenu = null
					activeMenu = if (activeMenu == "language") null else "language"
				},
				onDismiss = {
					activeMenu = null
					activeSubmenu = null
				},
				onSetLanguage = onSetLanguage,
			)
			TitleBarThemeToggle(
				darkTheme = darkTheme,
				onClick = onToggleTheme,
			)
			TitleBarSettingsButton(
				shown = settingsShown,
				onClick = {
					activeMenu = null
					activeSubmenu = null
					onShowSettings()
				},
			)

			Spacer(modifier = Modifier.width(4.dp))

			if (nativeTitleBar != null || (io.github.psd2live.ui.utils.MacShell.active && window?.isUndecorated == false)) {
				Spacer(Modifier.width(nativeInsets.second))
				return@Row
			}

			// Minimize
			WindowControlButton(
				onClick = { windowState.isMinimized = true },
			) {
				GridIcon(Modifier.size(10.dp), colors.textPrimary) { line(1f, 9f, 17f, 9f) }
			}

			// Maximize / Restore; in full screen, restore leaves it.
			val isMaximized = windowMode != WindowMode.WINDOWED
			WindowControlButton(
				onClick = { AppSettings.toggleMaximized() },
			) {
				GridIcon(Modifier.size(10.dp), colors.textPrimary) {
					if (isMaximized) {
						// Restore: the front window hides the back one's lines.
						behind(rectPath(0.7f, 4.3f, 13f, 13f, 0.8f)) { box(4.3f, 0.7f, 13f, 13f, 0.8f) }
						box(0.7f, 4.3f, 13f, 13f, 0.8f)
					} else {
						box(0.7f, 0.7f, 16.6f, 16.6f, 0.8f)
					}
				}
			}

			// Close
			WindowControlButton(
				isClose = true,
				onClick = onClose,
			) {
				GridIcon(Modifier.size(10.dp), colors.textPrimary) { cross(reach = 8f, width = 1.6f) }
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
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }
	val fill by animateColorAsState(when {
		isOpen -> colors.controlHover
		isHovered -> colors.controlHover.copy(alpha = 0.6f)
		else -> Color.Transparent
	}, tween(Motion.Instant, easing = LinearOutSlowInEasing))

	// The whole height of the bar answers the pointer; the fill sits inside it, as a control's does.
	Box(
		modifier = modifier
			.fillMaxHeight()
			.pressScale()
			.handCursor()
			.onPointerEvent(PointerEventType.Enter) {
				isHovered = true
				onHoverWhenActive()
			}
			.onPointerEvent(PointerEventType.Exit) {
				isHovered = false
			}
			.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
			.padding(vertical = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		Box(
			Modifier.fillMaxHeight().background(fill, RoundedCornerShape(Corners.Control)).padding(horizontal = MenuTitleInset),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = title,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = colors.textPrimary,
			)
		}
		content()
	}
}

/**
 * A control shrinks the moment it is pressed and settles as the pointer comes up. Ease-out, so the first frames
 * move immediately. Dragging off the control cancels it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.pressScale(): Modifier {
	var pressed by remember { mutableStateOf(false) }
	val scale by animateFloatAsState(
		if (pressed) 0.96f else 1f,
		tween(120, easing = LinearOutSlowInEasing),
		label = "press",
	)
	return this
		.onPointerEvent(PointerEventType.Press) { pressed = true }
		.onPointerEvent(PointerEventType.Release) { pressed = false }
		.onPointerEvent(PointerEventType.Exit) { pressed = false }
		.graphicsLayer {
			scaleX = scale
			scaleY = scale
		}
}

/** How far a title bar menu's name sits in from its button's edge; its menu's labels line up with it. */
private val MenuTitleInset = 10.dp

/** How far a menu row's label sits in from the menu's edge: the panel's padding and the row's own. */
private val MenuRowInset = 4.dp + 8.dp

/**
 * A title bar menu, on the panel every menu sits on: it grows in from its button and leaves quicker than it came, its
 * rows glide one highlight between them, and its labels line up under the button's name. Tutorial steps place the
 * menu and its spotlight in the same full-window popup.
 */
@Composable
private fun AppSeamlessDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    tutorialOverlay: (@Composable () -> Unit)? = null,
    /** Lines the menu's right edge up with the anchor's, for buttons at the right end of the bar. */
    alignEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return
    val density = LocalDensity.current
    val gapPx = with(density) { 2.dp.roundToPx() }
    val shiftPx = with(density) { (MenuRowInset - MenuTitleInset).roundToPx() }
    val positionProvider = remember(tutorialOverlay != null, gapPx, shiftPx, alignEnd) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): IntOffset = if (tutorialOverlay != null) IntOffset.Zero
                else IntOffset(
                    if (alignEnd) anchorBounds.right - popupContentSize.width else (anchorBounds.left - shiftPx).coerceAtLeast(0),
                    anchorBounds.bottom + gapPx,
                )
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box(if (tutorialOverlay != null) Modifier.fillMaxSize() else Modifier) {
            Box(
                (if (tutorialOverlay != null) Modifier.offset(y = 32.dp) else Modifier)
                    .menuAppearance(visibility, TransformOrigin(if (alignEnd) 0.88f else 0.12f, 0f), rise = false)
                    .menuPanel()
                    .padding(4.dp),
            ) {
                MenuGlide {
                    Column(modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(1.dp)) { content() }
                }
            }
            // Draw after the menu, in the same popup. No cross-popup z-order or coordinate conversion.
            tutorialOverlay?.invoke()
        }
    }
}

/**
 * A menu dropping from the control it is composed beside (put both in one Box), on the panel and with the motion of
 * every menu: under the control's left edge, or above it when there is no room below, and kept inside the window.
 * The drop-in replacement for Material's DropdownMenu; [offset] shifts it from that spot.
 */
@Composable
fun AppDropdownMenu(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero,
	content: @Composable ColumnScope.() -> Unit,
) {
	val visibility = remember { MutableTransitionState(false) }
	visibility.targetState = expanded
	if (!visibility.currentState && !visibility.targetState) return
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val gapPx = with(density) { 2.dp.roundToPx() }
	val shiftX = with(density) { offset.x.roundToPx() }
	val shiftY = with(density) { offset.y.roundToPx() }
	val positionProvider = remember(gapPx, shiftX, shiftY) {
		object : androidx.compose.ui.window.PopupPositionProvider {
			override fun calculatePosition(
				anchorBounds: androidx.compose.ui.unit.IntRect,
				windowSize: androidx.compose.ui.unit.IntSize,
				layoutDirection: androidx.compose.ui.unit.LayoutDirection,
				popupContentSize: androidx.compose.ui.unit.IntSize,
			): IntOffset {
				val x = (anchorBounds.left + shiftX).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
				val below = anchorBounds.bottom + gapPx + shiftY
				val y = if (below + popupContentSize.height <= windowSize.height) below
					else (anchorBounds.top - gapPx - popupContentSize.height - shiftY).coerceAtLeast(0)
				return IntOffset(x, y)
			}
		}
	}
	Popup(
		popupPositionProvider = positionProvider,
		onDismissRequest = onDismissRequest,
		properties = PopupProperties(focusable = true),
	) {
		androidx.compose.runtime.CompositionLocalProvider(
			LocalDensity provides density,
			LocalToolColors provides colors,
			LocalToolTypography provides typography,
		) {
			Box(
				Modifier
					.menuAppearance(visibility, TransformOrigin(0.12f, 0f), rise = false)
					.menuPanel()
					.padding(4.dp),
			) {
				MenuGlide {
					Column(modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(1.dp), content = content)
				}
			}
		}
	}
}

/**
 * The body of a menu in a popup of its own (a filter, a sort order, a column list): the panel every menu sits on,
 * arriving as menus do, with the rows' gliding highlight. [modifier] sizes the column of rows.
 */
@Composable
fun AppMenuPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
	val visibility = remember { MutableTransitionState(false) }
	visibility.targetState = true
	Box(
		Modifier
			.menuAppearance(visibility, TransformOrigin(0.12f, 0f), rise = false)
			.menuPanel()
			.padding(4.dp),
	) {
		MenuGlide {
			Column(modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(1.dp), content = content)
		}
	}
}

/**
 * A row of an app menu, in the menus' style: a rounded fill on hover (the gliding highlight inside a title bar menu),
 * an optional check, icon and tree rail, the label and its shortcut. In a menu without the gliding highlight the row
 * keeps clear of the menu's edges.
 */
@Composable
fun AppMenuItem(
	text: String,
	shortcut: String? = null,
	isChecked: Boolean? = null,
	enabled: Boolean = true,
	highlighted: Boolean = false,
	icon: (@Composable (tint: Color) -> Unit)? = null,
	indentCheckSpace: Boolean = false,
	/**
	 * Renders the entry as a nested sub-option: a tree rail hangs off the parent's check column and
	 * the whole row shifts right. [isLastChild] closes the rail at the row's vertical center so a
	 * child group does not read as if more entries followed.
	 */
	isChild: Boolean = false,
	isLastChild: Boolean = false,
	modifier: Modifier = Modifier,
	onHover: (() -> Unit)? = null,
	/** A control of its own at the row's end, after the shortcut: a pin, say. */
	trailing: (@Composable () -> Unit)? = null,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val isHovered by interaction.collectIsHoveredAsState()
	LaunchedEffect(isHovered) { if (isHovered && enabled) onHover?.invoke() }
	val glide = LocalMenuGlide.current
	val fill by animateColorAsState(when {
		!enabled -> Color.Transparent
		highlighted -> colors.controlHover.copy(alpha = 0.5f)
		isHovered && !glide -> colors.controlHover.copy(alpha = 0.75f)
		else -> Color.Transparent
	}, tween(Motion.Instant))
	val contentColor = if (enabled) colors.textPrimary else colors.textDisabled

	Row(
		modifier = modifier
			.then(if (glide) Modifier else Modifier.padding(horizontal = 4.dp))
			.glideRow(enabled)
			.fillMaxWidth()
			.height(26.dp)
			.clip(RoundedCornerShape(Corners.Control))
			.background(fill)
			.hoverable(interaction)
			.handCursor(enabled)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			modifier = Modifier.weight(1f, fill = false),
		) {
			if (isChild) {
				Canvas(
					modifier = Modifier
						.width(12.dp)
						.fillMaxHeight(),
				) {
					val railX = 1.dp.toPx()
					val midY = size.height / 2f
					val railColor = if (enabled) colors.border else colors.border.copy(alpha = 0.45f)
					drawLine(
						color = railColor,
						start = Offset(railX, 0f),
						end = Offset(railX, if (isLastChild) midY else size.height),
						strokeWidth = 1.dp.toPx(),
					)
					drawLine(
						color = railColor,
						start = Offset(railX, midY),
						end = Offset(size.width, midY),
						strokeWidth = 1.dp.toPx(),
					)
				}
			}
			if (isChecked != null) {
				Text(
					text = if (isChecked) "✓" else " ",
					style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
					color = if (isChecked) colors.accent else Color.Transparent,
					modifier = Modifier.width(14.dp),
				)
			} else if (indentCheckSpace) {
				Spacer(modifier = Modifier.width(14.dp))
			}
			if (icon != null) {
				Box(
					modifier = Modifier.size(15.dp),
					contentAlignment = Alignment.Center,
				) {
					icon(if (enabled && !isHovered && !highlighted) colors.textMuted else contentColor)
				}
			}
			Text(
				text = text,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = contentColor,
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
		if (trailing != null) {
			Spacer(modifier = Modifier.width(12.dp))
			trailing()
		}
	}
}

/** A row opening a second level beside it, on the same panel and with the same motion as its menu. */
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
	val interaction = remember { MutableInteractionSource() }
	val isHovered by interaction.collectIsHoveredAsState()
	LaunchedEffect(isHovered) { if (isHovered) onOpen() }
	var itemWidthPx by remember { mutableStateOf(0) }
	val glide = LocalMenuGlide.current
	// Open, the row keeps its fill while the pointer works the second level.
	val fill by animateColorAsState(when {
		isOpen -> colors.controlHover.copy(alpha = 0.75f)
		isHovered && !glide -> colors.controlHover.copy(alpha = 0.75f)
		else -> Color.Transparent
	}, tween(Motion.Instant))
	val visibility = remember { MutableTransitionState(false) }
	visibility.targetState = isOpen

	Box(
		modifier = modifier
			.then(if (glide) Modifier else Modifier.padding(horizontal = 4.dp))
			.glideRow(true)
			.fillMaxWidth()
			.onGloballyPositioned { itemWidthPx = it.size.width }
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(26.dp)
				.clip(RoundedCornerShape(Corners.Control))
				.background(fill)
				.hoverable(interaction)
				.handCursor()
				.clickable(interactionSource = interaction, indication = null, onClick = onOpen)
				.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = text,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			IconChevron(
				expanded = false,
				modifier = Modifier.size(9.dp),
				tint = if (isOpen || isHovered) colors.textPrimary else colors.textMuted,
			)
		}

		if (visibility.currentState || visibility.targetState) {
			// The second level's first row lines up with this one: past the panel's padding on both sides.
			val inset = with(density) { 4.dp.roundToPx() }
			Popup(
				alignment = Alignment.TopStart,
				offset = IntOffset(itemWidthPx + inset, -inset),
				onDismissRequest = onDismiss,
				properties = PopupProperties(focusable = false),
			) {
				androidx.compose.runtime.CompositionLocalProvider(
					LocalDensity provides density,
					LocalToolColors provides colors,
					LocalToolTypography provides typography,
				) {
					Box(
						Modifier
							.menuAppearance(visibility, TransformOrigin(0f, 0.1f), rise = false)
							.menuPanel()
							.padding(4.dp),
					) {
						MenuGlide {
							Column(
								Modifier.widthIn(min = 180.dp, max = 260.dp).width(IntrinsicSize.Max),
								verticalArrangement = Arrangement.spacedBy(1.dp),
							) { content() }
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
	Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).height(1.dp).background(colors.divider))
}

@Composable
fun AppMenuHeader(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(
		text = text,
		style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
		color = colors.textMuted.copy(alpha = 0.8f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 2.dp),
	)
}

private fun sidebarTooltip(toggle: SidebarToggle): String {
	val name = tr("layout.sidebar.${toggle.side.name.lowercase()}")
	val action = tr(if (toggle.active) "layout.sidebar.hide" else "layout.sidebar.show", name)
	return if (toggle.modules.isEmpty()) action
	else action + "\n" + toggle.modules.joinToString(tr("layout.sidebar.separator"))
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TitleBarLayoutToggle(
	tooltip: String,
	active: Boolean,
	side: SidebarSide,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }
	// The band grows in from the frame edge as the sidebar opens and shrinks as it closes.
	val open by animateFloatAsState(if (active) 1f else 0f, tween(Motion.Expand), label = "sidebarOpen")
	val hover by animateFloatAsState(if (isHovered) 1f else 0f, tween(Motion.Fade), label = "sidebarHover")

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
		delayMillis = Motion.TooltipDelay,
	) {
		Box(
			modifier = modifier
				.width(30.dp)
				.fillMaxHeight()
				.pressScale()
				.handCursor()
				.onPointerEvent(PointerEventType.Enter) { isHovered = true }
				.onPointerEvent(PointerEventType.Exit) { isHovered = false }
				.clickable(onClick = onClick),
			contentAlignment = Alignment.Center,
		) {
			val frameTint = lerp(colors.textMuted, colors.textPrimary, maxOf(open, hover))
			GridIcon(Modifier.size(14.dp), frameTint) {
				val band = 2.6f + 2.9f * open
				val fill = lerp(
					lerp(colors.textMuted.copy(alpha = 0.35f), colors.textPrimary.copy(alpha = 0.45f), hover),
					colors.textPrimary.copy(alpha = 0.75f),
					open,
				)
				val frame = rectPath(1.2f, 1.2f, 15.6f, 15.6f, 2.2f)
				// Clip to the rounded frame so outer corners follow the outline,
				// while the inner divider stays a hard straight edge.
				scope.clipPath(frame) {
					when (side) {
						SidebarSide.TOP -> fillBox(1.2f, 1.2f, 15.6f, band, 0f, fill)
						SidebarSide.LEFT -> fillBox(1.2f, 1.2f, band, 15.6f, 0f, fill)
						SidebarSide.BOTTOM -> fillBox(1.2f, 16.8f - band, 15.6f, band, 0f, fill)
						SidebarSide.RIGHT -> fillBox(16.8f - band, 1.2f, band, 15.6f, 0f, fill)
					}
				}
				outline(frame)
			}
		}
	}
}

/**
 * Language choice as a globe beside the theme toggle rather than a text menu: it stays recognizable
 * after a switch to a language one cannot read, and the options name themselves in their own language.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TitleBarLanguageButton(
	currentLanguage: AppLanguage,
	expanded: Boolean,
	onToggle: () -> Unit,
	onDismiss: () -> Unit,
	onSetLanguage: (AppLanguage) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }

	TooltipArea(
		tooltip = {
			if (!expanded) Surface(
				color = colors.panelElevated,
				shape = RoundedCornerShape(3.dp),
				border = BorderStroke(1.dp, colors.border),
				elevation = 4.dp,
			) {
				Text(
					text = tr("menu.language"),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textPrimary,
					modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
				)
			}
		},
		delayMillis = Motion.TooltipDelay,
	) {
		Box(
			modifier = Modifier
				.width(30.dp)
				.fillMaxHeight()
				.pressScale()
				.background(if (expanded) colors.selection else Color.Transparent)
				.handCursor()
				.onPointerEvent(PointerEventType.Enter) { isHovered = true }
				.onPointerEvent(PointerEventType.Exit) { isHovered = false }
				.clickable(onClick = onToggle),
			contentAlignment = Alignment.Center,
		) {
			IconLanguage(
				modifier = Modifier.size(14.dp),
				tint = when {
					expanded -> colors.selectionText
					isHovered -> colors.textPrimary
					else -> colors.textMuted
				},
			)
			AppSeamlessDropdownMenu(
				expanded = expanded,
				onDismissRequest = onDismiss,
				modifier = Modifier.widthIn(min = 140.dp, max = 200.dp),
				alignEnd = true,
			) {
				for (language in I18n.supportedLanguages) {
					AppMenuItem(
						text = tr(language.displayNameKey),
						isChecked = language == currentLanguage,
						onClick = {
							onDismiss()
							onSetLanguage(language)
						},
					)
				}
			}
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TitleBarThemeToggle(
	darkTheme: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }
	val tooltip = if (darkTheme) tr("theme.toggle.toLight") else tr("theme.toggle.toDark")

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
		delayMillis = Motion.TooltipDelay,
	) {
		Box(
			modifier = Modifier
				.width(30.dp)
				.fillMaxHeight()
				.pressScale()
				.handCursor()
				.onPointerEvent(PointerEventType.Enter) { isHovered = true }
				.onPointerEvent(PointerEventType.Exit) { isHovered = false }
				.clickable(onClick = onClick),
			contentAlignment = Alignment.Center,
		) {
			// The sidebar toggles' tint when open: a control in plain sight, not a hint that brightens on hover.
			GridIcon(Modifier.size(14.dp), colors.textPrimary.copy(alpha = if (isHovered) 1f else 0.85f)) {
				if (darkTheme) {
					// Sun: switch to the light theme.
					dot(9f, 9f, 3.8f, color.copy(alpha = 0.75f))
					ring(9f, 9f, 3.8f)
					for (i in 0 until 8) {
						val angle = i * PI / 4.0
						val cosA = cos(angle).toFloat()
						val sinA = sin(angle).toFloat()
						line(9f + cosA * 6.2f, 9f + sinA * 6.2f, 9f + cosA * 8.2f, 9f + sinA * 8.2f)
					}
				} else {
					// Moon: switch to the dark theme.
					val moon = circlePath(8.3f, 9f, 6.6f)
					val cut = circlePath(11.1f, 7.8f, 5.8f)
					shape(Path().apply { op(moon, cut, PathOperation.Difference) }, body = color.copy(alpha = 0.75f))
				}
			}
		}
	}
}

/**
 * Tells the native title bar, on every pointer event over this element, whether the pointer is on the app's own
 * controls ([client]) or on the bar itself, which the system then drags, maximizes and snaps. The innermost element
 * decides: the bar marks itself client and its empty middle marks itself not.
 */
private fun Modifier.titleBarHitTest(titleBar: NativeTitleBar, client: Boolean): Modifier =
	pointerInput(titleBar, client) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				if (event.type != PointerEventType.Exit && event.type != PointerEventType.Scroll) titleBar.forceHitTest(client)
			}
		}
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
			.pressScale()
			.handCursor()
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

/** The settings page as a cog beside the language and theme buttons; pressed while the page is shown. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TitleBarSettingsButton(shown: Boolean, onClick: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }
	TooltipArea(
		tooltip = {
			Surface(
				color = colors.panelElevated,
				shape = RoundedCornerShape(3.dp),
				border = BorderStroke(1.dp, colors.border),
				elevation = 4.dp,
			) {
				Text(
					text = tr("menu.settings"),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textPrimary,
					modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
				)
			}
		},
		delayMillis = Motion.TooltipDelay,
	) {
		Box(
			modifier = Modifier
				.width(30.dp)
				.fillMaxHeight()
				.pressScale()
				.background(if (shown) colors.selection else Color.Transparent)
				.handCursor()
				.onPointerEvent(PointerEventType.Enter) { isHovered = true }
				.onPointerEvent(PointerEventType.Exit) { isHovered = false }
				.clickable(onClick = onClick),
			contentAlignment = Alignment.Center,
		) {
			PageIcon(
				AppPage.SETTINGS,
				tint = if (shown) colors.selectionText else colors.textPrimary.copy(alpha = if (isHovered) 1f else 0.85f),
				modifier = Modifier.size(14.dp),
			)
		}
	}
}
