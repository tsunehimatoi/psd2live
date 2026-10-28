package io.github.psd2live.core.fit

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The face turning with the head, drawn by a style ([TurnStyle]): at a turn of yaw and pitch degrees the face's middle
 * slides, and each of [TurnStyle.OUTLINE_POINTS] points along its outline goes its own way against the middle - the
 * near side early, the far side folding in, the chin swinging - each by its own timing over the way. Every other point
 * of the face follows a thin-plate spline through them, so the face keeps its own shape. The parts on the face (eyes,
 * brows, mouth, ears, hair) take their own moves from the same pose ([Pose], [FeatureTurn]).
 *
 * Amounts are in the face's width ([unit]); a turn's share is its angle over the parameter's end (45 degrees), a
 * look's over 30. The angles are only where the parameter is: the style says how far the head turns at its end.
 */
class FaceTurn(private val marks: FaceLandmarks, private val setting: TurnStyle.Setting = TurnStyle.Setting(), val style: TurnStyle = TurnStyle.DEFAULT) {
	private val eyeL = marks["eye.L.inner"]?.let { i -> marks["eye.L.outer"]?.let { o -> ((i.x + o.x) / 2) to ((i.y + o.y) / 2) } }
	private val eyeR = marks["eye.R.inner"]?.let { i -> marks["eye.R.outer"]?.let { o -> ((i.x + o.x) / 2) to ((i.y + o.y) / 2) } }
	private val contour = marks.contour
	private val left = contour.minOfOrNull { it.first } ?: 0f
	private val right = contour.maxOfOrNull { it.first } ?: 0f

	/** The face's middle: halfway between its eyes. */
	val centre = if (eyeL != null && eyeR != null) ((eyeL.first + eyeR.first) / 2) to ((eyeL.second + eyeR.second) / 2) else 0f to 0f

	/** The face's width: the unit of every move. */
	val unit: Float = right - left
	/** The face's top and its chin (its outline's highest and lowest points), and its eye line. */
	val top: Float = contour.minOfOrNull { it.second } ?: 0f
	val chin: Float = contour.maxOfOrNull { it.second } ?: 0f
	val eyeLine: Float = listOf("L", "R").flatMap { s -> listOf("inner", "outer", "apex", "lowLid").mapNotNull { marks["eye.$s.$it"]?.y } }
		.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: centre.second
	/** The face's middle across: halfway between its outline's sides. */
	val middle: Float = (left + right) / 2f

	/** Whether the face has what the style needs: both eyes and an outline. */
	val usable: Boolean = eyeL != null && eyeR != null && contour.size >= 40 && unit > 0f && chin > eyeLine

	private val ends = style.ends(setting)

	/** The outline's points the style moves: evenly by arc length from the chin, toward +x first, so point k mirrors point n - k. */
	private val ring: IntArray = run {
		if (contour.size < TurnStyle.OUTLINE_POINTS) return@run IntArray(0)
		val n = contour.size
		val start = contour.indices.maxBy { contour[it].second }
		val step = if (contour[(start + 1) % n].first >= contour[(start - 1 + n) % n].first) 1 else -1
		val order = IntArray(n) { (start + it * step + n * 2) % n }
		val arc = FloatArray(n + 1)
		for (k in 1..n) arc[k] = arc[k - 1] + hypot(contour[order[k % n]].first - contour[order[k - 1]].first, contour[order[k % n]].second - contour[order[k - 1]].second)
		IntArray(TurnStyle.OUTLINE_POINTS) { j ->
			val s = arc[n] * j / TurnStyle.OUTLINE_POINTS
			order[(0 until n).minBy { abs(arc[it] - s) }]
		}
	}

	/**
	 * The head at one pose: [sx] the turn's side (+1 toward +x), [moves] the face's moves and [hair] the hair's role
	 * fields there, [slide] the face's middle's move, [field] where every point of the face goes.
	 */
	inner class Pose internal constructor(val sx: Float, val moves: TurnStyle.Moves, val hair: TurnStyle.Moves) {
		val slide: Pair<Float, Float> = moves["slide_x"] * sx * unit to moves["slide_y"] * unit
		val field: (Float, Float) -> Pair<Float, Float> = run {
			if (!usable || ring.isEmpty()) return@run { x: Float, y: Float -> x to y }
			val from = ArrayList<Pair<Float, Float>>(); val to = ArrayList<Pair<Float, Float>>()
			val n = ring.size
			for (j in 0 until n) {
				val p = contour[ring[if (sx > 0f) j else (n - j) % n]]
				val k = "c%02d".format(j)
				from += p; to += (p.first + slide.first + moves["${k}_x"] * sx * unit) to (p.second + slide.second + moves["${k}_y"] * unit)
			}
			// The face's middle, from between the eyes down past the nose, slides whole.
			val length = chin - eyeLine
			for (c in -1..1) for (r in 0..4) {
				val p = (middle + c * 0.12f * unit) to (eyeLine + length * (0.05f + r * 0.125f))
				from += p; to += (p.first + slide.first) to (p.second + slide.second)
			}
			val tps = ThinPlateSpline(from, to, 1e-3)
			return@run { x: Float, y: Float -> tps.map(x, y) }
		}

		/** The outline's furthest point toward [outward] (+1 or -1) in a band about [y], at this pose ([moved]) or at rest. */
		fun outlineAt(y: Float, outward: Float, moved: Boolean): Float? {
			val band = 0.05f * (chin - eyeLine)
			var best: Float? = null
			for (p in contour) {
				val q = if (moved) field(p.first, p.second) else p
				if (abs(q.second - y) >= band) continue
				if (best == null || q.first * outward > best * outward) best = q.first
			}
			return best
		}
	}

	/** The head at [yaw] degrees (+ toward +x) and [pitch] (+ up). */
	fun pose(yaw: Float, pitch: Float): Pose {
		val px = yaw / TURN_END; val py = pitch / LOOK_END
		return Pose(if (yaw >= 0f) 1f else -1f, style.at(ends, px, py), style.hairAt(ends, px, py))
	}

	/** Where the face's points go at [yaw] and [pitch]. */
	fun map(yaw: Float, pitch: Float): (Float, Float) -> Pair<Float, Float> =
		if (!usable || yaw == 0f && pitch == 0f) { x, y -> x to y } else pose(yaw, pitch).field

	companion object {
		/** The turn's and the look's parameter ends, in degrees. */
		const val TURN_END = 45f
		const val LOOK_END = 30f
		/** The keys the face's warps are drawn at: the turn's in quarters of the way, the look's in halves. */
		val YAW_KEYS = floatArrayOf(-45f, -33.75f, -22.5f, -11.25f, 0f, 11.25f, 22.5f, 33.75f, 45f)
		val PITCH_KEYS = floatArrayOf(-30f, -15f, 0f, 15f, 30f)
	}
}
