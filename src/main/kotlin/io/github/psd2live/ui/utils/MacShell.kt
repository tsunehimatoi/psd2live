package io.github.psd2live.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.awt.ComposeWindow
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.channels.Channel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem

/**
 * The Swift app bundle hosts this process and sets `psd2live.macShell`. The JVM owns the AppKit
 * run loop, so the macOS menu bar is a Swing bar (moved to the screen by `apple.laf.useScreenMenuBar`)
 * rather than a second Swift menu. Commands call the same actions as the in-window menus.
 */
internal object MacShell {
	val active: Boolean = System.getProperty("psd2live.macShell") == "true"

	val actions = Actions()
	private val pending = FileOpenRequests()

	fun enqueue(path: String) {
		pending.enqueue(path)
	}

	suspend fun consumeFiles(open: suspend (String) -> Unit) = pending.consume(open)

	/** Existing paths to open in the GUI, an empty list for a normal launch, or null when [command] is a CLI invocation. */
	fun guiFiles(command: List<String>): List<String>? {
		if (command.isEmpty()) return emptyList()
		if (command.first() in setOf("targets", "export", "mcp")) return null
		if (command.any { it.startsWith("-") }) return null
		val paths = command.map { runCatching { Path.of(it) }.getOrNull() ?: return null }
		if (paths.any { !Files.isRegularFile(it) ||
			!(it.fileName.toString().endsWith(".psd", true) || it.fileName.toString().endsWith(".psd2live", true)) }) return null
		return paths.map { it.toAbsolutePath().normalize().toString() }
	}

	class Actions {
		var onStartScreen: () -> Unit = {}
		var onOpenProject: () -> Unit = {}
		var onOpenPsd: () -> Unit = {}
		var onOpenRecent: (String) -> Unit = {}
		var onClearRecent: () -> Unit = {}
		var onSave: () -> Unit = {}
		var onSaveAs: () -> Unit = {}
		var onExport: () -> Unit = {}
		var onExportUnity: () -> Unit = {}
		var onNewLayout: () -> Unit = {}
		var onUndo: () -> Unit = {}
		var onRedo: () -> Unit = {}
		var onZoomIn: () -> Unit = {}
		var onZoomOut: () -> Unit = {}
		var onResetZoom: () -> Unit = {}
		var onToggleTheme: () -> Unit = {}
		var onShowSettings: () -> Unit = {}
		var onOpenHelp: () -> Unit = {}
		var onShowHistory: () -> Unit = {}
	}

	@Composable
	fun Host(
		window: ComposeWindow,
		isBusy: Boolean,
		hasInput: Boolean,
		recentFiles: List<String>,
		darkTheme: Boolean,
		onStartScreen: () -> Unit,
		onOpenProject: () -> Unit,
		onOpenPsd: () -> Unit,
		onOpenRecent: (String) -> Unit,
		onClearRecent: () -> Unit,
		onSave: () -> Unit,
		onSaveAs: () -> Unit,
		onExport: () -> Unit,
		onExportUnity: () -> Unit,
		onNewLayout: () -> Unit,
		onUndo: () -> Unit,
		onRedo: () -> Unit,
		onZoomIn: () -> Unit,
		onZoomOut: () -> Unit,
		onResetZoom: () -> Unit,
		onToggleTheme: () -> Unit,
		onShowSettings: () -> Unit,
		onOpenHelp: () -> Unit,
		onShowHistory: () -> Unit,
	) {
		DisposableEffect(window) {
			val menu = ScreenMenu(window)
			menu.install()
			onDispose {
				window.jMenuBar = null
				if (ScreenMenu.current === menu) ScreenMenu.current = null
			}
		}
		SideEffect {
			actions.onStartScreen = onStartScreen
			actions.onOpenProject = onOpenProject
			actions.onOpenPsd = onOpenPsd
			actions.onOpenRecent = onOpenRecent
			actions.onClearRecent = onClearRecent
			actions.onSave = onSave
			actions.onSaveAs = onSaveAs
			actions.onExport = onExport
			actions.onExportUnity = onExportUnity
			actions.onNewLayout = onNewLayout
			actions.onUndo = onUndo
			actions.onRedo = onRedo
			actions.onZoomIn = onZoomIn
			actions.onZoomOut = onZoomOut
			actions.onResetZoom = onResetZoom
			actions.onToggleTheme = onToggleTheme
			actions.onShowSettings = onShowSettings
			actions.onOpenHelp = onOpenHelp
			actions.onShowHistory = onShowHistory
			ScreenMenu.current?.update(isBusy, hasInput, recentFiles, darkTheme)
		}
	}

	private class ScreenMenu(private val window: ComposeWindow) {
		private var recent: JMenu? = null
		private val enabled = HashMap<String, JMenuItem>()

		fun install() {
			val bar = JMenuBar()
			bar.add(menu(tr("menu.file"),
				item("start", tr("menu.file.startPage")) { actions.onStartScreen() },
				item("openProject", tr("project.open")) { actions.onOpenProject() },
				item("openPsd", tr("menu.file.openPsd")) { actions.onOpenPsd() },
				JMenu(tr("menu.file.recent")).also { recent = it },
				null,
				item("save", tr("project.save")) { actions.onSave() },
				item("saveAs", tr("project.saveAs")) { actions.onSaveAs() },
				null,
				item("export", tr("menu.file.export")) { actions.onExport() },
				item("unity", tr("export.menu.unity")) { actions.onExportUnity() },
				null,
				item("layout", tr("menu.file.newLayout")) { actions.onNewLayout() },
			))
			bar.add(menu(tr("menu.edit"),
				item("undo", tr("editor.undo")) { actions.onUndo() },
				item("redo", tr("editor.redo")) { actions.onRedo() },
				null,
				item("history", tr("tab.new.history")) { actions.onShowHistory() },
			))
			bar.add(menu(tr("menu.view"),
				item("zoomIn", tr("menu.view.zoomIn")) { actions.onZoomIn() },
				item("zoomOut", tr("menu.view.zoomOut")) { actions.onZoomOut() },
				item("zoomReset", tr("menu.view.zoomReset")) { actions.onResetZoom() },
				null,
				item("fullscreen", tr("menu.view.fullscreen")) { AppSettings.toggleFullscreen() },
				item("theme", tr("theme.toggle.toLight")) { actions.onToggleTheme() },
			))
			bar.add(menu(tr("menu.help"),
				item("help", tr("menu.help.open")) { actions.onOpenHelp() },
				item("settings", tr("dialog.settings.title")) { actions.onShowSettings() },
			))
			window.jMenuBar = bar
			current = this
		}

		fun update(isBusy: Boolean, hasInput: Boolean, recentFiles: List<String>, darkTheme: Boolean) {
			fun allow(id: String, value: Boolean) { enabled[id]?.isEnabled = value }
			allow("openProject", !isBusy)
			allow("openPsd", !isBusy)
			allow("save", hasInput)
			allow("saveAs", hasInput)
			allow("export", hasInput && !isBusy)
			allow("unity", hasInput && !isBusy)
			allow("layout", !isBusy)
			allow("undo", !isBusy)
			allow("redo", !isBusy)
			enabled["theme"]?.text = tr(if (darkTheme) "theme.toggle.toLight" else "theme.toggle.toDark")
			enabled["fullscreen"]?.text = tr(if (AppSettings.windowMode == io.github.psd2live.ui.state.WindowMode.FULLSCREEN)
				"menu.view.exitFullscreen" else "menu.view.fullscreen")
			val menu = recent ?: return
			val shown = recentFiles.filter { runCatching { Files.isRegularFile(Path.of(it)) }.getOrDefault(false) }
			if (shown == lastRecent) return
			lastRecent = shown
			menu.removeAll()
			if (shown.isEmpty()) {
				menu.add(JMenuItem(tr("menu.file.recent.empty")).apply { isEnabled = false })
			} else {
				shown.take(12).forEach { path ->
					val name = runCatching { Path.of(path).fileName.toString() }.getOrDefault(path)
					menu.add(JMenuItem(name).apply { addActionListener { actions.onOpenRecent(path) } })
				}
				menu.addSeparator()
				menu.add(JMenuItem(tr("menu.file.recent.clear")).apply { addActionListener { actions.onClearRecent() } })
			}
		}

		private var lastRecent: List<String>? = null

		private fun item(id: String, title: String, action: () -> Unit): JMenuItem =
			JMenuItem(title).apply {
				addActionListener { action() }
				enabled[id] = this
			}

		private fun menu(title: String, vararg entries: Any?): JMenu = JMenu(title).apply {
			entries.forEach { entry ->
				when (entry) {
					null -> addSeparator()
					is JMenuItem -> add(entry)
					is JMenu -> add(entry)
				}
			}
		}

		companion object {
			var current: ScreenMenu? = null
		}
	}
}

/** One consumer awaits each open (including save prompts) before accepting the next file. */
internal class FileOpenRequests {
	private val pending = Channel<String>(Channel.UNLIMITED)
	fun enqueue(path: String) { check(pending.trySend(path).isSuccess) }
	suspend fun consume(open: suspend (String) -> Unit) {
		for (path in pending) open(path)
	}
}
