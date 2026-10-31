package io.github.psd2live

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import io.github.psd2live.agent.AgentMcpController
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.UiFailureReports
import io.github.psd2live.ui.state.reportingWindowExceptionHandler
import io.github.psd2live.ui.views.PSD2LiveApp
import io.github.psd2live.ui.utils.LocalNativeTitleBar
import io.github.psd2live.ui.utils.MainWindowModes
import io.github.psd2live.ui.utils.NativeTitleBar
import io.github.psd2live.ui.state.WindowMode
import androidx.compose.ui.window.WindowPlacement
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess

import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.Alignment
import io.github.psd2live.ui.state.AppSettings

fun main(arguments: Array<String>) {
	System.setProperty("sun.java2d.uiScale.enabled", "true")
	// The name macOS shows in the menu bar and Dock instead of the main class; read when AWT starts.
	System.setProperty("apple.awt.application.name", "PSD2Live")
	if (io.github.psd2live.ui.utils.MacShell.active) {
		System.setProperty("apple.laf.useScreenMenuBar", "true")
		System.setProperty("apple.awt.application.appearance", "system")
	}
	configureLanguage(arguments)
	val command = commandArguments(arguments)
	val filesToOpen = io.github.psd2live.ui.utils.MacShell.guiFiles(command)
	// Canvases draw on the GPU in Skia's own OpenGL context, so Skia must draw the window with OpenGL; the editor
	// starts with the user's graphics settings (render path, card, vsync, driver threading), before its first window.
	io.github.psd2live.render.GraphicsSettings.applyAtStartup(
		if (filesToOpen != null) AppSettings.graphics else io.github.psd2live.render.GraphicsOptions())
	if (filesToOpen != null) {
		filesToOpen.forEach { io.github.psd2live.ui.utils.MacShell.enqueue(it) }
		runGui()
		return
	}
	if (command.first() == "--clear-user-data") {
		if (UserData().clear()) return
		System.err.println(tr("app.alreadyRunning"))
		exitProcess(1)
	}
	if (command.first() == "mcp") {
		// A failure to start must end the process: the workspace's threads would keep it alive otherwise.
		exitProcess(runCatching { HeadlessMcp.run(command) }.getOrElse { it.printStackTrace(); 1 })
	}
	if (command.first() == "export" || command.first() == "targets") {
		exitProcess(ExportCli.run(command))
	}
	if (command.any { it == "--help" || it == "-h" }) {
		printUsage()
		return
	}
	val options = CliOptions.parse(command.toTypedArray())
	val config = PipelineConfig(
		atlasSize = options.int("--atlas", 4096),
        textureUpscale = io.github.psd2live.core.TextureUpscaleConfig(
            scale = options.int("--upscale", 1),
            python = options.value("--upscale-python") ?: io.github.psd2live.core.TextureUpscaleConfig.defaultPython,
            nunifDirectory = options.value("--nunif-dir") ?: "",
            modelDirectory = options.value("--upscale-model") ?: "",
            tileSize = options.int("--upscale-tile", 256),
            noiseLevel = options.int("--upscale-noise", 1),
            neuralAlpha = !options.flags.contains("--no-upscale-neural-alpha"),
        ),
		meshSpacing = options.int("--mesh-spacing", 64),
		meshUnits = if (options.flags.contains("--mesh-pixels")) io.github.psd2live.core.MeshUnits.PIXELS
			else io.github.psd2live.core.MeshUnits.DOCUMENT,
		meshWrap = options.float("--mesh-wrap", 0f).takeIf { it.isFinite() }?.coerceIn(io.github.psd2live.core.MeshWrap.range) ?: 0f,
		headTurnStrength = options.float("--head-strength", 1f),
		bodyStrength = options.float("--body-strength", 1f),
		meshOnly = options.flags.contains("--mesh-only"),
		generateDeformers = !options.flags.contains("--no-deformers"),
		seeThroughBrows = !options.flags.contains("--no-see-through"),
		seeThroughEyes = !options.flags.contains("--no-see-through") && !options.flags.contains("--no-see-through-eyes"),
		seeThroughOpacity = options.float("--see-through-opacity", io.github.psd2live.core.SeeThroughLayers.DEFAULT_OPACITY)
			.takeIf { it.isFinite() }?.coerceIn(io.github.psd2live.core.SeeThroughLayers.OPACITY_RANGE) ?: io.github.psd2live.core.SeeThroughLayers.DEFAULT_OPACITY,
		exportMotions = !options.flags.contains("--no-motions"),
		generatePhysics = !options.flags.contains("--no-physics"),
		exportCmo3 = !options.flags.contains("--no-cmo3"),
		exportMoc3 = !options.flags.contains("--no-moc3"),
		exportJson = !options.flags.contains("--no-json"),
	)
	require(config.exportCmo3 || config.exportMoc3 || config.exportJson) { tr("cli.exportFormatRequired") }
	val input = Path.of(options.required("--input"))
	val output = Path.of(options.value("--output") ?: input.toAbsolutePath().parent.resolve("psd2live-output").toString())
	println(tr("cli.start", input.toAbsolutePath(), output.toAbsolutePath()))
	val result = PSD2LivePipeline().run(input, output, config, ProgressListener { stage, fraction ->
		println("%3d%%  %s".format((fraction * 100).toInt(), stage))
	})
	println(tr("cli.complete", result.exportedFiles.size))
	result.exportedFiles.forEach { println("  ${it.path.absolutePathString()} (${it.bytes} bytes)") }
	result.warnings.forEach { System.err.println(tr("cli.warning", it)) }
}

/** The Dock shows the Java icon on macOS: the window icon only reaches the title bar and taskbar elsewhere. */
private fun useAppIconInDock() {
	if (!java.awt.Taskbar.isTaskbarSupported()) return
	val taskbar = java.awt.Taskbar.getTaskbar()
	if (!taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) return
	runCatching {
		Thread.currentThread().contextClassLoader.getResourceAsStream("icons/psd2live.png")
			?.use { javax.imageio.ImageIO.read(it) }?.let { taskbar.iconImage = it }
	}
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun runGui() {
	SessionLog.install(Path.of(System.getProperty("user.home"), ".psd2live", "logs"))
	FreezeWatchdog().start()
	val instanceLock = try {
		AppInstanceLock.acquire(WorkspaceStore.defaultRoot()) ?: run {
			// Held by a running editor; leave it alone rather than share its store and MCP port.
			System.err.println(tr("app.alreadyRunning"))
			runCatching { JOptionPane.showMessageDialog(null, tr("app.alreadyRunning"), tr("app.title"), JOptionPane.WARNING_MESSAGE) }
			exitProcess(1)
		}
	} catch (failure: IOException) {
		System.err.println("Instance lock unavailable, continuing without it: ${failure.message}")
		null
	}
	val viewModel = PSD2LiveViewModel()
	val failureReports = UiFailureReports()
	val agentWorkspace = DesktopWorkspace(viewModel)
	viewModel.attachWorkspace(agentWorkspace)
	val agentMcp = AgentMcpController(agentWorkspace, observer = viewModel.agentCallObserver)
	agentMcp.start()
	viewModel.operationDocs = agentMcp::operationDocs
	viewModel.watchAgentMcp(agentMcp.status)

	// Both close() calls are idempotent, so the hook (Ctrl+C, SIGTERM, logoff) and the normal exit can race.
	val shutdown = {
		runCatching { viewModel.close() }
		runCatching { agentMcp.close() }
		Unit
	}
	val watchdog = ExitWatchdog()
	val shutdownHook = Thread({
		watchdog.arm()
		shutdown()
	}, "psd2live-shutdown-hook")
	Runtime.getRuntime().addShutdownHook(shutdownHook)

	useAppIconInDock()
	var status = 0
	// Set when the user quits through the save-or-discard prompt; any other end (a crash, a kill) keeps the
	// recovery marker so the next start offers the unsaved edits back.
	val quitByUser = AtomicBoolean(false)
	try {
		// Exit from main rather than letting Compose call System.exit on the EDT, which would block
		// the EDT while the shutdown hooks run.
		application(exitProcessOnExit = false) {
			// Application lifetime: replacing a window for language/transparency must not cancel an open.
			LaunchedEffect(viewModel) {
				io.github.psd2live.ui.utils.MacShell.consumeFiles(viewModel::openExternalFile)
			}
			val defaultExceptionHandlers = LocalWindowExceptionHandlerFactory.current
			val exceptionHandlers = remember(defaultExceptionHandlers) {
				WindowExceptionHandlerFactory { window ->
					reportingWindowExceptionHandler(defaultExceptionHandlers.exceptionHandler(window)) { failure ->
						failureReports.record(failure, "compose-window")
						viewModel.addLog(failure.message ?: failure.javaClass.name,
							level = io.github.psd2live.ui.state.LogLevel.ERROR,
							source = io.github.psd2live.ui.state.LogSource.SYSTEM, tag = "UI",
							detail = "${failureReports.path}\n${failure.stackTraceToString()}")
					}
				}
			}
			// Opens in the mode it last closed in, already at that mode's bounds; windowed is the saved size centred. A
			// native frame maximizes the system's way, restoring to the windowed bounds.
			val windowState = remember {
				val nativeMaximized = AppSettings.windowMode == WindowMode.MAXIMIZED && NativeTitleBar.supported &&
					!viewModel.uiState.value.canvasBackground.windowTransparent
				val bounds = MainWindowModes.initialBounds(if (nativeMaximized) WindowMode.WINDOWED else AppSettings.windowMode,
					AppSettings.windowWidth, AppSettings.windowHeight)
				val placement = if (nativeMaximized) WindowPlacement.Maximized else WindowPlacement.Floating
				if (bounds == null) WindowState(placement, position = WindowPosition(Alignment.Center), size = DpSize(AppSettings.windowWidth.dp, AppSettings.windowHeight.dp))
				else WindowState(placement, position = WindowPosition(bounds.x.dp, bounds.y.dp), size = DpSize(bounds.width.dp, bounds.height.dp))
			}
			val mainFrame = remember { java.util.concurrent.atomic.AtomicReference<java.awt.Frame?>() }
			val closeApp: () -> Unit = {
				viewModel.withSavedChanges {
					mainFrame.get()?.let { MainWindowModes.windowedSize(it) }?.let { AppSettings.rememberWindowSize(it.width.toFloat(), it.height.toFloat()) }
					quitByUser.set(true)
                    agentWorkspace.prepareClose(if (viewModel.state.value.projectDirty)
                        io.github.psd2live.application.WorkspaceCloseReason.DISCARDED
                        else io.github.psd2live.application.WorkspaceCloseReason.SAVED)
					watchdog.arm()
					exitApplication()
				}
			}
			// macOS quits through the app menu and Cmd+Q, which would otherwise exit without asking to save.
			val currentCloseApp by rememberUpdatedState(closeApp)
			DisposableEffect(Unit) {
				val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop()
					.takeIf { it.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER) } else null
				desktop?.setQuitHandler { _, response ->
					response.cancelQuit()
					javax.swing.SwingUtilities.invokeLater { currentCloseApp() }
				}
				if (io.github.psd2live.ui.utils.MacShell.active) {
					desktop?.takeIf { it.isSupported(java.awt.Desktop.Action.APP_ABOUT) }?.setAboutHandler {
						javax.swing.SwingUtilities.invokeLater {
							javax.swing.JOptionPane.showMessageDialog(null, "PSD2Live 3.7.0", tr("app.title"),
								javax.swing.JOptionPane.INFORMATION_MESSAGE)
						}
					}
					desktop?.takeIf { it.isSupported(java.awt.Desktop.Action.APP_PREFERENCES) }?.setPreferencesHandler {
						javax.swing.SwingUtilities.invokeLater { io.github.psd2live.ui.utils.MacShell.actions.onShowSettings() }
					}
					desktop?.takeIf { it.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE) }?.setOpenFileHandler { event ->
						event.files.forEach { io.github.psd2live.ui.utils.MacShell.enqueue(it.absolutePath) }
					}
				}
				onDispose { desktop?.setQuitHandler(null) }
			}
			val transparent by remember {
				derivedStateOf { viewModel.uiState.value.canvasBackground.windowTransparent }
			}
			val language by remember {
				derivedStateOf { viewModel.uiState.value.currentLanguage }
			}
			// A window's transparency cannot change once it is shown, so switching the canvas
			// background to or from transparent replaces the window; windowState keeps its bounds. The system only
			// lets an undecorated window be transparent, so that one draws its whole title bar. Otherwise the window
			// keeps its native frame: the JetBrains runtime draws under a custom title bar, and the Mac shell draws
			// the app bar in the system title-bar area.
			// A language switch replaces it too: text is read through tr() wherever it is composed,
			// remembered, or handed to Swing, so only a fresh composition shows none of the old language.
			CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides exceptionHandlers) {
				key(transparent, language) {
					Window(
						onCloseRequest = closeApp,
						title = tr("app.title"),
						icon = painterResource("icons/psd2live.png"),
						state = windowState,
						undecorated = transparent || (!NativeTitleBar.supported && !io.github.psd2live.ui.utils.MacShell.active),
						transparent = transparent,
					) {
						if (io.github.psd2live.ui.utils.MacShell.active && !window.isUndecorated && !NativeTitleBar.supported) {
							io.github.psd2live.ui.utils.MacShell.installChrome(window)
						}
						val titleBar = remember(window) { if (window.isUndecorated) null else NativeTitleBar.install(window) }
						DisposableEffect(window) {
							mainFrame.set(window)
							val uninstall = MainWindowModes.install(window)
							onDispose { uninstall(); mainFrame.compareAndSet(window, null) }
						}
						val windowMode by AppSettings.windowModeFlow.collectAsState()
						LaunchedEffect(window, windowMode) { MainWindowModes.apply(window, windowMode) }
						androidx.compose.runtime.CompositionLocalProvider(io.github.psd2live.ui.views.LocalAwtWindow provides window,
							LocalNativeTitleBar provides titleBar) {
							PSD2LiveApp(
								viewModel = viewModel,
								window = window,
								windowState = windowState,
								onCloseRequest = closeApp,
								agentMcp = agentMcp,
							)
						}
					}
				}
			}
		}
	} catch (failure: Throwable) {
		runCatching { failureReports.record(failure, "application") }.onFailure { it.printStackTrace() }
		failure.printStackTrace()
		status = 1
	}
	watchdog.arm()
	shutdown()
	runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
	instanceLock?.close()
	// AWT, Skiko and pooled threads would otherwise keep the JVM alive.
	exitProcess(status)
}

/**
 * Halts the JVM when an exit stalls, since a hung hook or window would otherwise keep the process,
 * and with it the instance lock, alive until it is killed by hand. Built at startup, so arming it
 * loads nothing.
 */
private class ExitWatchdog {
	private val thread = Thread({
		try {
			Thread.sleep(EXIT_TIMEOUT_MILLIS)
		} catch (_: InterruptedException) {
		}
		System.err.println("PSD2Live did not exit within ${EXIT_TIMEOUT_MILLIS / 1000} s; halting.")
		Runtime.getRuntime().halt(1)
	}, "psd2live-exit-watchdog").apply { isDaemon = true }
	private val armed = AtomicBoolean(false)

	fun arm() {
		if (armed.compareAndSet(false, true)) thread.start()
	}

	private companion object {
		// Above the longest clean exit: MCP stop, the recovery-write flush and the Cubism shutdown.
		const val EXIT_TIMEOUT_MILLIS = 15_000L
	}
}

/** Arguments with every `--lang <tag>` pair removed. [configureLanguage] already applied the tag. */
internal fun commandArguments(arguments: Array<String>): List<String> {
	val command = ArrayList<String>(arguments.size)
	var index = 0
	while (index < arguments.size) {
		if (arguments[index] == "--lang") index += 2 else command += arguments[index++]
	}
	return command
}

private fun configureLanguage(arguments: Array<String>) {
	val index = arguments.indexOf("--lang")
	if (index < 0) return
	require(index + 1 < arguments.size) { tr("cli.missingValue", "--lang") }
	val raw = arguments[index + 1]
	val language = AppLanguage.fromTag(raw) ?: error(tr("cli.invalidLanguage", raw))
	I18n.setLanguage(language)
}

private fun printUsage() {
    println(tr("cli.usage"))
    println("""
        Texture upscale (optional local nunif):
          --upscale <1|2|4>          Default 1 (off)
          --upscale-python <path>    Python executable with nunif dependencies
          --nunif-dir <path>         nunif source checkout
          --upscale-model <path>     Explicit Art weights directory
          --upscale-tile <64..512>   Input tile size; default 256, batch 1, no TTA
          --upscale-noise <-1..3>    Denoise/sharpen level: -1 (none), 0 (clean art/sharp), 1 (medium, default), 2 (high), 3 (max)
          --no-upscale-neural-alpha  Disable neural alpha (use bilinear fallback)
    """.trimIndent())
}

internal data class CliOptions(val values: Map<String, String>, val flags: Set<String>) {
	fun value(name: String): String? = values[name]
	fun required(name: String): String = value(name) ?: error(tr("cli.missingRequired", name))
	fun int(name: String, default: Int): Int = value(name)?.let { it.toIntOrNull() ?: error(tr("cli.invalidNumber", name, it)) } ?: default
	fun float(name: String, default: Float): Float = value(name)?.let { it.toFloatOrNull() ?: error(tr("cli.invalidNumber", name, it)) } ?: default

	companion object {
		internal val flagNames = setOf("--no-upscale-neural-alpha", "--upscale-neural-alpha", "--mesh-pixels", "--no-physics", "--no-cmo3", "--no-moc3", "--mesh-only", "--no-deformers", "--no-motions", "--no-json",
			"--no-see-through", "--no-see-through-eyes", "--see-through-eyes")
		internal val valueNames = setOf("--upscale", "--upscale-noise", "--upscale-python", "--nunif-dir", "--upscale-model", "--upscale-tile", "--input", "--output", "--lang", "--atlas", "--mesh-spacing", "--mesh-wrap", "--head-strength", "--body-strength",
			"--see-through-opacity")
		fun parse(arguments: Array<String>): CliOptions {
			val values = linkedMapOf<String, String>()
			val flags = linkedSetOf<String>()
			var index = 0
			while (index < arguments.size) {
				val name = arguments[index]
				require(name in flagNames || name in valueNames) { tr("cli.unknownOption", name) }
				if (name in flagNames) {
					flags += name
					index++
				} else {
					// "--output --no-json" is a missing value, not an output directory named "--no-json".
					require(index + 1 < arguments.size && arguments[index + 1] !in flagNames && arguments[index + 1] !in valueNames) { tr("cli.missingValue", name) }
					values[name] = arguments[index + 1]
					index += 2
				}
			}
			return CliOptions(values, flags)
		}
	}
}
