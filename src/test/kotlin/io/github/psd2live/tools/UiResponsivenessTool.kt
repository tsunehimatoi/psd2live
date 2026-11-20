package io.github.psd2live.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.HelpTab
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.StartSection
import io.github.psd2live.ui.views.LocalAwtWindow
import io.github.psd2live.ui.views.PSD2LiveApp
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * How long the editor window stays unresponsive after each page or mode switch, in the real window: for each step
 * the EDT's worst ping delay and busy time over the next seconds, with the EDT frames sampled meanwhile
 * (build/tools/ui-responsiveness/report.txt). Under Xvfb with Mesa's llvmpipe it stands in for a weak GPU; Skiko
 * refuses llvmpipe for OpenGL, so the tool lifts that refusal.
 *
 * PSD2LIVE_TOOLS=1 SKIKO_RENDER_API=OPENGL xvfb-run -s "-screen 0 1920x1200x24" ./gradlew test --tests '*UiResponsivenessTool'
 */
class UiResponsivenessTool {
	@Test fun responsiveness() = try { run() } catch (t: Throwable) { t.printStackTrace(); throw t }

	private fun allowSoftwareGl() = runCatching {
		val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
		val unsafe = unsafeField.get(null) as sun.misc.Unsafe
		val type = Class.forName("org.jetbrains.skiko.GraphicsApi_jvmKt")
		val field = type.getDeclaredField("notSupportedAdapters\$delegate")
		@Suppress("DEPRECATION")
		unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), lazyOf(emptyList<Any>()))
	}.onFailure { println("Could not lift Skiko's adapter list: $it") }

	private fun run() {
		requireTools()
		allowSoftwareGl()
		System.getenv("PSD2LIVE_MSAA")?.let { System.setProperty("psd2live.msaa", it) }
		System.getenv("PSD2LIVE_COMPOSITOR")?.let { System.setProperty("psd2live.compositor", it) }
		SkiaGpu.requestOpenGl()
		val out = output("ui-responsiveness")
		val report = StringBuilder()
		val seconds = setting("PSD2LIVE_STEP_SECONDS", "3").toDouble()
		Thread {
			Thread.sleep(900_000)
			File(out, "report.txt").writeText("$report\n--- watchdog: stuck")
			Runtime.getRuntime().halt(3)
		}.apply { isDaemon = true; start() }
		val vm = PSD2LiveViewModel()
		vm.attachWorkspace(io.github.psd2live.ui.state.DesktopWorkspace(vm, out.resolve("workspace").toPath()))
		val window = AtomicReference<java.awt.Window?>()
		val edt = AtomicReference<Thread?>()
		Thread {
			application(exitProcessOnExit = false) {
				Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1920.dp, 1160.dp)), undecorated = true) {
					window.set(this.window)
					CompositionLocalProvider(LocalAwtWindow provides this.window) { PSD2LiveApp(viewModel = vm, window = this.window) }
				}
			}
		}.apply { isDaemon = true; start() }
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(20) }
		}
		val totals = HashMap<String, Int>()
		val frames = java.util.concurrent.atomic.AtomicLong()
		/** Counts the window's frames: a delegate around whatever renders it now (the editor's own wrapper re-wraps it). */
		fun countFrames() {
			val layer = generateSequence(listOf<java.awt.Component>(window.get()!!)) { level ->
				level.flatMap { (it as? java.awt.Container)?.components?.toList().orEmpty() }.takeIf { it.isNotEmpty() }
			}.flatten().filterIsInstance<org.jetbrains.skiko.SkiaLayer>().first()
			val inner = layer.renderDelegate ?: return
			layer.renderDelegate = object : org.jetbrains.skiko.SkikoRenderDelegate {
				override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
					frames.incrementAndGet()
					inner.onRender(canvas, width, height, nanoTime)
				}
			}
		}
		/** Runs [action] on the EDT, then measures how the EDT answers pings and what it runs for [seconds]. */
		fun step(name: String, action: () -> Unit) {
			val samples = HashMap<String, Int>()
			var worst = 0L
			var busy = 0L
			val started = System.nanoTime()
			val framesBefore = frames.get()
			SwingUtilities.invokeLater(action)
			val end = started + (seconds * 1e9).toLong()
			val sampler = Thread {
				val thread = edt.get() ?: return@Thread
				while (System.nanoTime() < end) {
					val stack = thread.stackTrace
					if (thread.state == Thread.State.RUNNABLE && stack.isNotEmpty()) {
						val own = stack.firstOrNull { it.className.startsWith("io.github.psd2live") }
						val key = "${stack[0].className}.${stack[0].methodName} | ${own?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }}"
						samples.merge(key, 1, Int::plus)
						for (frame in stack.distinctBy { "${it.className}.${it.methodName}" }) {
							if (frame.className.startsWith("io.github.psd2live")) totals.merge("${frame.className.substringAfterLast('.')}.${frame.methodName}", 1, Int::plus)
						}
					}
					Thread.sleep(5)
				}
			}.apply { isDaemon = true; start() }
			while (System.nanoTime() < end) {
				val sent = System.nanoTime()
				val done = java.util.concurrent.CountDownLatch(1)
				SwingUtilities.invokeLater { done.countDown() }
				done.await()
				val delay = (System.nanoTime() - sent) / 1_000_000
				worst = maxOf(worst, delay)
				if (delay > 20) busy += delay
				Thread.sleep(10)
			}
			sampler.join()
			report.appendLine(String.format("%-34s worst ping %5d ms · busy %5d ms of %.0f s · %4d frames", name, worst, busy, seconds, frames.get() - framesBefore))
			samples.entries.sortedByDescending { it.value }.take(6).forEach { report.appendLine("      ${it.value * 5} ms  ${it.key}") }
			println(report.lines().takeLast(8).joinToString("\n"))
		}
		/** Frames per second while the window is asked to redraw continuously, for the frame's own cost. */
		fun redrawRate(name: String) {
			val layer = generateSequence(listOf<java.awt.Component>(window.get()!!)) { level ->
				level.flatMap { (it as? java.awt.Container)?.components?.toList().orEmpty() }.takeIf { it.isNotEmpty() }
			}.flatten().filterIsInstance<org.jetbrains.skiko.SkiaLayer>().first()
			val before = frames.get()
			val start = System.nanoTime()
			while (System.nanoTime() - start < 3_000_000_000L) {
				SwingUtilities.invokeAndWait { layer.needRedraw() }
				Thread.sleep(2)
			}
			val count = frames.get() - before
			report.appendLine(String.format("%-34s %5.1f fps forced · %5.1f ms per frame", "redraw: $name", count / 3.0, 3000.0 / count.coerceAtLeast(1)))
		}
		try {
			waitFor(60, "window") { window.get()?.isShowing == true }
			SwingUtilities.invokeAndWait { edt.set(Thread.currentThread()) }
			waitFor(30, "GPU") { SkiaGpu.status.value !is SkiaGpu.Status.Starting }
			report.appendLine("GPU: ${SkiaGpu.status.value}")
			Thread.sleep(1000)
			SwingUtilities.invokeAndWait { countFrames() }
			step("idle start page") {}
			redrawRate("start page")
			for (section in StartSection.entries) step("start: $section") { vm.setStartSection(section) }
			for (tab in HelpTab.entries) step("help: $tab") { vm.showHelp(tab) }
			redrawRate("help page")
			step("settings") { vm.setStartSection(StartSection.SETTINGS) }
			step("back to start") { vm.showStartPage(StartSection.RECENT) }
			step("help: QUICK_START again") { vm.showHelp(HelpTab.TUTORIALS) }
			step("back to start again") { vm.showPage(AppPage.START) }
			if (setting("PSD2LIVE_UI_MODEL", "1") == "1") {
				val sample = Sample.fromEnvironment()
				vm.openRecentFile(sample.path.toAbsolutePath().toString())
				waitFor(600, "model") { vm.state.value.previewModel != null && !vm.state.value.isBusy }
				SwingUtilities.invokeAndWait { vm.dismissMeshSplitBatch() }
				step("model open, idle") {}
				Thread.sleep(5000)
				step("edit idle, settled") {}
				redrawRate("edit workspace")
				step("mode: preview") { vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW) }
				step("preview idle") {}
				step("mode: edit") { vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.EDIT) }
				step("help: QUICK_START with model") { vm.showHelp(HelpTab.TUTORIALS) }
				step("back to workspace") { vm.showWorkspace() }
				step("mode: preview again") { vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW) }
			}
			report.appendLine("\nEDT samples by psd2live frame (5 ms each):")
			totals.entries.sortedByDescending { it.value }.take(40).forEach { report.appendLine("  ${it.value * 5} ms  ${it.key}") }
		} finally {
			File(out, "report.txt").writeText(report.toString())
			println(report)
			SwingUtilities.invokeLater { window.get()?.dispose() }
		}
	}
}
