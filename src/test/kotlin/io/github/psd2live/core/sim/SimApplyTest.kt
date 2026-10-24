package io.github.psd2live.core.sim

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlin.math.hypot
import kotlin.test.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

class SimApplyTest {
	private val preview: RigPreviewModel by lazy {
		val raster = LayerRaster(64, 128, ByteArray(64 * 128 * 4) { if (it % 4 == 3) 255.toByte() else 120.toByte() })
		val layer = WorkspaceSourceLayer(LayerId("skirt"), "skirt", "", SourceLayerKind.Raster, true, 0, LayerBounds(20, 20, 64, 128),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128, 168, listOf(layer), emptyList()), PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

	@Test
	fun `an applied simulation leaves the same rig in the journal and goes`() {
		val authored = preview.authored.rig.puppet
		val mesh = authored.drawables.first { it.mesh != null }
		val count = mesh.mesh!!.vertexCount
		// One mode that pushes the mesh sideways, more at its keys' ends.
		val mode = SimBakedAxis("ParamSimSkirt_1", floatArrayOf(-30f, 0f, 30f), mapOf(mesh.id.raw to listOf(
			FloatArray(count * 2) { if (it % 2 == 0) -0.05f else 0f }, FloatArray(count * 2), FloatArray(count * 2) { if (it % 2 == 0) 0.05f else 0.01f })))
		val sim = RigSimEdit("Skirt", "Skirt", SimKind.CLOTH, listOf(mesh.id.raw), blendShapes = false,
			bake = SimBakeResult("test", mapOf(mesh.id.raw to count), emptyList(), listOf(SimBakedMode(mode, 1f, 1f))))
		val overlay = RigEditOverlay(simEdits = listOf(sim))
		val shown = overlay.applyTo(preview.baseRig.puppet)
		val parameter = SimGenerator.parameterId(sim, 1)
		assertTrue(shown.parameters.any { it.id.raw == parameter })
		val applied = SimApply.apply(overlay, preview.authoredPuppet(overlay), "Skirt")
		assertTrue(applied.simEdits.isEmpty())
		val replayed = applied.applyTo(preview.baseRig.puppet)
		for (v in listOf(-30f, -12f, 0f, 20f, 30f)) {
			fun at(model: PuppetModel) = CpuDeformationEvaluator().evaluate(model, mapOf(ParameterId(parameter) to v)).worldPositions.getValue(mesh.id)
			val a = at(shown); val b = at(replayed)
			assertTrue((0 until a.size / 2).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) } < 1e-3f, "at $v")
		}
	}
}
