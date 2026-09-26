package io.github.psd2live.core

import java.nio.file.Path
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkeletonPipelineIntegrationTest {
	@Test fun presetImportDoesNotAutoInitializeAndManualEnablementWorks() {
		val initialPreview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		// Not automatically initialized on import
		kotlin.test.assertNull(initialPreview.config.rigEdits.skeleton)

		// Manually enabled via tool / SkeletonAutoBuilder
		val generatedSkeleton = SkeletonAutoBuilder.build(initialPreview.analysis, initialPreview.rig)
		val preview = PSD2LivePipeline().buildPreview(
			initialPreview.analysis,
			initialPreview.config.copy(rigEdits = initialPreview.config.rigEdits.copy(skeleton = generatedSkeleton)),
		)
		val spec = assertNotNull(preview.config.rigEdits.skeleton)
		assertTrue(spec.enabled && spec.bones.any { it.role == BoneRole.FOREARM })
		val puppet = preview.rig.puppet
		val layers = preview.analysis.layers.associateBy { it.source.id.raw }
		val limbDrawables = puppet.drawables.filter { drawable ->
			val layerId = preview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
			layers[layerId]?.semantic?.tag in SkeletonAutoBuilder.limbTags
		}
		assertTrue(limbDrawables.isNotEmpty())
		val bound = spec.bones.flatMap { it.drawableIds }.toSet()
		val boneDeformers = puppet.deformers.filterIsInstance<Deformer.Rotation>().filter { it.id.raw.startsWith("DeformSkel_") }
		assertTrue(boneDeformers.isNotEmpty())
		// Every skinned mesh hangs directly under one of its bones.
		for (drawable in limbDrawables.filter { it.id.raw in bound }) {
			assertTrue(drawable.parentDeformerId in boneDeformers.map { it.id }, "${drawable.id.raw} is not under a bone")
		}
		// Rest pose matches the unskinned rig; a posed arm moves.
		val evaluator = CpuDeformationEvaluator()
		val before = evaluator.evaluate(initialPreview.rig.puppet, emptyMap()).worldPositions
		val after = evaluator.evaluate(puppet, emptyMap()).worldPositions
		for (drawable in limbDrawables.filter { it.id.raw in bound }) {
			val expected = before[drawable.id] ?: continue
			val actual = after.getValue(drawable.id)
			for (i in expected.indices) assertTrue(kotlin.math.abs(expected[i] - actual[i]) < 0.5f, "${drawable.id.raw} moved at rest")
		}
		val arm = spec.bones.first { it.role == BoneRole.UPPER_ARM }
		val posed = evaluator.evaluate(puppet, mapOf(ParameterId(arm.parameterId) to 30f)).worldPositions
		assertTrue(arm.drawableIds.any { id -> after.keys.any { it.raw == id } &&
			posed.getValue(after.keys.first { it.raw == id }).indices.any { i ->
				kotlin.math.abs(posed.getValue(after.keys.first { it.raw == id })[i] - after.getValue(after.keys.first { it.raw == id })[i]) > 5f
			} })
		val idle = assertNotNull(preview.runtimeBundle.assets.firstOrNull { it.path.endsWith(".idle.motion3.json") })
		assertTrue(idle.bytes.decodeToString().contains("ParamArmLA"))
	}
}
