package io.github.psd2live.scenario

import io.github.psd2live.core.GeometrySafetyEvaluator
import io.github.psd2live.core.GeometrySafetyReport
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * A warp made for a mesh in canvas pixels (at the root, or under a rotation) that is larger than a million square
 * pixels (issue #56). The mesh moves into the warp's 0..1 square, so every triangle shrinks by the part's area; the
 * safety gate read that change of space as degenerate triangles and refused the warp.
 */
class LargeCanvasWarpScenario {
	@TempDir lateinit var temp: Path

	@Test fun aWarpOnALargePartPassesTheGate() = Studio.run(temp, Characters.largePart(), checks = emptySet()) {
		structural("take the top to the root", "rig_edit_structure" to req("edits" to listOf(
			mapOf("action" to "bind", "kind" to "mesh", "id" to mesh("top"), "parent_id" to null, "space" to "canvas"))))
		structural("warp the top at the root", "canvas_warp" to req("id" to "TopWarp", "name" to "Top", "meshes" to listOf(mesh("top"))))
		undo()
		structural("warp the top under a rotation",
			"canvas_rotation" to req("id" to "TopTurn", "name" to "Top turn", "meshes" to listOf(mesh("top")), "origin" to listOf(600, 600)),
			"canvas_warp" to req("id" to "TopWarp", "name" to "Top", "meshes" to listOf(mesh("top"))))
	}
}

/**
 * Squeezing a whole mesh is a valid edit however far its canvas pixels lie from the origin; squeezing half of it
 * still collapses the triangles between the halves. The gate's affine check must allow for the rounding of points
 * in the thousands.
 */
class LargeCanvasSqueezeScenario {
	@TempDir lateinit var temp: Path

	@Test fun aWholeSqueezeIsAffineAndAHalfOneIsNot() = Studio.run(temp, Characters.largePart(), checks = emptySet()) {
		val top = mesh("top")
		structural("take the top to the root", "rig_edit_structure" to req("edits" to listOf(
			mapOf("action" to "bind", "kind" to "mesh", "id" to top, "parent_id" to null, "space" to "canvas"))))
		edits("a squeeze axis", "parameter_create" to req("parameter_id" to "ParamSqueeze", "name" to "Squeeze", "min" to 0, "max" to 1),
			"keyform_apply" to req("changes" to listOf(mapOf("target" to "mesh:$top", "key" to mapOf("ParamSqueeze" to 0), "op" to "seed"))))
		fun squeeze(name: String, selection: Map<String, Any>): GeometrySafetyReport {
			val before = puppet
			edits(name, "rig_deform" to req("changes" to listOf(mapOf("target" to "mesh:$top", "key" to mapOf("ParamSqueeze" to 1),
				"operations" to listOf(mapOf("type" to "scale", "factors" to listOf(0.05, 0.1), "selection" to selection))))))
			return GeometrySafetyEvaluator.evaluate(before, puppet, blockFoldovers = false).also { undo() }
		}
		val whole = squeeze("squeeze the whole top", emptyMap())
		expect("whole", whole.safe && whole.warnings.isEmpty()) { "a whole-mesh squeeze was flagged: ${whole.warnings.map { it.reason }}" }
		val half = squeeze("squeeze the left half", mapOf("rect" to listOf(0, 0, 0.5, 1)))
		expect("half", half.newCollapseCount + half.newFlipCount > 0) { "a half squeeze was not flagged" }
	}
}
