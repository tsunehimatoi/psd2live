package io.github.psd2live.scenario

import io.github.psd2live.core.GeneratedOverrides
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * The user corrects a shape a generator made - the hair swing at its far end - and then changes the generator. The
 * correction is kept as an override and merged three ways onto whatever the generator makes now: where both changed
 * the same points the user's value wins and the conflict is reported; the uncorrected branch follows the generator.
 */
class GeneratedOverrideScenario {
	@TempDir lateinit var temp: Path

	@Test fun aCorrectionOfTheSwingSurvivesChangingTheSwing() = Studio.run(temp, Characters.head()) {
		val swing = { magnitude: Double -> req("id" to "sway", "kind" to "lateral", "targets" to listOf("DeformHairBackPhysics"), "magnitude" to magnitude) }
		edit("add hair swing", "swing_put", swing(0.3))
		val generated = now
		val parameter = puppet.parameters.single { it.id.raw.startsWith("ParamSwing") }
		val hair = DrawableId(mesh("hair"))
		fun far(): FloatArray = CpuDeformationEvaluator().evaluate(puppet, mapOf(parameter.id to parameter.max)).worldPositions.getValue(hair)
		val warp = puppet.deformers.single { it.id.raw == "DeformHairBackPhysics" } as org.umamo.runtime.model.Deformer.Warp
		val key = warp.geometryGrid!!.axes.associate { it.parameterId.raw to (if (it.parameterId == parameter.id) parameter.max
			else puppet.parameters.single { p -> p.id == it.parameterId }.default) }
		val before = far()
		edit("correct the swing's far end", "rig_deform", req("changes" to listOf(mapOf("target" to "warp:DeformHairBackPhysics", "key" to key,
			"operations" to listOf(mapOf("type" to "translate", "delta" to listOf(0.1, 0)))))))
		expect("override", now.document.rigEdits.authoringJournal.any { it["op"]?.toString()?.trim('"') == GeneratedOverrides.OP }) {
			"the correction of a generated shape was not recorded as an override"
		}
		val corrected = far()
		val correction = FloatArray(before.size) { corrected[it] - before[it] }
		expect("override", correction.any { abs(it) > 0.5f }) { "the correction moved nothing" }

		allow("GENERATED_OVERRIDE_CONFLICT")
		edit("swing harder", "swing_put", swing(0.6))
		val merged = far()
		val conflicts = inspect().getValue("quality").jsonObject.getValue("overrides").jsonObject.getValue("findings").jsonArray
		expect("merged", conflicts.any { it.jsonObject.getValue("code").jsonPrimitive.content == "GENERATED_OVERRIDE_CONFLICT" }) { "the conflicting merge was not reported" }
		expect("merged", merged.indices.maxOf { abs(merged[it] - corrected[it]) } < 1e-3f) { "the user's corrected shape was not kept" }
		val withCorrection = now
		checkout("back before the correction", generated.historyHead)
		edit("swing harder, uncorrected", "swing_put", swing(0.6))
		val plain = far()
		expect("uncorrected", plain.indices.maxOf { abs(plain[it] - before[it]) } > 1f) { "the harder swing changed nothing, so the merge was not exercised" }
		checkout("back to the corrected branch", withCorrection.historyHead)
		reopen()
		expect("reopened", far().indices.maxOf { abs(far()[it] - merged[it]) } < 1e-3f) { "the merged correction changed on reopen" }
	}
}
