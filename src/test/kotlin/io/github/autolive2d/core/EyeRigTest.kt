package io.github.autolive2d.core

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EyeRigTest {
	@Test
	fun `closed eyelash bends into a U around the eye-white centre line`() {
		val white = Bounds(20f, 30f, 80f, 50f)
		val lash = Bounds(17f, 21f, 83f, 45f)

		val left = RigBuilder.eyeClosurePoint(white.left, lash.centerY, lash, white, SemanticTag.EYELASH)
		val centre = RigBuilder.eyeClosurePoint(white.centerX, lash.centerY, lash, white, SemanticTag.EYELASH)
		val right = RigBuilder.eyeClosurePoint(white.right, lash.centerY, lash, white, SemanticTag.EYELASH)

		assertEquals(white.centerX, centre.first)
		assertTrue(centre.second > left.second + white.height * 0.20f, "the lash centre must bend downward")
		assertEquals(left.second, right.second, 1e-5f, "the closed U must stay symmetric")
	}

	@Test
	fun `eyelash trough covers the compressed eye white with only slight thinning`() {
		val white = Bounds(20f, 30f, 80f, 50f)
		val lash = Bounds(17f, 21f, 83f, 45f)
		val lashCentre = RigBuilder.eyeClosurePoint(white.centerX, lash.centerY, lash, white, SemanticTag.EYELASH)
		val whiteCentre = RigBuilder.eyeClosurePoint(white.centerX, white.centerY, white, white, SemanticTag.EYEWHITE)
		assertEquals(whiteCentre.second, lashCentre.second, 1e-5f, "lash and eye white must share the trough")

		val lashTop = RigBuilder.eyeClosurePoint(white.centerX, lash.top, lash, white, SemanticTag.EYELASH)
		val lashBottom = RigBuilder.eyeClosurePoint(white.centerX, lash.bottom, lash, white, SemanticTag.EYELASH)
		val whiteTop = RigBuilder.eyeClosurePoint(white.centerX, white.top, white, white, SemanticTag.EYEWHITE)
		val whiteBottom = RigBuilder.eyeClosurePoint(white.centerX, white.bottom, white, white, SemanticTag.EYEWHITE)
		val lashThickness = lashBottom.second - lashTop.second
		val whiteThickness = whiteBottom.second - whiteTop.second
		assertEquals(lash.height * 0.88f, lashThickness, 1e-5f, "the lash should become only slightly thinner")
		assertTrue(lashThickness > whiteThickness, "the lash must cover the compressed eye white")
	}

	@Test
	fun `blink keeps iris geometry unchanged and hides it with the eye-white mask`() {
		val art = object : SourceArt {
			override val widthPx = 96
			override val heightPx = 64
			override val layers = listOf(
				testLayer("irides-l", 0, rectangleRaster(96, 64, 40, 22, 56, 42)),
				testLayer("eyewhite-l", 1, rectangleRaster(96, 64, 22, 18, 74, 46)),
			)
		}
		val preview = AutoLive2DPipeline().buildPreview(
			art,
			PipelineConfig(atlasSize = 256, meshSpacing = 12),
		)
		val tagByLayerId = preview.analysis.layers.associate { it.source.id.raw to it.semantic.tag }
		val drawableByTag = preview.rig.puppet.drawables.associateBy { drawable ->
			tagByLayerId.getValue(preview.rig.layerIdByDrawableId.getValue(drawable.id.raw))
		}
		val iris = assertNotNull(drawableByTag[SemanticTag.IRIDES])
		val eyeWhite = assertNotNull(drawableByTag[SemanticTag.EYEWHITE])
		val geometry = assertNotNull(iris.geometryGrid)

		assertTrue(geometry.axes.isEmpty(), "blink parameters must not deform the pupil")
		assertTrue(geometry.cells.single().form.positionDeltas.all { it == 0f })
		assertEquals(listOf(eyeWhite.id), iris.maskedBy)
	}

	private fun rectangleRaster(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): ByteArray {
		val rgba = ByteArray(width * height * 4)
		for (y in top until bottom) for (x in left until right) {
			val offset = (y * width + x) * 4
			rgba[offset] = 0x30
			rgba[offset + 1] = 0x40
			rgba[offset + 2] = 0x50
			rgba[offset + 3] = 0xff.toByte()
		}
		return rgba
	}

	private fun testLayer(name: String, order: Int, rgba: ByteArray) = object : SourceLayer {
		override val id = LayerId(name)
		override val name = name
		override val groupPath = ""
		override val order = order
		override val bounds = LayerBounds(0, 0, 96, 64)
		override val opacity = 1f
		override val clipped = false
		override val blend = LayerBlend.Normal
		override val raster = LayerRaster(96, 64, rgba)
	}
}
