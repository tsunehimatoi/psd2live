package io.github.psd2live.ui.utils

import io.github.psd2live.i18n.tr
import java.awt.Component
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.UIManager

object NativeFilePicker {

	private val isPicking = AtomicBoolean(false)

	/** The picker window on screen, while one is. */
	@Volatile private var openPicker: Window? = null

	/** Whether a picker is open; a second request raises it instead of opening another. */
	val isOpen: Boolean get() = isPicking.get()

	/**
	 * The window a picker belongs to: [window], or else the application's active or first showing frame. A picker
	 * without an owner is a top-level window of its own that can open behind the main window and look like a hang.
	 */
	internal fun owner(window: Window?): Window? = window
		?: KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
		?: Window.getWindows().firstOrNull { it is Frame && it.isShowing }

	/**
	 * A picker is already open (one at a time, so two requests cannot stack dialogs): raises it and gives it focus,
	 * or beeps when it has no window yet. Returns whether one was raised.
	 */
	fun focusOpenPicker(): Boolean {
		val picker = openPicker?.takeIf { it.isShowing }
			?: Window.getWindows().firstOrNull { it.isShowing && (it is FileDialog || it is JDialog && it.contentPane.components.any { c -> c is JFileChooser }) }
		val raise = Runnable {
			if (picker == null) Toolkit.getDefaultToolkit().beep()
			else { picker.toFront(); picker.requestFocus() }
		}
		if (EventQueue.isDispatchThread()) raise.run() else EventQueue.invokeLater(raise)
		return picker != null
	}

	/** A file chooser whose dialog is registered as the open picker. */
	private fun chooser(): JFileChooser = object : JFileChooser() {
		override fun createDialog(parent: Component?): JDialog = super.createDialog(parent).also { openPicker = it }
	}

	private fun createFileDialog(window: Window?, title: String, mode: Int): FileDialog {
		return when (val owner = owner(window)) {
			is Dialog -> FileDialog(owner, title, mode)
			is Frame -> FileDialog(owner, title, mode)
			else -> FileDialog(null as Frame?, title, mode)
		}
	}

	/**
	 * Opens the modern native OS file picker for selecting a PSD file.
	 */
	fun choosePsdFile(window: Window? = null, initialPath: String? = null): String? =
		chooseSourceFile(window, initialPath, "psd", tr("dialog.choosePsd"), tr("dialog.psdFilter"))

	fun chooseCmo3File(window: Window? = null, initialPath: String? = null): String? =
		chooseSourceFile(window, initialPath, "cmo3", tr("cmo3.chooseFile"), "Cubism model (*.cmo3)")

	private fun chooseSourceFile(window: Window?, initialPath: String?, extension: String, title: String, filterLabel: String): String? {
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
			return null
		}
		try {

			// 1. Try Java AWT FileDialog (native OS Open File Dialog)
			try {
				val dialog = createFileDialog(window, title, FileDialog.LOAD).apply {
					setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
					file = "*.$extension"
					if (!initialPath.isNullOrBlank()) {
						val f = File(initialPath)
						if (f.exists()) directory = if (f.isDirectory) f.absolutePath else f.parent
					}
					openPicker = this
					isVisible = true
				}
				val dir = dialog.directory
				val selectedFile = dialog.file
				if (!dir.isNullOrBlank() && !selectedFile.isNullOrBlank()) {
					val full = File(dir, selectedFile).toPath().toAbsolutePath().normalize().toString()
					if (full.endsWith(".$extension", ignoreCase = true)) {
						return full
					}
				}
				// Dialog completed normally and user cancelled
				return null
			} catch (_: Throwable) {}

			// 2. Fallback to System Look & Feel JFileChooser only if native picker threw exception
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
				val chooser = chooser().apply {
					dialogTitle = title
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter(filterLabel, extension)
					if (!initialPath.isNullOrBlank()) {
						val f = File(initialPath)
						if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
					}
				}
				if (chooser.showOpenDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					return chooser.selectedFile.toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}

			return null
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/** Opens the native OS file picker for a Cubism physics3.json to import. */
	fun choosePhysicsFile(window: Window? = null): String? {
		if (!isPicking.compareAndSet(false, true)) { focusOpenPicker(); return null }
		try {
			val title = tr("dialog.choosePhysics")
			try {
				val dialog = createFileDialog(window, title, FileDialog.LOAD).apply {
					setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
					file = "*.physics3.json"
					openPicker = this
					isVisible = true
				}
				val dir = dialog.directory
				val selectedFile = dialog.file
				return if (!dir.isNullOrBlank() && !selectedFile.isNullOrBlank()) File(dir, selectedFile).toPath().toAbsolutePath().normalize().toString() else null
			} catch (_: Throwable) {}
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
				val chooser = chooser().apply {
					dialogTitle = title
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter(tr("dialog.physicsFilter"), "json")
				}
				if (chooser.showOpenDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					return chooser.selectedFile.toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}
			return null
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/**
	 * Opens the native OS file picker for selecting an existing .psd2live project file.
	 */
	fun chooseProjectFile(window: Window? = null, initialPath: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
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
					openPicker = this
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
				val chooser = chooser().apply {
					dialogTitle = title
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PSD2Live (*.psd2live)", "psd2live")
					if (!initialPath.isNullOrBlank()) {
						val f = File(initialPath)
						if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
					}
				}
				if (chooser.showOpenDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					return chooser.selectedFile.toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}
			return null
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/**
	 * Opens the native OS file picker for saving a single .psd2live project file.
	 */
	fun chooseSaveProjectFile(window: Window? = null, defaultName: String? = null, initialDir: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
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
					openPicker = this
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
				val chooser = chooser().apply {
					dialogTitle = title
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PSD2Live (*.psd2live)", "psd2live")
					selectedFile = File(defaultFileName)
					if (!initialDir.isNullOrBlank()) {
						val f = File(initialDir)
						if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
					}
				}
				if (chooser.showSaveDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					val f = chooser.selectedFile
					val name = if (f.name.endsWith(".psd2live", ignoreCase = true)) f.name else "${f.name}.psd2live"
					return File(f.parentFile ?: File("."), name).toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}
			return null
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/**
	 * Opens the native OS file picker for saving a PSD (.psd) file.
	 */
	fun chooseSavePsdFile(window: Window? = null, defaultName: String? = null, initialDir: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
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
					openPicker = this
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
				val chooser = chooser().apply {
					dialogTitle = title
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Photoshop Document (*.psd)", "psd")
					selectedFile = File(defaultFileName)
					if (!initialDir.isNullOrBlank()) {
						val f = File(initialDir)
						if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
					}
				}
				if (chooser.showSaveDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					val f = chooser.selectedFile
					val name = if (f.name.endsWith(".psd", ignoreCase = true)) f.name else "${f.name}.psd"
					return File(f.parentFile ?: File("."), name).toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}
			return null
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/**
	 * Opens the native OS file picker for saving a PNG image; the name gets ".png" when it lacks it.
	 */
	fun chooseSavePngFile(window: Window? = null, title: String, defaultName: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
			return null
		}
		try {
			val defaultFileName = if (defaultName.isNullOrBlank()) "image.png" else if (defaultName.endsWith(".png", ignoreCase = true)) defaultName else "$defaultName.png"
			fun named(name: String) = if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"

			try {
				val dialog = createFileDialog(window, title, FileDialog.SAVE).apply {
					setFilenameFilter { _, name -> name.endsWith(".png", ignoreCase = true) }
					file = defaultFileName
					openPicker = this
					isVisible = true
				}
				val dir = dialog.directory
				val selectedFile = dialog.file
				if (!dir.isNullOrBlank() && !selectedFile.isNullOrBlank()) {
					return File(dir, named(selectedFile)).toPath().toAbsolutePath().normalize().toString()
				}
				// Dialog completed normally and user cancelled
				return null
			} catch (_: Throwable) {}

			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
				val chooser = chooser().apply {
					dialogTitle = title
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PNG (*.png)", "png")
					selectedFile = File(defaultFileName)
				}
				if (chooser.showSaveDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					val f = chooser.selectedFile
					return File(f.parentFile ?: File("."), named(f.name)).toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}
			return null
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/**
	 * Opens the modern native OS directory picker.
	 */
	fun chooseDirectory(window: Window? = null, initialPath: String? = null, title: String? = null): String? {
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
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
						openPicker = this
						openPicker = this
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
				val chooser = chooser().apply {
					this.dialogTitle = dialogTitle
					fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
					if (initialFile != null) {
						currentDirectory = initialFile
						selectedFile = initialFile
					}
				}
				if (chooser.showOpenDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					val selected = chooser.selectedFile ?: return null
					return selected.toPath().toAbsolutePath().normalize().toString()
				}
			} catch (_: Throwable) {}

			return null
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/**
	 * Opens a multi-select picker for transparent raster images (PNG / WebP / TIFF / BMP).
	 */
	fun chooseTransparentImages(window: Window? = null, initialPath: String? = null): List<File> {
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
			return emptyList()
		}
		try {
			val title = tr("dialog.chooseTransparentImages")
			val extensions = arrayOf("png", "webp", "tif", "tiff", "bmp")
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
				val chooser = chooser().apply {
					dialogTitle = title
					isMultiSelectionEnabled = true
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
						tr("dialog.transparentImageFilter"),
						*extensions,
					)
					if (!initialPath.isNullOrBlank()) {
						val f = File(initialPath)
						if (f.exists()) currentDirectory = if (f.isDirectory) f else f.parentFile
					}
				}
				if (chooser.showOpenDialog(owner(window)) == JFileChooser.APPROVE_OPTION) {
					return chooser.selectedFiles
						?.filter { it.isFile }
						.orEmpty()
				}
			} catch (_: Throwable) {}
			return emptyList()
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}
}


