package io.github.psd2live.core

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayerBindingTest {
	@Test
	fun `disabling feature displacement reconnects features to the face surface`() {
		val tags = mapOf("eye" to SemanticTag.EYEWHITE, "brow" to SemanticTag.EYEBROW, "mouth" to SemanticTag.MOUTH)
		val art = artOf(*tags.keys.mapIndexed { i, name -> layer(name, i) }.toTypedArray())
		val config = PipelineConfig(atlasSize = 256, meshSpacing = 16,
			layerOverrides = tags.mapValues { LayerClassificationOverride(tag = it.value, side = Side.NONE) })
		for (enabled in listOf(true, false)) {
			val model = PSD2LivePipeline().buildPreview(art, config.copy(featureDisplacementEnabled = enabled)).rig.puppet
			assertEquals(enabled, model.deformers.any { it.id.raw == "DeformFeatureDisplacement" })
			for (mesh in model.drawables) {
				val parent = model.deformers.single { it.id == mesh.parentDeformerId }
				assertEquals(if (enabled) "DeformFeatureDisplacement" else "DeformFaceNinePose", parent.parent?.raw)
			}
		}
	}

	@Test
	fun `face contour child owns only skin under the face surface`() {
		val tags = mapOf("skin" to SemanticTag.FACE, "detail" to SemanticTag.FACE_DETAIL,
			"eye" to SemanticTag.EYEWHITE, "hair" to SemanticTag.FRONT_HAIR)
		val preview = PSD2LivePipeline().buildPreview(
			artOf(*tags.keys.mapIndexed { i, name -> layer(name, i) }.toTypedArray()),
			PipelineConfig(atlasSize = 256, meshSpacing = 16,
				layerOverrides = tags.mapValues { LayerClassificationOverride(tag = it.value, side = Side.NONE) }),
		)
		val model = preview.rig.puppet
		val contour = model.deformers.single { it.id.raw == "DeformFaceContour" }
		assertEquals("DeformFaceNinePose", contour.parent?.raw)
		assertEquals(listOf("skin"), model.drawables.filter { it.parentDeformerId == contour.id }.map { it.name })
		assertTrue(model.deformers.none { it.parent == contour.id })
	}

	@Test
	fun `toggle layer creates 0 to 1 parameter and binds opacity channel grid`() {
		val face = layer("face", 0)
		val tears = layer("tears", 1)
		val art = artOf(face, tears)

		val config = PipelineConfig(
			atlasSize = 256,
			meshSpacing = 16,
			layerOverrides = mapOf(
				"face" to LayerClassificationOverride(tag = SemanticTag.FACE, side = Side.NONE),
				"tears" to LayerClassificationOverride(
					type = LayerType.TOGGLE,
					parameter = "show_tears",
				),
			),
		)

		val preview = PSD2LivePipeline().buildPreview(art, config)
		val tearsParam = preview.rig.puppet.parameters.firstOrNull { it.id == ParameterId("show_tears") }
		assertNotNull(tearsParam, "show_tears parameter must be registered in puppet")
		assertEquals("show_tears", tearsParam.name)
		assertEquals(0f, tearsParam.min)
		assertEquals(1f, tearsParam.max)
		assertEquals(0f, tearsParam.default)

		// Verify custom parameter group in parameterTree
		val customGroup = preview.rig.puppet.parameterTree
			.filterIsInstance<ParameterNode.Group>()
			.firstOrNull { it.id.raw == "ParamGroupCustom" }
		assertNotNull(customGroup, "ParamGroupCustom group should exist")
		assertTrue(customGroup.children.any { (it as? ParameterNode.Param)?.id == ParameterId("show_tears") })

		// Verify tears drawable opacity channel
		val tearsDrawable = preview.rig.puppet.drawables.first { it.name == "tears" }
		val opacityGrid = assertNotNull(tearsDrawable.channelGrids[FormChannel.OPACITY])
		assertEquals(listOf(ParameterId("show_tears")), opacityGrid.axes.map { it.parameterId })

		val cell0 = opacityGrid.cells.single { it.coordinate.contentEquals(intArrayOf(0)) }
		val cell1 = opacityGrid.cells.single { it.coordinate.contentEquals(intArrayOf(1)) }
		assertEquals(0f, (cell0.form as ChannelValue.Scalar).value)
		assertEquals(1f, (cell1.form as ChannelValue.Scalar).value)
	}

	@Test
	fun `switch layers share one parameter and toggle opacity mutually exclusively`() {
		val body = layer("body", 0)
		val armSilent = layer("arm_silent", 1)
		val armHeart = layer("arm_heart", 2)
		val armFold = layer("arm_fold", 3)
		val armRaise = layer("arm_raise", 4)
		val art = artOf(body, armSilent, armHeart, armFold, armRaise)

		val config = PipelineConfig(
			atlasSize = 256,
			meshSpacing = 16,
			layerOverrides = mapOf(
				"body" to LayerClassificationOverride(tag = SemanticTag.FACE, side = Side.NONE),
				"arm_silent" to LayerClassificationOverride(type = LayerType.SWITCH, parameter = "arm_pose", switchId = 0),
				"arm_heart" to LayerClassificationOverride(type = LayerType.SWITCH, parameter = "arm_pose", switchId = 1),
				"arm_fold" to LayerClassificationOverride(type = LayerType.SWITCH, parameter = "arm_pose", switchId = 2),
				"arm_raise" to LayerClassificationOverride(type = LayerType.SWITCH, parameter = "arm_pose", switchId = 3),
			),
		)

		val preview = PSD2LivePipeline().buildPreview(art, config)
		val armPoseParam = preview.rig.puppet.parameters.firstOrNull { it.id == ParameterId("arm_pose") }
		assertNotNull(armPoseParam, "arm_pose parameter must be registered")
		assertEquals("arm_pose", armPoseParam.name)
		assertEquals(0f, armPoseParam.min)
		assertEquals(3f, armPoseParam.max)
		assertEquals(0f, armPoseParam.default)

		val drawablesByName = preview.rig.puppet.drawables.associateBy { it.name }
		val armDrawables = listOf(
			drawablesByName.getValue("arm_silent"),
			drawablesByName.getValue("arm_heart"),
			drawablesByName.getValue("arm_fold"),
			drawablesByName.getValue("arm_raise"),
		)

		for ((targetIndex, targetDrawable) in armDrawables.withIndex()) {
			val opacityGrid = assertNotNull(targetDrawable.channelGrids[FormChannel.OPACITY])
			assertEquals(listOf(ParameterId("arm_pose")), opacityGrid.axes.map { it.parameterId })
			assertEquals(4, opacityGrid.cells.size)

			for (keyIndex in 0..3) {
				val cell = opacityGrid.cells.single { it.coordinate.contentEquals(intArrayOf(keyIndex)) }
				val expectedOpacity = if (keyIndex == targetIndex) 1f else 0f
				assertEquals(expectedOpacity, (cell.form as ChannelValue.Scalar).value,
					"Drawable ${targetDrawable.name} at arm_pose=$keyIndex must have opacity=$expectedOpacity")
			}
		}
	}

	@Test
	fun `preset layers do not get custom toggle or switch opacity channels`() {
		val face = layer("face", 0)
		val normalHair = layer("front_hair", 1)
		val art = artOf(face, normalHair)

		val config = PipelineConfig(
			atlasSize = 256,
			meshSpacing = 16,
			layerOverrides = mapOf(
				"face" to LayerClassificationOverride(tag = SemanticTag.FACE, side = Side.NONE),
				"front_hair" to LayerClassificationOverride(
					type = LayerType.PRESET,
					tag = SemanticTag.FRONT_HAIR,
					side = Side.NONE,
				),
			),
		)

		val preview = PSD2LivePipeline().buildPreview(art, config)
		val hairDrawable = preview.rig.puppet.drawables.first { it.name == "front_hair" }
		assertNull(hairDrawable.channelGrids[FormChannel.OPACITY], "Standard front hair should not have an opacity channel grid")
	}

	private fun artOf(vararg layers: SourceLayer) = object : SourceArt {
		override val layers = layers.toList()
		override val widthPx = WIDTH
		override val heightPx = HEIGHT
	}

	private fun layer(name: String, order: Int) = object : SourceLayer {
		override val id = LayerId(name)
		override val name = name
		override val groupPath = ""
		override val order = order
		override val bounds = LayerBounds(0, 0, WIDTH, HEIGHT)
		override val opacity = 1f
		override val clipped = false
		override val blend = LayerBlend.Normal
		override val raster = LayerRaster(WIDTH, HEIGHT, solidRaster())
	}

	private fun solidRaster(): ByteArray {
		val rgba = ByteArray(WIDTH * HEIGHT * 4)
		for (index in 0 until WIDTH * HEIGHT) {
			val offset = index * 4
			rgba[offset] = 128.toByte()
			rgba[offset + 1] = 128.toByte()
			rgba[offset + 2] = 128.toByte()
			rgba[offset + 3] = 0xff.toByte()
		}
		return rgba
	}

	private companion object {
		const val WIDTH = 64
		const val HEIGHT = 64
	}
}
