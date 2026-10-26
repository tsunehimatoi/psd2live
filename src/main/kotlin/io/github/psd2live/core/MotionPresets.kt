package io.github.psd2live.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * One adjustable setting of a generated motion. [label] names it in the panel (`animation.knob.<label>`);
 * a [toggle] is on at 1 and off at 0, and an [integer] knob takes whole values.
 */
data class MotionPresetKnob(
	val id: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val step: Float = 0.05f,
	val label: String = id,
	val toggle: Boolean = false,
	val integer: Boolean = false,
)

/**
 * How the user tuned one generated motion, by knob id, whether they removed it from the model and, for a
 * skeleton preset, whether it is switched off (the basic motions keep their own switches in the settings).
 */
data class MotionPresetSettings(
	val values: Map<String, Float> = emptyMap(),
	val deleted: Boolean = false,
	val disabled: Boolean = false,
	/** Whether the presets move the `motion/natural` way; from the rig recipe, never saved with the settings. */
	val natural: Boolean = false,
) {
	init {
		require(values.values.all(Float::isFinite)) { "Motion preset settings must be finite" }
	}

	fun value(knob: MotionPresetKnob): Float {
		val raw = values[knob.id]?.coerceIn(knob.min, knob.max) ?: knob.default
		return if (knob.integer || knob.toggle) raw.roundToInt().toFloat() else raw
	}

	val isDefault: Boolean get() = values.isEmpty() && !deleted && !disabled

	/** The saved settings' text, as before [natural] existed: revision identities are made from it. */
	override fun toString(): String = "MotionPresetSettings(values=$values, deleted=$deleted, disabled=$disabled)"

	companion object {
		/** [name]'s settings in [config], moving the way its recipe asks. */
		fun of(config: PipelineConfig, name: String): MotionPresetSettings =
			(config.rigEdits.motionPresets[name] ?: MotionPresetSettings()).copy(natural = natural(config))

		/** Whether [config]'s recipe moves the `motion/natural` way. */
		fun natural(config: PipelineConfig): Boolean =
			config.recipe.base("motion") === io.github.psd2live.core.recipe.RigModules.MOTION_NATURAL
	}
}

/**
 * The generated motions and what each lets the user tune.
 *
 * Every preset takes a speed, which plays it faster or slower, and the knobs that suit what it does: the idle
 * separates the body's sway, the breath and the head; a nod or a head shake has a count; the blink closes
 * further or less and can blink twice; the tail swing has a number of swings; the cute idle a degree of tuck.
 * A gesture's amplitude scales it about its rest, a loop's about its middle, so a smaller setting is the
 * same motion done more gently. The preview and the export read the same tracks.
 */
object MotionPresets {
	const val AMPLITUDE = "amplitude"
	const val SPEED = "speed"
	const val BREATH = "breath"
	const val HEAD = "head"
	const val BLINK = "blink"
	const val COUNT = "count"
	const val CLOSURE = "closure"
	const val DOUBLE = "double"
	const val TUCK = "tuck"

	private val speed = MotionPresetKnob(SPEED, 0.25f, 3f, 1f)
	private fun amplitude(max: Float = 2f, label: String = AMPLITUDE) = MotionPresetKnob(AMPLITUDE, 0f, max, 1f, label = label)
	private val idleKnobs = listOf(
		amplitude(3f, label = "sway"),
		MotionPresetKnob(BREATH, 0f, 2f, 1f),
		MotionPresetKnob(HEAD, 0f, 3f, 1f),
		speed,
		MotionPresetKnob(BLINK, 0f, 1f, 1f, step = 1f, toggle = true),
	)

	/** The knobs of [name] in the order the panel shows them; empty for a name that is not generated. */
	fun knobs(name: String): List<MotionPresetKnob> = when (name.lowercase()) {
		"idle" -> idleKnobs
		"idlecute" -> idleKnobs.take(4) + MotionPresetKnob(TUCK, 0f, 1f, 0.3f) + idleKnobs.last()
		"blink" -> listOf(
			MotionPresetKnob(CLOSURE, 0.2f, 1f, 1f),
			speed,
			MotionPresetKnob(DOUBLE, 0f, 1f, 0f, step = 1f, toggle = true),
		)
		"nod" -> listOf(amplitude(), speed, MotionPresetKnob(COUNT, 1f, 4f, 1f, step = 1f, integer = true))
		"shake" -> listOf(amplitude(), speed, MotionPresetKnob(COUNT, 1f, 4f, 1f, step = 1f, integer = true))
		"tailswing" -> listOf(amplitude(1f), speed, MotionPresetKnob(COUNT, 1f, 8f, 3f, step = 1f, integer = true))
		"crouch" -> listOf(amplitude(1.5f, label = "depth"), speed)
		"cheer" -> listOf(amplitude(1.5f, label = "energy"), speed)
		"headtilt", "wave", "shy", "weightshift", "legkick", "sway" -> listOf(amplitude(1.5f), speed)
		else -> emptyList()
	}

	private fun MotionPresetSettings.of(name: String, id: String): Float =
		knobs(name).firstOrNull { it.id == id }?.let(::value) ?: 1f

	/** Whether [name] loops: the idle and the presets that stand in for it. */
	fun loops(name: String): Boolean = name.equals("Idle", ignoreCase = true) ||
		SkeletonMotions.presets.any { it.loop && it.name.equals(name, ignoreCase = true) }

	/**
	 * The tracks [name] plays as [settings] tune it, the same the export writes. [exclude] keeps
	 * physics-driven parameters out of a loop.
	 */
	fun tracks(
		name: String,
		skeleton: SkeletonSpec?,
		settings: MotionPresetSettings = MotionPresetSettings(),
		exclude: Set<String> = emptySet(),
	): List<MotionTrack> = generated.getOrPut(io.github.psd2live.format.compile.document.ContentHash.of("motion", name.lowercase(),
		skeleton?.toJson(), settings.values.toSortedMap(), settings.deleted, settings.disabled, exclude.sorted(), settings.natural)) {
		generate(name, skeleton, settings, exclude)
	}

	/**
	 * Generated tracks by the content hash of their inputs. The skeleton presets solve the figure frame by
	 * frame, which takes far longer than hashing the skeleton, and every rebuild compiles every preset.
	 */
	private val generated = io.github.psd2live.format.compile.document.GenerationCache<List<MotionTrack>>(capacity = 64)
	internal fun clearCache() = generated.clear()

	private fun generate(name: String, skeleton: SkeletonSpec?, settings: MotionPresetSettings, exclude: Set<String>): List<MotionTrack> {
		val key = name.lowercase()
		val speed = settings.of(name, SPEED)
		val tracks = when (key) {
			"idle", "idlecute" -> {
				val body = if (key == "idle") SkeletonMotions.idle(skeleton, exclude)
				else SkeletonMotions.idleCute(skeleton, exclude, settings.of(name, TUCK))
				if (body.isEmpty()) return emptyList()
				val tuned = idleTuned(body, settings.of(name, AMPLITUDE), settings.of(name, BREATH), settings.of(name, HEAD))
				val blinks = if (settings.natural) MotionGenerator.naturalBlinkTracks + MotionGenerator.glanceTracks else MotionGenerator.idleBlinkTracks
				if (settings.of(name, BLINK) > 0.5f) tuned + blinks else tuned
			}
			"blink" -> if (settings.natural) MotionGenerator.naturalBlink(settings.of(name, CLOSURE), settings.of(name, DOUBLE) > 0.5f)
				else MotionGenerator.blinkTracks(settings.of(name, CLOSURE), settings.of(name, DOUBLE) > 0.5f)
			"nod" -> scaledOneShot(MotionGenerator.nodTracks(settings.of(name, COUNT).roundToInt()), settings.of(name, AMPLITUDE))
			"shake" -> scaledOneShot(MotionGenerator.shakeTracks(settings.of(name, COUNT).roundToInt()), settings.of(name, AMPLITUDE))
			"tailswing" -> SkeletonMotions.tailSwing(skeleton, settings.of(name, AMPLITUDE), settings.of(name, COUNT).roundToInt())
			else -> {
				val preset = SkeletonMotions.presets.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return emptyList()
				val generated = preset.tracks(skeleton)
				if (preset.loop && generated.isNotEmpty()) generated + MotionGenerator.idleBlinkTracks
				else scaledOneShot(generated, settings.of(name, AMPLITUDE))
			}
		}
		return if (speed == 1f) tracks else tracks.map { timed(it, speed) }
	}

	/** How long [name] plays: a loop its whole cycle, a one-shot to its last key. */
	fun duration(name: String, tracks: List<MotionTrack>, settings: MotionPresetSettings = MotionPresetSettings()): Float =
		if (name.equals("Idle", ignoreCase = true)) SkeletonMotions.IDLE_DURATION / settings.of(name, SPEED)
		else tracks.maxOfOrNull { it.keys.last().time } ?: 0f

	/**
	 * [tracks] on the parameters [renames] (an ID conversion's, by standard ID) gave other IDs: generated tracks name
	 * the standard parameters by their standard IDs.
	 */
	fun renamed(tracks: List<MotionTrack>, renames: Map<String, String>): List<MotionTrack> =
		if (renames.isEmpty()) tracks else tracks.map { track -> renames[track.parameterId]?.let { track.copy(parameterId = it) } ?: track }

	/** [name] as a clip, as the editor shows a generated motion before it is edited; [renames] as [renamed] takes them. */
	fun clip(id: String, name: String, skeleton: SkeletonSpec?, settings: MotionPresetSettings = MotionPresetSettings(),
			 renames: Map<String, String> = emptyMap()): MotionClip {
		val tracks = renamed(tracks(name, skeleton, settings), renames)
		return MotionClips.fromTracks(id, name, builtin = name, loop = loops(name), tracks = tracks, duration = duration(name, tracks, settings))
	}

	/** The idle's parts at their own strengths: the breath, the head and the sway of everything else. */
	private fun idleTuned(tracks: List<MotionTrack>, sway: Float, breath: Float, head: Float): List<MotionTrack> {
		val headIds = setOf(StandardParameters.ANGLE_X.raw, StandardParameters.ANGLE_Y.raw, StandardParameters.ANGLE_Z.raw)
		val breathIds = setOf(StandardParameters.BODY_Y.raw, SkeletonPoses.wingFlap.id.raw)
		return tracks.map { track ->
			when (track.parameterId) {
				StandardParameters.BREATH.raw -> scaled(track, breath, 0f)
				in breathIds -> scaled(track, breath, mean(track))
				in headIds -> scaled(track, head, mean(track))
				else -> scaled(track, sway, mean(track))
			}
		}
	}

	/** A one-shot scaled about where it starts, which is its rest; the eyes keep their own range. */
	private fun scaledOneShot(tracks: List<MotionTrack>, amplitude: Float): List<MotionTrack> =
		if (amplitude == 1f) tracks
		else tracks.map { if (it.parameterId in EYE_OPEN) it else scaled(it, amplitude, it.keys.first().value) }

	private val EYE_OPEN = setOf(StandardParameters.EYE_L_OPEN.raw, StandardParameters.EYE_R_OPEN.raw)

	private fun mean(track: MotionTrack): Float {
		val duration = track.keys.last().time
		if (duration <= 0f) return track.keys.first().value
		val samples = 240
		return (0 until samples).sumOf { MotionClips.sample(track, duration * it / samples).toDouble() }.toFloat() / samples
	}

	/** [track] with every value moved [factor] times as far from [about]; the handles keep the shape. */
	internal fun scaled(track: MotionTrack, factor: Float, about: Float): MotionTrack =
		if (factor == 1f) track
		else track.copy(keys = track.keys.map { key ->
			key.copy(
				value = about + (key.value - about) * factor,
				outHandle = key.outHandle.copy(y = key.outHandle.y * factor),
				inHandle = key.inHandle.copy(y = key.inHandle.y * factor),
			)
		})

	/** [track] played [speed] times as fast; handles are fractions of their segment, so they stay. */
	private fun timed(track: MotionTrack, speed: Float): MotionTrack =
		track.copy(keys = track.keys.map { it.copy(time = it.time / speed) })

	fun toJson(settings: Map<String, MotionPresetSettings>): JsonObject = buildJsonObject {
		for ((name, value) in settings) {
			if (value.isDefault) continue
			put(name, buildJsonObject {
				for ((id, v) in value.values) put(id, v)
				if (value.deleted) put("deleted", true)
				if (value.disabled) put("disabled", true)
			})
		}
	}

	fun fromJson(o: JsonObject?): Map<String, MotionPresetSettings> = o.orEmpty().mapNotNull { (name, element) ->
		val fields = element.jsonObject
		val settings = MotionPresetSettings(
			values = fields.filterKeys { it != "deleted" && it != "disabled" }.mapNotNull { (id, v) -> v.jsonPrimitive.floatOrNull?.takeIf(Float::isFinite)?.let { id to it } }.toMap(),
			deleted = fields["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
			disabled = fields["disabled"]?.jsonPrimitive?.booleanOrNull ?: false,
		)
		if (settings.isDefault) null else name to settings
	}.toMap()
}
