package io.github.psd2live.ui.utils

import io.github.psd2live.i18n.tr
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.HierarchyEvent
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Universal Drag-and-Drop handler for Swing and Compose Multiplatform Desktop windows.
 *
 * In Java AWT/Swing, OLE drag-and-drop events are delivered directly to the deepest component
 * under the cursor. If child components (such as Compose's SkiaLayer/Canvas inside contentPane)
 * lack their own DropTarget, AWT drops the event and never dispatches to parent containers.
 * Furthermore, Windows OLE requires explicit `acceptDrag` during `dragEnter` and `dragOver`,
 * otherwise Windows displays the prohibited cursor and suppresses `drop` events.
 *
 * DesktopDropTarget recursively registers DropTargets on the window and all current/future child
 * components, handles drag state debouncing without flicker, and resolves dropped files into
 * semantic actions (PSD, project, directory).
 */
object DesktopDropTarget {

	sealed interface DroppedAction {
		data class OpenProject(val file: File) : DroppedAction
		data class OpenPsd(val file: File, val outputDir: File? = null) : DroppedAction
		data class SetOutputDir(val dir: File) : DroppedAction
		data class Unsupported(val files: List<File>, val message: String) : DroppedAction
	}

	private val installedComponents = Collections.newSetFromMap(WeakHashMap<Component, Boolean>())

	/**
	 * Installs recursive drop target listening on a Window and all its descendant components.
	 */
	fun install(
		window: Window,
		onDragStateChanged: ((Boolean) -> Unit)? = null,
		onFilesDropped: (List<File>) -> Unit,
	) {
		val installTask = {
			val activeDragCount = AtomicInteger(0)
			val listener = createListener(activeDragCount, onDragStateChanged, onFilesDropped)

			installRecursively(window, listener)
			if (window is RootPaneContainer) {
				window.rootPane?.let { installRecursively(it, listener) }
				window.contentPane?.let { installRecursively(it, listener) }
				window.layeredPane?.let { installRecursively(it, listener) }
				window.glassPane?.let { installRecursively(it, listener) }
			}

			window.addHierarchyListener { event ->
				if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L ||
					event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L
				) {
					installRecursively(window, listener)
					if (window is RootPaneContainer) {
						window.contentPane?.let { installRecursively(it, listener) }
						window.rootPane?.let { installRecursively(it, listener) }
					}
				}
			}
		}

		if (SwingUtilities.isEventDispatchThread()) {
			installTask()
		} else {
			SwingUtilities.invokeLater(installTask)
		}
	}

	internal fun installRecursively(component: Component, listener: DropTargetListener) {
		synchronized(installedComponents) {
			if (!installedComponents.add(component)) {
				return
			}
		}

		try {
			component.dropTarget = DropTarget(
				component,
				DnDConstants.ACTION_COPY_OR_MOVE,
				listener,
				true,
			)
		} catch (_: Throwable) {}

		if (component is Container) {
			for (i in 0 until component.componentCount) {
				installRecursively(component.getComponent(i), listener)
			}
			component.addContainerListener(object : ContainerAdapter() {
				override fun componentAdded(e: ContainerEvent) {
					installRecursively(e.child, listener)
				}
			})
		}
	}

	private fun createListener(
		activeDragCount: AtomicInteger,
		onDragStateChanged: ((Boolean) -> Unit)?,
		onFilesDropped: (List<File>) -> Unit,
	): DropTargetListener {
		return object : DropTargetListener {
			override fun dragEnter(dtde: DropTargetDragEvent) {
				if (isDragAcceptable(dtde)) {
					val action = chooseAction(dtde.sourceActions, dtde.dropAction)
					dtde.acceptDrag(action)
					if (activeDragCount.getAndIncrement() == 0) {
						onDragStateChanged?.invoke(true)
					}
				} else {
					dtde.rejectDrag()
				}
			}

			override fun dragOver(dtde: DropTargetDragEvent) {
				if (isDragAcceptable(dtde)) {
					val action = chooseAction(dtde.sourceActions, dtde.dropAction)
					dtde.acceptDrag(action)
				} else {
					dtde.rejectDrag()
				}
			}

			override fun dropActionChanged(dtde: DropTargetDragEvent) {
				if (isDragAcceptable(dtde)) {
					val action = chooseAction(dtde.sourceActions, dtde.dropAction)
					dtde.acceptDrag(action)
				} else {
					dtde.rejectDrag()
				}
			}

			override fun dragExit(dte: DropTargetEvent) {
				if (activeDragCount.decrementAndGet() <= 0) {
					activeDragCount.set(0)
					onDragStateChanged?.invoke(false)
				}
			}

			override fun drop(dtde: DropTargetDropEvent) {
				activeDragCount.set(0)
				onDragStateChanged?.invoke(false)
				try {
					if (isDragAcceptable(dtde)) {
						val action = chooseAction(dtde.sourceActions, dtde.dropAction)
						dtde.acceptDrop(action)
						val files = extractDroppedFiles(dtde.transferable)
						if (files.isNotEmpty()) {
							onFilesDropped(files)
							dtde.dropComplete(true)
							return
						}
						dtde.dropComplete(false)
					} else {
						dtde.rejectDrop()
					}
				} catch (_: Throwable) {
					runCatching { dtde.dropComplete(false) }
				}
			}
		}
	}

	private fun chooseAction(sourceActions: Int, requestedAction: Int): Int {
		return when {
			(sourceActions and DnDConstants.ACTION_COPY) != 0 -> DnDConstants.ACTION_COPY
			(sourceActions and DnDConstants.ACTION_MOVE) != 0 -> DnDConstants.ACTION_MOVE
			else -> requestedAction
		}
	}

	fun isDragAcceptable(dtde: DropTargetDragEvent): Boolean {
		val isSupportedFlavor = dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
			dtde.currentDataFlavors.any {
				it.isFlavorJavaFileListType ||
					it.isMimeTypeEqual("text/uri-list") ||
					it == DataFlavor.stringFlavor
			}
		val isSupportedAction = (dtde.sourceActions and (DnDConstants.ACTION_COPY_OR_MOVE or DnDConstants.ACTION_LINK)) != 0
		return isSupportedFlavor && isSupportedAction
	}

	fun isDragAcceptable(dtde: DropTargetDropEvent): Boolean {
		val isSupportedFlavor = dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
			dtde.currentDataFlavors.any {
				it.isFlavorJavaFileListType ||
					it.isMimeTypeEqual("text/uri-list") ||
					it == DataFlavor.stringFlavor
			}
		val isSupportedAction = (dtde.sourceActions and (DnDConstants.ACTION_COPY_OR_MOVE or DnDConstants.ACTION_LINK)) != 0
		return isSupportedFlavor && isSupportedAction
	}

	/**
	 * Safely extracts a list of Files from a Drag-and-Drop Transferable.
	 * Supports javaFileListFlavor, text/uri-list, and plain path strings.
	 */
	fun extractDroppedFiles(transferable: Transferable): List<File> {
		val files = mutableListOf<File>()

		if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			try {
				@Suppress("UNCHECKED_CAST")
				val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
				list?.filterIsInstance<File>()?.let { files.addAll(it) }
			} catch (_: Throwable) {}
		}

		if (files.isEmpty()) {
			for (flavor in transferable.transferDataFlavors) {
				if (flavor.isMimeTypeEqual("text/uri-list")) {
					try {
						val data = transferable.getTransferData(flavor) as? String
						data?.lineSequence()?.forEach { line ->
							val trimmed = line.trim()
							if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
								try {
									val uri = URI(trimmed)
									if (uri.scheme.equals("file", ignoreCase = true)) {
										files.add(File(uri))
									}
								} catch (_: Throwable) {}
							}
						}
					} catch (_: Throwable) {}
				}
			}
		}

		if (files.isEmpty() && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
			try {
				val text = (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.trim()
				if (!text.isNullOrBlank()) {
					text.lineSequence().forEach { line ->
						val candidate = line.trim().removeSurrounding("\"").removeSurrounding("'")
						if (candidate.isNotEmpty()) {
							val file = File(candidate)
							if (file.exists()) {
								files.add(file)
							}
						}
					}
				}
			} catch (_: Throwable) {}
		}

		return files
	}

	/**
	 * Resolves a list of dropped files into a typed action:
	 * - .psd2live project -> OpenProject
	 * - .psd file -> OpenPsd (attaching any companion directory as output directory)
	 * - directory -> searches inside for .psd2live or .psd; if neither, treats as output directory
	 * - others -> Unsupported
	 */
	fun resolveDropAction(files: List<File>): DroppedAction {
		if (files.isEmpty()) {
			return DroppedAction.Unsupported(emptyList(), tr("dialog.unsupportedDrop", ""))
		}

		// 1. Check for .psd2live project file
		val projectFile = files.firstOrNull { it.isFile && it.extension.equals("psd2live", ignoreCase = true) }
		if (projectFile != null) {
			return DroppedAction.OpenProject(projectFile)
		}

		// 2. Check for .psd artwork file
		val psdFile = files.firstOrNull { it.isFile && it.extension.equals("psd", ignoreCase = true) }
		if (psdFile != null) {
			val companionDir = files.firstOrNull { it.isDirectory }
			return DroppedAction.OpenPsd(psdFile, companionDir)
		}

		// 3. Check for dropped directory
		val dir = files.firstOrNull { it.isDirectory }
		if (dir != null) {
			// Scan folder for .psd2live project
			val innerProject = runCatching {
				dir.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.extension.equals("psd2live", ignoreCase = true) }
			}.getOrNull()
			if (innerProject != null) {
				return DroppedAction.OpenProject(innerProject)
			}

			// Scan folder for .psd artwork
			val innerPsd = runCatching {
				dir.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.extension.equals("psd", ignoreCase = true) }
			}.getOrNull()
			if (innerPsd != null) {
				return DroppedAction.OpenPsd(innerPsd, null)
			}

			// Folder without PSD or project: treat as output destination
			return DroppedAction.SetOutputDir(dir)
		}

		val unsupportedNames = files.joinToString(", ") { it.name }
		return DroppedAction.Unsupported(files, tr("dialog.unsupportedDrop", unsupportedNames))
	}

	internal fun clearInstalledComponentsForTesting() {
		synchronized(installedComponents) {
			installedComponents.clear()
		}
	}
}

