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

		// The upper body bends the torso warp at the waist, which carries its meshes, the arms and the head.
		val upperBody = spec.bones.single { it.role == BoneRole.UPPER_BODY }
		val upperDeformer = puppet.deformers.single { it.id == SkeletonRig.deformerOf(puppet, upperBody) }
		assertTrue(upperDeformer is Deformer.Warp && upperDeformer.parent?.raw == "DeformBodyZBreath")
		assertTrue(upperBody.drawableIds.isNotEmpty())
		for (id in upperBody.drawableIds) {
			assertTrue(puppet.drawables.single { it.id.raw == id }.parentDeformerId == upperDeformer.id, "$id is not under the upper body")
		}
		assertTrue(puppet.deformers.single { it.id.raw == "DeformHeadRotation" }.parent == upperDeformer.id)
		// The body warps above reach the bones below: the breath lifts the arms.
		val breathing = evaluator.evaluate(puppet, mapOf(ParameterId("ParamBreath") to 1f)).worldPositions
		assertTrue(arm.drawableIds.any { id ->
			val drawable = after.keys.firstOrNull { it.raw == id } ?: return@any false
			breathing.getValue(drawable).indices.any { kotlin.math.abs(breathing.getValue(drawable)[it] - after.getValue(drawable)[it]) > 1f }
		}, "the breath does not reach the arm")
		for ((id, expected) in before) {
			val actual = after[id] ?: continue
			for (i in expected.indices) assertTrue(kotlin.math.abs(expected[i] - actual[i]) < 0.5f, "${id.raw} moved at rest")
		}
		val leaned = evaluator.evaluate(puppet, mapOf(ParameterId(upperBody.parameterId) to 20f)).worldPositions
		val faces = puppet.drawables.filter { layers[preview.rig.layerIdByDrawableId[it.id.raw] ?: it.id.raw]?.semantic?.tag == SemanticTag.FACE }
		assertTrue(faces.isNotEmpty())
		for (drawable in faces) {
			assertTrue(leaned.getValue(drawable.id).indices.any { kotlin.math.abs(leaned.getValue(drawable.id)[it] - after.getValue(drawable.id)[it]) > 5f },
				"${drawable.id.raw} did not follow the upper body")
		}

		// The bone and pose parameters sit in their own folder, so the panel lists every one of them.
		val skeletonGroup = puppet.parameterTree.filterIsInstance<org.umamo.runtime.model.ParameterNode.Group>()
			.single { it.id.raw == "ParamGroupSkeleton" }
		val grouped = skeletonGroup.children.filterIsInstance<org.umamo.runtime.model.ParameterNode.Param>().map { it.id.raw }.toSet()
		assertTrue(arm.parameterId in grouped && SkeletonPoses.armSway.id.raw in grouped)

		val idle = assertNotNull(preview.runtimeBundle.assets.firstOrNull { it.path.endsWith(".idle.motion3.json") })
		assertTrue(idle.bytes.decodeToString().contains(SkeletonPoses.armSway.id.raw))
	}
}
