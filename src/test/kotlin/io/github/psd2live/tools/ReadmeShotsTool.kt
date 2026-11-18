package io.github.psd2live.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.ui.components.BatchSplitItemState
import io.github.psd2live.ui.state.StartQuickPreset
import io.github.psd2live.ui.state.StartSection
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.views.LocalAwtWindow
import io.github.psd2live.ui.views.PSD2LiveApp
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * Screenshots of the real editor window for the project page: a PSD imported from the start page with the
 * full preset, an automatic skeleton, then each workspace. Frames are the window's own Skia frames, never the screen.
 * The project is saved as `project.psd2live` so the raster exports can render its clips.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ReadmeShotsTool'
 */
class ReadmeShotsTool {
	@Test fun shots() = try { run() } catch (t: Throwable) { t.printStackTrace(); throw t }

	private fun run() {
		requireTools()
		SkiaGpu.requestOpenGl()
		val sample = Sample.fromEnvironment()
		val out = output("readme-shots/${sample.name}${setting("PSD2LIVE_SHOT_SUFFIX", "")}")
		val log = StringBuilder()
		Thread { Thread.sleep(900_000); File(out, "log.txt").writeText("$log\n--- watchdog"); Runtime.getRuntime().halt(3) }
			.apply { isDaemon = true; start() }
		val vm = PSD2LiveViewModel()
		out.resolve("workspace").deleteRecursively()
		vm.attachWorkspace(io.github.psd2live.ui.state.DesktopWorkspace(vm, out.resolve("workspace").toPath()))
		val window = AtomicReference<java.awt.Window?>()
		val width = setting("PSD2LIVE_SHOT_W", "1600").toInt()
		val height = setting("PSD2LIVE_SHOT_H", "1000").toInt()
		Thread {
			application(exitProcessOnExit = false) {
				Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(width.dp, height.dp)), undecorated = true) {
					window.set(this.window)
					CompositionLocalProvider(LocalAwtWindow provides this.window) { PSD2LiveApp(viewModel = vm, window = this.window) }
				}
			}
		}.apply { isDaemon = true; start() }
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(50) }
		}
		fun idle(seconds: Int = 600) {
			Thread.sleep(300)
			waitFor(seconds, "idle") { val s = vm.state.value; !s.isBusy && !s.workspaceEditBusy && !s.editorDraftBusy }
			Thread.sleep(300)
		}
		fun layer(c: java.awt.Component): org.jetbrains.skiko.SkiaLayer? = c as? org.jetbrains.skiko.SkiaLayer
			?: (c as? java.awt.Container)?.components?.firstNotNullOfOrNull(::layer)
		fun capture(name: String) {
			Thread.sleep(1200)
			var written = false
			SwingUtilities.invokeAndWait {
				val bitmap = layer(window.get()!!)?.screenshot() ?: return@invokeAndWait
				org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
					?.bytes?.let { File(out, "$name.png").writeBytes(it); written = true }
			}
			log.appendLine("shot $name: $written")
		}
		fun key(code: Int, modifiers: Int = 0) {
			SwingUtilities.invokeAndWait {
				val target = layer(window.get()!!)?.canvas ?: return@invokeAndWait
				val now = System.currentTimeMillis()
				target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, code, KeyEvent.CHAR_UNDEFINED))
				target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, now + 5, modifiers, code, KeyEvent.CHAR_UNDEFINED))
			}
			Thread.sleep(400)
		}
		fun click(x: Int, y: Int) {
			SwingUtilities.invokeAndWait {
				val win = window.get()!!
				val target = SwingUtilities.getDeepestComponentAt(win, x, y) ?: return@invokeAndWait
				val p = SwingUtilities.convertPoint(win, java.awt.Point(x, y), target)
				val now = System.currentTimeMillis()
				target.dispatchEvent(java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_MOVED, now, 0, p.x, p.y, 0, false, 0))
				target.dispatchEvent(java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, p.x, p.y, 1, false, java.awt.event.MouseEvent.BUTTON1))
				target.dispatchEvent(java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_RELEASED, now + 30, 0, p.x, p.y, 1, false, java.awt.event.MouseEvent.BUTTON1))
			}
			Thread.sleep(400)
		}
		fun outdated(): List<String> {
			val current = vm.state.value
			val puppet = current.previewModel?.rig?.puppet ?: return emptyList()
			return current.rigEdits.simEdits.filter { it.enabled && (it.bake == null || io.github.psd2live.core.sim.SimBake.stale(puppet, it)) }.map { it.id }
		}
		fun bakes() {
			Thread.sleep(1500)
			repeat(3) {
				waitFor(900, "simulation bakes") { vm.simulationBaking.value == null && !vm.state.value.workspaceEditBusy }
				idle()
				val stale = outdated()
				log.appendLine("outdated after bake: $stale")
				if (stale.isEmpty()) return
				SwingUtilities.invokeAndWait { vm.bakeAllSimulations() }
				Thread.sleep(1500)
			}
		}
		try {
			waitFor(60, "window") { window.get()?.isShowing == true }
			waitFor(30, "GPU") { SkiaGpu.status.value !is SkiaGpu.Status.Starting }
			val project = out.resolve("project.psd2live")
			val reuse = setting("PSD2LIVE_SHOT_REUSE", "0") == "1" && project.isFile
			// The import applies the start page's presets; the user's own choice comes back once the bakes are done.
			val userChoices = AppSettings.startChoices
			if (!reuse) {
				SwingUtilities.invokeAndWait {
					vm.setStartChoices(StartQuickPreset.FULL.choices)
					vm.showStartPage(StartSection.GENERATE)
				}
				capture("01-start")
			}
			val importSplitPrompt = AppSettings.autoDetectMeshSplitsOnImport
			try {
				// Take the split dialog screenshot from the explicit model check below.
				if (!reuse) SwingUtilities.invokeAndWait { vm.setAutoDetectMeshSplitsOnImport(false) }
				vm.openRecentFile((if (reuse) project.toPath() else sample.path).toAbsolutePath().toString())
				waitFor(600, "model") { vm.state.value.previewModel != null && !vm.state.value.isBusy && !vm.startPageShown }
			} finally {
				if (!reuse) SwingUtilities.invokeAndWait { vm.setAutoDetectMeshSplitsOnImport(importSplitPrompt) }
			}
			if (!reuse) {
				idle()
				bakes()
				SwingUtilities.invokeAndWait { vm.setStartChoices(userChoices) }
				if (setting("PSD2LIVE_SHOT_SPLITS", "1") == "1") {
					SwingUtilities.invokeAndWait { vm.requestMeshSplitScan() }
					waitFor(120, "mesh split scan") {
						vm.pendingMeshSplitBatch != null || vm.pendingMeshSplit != null || vm.state.value.statusText == tr("start.splits.none")
					}
					vm.pendingMeshSplitBatch?.let { offer ->
						capture("02-splits")
						val decisions = offer.splits.map { BatchSplitItemState(it) }.filter { it.isSelected }.map { item ->
							val (names, sides) = item.generatedNamesAndSides()
							PSD2LiveViewModel.LayerSplitDecision(item.offer, names, sides)
						}
						log.appendLine("splits: ${decisions.map { it.names }}")
						SwingUtilities.invokeAndWait { vm.applyMeshSplitBatch(decisions) }
					}
					vm.pendingMeshSplit?.let { SwingUtilities.invokeAndWait { vm.dismissMeshSplit() } }
					idle()
					bakes()
				}
				val model = vm.state.value.previewModel!!
				val spec = io.github.psd2live.core.SkeletonAutoBuilder.build(model.analysis, model.rig)
				log.appendLine("skeleton before: ${vm.state.value.rigEdits.skeleton?.bones?.size}; auto ${spec.bones.size}")
				repeat(4) {
					if (spec.bones.isEmpty() || !vm.state.value.rigEdits.skeleton?.bones.isNullOrEmpty()) return@repeat
					idle()
					val done = java.util.concurrent.CompletableFuture<String?>()
					SwingUtilities.invokeAndWait { vm.setSkeleton(spec.copy(enabled = true), vm.currentWorkspaceState()) { done.complete(it) } }
					log.appendLine("skeleton commit: ${done.get(300, java.util.concurrent.TimeUnit.SECONDS) ?: "ok"}")
					idle()
				}
				bakes()
				log.appendLine("skeleton: ${vm.state.value.rigEdits.skeleton?.bones?.size}; arm/leg params ${vm.state.value.previewModel!!.rig.puppet.parameters.count { it.id.raw.startsWith("ParamArm") || it.id.raw.startsWith("ParamLeg") }}")
				kotlinx.coroutines.runBlocking { vm.saveProjectNow(project.toPath()) }
			}
			log.appendLine("simulations: ${vm.state.value.rigEdits.simEdits.map { it.id }}")
			SwingUtilities.invokeAndWait { vm.setLogPanelExpanded(false) }
			for (preset in WorkspacePreset.entries.filter { it != WorkspacePreset.BLANK && it != WorkspacePreset.HISTORY }) {
				SwingUtilities.invokeAndWait { vm.openWorkspacePreset(preset) }
				idle()
				capture("10-${preset.name.lowercase()}")
			}
			fun layerId(name: String) = vm.state.value.previewModel!!.analysis.layers.firstOrNull { it.source.name == name }?.source?.id?.raw
			val win = window.get()!!
			fun canvasClick() = click(win.width * 24 / 100, win.height * 55 / 100)
			fun mode(code: Int) = key(code, InputEvent.ALT_DOWN_MASK)
			fun focusOn(vararg names: String, modeKey: Int, shot: String) {
				canvasClick()
				mode(KeyEvent.VK_1)
				val ids = names.mapNotNull(::layerId)
				log.appendLine("$shot: ${names.toList()} -> $ids")
				SwingUtilities.invokeAndWait { vm.selectLayers(ids) }
				Thread.sleep(300)
				mode(modeKey)
				key(KeyEvent.VK_F)
				capture(shot)
			}
			SwingUtilities.invokeAndWait { vm.openWorkspacePreset(WorkspacePreset.EDIT) }
			idle()
			focusOn("front hair-t", modeKey = KeyEvent.VK_3, shot = "30-mesh-front")
			focusOn("back hair", modeKey = KeyEvent.VK_3, shot = "30-mesh-back")
			System.getenv("PSD2LIVE_SHOT_MESHES")?.split(';')?.filter { it.isNotBlank() }?.forEachIndexed { i, names ->
				focusOn(*names.split(',').map { it.trim() }.toTypedArray(), modeKey = KeyEvent.VK_3, shot = "31-mesh-$i")
			}
			focusOn("front hair-t", modeKey = KeyEvent.VK_2, shot = "30-deform-front")
			focusOn("face", modeKey = KeyEvent.VK_2, shot = "30-deform-face")
			focusOn("back hair", modeKey = KeyEvent.VK_4, shot = "30-simulate-back")
			focusOn("bottomwear", modeKey = KeyEvent.VK_4, shot = "30-simulate-skirt")
			focusOn("front hair-t", modeKey = KeyEvent.VK_6, shot = "30-paint")
			log.appendLine("layers: ${vm.state.value.previewModel!!.analysis.layers.map { it.source.name }}")
			canvasClick(); mode(KeyEvent.VK_1); key(KeyEvent.VK_HOME); mode(KeyEvent.VK_5); capture("30-skeleton")
			fun drag(from: java.awt.Point, to: java.awt.Point, steps: Int = 24) {
				val M = java.awt.event.MouseEvent::class.java
				fun send(id: Int, at: java.awt.Point, mods: Int, button: Int, clicks: Int) = SwingUtilities.invokeAndWait {
					val target = SwingUtilities.getDeepestComponentAt(win, at.x, at.y) ?: return@invokeAndWait
					val p = SwingUtilities.convertPoint(win, at, target)
					target.dispatchEvent(java.awt.event.MouseEvent(target, id, System.currentTimeMillis(), mods, p.x, p.y, clicks, false, button))
				}
				send(java.awt.event.MouseEvent.MOUSE_MOVED, from, 0, 0, 0); Thread.sleep(150)
				send(java.awt.event.MouseEvent.MOUSE_PRESSED, from, InputEvent.BUTTON1_DOWN_MASK, java.awt.event.MouseEvent.BUTTON1, 1); Thread.sleep(80)
				for (i in 1..steps) {
					val at = java.awt.Point(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
					send(java.awt.event.MouseEvent.MOUSE_DRAGGED, at, InputEvent.BUTTON1_DOWN_MASK, 0, 0); Thread.sleep(25)
				}
				send(java.awt.event.MouseEvent.MOUSE_RELEASED, to, 0, java.awt.event.MouseEvent.BUTTON1, 1)
				Thread.sleep(600); idle()
			}
			/** A point of the 2000-wide review image (2400 window pixels at 1.5x) in window coordinates. */
			fun at(x: Int, y: Int) = java.awt.Point(x * width / 2000, y * width / 2000)
			System.getenv("PSD2LIVE_SHOT_DRAGS")?.split(';')?.filter { it.isNotBlank() }?.forEach { drag ->
				val n = drag.split(',').map { it.trim().toInt() }
				drag(at(n[0], n[1]), at(n[2], n[3]))
			}
			capture("30-skeleton-pose")
			SwingUtilities.invokeAndWait { vm.openWorkspacePreset(WorkspacePreset.ANIMATION) }
			idle()
			for (clip in listOf("Wave", "Idle")) {
				SwingUtilities.invokeAndWait { runCatching { vm.openMotionInEditor(io.github.psd2live.ui.state.MotionEditorState.presetClipId(clip)) }.onFailure { log.appendLine("clip $clip: $it") } }
				Thread.sleep(800)
				capture("40-anim-${clip.lowercase()}")
			}
			SwingUtilities.invokeAndWait { vm.openWorkspacePreset(WorkspacePreset.PHYSICS) }
			idle()
			capture("40-physics")
		} finally {
			File(out, "log.txt").writeText(log.toString())
			println(log)
			SwingUtilities.invokeLater { window.get()?.dispose() }
		}
	}
}
