package io.github.psd2live.scenario

import io.github.psd2live.core.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * Parts split out of a layer survive everything the generators do afterwards: the back hair is cut in two, hair
 * simulation and a classification change regenerate the rig under them, the skeleton is put on, and the project is
 * reopened and exported. The pieces keep their IDs, stay where they were drawn, keep hanging from something that
 * moves, and reach the export.
 */
class SplitThenRegenerateScenario {
	@TempDir lateinit var temp: Path

	@Test fun splitHairThroughSimulationReclassifySkeletonAndReopen() = Studio.run(temp, Characters.figure(physics = true)) {
		edit("split the back hair", "source_split_polygon", req("layer_id" to "back_hair", "names" to listOf("Left", "Right"),
			"piece_ids" to listOf("left", "right"), "polygon" to listOf(listOf(0, 0), listOf(210, 0), listOf(210, 420), listOf(0, 420))))
		val pieces = (meshes("left") + meshes("right"))
		expect("split", pieces.size == 2) { "the split made ${pieces.size} meshes: ${now.model.rig.layerIdByDrawableId}" }
		val drawn = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.filterKeys { it.raw in pieces }

		fun piecesHold(label: String) {
			expect(label, meshes("left") + meshes("right") == pieces) { "the pieces were renamed: ${meshes("left") + meshes("right")} instead of $pieces" }
			val rest = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
			for ((id, was) in drawn) {
				val at = rest.getValue(id)
				val moved = was.indices.maxOf { abs(was[it] - at[it]) }
				expect(label, moved < 0.5f) { "piece ${id.raw} moved $moved px at rest" }
				val parent = puppet.drawables.single { it.id == id }.parentDeformerId
				expect(label, parent == null || puppet.deformers.any { it.id == parent }) { "piece ${id.raw} hangs from missing ${parent?.raw}" }
			}
			expect(label, pieces.all { motion(it, "ParamAngleZ", 30f) > 5.0 }) { "a piece does not follow the head tilt" }
		}
		piecesHold("split")

		edit("simulate the back hair", "model_apply_preset", req("preset" to "back_hair"))
		piecesHold("hair simulation")
		edit("reclassify the right sleeve", "layer_classify", req("layer_id" to "sleeve_r", "role" to "objects"))
		piecesHold("reclassified")
		edit("classify it back", "layer_classify", req("layer_id" to "sleeve_r", "role" to "handwear", "side" to "right"))
		edit("apply the proposed skeleton", "skeleton_auto", req())
		piecesHold("skeleton")
		val armR = now.document.rigEdits.skeleton!!.bones.single { it.role == BoneRole.UPPER_ARM && it.side == Side.RIGHT }
		expect("skeleton", motion(mesh("sleeve_r"), armR.parameterId, 45f) > 15.0) { "the right arm does not move its sleeve after the split" }
		reopen()
		piecesHold("reopened")
		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		exportMatchesEditor(this)
	}

	/** A piece of a split face deleted, then the deletion undone: the pieces return with the IDs they had. */
	@Test fun splitPieceDeletedAndTakenBack() = Studio.run(temp, Characters.head(physics = false)) {
		edit("split the face", "source_split_polygon", req("layer_id" to "face", "names" to listOf("Upper", "Lower"),
			"piece_ids" to listOf("upper", "lower"), "polygon" to listOf(listOf(0, 0), listOf(64, 0), listOf(64, 24), listOf(0, 24))))
		val pieces = meshes("upper") + meshes("lower")
		expect("split", pieces.size == 2) { "the split made ${pieces.size} meshes" }
		edit("delete the lower piece", "layer_delete", req("layer_id" to "lower"))
		expect("deleted", meshes("lower").isEmpty() && meshes("upper") == pieces.take(1)) { "deleting a piece touched the other" }
		undo("take the deletion back")
		expect("restored", meshes("upper") + meshes("lower") == pieces) { "restored pieces have other IDs: ${meshes("upper") + meshes("lower")}" }
		reopen()
	}
}
