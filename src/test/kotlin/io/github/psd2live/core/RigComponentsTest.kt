package io.github.psd2live.core

import io.github.psd2live.project.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*

class RigComponentsTest {
	private fun preview(config: PipelineConfig): RigPreviewModel {
		val raster = LayerRaster(64, 128, ByteArray(64 * 128 * 4) { if (it % 4 == 3) 255.toByte() else 120.toByte() })
		val layer = WorkspaceSourceLayer(LayerId("skirt"), "skirt", "", SourceLayerKind.Raster, true, 0, LayerBounds(20, 20, 64, 128),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		return PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128, 168, listOf(layer), emptyList()), config)
	}

	private fun axes(model: PuppetModel): Set<String> = (model.drawables.flatMap { it.geometryGrid?.axes.orEmpty() } + model.deformers.flatMap { d ->
		when (d) { is Deformer.Warp -> d.geometryGrid?.axes.orEmpty(); is Deformer.Rotation -> d.geometryGrid?.axes.orEmpty() }
	}).map { it.parameterId.raw }.toSet()

	@Test
	fun `a component switched off keeps its deformers and moves nothing it made`() {
		val config = PipelineConfig(atlasSize = 512, meshSpacing = 16)
		val on = preview(config).rig.puppet
		val body = RigComponents.BODY.parameters.filter { it in axes(on) }
		assertTrue(body.isNotEmpty(), "the base rig keys the body: ${axes(on)}")
		val off = preview(config.copy(disabledComponents = setOf("body"))).rig.puppet
		assertEquals(on.deformers.map { it.id }, off.deformers.map { it.id }, "the hierarchy stays")
		assertTrue(body.none { it in axes(off) }, "nothing is keyed on the body's parameters")
		assertEquals(RigComponents.BREATH.parameters.filter { it in axes(on) }.toSet(), RigComponents.BREATH.parameters.filter { it in axes(off) }.toSet(),
			"the breath is a component of its own")
		assertTrue(on.parameters.map { it.id } == off.parameters.map { it.id }, "its parameters stay, for edits and motions")
	}

	@Test
	fun `a deformer and a parameter belong to the component that makes them`() {
		assertEquals(RigComponents.BREATH, RigComponents.ofDeformer("DeformBodyZBreath"))
		assertEquals(RigComponents.BODY, RigComponents.ofDeformer("DeformBodyXY"))
		assertEquals(RigComponents.EYES, RigComponents.ofDeformer("DeformEyeGaze"))
		assertNull(RigComponents.ofDeformer("Warp_Swing_Bang"))
		assertEquals(RigComponents.HEAD, RigComponents.ofParameter("ParamAngleX"))
	}

	@Test
	fun `a side takes its own values and they round-trip through the settings`() {
		val tuning = RigTuning(left = mapOf("blinkDepth" to 30f))
		assertEquals(30f, tuning.forSide(Side.LEFT).blinkDepth)
		assertEquals(tuning.blinkDepth, tuning.forSide(Side.RIGHT).blinkDepth)
		assertFailsWith<IllegalArgumentException> { RigTuning(right = mapOf("faceTurn" to 3f)) }
		val encoded = WorkspaceSettingsCodec.encodeRigTuning(tuning)
		assertEquals(tuning, WorkspaceSettingsCodec.decodeRigTuning(encoded))
		assertFalse("left" in WorkspaceSettingsCodec.encodeRigTuning(RigTuning()), "a tuning without sides writes none")
		val merged = WorkspaceSettingsCodec.mergeRigTuning(tuning, buildJsonObject {
			putJsonObject("left") { put("blinkDepth", JsonNull) }; putJsonObject("right") { put("browLift", 9) }
		})
		assertEquals(emptyMap(), merged.left)
		assertEquals(mapOf("browLift" to 9f), merged.right)
	}
}
