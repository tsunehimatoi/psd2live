package io.github.psd2live.core.fit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How the eyes and the brows change shape as the head turns, on top of where the face's field ([FaceTurn]) carries
 * them. Hand-made 2D heads do not scale a turned eye; they reshape it:
 *
 * - **A gradient across the eyes, not a scale.** Turned, the near eye's outer end opens a little and its inner end
 *   tightens; the far eye tightens most at its outer end, which folds into a steep round edge, and less at its inner
 *   end. Read across both eyes the squeeze grows steadily toward the far side of the face, and it is that direction the
 *   viewer reads, not one eye's size: a far eye merely narrowed reads as a smaller eye, not a turned one.
 * - **The top leans into the turn.** An eye's top goes further toward the turn than its bottom, so the lid's peak - where
 *   2D reads an eye's facing - moves toward the turn: inward on the near eye, outward on the far one, more so there.
 * - **The iris holds its shape.** The opening reshapes about the eyeball, the iris only follows the eye's middle and
 *   narrows a little on the far eye, so it sits toward the turn inside its opening: an eyeball in a socket, not a print.
 * - **Looking up or down bends the eye's line.** Looking up the outer corners drop and the inner rise, most at the lash's
 *   tail; looking down the line bends the other way and the opening closes from its top. Squeezing it alone reads as
 *   squinting.
 * - **The brows stand before the eyes.** They lean and bend as the eyes do, more, and the far brow's tail tightens most.
 * - Every local change turns with the face's roll ([FaceTurn.roll]), so on a corner pose the whole eye line rolls;
 *   a pure look does not roll an eye, however unevenly the field lifts the face about it.
 *
 * [s] and [t] are the turn's and the look's shares ([FaceTurn.shares]), [roll] the face's roll; [field] is the face's field at that pose.
 */
object FeatureTurn {
	/** The near eye's inner and outer ends' widths at a full turn, and the far eye's. */
	private const val NEAR_INNER = 0.12f
	private const val NEAR_OUTER = 0.18f
	private const val FAR_INNER = 0.28f
	private const val FAR_OUTER = 0.55f
	/** How far an eye's top leans into the turn per unit of its height, near and far. */
	private const val LEAN_NEAR = 0.12f
	private const val LEAN_FAR = 0.30f
	/** Looking up 30 degrees, how far the outer corner drops and the inner rises, in the eye's widths. */
	private const val BEND_OUTER = 0.16f
	private const val BEND_INNER = 0.04f
	/** Looking down, how much the opening above and below its middle line closes; looking up, how much it squeezes. */
	private const val CLOSE_TOP = 0.30f
	private const val CLOSE_BOTTOM = 0.06f
	private const val SQUEEZE_UP = 0.05f
	/** The far iris narrows this much more than its opening's middle does: 1 holds it, 0 narrows it as the opening. */
	private const val IRIS_HOLD = 0.55f
	/** The brows: the near head and tail, the far head and tail, their lean and their bend. */
	private const val BROW_NEAR_HEAD = 0.05f
	private const val BROW_NEAR_TAIL = 0.12f
	private const val BROW_FAR_HEAD = 0.25f
	private const val BROW_FAR_TAIL = 0.45f
	private const val BROW_LEAN_NEAR = 0.15f
	private const val BROW_LEAN_FAR = 0.30f
	private const val BROW_BEND_OUTER = 0.16f
	private const val BROW_BEND_INNER = 0.04f

	/** A width that goes from [inner] to [outer] across 0..1, integrated from the middle: where [u] ends up. */
	private fun across(u: Float, inner: Float, outer: Float): Float {
		val b = outer - inner
		// k(w) = inner + b w; held at the ends outside 0..1.
		fun integral(from: Float, to: Float) = inner * (to - from) + b * (to * to - from * from) / 2f
		return when {
			u < 0f -> integral(0.5f, 0f) + inner * u
			u > 1f -> integral(0.5f, 1f) + outer * (u - 1f)
			else -> integral(0.5f, u)
		} + 0.5f
	}

	/** Where the point ([x], [y]) of an eye ([fit]) goes, its eye [far] from the turn or near it. */
	fun eye(fit: EyeFit, field: (Float, Float) -> Pair<Float, Float>, s: Float, t: Float, roll: Float, x: Float, y: Float): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		val (cx, cy) = fit.toCanvas(0.5f, middle(fit))
		// The far eye is the one on the side the face turns toward: its inner-to-outer axis points with the turn.
		val far = s * fit.axisX > 0f
		val (u, v) = fit.toEye(x, y)
		val v0 = middle(fit)
		val inner = if (far) 1f - FAR_INNER * turn else 1f - NEAR_INNER * turn
		val outer = if (far) 1f - FAR_OUTER * turn else 1f + NEAR_OUTER * turn
		var nu = across(u, max(inner, 0.2f), max(outer, 0.15f))
		// The top leans into the turn: along the eye's axis, toward the turn's side.
		val toward = if (s * fit.axisX > 0f) 1f else -1f
		nu += toward * (if (far) LEAN_FAR else LEAN_NEAR) * turn * -(v - v0)
		var nv = v
		val dv = v - v0
		nv = v0 + dv * when {
			t < 0f -> 1f - (if (dv < 0f) CLOSE_TOP else CLOSE_BOTTOM) * -t
			else -> 1f - SQUEEZE_UP * t
		}
		val uc = u.coerceIn(0f, 1f)
		nv += t * (BEND_OUTER * uc * uc - BEND_INNER * (1f - uc) * (1f - uc))
		return place(fit.toCanvas(nu, nv), cx, cy, field, roll)
	}

	/**
	 * Where a point ([x], [y]) of an eye's iris goes: with the eye's middle, holding its shape but for a little narrowing
	 * on the far eye, so the opening moves about it.
	 */
	fun iris(fit: EyeFit, field: (Float, Float) -> Pair<Float, Float>, s: Float, t: Float, roll: Float, x: Float, y: Float): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		val (cx, cy) = fit.iris?.let { it.centerX to it.centerY } ?: fit.toCanvas(0.5f, middle(fit))
		val far = s * fit.axisX > 0f
		val (u, v) = fit.toEye(x, y)
		val (uc, vc) = fit.toEye(cx, cy)
		// The opening's own narrowing at its middle, held back.
		val opening = if (far) 1f - (FAR_INNER + FAR_OUTER) / 2f * turn else 1f + (NEAR_OUTER - NEAR_INNER) / 2f * turn
		val k = 1f + (opening - 1f) * (1f - IRIS_HOLD)
		val nu = uc + (u - uc) * min(k, 1f)
		// It goes where the opening's middle goes.
		val (mx, my) = eye(fit, field, s, t, roll, cx, cy)
		val (px, py) = fit.toCanvas(nu, v)
		val (ox, oy) = fit.toCanvas(uc, vc)
		val dx = px - ox; val dy = py - oy
		return (mx + dx * cos(roll) - dy * sin(roll)) to (my + dx * sin(roll) + dy * cos(roll))
	}

	/** Where the point ([x], [y]) of a brow ([fit]) goes. */
	fun brow(fit: BrowFit, field: (Float, Float) -> Pair<Float, Float>, s: Float, t: Float, roll: Float, x: Float, y: Float): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		val mid = fit.centre[fit.centre.size / 2]
		val (cx, cy) = fit.toRig(0.5f, mid)
		val far = s * fit.axisX > 0f
		val (u, v) = fit.toBrow(x, y)
		val head = if (far) 1f - BROW_FAR_HEAD * turn else 1f - BROW_NEAR_HEAD * turn
		val tail = if (far) 1f - BROW_FAR_TAIL * turn else 1f + BROW_NEAR_TAIL * turn
		var nu = across(u, max(head, 0.2f), max(tail, 0.15f))
		val toward = if (s * fit.axisX > 0f) 1f else -1f
		nu += toward * (if (far) BROW_LEAN_FAR else BROW_LEAN_NEAR) * turn * -(v - mid)
		val uc = u.coerceIn(0f, 1f)
		val nv = v + t * (BROW_BEND_OUTER * uc * uc - BROW_BEND_INNER * (1f - uc) * (1f - uc))
		val (bx, by) = fit.toRig(nu, nv)
		return place(bx to by, cx, cy, field, roll)
	}

	/**
	 * Where a point ([x], [y]) of the nose goes. The nose is the face's most forward point - the field already sends it
	 * furthest - and it is a small wedge with two sides: turned, the side facing the viewer opens and the far side folds
	 * into the bridge's line, the whole leaning a little into the turn; looking up it shows its underside, flatter and
	 * wider; looking down it lengthens. [left], [right], [top] and [bottom] are the drawn nose's extremes.
	 */
	fun nose(left: Pair<Float, Float>, right: Pair<Float, Float>, top: Float, bottom: Float, unit: Float, field: (Float, Float) -> Pair<Float, Float>,
	         s: Float, t: Float, roll: Float, x: Float, y: Float): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		val cx = (left.first + right.first) / 2f; val cy = (top + bottom) / 2f
		var dx = x - cx; var dy = y - cy
		dx *= if (dx * s > 0f) 1f - NOSE_FOLD * turn else 1f + NOSE_OPEN * turn
		dx += (if (s > 0f) 1f else -1f) * NOSE_LEAN * turn * -dy
		if (t > 0f) { dy *= 1f - NOSE_FLATTEN * t; dx *= 1f + NOSE_WIDEN * t } else dy *= 1f + NOSE_LENGTHEN * -t
		// A drawn nose stands before everything else on the face: it leads past the field (which keeps a nose drawn as skin
		// behind the eyes) sideways and up and down, [unit] being the face's.
		val (px, py) = place((cx + dx) to (cy + dy), cx, cy, field, roll)
		return (px + NOSE_LEAD * unit * s) to (py - (if (t >= 0f) NOSE_LIFT else NOSE_DROP) * unit * t)
	}

	/**
	 * Where a point ([x], [y]) of the mouth goes, as a whole (its own shapes ride on top). Turned it hinges at its middle:
	 * only the far half narrows, the near half keeps its width, and it does not tilt; looking up its middle rises against
	 * its corners, looking down its corners rise. [a] and [b] are its corners.
	 */
	fun mouth(a: Pair<Float, Float>, b: Pair<Float, Float>, field: (Float, Float) -> Pair<Float, Float>, s: Float, t: Float, roll: Float,
	          x: Float, y: Float): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		val cx = (a.first + b.first) / 2f; val cy = (a.second + b.second) / 2f
		val half = (abs(b.first - a.first) / 2f).coerceAtLeast(1e-3f)
		var dx = x - cx
		val u = (dx / half).coerceIn(-1.5f, 1.5f)
		dx *= if (dx * s > 0f) 1f - MOUTH_FOLD * turn else 1f + MOUTH_OPEN * turn
		val uc = u.coerceIn(-1f, 1f)
		val dy = (y - cy) - t * MOUTH_ARC * half * (1f - uc * uc)
		return place((cx + dx) to (cy + dy), cx, cy, field, roll)
	}

	/**
	 * Where a point ([x], [y]) of an ear goes. Its root, on the face's outline, goes with the outline; its free edge
	 * follows behind: the near ear opens out, the far one folds toward its root and so falls back behind the face's
	 * side. Looking up the ears sink against the features, looking down they rise. [root] and [outer] are its root and
	 * its outermost point, [height] its height; [side] is +1 on the canvas's right, -1 on its left.
	 */
	fun ear(root: Pair<Float, Float>, outer: Pair<Float, Float>, height: Float, side: Float, field: (Float, Float) -> Pair<Float, Float>,
	        s: Float, t: Float, roll: Float, x: Float, y: Float): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		val ex = outer.first - root.first; val ey = outer.second - root.second
		val len = kotlin.math.hypot(ex, ey).coerceAtLeast(1e-3f)
		val nx = ex / len; val ny = ey / len
		// How far out from the root along the ear.
		val d = ((x - root.first) * nx + (y - root.second) * ny).coerceAtLeast(0f)
		val k = if (s * side > 0f) -EAR_FOLD * turn else EAR_OPEN * turn
		val px = x + nx * d * k; val py = y + ny * d * k + EAR_SINK * height * t
		return place(px to py, root.first, root.second, field, roll)
	}

	private const val NOSE_FOLD = 0.70f
	private const val NOSE_OPEN = 0.10f
	private const val NOSE_LEAN = 0.20f
	private const val NOSE_FLATTEN = 0.40f
	private const val NOSE_WIDEN = 0.25f
	private const val NOSE_LENGTHEN = 0.15f
	/** A drawn nose's lead past the field at 30 degrees, and its extra rise and drop looking up and down, in the face's unit. */
	private const val NOSE_LEAD = 0.20f
	private const val NOSE_LIFT = 0.12f
	private const val NOSE_DROP = 0.06f
	private const val MOUTH_FOLD = 0.35f
	private const val MOUTH_OPEN = 0.04f
	/** Looking up 30 degrees, how far the mouth's middle rises against its corners, in its half width. */
	private const val MOUTH_ARC = 0.12f
	private const val EAR_FOLD = 0.30f
	private const val EAR_OPEN = 0.20f
	private const val EAR_SINK = 0.15f

	/** The middle line of an eye's opening at its middle, in its own frame. */
	private fun middle(fit: EyeFit): Float = (EyeFit.sample(fit.upper, 0.5f) + EyeFit.sample(fit.lower, 0.5f)) / 2f

	/** [p] - a point reshaped about the feature's middle ([cx], [cy]) - carried where the field takes the middle, turned by its roll there. */
	private fun place(p: Pair<Float, Float>, cx: Float, cy: Float, field: (Float, Float) -> Pair<Float, Float>, roll: Float): Pair<Float, Float> {
		val (mx, my) = field(cx, cy)
		val dx = p.first - cx; val dy = p.second - cy
		return (mx + dx * cos(roll) - dy * sin(roll)) to (my + dx * sin(roll) + dy * cos(roll))
	}
}
