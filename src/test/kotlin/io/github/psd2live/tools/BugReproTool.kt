package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.canvasRect
import io.github.psd2live.project.storedCanvasRect
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.Test

/** Temporary diagnosis: PSD2LIVE_PROJECT names a .psd2live; replays skeleton auto and a mesh-rebuilding repaint. */
class BugReproTool {
	@Test fun repro() = kotlinx.coroutines.runBlocking {
		requireTools()
		val path = Path.of(System.getenv("PSD2LIVE_PROJECT"))
		ProjectRepository().open(path).use { opened ->
			val document = opened.history.head().snapshot
			val journal = document.rigEdits.authoringJournal
			println("journal ${journal.size}: " + journal.groupingBy { it["op"]?.jsonPrimitive?.content }.eachCount())
			println("skeleton: ${document.rigEdits.skeleton?.let { "enabled=${it.enabled} bones=${it.bones.size}" }}")
			journal.forEachIndexed { i, r ->
				if (r.toString().contains("Handwear")) println("  #$i ${r["op"]} id=${r["id"]} keys=${r.keys}")
			}
			val builder = WorkspacePreviewBuilder()
			var t = System.nanoTime()
			val current = builder.build(document)
			println("build ${(System.nanoTime() - t) / 1e6} ms; drawables ${current.rig.puppet.drawables.size}")
			fun attempt(name: String, edit: (io.github.psd2live.project.WorkspaceDocument) -> io.github.psd2live.project.WorkspaceDocument) {
				t = System.nanoTime()
				runCatching {
					kotlinx.coroutines.runBlocking {
						val candidate = edit(document)
						val normalized = builder.normalizeMeshEdits(candidate, current)
						builder.build(normalized, current)
						normalized
					}
				}.onSuccess { println("$name OK ${(System.nanoTime() - t) / 1e6} ms, journal ${it.rigEdits.authoringJournal.size}") }
				 .onFailure { println("$name FAILED ${(System.nanoTime() - t) / 1e6} ms: $it"); it.printStackTrace(System.out) }
			}
			attempt("skeleton_auto") { WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation("skeleton_auto", buildJsonObject {}), it, current) }
			for (target in (System.getenv("PSD2LIVE_MESHES") ?: "ArtMeshHandwearR3,ArtMeshHandwearR2").split(",")) {
				val layerId = current.rig.layerIdByDrawableId[target] ?: run { println("no layer for $target"); continue }
				val layer = document.source.layers.single { it.id.raw == layerId }
				println("$target -> layer $layerId bounds=${layer.bounds} raster=${layer.raster.width}x${layer.raster.height} stored=${layer.storedCanvasRect}")
				val erased = org.umamo.format.art.LayerRaster(layer.raster.width, layer.raster.height, layer.raster.rgba.copyOf().also { rgba ->
					for (y in layer.raster.height * 3 / 4 until layer.raster.height) for (x in 0 until layer.raster.width) rgba[(y * layer.raster.width + x) * 4 + 3] = 0
				})
				val before = current.rig.puppet.drawables.single { it.id.raw == target }.mesh!!
				for (rebuild in listOf(true, false)) {
					var committed: io.github.psd2live.project.WorkspaceDocument? = null; var ok = false
					attempt("erase rebuild=$rebuild $target") {
						WorkspaceRasterEdits.prepare(it, current, WorkspacePaintRaster(layerId, erased, rebuildMesh = rebuild, rect = layer.canvasRect())).also { committed = it }
					}
					runCatching { committed?.let { doc ->
						val after = builder.build(doc, current).rig.puppet.drawables.single { it.id.raw == target }.mesh!!
						println("  mesh $target vertices ${before.vertexCount} -> ${after.vertexCount}, same=${before.positions.contentEquals(after.positions)}; meshSource layer present=${doc.meshSource?.layers?.any { it.id.raw == layerId }} generationSource has layer=${doc.generationSource?.layers?.any { it.id.raw == layerId }}")
					} }.onFailure { println("  rebuilt candidate failed: $it") }
				}
			}
		}
	}
}

