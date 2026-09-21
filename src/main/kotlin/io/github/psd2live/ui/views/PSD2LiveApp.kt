package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.ProgressIndicatorDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.CanvasStatusTone
import io.github.psd2live.agent.AgentMcpConnectionInfo
import io.github.psd2live.ui.components.AgentConnectionDialog
import io.github.psd2live.ui.components.AppTitleBar
import io.github.psd2live.ui.components.AppMenuHeader
import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.AppMenuSeparator
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.ImageLightboxDialog
import io.github.psd2live.ui.components.ExportDialog
import io.github.psd2live.ui.components.ExportPsdDialog
import io.github.psd2live.ui.components.HelpDialog
import io.github.psd2live.ui.components.HelpTab
import io.github.psd2live.ui.components.TextureUpscaleDialog
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.WorkspaceTabKind
import io.github.psd2live.ui.tutorial.InteractiveTutorialState
import io.github.psd2live.ui.tutorial.LocalTutorialTargets
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialOverlay
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.advance
import io.github.psd2live.ui.tutorial.continueNextTutorial
import io.github.psd2live.ui.tutorial.isComplete
import io.github.psd2live.ui.tutorial.rememberTutorialTargetRegistry
import io.github.psd2live.ui.tutorial.retreat
import io.github.psd2live.ui.tutorial.start
import io.github.psd2live.ui.tutorial.stop
import io.github.psd2live.ui.tutorial.tutorialTarget
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.DropdownMenu
import io.github.psd2live.ui.components.SettingsDialog
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.utils.DesktopUtils
import io.github.psd2live.ui.utils.DesktopDropTarget
import kotlin.math.roundToInt
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane
import androidx.compose.ui.input.key.Key
@Composable
fun FrameWindowScope.PSD2LiveApp(
	viewModel: PSD2LiveViewModel,
	window: ComposeWindow? = null,
	windowState: WindowState? = null,
	agentConnectionInfo: AgentMcpConnectionInfo? = null,
	agentStartupError: String? = null,
	onCloseRequest: () -> Unit = {
		viewModel.close()
		window?.dispose()
	},
) {
	val state by viewModel.state.collectAsState()
	var helpDialogTab by remember { mutableStateOf<HelpTab?>(null) }
	var showAgentDialog by remember { mutableStateOf(false) }
	var showUpscaleDialog by remember { mutableStateOf(false) }
	var tutorial by remember { mutableStateOf(InteractiveTutorialState()) }
	val tutorialTargets = rememberTutorialTargetRegistry()

	fun startInteractiveTutorial(id: TutorialId = TutorialId.BASIC) {
		helpDialogTab = null
		tutorial = InteractiveTutorialState().start(id)
	}

	fun stopInteractiveTutorial() {
		tutorial = tutorial.stop()
	}

	fun advanceTutorial() {
		tutorial = tutorial.advance()
	}

	fun retreatTutorial() {
		tutorial = tutorial.retreat()
	}

	fun continueNextTutorial() {
		tutorial = tutorial.continueNextTutorial()
	}

	fun openTutorialCatalog() {
		stopInteractiveTutorial()
		helpDialogTab = HelpTab.QUICK_START
	}

	viewModel.confirmUnsavedChanges = {
        JOptionPane.showOptionDialog(window, tr("project.unsaved"), tr("project.save"), JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE, null, arrayOf(tr("project.save"), tr("project.discard"), tr("project.cancel")), tr("project.save"))
    }
    LaunchedEffect(state.projectFile, state.projectDirty) { window?.title = "PSD2Live — " + (state.projectFile ?: tr("project.untitled")) + if (state.projectDirty) " *" else "" }
    // Language key tracking for recomposition
	val currentLanguage = state.currentLanguage

	var isDraggingOver by remember { mutableStateOf(false) }

	// Window Drop Target for PSD Drag & Drop and Project Files
	LaunchedEffect(window) {
		if (window != null) {
			DesktopDropTarget.install(
				window = window,
				onDragStateChanged = { isDraggingOver = it },
				onFilesDropped = { files, screenLocation ->
					val rasters = io.github.psd2live.core.LayerImport.transparentRasterFiles(files)
					val hasPreview = viewModel.state.value.previewModel != null
					// Transparent rasters never go through the PSD/project drop path once a preview
					// exists — otherwise Unsupported messaging looks like a PSD hijack.
					if (rasters.isNotEmpty() && hasPreview) {
						val target = screenLocation?.let { point ->
							val origin = window?.locationOnScreen
							val windowX = if (origin != null) point.x - origin.x else point.x
							val windowY = if (origin != null) point.y - origin.y else point.y
							viewModel.hierarchyImportHitTest?.invoke(windowX, windowY)
						} ?: io.github.psd2live.core.HierarchyImportTarget(
							parentDeformerId = null,
							label = tr("canvas.hierarchy.root"),
						)
						viewModel.importLayersFromFiles(rasters, target.parentDeformerId, target.label)
						return@install
					}
					if (rasters.isNotEmpty() && !hasPreview) {
						viewModel.setErrorMessage(tr("error.importLayerBusy"))
						return@install
					}
					when (val action = DesktopDropTarget.resolveDropAction(files)) {
						is DesktopDropTarget.DroppedAction.OpenProject -> {
							viewModel.openProject(action.file.toPath())
						}
						is DesktopDropTarget.DroppedAction.OpenPsd -> {
							if (action.outputDir != null) {
								viewModel.setOutputPath(action.outputDir.absolutePath)
							}
							viewModel.withSavedChanges {
								viewModel.setInputPath(action.file.absolutePath)
								viewModel.analyze()
							}
						}
						is DesktopDropTarget.DroppedAction.SetOutputDir -> {
							viewModel.setOutputPath(action.dir.absolutePath)
							viewModel.setStatusText(tr("status.outputDirSet", action.dir.name))
						}
						is DesktopDropTarget.DroppedAction.Unsupported -> {
							viewModel.setErrorMessage(action.message)
						}
					}
				},
			)
		}
	}

	CompactToolTheme(
		uiScale = state.uiScale,
		fontScale = state.fontScale,
	) {
		val colors = LocalToolColors.current
		val typography = LocalToolTypography.current

		val isBusy = state.isBusy
		val hasInput = state.inputPath.isNotBlank()
		val hasOutput = state.outputPath.isNotBlank()
		val canGenerate = hasInput && (state.exportCmo3 || state.exportMoc3) && !isBusy
		val canOpenOutput = hasOutput && try {
			Files.isDirectory(Path.of(state.outputPath))
		} catch (_: Exception) {
			false
		}

		val chooseOutputFolder = {
			if (!isBusy) {
				val selected = NativeFilePicker.chooseDirectory(window, state.outputPath)
				if (!selected.isNullOrBlank()) {
					viewModel.setOutputPath(selected)
				}
			}
		}

		val onOpenPsdAction = {
			if (!isBusy) {
				val selected = NativeFilePicker.choosePsdFile(window, state.inputPath)
				if (!selected.isNullOrBlank()) {
					viewModel.withSavedChanges { viewModel.setInputPath(selected); viewModel.analyze() }
				}
			}
		}

		val onOpenProjectAction: () -> Unit = {
			if (!isBusy) {
				val selected = NativeFilePicker.chooseProjectFile(window, state.projectFile)
				if (!selected.isNullOrBlank()) {
					viewModel.openProject(java.nio.file.Path.of(selected))
				}
			}
		}
        val onReanalyzeAction = {
			if (hasInput && !isBusy) {
				viewModel.withSavedChanges { viewModel.analyze() }
			}
		}

		val onGenerateAction = {
			if (canGenerate) {
				viewModel.generateRig()
			}
		}

		// A modal owns the keyboard: the root dispatcher stands down so shortcuts cannot fire behind
		// a dialog (Ctrl+O with Help open used to raise a file picker behind it). Returning false
		// rather than true is deliberate — the dialogs keep their own key handling and text input.
		val modalOpen = helpDialogTab != null ||
			showAgentDialog ||
			showUpscaleDialog ||
			state.lightboxImage != null ||
			state.showProjectLocationDialog ||
			state.showExportDialog ||
			state.showExportPsdDialog ||
			state.showSettingsDialog ||
			state.projectSaveError != null ||
			state.errorMessage != null ||
			isDraggingOver ||
			tutorial.active

		// Tutorial step side-effects and auto-advance
		LaunchedEffect(tutorial.active, tutorial.tutorialId, tutorial.stepIndex) {
			if (!tutorial.active) return@LaunchedEffect
			val step = tutorial.step
			if (step.ensureEditTab) {
				val edit = state.workspaceTabs.firstOrNull { it.kind == WorkspaceTabKind.EDIT }
				when {
					edit != null && state.activeWorkspaceTabId != edit.id -> viewModel.setActiveTab(edit.id)
					edit == null -> viewModel.addTab(WorkspaceTabKind.EDIT)
				}
			}
			if (step.ensureHierarchyVisible && state.hierarchyCollapsed) {
				viewModel.setHierarchyView(collapsed = false)
			}
			step.selectDock?.let { dock ->
				viewModel.setInspectorCollapsed(false)
				viewModel.requestSelectDockModule(dock)
			}
			if (step.expandModelSettings) {
				viewModel.setInspectorCollapsed(false)
				viewModel.setModelSettingsExpanded(true)
			}
			step.setHierarchyMode?.let { mode ->
				viewModel.canvasEditor.setHierarchyMode(mode)
			}
		}
		LaunchedEffect(tutorial.active, tutorial.tutorialId, tutorial.stepIndex, state.previewModel, state.activeTabKind, state.showExportDialog, tutorial.titleBarMenuOpen, tutorial.reviewing) {
			if (!tutorial.active) return@LaunchedEffect
			val step = tutorial.step
			if (step.isDone) return@LaunchedEffect
			if (step.isComplete(state, tutorial)) {
				advanceTutorial()
			}
		}

		CompositionLocalProvider(LocalTutorialTargets provides tutorialTargets) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.border(BorderStroke(1.dp, colors.border))
				.onPreviewKeyEvent { event ->
					if (tutorial.active && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
						stopInteractiveTutorial()
						return@onPreviewKeyEvent true
					}
					// Recording a shortcut owns the keyboard outright, so the chord being recorded
					// cannot be swallowed by the very action it is about to replace.
					if (state.keyCapture != null) {
						if (event.type == KeyEventType.KeyDown) viewModel.captureKeyEvent(event)
						return@onPreviewKeyEvent true
					}
					if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
					// Load-bearing: an unmatched key must still reach the canvas handler, the open
					// dialogs and any focused text field.
					val action = state.keymap.match(event, ShortcutScope.APP)
						?: return@onPreviewKeyEvent false
					if (action == ShortcutAction.GENERATE) {
						return@onPreviewKeyEvent when {
							state.showExportDialog && canGenerate -> { onGenerateAction(); true }
							state.showExportDialog -> true
							tutorial.active && hasInput && !isBusy -> { viewModel.openExportDialog(); true }
							modalOpen -> false
							!hasInput || isBusy -> false
							else -> { viewModel.openExportDialog(); true }
						}
					}
					if (tutorial.active) {
						return@onPreviewKeyEvent when (action) {
							ShortcutAction.OPEN_PSD -> { onOpenPsdAction(); true }
							ShortcutAction.OPEN_HELP -> { stopInteractiveTutorial(); true }
							else -> true // consume other app shortcuts during the tour
						}
					}
					if (modalOpen) return@onPreviewKeyEvent false
					when (action) {
						ShortcutAction.OPEN_PROJECT -> { onOpenProjectAction(); true }
						ShortcutAction.OPEN_PSD -> { onOpenPsdAction(); true }
						ShortcutAction.SAVE_PROJECT -> { viewModel.requestProjectSave(false); true }
						ShortcutAction.SAVE_PROJECT_AS -> { viewModel.requestProjectSave(true); true }
						ShortcutAction.REANALYZE -> { onReanalyzeAction(); true }
						// Falls through when there is no source PSD, as the old Shift-gated branch did.
						ShortcutAction.REEXPORT_PSD ->
							if (hasInput && !isBusy) { viewModel.openExportPsdDialog(); true } else false
						ShortcutAction.TEXTURE_UPSCALE -> {
							if (hasInput && !isBusy) showUpscaleDialog = true
							true
						}
						ShortcutAction.UNDO -> {
							val ed = viewModel.canvasEditor
							if (ed.hierarchyMode == EditHierarchyMode.PAINT && ed.canUndoPaint()) {
								ed.undoPaint()
							} else {
								viewModel.undoHistory()
							}
							true
						}
						ShortcutAction.REDO -> {
							val ed = viewModel.canvasEditor
							if (ed.hierarchyMode == EditHierarchyMode.PAINT && ed.canRedoPaint()) {
								ed.redoPaint()
							} else {
								viewModel.redoHistory()
							}
							true
						}
						ShortcutAction.NEW_EDIT_TAB -> { viewModel.addTab(WorkspaceTabKind.EDIT); true }
						ShortcutAction.NEW_PREVIEW_TAB -> { viewModel.addTab(WorkspaceTabKind.PREVIEW); true }
						ShortcutAction.OPEN_HISTORY_TAB -> { viewModel.openHistoryTab(); true }
						ShortcutAction.DUPLICATE_TAB -> { viewModel.duplicateActiveTab(); true }
						ShortcutAction.CLOSE_TAB -> { viewModel.closeTab(state.activeWorkspaceTab.id); true }
						ShortcutAction.NEXT_TAB -> { viewModel.cycleTab(1); true }
						ShortcutAction.PREV_TAB -> { viewModel.cycleTab(-1); true }
						ShortcutAction.ZOOM_IN -> { viewModel.zoomIn(); true }
						ShortcutAction.ZOOM_OUT -> { viewModel.zoomOut(); true }
						ShortcutAction.ZOOM_RESET -> { viewModel.resetZoom(); true }
						ShortcutAction.OPEN_SETTINGS -> { viewModel.openSettingsDialog(); true }
						ShortcutAction.OPEN_HELP -> { openTutorialCatalog(); true }
						else -> {
							// The nine tab-jump actions share one body. A null index means this is a
							// canvas action, which this handler does not own.
							val jump = action.jumpIndex
							if (jump == null) false else { viewModel.activateTabByIndex(jump - 1); true }
						}
					}
				},
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.background(colors.windowBackground),
			) {
				// Custom Window Title Bar & Tool Menu Bar
				if (windowState != null) {
					AppTitleBar(
						window = window,
						windowState = windowState,
						isBusy = isBusy,
						hasInput = hasInput,
						canOpenOutput = canOpenOutput,
						currentLanguage = currentLanguage,
						uiScale = state.uiScale,
						fontScale = state.fontScale,
						keymap = state.keymap,
						hierarchyVisible = !state.hierarchyCollapsed,
						logVisible = state.logPanelExpanded,
						inspectorVisible = !state.inspectorCollapsed,
						onToggleHierarchy = { viewModel.setHierarchyView(collapsed = !state.hierarchyCollapsed) },
						onToggleLog = { viewModel.setLogPanelExpanded(!state.logPanelExpanded) },
						onToggleInspector = { viewModel.setInspectorCollapsed(!state.inspectorCollapsed) },
						onOpenPsd = onOpenPsdAction,
                        onOpenProject = onOpenProjectAction,
                        onSaveProject = { viewModel.requestProjectSave() },
                        onSaveProjectAs = { viewModel.requestProjectSave(true) },
                        projectTitle = (state.projectFile ?: tr("project.untitled")) + if (state.projectDirty) " *" else "",
						onReanalyze = onReanalyzeAction,
						onReexportPsd = { if (hasInput && !isBusy) viewModel.openExportPsdDialog() },
						onOpenOutput = { openFolder(state.outputPath) },
						onShowExport = { if (hasInput && !isBusy) viewModel.openExportDialog() },
						onClose = onCloseRequest,
						onSetLanguage = { viewModel.setLanguage(it) },
						onZoomIn = { viewModel.zoomIn() },
						onZoomOut = { viewModel.zoomOut() },
						onResetZoom = { viewModel.resetZoom() },
						onSetUiScale = { viewModel.setUiScale(it) },
						onSetFontScale = { viewModel.setFontScale(it) },
						onShowSettings = { viewModel.openSettingsDialog() },
						onShowAgentConnection = { showAgentDialog = true },
						onShowTextureUpscale = { showUpscaleDialog = true },
						onShowHistory = { viewModel.openHistoryTab() },
						onNewEditTab = { viewModel.addTab(WorkspaceTabKind.EDIT) },
						onNewPreviewTab = { viewModel.addTab(WorkspaceTabKind.PREVIEW) },
						onDuplicateTab = { viewModel.duplicateActiveTab() },
						onCloseTab = { viewModel.closeTab(state.activeWorkspaceTab.id) },
						onNextTab = { viewModel.cycleTab(1) },
						onPrevTab = { viewModel.cycleTab(-1) },
						onShowAbout = { helpDialogTab = HelpTab.ABOUT },
						onShowHelp = { tab -> helpDialogTab = tab },
						onOpenTutorialCatalog = { openTutorialCatalog() },
						tutorialMenuForce = if (tutorial.active && tutorial.step.forcesFileMenu) "file" else null,
						tutorialHighlightTarget = if (tutorial.active) tutorial.step.targetId else null,
						tutorialId = if (tutorial.active) tutorial.tutorialId else null,
						tutorialStep = if (tutorial.active) tutorial.step else null,
						tutorialStepIndex = tutorial.stepIndex,
						tutorialReviewing = tutorial.reviewing,
						tutorialIsFirstStep = tutorial.isFirstStep,
						onTutorialNext = { advanceTutorial() },
						onTutorialPrevious = { retreatTutorial() },
						onTutorialSkip = { advanceTutorial() },
						onTutorialExit = { stopInteractiveTutorial() },
						onTutorialMenuChanged = { menu ->
							if (tutorial.active) {
								tutorial = tutorial.copy(titleBarMenuOpen = menu)
							}
						},
						onOpenUrl = { url -> DesktopUtils.openBrowser(url) },
					)
				}
				Column(
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth()
						.padding(horizontal = 8.dp),
				) {
					DockWorkspaceView(
						state,
						viewModel,
						Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp),
						window,
						onStartTutorial = { startInteractiveTutorial(TutorialId.BASIC) },
					)
					// Selection / tool hint bar ("已选 N 个对象 / M 个控制点 · …")
					StatusBar(state, viewModel, Modifier.tutorialTarget(TutorialTargetId.STATUS_BAR))
				}
			}

			// Floating Non-blocking Success Toast
			state.successExportMessage?.let { successMsg ->
				SuccessToast(
					message = successMsg,
					onOpenFolder = {
						openFolder(state.outputPath)
						viewModel.clearSuccessExportMessage()
					},
					onDismiss = { viewModel.clearSuccessExportMessage() },
					modifier = Modifier.align(Alignment.BottomEnd),
				)
			}

			if (tutorial.active && !tutorial.step.coachBesideMenu) {
				// Menu steps render their overlay inside the menu popup.
				TutorialOverlay(
					tutorialId = tutorial.tutorialId,
					step = tutorial.step,
					stepIndex = tutorial.stepIndex,
					registry = tutorialTargets,
					keymap = state.keymap,
					reviewing = tutorial.reviewing,
					isFirstStep = tutorial.isFirstStep,
					onNext = { advanceTutorial() },
					onPrevious = { retreatTutorial() },
					onSkip = { advanceTutorial() },
					onExit = { stopInteractiveTutorial() },
					onFinish = { stopInteractiveTutorial() },
					onContinueNext = if (tutorial.isDoneStep && tutorial.nextTutorialId != null) {
						{ continueNextTutorial() }
					} else null,
					onOpenCatalog = if (tutorial.isDoneStep) {
						{ openTutorialCatalog() }
					} else null,
				)
			}
		}
		} // CompositionLocalProvider

		// Error Message Modal
		state.errorMessage?.let { error ->
			ModalDialog(
				title = tr("dialog.failure.title"),
				message = error,
				onDismiss = { viewModel.clearErrorMessage() },
				isError = true,
			)
		}

		// Help & About Dialog
		helpDialogTab?.let { tab ->
			HelpDialog(
				initialTab = tab,
				keymap = state.keymap,
				onDismiss = { helpDialogTab = null },
				onOpenUrl = { url -> DesktopUtils.openBrowser(url) },
				onStartInteractiveTutorial = { id ->
					startInteractiveTutorial(id)
				},
			)
		}

		if (showAgentDialog) {
			AgentConnectionDialog(
				connection = agentConnectionInfo,
				startupError = agentStartupError,
				onDismiss = { showAgentDialog = false },
			)
		}

		if (showUpscaleDialog) {
			TextureUpscaleDialog(
				config = state.textureUpscale,
				isBusy = isBusy,
				isUpscaling = state.isUpscaling,
				progress = state.progress,
				statusText = state.statusText,
				onDismiss = { showUpscaleDialog = false },
				onApply = viewModel::setTextureUpscale,
			)
		}

		state.lightboxImage?.let { imgBytes ->
			ImageLightboxDialog(
				imageBytes = imgBytes,
				title = state.lightboxTitle,
				onDismiss = { viewModel.closeLightbox() },
			)
		}

		io.github.psd2live.ui.components.ProjectLocationDialog(state, viewModel, window)
		ExportDialog(
			state = state,
			viewModel = viewModel,
			onChooseOutput = chooseOutputFolder,
			onDismiss = { viewModel.closeExportDialog() },
		)
		ExportPsdDialog(state, viewModel, window)

		if (!state.showProjectLocationDialog && state.projectSaveError != null) {
			ModalDialog(
				title = tr("project.saveFailed"),
				message = state.projectSaveError!!,
				onDismiss = { viewModel.clearProjectSaveError() },
				isError = true,
				confirmText = tr("dialog.ok"),
			)
		}

		if (state.showSettingsDialog) {
			SettingsDialog(
				uiScale = state.uiScale,
				fontScale = state.fontScale,
				clickToSelectLayer = state.clickToSelectLayer,
				keymap = state.keymap,
				keyPreset = state.keymapPreset,
				keyCapture = state.keyCapture,
				currentLanguage = currentLanguage,
				onUiScaleChange = viewModel::setUiScale,
				onFontScaleChange = viewModel::setFontScale,
				onClickToSelectLayerChange = viewModel::setClickToSelectLayer,
				onLanguageChange = viewModel::setLanguage,
				onKeyCapture = viewModel::beginKeyCapture,
				onKeyRemoveBinding = viewModel::removeKeyBinding,
				onKeyResetBinding = viewModel::resetKeyBinding,
				onKeyPresetChange = viewModel::applyKeymapPreset,
				onResetDefaults = {
					AppSettings.resetToDefaults()
					viewModel.resetZoom()
					viewModel.resetInteractionPrefs()
					viewModel.resetKeymap()
				},
				onDismiss = { viewModel.closeSettingsDialog() },
			)
		}

		if (isDraggingOver) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 0.65f))
					.padding(24.dp)
					.border(2.dp, colors.accent, RoundedCornerShape(12.dp)),
				contentAlignment = Alignment.Center,
			) {
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Text(
						text = tr("drop.overlay.title"),
						style = typography.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
						color = Color.White,
					)
					Text(
						text = tr("drop.overlay.desc"),
						style = typography.body.copy(fontSize = 13.sp),
						color = Color.White.copy(alpha = 0.85f),
					)
				}
			}
		}
	}
}

@Composable
private fun StatusBar(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val editor = viewModel.canvasEditor
	// Read editor snapshot fields so tool/selection changes recompose this bar.
	val editorMessage = if (!state.isBusy && state.activeTabKind == WorkspaceTabKind.EDIT) {
		editor.statusBarMessage(state.selectedLayerId, state.selectedDeformerId)
	} else {
		null
	}
	val statusText = editorMessage?.text ?: state.statusText.ifBlank { tr("status.ready") }
	val statusColor = when (editorMessage?.tone) {
		CanvasStatusTone.ERROR -> colors.error
		CanvasStatusTone.WARNING -> colors.warning
		CanvasStatusTone.NORMAL -> colors.textPrimary
		null -> colors.textPrimary
	}

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(24.dp)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = statusText,
			style = typography.caption.copy(fontSize = 11.sp),
			color = statusColor,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)

		if (state.isBusy) {
			Spacer(Modifier.width(12.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				if (!state.isIndeterminateProgress) {
					Text(
						text = "%3d%%".format((state.progress * 100).toInt()),
						style = typography.monoSmall.copy(fontSize = 10.sp),
						color = colors.accent,
					)
					LinearProgressIndicator(
						progress = state.progress,
						modifier = Modifier.width(140.dp).height(5.dp),
						color = colors.accent,
						backgroundColor = colors.controlBackground,
					)
				} else {
					LinearProgressIndicator(
						modifier = Modifier.width(140.dp).height(5.dp),
						color = colors.accent,
						backgroundColor = colors.controlBackground,
					)
				}
			}
		}

		Spacer(Modifier.width(10.dp))

		// UI scale chip — same AppMenu* language as the View menu / tab-strip dropdowns.
		Box {
			var showZoomMenu by remember { mutableStateOf(false) }
			val pct = (state.uiScale * 100).roundToInt()
			val keymap = state.keymap
			Row(
				modifier = Modifier
					.height(18.dp)
					.background(colors.controlBackground, RoundedCornerShape(3.dp))
					.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
					.clickable { showZoomMenu = true }
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.padding(horizontal = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(
					text = "$pct%",
					style = typography.monoSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
				)
				IconChevron(
					expanded = showZoomMenu,
					modifier = Modifier.size(8.dp),
					tint = colors.textMuted,
				)
			}

			DropdownMenu(
				expanded = showZoomMenu,
				onDismissRequest = { showZoomMenu = false },
				modifier = Modifier
					.background(colors.panelElevated)
					.border(BorderStroke(1.dp, colors.border))
					.widthIn(min = 210.dp, max = 280.dp),
			) {
				AppMenuHeader(tr("menu.view.category.zoom"))
				AppMenuItem(
					text = tr("menu.view.zoomIn"),
					shortcut = keymap.labelFor(ShortcutAction.ZOOM_IN),
					onClick = { showZoomMenu = false; viewModel.zoomIn() },
				)
				AppMenuItem(
					text = tr("menu.view.zoomOut"),
					shortcut = keymap.labelFor(ShortcutAction.ZOOM_OUT),
					onClick = { showZoomMenu = false; viewModel.zoomOut() },
				)
				AppMenuItem(
					text = tr("menu.view.zoomReset"),
					shortcut = keymap.labelFor(ShortcutAction.ZOOM_RESET),
					onClick = { showZoomMenu = false; viewModel.resetZoom() },
				)

				AppMenuSeparator()
				AppMenuHeader(tr("menu.view.uiScale"))
				listOf(1.0f, 1.15f, 1.25f, 1.35f, 1.50f, 1.75f, 2.00f, 2.50f).forEach { scale ->
					val p = (scale * 100).toInt()
					val label = when (scale) {
						1.0f -> "$p% (${tr("settings.scale.standard")})"
						1.25f -> "$p% (2K)"
						1.5f -> "$p% (2K/4K)"
						1.75f, 2.0f -> "$p% (4K)"
						else -> "$p%"
					}
					val isCurrent = kotlin.math.abs(state.uiScale - scale) < 0.03f
					AppMenuItem(
						text = label,
						isChecked = isCurrent,
						onClick = { showZoomMenu = false; viewModel.setUiScale(scale) },
					)
				}

				AppMenuSeparator()
				AppMenuItem(
					text = tr("dialog.settings.title") + "…",
					shortcut = keymap.labelFor(ShortcutAction.OPEN_SETTINGS),
					onClick = { showZoomMenu = false; viewModel.openSettingsDialog() },
				)
			}
		}
	}
}

@Composable
private fun ModalDialog(
	title: String,
	message: String,
	onDismiss: () -> Unit,
	isError: Boolean = false,
	confirmText: String = tr("dialog.ok"),
	extraAction: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x88000000))
			.clickable(onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(420.dp)
				.background(colors.panelBackground, RoundedCornerShape(4.dp))
				.border(BorderStroke(1.dp, if (isError) colors.error else colors.border), RoundedCornerShape(4.dp))
				.clickable(enabled = false) {}
				.padding(14.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					text = title,
					style = typography.title.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
					color = if (isError) colors.error else colors.textPrimary,
				)
				CompactIconButton(
					onClick = onDismiss,
					size = 20.dp,
				) {
					IconClose(tint = colors.textMuted)
				}
			}

			Spacer(Modifier.height(10.dp))

			Text(
				text = message,
				style = typography.body.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
				color = colors.textPrimary,
			)

			Spacer(Modifier.height(14.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (extraAction != null) {
					extraAction()
				}
				CompactButton(
					text = confirmText,
					onClick = onDismiss,
					isPrimary = true,
					height = 24.dp,
				)
			}
		}
	}
}

private fun openFolder(pathString: String) {
	DesktopUtils.openDirectory(pathString)
}

private fun copyToClipboard(text: String) {
	DesktopUtils.copyToClipboard(text)
}

@Composable
private fun SuccessToast(
	message: String,
	onOpenFolder: () -> Unit,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	LaunchedEffect(message) {
		delay(7000)
		onDismiss()
	}

	Box(
		modifier = modifier
			.padding(end = 16.dp, bottom = 32.dp)
			.background(colors.panelElevated, RoundedCornerShape(6.dp))
			.border(BorderStroke(1.dp, colors.accent), RoundedCornerShape(6.dp))
			.padding(horizontal = 12.dp, vertical = 8.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(
				text = message,
				style = typography.body.copy(fontSize = 11.sp),
				color = colors.textPrimary,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 380.dp),
			)
			CompactButton(
				text = tr("dialog.openFolder"),
				onClick = onOpenFolder,
				isPrimary = true,
				height = 22.dp,
			)
			CompactIconButton(
				onClick = onDismiss,
				size = 18.dp,
			) {
				IconClose(tint = colors.textMuted)
			}
		}
	}
}
