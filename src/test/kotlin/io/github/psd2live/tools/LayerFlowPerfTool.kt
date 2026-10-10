package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.nio.file.Files
import kotlin.test.Test

/**
 * Commit times of the image flow on a real PSD: import a 1024 x 512 image, move and scale it, put it under a deformer,
 * rebuild its mesh, delete it - each through the application commands, as the GUI commits them.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*LayerFlowPerfTool'; PSD2LIVE_SAMPLE picks the PSD (tml by default).
 * Writes build/tools/layer-flow/report.txt.
 */
class LayerFlowPerfTool {
	@Test fun profile() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("layer-flow")
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) },
			rebuildFrom = { document, previous -> builder.build(document, previous) })
		val commands = WorkspaceDocumentCommands(runtime)
		val report = StringBuilder()
		runBlocking {
			var start = System.nanoTime()
			WorkspaceSourceImporter(runtime, { document -> builder.build(document) }).importPsd(sample.path.toAbsolutePath(), null, runtime.state.value.state, initialConfig = PipelineConfig())
			report.appendLine("open ${sample.name}: %.0f ms, %d drawables".format(since(start), runtime.capture().model.rig.puppet.drawables.size))
			val pixels = ByteArray(1024 * 512 * 4) { if (it % 4 == 3) -1 else 120 }
			val file = Files.createTempFile("bow", ".png").also { Files.write(it, PngCodec.write(RasterImage(1024, 512, pixels))) }
			var capture = runtime.capture()
			run {
				// The import's parts alone: decoding and placing the image, its mesh, and a build of the result.
				var t = System.nanoTime()
				val layer = io.github.psd2live.core.LayerImport.placedLayer(io.github.psd2live.core.LayerImport.decodeRasterFile(file.toFile(), {}),
					capture.document.source.widthPx, capture.document.source.heightPx, "probe", "import:probe", checkCancelled = {})
				report.appendLine("  decode+place: %.0f ms".format(since(t)))
				val all = capture.document.source.layers + layer
				val document = capture.document.copy(source = io.github.psd2live.project.WorkspaceSourceArt(capture.document.source.widthPx,
					capture.document.source.heightPx, all.mapIndexed { i, l -> io.github.psd2live.project.WorkspaceSourceLayer.copyOf(l, all.lastIndex - i) },
					capture.document.source.groups))
				t = System.nanoTime()
				io.github.psd2live.core.RigBuildProfile.reset(); io.github.psd2live.core.RigBuildProfile.recording = true
				val materialized = WorkspaceLayerInsertionEdits.materialize(document, capture.model, setOf("import:probe"), null) {}
				io.github.psd2live.core.RigBuildProfile.recording = false
				io.github.psd2live.core.RigBuildProfile.snapshot().entries.sortedByDescending { it.value.first }.take(6).forEach { (name, value) ->
					report.appendLine("    %-48s %7.0f ms x%d".format(name, value.first, value.second))
				}
				report.appendLine("  materialize: %.0f ms".format(since(t)))
				t = System.nanoTime()
				io.github.psd2live.core.RigBuildProfile.reset(); io.github.psd2live.core.RigBuildProfile.recording = true
				builder.build(materialized, capture.model)
				io.github.psd2live.core.RigBuildProfile.recording = false
				report.appendLine("  build: %.0f ms".format(since(t)))
				io.github.psd2live.core.RigBuildProfile.snapshot().entries.sortedByDescending { it.value.first }.take(12).forEach { (name, value) ->
					report.appendLine("    %-48s %7.0f ms x%d".format(name, value.first, value.second))
				}
				t = System.nanoTime()
				builder.build(materialized, capture.model)
				report.appendLine("  build again: %.0f ms".format(since(t)))
			}
			start = System.nanoTime()
			val id = WorkspaceImageLayerCommands(runtime).importImages(capture.projectId, capture.state, listOf(file), null, "Import", MutationAuthor.USER)
				.mutation.affectedLayerIds.single()
			report.appendLine("import 1024x512: %.0f ms".format(since(start)))
			suspend fun edit(label: String, operation: WorkspaceDocumentOperation) {
				capture = runtime.capture()
				val begin = System.nanoTime()
				commands.execute(capture.projectId, capture.state, label, listOf(operation), MutationAuthor.USER)
				report.appendLine("$label: %.0f ms".format(since(begin)))
			}
			repeat(5) { round ->
				edit("move ${round + 1}", WorkspaceDocumentOperation(WorkspaceLayerTransform.OP, buildJsonObject {
					put("layer_id", id); putJsonArray("translate") { add(3); add(-2) }; put("scale", 0.97)
				}))
			}
			val model = runtime.capture().model
			val mesh = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == id }.id.raw
			val parent = model.rig.puppet.deformers.firstOrNull { it.id.raw == "DeformHeadRotation" }?.id?.raw
			if (parent != null) {
				val edit = WorkspaceHierarchyEdits.reparent(model.rig.puppet, mesh, parent)
				if (edit != null) {
					capture = runtime.capture()
					start = System.nanoTime()
					commands.executeJournal(capture.projectId, capture.state, "Reparent", WorkspaceHierarchyEdits.journal(edit), MutationAuthor.USER)
					report.appendLine("reparent under $parent: %.0f ms".format(since(start)))
					edit("move under parent", WorkspaceDocumentOperation(WorkspaceLayerTransform.OP, buildJsonObject {
						put("layer_id", id); put("rotate", 10)
					}))
				}
			}
			edit("rebuild mesh", WorkspaceDocumentOperation(WorkspaceRasterEdits.REBUILD_MESH, buildJsonObject { put("layer_id", id) }))
			capture = runtime.capture()
			start = System.nanoTime()
			WorkspaceLayerCommands(runtime).execute(capture.projectId, capture.state, WorkspaceDocumentOperation(WorkspaceLayerEdits.DELETE,
				buildJsonObject { put("layer_id", id) }), "Delete", MutationAuthor.USER)
			report.appendLine("delete: %.0f ms".format(since(start)))
		}
		out.resolve("report.txt").writeText(report.toString())
		println(report)
	}
}
