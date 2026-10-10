package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

/**
 * Named physics settings to reuse across groups and models, as Cubism Editor keeps them: an input preset
 * holds the inputs and their normalization, a pendulum preset the pendulums (segments). The built-in ones
 * are the generated hair and eye groups; the user's are stored by the app.
 */
object PhysicsPresets {
	enum class Kind { INPUT, PENDULUM }

	data class Preset(
		val kind: Kind,
		val name: String,
		val inputs: List<PhysicsInput> = emptyList(),
		val normalization: PhysicsNormalization = PhysicsNormalization(),
		val segments: List<PhysicsSegment> = emptyList(),
		val builtin: Boolean = false,
	) {
		init {
			require(name.isNotBlank() && name.none(Char::isISOControl)) { "A preset needs a name" }
			require(kind != Kind.PENDULUM || segments.size in 1..RigPhysicsEdit.MAX_SEGMENTS) { "A pendulum preset has 1..${RigPhysicsEdit.MAX_SEGMENTS} pendulums" }
			require(kind != Kind.INPUT || inputs.isNotEmpty()) { "An input preset has at least one input" }
		}

		fun toJson() = buildJsonObject {
			put("kind", kind.name.lowercase()); put("name", name)
			if (kind == Kind.INPUT) {
				putJsonArray("inputs") { inputs.forEach { add(it.toJson()) } }
				put("normalization", normalization.toJson())
			} else putJsonArray("segments") { segments.forEach { add(it.toJson()) } }
		}

		companion object {
			fun fromJson(o: JsonObject): Preset {
				val kind = Kind.valueOf(o.getValue("kind").jsonPrimitive.content.uppercase())
				return Preset(
					kind, o.getValue("name").jsonPrimitive.content,
					inputs = o["inputs"]?.jsonArray?.map { PhysicsInput.fromJson(it.jsonObject) }.orEmpty(),
					normalization = o["normalization"]?.jsonObject?.let { PhysicsNormalization.fromJson(it) } ?: PhysicsNormalization(),
					segments = o["segments"]?.jsonArray?.map { PhysicsSegment.fromJson(it.jsonObject, PhysicsSegment()) }.orEmpty(),
				)
			}
		}
	}

	/** The generated groups' inputs and pendulums, so a new group can start from what the presets use. */
	fun builtins(kind: Kind): List<Preset> {
		val rules = PhysicsGenerator.presetRules(PhysicsGenerator.Presets.All)
		fun rule(id: String) = rules.first { it.id == id }
		return when (kind) {
			Kind.INPUT -> listOf(
				Preset(kind, tr("physics.preset.headBody"), rule(PhysicsGenerator.FRONT_HAIR_ID).inputs, rule(PhysicsGenerator.FRONT_HAIR_ID).normalization, builtin = true),
				Preset(kind, tr("physics.preset.headBodyWide"), rule(PhysicsGenerator.BACK_HAIR_ID).inputs, rule(PhysicsGenerator.BACK_HAIR_ID).normalization, builtin = true),
				Preset(kind, tr("physics.preset.pitch"), listOf(
					PhysicsInput("ParamAngleY", 60f, PhysicsSourceType.X),
					PhysicsInput("ParamBodyAngleY", 40f, PhysicsSourceType.X),
					PhysicsInput("ParamAngleZ", 20f, PhysicsSourceType.ANGLE),
				), PhysicsNormalization(angleMin = -30f, angleMax = 30f), builtin = true),
				Preset(kind, tr("physics.preset.eyes"), rule(PhysicsGenerator.EYE_JELLY_ID).inputs, rule(PhysicsGenerator.EYE_JELLY_ID).normalization, builtin = true),
			)
			Kind.PENDULUM -> listOf(
				Preset(kind, tr("model.physics.frontHair"), segments = rule(PhysicsGenerator.FRONT_HAIR_ID).segments, builtin = true),
				Preset(kind, tr("model.physics.backHair"), segments = rule(PhysicsGenerator.BACK_HAIR_ID).segments, builtin = true),
				Preset(kind, tr("physics.preset.chain"), segments = List(3) { PhysicsSegment(5f, 0.9f, 0.9f, 1.2f) }, builtin = true),
				Preset(kind, tr("model.physics.eyeJelly"), segments = rule(PhysicsGenerator.EYE_JELLY_ID).segments, builtin = true),
			)
		}
	}

	/** Whether [setting] holds exactly [preset]'s inputs and normalization, or its pendulums. */
	fun matches(preset: Preset, setting: RigPhysicsEdit): Boolean = when (preset.kind) {
		Kind.INPUT -> preset.inputs == setting.inputs && preset.normalization == setting.normalization
		Kind.PENDULUM -> preset.segments == setting.segments
	}

	/** [setting]'s inputs or pendulums saved as [name]. */
	fun capture(kind: Kind, name: String, setting: RigPhysicsEdit) = when (kind) {
		Kind.INPUT -> Preset(kind, name.trim(), setting.inputs, setting.normalization)
		Kind.PENDULUM -> Preset(kind, name.trim(), segments = setting.segments)
	}

	/**
	 * [setting] with [preset] in place of its inputs (skipping parameters the model lacks or the group
	 * writes) or its pendulums (outputs past the new tip read the tip).
	 */
	fun apply(preset: Preset, setting: RigPhysicsEdit, available: Set<String>): RigPhysicsEdit = when (preset.kind) {
		Kind.INPUT -> setting.copy(
			inputs = preset.inputs.filter { it.parameter in available && it.parameter !in setting.outputParameters },
			normalization = preset.normalization,
		)
		Kind.PENDULUM -> {
			val n = preset.segments.size
			setting.copy(segments = preset.segments,
				outputs = setting.outputs.map { if (it.vertex > n) it.copy(vertex = n) else it }.distinctBy { it.parameter })
		}
	}

	fun listToJson(presets: List<Preset>): String = JsonArray(presets.filterNot { it.builtin }.map { it.toJson() }).toString()

	/** The presets in [text]; unreadable entries are skipped so one bad preset does not lose the rest. */
	fun listFromJson(text: String?): List<Preset> =
		runCatching { Json.parseToJsonElement(text ?: return emptyList()).jsonArray }.getOrNull().orEmpty()
			.mapNotNull { runCatching { Preset.fromJson(it.jsonObject) }.getOrNull() }
}
