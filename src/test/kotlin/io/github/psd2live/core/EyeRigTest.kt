package io.github.psd2live.core

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
	fun `blink drives iris only through jelly physics and hides it with the eye-white mask`() {
		val art = object : SourceArt {
			override val widthPx = 96
			override val heightPx = 64
			override val layers = listOf(
				testLayer("irides-l", 0, rectangleRaster(96, 64, 40, 22, 56, 42)),
				testLayer("eyewhite-l", 1, rectangleRaster(96, 64, 22, 18, 74, 46)),
			)
		}
		val preview = PSD2LivePipeline().buildPreview(
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

		assertEquals(listOf(StandardParameters.EYE_BALL_FORM), geometry.axes.map { it.parameterId })
		assertTrue(geometry.axes.none { it.parameterId in setOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN) })
		assertTrue(geometry.cells.single { it.coordinate.contentEquals(intArrayOf(1)) }.form.positionDeltas.all { it == 0f })
		assertEquals(listOf(eyeWhite.id), iris.maskedBy)
		val physics = assertNotNull(
			org.umamo.format.moc3.Moc3.readPhysics3(
				preview.runtimeBundle.assets.single { it.path.endsWith(".physics3.json") }.bytes.decodeToString(),
			),
		)
		assertTrue(physics.physicsSettings.any { it.id == "PhysicsEyeJelly" })
	}

	@Test
	fun `iris jelly keyform squashes and stretches around a fixed centre`() {
		val pivotX = 50f
		val pivotY = 30f
		val top = RigBuilder.irisJellyPoint(50f, 20f, pivotX, pivotY, 1f)
		val right = RigBuilder.irisJellyPoint(60f, 30f, pivotX, pivotY, 1f)
		val neutral = RigBuilder.irisJellyPoint(60f, 20f, pivotX, pivotY, 0f)
		assertEquals(18.9f, top.second, 1e-5f, "positive rebound should stretch vertically")
		assertEquals(59.55f, right.first, 1e-5f, "vertical stretch should narrow slightly")
		assertEquals(60f to 20f, neutral)
	}

	@Test
	fun `software eye jelly rebounds after a blink and settles`() {
		val dynamics = EyeJellyDynamics()
		val samples = mutableListOf<Float>()
		for (step in 0..5) samples += dynamics.advance(1f - step / 5f, 1f / 60f, true)
		for (step in 0..5) samples += dynamics.advance(step / 5f, 1f / 60f, true)
		repeat(120) { samples += dynamics.advance(1f, 1f / 60f, true) }
		assertTrue(samples.min() < -0.15f, "closing should squash the pupil")
		assertTrue(samples.max() > 0.08f, "opening should overshoot and rebound")
		assertEquals(0f, samples.last(), 1e-4f, "the pupil must settle back to its authored shape")
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
