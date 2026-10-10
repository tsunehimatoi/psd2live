package io.github.psd2live.scenario

import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * The user sculpts while the model is posed: hair swing is added, then the face is brushed with the head turned and
 * the swing deflected, as the canvas shows it; the swing is later baked away and hair simulation applied. The stroke
 * replays on every rebuild (the pose it was made in names parameters that come and go), and its effect stays.
 */
class PosedStrokeScenario {
	@TempDir lateinit var temp: Path

	@Test fun strokesAtAPoseSurviveGeneratorsComingAndGoing() = Studio.run(temp, Characters.figure(physics = true)) {
		edit("add hair swing", "swing_put", req("id" to "sway", "kind" to "lateral", "targets" to listOf("DeformHairBackPhysics")))
		val swing = puppet.parameters.single { it.id.raw.startsWith("ParamSwing") }.id.raw
		val face = mesh("face")
		val before = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(DrawableId(face))
		val shown = mapOf("ParamAngleX" to 20f, swing to 0.6f)
		edit("push the cheek out, posed", "canvas_deform_stroke", req("stroke" to mapOf("mode" to "edit", "action" to "brush",
			"targets" to listOf(mapOf("target" to "mesh:$face")), "pose" to shown, "radius" to 30, "strength" to 1,
			"samples" to listOf(mapOf("point" to listOf(190, 80)), mapOf("point" to listOf(205, 82)), mapOf("point" to listOf(220, 84))))))
		fun stroked(label: String) {
			val after = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(DrawableId(face))
			expect(label, before.indices.maxOf { abs(before[it] - after[it]) } > 1f) { "the stroke left no mark on the face" }
		}
		stroked("stroked")
		edit("bake the swing away", "swing_delete", req("id" to "sway", "bake" to true))
		stroked("swing baked")
		edit("simulate the front hair", "model_apply_preset", req("preset" to "front_hair"))
		stroked("front hair simulated")
		reopen()
		stroked("reopened")
	}
}
