package io.github.psd2live.scenario

import io.github.psd2live.core.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Animating a rigged character: a built-in motion is added and edited key by key on a custom axis, the axis range is
 * narrowed under the keys, the clip duplicated and renamed, the project reopened and the motions exported. The clip
 * plays within the axis it drives, survives the round trips, and the exported motion3.json is what Cubism reads.
 */
class MotionScenario {
	@TempDir lateinit var temp: Path

	@Test fun builtInMotionEditedNarrowedDuplicatedAndExported() = Studio.run(temp, Characters.figure()) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		edits("add a tail axis and the Cheer motion",
			"parameter_create" to req("parameter_id" to "ParamTail", "name" to "Tail", "min" to -10, "max" to 10),
			"motion_seed_builtin" to req("builtin" to "Cheer", "id" to "cheer"))
		edits("key the tail in the cheer",
			"motion_set_key" to req("id" to "cheer", "parameter" to "ParamTail", "key" to mapOf("time" to 0, "value" to 0)),
			"motion_set_key" to req("id" to "cheer", "parameter" to "ParamTail", "key" to mapOf("time" to 0.4, "value" to 9)),
			"motion_set_key" to req("id" to "cheer", "parameter" to "ParamTail", "key" to mapOf("time" to 0.8, "value" to 0.000001)))
		fun clip(id: String = "cheer") = now.document.rigEdits.motionClips.single { it.id == id }
		fun peak(id: String = "cheer") = (0..40).maxOf { MotionClips.sampleAll(clip(id), clip(id).duration * it / 40.0)[ParameterId("ParamTail")] ?: 0f }
		expect("keyed", peak() in 8.5f..9.01f) { "the tail peaks at ${peak()}" }
		val arms = now.document.rigEdits.skeleton!!.bones.filter { it.role == BoneRole.UPPER_ARM }.map { it.parameterId }
		expect("keyed", clip().curves.any { it.parameterId in arms }) { "the Cheer motion does not move the arm bones: ${clip().curves.map { it.parameterId }}" }

		edit("narrow the tail axis under its keys", "parameter_update", req("parameter_id" to "ParamTail", "min" to -5, "max" to 5))
		edit("delete the last tail key", "motion_delete_key", req("id" to "cheer", "parameter" to "ParamTail", "time" to 0.8))
		edits("duplicate and rename", "motion_duplicate" to req("id" to "cheer", "new_id" to "cheer2"),
			"motion_rename" to req("id" to "cheer2", "name" to "Cheer again"))
		reopen()
		expect("reopened", clip("cheer2").name == "Cheer again" && clip().curves == clip("cheer2").curves) { "the duplicate changed on reopen" }

		edit("export motions", "settings_update", req("changes" to mapOf("exportMoc3" to true, "exportMotions" to true)))
		val motions = export("moc3").filter { it.toString().endsWith(".motion3.json") }
		expect("export", motions.isNotEmpty()) { "no motion3.json was exported" }
		for (file in motions) {
			val text = Files.readString(file)
			expect("export", !Regex("[0-9][eE][-+]?[0-9]").containsMatchIn(text)) { "${file.fileName} has numbers in scientific notation, which Cubism rejects" }
		}
		val cheer = motions.map { kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(it)).jsonObject }
			.flatMap { it.getValue("Curves").jsonArray }.map { it.jsonObject }.filter { it.getValue("Id").jsonPrimitive.content == "ParamTail" }
		expect("export", cheer.isNotEmpty()) { "the edited curve did not reach the export" }
		val values = cheer.flatMap { curve -> segmentValues(curve.getValue("Segments").jsonArray.map { it.jsonPrimitive.float }) }
		expect("export", values.all { it in -5.0001f..5.0001f }) { "the exported tail curve leaves the narrowed range: $values" }
	}
}

/** The values in motion3.json segments: a first point, then per segment its type and one point, or three for a Bezier. */
private fun segmentValues(segments: List<Float>): List<Float> {
	val values = arrayListOf(segments[1])
	var i = 2
	while (i < segments.size) {
		val points = if (segments[i].toInt() == 1) 3 else 1
		for (p in 0 until points) values += segments[i + 2 + p * 2]
		i += 1 + points * 2
	}
	return values
}
