package io.github.psd2live.core.fit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The face turning with the head, as a 2D field over the face built from its own feature points ([FaceLandmarks]): at
 * a turn of [yaw] and [pitch] degrees every point of the face goes where [map] sends it. It is not a solid turned and
 * projected; it is how hand-made 2D heads are drawn turning, and it keeps the face's own shape:
 *
 * - **The side turning away straightens, the side turning toward keeps its shape and grows structure.** Turned sideways,
 *   the side moving away from the viewer sweeps in: its temple and cheek go with the face's middle while its jaw angle
 *   stays, so the cheek between them is drawn as one straight line meeting the forehead and the jaw in corners. The side
 *   turning toward the viewer holds still - its cheekbone stands out as the face slides past it - and folds in at its own
 *   cheek-to-jaw turn, as deep as its jaw angle is sharp: a square jaw steps, a round one only dips. Looking up, the chin
 *   is the side turning away and flattens; looking down, the forehead does, and the jaw's sides rise past the chin.
 * - **The front leads.** The middle of the face - the brows' meeting, the nose, the mouth, the chin - goes further than
 *   the cheeks, and a band along the far outline keeps its width, so the face's middle swells toward the turn while its
 *   far side stays a side.
 * - **A corner pose rolls.** Turned and looking up, the far side drops; turned and looking down, it rises - the face's
 *   level lines bend into an arc seen from one end - and looking down the moves are a little short of the two added.
 *
 * Amounts are in a unit of the face's own width (W, see [w]) at 30 degrees, scaled by [strength]; turning follows the
 * sine of the angle.
 * Points where no rule says otherwise follow a thin-plate spline through the ruled ones, so the face's inside is carried
 * by its edges and its middle.
 */
class FaceTurn(private val marks: FaceLandmarks, private val strength: Float = 1f) {
	private val eyeL = marks["eye.L.inner"]?.let { i -> marks["eye.L.outer"]?.let { o -> ((i.x + o.x) / 2) to ((i.y + o.y) / 2) } }
	private val eyeR = marks["eye.R.inner"]?.let { i -> marks["eye.R.outer"]?.let { o -> ((i.x + o.x) / 2) to ((i.y + o.y) / 2) } }
	private val centre = if (eyeL != null && eyeR != null) ((eyeL.first + eyeR.first) / 2) to ((eyeL.second + eyeR.second) / 2) else 0f to 0f
	private val contour = marks.contour
	/**
	 * The unit of the moves: the face's width at the eye line over [FACE_PER_UNIT], so a face drawn with eyes set wide or
	 * close turns by its own size, not its eyes' spacing.
	 */
	val unit: Float get() = w

	private val w: Float = run {
		if (eyeL == null || eyeR == null || contour.size < 40) return@run 0f
		val l = marks["face.eyeLine.L"]; val r = marks["face.eyeLine.R"]
		val across = if (l != null && r != null) abs(l.x - r.x) else contour.maxOf { it.first } - contour.minOf { it.first }
		across / FACE_PER_UNIT
	}

	/** Whether the face has what the field needs: both eyes, an outline and the outline's points. */
	val usable: Boolean = w > 0f && contour.size >= 40 && listOf("face.chin", "face.jaw.L", "face.jaw.R", "face.cheekbone.L", "face.cheekbone.R",
		"face.temple.L", "face.temple.R", "face.top").all { marks[it] != null }

	private fun index(id: String): Int? = marks[id]?.let { p -> contour.indices.minByOrNull { hypot(contour[it].first - p.x, contour[it].second - p.y) } }

	/** A sign for each side of the canvas: the character's left (L) is on the canvas's right. */
	private fun sideSign(side: String) = if (side == "L") 1f else -1f

	/**
	 * Where each ruled point goes at a pure turn of [s] (the turn's share of 30 degrees' sine, signed: + toward the
	 * canvas's right) and a pure look of [t] (+ up): pairs of rest point and moved point.
	 */
	private fun rules(s: Float, t: Float): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
		val out = ArrayList<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
		val n = contour.size
		// The outline: each point's horizontal and vertical move.
		val dx = FloatArray(n); val dy = FloatArray(n)
		val chin = index("face.chin")!!; val top = index("face.top")!!
		// Along the outline, from each side's points, moves pinned at the points and linear between them by arc length.
		fun along(from: Int, to: Int, step: Int): List<Int> { val list = ArrayList<Int>(); var i = from; while (i != to) { list += i; i = (i + step + n) % n }; list += to; return list }
		val faceW = contour.maxOf { it.first } - contour.minOf { it.first }
		for (side in listOf("L", "R")) {
			val sign = sideSign(side)
			// Which way round from the chin reaches this side.
			val probe = (chin + n / 8) % n
			val step = if ((contour[probe].first - centre.first) * sign > 0) 1 else -1
			val path = along(chin, top, step)
			val ids = listOf("face.chin", "face.jaw.$side", "face.cheekJaw.$side", "face.cheekbone.$side", "face.temple.$side", "face.top")
			val at = ids.associateWith { id -> index(id)?.let { path.indexOf(it) }?.takeIf { it >= 0 } }
			// A side turning away when the turn goes toward the other side.
			val away = s * sign < 0f
			val turn = abs(s)
			// Sideways moves (canvas x) of each point.
			val lead = LEAD_MIDDLE * w * s
			val mx = HashMap<String, Float>()
			mx["face.chin"] = LEAD_CHIN * w * s
			mx["face.top"] = LEAD_TOP * w * s
			if (away) {
				mx["face.temple.$side"] = lead * SWEEP; mx["face.cheekbone.$side"] = lead * SWEEP
				mx["face.cheekJaw.$side"] = lead * SWEEP; mx["face.jaw.$side"] = lead * HOLD_NEAR_JAW
			} else {
				mx["face.temple.$side"] = lead * PIN; mx["face.cheekbone.$side"] = lead * PIN
				mx["face.cheekJaw.$side"] = lead * PIN; mx["face.jaw.$side"] = lead * PIN_FAR_JAW
			}
			// Up and down (canvas y; + down): looking up the bottom rises past the sides, looking down the top drops past them.
			val my = HashMap<String, Float>()
			if (t > 0f) {
				my["face.chin"] = -LIFT_CHIN * w * t; my["face.jaw.$side"] = -LIFT_JAW * w * t; my["face.cheekJaw.$side"] = -LIFT_SIDE * w * t
				my["face.cheekbone.$side"] = -LIFT_SIDE * w * t; my["face.temple.$side"] = -LIFT_TEMPLE * w * t; my["face.top"] = -LIFT_TOP * w * t
			} else if (t < 0f) {
				val d = -t
				my["face.chin"] = DROP_CHIN * w * d; my["face.jaw.$side"] = DROP_JAW * w * d; my["face.cheekJaw.$side"] = DROP_SIDE * w * d
				my["face.cheekbone.$side"] = DROP_SIDE * w * d; my["face.temple.$side"] = DROP_TEMPLE * w * d; my["face.top"] = DROP_TOP * w * d
			}
			val known = ids.filter { at[it] != null }.sortedBy { at[it]!! }
			// Every point between two ruled ones moves between their moves, by its share of the arc between them.
			for (k in 0 until known.size - 1) {
				val a = known[k]; val b = known[k + 1]
				val ia = at[a]!!; val ib = at[b]!!
				val len = arc(path, ia, ib)
				for (j in ia..ib) {
					val f = if (len.last() > 0f) len[j - ia] / len.last() else 0f
					dx[path[j]] = (mx[a] ?: 0f) + ((mx[b] ?: 0f) - (mx[a] ?: 0f)) * f
					dy[path[j]] = (my[a] ?: 0f) + ((my[b] ?: 0f) - (my[a] ?: 0f)) * f
				}
			}
			// The side turning away: from its jaw angle to its temple it is drawn as one straight line between their new
			// places, the more so the further the turn.
			val ja = at["face.jaw.$side"]; val te = at["face.temple.$side"]
			if (away && turn > 0f && ja != null && te != null && te > ja) {
				val len = arc(path, ja, te)
				val pa = contour[path[ja]]; val pb = contour[path[te]]
				val ax = pa.first + dx[path[ja]]; val ay = pa.second + dy[path[ja]]
				val bx = pb.first + dx[path[te]]; val by = pb.second + dy[path[te]]
				val k2 = min(1f, turn * STRAIGHTEN)
				for (j in ja..te) {
					val i = path[j]
					val f = if (len.last() > 0f) len[j - ja] / len.last() else 0f
					val lx = ax + (bx - ax) * f; val ly = ay + (by - ay) * f
					val kx = contour[i].first + dx[i]; val ky = contour[i].second + dy[i]
					dx[i] = kx + (lx - kx) * k2 - contour[i].first
					dy[i] = ky + (ly - ky) * k2 - contour[i].second
				}
			}
			// The side turning toward: the cheek folds in at its cheek-to-jaw turn, as deep as the jaw angle is sharp.
			if (!away && turn > 0f) {
				val cj = at["face.cheekJaw.$side"]; val jaw = at["face.jaw.$side"]; val cb = at["face.cheekbone.$side"]
				if (cj != null && jaw != null && cb != null) {
					val sharp = sharpness(path[jaw])
					val depth = NOTCH * w * turn * sharp
					val reach = max(2, (cb - jaw) / 2)
					for (j in (cj - reach)..(cj + reach)) {
						if (j < 0 || j >= path.size) continue
						val f = 1f - abs(j - cj).toFloat() / (reach + 1)
						dx[path[j]] -= sign * depth * f * f * (3f - 2f * f)
					}
				}
				// Where the far eye's outer corner nearly touches the outline, the outline dips at its height: the eye sits in its socket below the brow and before the cheekbone.
				marks["eye.$side.outer"]?.let { corner ->
					val gap = marks.measures["eye.$side.outerToContour"] ?: return@let
					if (gap < w * NEAR_CORNER) {
						val j = path.indices.minByOrNull { abs(contour[path[it]].second - corner.y) } ?: return@let
						val depth = min(NOTCH_EYE * w * turn, gap * 0.5f) * (1f - gap / (w * NEAR_CORNER))
						val reach = max(2, path.size / 24)
						for (q in (j - reach)..(j + reach)) {
							if (q < 0 || q >= path.size) continue
							val f = 1f - abs(q - j).toFloat() / (reach + 1)
							dx[path[q]] -= sign * depth * f * f * (3f - 2f * f)
						}
					}
				}
			}
		}
		// Every fourth outline point, and a band inside the far outline moving with it so the side keeps its width.
		for (i in contour.indices step 3) out += contour[i] to (contour[i].first + dx[i] to contour[i].second + dy[i])
		if (s != 0f) {
			val band = faceW * BAND
			for (i in contour.indices step 6) {
				val p = contour[i]
				val sign = if (p.first > centre.first) 1f else -1f
				if (s * sign <= 0f || abs(p.first - centre.first) < faceW * 0.3f) continue
				val q = p.first - sign * band to p.second
				// Clear of the eyes and the cheeks, which lead the band and would cross it.
				if (abs(q.second - centre.second) < w * 0.6f) continue
				if (listOfNotNull(eyeL, eyeR, marks["face.cheekTop.L"]?.let { it.x to it.y }, marks["face.cheekTop.R"]?.let { it.x to it.y })
						.any { hypot(it.first - q.first, it.second - q.second) < w * 0.6f }) continue
				out += q to (q.first + dx[i] to q.second + dy[i])
			}
		}
		// The middle: the brows' meeting, the nose, the mouth, the chin's middle, the eyes - the front leads.
		// Looking up the middle rises together; looking down it drops from the top, the mouth and the chin least, so the
		// lower face shortens and nothing slides past the chin.
		fun mid(id: String, lx: Float, up: Float, down: Float) { marks[id]?.let { p -> out += (p.x to p.y) to (p.x + lx * w * s to p.y - (if (t >= 0f) up else down) * w * t) } }
		mid("face.browCentre", LEAD_MIDDLE, LIFT_MIDDLE, DROP_MIDDLE)
		mid("nose.tip", LEAD_NOSE, LIFT_NOSE, DROP_NOSE)
		mid("mouth.upperMid", LEAD_MOUTH, LIFT_MOUTH, DROP_MOUTH)
		mid("face.chinCentre", (LEAD_MOUTH + LEAD_CHIN) / 2, LIFT_MOUTH, DROP_CHIN * 1.2f)
		for (side in listOf("L", "R")) {
			val e = if (side == "L") eyeL else eyeR
			val toward = s * sideSign(side) > 0f
			// The eyes and, further, the brows stand before the face's middle: the near ones lead it, the far ones lag less than the cheek.
			e?.let { p -> out += p to (p.first + (if (toward) LEAD_FAR_EYE else LEAD_NEAR_EYE) * w * s to p.second - (if (t >= 0f) LIFT_EYE else DROP_EYE) * w * t) }
			val brow = listOfNotNull(marks["brow.$side.head"], marks["brow.$side.peak"], marks["brow.$side.tail"])
			if (brow.isNotEmpty()) {
				val b = brow.map { it.x }.average().toFloat() to brow.map { it.y }.average().toFloat()
				out += b to (b.first + (if (toward) LEAD_FAR_BROW else LEAD_NEAR_BROW) * w * s to b.second - (if (t >= 0f) LIFT_BROW else DROP_BROW) * w * t)
			}
			marks["face.cheekTop.$side"]?.let { p -> out += (p.x to p.y) to (p.x + (if (toward) LEAD_FAR_EYE else LEAD_NEAR_EYE) * 0.8f * w * s to p.y - (if (t >= 0f) LIFT_SIDE else DROP_SIDE) * w * t) }
		}
		return out
	}

	/** The arc length along [path] from its index [from] to each index up to [to]. */
	private fun arc(path: List<Int>, from: Int, to: Int): FloatArray {
		val len = FloatArray(to - from + 1)
		for (j in from + 1..to) len[j - from] = len[j - from - 1] + hypot(contour[path[j]].first - contour[path[j - 1]].first, contour[path[j]].second - contour[path[j - 1]].second)
		return len
	}

	/** How sharply the outline bends at contour point [i]: 0 for a gentle round, 1 for a corner. */
	private fun sharpness(i: Int): Float {
		val n = contour.size; val k = max(2, n / 40)
		val a = contour[(i - k + n) % n]; val b = contour[i]; val c = contour[(i + k) % n]
		val a1 = kotlin.math.atan2(b.second - a.second, b.first - a.first); val a2 = kotlin.math.atan2(c.second - b.second, c.first - b.first)
		var d = abs(a2 - a1); if (d > PI) d = (2 * PI).toFloat() - d
		return ((d - 0.15f) / 0.45f).coerceIn(0f, 1f)
	}

	/**
	 * Where the face's points go at [yaw] and [pitch] degrees: a map of rig points. Sideways the moves follow the sine of
	 * the turn; a corner pose adds the two looks, a little short looking down, and rolls about the eyes' middle.
	 */
	/** The turn's share at [yaw] (+ toward the canvas's right, by the sine of 30 degrees) and the look's at [pitch] (+ up), with the strength. */
	fun shares(yaw: Float, pitch: Float): Pair<Float, Float> =
		strength * sin(yaw * PI.toFloat() / 180f) / sin(PI.toFloat() / 6f) to strength * pitch / 30f

	/** The face's roll at [yaw] and [pitch], in radians: none but on a corner pose. */
	fun roll(yaw: Float, pitch: Float): Float {
		val (s, t) = shares(yaw, pitch)
		return if (s != 0f && t != 0f) ROLL * (s / strength.coerceAtLeast(1e-3f)) * (t / strength.coerceAtLeast(1e-3f)) * PI.toFloat() / 180f else 0f
	}

	fun map(yaw: Float, pitch: Float): (Float, Float) -> Pair<Float, Float> {
		if (!usable || yaw == 0f && pitch == 0f) return { x, y -> x to y }
		val s = strength * sin(yaw * PI.toFloat() / 180f) / sin(PI.toFloat() / 6f)
		val t = strength * pitch / 30f
		val pairs = rules(s, t)
		val tps = ThinPlateSpline(pairs.map { it.first }, pairs.map { it.second }, 1e-3)
		val scale = if (s != 0f && t < 0f) CORNER_DOWN else 1f
		val roll = roll(yaw, pitch)
		return { x, y ->
			val (mx, my) = tps.map(x, y)
			var px = x + (mx - x) * scale; var py = y + (my - y) * scale
			if (roll != 0f) {
				val ox = px - centre.first; val oy = py - centre.second
				px = centre.first + ox * cos(roll) - oy * sin(roll); py = centre.second + ox * sin(roll) + oy * cos(roll)
			}
			px to py
		}
	}

	companion object {
		/** The face's middle's sideways lead at 30 degrees, in W. */
		const val LEAD_MIDDLE = 0.40f
		const val LEAD_NOSE = 0.46f
		const val LEAD_MOUTH = 0.42f
		const val LEAD_CHIN = 0.48f
		const val LEAD_TOP = 0.22f
		const val LEAD_NEAR_EYE = 0.52f
		const val LEAD_FAR_EYE = 0.30f
		const val LEAD_NEAR_BROW = 0.56f
		const val LEAD_FAR_BROW = 0.34f
		/** The side turning away: its temple and cheek go this share of the middle's lead, its jaw angle this much. */
		const val SWEEP = 0.6f
		const val HOLD_NEAR_JAW = 0.35f
		/** The side turning toward: its cheek this share of the middle's lead, its jaw angle this much. */
		const val PIN = 0.25f
		const val PIN_FAR_JAW = 0.45f
		/** How fast the side turning away straightens with the turn (1 at 30 degrees when 1). */
		const val STRAIGHTEN = 1f
		/** The fold at the far cheek-to-jaw turn at 30 degrees for a sharp jaw, and the dip by a far eye's corner, in W. */
		const val NOTCH = 0.07f
		const val NOTCH_EYE = 0.04f
		/** How near the far eye's corner comes to the outline before the outline dips by it, in W. */
		const val NEAR_CORNER = 0.35f
		/** The band along the far outline that keeps its width, of the face's width. */
		const val BAND = 0.12f
		/** Looking up 30 degrees, how far each point rises, in W. */
		const val LIFT_MIDDLE = 0.30f
		const val LIFT_EYE = 0.40f
		const val LIFT_BROW = 0.40f
		const val LIFT_NOSE = 0.26f
		const val LIFT_MOUTH = 0.34f
		const val LIFT_CHIN = 0.34f
		const val LIFT_JAW = 0.18f
		const val LIFT_SIDE = 0.06f
		const val LIFT_TEMPLE = 0.08f
		const val LIFT_TOP = 0.14f
		/** Looking down 30 degrees, how far each point drops, in W. */
		const val DROP_MIDDLE = 0.24f
		const val DROP_EYE = 0.28f
		const val DROP_BROW = 0.32f
		const val DROP_NOSE = 0.22f
		const val DROP_MOUTH = 0.16f
		const val DROP_CHIN = 0.10f
		const val DROP_JAW = 0.04f
		const val DROP_SIDE = 0.05f
		const val DROP_TEMPLE = 0.10f
		const val DROP_TOP = 0.24f
		/** A corner pose looking down is this share of the two looks added; and it rolls this many degrees at full turn and look. */
		const val CORNER_DOWN = 0.92f
		const val ROLL = 4f
		/** The face's width at the eye line in units: a typical face is this many eye-distances across there. */
		const val FACE_PER_UNIT = 2.6f
	}
}
