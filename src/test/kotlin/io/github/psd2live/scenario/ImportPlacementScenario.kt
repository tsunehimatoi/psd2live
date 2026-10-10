package io.github.psd2live.scenario

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * An accessory image is imported onto a finished character: moved and scaled, hung under the head so that it turns
 * with it, dragged back to the root, and the project reopened. At rest it stays exactly where the user put it through
 * every re-parenting, and it moves with whatever it hangs from.
 */
class ImportPlacementScenario {
	@TempDir lateinit var temp: Path

	@Test fun importMoveHangUnderTheHeadAndBack() = Studio.run(temp, Characters.figure()) {
		val layer = importImages("import an accessory", listOf(Characters.disc(temp, "badge.png", 24))).single()
		val badge = mesh(layer)
		val imported = bounds(badge)
		val centre = { b: FloatArray -> (b[0] + b[2]) / 2 to (b[1] + b[3]) / 2 }
		edit("move and scale it", "layer_transform", req("layer_id" to layer, "translate" to listOf(40, 20), "scale" to 1.5,
			"pivot" to centre(imported).toList()))
		val placed = bounds(badge)
		fun near(a: Float, b: Float) = abs(a - b) < 1.5f
		expect("moved", near(centre(placed).first, centre(imported).first + 40) && near(centre(placed).second, centre(imported).second + 20)) {
			"moved to ${placed.toList()} from ${imported.toList()}"
		}
		expect("scaled", near(placed[2] - placed[0], (imported[2] - imported[0]) * 1.5f)) { "scaled to ${placed.toList()} from ${imported.toList()}" }

		val head = puppet.drawables.single { it.id.raw == mesh("face") }.parentDeformerId!!.raw
		fun stays(label: String) = expect(label, bounds(badge).zip(placed).all { (a, b) -> abs(a - b) < 0.5f }) {
			"the badge moved at rest: ${placed.toList()} -> ${bounds(badge).toList()}"
		}
		edit("hang it under the head", "object_edit_appearance", req("edits" to listOf(mapOf("action" to "bind", "kind" to "mesh", "id" to badge,
			"parent_id" to head, "space" to "canvas"))))
		stays("under the head")
		expect("under the head", motion(badge, "ParamAngleZ", 30f) > 3.0) { "the badge does not tilt with the head" }
		edit("drag it back to the root", "object_edit_appearance", req("edits" to listOf(mapOf("action" to "bind", "kind" to "mesh", "id" to badge,
			"parent_id" to null, "space" to "canvas"))))
		stays("at the root")
		expect("at the root", motion(badge, "ParamAngleZ", 30f) < 0.01) { "the badge still follows the head" }
		reopen()
		stays("reopened")
	}
}
