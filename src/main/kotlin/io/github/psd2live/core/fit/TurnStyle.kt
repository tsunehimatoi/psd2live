package io.github.psd2live.core.fit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * How a head turns, as a style: named moves of the face's parts (its outline's points, each eye's and mouth's own
 * linear change, the iris in its opening, the brows, the ears, headwear - in the face's width, mirrored so the turn goes
 * toward +x) and the hair sheet's coefficients ([HairTurn]), each a smooth surface over the pose ([Surface]): its
 * course over the turn and the look together, so each move keeps its own timing - the far eye narrowing late, the near
 * outline going early - and a corner pose its own change.
 *
 * A setting starts from the default style or a preset ([PRESETS]), adds what each artistic slider gives per unit
 * ([Setting.sliders]), scales the hair's groups ([Setting.hair]) and the turn's and the look's amounts. The style is the
 * binary `/rig/head-turn-style.bin`: per value only the terms it needs, as small integer codes.
 */
class TurnStyle private constructor(
	val names: List<String>,
	private val face: Surface,
	private val faceVectors: Map<String, Array<FloatArray>>,
	val hairNames: List<String>,
	private val hair: Surface,
	private val hairVectors: Map<String, Array<FloatArray>>,
	/** The hair sheet's heights down the head and its reach across ([HairTurn]). */
	val hairKnots: FloatArray,
	val hairReach: Float,
	private val hairSliders: Map<String, IntArray>,
) {
	private val index = names.withIndex().associate { it.value to it.index }
	private val hairIndex = hairNames.withIndex().associate { it.value to it.index }

	/**
	 * A setting of the style: the style it starts from ([preset], one of [PRESETS]; the default when null), how far it
	 * turns ([turn]) and looks up and down ([look]) against that style's own, the artistic sliders ([SLIDERS], one unit
	 * is one spread of what it moves) and the hair's ([HAIR_SLIDERS], a share more or less of what it scales).
	 */
	data class Setting(val turn: Float = 1f, val look: Float = 1f, val sliders: Map<String, Float> = emptyMap(), val hair: Map<String, Float> = emptyMap(),
	                   val preset: String? = null)

	/** A setting's surfaces: per value, its coefficient on each of the surface's terms. */
	class Ends internal constructor(internal val face: Array<FloatArray>, internal val hair: Array<FloatArray>)

	/** The moves' values at one pose, by name; a name the style lacks reads 0. */
	class Moves internal constructor(private val index: Map<String, Int>, private val values: FloatArray) {
		operator fun get(name: String): Float = index[name]?.let { values[it] } ?: 0f
	}

	fun ends(setting: Setting): Ends {
		val f = (setting.preset?.let { faceVectors["preset:$it"] } ?: faceVectors.getValue("default")).map { it.copyOf() }.toTypedArray()
		for ((name, amount) in setting.sliders) {
			val add = faceVectors["slider:$name"] ?: continue
			for (t in f.indices) for (j in f[t].indices) f[t][j] += amount * add[t][j]
		}
		face.scale(f, setting.turn, setting.look)
		val h = (setting.preset?.let { hairVectors["preset:$it"] } ?: hairVectors.getValue("default")).map { it.copyOf() }.toTypedArray()
		for ((name, amount) in setting.hair) for (j in hairSliders[name] ?: IntArray(0)) for (t in h.indices) h[t][j] *= 1f + amount
		hair.scale(h, setting.turn, setting.look)
		return Ends(f, h)
	}

	/** The face's moves at [px] of the turn (its sign apart) and [py] of the look (-1..1, + up). */
	fun at(ends: Ends, px: Float, py: Float): Moves =
		Moves(index, face.at(ends.face, kotlin.math.abs(px).coerceAtMost(1f), py.coerceIn(-1f, 1f)))

	/** The hair sheet's coefficients at [px] of the turn (signed: + where the face's middle goes toward +x) and [py] of the look. */
	fun hairAt(ends: Ends, px: Float, py: Float): Moves =
		Moves(hairIndex, hair.at(ends.hair, px.coerceIn(-1f, 1f), py.coerceIn(-1f, 1f)))

	/**
	 * A value's course over the pose: the turn's share a (0..1, or -1..1 when [signed]) and the look's p (-1..1) as
	 * `sum c_i0 U_i(a) + sum c_0j V_j(p) + sum c_ij U_i(a) V_j(p)`, with U_i(a) = T_i(2a - 1) - T_i(-1) (or T_i(a) - T_i(0)
	 * signed), V_j(p) = T_j(p) - T_j(0), T Chebyshev's polynomials: 0 at rest, the first sum the pure turn, the second
	 * the pure look, the third only a corner's own change.
	 */
	class Surface(val degreeTurn: Int, val degreeLook: Int, val signed: Boolean) {
		/** The terms' (i, j), in the file's order: the turn's, the look's, then the corner's. */
		val terms: List<Pair<Int, Int>> = (1..degreeTurn).map { it to 0 } + (1..degreeLook).map { 0 to it } +
			(1..degreeTurn).flatMap { i -> (1..degreeLook).map { j -> i to j } }

		private fun cheb(n: Int, x: Float) = FloatArray(n + 1).also { t ->
			t[0] = 1f; if (n >= 1) t[1] = x
			for (k in 2..n) t[k] = 2f * x * t[k - 1] - t[k - 2]
		}

		fun basis(a: Float, p: Float): FloatArray {
			val u = cheb(degreeTurn, if (signed) a else 2f * a - 1f); val u0 = cheb(degreeTurn, if (signed) 0f else -1f)
			val v = cheb(degreeLook, p); val v0 = cheb(degreeLook, 0f)
			return FloatArray(terms.size) { k ->
				val (i, j) = terms[k]
				when {
					j == 0 -> u[i] - u0[i]
					i == 0 -> v[j] - v0[j]
					else -> (u[i] - u0[i]) * (v[j] - v0[j])
				}
			}
		}

		/** Every value's at (a, p), from its coefficients [c] (per term, per value). */
		fun at(c: Array<FloatArray>, a: Float, p: Float): FloatArray {
			val b = basis(a, p); val out = FloatArray(c[0].size)
			for (k in b.indices) { val w = b[k]; if (w == 0f) continue; val row = c[k]; for (j in out.indices) out[j] += w * row[j] }
			return out
		}

		/** The turn's terms times [turn], the look's times [look], the corner's times both. */
		fun scale(c: Array<FloatArray>, turn: Float, look: Float) {
			for ((k, t) in terms.withIndex()) {
				val s = if (t.second == 0) turn else if (t.first == 0) look else turn * look
				for (j in c[k].indices) c[k][j] *= s
			}
		}
	}

	companion object {
		/** The artistic sliders, in the style's names. */
		val SLIDERS = listOf("depth", "perspective", "gaze_hold", "cheek_swing", "corner_roll", "nod_depth", "accessory")
		val HAIR_SLIDERS = listOf("hair_follow", "hair_lead", "back_parallax")
		/**
		 * The styles a setting can start from besides the default, each a whole turn on the style's axes: bold (deep, the
		 * gaze held, features and headwear leading), dramatic (a wide turn and look, a strong perspective), wide (a wide
		 * turn, the cheeks swinging), nod (a flat turn, a wide look up and down), soft (flat and small).
		 */
		val PRESETS = listOf("bold", "dramatic", "wide", "nod", "soft")
		/** The hair sheet's fields - the hair before the face and behind it, across and down - in the style's names (`front_x_0`, ...). */
		val HAIR_FIELDS = listOf("front_x", "front_y", "back_x", "back_y")
		/** Its terms at each height: 1, across, across squared, down the lock. */
		const val HAIR_TERMS = 4
		/** The face's outline's points the style moves, evenly along it from the chin. */
		const val OUTLINE_POINTS = 24

		val DEFAULT: TurnStyle by lazy {
			read(checkNotNull(TurnStyle::class.java.getResourceAsStream("/rig/head-turn-style.bin")).use { it.readBytes() })
		}

		/** Reads the style's binary: `P2LT`, a version, then the deflated body (the face's section, the hair's, the hair's frame and sliders). */
		fun read(bytes: ByteArray): TurnStyle {
			require(bytes.size > 6 && String(bytes, 0, 4, Charsets.US_ASCII) == "P2LT") { "Not a head-turn style" }
			val inflater = Inflater().apply { setInput(bytes, 6, bytes.size - 6) }
			val body = java.io.ByteArrayOutputStream().also { out ->
				val buf = ByteArray(1 shl 16)
				while (!inflater.finished()) { val n = inflater.inflate(buf); if (n == 0 && inflater.needsInput()) break; out.write(buf, 0, n) }
				inflater.end()
			}.toByteArray()
			val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
			fun name(): String { val n = b.get().toInt() and 0xff; val a = ByteArray(n); b.get(a); return String(a, Charsets.UTF_8) }
			fun section(): Triple<List<String>, Surface, Map<String, Array<FloatArray>>> {
				val n = b.short.toInt() and 0xffff
				val names = List(n) { name() }
				val surface = Surface(b.get().toInt(), b.get().toInt(), b.get().toInt() != 0)
				val masks = IntArray(n) { b.int }
				val vectors = LinkedHashMap<String, Array<FloatArray>>()
				repeat(b.get().toInt() and 0xff) {
					val vname = name()
					val widths = IntArray(n) { b.get().toInt() and 0xff }
					val scales = FloatArray(n) { java.lang.Float.float16ToFloat(b.short) }
					val c = Array(surface.terms.size) { FloatArray(n) }
					for (j in 0 until n) for (t in surface.terms.indices) if ((masks[j] ushr t) and 1 == 1)
						c[t][j] = (if (widths[j] == 1) b.get().toFloat() else b.short.toFloat()) * scales[j]
					vectors[vname] = c
				}
				return Triple(names, surface, vectors)
			}
			val (fn, fs, fv) = section(); val (hn, hs, hv) = section()
			val knots = FloatArray(b.int) { b.float }; val reach = b.float
			val sliders = HashMap<String, IntArray>()
			repeat(b.get().toInt() and 0xff) { val nm = name(); sliders[nm] = IntArray(b.short.toInt() and 0xffff) { b.short.toInt() and 0xffff } }
			return TurnStyle(fn, fs, fv, hn, hs, hv, knots, reach, sliders)
		}
	}
}
