package io.github.psd2live.scenario

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * The user authors on generated meshes, then changes their mind about what the layers are: reclassifies one, deletes
 * deletes another and takes that back, changes a generation setting. Meshes keep their IDs whatever the layer is classified as,
 * every layer keeps exactly one mesh (no retired copy beside a new one), and the user's work stays on the objects it
 * was made on.
 */
class ReclassifyDeleteScenario {
	@TempDir lateinit var temp: Path

	@Test fun authoredMeshesSurviveReclassifyDeleteRestoreAndSettings() = Studio.run(temp, Characters.figure()) {
		val layers = now.document.source.layers.map { it.id.raw }
		val ids = layers.associateWith { mesh(it) }
		structural("give the right sleeve a warp and a custom axis",
			"canvas_warp" to req("id" to "SleeveWarp", "name" to "Sleeve warp", "meshes" to listOf(ids.getValue("sleeve_r"))),
			"parameter_create" to req("parameter_id" to "ParamFlap", "name" to "Flap", "min" to -1, "max" to 1),
			"keyform_apply" to req("changes" to listOf(mapOf("target" to "warp:SleeveWarp", "key" to mapOf("ParamFlap" to 0), "op" to "seed"))),
			"rig_deform" to req("changes" to listOf(mapOf("target" to "warp:SleeveWarp", "key" to mapOf("ParamFlap" to 1),
				"operations" to listOf(mapOf("type" to "translate", "delta" to listOf(0, 0.2)))))))
		fun authored(label: String) {
			expect(label, layers.all { meshes(it) == listOf(ids.getValue(it)) }) {
				"layers no longer map to their meshes one to one: " + layers.filter { meshes(it) != listOf(ids.getValue(it)) }.associateWith { meshes(it) }
			}
			expect(label, puppet.drawables.single { it.id.raw == ids.getValue("sleeve_r") }.parentDeformerId?.raw == "SleeveWarp") { "the sleeve left the user's warp" }
			expect(label, motion(ids.getValue("sleeve_r"), "ParamFlap", 1f) > 3.0) { "the user's axis no longer moves the sleeve" }
		}
		authored("authored")
		// The sleeve now hangs from the user's warp: a regeneration reports that it keeps the user's parent.
		allow("REGENERATION_CONFLICT")
		edit("reclassify the right sleeve as an object", "layer_classify", req("layer_id" to "sleeve_r", "role" to "objects", "side" to "none"))
		authored("reclassified")
		edit("delete the left leg", "layer_delete", req("layer_id" to "leg_l"))
		expect("deleted", meshes("leg_l").isEmpty()) { "the deleted leg still has a mesh" }
		expect("deleted", (layers - "leg_l").all { meshes(it) == listOf(ids.getValue(it)) }) { "deleting a layer renumbered its siblings" }
		// The edit's own check undid and redid the deletion; undo it for good, as the user takes it back.
		undo("take the deletion back")
		authored("deletion undone")
		edit("make the mesh denser", "settings_update", req("changes" to mapOf("meshSpacing" to 24)))
		authored("denser mesh")
		reopen()
		authored("reopened")
	}
}
