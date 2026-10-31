package io.github.psd2live.ui.views

import io.github.psd2live.ui.components.AppDropdownMenu
import io.github.psd2live.ui.theme.Spacing
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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import io.github.psd2live.ui.state.LogLevel
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.CanvasStatusTone
import io.github.psd2live.agent.AgentMcpController
import io.github.psd2live.ui.components.AppDataFolders
import io.github.psd2live.ui.components.LocalAgentMcp
import io.github.psd2live.ui.components.MenuFolder
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
import io.github.psd2live.ui.state.Manual
import io.github.psd2live.ui.state.HELP_CONTENT
import io.github.psd2live.ui.components.TextureUpscaleDialog
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.StartSection
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.buttonBindingOf
import io.github.psd2live.ui.state.wheelBindingOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.tutorial.InteractiveTutorialState
import io.github.psd2live.ui.tutorial.LocalTutorialTargets
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialPath
import io.github.psd2live.ui.tutorial.TutorialOverlay
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.advance
import io.github.psd2live.ui.tutorial.continueNextTutorial
import io.github.psd2live.ui.tutorial.effectiveTargetId
import io.github.psd2live.ui.tutorial.isComplete
import io.github.psd2live.ui.tutorial.prerequisiteMet
import io.github.psd2live.ui.tutorial.rememberTutorialTargetRegistry
import io.github.psd2live.ui.tutorial.retreat
import io.github.psd2live.ui.tutorial.start
import io.github.psd2live.ui.tutorial.stop
import io.github.psd2live.ui.tutorial.tutorialTarget
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.DropdownMenu
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.WindowMode
import io.github.psd2live.ui.state.AppPrompt
import io.github.psd2live.ui.components.ModalDialogFrame
import io.github.psd2live.ui.components.ModalMessage
import io.github.psd2live.ui.components.ModalTone
import io.github.psd2live.ui.utils.DesktopUtils
import io.github.psd2live.ui.utils.DesktopDropTarget
import kotlin.math.roundToInt
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.components.SidebarToggle
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
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor
/**
 * The editor in this window. The canvases find their window's GPU through [LocalAwtWindow]; this window provides it
 * unless an enclosing one already did (the app's main window does), so a window made elsewhere (the development
 * tools' own) draws its canvases on the GPU as the app does instead of quietly in software.
 */
@Composable
fun FrameWindowScope.PSD2LiveApp(
	viewModel: PSD2LiveViewModel,
	window: ComposeWindow? = null,
	windowState: WindowState? = null,
	agentMcp: AgentMcpController? = null,
	onCloseRequest: () -> Unit = {
		viewModel.close()
		window?.dispose()
	},
) {
	androidx.compose.runtime.CompositionLocalProvider(LocalAwtWindow provides (LocalAwtWindow.current ?: this.window)) {
		PSD2LiveAppContent(viewModel, window, windowState, agentMcp, onCloseRequest)
	}
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FrameWindowScope.PSD2LiveAppContent(
	viewModel: PSD2LiveViewModel,
	window: ComposeWindow?,
	windowState: WindowState?,
	agentMcp: AgentMcpController?,
	onCloseRequest: () -> Unit,
) {
	val state by viewModel.uiState
	val baking by viewModel.simulationBaking.collectAsState()
	val download by viewModel.modelDownloadState.collectAsState()
	val projectProgress by viewModel.projectProgress.collectAsState()
	val task = io.github.psd2live.ui.state.taskProgress(state, baking, download, projectProgress)
	var tutorial by remember { mutableStateOf(InteractiveTutorialState()) }
	val tutorialTargets = rememberTutorialTargetRegistry()
	// The performance overlay draws outside Compose, in the theme's colours.
	val overlayColors = LocalToolColors.current
	LaunchedEffect(overlayColors) {
		io.github.psd2live.render.PerformanceOverlay.palette = io.github.psd2live.render.PerformanceOverlay.Palette(
			overlayColors.panelElevated.copy(alpha = 0.92f).toArgb(), overlayColors.border.toArgb(), overlayColors.textPrimary.toArgb(),
			overlayColors.textMuted.toArgb(), overlayColors.accent.toArgb(), overlayColors.warning.toArgb())
	}

	fun startInteractiveTutorial(id: TutorialId = TutorialId.BASIC, path: TutorialPath = TutorialPath.defaultFor(id)) {
		// The steps point into the dock, which the pages cover.
		viewModel.leaveStartPage()
		tutorial = InteractiveTutorialState().start(id, path, hasModel = state.previewModel != null)
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
		tutorial = tutorial.continueNextTutorial(hasModel = state.previewModel != null)
	}

	fun openTutorialCatalog() {
		stopInteractiveTutorial()
		viewModel.showHelpPage(Manual.FIRST_MANUAL)
	}

	viewModel.confirmUnsavedChanges = {
        JOptionPane.showOptionDialog(window, tr("project.unsaved"), tr("project.save"), JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE, null, arrayOf(tr("project.save"), tr("project.discard"), tr("project.cancel")), tr("project.save"))
    }
    val recovery by viewModel.recoveryOffer.collectAsState()
    LaunchedEffect(recovery) {
        val session = recovery ?: return@LaunchedEffect
        val choice = outsideFrame {
            JOptionPane.showOptionDialog(window, tr("recovery.message", session.name), tr("recovery.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, arrayOf(tr("recovery.restore"), tr("recovery.discard")),
                tr("recovery.restore"))
        }
        // Closing the prompt throws nothing away: the marker stays until this session's own unsaved edits replace it.
        when (choice) {
            0 -> viewModel.restoreRecoveredSession(session)
            1 -> viewModel.discardRecoveredSession()
            else -> viewModel.recoveryOffer.value = null
        }
    }
    LaunchedEffect(state.projectFile, state.projectDirty) {
        window?.title = "PSD2Live — " + (state.projectFile ?: tr("project.untitled")) + if (state.projectDirty) " *" else ""
        window?.let { io.github.psd2live.ui.utils.MacShell.setDocumentEdited(it, state.projectDirty) }
    }
    // Language key tracking for recomposition
	val currentLanguage = state.currentLanguage

	var isDraggingOver by remember { mutableStateOf(false) }

	// Which renderers draw this window and the editing canvas: a window composited in software makes every
	// panel and animation slow on its own, so the log says so instead of leaving it to be guessed.
	LaunchedEffect(window) {
		// Canvases draw on the GPU in this window's Skia context when it draws with OpenGL.
		window?.let(io.github.psd2live.render.SkiaGpu::capturePrimary)
		val api = window?.let { runCatching { it.renderApi.name }.getOrNull() } ?: return@LaunchedEffect
		viewModel.addLog(tr("log.renderer.window", api), level = if (api.startsWith("SOFTWARE")) LogLevel.WARNING else LogLevel.INFO, tag = "Render")
		io.github.psd2live.render.GraphicsSettings.appliedEnvironment.takeIf { it.isNotEmpty() }?.let { environment ->
			viewModel.addLog(tr("log.renderer.environment", environment.entries.joinToString(" ") { "${it.key}=${it.value}" }), tag = "Render")
		}
	}
	val canvasRenderer by io.github.psd2live.render.SkiaGpu.status.collectAsState()
	LaunchedEffect(canvasRenderer) {
		when (val status = canvasRenderer) {
			is io.github.psd2live.render.SkiaGpu.Status.Ready ->
				viewModel.addLog(tr("log.renderer.canvasGpu", status.description), tag = "Render")
			is io.github.psd2live.render.SkiaGpu.Status.Unavailable ->
				viewModel.addLog(tr("log.renderer.canvasSoftware", status.reason), level = LogLevel.WARNING, tag = "Render")
			io.github.psd2live.render.SkiaGpu.Status.Starting -> Unit
		}
	}

	// Window Drop Target for PSD Drag & Drop and Project Files
	LaunchedEffect(window) {
		if (window != null) {
			DesktopDropTarget.install(
				window = window,
				onDragStateChanged = { dragging ->
					isDraggingOver = dragging
					if (!dragging) viewModel.fileDrops.end()
				},
				// The target under the pointer decides what a drop does; panels register theirs with the router.
				onDragMoved = { files, at -> viewModel.fileDrops.move(files, at.x.toFloat(), at.y.toFloat()) },
				onFilesDropped = { files, at -> viewModel.dropFiles(files, at?.x?.toFloat(), at?.y?.toFloat()) },
			)
		}
	}

	CompactToolTheme(
		colors = state.toolColors,
		uiScale = state.uiScale,
		fontScale = state.fontScale,
	) {
		val colors = LocalToolColors.current
		val typography = LocalToolTypography.current

		val isBusy = state.isBusy
		val hasInput = state.inputPath.isNotBlank()
		val hasOutput = state.outputPath.isNotBlank()
		val canGenerate = hasInput && (state.exportCmo3 || state.exportMoc3) && !isBusy
		// Folders the File menu opens: the open project's, then the app's own. Checked on each change of path, not on
		// every recomposition, since a missing network share can stall the check.
		val menuFolders = remember(state.projectFile, state.inputPath, state.outputPath, state.currentLanguage) {
			listOf(
				MenuFolder(tr("menu.file.openFolder.project"), existingFolder(state.projectFile?.let { Path.of(it).parent?.toString() })),
				MenuFolder(tr("menu.file.openFolder.source"), existingFolder(state.inputPath.takeIf(String::isNotBlank)?.let { Path.of(it.trim()).parent?.toString() })),
				MenuFolder(tr("menu.file.openFolder.output"), existingFolder(state.outputPath.takeIf { hasOutput })),
				MenuFolder(tr("menu.file.openFolder.workspaces"), existingFolder(AppDataFolders.workspaces.toString()), startsGroup = true),
				MenuFolder(tr("menu.file.openFolder.appData"), existingFolder(AppDataFolders.home.toString())),
			)
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
					viewModel.withSavedChanges { viewModel.setInputPath(selected); viewModel.analyze(discardUnsaved = true) }
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
		val onNewCmo3Action: () -> Unit = {
			if (!isBusy) NativeFilePicker.chooseCmo3File(window, state.inputPath)?.let {
				viewModel.withSavedChanges { viewModel.importCmo3(Path.of(it), io.github.psd2live.core.Cmo3ImportMode.NEW) }
			}
		}
		val onReplaceCmo3Action: () -> Unit = {
			NativeFilePicker.chooseCmo3File(window, state.inputPath)?.let {
				viewModel.importCmo3(Path.of(it), io.github.psd2live.core.Cmo3ImportMode.REPLACE)
			}
		}
        val onReanalyzeAction = {
			if (hasInput && !isBusy) {
				viewModel.withSavedChanges { viewModel.analyze(discardUnsaved = true) }
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
		val modalOpen = state.showTextureUpscaleDialog ||
			state.lightboxImage != null ||
			state.showProjectLocationDialog ||
			state.showExportDialog ||
			state.showExportPsdDialog ||
			state.otherExportTarget != null ||
			state.projectSaveError != null ||
			state.errorMessage != null ||
			state.exportSuccess != null ||
			isDraggingOver ||
			tutorial.active

		// The start screen that follows an import, like a mesh split offer, is a modal inside the workspace;
		// the tour steps aside until it is answered so neither covers the other.
		val tutorialPaused = viewModel.pendingMeshSplit != null || viewModel.pendingMeshSplitBatch != null
		val tutorialShown = tutorial.active && !tutorialPaused

		// Tutorial step side-effects and auto-advance
		LaunchedEffect(
			tutorial.active,
			tutorial.tutorialId,
			tutorial.stepIndex,
			state.selectedLayerId,
			state.selectedDeformerId,
			state.hierarchyCollapsed,
		) {
			if (!tutorial.active) return@LaunchedEffect
			val step = tutorial.step
			step.openWorkspace?.let { preset -> if (state.activeWorkspace.preset != preset) viewModel.openWorkspacePreset(preset) }
			if (step.ensureEditTab) viewModel.ensureEditCanvas()
			if (step.ensureHistoryTab) viewModel.showHistoryModule()
			if (step.ensureHierarchyVisible && state.hierarchyCollapsed) {
				viewModel.setHierarchyView(collapsed = false)
			}
			if ((step.requireSelection || step.requireLayerSelection) && state.hierarchyCollapsed) {
				viewModel.setHierarchyView(collapsed = false)
			}
			step.selectDock?.let { dock ->
				viewModel.setInspectorCollapsed(false)
				viewModel.requestSelectDockModule(dock)
			}
			if (step.expandModelSettings) {
				viewModel.setInspectorCollapsed(false)
			}
			if (step.expandSimulationPresets && !state.simulationPresetsExpanded) {
				viewModel.setSimulationPresetsExpanded(true)
			}
			// Don't force a mode that needs a target until the user finishes selecting.
			if (step.prerequisiteMet(state)) {
				step.setHierarchyMode?.let { mode ->
					viewModel.canvasEditor.setHierarchyMode(mode)
				}
			}
		}
		LaunchedEffect(tutorial.active, tutorial.tutorialId, tutorial.stepIndex, state.previewModel, state.activeCanvas.mode, state.historyPanelShown, state.showExportDialog, tutorial.titleBarMenuOpen, tutorial.reviewing) {
			if (!tutorial.active) return@LaunchedEffect
			val step = tutorial.step
			if (step.isDone) return@LaunchedEffect
			if (step.isComplete(state, tutorial)) {
				advanceTutorial()
			}
		}

		// One application shortcut, from a key or a mouse input. False lets the input through to
		// the canvas, the open dialogs and any focused text field.
		fun runAppAction(action: ShortcutAction): Boolean {
			if (action == ShortcutAction.GENERATE) {
				val glueEditor = viewModel.canvasEditor
				if (!modalOpen && !state.showExportDialog &&
					glueEditor.hierarchyMode == io.github.psd2live.ui.EditHierarchyMode.EDIT &&
					glueEditor.glueMeshPair() != null
				) {
					glueEditor.glueSelectedVertices()
					return true
				}
				return when {
					state.showExportDialog && canGenerate -> { onGenerateAction(); true }
					state.showExportDialog -> true
					tutorial.active && hasInput && !isBusy -> { viewModel.openExportDialog(); true }
					modalOpen -> false
					!hasInput || isBusy -> false
					else -> { viewModel.openExportDialog(); true }
				}
			}
			if (tutorial.active) {
				return when (action) {
					ShortcutAction.OPEN_PSD -> { onOpenPsdAction(); true }
					ShortcutAction.OPEN_PROJECT -> { onOpenProjectAction(); true }
					ShortcutAction.OPEN_HELP -> { stopInteractiveTutorial(); true }
					else -> true // consume other app shortcuts during the tour
				}
			}
			if (modalOpen) return false
			return when (action) {
				ShortcutAction.OPEN_PROJECT -> { onOpenProjectAction(); true }
				ShortcutAction.OPEN_PSD -> { onOpenPsdAction(); true }
				ShortcutAction.SAVE_PROJECT -> { viewModel.requestProjectSave(false); true }
				ShortcutAction.SAVE_PROJECT_AS -> { viewModel.requestProjectSave(true); true }
				ShortcutAction.REANALYZE -> { onReanalyzeAction(); true }
				// Falls through when there is no source PSD, as the old Shift-gated branch did.
				ShortcutAction.REEXPORT_PSD ->
					if (hasInput && !isBusy) { viewModel.openExportPsdDialog(); true } else false
				ShortcutAction.TEXTURE_UPSCALE -> {
					if (hasInput && !isBusy) viewModel.openTextureUpscaleDialog()
					true
				}
				ShortcutAction.UNDO -> {
					val ed = viewModel.canvasEditor
					// An open atlas edit session steps back first, as a paint session does.
					if (state.textureWorkspace.sessionUndo.isNotEmpty()) {
						viewModel.undoTextureSession()
					} else if (ed.hierarchyMode == EditHierarchyMode.PAINT && ed.canUndoPaint()) {
						ed.undoPaint()
					} else {
						viewModel.undoHistory()
					}
					true
				}
				ShortcutAction.REDO -> {
					val ed = viewModel.canvasEditor
					if (state.textureWorkspace.sessionRedo.isNotEmpty()) {
						viewModel.redoTextureSession()
					} else if (ed.hierarchyMode == EditHierarchyMode.PAINT && ed.canRedoPaint()) {
						ed.redoPaint()
					} else {
						viewModel.redoHistory()
					}
					true
				}
				ShortcutAction.NEW_EDIT_TAB -> { viewModel.addWorkspace(); true }
				ShortcutAction.NEW_PREVIEW_TAB -> { viewModel.addCanvas(CanvasMode.PREVIEW); true }
				ShortcutAction.OPEN_HISTORY_TAB -> { viewModel.showHistoryModule(); true }
				ShortcutAction.DUPLICATE_TAB -> { viewModel.duplicateWorkspace(); true }
				ShortcutAction.CLOSE_TAB -> { viewModel.closeWorkspace(state.activeWorkspace.id); true }
				ShortcutAction.NEXT_TAB -> { viewModel.cycleWorkspace(1); true }
				ShortcutAction.PREV_TAB -> { viewModel.cycleWorkspace(-1); true }
				ShortcutAction.ZOOM_IN -> { viewModel.zoomIn(); true }
				ShortcutAction.ZOOM_OUT -> { viewModel.zoomOut(); true }
				ShortcutAction.ZOOM_RESET -> { viewModel.resetZoom(); true }
				ShortcutAction.OPEN_SETTINGS -> { viewModel.togglePage(AppPage.SETTINGS); true }
				ShortcutAction.OPEN_HELP -> { viewModel.togglePage(AppPage.HELP); true }
				ShortcutAction.TOGGLE_FULLSCREEN -> { AppSettings.toggleFullscreen(); true }
				else -> {
					// The nine tab-jump actions share one body. A null index means this is a
					// canvas action, which this handler does not own.
					val jump = action.jumpIndex
					if (jump == null) false else { viewModel.activateWorkspaceByIndex(jump - 1); true }
				}
			}
		}

		val windowMode by AppSettings.windowModeFlow.collectAsState()
		Box(Modifier.fillMaxSize()) {
		CompositionLocalProvider(LocalTutorialTargets provides tutorialTargets, LocalAgentMcp provides agentMcp) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				// The outline marks a window's edge on the desktop; filling the screen, it would draw a line along it.
				.then(if (windowMode == WindowMode.WINDOWED) Modifier.border(BorderStroke(1.dp, colors.border)) else Modifier)
				.onPreviewKeyEvent { event ->
					if (tutorialShown && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
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
					runAppAction(action)
				}
				// A lone Alt released unconsumed puts a Windows window into its menu mode: the system menu takes the
				// keys that follow and the app seems stuck until Alt or Esc is pressed again (after an Alt + drag
				// brush adjustment too). Every view has seen the release by the time it bubbles up here, so taking
				// it now only keeps it from Windows.
				.onKeyEvent { event -> event.type == KeyEventType.KeyUp && (event.key == Key.AltLeft || event.key == Key.AltRight) }
				// The mouse twin of the key handler above: a wheel notch or a button bound to an application
				// shortcut fires it before any view sees the input, and anything unbound passes untouched.
				// While a shortcut is being recorded the recording cell takes the mouse instead.
				.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
					if (state.keyCapture != null) return@onPointerEvent
					val change = event.changes.firstOrNull() ?: return@onPointerEvent
					val action = state.keymap.mouseCommand(wheelBindingOf(change.scrollDelta, event.keyboardModifiers), ShortcutScope.APP)
						?: return@onPointerEvent
					if (runAppAction(action)) change.consume()
				}
				.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
					if (state.keyCapture != null) return@onPointerEvent
					val action = state.keymap.mouseCommand(buttonBindingOf(event.button, event.keyboardModifiers), ShortcutScope.APP)
						?: return@onPointerEvent
					if (runAppAction(action)) event.changes.forEach { it.consume() }
				},
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.background(colors.windowBackground),
			) {
				// Custom Window Title Bar & Tool Menu Bar
				if (windowState != null) {
					val workspace = state.activeWorkspace
					val sidebars = remember(workspace.id, workspace.preset, workspace.layoutJson, workspace.canvases.map { it.id }, workspace.placeModules) {
						workspace.sidebars()
					}
					val sidebarToggles = sidebars.map { (side, modules) ->
						SidebarToggle(
							side = side,
							active = modules.any { it !in workspace.hiddenModules },
							modules = modules.map(::moduleTitle).distinct(),
							hostsHierarchy = "hierarchy" in modules,
						)
					}
					if (io.github.psd2live.ui.utils.MacShell.active && window != null) {
						io.github.psd2live.ui.utils.MacShell.Host(
							window = window,
							isBusy = isBusy,
							hasInput = hasInput,
							recentFiles = state.recentFiles,
							darkTheme = state.darkTheme,
							onStartScreen = { viewModel.showStartPage(StartSection.GENERATE) },
							onOpenProject = onOpenProjectAction,
							onOpenPsd = onOpenPsdAction,
							onOpenRecent = viewModel::openRecentFile,
							onClearRecent = viewModel::clearRecentFiles,
							onSave = { viewModel.requestProjectSave() },
							onSaveAs = { viewModel.requestProjectSave(true) },
							onExport = { if (hasInput && !isBusy) viewModel.openExportDialog() },
							onExportUnity = { if (hasInput && !isBusy) viewModel.openOtherExport("unity") },
							onNewLayout = { viewModel.showStartPage(StartSection.LAYOUT) },
								onUndo = { runAppAction(ShortcutAction.UNDO) },
								onRedo = { runAppAction(ShortcutAction.REDO) },
							onZoomIn = { viewModel.zoomIn() },
							onZoomOut = { viewModel.zoomOut() },
							onResetZoom = { viewModel.resetZoom() },
							onToggleTheme = { viewModel.toggleDarkTheme() },
							onShowSettings = { viewModel.togglePage(AppPage.SETTINGS) },
							onOpenHelp = { viewModel.showPage(AppPage.HELP, HELP_CONTENT) },
							onShowHistory = { viewModel.showHistoryModule() },
						)
					}
					AppTitleBar(
						window = window,
						windowState = windowState,
						isBusy = isBusy,
						hasInput = hasInput,
						folders = menuFolders,
						currentLanguage = currentLanguage,
						darkTheme = state.darkTheme,
						keymap = state.keymap,
						sidebarToggles = sidebarToggles,
						onToggleSidebar = viewModel::toggleSidebar,
						onOpenPsd = onOpenPsdAction,
						onReplaceCmo3 = onReplaceCmo3Action,
                        onNewLayout = { viewModel.showStartPage(StartSection.LAYOUT) },
                        onNewCmo3 = onNewCmo3Action,
                        onOpenProject = onOpenProjectAction,
						recentFiles = state.recentFiles,
						onOpenRecent = viewModel::openRecentFile,
						onClearRecent = viewModel::clearRecentFiles,
                        onSaveProject = { viewModel.requestProjectSave() },
                        onSaveProjectAs = { viewModel.requestProjectSave(true) },
                        projectTitle = (state.projectFile ?: tr("project.untitled")) + if (state.projectDirty) " *" else "",
						onReanalyze = onReanalyzeAction,
						onReexportPsd = { if (hasInput && !isBusy) viewModel.openExportPsdDialog() },
						onOpenFolder = ::openFolder,
						onShowExport = { if (hasInput && !isBusy) viewModel.openExportDialog() },
						onExportAs = { if (hasInput && !isBusy) viewModel.openOtherExport(it) },
						onClose = onCloseRequest,
						onSetLanguage = { viewModel.setLanguage(it) },
						onZoomIn = { viewModel.zoomIn() },
						onZoomOut = { viewModel.zoomOut() },
						onResetZoom = { viewModel.resetZoom() },
						onToggleTheme = { viewModel.toggleDarkTheme() },
						settingsShown = viewModel.activePage == AppPage.SETTINGS,
						onShowSettings = { viewModel.togglePage(AppPage.SETTINGS) },
						onShowTextureUpscale = { viewModel.openTextureUpscaleDialog() },
						onStartScreen = { viewModel.showStartPage(StartSection.GENERATE) },
						onMeshSplitScan = { viewModel.requestMeshSplitScan() },
						onFindReplace = { viewModel.openObjectDialog(PSD2LiveViewModel.ObjectDialog.FIND_REPLACE) },
						onConvertIds = { viewModel.openObjectDialog(PSD2LiveViewModel.ObjectDialog.CONVERT_IDS) },
						canUpdateGeneration = viewModel.canUpdateGeneration(state),
						onUpdateGeneration = { viewModel.updateGeneration() },
						onPreviewGenerationUpdate = { viewModel.previewGenerationUpdate() },
						onShowHistory = { viewModel.showHistoryModule() },
						onNewEditTab = { viewModel.addWorkspace() },
						onNewPreviewTab = { viewModel.addCanvas(CanvasMode.PREVIEW) },
						onDuplicateTab = { viewModel.duplicateWorkspace() },
						onCloseTab = { viewModel.closeWorkspace(state.activeWorkspace.id) },
						onNextTab = { viewModel.cycleWorkspace(1) },
						onPrevTab = { viewModel.cycleWorkspace(-1) },
						onOpenHelp = { viewModel.showPage(AppPage.HELP, HELP_CONTENT) },
						tutorialMenuForce = if (tutorialShown) tutorial.step.forcesMenu else null,
						tutorialHighlightTarget = if (tutorialShown) {
							tutorial.step.effectiveTargetId(state)
						} else null,
						tutorialId = if (tutorialShown) tutorial.tutorialId else null,
						tutorialStep = if (tutorialShown) tutorial.step else null,
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
						.padding(start = Spacing.Margin, end = Spacing.Margin, bottom = Spacing.Gap),
				) {
					DockWorkspaceView(
						state,
						viewModel,
						Modifier.weight(1f).fillMaxWidth(),
						window,
						onOpenTutorialCatalog = { openTutorialCatalog() },
						onStartTutorial = { startInteractiveTutorial(it) },
						onOpenProject = onOpenProjectAction,
						onOpenPsd = onOpenPsdAction,
						onOpenCmo3 = onNewCmo3Action,
						onReplaceCmo3 = onReplaceCmo3Action,
						onStartTutorialPath = { id, path -> startInteractiveTutorial(id, path) },
						onRevealFile = ::openFolder,
						filesDragging = isDraggingOver,
					)
					// Selection / tool hint bar ("已选 N 个对象 / M 个控制点 · …")
					if (task == null) {
						// The bar shows no pose: a dragged slider or a playing preview leaves it (and its tooltip area, whose
						// positioning callback asks for the whole window again) as it was.
						StatusBar(withoutPose(state), viewModel, Modifier.tutorialTarget(TutorialTargetId.STATUS_BAR))
					} else {
						Spacer(Modifier.fillMaxWidth().height(24.dp).tutorialTarget(TutorialTargetId.STATUS_BAR))
					}
				}
			}

			// A material carried from the materials panel follows the pointer until it lands on a canvas.
			MaterialDragGhost(viewModel)
			// The material window: a material to look at and paint, in a window of its own.
			MaterialEditorWindow(state, viewModel)

			if (tutorialShown && !tutorial.step.coachBesideMenu) {
				// Menu steps render their overlay inside the menu popup.
				val step = tutorial.step
				val prereqOk = step.prerequisiteMet(state)
				TutorialOverlay(
					tutorialId = tutorial.tutorialId,
					step = step,
					stepIndex = tutorial.stepIndex,
					registry = tutorialTargets,
					keymap = state.keymap,
					reviewing = tutorial.reviewing,
					isFirstStep = tutorial.isFirstStep,
					prerequisiteMet = prereqOk,
					spotlightTargetId = step.effectiveTargetId(state),
					nextTutorialId = tutorial.nextTutorialId,
					onNext = { if (prereqOk) advanceTutorial() },
					onPrevious = { retreatTutorial() },
					onSkip = { if (prereqOk) advanceTutorial() },
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

		state.exportSuccess?.let { success ->
			io.github.psd2live.ui.components.ExportSuccessDialog(
				success = success,
				onOpenFolder = {
					openFolder(success.folder)
					viewModel.clearExportSuccess()
				},
				onDismiss = { viewModel.clearExportSuccess() },
				onDontShowAgain = {
					viewModel.setPromptEnabled(AppPrompt.EXPORT_SUCCESS, false)
					viewModel.clearExportSuccess()
				},
			)
		}

		if (state.showTextureUpscaleDialog) {
			TextureUpscaleDialog(
				config = state.textureUpscale,
				isBusy = isBusy,
				isUpscaling = state.isUpscaling,
				onDownloadStateChange = viewModel::reportModelDownload,
				onDismiss = { viewModel.closeTextureUpscaleDialog() },
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
		io.github.psd2live.ui.components.OtherFormatExportDialog(state, viewModel, onChooseOutput = chooseOutputFolder)

		if (!state.showProjectLocationDialog && state.projectSaveError != null) {
			ModalDialog(
				title = tr("project.saveFailed"),
				message = state.projectSaveError!!,
				onDismiss = { viewModel.clearProjectSaveError() },
				isError = true,
				confirmText = tr("dialog.ok"),
			)
		}

		// Something carried - files or a material: the pointer says what a drop there does; the start page shows where files land itself.
		if (!viewModel.startPageShown) FileDragLabel(viewModel)
		// Keep the shared task indicator visible above dialog scrims.
		if (task != null) {
			StatusBar(state, viewModel, Modifier.align(Alignment.BottomCenter).background(colors.windowBackground), task)
		}

		} // Dialogs and shared status bar
	}
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StatusBar(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
	task: io.github.psd2live.ui.state.TaskProgress? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val editor = viewModel.canvasEditor
	// Read editor snapshot fields so tool/selection changes recompose this bar.
	val editorMessage = if (task == null && state.activeCanvas.mode == CanvasMode.EDIT) {
		editor.statusBarMessage(state.selectedLayerId, state.selectedDeformerId)
	} else {
		null
	}
	val statusText = task?.text ?: editorMessage?.text ?: state.statusText.ifBlank { tr("status.ready") }
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
		// One line here; the whole message (a failed edit's cause can run long) on hover.
		androidx.compose.foundation.TooltipArea(
			tooltip = { Box(Modifier.widthIn(max = 720.dp)) { ParameterTooltip(statusText) } },
			modifier = Modifier.weight(1f),
			delayMillis = Motion.TooltipDelay,
		) {
			Text(
				text = statusText,
				style = typography.caption.copy(fontSize = 11.sp),
				color = statusColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		if (task != null) {
			Spacer(Modifier.width(12.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				if (task.fraction != null) {
					Text(
						text = "%3d%%".format((task.fraction * 100).toInt()),
						style = typography.monoSmall.copy(fontSize = 10.sp),
						color = colors.accent,
					)
					LinearProgressIndicator(
						progress = task.fraction,
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
				if (task.canCancelBake) {
					CompactIconButton(onClick = viewModel::cancelSimulationBake, tooltip = tr("sim.cancelBake"), size = 18.dp) {
						IconClose(modifier = Modifier.size(9.dp), tint = colors.textPrimary)
					}
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
					.handCursor()
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

			AppDropdownMenu(
				expanded = showZoomMenu,
				onDismissRequest = { showZoomMenu = false },
				modifier = Modifier.widthIn(min = 210.dp, max = 280.dp),
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
					onClick = { showZoomMenu = false; viewModel.openSettings() },
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
) {
	ModalDialogFrame(
		title = title,
		onDismiss = onDismiss,
		width = 440.dp,
		tone = if (isError) ModalTone.ERROR else ModalTone.NONE,
		onConfirm = onDismiss,
		footer = { CompactButton(text = confirmText, onClick = onDismiss, isPrimary = true) },
	) {
		ModalMessage(message)
	}
}

/** [path] when it names a folder that exists, otherwise null. */
private fun existingFolder(path: String?): String? = path?.trim()?.takeIf { it.isNotEmpty() && runCatching { Files.isDirectory(Path.of(it)) }.getOrDefault(false) }

private fun openFolder(pathString: String) {
	DesktopUtils.openDirectory(pathString)
}

private fun copyToClipboard(text: String) {
	DesktopUtils.copyToClipboard(text)
}

/**
 * Runs [prompt] (a modal dialog) as its own event, after the frame this coroutine resumed in. Effects run inside
 * Skiko's render of a frame, and on Linux it holds the window's drawing-surface lock (the AWT lock) there: a modal
 * loop entered there leaves the dialog blank and the whole application frozen.
 */
private suspend fun <T> outsideFrame(prompt: () -> T): T = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    javax.swing.SwingUtilities.invokeLater {
        if (!continuation.isActive) return@invokeLater
        val result = runCatching(prompt)
        continuation.resumeWith(result)
    }
}
