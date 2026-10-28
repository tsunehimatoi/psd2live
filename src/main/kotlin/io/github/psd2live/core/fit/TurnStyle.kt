package io.github.psd2live.core.fit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * How a head turns, as a style: named moves of the face's parts (its outline's points, each eye's and mouth's own
 * linear change, the iris in its opening, the brows, the ears, headwear and the hair's role fields - all in the face's
 * width, mirrored so the turn goes toward +x) given at the ends of the turn and the look, and how each spreads over the
 * way there. A drawn head turn is not one shape scaled: the far eye narrows late, the near outline goes early, the two
 * eyes close up after half the turn - each move keeps its own timing.
 *
 * The values are the style's default plus what each artistic slider adds per unit ([Setting.sliders]); the hair's
 * sliders scale whole groups of its role fields ([Setting.hair]). The table is `/rig/head-turn-style.json`.
 */
class TurnStyle private constructor(
	val names: List<String>,
	private val poses: List<String>,
	private val base: Map<String, FloatArray>,
	private val sliders: Map<String, Map<String, FloatArray>>,
	private val timingX: Map<String, FloatArray>,
	private val timingY: Map<String, FloatArray>,
	val hairNames: List<String>,
	private val hairPoses: List<String>,
	private val hairBase: Map<String, FloatArray>,
	private val hairSliders: Map<String, List<String>>,
	private val presets: Map<String, Pair<Map<String, FloatArray>, Map<String, FloatArray>?>>,
) {
	private val index = names.withIndex().associate { it.value to it.index }
	private val hairIndex = hairNames.withIndex().associate { it.value to it.index }

	/**
	 * A setting of the style: the style it starts from ([preset], one of [PRESETS]; the default when null), how far it
	 * turns ([turn]) and looks up and down ([look]) against that style's own ends, the artistic sliders ([SLIDERS], one
	 * unit is one spread of what it moves) and the hair's ([HAIR_SLIDERS], a share more or less of what it scales).
	 */
	data class Setting(val turn: Float = 1f, val look: Float = 1f, val sliders: Map<String, Float> = emptyMap(), val hair: Map<String, Float> = emptyMap(),
	                   val preset: String? = null)

	/** The moves' values at the end poses (`X`, `up`, `down`, `Xup`, `Xdown`) and the hair's (those and their `X-` mirrors) under a setting. */
	class Ends internal constructor(internal val face: Map<String, FloatArray>, internal val hair: Map<String, FloatArray>)

	/** The moves' values at one pose, by name; a name the style lacks reads 0. */
	class Moves internal constructor(private val index: Map<String, Int>, private val values: FloatArray) {
		operator fun get(name: String): Float = index[name]?.let { values[it] } ?: 0f
	}

	fun ends(setting: Setting): Ends {
		val preset = setting.preset?.let { presets[it] }
		val faceBase = preset?.first ?: base; val hairBase = preset?.second ?: this.hairBase
		val face = poses.associateWith { pose ->
			val k = when (pose) { "X" -> setting.turn; "up", "down" -> setting.look; else -> setting.turn * setting.look }
			val v = faceBase.getValue(pose).copyOf()
			for ((name, amount) in setting.sliders) {
				val add = sliders[name]?.get(pose) ?: continue
				for (i in v.indices) v[i] += amount * add[i]
			}
			for (i in v.indices) v[i] *= k
			v
		}
		val hair = hairPoses.associateWith { pose ->
			val k = when (pose) { "X", "X-" -> setting.turn; "up", "down" -> setting.look; else -> setting.turn * setting.look }
			val v = hairBase.getValue(pose).copyOf()
			for (i in v.indices) v[i] *= k
			for ((name, amount) in setting.hair) for (n in hairSliders[name].orEmpty()) hairIndex[n]?.let { v[it] *= 1f + amount }
			v
		}
		return Ends(face, hair)
	}

	/**
	 * The face's moves at [px] of the turn (0..1, its sign apart) and [py] of the look (-1..1, + up): each move's turn
	 * and look by its own timing, and on a corner pose the corner's own change on top, at the product of the two timings.
	 */
	fun at(ends: Ends, px: Float, py: Float): Moves {
		val ax = abs(px).coerceAtMost(1f); val ay = abs(py).coerceAtMost(1f)
		val x = ends.face.getValue("X"); val y = ends.face.getValue(if (py >= 0f) "up" else "down"); val c = ends.face.getValue(if (py >= 0f) "Xup" else "Xdown")
		val out = FloatArray(names.size)
		for ((i, n) in names.withIndex()) {
			val gx = timing(timingX[n], ax); val gy = timing(timingY[n], ay)
			out[i] = x[i] * gx + y[i] * gy + (c[i] - x[i] - y[i]) * gx * gy
		}
		return Moves(index, out)
	}

	/** The hair's role fields at [px] of the turn (signed: the `X-` poses for a turn toward -x) and [py] of the look, linear in both. */
	fun hairAt(ends: Ends, px: Float, py: Float): Moves {
		val side = if (px >= 0f) "" else "-"
		val ax = abs(px).coerceAtMost(1f); val ay = abs(py).coerceAtMost(1f)
		val x = ends.hair.getValue("X$side"); val y = ends.hair.getValue(if (py >= 0f) "up" else "down")
		val c = ends.hair.getValue("X$side" + (if (py >= 0f) "up" else "down"))
		return Moves(hairIndex, FloatArray(hairNames.size) { i -> x[i] * ax + y[i] * ay + (c[i] - x[i] - y[i]) * ax * ay })
	}

	/** A move's share of its end value at [p] of the way: p + a p(1-p) + b p(1-p)(2p-1), a lag when a < 0. */
	private fun timing(ab: FloatArray?, p: Float): Float {
		if (ab == null || p <= 0f) return 0f
		if (p >= 1f) return 1f
		val q = p * (1f - p)
		return p + ab[0] * q + ab[1] * q * (2f * p - 1f)
	}

	companion object {
		/** The artistic sliders, in the table's names. */
		val SLIDERS = listOf("depth", "perspective", "gaze_hold", "cheek_swing", "corner_roll", "nod_depth", "accessory")
		val HAIR_SLIDERS = listOf("hair_follow", "hair_lead", "back_parallax")
		/**
		 * The styles a setting can start from besides the default, each a whole turn on the style's axes: bold (deep, the
		 * gaze held, features and headwear leading), dramatic (a wide turn and look, a strong perspective), wide (a wide
		 * turn, the cheeks swinging), nod (a flat turn, a wide look up and down), soft (flat and small).
		 */
		val PRESETS = listOf("bold", "dramatic", "wide", "nod", "soft")
		/** The roles a front hair piece's points take by where they lie, and a back piece's. */
		val FRONT_ROLES = listOf("bangs", "side", "crown", "tail")
		val BACK_ROLES = listOf("back", "back_tail")
		/** Each role field's eleven values, in the table's order. */
		val HAIR_PARAMS = listOf("a0", "a1", "a2", "s", "s2", "sv", "c0", "c1", "c2", "t", "tv")
		/** The face's outline's points the style moves, evenly along it from the chin. */
		const val OUTLINE_POINTS = 24

		val DEFAULT: TurnStyle by lazy {
			val text = checkNotNull(TurnStyle::class.java.getResourceAsStream("/rig/head-turn-style.json")).use { it.readBytes().decodeToString() }
			parse(Json.parseToJsonElement(text).jsonObject)
		}

		private fun floats(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
		private fun strings(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { it.jsonPrimitive.content }
		private fun byPose(o: JsonObject) = o.mapValues { floats(it.value) }

		private fun parse(root: JsonObject): TurnStyle {
			val face = root.getValue("face").jsonObject; val hair = root.getValue("hair").jsonObject
			val timing = face.getValue("timing").jsonObject
			return TurnStyle(
				strings(face.getValue("names")), strings(face.getValue("poses")), byPose(face.getValue("base").jsonObject),
				face.getValue("sliders").jsonObject.mapValues { byPose(it.value.jsonObject) },
				timing.getValue("x").jsonObject.mapValues { floats(it.value) }, timing.getValue("y").jsonObject.mapValues { floats(it.value) },
				strings(hair.getValue("names")), strings(hair.getValue("poses")), byPose(hair.getValue("base").jsonObject),
				hair.getValue("sliders").jsonObject.mapValues { strings(it.value) },
				root["presets"]?.jsonObject.orEmpty().mapValues { (_, v) ->
					val o = v.jsonObject
					byPose(o.getValue("face").jsonObject) to o["hair"]?.let { byPose(it.jsonObject) }
				},
			)
		}
	}
}
