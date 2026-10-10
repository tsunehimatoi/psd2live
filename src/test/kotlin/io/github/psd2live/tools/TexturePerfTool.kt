package io.github.psd2live.tools

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.application.WorkspaceTextureCommands
import io.github.psd2live.application.WorkspaceTextureEdit
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.TexturePin
import io.github.psd2live.project.config
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Wall time of the texture workspace's commands: pinning a tile, a density change, a pack and a budget change.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*TexturePerfTool'
 * [runtime] times the application command and the preview rebuild it runs; [desktop] times the editor's own
 * path (view model, desktop adapter, projection, the views' capture and page image) with Swing thread stalls.
 * Writes build/tools/texture-perf/{runtime,desktop}.txt.
 */
class TexturePerfTool {
	private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0

	@Test fun runtime() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("texture-perf")
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		val commands = WorkspaceTextureCommands(runtime)
		val report = StringBuilder()
		runBlocking {
			WorkspaceSourceImporter(runtime).importPsd(sample.path.toAbsolutePath(), null, runtime.state.value.state, initialConfig = PipelineConfig())
			// TEXTURE_PERF_SCENARIO: plain, skeleton (auto skeleton) or deleted (auto skeleton and one soft-deleted
			// layer, so the model replays the deleted layer before filtering it).
			val scenario = setting("TEXTURE_PERF_SCENARIO", "plain")
			if (scenario != "plain") {
				val plain = runtime.capture()
				val spec = io.github.psd2live.core.SkeletonAutoBuilder.build(plain.model.analysis, plain.model.rig)
				val document = plain.document.copy(rigEdits = plain.document.rigEdits.copy(skeleton = spec))
				runtime.install(runtime.state.value.state, plain.projectId, document, builder.build(document), discardUnsaved = true)
			}
			if (scenario == "deleted") {
				val start = runtime.capture()
				val victim = start.model.atlas.placementByLayerId.entries.minBy { it.value.width.toLong() * it.value.height }.key
				io.github.psd2live.application.WorkspaceLayerCommands(runtime).execute(start.projectId, start.state,
					io.github.psd2live.application.WorkspaceDocumentOperation("layer_delete", kotlinx.serialization.json.buildJsonObject {
						put("layer_id", kotlinx.serialization.json.JsonPrimitive(victim)) }), "Delete", MutationAuthor.USER)
			}
			val tiles = runtime.capture().model.atlas.placementByLayerId.entries.sortedBy { it.key }
			val (layer, placement) = tiles.first { it.value.width >= 16 }
			report.appendLine("scenario=$scenario tiles=${tiles.size} target=$layer ${placement.width}x${placement.height}")
			fun edit(round: Int): WorkspaceTextureEdit = when (round % 4) {
				0 -> WorkspaceTextureEdit.SetTile(layer, TexturePin(0, 2000 + round * 4, 3000))
				1 -> WorkspaceTextureEdit.SetPixelDensity(listOf(layer), if (round % 8 == 1) 2f else null)
				2 -> WorkspaceTextureEdit.Pack(byMesh = round % 8 == 2)
				else -> WorkspaceTextureEdit.SetBudget(padding = if (round % 8 == 3) 4 else 2)
			}
			// TEXTURE_PERF_JFR=1 records the rounds to build/tools/texture-perf/runtime.jfr.
			val recording = if (setting("TEXTURE_PERF_JFR", "0") == "1") jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply { start() } else null
			repeat(12) { round ->
				val before = runtime.capture()
				val change = edit(round)
				var t = System.nanoTime()
				commands.execute(before.projectId, before.state, change, MutationAuthor.USER)
				val commit = ms(t)
				val after = runtime.capture()
				t = System.nanoTime(); builder.build(after.document, before.model)
				val rebuild = ms(t)
				t = System.nanoTime(); after.document.config()
				val decode = ms(t)
				report.appendLine("%-20s commit %5.0f ms  rebuild alone %5.0f ms  config %4.1f ms".format(change.operation, commit, rebuild, decode))
			}
			recording?.run { stop(); dump(out.resolve("runtime.jfr").toPath()); close() }
		}
		out.resolve("runtime-${setting("TEXTURE_PERF_SCENARIO", "plain")}.txt").writeText(report.toString())
		println(report)
	}

	@Test fun desktop() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("texture-perf")
		val viewModel = io.github.psd2live.ui.state.PSD2LiveViewModel()
		val workspace = io.github.psd2live.ui.state.DesktopWorkspace(viewModel, out.resolve("workspace").toPath())
		viewModel.attachWorkspace(workspace)
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(2) }
		}
		val maxGap = java.util.concurrent.atomic.AtomicLong()
		var last = System.nanoTime()
		javax.swing.SwingUtilities.invokeAndWait {
			javax.swing.Timer(2) { val now = System.nanoTime(); maxGap.accumulateAndGet(now - last, ::maxOf); last = now }.start()
		}
		val report = StringBuilder()
		viewModel.openRecentFile(sample.path.toAbsolutePath().toString())
		waitFor(600, "model") { viewModel.state.value.previewModel != null && !viewModel.state.value.isBusy && viewModel.currentWorkspaceState() != null }
		javax.swing.SwingUtilities.invokeAndWait { viewModel.dismissStartScreen() }
		Thread.sleep(1000)
		val first = requireNotNull(viewModel.textureSnapshot())
		val tile = first.atlas.tiles.filter { it.width >= 16 }.minBy { it.layerId }
		report.appendLine("target=${tile.layerId} ${tile.width}x${tile.height}")
		repeat(8) { round ->
			val snapshot = requireNotNull(viewModel.textureSnapshot())
			val revision = viewModel.state.value.textureWorkspace.revision
			maxGap.set(0)
			val t = System.nanoTime()
			javax.swing.SwingUtilities.invokeAndWait {
				if (round % 2 == 0) {
					viewModel.draggedTextureTile(snapshot, tile.layerId, 2000f + round * 8, 3000f)?.let { viewModel.moveTextureTile(snapshot, it) }; viewModel.applyTextureSession()
				} else { viewModel.setTextureDensity(snapshot, listOf(tile.layerId), if (round % 4 == 1) 2f else null); viewModel.applyTextureSession() }
			}
			waitFor(60, "commit") { viewModel.state.value.textureWorkspace.revision != revision && !viewModel.state.value.textureWorkspace.busy }
			val committed = ms(t)
			var s = System.nanoTime()
			val next = requireNotNull(viewModel.textureSnapshot())
			val capture = ms(s)
			s = System.nanoTime()
			val matches = next.matches(viewModel.state.value.previewModel?.atlas)
			val match = ms(s)
			s = System.nanoTime(); next.pagePng(0)
			val png = ms(s)
			report.appendLine("%-8s commit %5.0f ms  max EDT gap %4.0f ms  capture %5.1f ms  preview atlas matches=%s (%4.1f ms)  page PNG %5.0f ms %s".format(
				if (round % 2 == 0) "pin" else "density", committed, maxGap.get() / 1e6, capture, matches, match, png,
				viewModel.state.value.textureWorkspace.error ?: ""))
			Thread.sleep(300)
		}
		out.resolve("desktop.txt").writeText(report.toString())
		println(report)
		workspace.close()
	}
}
