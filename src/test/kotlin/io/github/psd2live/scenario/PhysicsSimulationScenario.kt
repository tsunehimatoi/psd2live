package io.github.psd2live.scenario

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Secondary motion end to end: a hand-made physics group drives the back hair from the head turn and reaches the
 * exported physics3.json, where a runtime's physics swings the hair; hair simulation is applied, baked and cleared; a
 * swing whose warp is deleted leaves no dangling target. The physics kernel itself matches the Cubism framework.
 */
class PhysicsSimulationScenario {
	@TempDir lateinit var temp: Path

	@Test fun physicsReachesTheExportAndSimulationBakesAndClears() = Studio.run(temp, Characters.head()) {
		edit("add a physics group for the back hair", "physics_put", req("id" to "HairSway", "name" to "Hair sway",
			"inputs" to listOf(mapOf("parameter" to "ParamAngleX", "weight" to 100, "type" to "x")),
			"outputs" to listOf(mapOf("parameter" to "ParamHairBack", "vertex" to 1, "scale" to 1)),
			"segment_count" to 2, "length" to 20))
		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		val files = export("moc3")
		val physics = files.singleOrNull { it.toString().endsWith(".physics3.json") }
		expect("export", physics != null) { "no physics3.json was exported: $files" }
		val (settings, fps) = Physics3Json.read(Files.readString(physics!!))
		val ranges = puppet.parameters.associate { it.id.raw to PhysicsEngine.Range(it.min, it.default, it.max) }
		val engine = PhysicsEngine(settings, ranges, fps)
		var swing = 0f
		for (frame in 0 until 60) {
			val out = engine.step(mapOf("ParamAngleX" to if (frame < 10) 0f else 30f), 1f / 60f)
			swing = maxOf(swing, abs(out["ParamHairBack"] ?: 0f))
		}
		expect("export", swing > 0.05f) { "the exported physics never moves the back hair (largest output $swing)" }

		edit("simulate the back hair", "model_apply_preset", req("preset" to "back_hair"))
		val simulation = now.document.rigEdits.simEdits.firstOrNull()
		expect("simulation", simulation != null) { "the preset made no simulation" }
		edit("bake it", "simulation_bake", req("id" to simulation!!.id))
		expect("baked", now.document.rigEdits.simEdits.single { it.id == simulation.id }.bake != null) { "the bake was not kept" }
		reopen()
		expect("reopened", now.document.rigEdits.simEdits.single { it.id == simulation.id }.bake != null) { "the bake was lost on reopen" }
		edit("clear the bake", "simulation_clear_bake", req("id" to simulation.id))

		structural("add a warp for an accessory swing", "canvas_warp" to req("id" to "Tassel", "name" to "Tassel", "meshes" to listOf(mesh("face"))))
		edit("swing it", "swing_put", req("id" to "tassel", "kind" to "vertical", "targets" to listOf("Tassel")))
		edit("delete the warp", "rig_edit_structure", req("edits" to listOf(mapOf("action" to "delete", "kind" to "warp", "id" to "Tassel"))))
		expect("warp deleted", now.document.rigEdits.swingEdits.none { "Tassel" in it.targets }) { "the swing still targets the deleted warp" }
	}

	/** The physics kernel against Cubism Native Framework output for the same physics3.json and frame schedule. */
	@Test fun physicsKernelMatchesCubism() {
		fun resource(name: String) = requireNotNull(javaClass.getResource("/physics-reference/$name")) { name }.readText()
		for (variant in listOf("fps60", "nofps")) {
			val (settings, fps) = Physics3Json.read(resource("$variant.physics3.json"))
			val lines = resource("$variant.expected.csv").lines().filter { it.isNotBlank() }
			val ranges = lines.filter { it.startsWith("# range,") }.associate { line ->
				val c = line.removePrefix("# range,").split(",")
				c[0] to PhysicsEngine.Range(c[1].toFloat(), c[2].toFloat(), c[3].toFloat())
			}
			val header = lines.first { it.startsWith("frame") }.split(",").drop(1)
			val expected = lines.filter { it.first().isDigit() }.map { row -> row.split(",").drop(1).map(String::toFloat) }
			val schedule = resource("schedule.csv").lines().filter { it.isNotBlank() }
			val inputs = schedule.first().split(",").drop(1)
			val engine = PhysicsEngine(settings, ranges, fps)
			schedule.drop(1).forEachIndexed { frame, row ->
				val cells = row.split(",").map(String::toFloat)
				val out = engine.step(inputs.mapIndexed { k, id -> id to cells[k + 1].coerceIn(ranges.getValue(id).min, ranges.getValue(id).max) }.toMap(), cells[0])
				header.forEachIndexed { k, id ->
					val actual = out[id] ?: ranges.getValue(id).default
					assertTrue(abs(actual - expected[frame][k]) < 1e-4f, "$variant frame $frame $id: $actual, Cubism ${expected[frame][k]}")
				}
			}
		}
	}
}
