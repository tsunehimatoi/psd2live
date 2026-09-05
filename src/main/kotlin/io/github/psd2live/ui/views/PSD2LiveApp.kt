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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
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
import io.github.psd2live.agent.AgentMcpConnectionInfo
import io.github.psd2live.ui.components.AgentConnectionDialog
import io.github.psd2live.ui.components.AppTitleBar
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.ImageLightboxDialog
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspaceTab
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
	var showAboutDialog by remember { mutableStateOf(false) }
	var showAgentDialog by remember { mutableStateOf(false) }

	viewModel.confirmUnsavedChanges = {
        JOptionPane.showOptionDialog(window, tr("project.unsaved"), tr("project.save"), JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE, null, arrayOf(tr("project.save"), tr("project.discard"), tr("project.cancel")), tr("project.save"))
    }
    LaunchedEffect(state.projectFile, state.projectDirty) { window?.title = "PSD2Live — " + (state.projectFile ?: tr("project.untitled")) + if (state.projectDirty) " *" else "" }
    // Language key tracking for recomposition
	val currentLanguage = state.currentLanguage

	// Window Drop Target for PSD Drag & Drop
	LaunchedEffect(window) {
		window?.dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
			override fun drop(event: DropTargetDropEvent) {
				try {
					event.acceptDrop(DnDConstants.ACTION_COPY)
					@Suppress("UNCHECKED_CAST")
					val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
					files.firstOrNull { it.extension.lowercase() in setOf("psd", "psd2live") }?.let { file ->
                        if (file.extension.equals("psd2live", true)) viewModel.openProject(file.toPath())
                        else viewModel.withSavedChanges { viewModel.setInputPath(file.absolutePath); viewModel.analyze() }
                    }
					event.dropComplete(true)
				} catch (failure: Exception) {
					event.dropComplete(false)
				}
			}
		}, true)
	}

	CompactToolTheme {
		val colors = LocalToolColors.current
		val typography = LocalToolTypography.current

		val isBusy = state.isAnalyzing || state.isGenerating
		val hasInput = state.inputPath.isNotBlank()
		val hasOutput = state.outputPath.isNotBlank()
		val canGenerate = hasInput && (state.exportCmo3 || state.exportMoc3) && !isBusy
		val canOpenOutput = hasOutput && try {
			Files.isDirectory(Path.of(state.outputPath))
		} catch (_: Exception) {
			false
		}

		val triggerExportTo = {
			if (canGenerate) {
				val defaultFolder = state.outputPath.ifBlank {
					try {
						val p = Path.of(state.inputPath)
						val parent = p.toAbsolutePath().parent
						val name = p.fileName.toString().substringBeforeLast('.')
						parent.resolve("$name-psd2live").toString()
					} catch (_: Exception) {
						viewModel.lastExportDirectory ?: ""
					}
				}
				val selected = NativeFilePicker.chooseDirectory(window, defaultFolder)
				if (!selected.isNullOrBlank()) {
					viewModel.setOutputPath(selected)
					viewModel.generateRig(selected)
				}
			}
		}

		val chooseOutputFolder = {
			val selected = NativeFilePicker.chooseDirectory(window, state.outputPath)
			if (!selected.isNullOrBlank()) {
				viewModel.setOutputPath(selected)
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
			val selected = NativeFilePicker.chooseProjectFile(window, state.projectFile)
			if (!selected.isNullOrBlank()) {
				viewModel.openProject(java.nio.file.Path.of(selected))
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

		Box(
			modifier = Modifier
				.fillMaxSize()
				.border(BorderStroke(1.dp, colors.border))
				.onPreviewKeyEvent { event ->
					if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
						when (event.key) {
							Key.O -> {
                                if (event.isShiftPressed) onOpenPsdAction() else onOpenProjectAction()
								true
							}
							Key.S -> { viewModel.requestProjectSave(event.isShiftPressed); true }
                            Key.Z -> { if (event.isShiftPressed) viewModel.redoHistory() else viewModel.undoHistory(); true }
                            Key.Y -> { viewModel.redoHistory(); true }
                            Key.R -> {
								onReanalyzeAction()
								true
							}
							Key.G -> {
								if (event.isShiftPressed) {
									triggerExportTo()
								} else {
									onGenerateAction()
								}
								true
							}
							else -> false
						}
					} else false
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
						canGenerate = canGenerate,
						currentLanguage = currentLanguage,
						onOpenPsd = onOpenPsdAction,
                        onOpenProject = onOpenProjectAction,
                        onSaveProject = { viewModel.requestProjectSave() },
                        onSaveProjectAs = { viewModel.requestProjectSave(true) },
                        projectTitle = (state.projectFile ?: tr("project.untitled")) + if (state.projectDirty) " *" else "",
						onReanalyze = onReanalyzeAction,
						onOpenOutput = { openFolder(state.outputPath) },
						onGenerate = onGenerateAction,
						onExportTo = triggerExportTo,
						onClose = onCloseRequest,
						onSetLanguage = { viewModel.setLanguage(it) },
						onShowAgentConnection = { showAgentDialog = true },
						onShowHistory = { viewModel.setWorkspaceTab(WorkspaceTab.HISTORY) },
						onShowAbout = { showAboutDialog = true },
					)
				}
				// Main Center Area: Split Pane between Workspace (Left) and Inspector (Right)
				BoxWithConstraints(
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth()
						.padding(top = 2.dp),
				) {
					val density = LocalDensity.current
					val totalWidth = maxWidth
					val splitRatio = state.workspaceSplitRatio

					val leftWidth = totalWidth * splitRatio
					val rightWidth = (totalWidth - leftWidth - 4.dp).coerceAtLeast(0.dp)

					var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
					var splitterCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

					Row(
						modifier = Modifier
							.fillMaxSize()
							.onGloballyPositioned { rowCoords = it },
					) {
						// Left: Workspace Area
						WorkspaceView(
							state = state,
							viewModel = viewModel,
							modifier = Modifier.width(leftWidth).fillMaxHeight(),
						)

						// Resizable Splitter Handle
						Box(
							modifier = Modifier
								.width(4.dp)
								.fillMaxHeight()
								.background(colors.divider)
								.onGloballyPositioned { splitterCoords = it }
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
								.pointerInput(Unit) {
									awaitEachGesture {
										val down = awaitFirstDown()
										val grabOffset = down.position.x
										while (true) {
											val event = awaitPointerEvent()
											val change = event.changes.firstOrNull { it.id == down.id } ?: break
											if (!change.pressed) break
											change.consume()
											val row = rowCoords
											val splitter = splitterCoords
											if (row != null && splitter != null && row.isAttached && splitter.isAttached) {
												val rowWidth = row.size.width.toFloat()
												if (rowWidth > 0f) {
													val mouseInRow = row.localPositionOf(splitter, change.position)
													val splitterLeft = mouseInRow.x - grabOffset
													val ratio = (splitterLeft / rowWidth).coerceIn(0.25f, 0.85f)
													viewModel.setWorkspaceSplitRatio(ratio)
												}
											}
										}
									}
								},
						)

						// Right: Inspector Area
						InspectorView(
							state = state,
							viewModel = viewModel,
							onGenerate = { viewModel.generateRig() },
							onChooseOutput = chooseOutputFolder,
							modifier = Modifier.width(rightWidth).fillMaxHeight(),
						)
					}
				}

				// Bottom Status Bar
				StatusBar(state)
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
		}

		// Error Message Modal
		state.errorMessage?.let { error ->
			ModalDialog(
				title = tr("dialog.failure.title"),
				message = error,
				onDismiss = { viewModel.clearErrorMessage() },
				isError = true,
			)
		}

		// About Dialog
		if (showAboutDialog) {
			ModalDialog(
				title = tr("dialog.about.title"),
				message = tr("dialog.about.message"),
				onDismiss = { showAboutDialog = false },
				isError = false,
			)
		}

		if (showAgentDialog) {
			AgentConnectionDialog(
				connection = agentConnectionInfo,
				startupError = agentStartupError,
				onDismiss = { showAgentDialog = false },
			)
		}

		state.lightboxImage?.let { imgBytes ->
			ImageLightboxDialog(
				imageBytes = imgBytes,
				title = state.lightboxTitle,
				onDismiss = { viewModel.closeLightbox() },
			)
		}

		io.github.psd2live.ui.components.ProjectLocationDialog(state, viewModel)

		if (!state.showProjectLocationDialog && state.projectSaveError != null) {
			ModalDialog(
				title = tr("project.saveFailed"),
				message = state.projectSaveError!!,
				onDismiss = { viewModel.clearProjectSaveError() },
				isError = true,
				confirmText = tr("dialog.ok"),
			)
		}
	}
}

@Composable
private fun StatusBar(state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(24.dp)
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider))
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text = state.statusText.ifBlank { tr("status.ready") },
			style = typography.caption.copy(fontSize = 11.sp),
			color = colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)

		if (state.isAnalyzing || state.isGenerating) {
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
	val raw = pathString.trim()
	if (raw.isEmpty()) return
	try {
		val dir = Path.of(raw)
		if (Files.isDirectory(dir)) {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
				Desktop.getDesktop().open(dir.toFile())
			} else {
				val os = System.getProperty("os.name").orEmpty().lowercase()
				when {
					os.contains("win") -> ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start()
					os.contains("mac") -> ProcessBuilder("open", dir.toAbsolutePath().toString()).start()
					else -> ProcessBuilder("xdg-open", dir.toAbsolutePath().toString()).start()
				}
			}
		}
	} catch (_: Exception) {}
}

private fun copyToClipboard(text: String) {
	Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
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
