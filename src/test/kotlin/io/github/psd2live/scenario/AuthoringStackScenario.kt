package io.github.psd2live.scenario

import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * Hand authoring piled onto a generated rig, each on top of the last: a rotation driving a sleeve on a new axis, glue
 * between the sleeve and the top, a blend shape puffing the face, opacity and colour keyed on the top, the axis range
 * narrowed afterwards. Every layer of it replays, undoes, reopens, and reaches the export with the same motion and
 * the same channels.
 */
class AuthoringStackScenario {
	@TempDir lateinit var temp: Path

	@Test fun rotationGlueBlendShapeChannelsAndARangeChange() = Studio.run(temp, Characters.figure()) {
		val sleeve = mesh("sleeve_r"); val top = mesh("top"); val face = mesh("face")
		structural("make a rotation for the right sleeve and a new axis",
			"parameter_create" to req("parameter_id" to "ParamWing", "name" to "Wing", "min" to -1, "max" to 1),
			// As an agent makes it: the pivot in canvas pixels, nothing said about the pose (the sleeve must stay put).
			"canvas_rotation" to req("id" to "ElbowR", "name" to "Elbow R", "meshes" to listOf(sleeve), "origin" to listOf(162, 134)))
		val pivot = (puppet.deformers.single { it.id.raw == "ElbowR" } as Deformer.Rotation).geometryGrid!!.cells.single().form
		// The second key gives only the angle: the pivot stays where the rotation has it.
		edit("turn the rotation on the axis", "keyform_apply", req("changes" to listOf(
			mapOf("target" to "rotation:ElbowR", "key" to mapOf("ParamWing" to 0), "op" to "set",
				"geometry" to mapOf("originX" to pivot.originX, "originY" to pivot.originY, "angle" to pivot.angle)),
			mapOf("target" to "rotation:ElbowR", "key" to mapOf("ParamWing" to 1), "op" to "set", "geometry" to mapOf("angle" to pivot.angle + 30)))))
		expect("rotation", motion(sleeve, "ParamWing", 1f) > 10.0) { "the rotation does not swing the sleeve" }

		edit("glue the sleeve to the top", "canvas_glue", req("id" to "ShoulderGlue", "mesh_a" to sleeve, "mesh_b" to top, "distance" to 8))
		expect("glue", puppet.glues.isNotEmpty()) { "no glue was made" }

		edits("puff the face with a blend shape",
			"parameter_create" to req("parameter_id" to "ParamPuff", "name" to "Puff", "min" to 0, "max" to 1, "kind" to "blend_shape"),
			"rig_deform" to req("changes" to listOf(mapOf("target" to "mesh:$face", "key" to mapOf("ParamPuff" to 1),
				"operations" to listOf(mapOf("type" to "scale", "factors" to listOf(1.15, 1.15)))))))
		expect("blend shape", motion(face, "ParamPuff", 1f) > 1.0) { "the blend shape does not move the face" }

		edit("fade and tint the top on the axis", "keyform_apply", req("changes" to listOf(
			mapOf("target" to "mesh:$top", "key" to mapOf("ParamWing" to 0), "op" to "seed"),
			mapOf("target" to "mesh:$top", "key" to mapOf("ParamWing" to 1), "op" to "set",
				"channels" to mapOf("opacity" to 0.3, "multiplyColor" to listOf(1, 0.5, 0.5))))))
		fun faded(label: String) {
			val shown = CpuDeformationEvaluator().evaluate(puppet, mapOf(ParameterId("ParamWing") to 1f)).opacity[DrawableId(top)]
			expect(label, shown != null && abs(shown - 0.3f) < 0.02f) { "the top shows opacity $shown at Wing 1" }
		}
		faded("channels")

		edit("narrow the wing axis", "parameter_update", req("parameter_id" to "ParamWing", "min" to -0.5, "max" to 0.5))
		expect("narrowed", motion(sleeve, "ParamWing", 0.5f) in 3.0..(motion(sleeve, "ParamWing", 1f) + 0.01)) { "the narrowed axis lost its motion" }
		edit("widen it again", "parameter_update", req("parameter_id" to "ParamWing", "min" to -1, "max" to 1))
		faded("widened")
		reopen()
		faded("reopened")
		expect("reopened", motion(face, "ParamPuff", 1f) > 1.0 && puppet.glues.isNotEmpty()) { "the blend shape or glue was lost on reopen" }
		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		exportMatchesEditor(this)
	}
}
