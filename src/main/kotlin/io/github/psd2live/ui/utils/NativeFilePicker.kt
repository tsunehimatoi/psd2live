package io.github.psd2live.ui.utils

import androidx.compose.ui.awt.ComposeWindow
import io.github.psd2live.i18n.tr
import java.awt.FileDialog
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.UIManager

object NativeFilePicker {

	/**
	 * Opens the modern native OS file picker for selecting a PSD file.
	 */
	fun choosePsdFile(window: ComposeWindow? = null, initialPath: String? = null): String? {
		val title = tr("dialog.choosePsd")
		val isWindows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

		// 1. Try Java AWT FileDialog (native OS Open File Dialog)
		try {
			val dialog = FileDialog(window, title, FileDialog.LOAD).apply {
				setFilenameFilter { _, name -> name.endsWith(".psd", ignoreCase = true) }
				file = "*.psd"
				if (!initialPath.isNullOrBlank()) {
					val f = File(initialPath)
					if (f.exists()) directory = if (f.isDirectory) f.absolutePath else f.parent
				}
				isVisible = true
			}
			val dir = dialog.directory
			val selectedFile = dialog.file
			if (!dir.isNullOrBlank() && !selectedFile.isNullOrBlank()) {
				val full = File(dir, selectedFile).toPath().toAbsolutePath().normalize().toString()
				if (full.endsWith(".psd", ignoreCase = true)) {
					return full
				}
			}
		} catch (_: Throwable) {}

		// 2. Fallback to System Look & Feel JFileChooser
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
			val chooser = JFileChooser().apply {
				dialogTitle = title
				fileFilter = javax.swing.filechooser.FileNameExtensionFilter(tr("dialog.psdFilter"), "psd")
				if (!initialPath.isNullOrBlank()) {
					val f = File(initialPath)
					if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
				}
			}
			if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
				return chooser.selectedFile.toPath().toAbsolutePath().normalize().toString()
			}
		} catch (_: Throwable) {}

		return null
	}

	/**
	 * Opens the native OS file picker for selecting an existing .psd2live project file.
	 */
	fun chooseProjectFile(window: ComposeWindow? = null, initialPath: String? = null): String? {
		val title = tr("project.open")
		try {
			val dialog = FileDialog(window, title, FileDialog.LOAD).apply {
				setFilenameFilter { _, name -> name.endsWith(".psd2live", ignoreCase = true) }
				file = "*.psd2live"
				if (!initialPath.isNullOrBlank()) {
					val f = File(initialPath)
					if (f.exists()) directory = if (f.isDirectory) f.absolutePath else f.parent
				}
				isVisible = true
			}
			val dir = dialog.directory
			val selectedFile = dialog.file
			if (!dir.isNullOrBlank() && !selectedFile.isNullOrBlank()) {
				val full = File(dir, selectedFile).toPath().toAbsolutePath().normalize().toString()
				if (full.endsWith(".psd2live", ignoreCase = true)) {
					return full
				}
			}
		} catch (_: Throwable) {}

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
			val chooser = JFileChooser().apply {
				dialogTitle = title
				fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PSD2Live (*.psd2live)", "psd2live")
				if (!initialPath.isNullOrBlank()) {
					val f = File(initialPath)
					if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
				}
			}
			if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
				return chooser.selectedFile.toPath().toAbsolutePath().normalize().toString()
			}
		} catch (_: Throwable) {}
		return null
	}

	/**
	 * Opens the native OS file picker for saving a single .psd2live project file.
	 */
	fun chooseSaveProjectFile(window: ComposeWindow? = null, defaultName: String? = null, initialDir: String? = null): String? {
		val title = tr("project.saveAs")
		val defaultFileName = if (defaultName.isNullOrBlank()) "project.psd2live" else if (defaultName.endsWith(".psd2live", ignoreCase = true)) defaultName else "$defaultName.psd2live"

		try {
			val dialog = FileDialog(window, title, FileDialog.SAVE).apply {
				setFilenameFilter { _, name -> name.endsWith(".psd2live", ignoreCase = true) }
				file = defaultFileName
				if (!initialDir.isNullOrBlank()) {
					val f = File(initialDir)
					if (f.exists()) directory = if (f.isDirectory) f.absolutePath else f.parent
				}
				isVisible = true
			}
			val dir = dialog.directory
			val selectedFile = dialog.file
			if (!dir.isNullOrBlank() && !selectedFile.isNullOrBlank()) {
				val name = if (selectedFile.endsWith(".psd2live", ignoreCase = true)) selectedFile else "$selectedFile.psd2live"
				return File(dir, name).toPath().toAbsolutePath().normalize().toString()
			}
		} catch (_: Throwable) {}

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
			val chooser = JFileChooser().apply {
				dialogTitle = title
				fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PSD2Live (*.psd2live)", "psd2live")
				selectedFile = File(defaultFileName)
				if (!initialDir.isNullOrBlank()) {
					val f = File(initialDir)
					if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
				}
			}
			if (chooser.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) {
				val f = chooser.selectedFile
				val name = if (f.name.endsWith(".psd2live", ignoreCase = true)) f.name else "${f.name}.psd2live"
				return File(f.parentFile ?: File("."), name).toPath().toAbsolutePath().normalize().toString()
			}
		} catch (_: Throwable) {}
		return null
	}

	/**
	 * Opens the modern native OS directory picker.
	 */
	fun chooseDirectory(window: ComposeWindow? = null, initialPath: String? = null): String? {
		val title = tr("dialog.chooseOutput")
		val isWindows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

		val initialFile = if (!initialPath.isNullOrBlank()) {
			val f = File(initialPath)
			if (f.exists() && f.isDirectory) f
			else if (f.parentFile?.exists() == true && f.parentFile.isDirectory) f.parentFile
			else null
		} else null
		val initialDir = initialFile?.absolutePath.orEmpty()

		// On Windows, try native PowerShell FolderPicker for modern Windows 10/11 folder selection
		if (isWindows) {
			try {
				val script = buildString {
					append("Add-Type -AssemblyName System.Windows.Forms; ")
					append("\$dialog = New-Object System.Windows.Forms.FolderBrowserDialog; ")
					append("\$dialog.Description = '${title.replace("'", "''")}'; ")
					append("\$dialog.UseDescriptionForTitle = \$true; ")
					if (initialDir.isNotBlank()) {
						append("\$dialog.SelectedPath = '${initialDir.replace("'", "''")}'; ")
					}
					append("if (\$dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output \$dialog.SelectedPath }")
				}

				val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
					.redirectErrorStream(true)
					.start()

				val output = process.inputStream.bufferedReader().readText().trim()
				process.waitFor()
				if (output.isNotBlank() && File(output).isDirectory) {
					return Path.of(output).toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}
		}

		// Fallback to System Look & Feel JFileChooser
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
			val chooser = JFileChooser().apply {
				dialogTitle = title
				fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
				if (initialFile != null) {
					currentDirectory = initialFile
				}
			}
			if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
				return chooser.selectedFile.toPath().toAbsolutePath().normalize().toString()
			}
		} catch (_: Throwable) {}

		return null
	}
}

