package io.github.psd2live.core

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MouthRigTest {
	@Test
	fun `flattened mouth stays one drawable and closes from the authored open pose`() {
		val mouth = layer("mouth", order = 0, rgba = mouthRaster())
		val art = artOf(mouth)
		val preview = PSD2LivePipeline().buildPreview(art, PipelineConfig(atlasSize = 256, meshSpacing = 12))
		assertEquals(listOf(SemanticTag.MOUTH), preview.analysis.layers.map { it.semantic.tag })
		assertEquals(1, preview.rig.puppet.drawables.size, "mouth pixels must not be auto-split")

		val drawable = preview.rig.puppet.drawables.single()
		val mesh = assertNotNull(drawable.mesh)
		val geometry = assertNotNull(drawable.geometryGrid)
		assertEquals(listOf(StandardParameters.MOUTH_FORM, StandardParameters.MOUTH_OPEN), geometry.axes.map { it.parameterId })
		fun verticalSpan(coordinate: IntArray): Float {
			val delta = geometry.cells.single { it.coordinate.contentEquals(coordinate) }.form.positionDeltas
			val ys = mesh.positions.indices.filter { it % 2 == 1 }.map { mesh.positions[it] + delta[it] }
			return ys.max() - ys.min()
		}
		val closedSpan = verticalSpan(intArrayOf(1, 0))
		val openSpan = verticalSpan(intArrayOf(1, 2))
		assertTrue(closedSpan < openSpan * 0.08f, "closed=$closedSpan open=$openSpan")
		val openDelta = geometry.cells.single { it.coordinate.contentEquals(intArrayOf(1, 2)) }.form.positionDeltas
		assertTrue(openDelta.all { kotlin.math.abs(it) < 1e-6f }, "OpenY=1 must preserve the source bitmap")
	}

	@Test
	fun `explicit tooth and tongue layers are clipped ordered and minimally deformed`() {
		val source = artOf(
			// Deliberately put the mouth first in PSD order; rig ordering must still place internals above it.
			layer("mouth", order = 0, rgba = mouthRaster()),
			layer("tongue", order = 1, rgba = ellipseRaster(238, 104, 132, 40f, 34f, 22f, 11f)),
			layer("tooth-b", order = 2, rgba = ellipseRaster(250, 247, 242, 40f, 38f, 14f, 5f)),
			layer("tooth-t", order = 3, rgba = ellipseRaster(250, 247, 242, 40f, 13f, 19f, 8f)),
		)
		val preview = PSD2LivePipeline().buildPreview(source, PipelineConfig(atlasSize = 256, meshSpacing = 12))
		assertEquals(
			setOf(SemanticTag.MOUTH, SemanticTag.TONGUE, SemanticTag.TOOTH_B, SemanticTag.TOOTH_T),
			preview.analysis.layers.map { it.semantic.tag }.toSet(),
		)
		val tagByLayerId = preview.analysis.layers.associate { it.source.id.raw to it.semantic.tag }
		val drawableByTag = preview.rig.puppet.drawables.associateBy { drawable ->
			tagByLayerId.getValue(preview.rig.layerIdByDrawableId.getValue(drawable.id.raw))
		}
		val mouth = assertNotNull(drawableByTag[SemanticTag.MOUTH])
		val tongue = assertNotNull(drawableByTag[SemanticTag.TONGUE])
		val upper = assertNotNull(drawableByTag[SemanticTag.TOOTH_T])
		val lower = assertNotNull(drawableByTag[SemanticTag.TOOTH_B])
		for (internal in listOf(tongue, upper, lower)) {
			assertEquals(listOf(mouth.id), internal.maskedBy)
			val geometry = assertNotNull(internal.geometryGrid)
			assertTrue(geometry.axes.isEmpty(), "${internal.name} should not receive a complex mouth morph")
			assertTrue(geometry.cells.single().form.positionDeltas.all { it == 0f })
		}
		assertTrue(upper.drawOrder > tongue.drawOrder)
		assertTrue(lower.drawOrder > tongue.drawOrder)
		assertTrue(tongue.drawOrder > mouth.drawOrder)

		val opacity = assertNotNull(tongue.channelGrids[FormChannel.OPACITY])
		val closedOpacity = opacity.cells.single { it.coordinate.contentEquals(intArrayOf(0)) }.form as ChannelValue.Scalar
		val openOpacity = opacity.cells.single { it.coordinate.contentEquals(intArrayOf(2)) }.form as ChannelValue.Scalar
		assertEquals(0f, closedOpacity.value)
		assertEquals(1f, openOpacity.value)
	}

	@Test
	fun `recognizes the explicit mouth internal naming contract`() {
		assertEquals(SemanticTag.TOOTH_T, LayerClassifier.classify("tooth-t").tag)
		assertEquals(SemanticTag.TOOTH_B, LayerClassifier.classify("tooth-b").tag)
		assertEquals(SemanticTag.TONGUE, LayerClassifier.classify("tongue").tag)
	}

	private fun mouthRaster(): ByteArray {
		val rgba = ByteArray(WIDTH * HEIGHT * 4)
		for (y in 2 until HEIGHT - 1) for (x in 2 until WIDTH - 1) {
			val dx = (x - 40f) / 36f
			val dy = (y - 24f) / 21f
			if (dx * dx + dy * dy <= 1f) setPixel(rgba, x, y, 112, 26, 54)
		}
		return rgba
	}

	private fun ellipseRaster(r: Int, g: Int, b: Int, centerX: Float, centerY: Float, radiusX: Float, radiusY: Float): ByteArray {
		val rgba = ByteArray(WIDTH * HEIGHT * 4)
		for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
			val dx = (x - centerX) / radiusX
			val dy = (y - centerY) / radiusY
			if (dx.pow(2) + dy.pow(2) <= 1f) setPixel(rgba, x, y, r, g, b)
		}
		return rgba
	}

	private fun setPixel(rgba: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
		val offset = (y * WIDTH + x) * 4
		rgba[offset] = r.toByte()
		rgba[offset + 1] = g.toByte()
		rgba[offset + 2] = b.toByte()
		rgba[offset + 3] = 0xff.toByte()
	}

	private fun artOf(vararg layers: SourceLayer) = object : SourceArt {
		override val layers = layers.toList()
		override val widthPx = WIDTH
		override val heightPx = HEIGHT
	}

	private fun layer(name: String, order: Int, rgba: ByteArray) = object : SourceLayer {
		override val id = LayerId(name)
		override val name = name
		override val groupPath = ""
		override val order = order
		override val bounds = LayerBounds(0, 0, WIDTH, HEIGHT)
		override val opacity = 1f
		override val clipped = false
		override val blend = LayerBlend.Normal
		override val raster = LayerRaster(WIDTH, HEIGHT, rgba)
	}

	private companion object {
		const val WIDTH = 80
		const val HEIGHT = 48
	}
}
