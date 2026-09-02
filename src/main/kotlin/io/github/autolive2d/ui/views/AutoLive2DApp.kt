package io.github.autolive2d.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.ProgressIndicatorDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import io.github.autolive2d.i18n.AppLanguage
import io.github.autolive2d.i18n.I18n
import io.github.autolive2d.i18n.tr
import io.github.autolive2d.ui.components.CompactButton
import io.github.autolive2d.ui.components.CompactIconButton
import io.github.autolive2d.ui.components.IconClose
import io.github.autolive2d.ui.state.AutoLive2DState
import io.github.autolive2d.ui.state.AutoLive2DViewModel
import io.github.autolive2d.ui.theme.CompactToolTheme
import io.github.autolive2d.ui.theme.LocalToolColors
import io.github.autolive2d.ui.theme.LocalToolTypography
import io.github.autolive2d.ui.utils.NativeFilePicker
import java.awt.Cursor
import java.awt.Desktop
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane

@Composable
fun FrameWindowScope.AutoLive2DApp(
	viewModel: AutoLive2DViewModel,
	window: ComposeWindow? = null,
) {
	val state by viewModel.state.collectAsState()
	var showAboutDialog by remember { mutableStateOf(false) }

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
					files.firstOrNull { it.extension.equals("psd", true) }?.let {
						viewModel.setInputPath(it.absolutePath)
						viewModel.analyze()
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
		val canGenerate = hasInput && hasOutput && (state.exportCmo3 || state.exportMoc3) && !isBusy
		val canOpenOutput = hasOutput && try {
			Files.isDirectory(Path.of(state.outputPath))
		} catch (_: Exception) {
			false
		}

		// Menu Bar
		MenuBar {
			Menu(tr("menu.file")) {
				Item(
					text = tr("menu.file.openPsd"),
					onClick = {
						val selected = NativeFilePicker.choosePsdFile(window, state.inputPath)
						if (!selected.isNullOrBlank()) {
							viewModel.setInputPath(selected)
							viewModel.analyze()
						}
					},
					enabled = !isBusy,
					shortcut = KeyShortcut(Key.O, ctrl = true),
				)
				Item(
					text = tr("menu.file.reanalyze"),
					onClick = { viewModel.analyze() },
					enabled = hasInput && !isBusy,
					shortcut = KeyShortcut(Key.R, ctrl = true),
				)
				Separator()
				Item(
					text = tr("menu.file.chooseOutput"),
					onClick = {
						val selected = NativeFilePicker.chooseDirectory(window, state.outputPath)
						if (!selected.isNullOrBlank()) {
							viewModel.setOutputPath(selected)
						}
					},
					enabled = !isBusy,
				)
				Item(
					text = tr("menu.file.openOutput"),
					onClick = {
						val raw = state.outputPath.trim()
						if (raw.isNotEmpty()) {
							val dir = Path.of(raw)
							if (Files.isDirectory(dir) && Desktop.isDesktopSupported()) {
								Desktop.getDesktop().open(dir.toFile())
							}
						}
					},
					enabled = canOpenOutput,
				)
				Separator()
				Item(
					text = tr("menu.file.generate"),
					onClick = { viewModel.generateRig() },
					enabled = canGenerate,
					shortcut = KeyShortcut(Key.G, ctrl = true),
				)
				Separator()
				Item(
					text = tr("menu.file.exit"),
					onClick = {
						viewModel.close()
						window?.dispose()
					},
				)
			}
			Menu(tr("menu.language")) {
				for (lang in I18n.supportedLanguages) {
					Item(
						text = tr(lang.displayNameKey),
						onClick = { viewModel.setLanguage(lang) },
					)
				}
			}
			Menu(tr("menu.help")) {
				Item(
					text = tr("menu.about"),
					onClick = { showAboutDialog = true },
				)
			}
		}

		Column(
			modifier = Modifier
				.fillMaxSize()
				.background(colors.windowBackground),
		) {
			// Main Center Area: Split Pane between Workspace (Left) and Inspector (Right)
			BoxWithConstraints(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.padding(top = 2.dp),
			) {
				val density = LocalDensity.current
				val totalWidth = maxWidth
				var splitRatio by remember { mutableStateOf(0.60f) }

				val leftWidth = totalWidth * splitRatio
				val rightWidth = (totalWidth - leftWidth - 4.dp).coerceAtLeast(0.dp)

				Row(modifier = Modifier.fillMaxSize()) {
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
							.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
							.draggable(
								orientation = Orientation.Horizontal,
								state = rememberDraggableState { delta ->
									val totalWidthPx = with(density) { totalWidth.toPx() }
									if (totalWidthPx > 0f) {
										val deltaRatio = delta / totalWidthPx
										splitRatio = (splitRatio + deltaRatio).coerceIn(0.25f, 0.85f)
									}
								},
							),
					)

					// Right: Inspector Area
					InspectorView(
						state = state,
						viewModel = viewModel,
						modifier = Modifier.width(rightWidth).fillMaxHeight(),
					)
				}
			}

			// Bottom Status Bar
			StatusBar(state)
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

		// Success Message Modal
		state.successExportMessage?.let { successMsg ->
			ModalDialog(
				title = "AutoLive2D",
				message = successMsg,
				onDismiss = { viewModel.clearSuccessExportMessage() },
				isError = false,
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
	}
}

@Composable
private fun StatusBar(state: AutoLive2DState) {
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
				horizontalArrangement = Arrangement.End,
			) {
				CompactButton(
					text = "OK",
					onClick = onDismiss,
					isPrimary = true,
					height = 24.dp,
				)
			}
		}
	}
}
