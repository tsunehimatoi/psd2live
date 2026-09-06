package io.github.psd2live.core

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.render.eval.CpuDeformationEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SymmetricPairDeformerTest {

	@Test
	fun `creates common parent deformers for bilateral eyes, brows, and ears`() {
		val art = object : SourceArt {
			override val widthPx = 200
			override val heightPx = 200
			override val layers = listOf(
				boxLayer("face", 0, 30, 30, 170, 170),
				boxLayer("eyewhite-l", 1, 110, 60, 140, 80),
				boxLayer("eyewhite-r", 2, 60, 60, 90, 80),
				boxLayer("eyebrow-l", 3, 110, 50, 140, 55),
				boxLayer("eyebrow-r", 4, 60, 50, 90, 55),
				boxLayer("ears-l", 5, 160, 65, 180, 95),
				boxLayer("ears-r", 6, 20, 65, 40, 95),
			)
		}

		val preview = PSD2LivePipeline().buildPreview(
			art,
			PipelineConfig(atlasSize = 512, meshSpacing = 16),
		)
		val deformers = preview.rig.puppet.deformers.associateBy { it.id.raw }

		// DeformEyes should exist and parent both DeformEyeShapeL and DeformEyeShapeR
		val deformEyes = assertNotNull(deformers["DeformEyes"])
		val eyeShapeL = assertNotNull(deformers["DeformEyeShapeL"])
		val eyeShapeR = assertNotNull(deformers["DeformEyeShapeR"])
		assertEquals(deformEyes.id, eyeShapeL.parent)
		assertEquals(deformEyes.id, eyeShapeR.parent)

		// DeformBrows should exist and parent both DeformBrowShapeL and DeformBrowShapeR
		val deformBrows = assertNotNull(deformers["DeformBrows"])
		val browShapeL = assertNotNull(deformers["DeformBrowShapeL"])
		val browShapeR = assertNotNull(deformers["DeformBrowShapeR"])
		assertEquals(deformBrows.id, browShapeL.parent)
		assertEquals(deformBrows.id, browShapeR.parent)

		// DeformEars should exist and parent both DeformEarOcclusionL and DeformEarOcclusionR
		val deformEars = assertNotNull(deformers["DeformEars"])
		val earOcclusionL = assertNotNull(deformers["DeformEarOcclusionL"])
		val earOcclusionR = assertNotNull(deformers["DeformEarOcclusionR"])
		assertEquals(deformEars.id, earOcclusionL.parent)
		assertEquals(deformEars.id, earOcclusionR.parent)

		// Eye drawables are parented to their respective EyeShape warps
		val drawablesByName = preview.rig.puppet.drawables.associateBy { it.name }
		val eyeWhiteL = assertNotNull(drawablesByName["eyewhite-l"])
		val eyeWhiteR = assertNotNull(drawablesByName["eyewhite-r"])
		assertEquals(eyeShapeL.id, eyeWhiteL.parentDeformerId)
		assertEquals(eyeShapeR.id, eyeWhiteR.parentDeformerId)
	}

	@Test
	fun `creates common parent deformers for non-facial bilateral pairs like limbs and hair`() {
		val art = object : SourceArt {
			override val widthPx = 200
			override val heightPx = 200
			override val layers = listOf(
				boxLayer("face", 0, 40, 30, 160, 150),
				boxLayer("front hair-l", 1, 105, 30, 150, 90),
				boxLayer("front hair-r", 2, 50, 30, 95, 90),
				boxLayer("arm-l", 3, 140, 110, 180, 170),
				boxLayer("arm-r", 4, 20, 110, 60, 170),
				boxLayer("shoe 1-l", 5, 110, 175, 140, 195),
				boxLayer("shoe 1-r", 6, 60, 175, 90, 195),
				boxLayer("shoe 2-l", 7, 115, 178, 135, 192),
				boxLayer("shoe 2-r", 8, 65, 178, 85, 192),
				boxLayer("ribbon-l", 9, 150, 20, 180, 50),
			)
		}

		val preview = PSD2LivePipeline().buildPreview(
			art,
			PipelineConfig(atlasSize = 512, meshSpacing = 16),
		)
		val deformers = preview.rig.puppet.deformers.associateBy { it.id.raw }
		val drawablesByName = preview.rig.puppet.drawables.associateBy { it.name }

		// Front hair pair deformer
		val pairHair = assertNotNull(deformers["DeformPair_FrontHair"])
		val hairL = assertNotNull(drawablesByName["front hair-l"])
		val hairR = assertNotNull(drawablesByName["front hair-r"])
		assertEquals(pairHair.id, hairL.parentDeformerId)
		assertEquals(pairHair.id, hairR.parentDeformerId)

		// Arm pair deformer
		val pairArm = assertNotNull(deformers["DeformPair_Arm"])
		val armL = assertNotNull(drawablesByName["arm-l"])
		val armR = assertNotNull(drawablesByName["arm-r"])
		assertEquals(pairArm.id, armL.parentDeformerId)
		assertEquals(pairArm.id, armR.parentDeformerId)

		// Distinct shoe pair deformers
		val pairShoe1 = assertNotNull(deformers["DeformPair_Shoe1"])
		val pairShoe2 = assertNotNull(deformers["DeformPair_Shoe2"])
		assertEquals(pairShoe1.id, drawablesByName["shoe 1-l"]?.parentDeformerId)
		assertEquals(pairShoe1.id, drawablesByName["shoe 1-r"]?.parentDeformerId)
		assertEquals(pairShoe2.id, drawablesByName["shoe 2-l"]?.parentDeformerId)
		assertEquals(pairShoe2.id, drawablesByName["shoe 2-r"]?.parentDeformerId)

		// Asymmetric layer (ribbon-l) should not have a pair deformer
		val ribbonL = assertNotNull(drawablesByName["ribbon-l"])
		assertTrue(deformers.none { it.key.contains("Ribbon") })
		assertEquals(deformers["DeformHeadContainer"]?.id, ribbonL.parentDeformerId)
	}

	@Test
	fun `creates pair deformer for CJK-named pairs and english pairs`() {
		val art = object : SourceArt {
			override val widthPx = 200
			override val heightPx = 200
			override val layers = listOf(
				boxLayer("face", 0, 40, 30, 160, 150),
				boxLayer("左上衣", 1, 140, 100, 175, 150),
				boxLayer("右上衣", 2, 25, 100, 60, 150),
				boxLayer("左后发", 3, 130, 40, 180, 120),
				boxLayer("右后发", 4, 20, 40, 70, 120),
				boxLayer("sleeve-l", 5, 140, 100, 175, 150),
				boxLayer("sleeve-r", 6, 25, 100, 60, 150),
			)
		}

		val preview = PSD2LivePipeline().buildPreview(
			art,
			PipelineConfig(atlasSize = 512, meshSpacing = 16),
		)
		val deformers = preview.rig.puppet.deformers.associateBy { it.id.raw }
		val drawablesByName = preview.rig.puppet.drawables.associateBy { it.name }

		// Topwear pair deformer
		val pairTopwear = assertNotNull(deformers["DeformPair_Topwear"])
		assertEquals(pairTopwear.id, drawablesByName["左上衣"]?.parentDeformerId)
		assertEquals(pairTopwear.id, drawablesByName["右上衣"]?.parentDeformerId)

		// Back hair pair deformer
		val pairBackHair = assertNotNull(deformers["DeformPair_BackHair"])
		assertEquals(pairBackHair.id, drawablesByName["左后发"]?.parentDeformerId)
		assertEquals(pairBackHair.id, drawablesByName["右后发"]?.parentDeformerId)

		// Sleeve pair deformer
		val pairSleeve = assertNotNull(deformers["DeformPair_Sleeve"])
		assertEquals(pairSleeve.id, drawablesByName["sleeve-l"]?.parentDeformerId)
		assertEquals(pairSleeve.id, drawablesByName["sleeve-r"]?.parentDeformerId)
	}

	@Test
	fun `neutral pose evaluates finite and valid coordinates on bilateral pairs`() {
		val art = object : SourceArt {
			override val widthPx = 200
			override val heightPx = 200
			override val layers = listOf(
				boxLayer("face", 0, 40, 30, 160, 150),
				boxLayer("eyewhite-l", 1, 110, 60, 140, 80),
				boxLayer("eyewhite-r", 2, 60, 60, 90, 80),
				boxLayer("front hair-l", 3, 105, 30, 150, 90),
				boxLayer("front hair-r", 4, 50, 30, 95, 90),
				boxLayer("arm-l", 5, 140, 110, 180, 170),
				boxLayer("arm-r", 6, 20, 110, 60, 170),
			)
		}

		val preview = PSD2LivePipeline().buildPreview(
			art,
			PipelineConfig(atlasSize = 512, meshSpacing = 16),
		)
		val validation = RigIntegrityValidator.validateNeutralPose(
			"SymmetricPairTest",
			preview.rig.puppet,
			emptyMap(),
		)
		assertTrue(validation.warnings.isEmpty(), "Neutral pose must have zero errors: ${validation.warnings}")

		// Verify world coordinates are finite and non-degenerate
		val world = CpuDeformationEvaluator().evaluate(preview.rig.puppet, emptyMap())
		for (drawable in preview.rig.puppet.drawables) {
			val positions = world.worldPositions[drawable.id]
			assertNotNull(positions)
			assertTrue(positions.isNotEmpty())
			assertTrue(positions.all { it.isFinite() })
		}
	}

	private fun boxLayer(name: String, order: Int, left: Int, top: Int, right: Int, bottom: Int): SourceLayer {
		val width = 200
		val height = 200
		val rgba = ByteArray(width * height * 4)
		for (y in top until bottom) {
			for (x in left until right) {
				val offset = (y * width + x) * 4
				rgba[offset] = 0x50
				rgba[offset + 1] = 0x60
				rgba[offset + 2] = 0x70
				rgba[offset + 3] = 0xff.toByte()
			}
		}
		return object : SourceLayer {
			override val id = LayerId(name)
			override val name = name
			override val groupPath = ""
			override val order = order
			override val bounds = LayerBounds(0, 0, width, height)
			override val opacity = 1f
			override val clipped = false
			override val blend = LayerBlend.Normal
			override val raster = LayerRaster(width, height, rgba)
		}
	}
}
