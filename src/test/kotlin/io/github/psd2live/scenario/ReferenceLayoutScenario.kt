package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceMaterialFiles
import io.github.psd2live.application.WorkspaceMaterialStaging
import io.github.psd2live.application.WorkspaceRenderSession
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Laying parts out against a reference image: the reference is set over the canvas without becoming a layer, a
 * close-up cropped from it by canvas rectangle lands back on it when placed, and views draw the reference over, under
 * or against the model and tint layers by their texture density.
 */
class ReferenceLayoutScenario {
	@TempDir lateinit var temp: Path

	@Test fun partsAreCroppedFromTheReferenceAndCompared() = Studio.run(temp, Characters.figure()) {
		val files = Files.createDirectories(temp.resolve("files"))
		val disc = Characters.disc(files)
		edit("import the reference", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(disc)))))
		val item = now.document.layout.materials.items.single()
		val width = now.document.source.widthPx.toFloat(); val height = now.document.source.heightPx.toFloat()
		val layers = now.document.source.layers.size

		edit("set it as the reference", "layout_set_reference", req("material_id" to item.id, "opacity" to 0.4))
		val reference = requireNotNull(now.document.layout.reference)
		expect("reference", now.document.source.layers.size == layers) { "the reference became a layer" }
		val scale = minOf(width / item.raster.width, height / item.raster.height)
		expect("reference", reference.rect.width == item.raster.width * scale && reference.rect.left == (width - reference.rect.width) / 2f && reference.opacity == 0.4f) {
			"the reference is not fitted and centred: ${reference.rect} at ${reference.opacity}" }
		refused("the reference's material cannot be deleted", "material_delete", req("ids" to listOf(item.id)))

		// A close-up of the reference's middle, by canvas rectangle; it keeps where it sits.
		val cx = reference.rect.left + reference.rect.width / 2f; val cy = reference.rect.top + reference.rect.height / 2f
		val half = reference.rect.width / 4f
		edit("crop a close-up from it", "material_crop", req("id" to item.id, "new_id" to "close",
			"canvas_rect" to mapOf("left" to cx - half, "top" to cy - half, "width" to half * 2, "height" to half * 2)))
		val close = requireNotNull(now.document.layout.materials.item("close"))
		val place = requireNotNull(close.placement)
		val expectedWidth = (close.raster.width * scale)
		expect("crop", kotlin.math.abs(place.width - expectedWidth) < 1e-3f && kotlin.math.abs(place.left - (cx - half)) <= scale &&
			close.raster.width in (item.raster.width / 2 - 1)..(item.raster.width / 2 + 2)) { "the crop's pixels or place are off: ${close.raster.width} at $place" }
		val sx = (place.left - reference.rect.left) / scale; val sy = (place.top - reference.rect.top) / scale
		val rowFrom = (sy.toInt() + close.raster.height / 2) * item.raster.width + sx.toInt()
		expect("crop", close.raster.rgba.copyOfRange(close.raster.height / 2 * close.raster.width * 4, (close.raster.height / 2 * close.raster.width + close.raster.width) * 4)
			.contentEquals(item.raster.rgba.copyOfRange(rowFrom * 4, (rowFrom + close.raster.width) * 4))) { "the crop is not the reference's own pixels" }

		edit("place the close-up", "layer_place", req("material_id" to "close", "layer_id" to "close"))
		val placed = now.document.source.layers.single { it.id.raw == "close" }
		expect("placed", placed.canvasRect() == place) { "the close-up did not land on the reference: ${placed.canvasRect()} vs $place" }

		val render = WorkspaceRenderSession(runtime.read(), { it })
		val frame = WorkspaceViewFrame.CanvasRect(io.github.psd2live.core.Bounds(0f, 0f, width, height))
		fun view(reference: WorkspaceViewReference?, density: Boolean = false) = runBlocking {
			render.renderModel(WorkspaceModelViewRequest(includeLayerIds = setOf("close"), frame = frame, reference = reference, density = density,
				output = WorkspaceViewOutputSpec(targetLongEdge = 256)))
		}
		val difference = view(WorkspaceViewReference(WorkspaceViewReferenceMode.DIFFERENCE))
		val over = view(WorkspaceViewReference(WorkspaceViewReferenceMode.OVER))
		val plain = view(null)
		expect("views", difference.referenceMode == WorkspaceViewReferenceMode.DIFFERENCE && difference.referenceDifference != null) { "a difference view gave no difference" }
		expect("views", !over.png.contentEquals(plain.png) && over.referenceDifference == null) { "the reference was not drawn over the model" }
		// The placed close-up waits for generation and ignores the head, so these draw the same pixels as different views.
		val turned = runBlocking { render.renderModel(WorkspaceModelViewRequest(includeLayerIds = setOf("close"), frame = frame,
			parameters = mapOf(now.model.rig.puppet.parameters.first().id.raw to 10f), output = WorkspaceViewOutputSpec(targetLongEdge = 256))) }
		expect("views", turned.png.contentEquals(plain.png) && turned.viewId != plain.viewId) { "views of different requests share an ID" }
		val density = view(null, density = true)
		val texels = density.texelDensity["close"]
		expect("density", texels != null && kotlin.math.abs(texels - 1f / scale) < 0.02f) { "the close-up's density is ${density.texelDensity}, not ${1f / scale}" }

		edit("hide and fade it", "layout_set_reference", req("visible" to false, "opacity" to 0.8))
		expect("reference", now.document.layout.reference?.let { !it.visible && it.opacity == 0.8f && it.rect == reference.rect } == true) { "the change lost the rest" }
		reopen()
		expect("saved", now.document.layout.reference?.materialId == item.id && now.document.layout.reference?.opacity == 0.8f) { "the reference did not survive saving" }
		edit("clear it", "layout_set_reference", req("material_id" to null))
		expect("cleared", now.document.layout.reference == null) { "the reference is still set" }
	}

	/**
	 * A part peeled off an image with a version that has it removed, as a generator gives it: the generator shifts every
	 * colour a little, yet only the part's area is taken from it, matched back to the image's colours, and the part over
	 * the image under it gives the image again. Both land where the image sits.
	 */
	@Test fun aPartIsPeeledOffAnImage() = Studio.run(temp, Characters.figure()) {
		val files = Files.createDirectories(temp.resolve("peel"))
		val size = 64
		fun ground(x: Int, y: Int) = intArrayOf(180 + x / 4, 170 + y / 4, 200)
		fun disc(x: Int, y: Int) = (x - 32) * (x - 32) + (y - 32) * (y - 32) <= 100
		fun png(name: String, pixel: (Int, Int) -> IntArray?): Path {
			val rgba = ByteArray(size * size * 4)
			for (y in 0 until size) for (x in 0 until size) {
				val c = pixel(x, y) ?: continue
				val o = (y * size + x) * 4
				for (k in 0..2) rgba[o + k] = c[k].coerceIn(0, 255).toByte()
				rgba[o + 3] = -1
			}
			return files.resolve(name).also { Files.write(it, org.umamo.format.png.PngCodec.write(org.umamo.format.raster.RasterImage(size, size, rgba))) }
		}
		val before = png("before.png") { x, y -> if (disc(x, y)) intArrayOf(200, 30, 40) else ground(x, y) }
		val revealed = png("revealed.png") { x, y -> ground(x, y).map { it - 9 }.toIntArray() }
		edit("import the image and its generated version", "material_add",
			req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(before, revealed)))))
		val (image, generated) = now.document.layout.materials.items.sortedBy { it.name }
		val place = mapOf("left" to 10f, "top" to 20f, "width" to 32f, "height" to 32f)
		edit("place the image", "material_set_placement", req("id" to image.id, "canvas_rect" to place))
		edit("the generated version takes its place", "material_set_placement", req("id" to generated.id, "from" to image.id))
		expect("placement", now.document.layout.materials.item(generated.id)?.placement == io.github.psd2live.project.LayerCanvasRect(10f, 20f, 32f, 32f)) {
			"from did not copy the place" }
		refused("an image unlike itself has nothing to peel", "material_peel", req("id" to image.id, "revealed" to image.id))

		edit("peel the disc off", "material_peel", req("id" to image.id, "revealed" to generated.id, "name" to "disc", "part_id" to "part", "base_id" to "base"))
		val part = requireNotNull(now.document.layout.materials.item("part")).raster
		val base = requireNotNull(now.document.layout.materials.item("base")).raster
		val src = requireNotNull(now.document.layout.materials.item(image.id)).raster.rgba
		fun at(r: org.umamo.format.art.LayerRaster, x: Int, y: Int, c: Int) = r.rgba[(y * size + x) * 4 + c].toInt() and 255
		expect("part", at(part, 32, 32, 3) == 255 && at(part, 32, 32, 0) == 200 && at(part, 2, 2, 3) == 0 && at(part, 50, 32, 3) == 0) {
			"the part is not the disc: centre alpha ${at(part, 32, 32, 3)}, outside ${at(part, 2, 2, 3)}" }
		var outside = 0; var worst = 0; var recomposed = 0
		for (y in 0 until size) for (x in 0 until size) {
			val o = (y * size + x) * 4
			val a = at(part, x, y, 3) / 255f
			for (k in 0..2) {
				val b = at(base, x, y, k); val s = src[o + k].toInt() and 255
				if (a == 0f && b != s) outside++
				if (disc(x, y)) worst = maxOf(worst, kotlin.math.abs(b - ground(x, y)[k]))
				recomposed = maxOf(recomposed, kotlin.math.abs((at(part, x, y, k) * a + b * (1 - a)).toInt() - s))
			}
		}
		expect("base", outside == 0) { "$outside channel(s) outside the part changed" }
		expect("base", worst <= 3) { "the area under the disc is $worst off the image's colours: the generator's shift was not matched" }
		expect("recompose", recomposed <= 2) { "the part over the image under it is $recomposed off the image" }
		expect("placement", now.document.layout.materials.item("part")?.placement == image.copy().let { now.document.layout.materials.item(image.id)?.placement }) {
			"the part does not sit where the image does" }

		// A generator also changes a speck far from the part; keep points hold the part to the piece they touch.
		fun speck(x: Int, y: Int) = x in 4..9 && y in 50..55
		val specked = png("specked.png") { x, y -> if (speck(x, y)) intArrayOf(20, 20, 20) else ground(x, y).map { it - 9 }.toIntArray() }
		edit("import the specked version", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(specked)))))
		val speckedId = now.document.layout.materials.items.single { it.name == "specked" }.id
		refused("a keep point away from the part", "material_peel", req("id" to image.id, "revealed" to speckedId, "keep" to listOf(listOf(60, 2))))
		edit("peel the disc, kept by a point on it", "material_peel", req("id" to image.id, "revealed" to speckedId, "keep" to listOf(listOf(32, 32)),
			"part_id" to "kept", "base_id" to "kept_base"))
		val kept = requireNotNull(now.document.layout.materials.item("kept")).raster
		val keptBase = requireNotNull(now.document.layout.materials.item("kept_base")).raster
		expect("keep", at(kept, 32, 32, 3) == 255 && at(kept, 6, 52, 3) == 0 && at(keptBase, 6, 52, 0) == src[(52 * size + 6) * 4].toInt() and 255) {
			"the speck was not left out: part alpha ${at(kept, 6, 52, 3)} there" }

		// An output enlarged from a smaller size differs from the image pixel by pixel (here a checker of +-40); measured
		// at the size it was made, only the removed disc remains.
		val enlarged = png("enlarged.png") { x, y -> ground(x, y).map { it - 9 + if ((x + y) % 2 == 0) 40 else -40 }.toIntArray() }
		edit("import the enlarged version", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(enlarged)))))
		val enlargedId = now.document.layout.materials.items.single { it.name == "enlarged" }.id
		edit("peel compared at half size", "material_peel", req("id" to image.id, "revealed" to enlargedId, "compare_scale" to 2,
			"part_id" to "halved", "base_id" to "halved_base"))
		val halved = requireNotNull(now.document.layout.materials.item("halved")).raster
		expect("compare scale", at(halved, 32, 32, 3) == 255 && at(halved, 52, 10, 3) == 0) {
			"the enlargement read as the part: alpha ${at(halved, 52, 10, 3)} far from the disc" }
		reopen()
		expect("saved", now.document.layout.materials.item("base") != null) { "the peel did not survive saving" }
	}
}
