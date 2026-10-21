package io.github.psd2live.scenario

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
