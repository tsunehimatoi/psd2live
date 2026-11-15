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
import java.util.concurrent.FutureTask
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.metal.MetalFileChooserUI

object NativeFilePicker {

	private val isPicking = AtomicBoolean(false)
	private val isLinux = System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)

	/** Includes callers on Dispatchers.IO as well as menu callbacks already on the EDT. */
	internal fun <T> onPickerThread(action: () -> T): T {
		if (EventQueue.isDispatchThread()) return action()
		val task = FutureTask<T> { action() }
		EventQueue.invokeLater(task)
		return task.get()
	}

	internal fun withExtension(file: File, extension: String): File =
		if (file.name.endsWith(".$extension", ignoreCase = true)) file else File(file.parentFile, "${file.name}.$extension")

	/**
	 * GTK's in-process FileDialog can fail to paint after Skia is loaded (GTK reports FreeType drawing errors).
	 * It still opens successfully, so exception-based fallback cannot help. Use Swing on Linux, including folder
	 * and multi-select pickers; a local Java UI delegate avoids GTK without changing the process's look and feel.
	 */
	internal fun linuxChooser(title: String, extensions: List<String>, filterLabel: String, initialPath: String?,
		defaultName: String?, selectionMode: Int, multiple: Boolean): JFileChooser = object : JFileChooser() {
		override fun updateUI() { setUI(MetalFileChooserUI(this)) }
		override fun createDialog(parent: Component?): JDialog = super.createDialog(parent).also { openPicker = it }
		override fun approveSelection() {
			if (dialogType == SAVE_DIALOG && extensions.size == 1) {
				selectedFile?.takeIf { it.isDirectory }?.let { currentDirectory = it; return }
				val target = selectedFile?.let { withExtension(it, extensions.single()) } ?: return
				if (target.isDirectory) { currentDirectory = target; return }
				if (target.exists() && JOptionPane.showConfirmDialog(this,
					"${target.absolutePath}\n${tr("project.overwriteTitle")}", tr("project.overwriteTitle"),
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return
				selectedFile = target
			}
			super.approveSelection()
		}
	}.apply {
		dialogTitle = title
		fileSelectionMode = selectionMode
		isMultiSelectionEnabled = multiple
		if (extensions.isNotEmpty()) {
			fileFilter = FileNameExtensionFilter(filterLabel, *extensions.toTypedArray())
			isAcceptAllFileFilterUsed = false
		}
		initialPath?.takeIf { it.isNotBlank() }?.let(::File)?.let { path ->
			val directory = if (path.isDirectory) path else path.parentFile
			if (directory?.isDirectory == true) currentDirectory = directory
		}
		if (!defaultName.isNullOrBlank()) selectedFile = File(defaultName).let { if (it.isAbsolute) it else File(currentDirectory, defaultName) }
	}

	private fun chooseLinux(window: Window?, title: String, extensions: List<String> = emptyList(), filterLabel: String = "",
		initialPath: String? = null, defaultName: String? = null, saveExtension: String? = null,
		selectionMode: Int = JFileChooser.FILES_ONLY, multiple: Boolean = false): List<File> = onPickerThread {
		if (!isPicking.compareAndSet(false, true)) { focusOpenPicker(); return@onPickerThread emptyList() }
		try {
			val chooser = linuxChooser(title, extensions, filterLabel, initialPath, defaultName, selectionMode, multiple)
			val parent = owner(window)
			val result = if (saveExtension != null) chooser.showSaveDialog(parent) else chooser.showOpenDialog(parent)
			if (result != JFileChooser.APPROVE_OPTION) emptyList()
			else {
				val files = if (multiple) chooser.selectedFiles.toList() else listOfNotNull(chooser.selectedFile)
				files.map { if (saveExtension == null) it else withExtension(it, saveExtension) }
					.filter { file ->
						when {
							saveExtension != null -> true
							file.isDirectory -> selectionMode != JFileChooser.FILES_ONLY
							!file.isFile || selectionMode == JFileChooser.DIRECTORIES_ONLY -> false
							else -> extensions.isEmpty() || extensions.any { file.name.endsWith(".$it", ignoreCase = true) }
						}
					}
			}
		} finally {
			openPicker?.dispose()
			openPicker = null
			isPicking.set(false)
		}
	}

	private fun List<File>.singlePath(): String? = firstOrNull()?.toPath()?.toAbsolutePath()?.normalize()?.toString()

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
	 * Opens the modern native OS file picker for selecting a PSD file or a layout manifest (JSON, or a zip holding one).
	 */
	fun choosePsdFile(window: Window? = null, initialPath: String? = null): String? =
		chooseSourceFile(window, initialPath, listOf("psd", "json", "zip"), tr("dialog.choosePsd"), tr("dialog.psdFilter"))

	fun chooseJsonFile(window: Window? = null, title: String): String? =
        chooseSourceFile(window, null, listOf("json"), title, "JSON (*.json)")

    fun choosePluginFile(window: Window? = null, title: String): String? =
        chooseSourceFile(window, null, listOf("zip"), title, "ZIP (*.zip)")

	fun chooseSaveJsonFile(window: Window? = null, title: String, defaultName: String? = null): String? =
        chooseSaveFile(window, title, defaultName, "json", "JSON (*.json)")

	fun chooseCmo3File(window: Window? = null, initialPath: String? = null): String? =
		chooseSourceFile(window, initialPath, listOf("cmo3"), tr("cmo3.chooseFile"), "Cubism model (*.cmo3)")

	private fun chooseSourceFile(window: Window?, initialPath: String?, extensions: List<String>, title: String, filterLabel: String): String? {
		if (isLinux) return chooseLinux(window, title, extensions, filterLabel, initialPath).singlePath()
		fun accepted(name: String) = extensions.any { name.endsWith(".$it", ignoreCase = true) }
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
			return null
		}
		try {

			// 1. Try Java AWT FileDialog (native OS Open File Dialog)
			try {
				val dialog = createFileDialog(window, title, FileDialog.LOAD).apply {
					setFilenameFilter { _, name -> accepted(name) }
					file = extensions.joinToString(";") { "*.$it" }
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
					if (accepted(full)) {
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
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter(filterLabel, *extensions.toTypedArray())
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
		if (isLinux) return chooseLinux(window, tr("dialog.choosePhysics"), listOf("json"), tr("dialog.physicsFilter")).singlePath()
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
		if (isLinux) return chooseLinux(window, tr("project.open"), listOf("psd2live"), "PSD2Live (*.psd2live)", initialPath).singlePath()
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
		if (isLinux) return chooseLinux(window, tr("project.saveAs"), listOf("psd2live"), "PSD2Live (*.psd2live)", initialDir,
			withExtension(File(defaultName?.takeIf { it.isNotBlank() } ?: "project"), "psd2live").path, "psd2live").singlePath()
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
		if (isLinux) return chooseLinux(window, tr("menu.file.reexportPsd"), listOf("psd"), "Photoshop Document (*.psd)", initialDir,
			withExtension(File(defaultName?.takeIf { it.isNotBlank() } ?: "export"), "psd").path, "psd").singlePath()
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
	fun chooseSavePngFile(window: Window? = null, title: String, defaultName: String? = null): String? =
        chooseSaveFile(window, title, defaultName ?: "image.png", "png", "PNG (*.png)")

    private fun chooseSaveFile(window: Window?, title: String, defaultName: String?, extension: String, filter: String): String? {
		if (isLinux) return chooseLinux(window, title, listOf(extension), filter,
			defaultName = withExtension(File(defaultName?.takeIf { it.isNotBlank() } ?: if (extension == "png") "image" else "preset"), extension).path,
			saveExtension = extension).singlePath()
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
			return null
		}
		try {
			val defaultFileName = if (defaultName.isNullOrBlank()) "${if (extension == "png") "image" else "preset"}.$extension" else if (defaultName.endsWith(".$extension", ignoreCase = true)) defaultName else "$defaultName.$extension"
			fun named(name: String) = if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"

			try {
				val dialog = createFileDialog(window, title, FileDialog.SAVE).apply {
					setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
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
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter(filter, extension)
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
		if (isLinux) return chooseLinux(window, title ?: tr("dialog.chooseOutput"), initialPath = initialPath,
			selectionMode = JFileChooser.DIRECTORIES_ONLY).singlePath()
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
	 * Opens a multi-select picker for material files and folders: images, PSD, CMO3, SVG and layout manifests
	 * ([io.github.psd2live.application.WorkspaceMaterialFiles.fileExtensions]).
	 */
	fun chooseMaterialFiles(window: Window? = null): List<File> {
		if (isLinux) return chooseLinux(window, tr("materials.import.title"),
			io.github.psd2live.application.WorkspaceMaterialFiles.fileExtensions.toList(), tr("materials.import.filter"),
			selectionMode = JFileChooser.FILES_AND_DIRECTORIES, multiple = true)
		if (!isPicking.compareAndSet(false, true)) {
			focusOpenPicker()
			return emptyList()
		}
		try {
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
				val chooser = chooser().apply {
					dialogTitle = tr("materials.import.title")
					isMultiSelectionEnabled = true
					fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
					fileFilter = javax.swing.filechooser.FileNameExtensionFilter(tr("materials.import.filter"),
						*io.github.psd2live.application.WorkspaceMaterialFiles.fileExtensions.toTypedArray())
				}
				if (chooser.showOpenDialog(owner(window)) == JFileChooser.APPROVE_OPTION) return chooser.selectedFiles?.toList().orEmpty()
			} catch (_: Throwable) {}
			return emptyList()
		} finally {
			openPicker = null
			isPicking.set(false)
		}
	}

	/**
	 * Opens a multi-select picker for transparent raster images (PNG / WebP / TIFF / BMP).
	 */
	fun chooseTransparentImages(window: Window? = null, initialPath: String? = null): List<File> {
		if (isLinux) return chooseLinux(window, tr("dialog.chooseTransparentImages"), listOf("png", "webp", "tif", "tiff", "bmp"),
			tr("dialog.transparentImageFilter"), initialPath, multiple = true).filter { it.isFile }
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


