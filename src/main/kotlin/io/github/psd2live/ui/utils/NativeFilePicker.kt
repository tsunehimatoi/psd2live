package io.github.psd2live.ui.utils

import io.github.psd2live.i18n.tr
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.UIManager

object NativeFilePicker {

	private val isPicking = AtomicBoolean(false)

	/**
	 * Resets the active picking state, primarily for testing purposes.
	 */
	internal fun resetPickingState() {
		isPicking.set(false)
	}

	private fun createFileDialog(window: Window?, title: String, mode: Int): FileDialog {
		return when (window) {
			is Dialog -> FileDialog(window, title, mode)
			is Frame -> FileDialog(window, title, mode)
			else -> FileDialog(null as Frame?, title, mode)
		}
	}

	/**
	 * Opens the modern native OS file picker for selecting a PSD file.
	 */
	fun choosePsdFile(window: Window? = null, initialPath: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			return null
		}
		try {
			val title = tr("dialog.choosePsd")

			// 1. Try Java AWT FileDialog (native OS Open File Dialog)
			try {
				val dialog = createFileDialog(window, title, FileDialog.LOAD).apply {
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
				// Dialog completed normally and user cancelled
				return null
			} catch (_: Throwable) {}

			// 2. Fallback to System Look & Feel JFileChooser only if native picker threw exception
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
		} finally {
			isPicking.set(false)
		}
	}

	/**
	 * Opens the native OS file picker for selecting an existing .psd2live project file.
	 */
	fun chooseProjectFile(window: Window? = null, initialPath: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			return null
		}
		try {
			val title = tr("project.open")
			try {
				val dialog = createFileDialog(window, title, FileDialog.LOAD).apply {
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
				// Dialog completed normally and user cancelled
				return null
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
		} finally {
			isPicking.set(false)
		}
	}

	/**
	 * Opens the native OS file picker for saving a single .psd2live project file.
	 */
	fun chooseSaveProjectFile(window: Window? = null, defaultName: String? = null, initialDir: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			return null
		}
		try {
			val title = tr("project.saveAs")
			val defaultFileName = if (defaultName.isNullOrBlank()) "project.psd2live" else if (defaultName.endsWith(".psd2live", ignoreCase = true)) defaultName else "$defaultName.psd2live"

			try {
				val dialog = createFileDialog(window, title, FileDialog.SAVE).apply {
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
				// Dialog completed normally and user cancelled
				return null
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
		} finally {
			isPicking.set(false)
		}
	}

	/**
	 * Opens the native OS file picker for saving a PSD (.psd) file.
	 */
	fun chooseSavePsdFile(window: Window? = null, defaultName: String? = null, initialDir: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			return null
		}
		try {
			val title = tr("menu.file.reexportPsd")
			val defaultFileName = if (defaultName.isNullOrBlank()) "export.psd" else if (defaultName.endsWith(".psd", ignoreCase = true)) defaultName else "$defaultName.psd"

			try {
				val dialog = createFileDialog(window, title, FileDialog.SAVE).apply {
					setFilenameFilter { _, name -> name.endsWith(".psd", ignoreCase = true) }
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
					val name = if (selectedFile.endsWith(".psd", ignoreCase = true)) selectedFile else "$selectedFile.psd"
					return File(dir, name).toPath().toAbsolutePath().normalize().toString()
				}
				// Dialog completed normally and user cancelled
				return null
			} catch (_: Throwable) {}

			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
				val chooser = JFileChooser().apply {
					dialogTitle = title
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Photoshop Document (*.psd)", "psd")
					selectedFile = File(defaultFileName)
					if (!initialDir.isNullOrBlank()) {
						val f = File(initialDir)
						if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
					}
				}
				if (chooser.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) {
					val f = chooser.selectedFile
					val name = if (f.name.endsWith(".psd", ignoreCase = true)) f.name else "${f.name}.psd"
					return File(f.parentFile ?: File("."), name).toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}
			return null
		} finally {
			isPicking.set(false)
		}
	}

	/**
	 * Opens the modern native OS directory picker.
	 */
	fun chooseDirectory(window: Window? = null, initialPath: String? = null, title: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			return null
		}
		try {
			val dialogTitle = title ?: tr("dialog.chooseOutput")
			val os = System.getProperty("os.name").orEmpty().lowercase()
			val isWindows = os.contains("win")
			val isMac = os.contains("mac")

			val initialFile = if (!initialPath.isNullOrBlank()) {
				val f = File(initialPath)
				if (f.exists() && f.isDirectory) f
				else if (f.parentFile?.exists() == true && f.parentFile.isDirectory) f.parentFile
				else null
			} else null
			val initialDir = initialFile?.absolutePath.orEmpty()

			// 1. On macOS, try native AWT FileDialog with directory mode
			if (isMac) {
				try {
					System.setProperty("apple.awt.fileDialogForDirectories", "true")
					val dialog = createFileDialog(window, dialogTitle, FileDialog.LOAD).apply {
						if (initialDir.isNotBlank()) directory = initialDir
						isVisible = true
					}
					System.setProperty("apple.awt.fileDialogForDirectories", "false")
					val dir = dialog.directory
					val file = dialog.file
					if (!dir.isNullOrBlank() && !file.isNullOrBlank()) {
						val full = File(dir, file)
						if (full.isDirectory) {
							return full.toPath().toAbsolutePath().normalize().toString()
						}
					}
					// User cancelled native dialog on macOS
					return null
				} catch (_: Throwable) {
					System.setProperty("apple.awt.fileDialogForDirectories", "false")
				}
			}

			// 2. System Look & Feel directory chooser for Windows and Linux
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
				val chooser = JFileChooser().apply {
					this.dialogTitle = dialogTitle
					fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
					if (initialFile != null) {
						currentDirectory = initialFile
						selectedFile = initialFile
					}
				}
				if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
					val selected = chooser.selectedFile ?: return null
					return selected.toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}

			return null
		} finally {
			isPicking.set(false)
		}
	}
}


