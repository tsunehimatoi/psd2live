package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspacePartitionEdits
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.ArtPrimitiveJournal
import io.github.psd2live.core.ArtPrimitiveV2
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigCheckpoint
import io.github.psd2live.core.RigPreviewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * Before/after images of materialized splits on tml: the legs split per side, the left eyelash split by a polygon
 * (open and closed) and the mouth split by a polygon (closed and open). Each sheet shows the unsplit rig, the split -
 * a version 2 record and the checkpoint its regeneration merge writes - at the same poses and their difference (x4),
 * for checking seams, misplaced parts and missing pixels. Writes
 * build/tools/art-primitive-v2/<case>.png. PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ArtPrimitiveV2VisualTool*'
 */
class ArtPrimitiveV2VisualTool {
	private class Case(val name: String, val rect: Bounds, val poses: List<Pair<String, Map<String, Float>>>, val split: (RigPreviewModel) -> WorkspaceDocumentOperation)

	private fun polygon(layer: String, x: Int) = WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
		put("layer_id", layer); put("names", JsonArray(listOf("A", "B").map(::JsonPrimitive))); put("piece_ids", JsonArray(listOf("$layer-a", "$layer-b").map(::JsonPrimitive)))
		putJsonArray("polygon") { listOf(0 to 0, x to 0, x to 2100, 0 to 2100).forEach { (px, py) -> add(buildJsonArray { add(px); add(py) }) } }
	})

	private val cases = listOf(
		Case("legs", Bounds(820f, 1360f, 1220f, 2030f), listOf("rest" to emptyMap(), "body x 10" to mapOf("ParamBodyAngleX" to 10f),
			"angle x -30" to mapOf("ParamAngleX" to -30f, "ParamBodyAngleX" to -10f))) {
			WorkspaceDocumentOperation("source_split_components", buildJsonObject {
				put("layer_id", "lyid:4"); put("names", JsonArray(listOf("Leg L", "Leg R").map(::JsonPrimitive)))
				put("sides", JsonArray(listOf("left", "right").map(::JsonPrimitive)))
			})
		},
		Case("eye", Bounds(1040f, 318f, 1160f, 412f), listOf("open" to mapOf("ParamEyeLOpen" to 1f), "half" to mapOf("ParamEyeLOpen" to 0.5f),
			"closed" to mapOf("ParamEyeLOpen" to 0f), "closed, turned" to mapOf("ParamEyeLOpen" to 0f, "ParamAngleX" to 25f))) { polygon("lyid:19", 1102) },
		Case("mouth", Bounds(975f, 430f, 1067f, 508f), listOf("closed" to mapOf("ParamMouthOpenY" to 0f), "open" to mapOf("ParamMouthOpenY" to 1f),
			"open smile" to mapOf("ParamMouthOpenY" to 1f, "ParamMouthForm" to 1f), "open, turned" to mapOf("ParamMouthOpenY" to 1f, "ParamAngleX" to 25f))) { polygon("lyid:29", 1021) },
	)

	@Test fun render() = runBlocking<Unit> {
		requireTools()
		val builder = WorkspacePreviewBuilder()
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
		WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
		val start = runtime.capture()
		val out = output("art-primitive-v2")
		for (case in cases) {
			val document = runBlocking { WorkspacePartitionEdits.apply(case.split(start.model), start.document, start.model) }
			val record = ArtPrimitiveJournal.commands(document.rigEdits).single()
			check(ArtPrimitiveV2.isV2(record)) { "${case.name}: wrote ${record["v"]}" }
			check(RigCheckpoint.isRecord(document.rigEdits.authoringJournal.last())) { "${case.name}: no checkpoint after the split" }
			val split = runBlocking { builder.build(document) }
			val frames = ArrayList<Pair<String, BufferedImage>>()
			for ((label, pose) in case.poses) {
				val images = listOf(start.model, split).map { Renderer(it, 360).render(pose, case.rect) }
				frames += "unsplit $label" to images[0]; frames += "split $label" to images[1]
				frames += "|unsplit-split| x4 $label" to difference(images[0], images[1])
				println("${case.name} $label: max channel difference unsplit/split ${maxDifference(images[0], images[1])}")
			}
			sheet(frames, File(out, "${case.name}.png"), columns = 3)
		}
	}

	private fun difference(a: BufferedImage, b: BufferedImage): BufferedImage {
		val image = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_RGB)
		for (y in 0 until a.height) for (x in 0 until a.width) {
			val p = a.getRGB(x, y); val q = b.getRGB(x, y)
			fun channel(shift: Int) = (abs((p shr shift and 0xff) - (q shr shift and 0xff)) * 4).coerceAtMost(255)
			image.setRGB(x, y, (channel(16) shl 16) or (channel(8) shl 8) or channel(0))
		}
		return image
	}

	private fun maxDifference(a: BufferedImage, b: BufferedImage): Int {
		var worst = 0
		for (y in 0 until a.height) for (x in 0 until a.width) {
			val p = a.getRGB(x, y); val q = b.getRGB(x, y)
			for (shift in listOf(0, 8, 16)) worst = maxOf(worst, abs((p shr shift and 0xff) - (q shr shift and 0xff)))
		}
		return worst
	}
}
