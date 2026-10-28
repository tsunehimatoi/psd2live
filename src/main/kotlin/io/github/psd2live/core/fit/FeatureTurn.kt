package io.github.psd2live.core.fit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How the eyes and the brows change shape as the head turns, on top of where the face's field ([FaceTurn]) carries
 * them. Hand-made 2D heads do not scale a turned eye; they reshape it:
 *
 * - **A gradient across the eyes, not a scale.** Turned, the near eye keeps its width, its inner end tightening a
 *   little and its outer opening as much; the far eye narrows by about a third, more toward its outer end. Read across
 *   both eyes the squeeze grows toward the far side of the face, and it is that direction the viewer reads, not one
 *   eye's size: a far eye merely narrowed reads as a smaller eye, not a turned one.
 * - **How much and where follow the drawing ([EyeStyle]).** A flat, straight lid (a sharp eye) narrows more than a round
 *   arched one, whose roundness has to survive the turn. On a short round face the eyes sit on what reads as a ball and
 *   the far eye's squeeze gathers at its outer end; on a long face they sit in the face's front plane and narrow evenly,
 *   keeping an almond an almond.
 * - **The top leans into the turn.** An eye's top goes further toward the turn than its bottom, so the lid's peak - where
 *   2D reads an eye's facing - moves toward the turn: inward on the near eye, outward on the far one, more so there.
 * - **The iris keeps its place in the opening and most of its width.** It stays where it sits between the opening's
 *   ends as they go (following the opening's middle point instead, with the outer end squeezing more, slides it
 *   outward and reads as a squint) and narrows less than the opening, so it reads as round and solid.
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
	/**
	 * How an eye reshapes turned, from its drawing and its face's: [farWidth] is the far eye's width at a full turn,
	 * [farGradient] how much more of its width its inner quarter keeps than its outer one.
	 */
	class EyeStyle(val farWidth: Float, val farGradient: Float) {
		companion object {
			val TYPICAL = EyeStyle(0.67f, 0.23f)

			/**
			 * Read from the face's points: the eye's ([side] "L" or "R") corners, the three points along its upper lid, its
			 * lid's peak and its lower lid's lowest point, and the face's length (eye line to chin over its width at the eye
			 * line). Without them, [TYPICAL].
			 */
			fun of(marks: FaceLandmarks, side: String): EyeStyle {
				val inner = marks["eye.$side.inner"] ?: return TYPICAL; val outer = marks["eye.$side.outer"] ?: return TYPICAL
				val ax = outer.x - inner.x; val ay = outer.y - inner.y
				val width = hypot(ax, ay).takeIf { it > 1e-3f } ?: return TYPICAL
				// Across the eye from its inner corner, in its widths; down positive (the canvas's y runs down).
				fun down(p: Landmark) = ((p.y - inner.y) * ax - (p.x - inner.x) * ay) / (width * width) * (if (ax >= 0f) 1f else -1f)
				val q = listOf(25, 50, 75).map { marks["eye.$side.upper$it"] ?: return TYPICAL }.map(::down)
				val height = ((marks["eye.$side.lowLid"] ?: return TYPICAL).let(::down) - (marks["eye.$side.apex"] ?: return TYPICAL).let(::down))
				// The lid's arch: the second-order term of the parabola through its three points, in the opening's heights.
				val curve = (q[0] - 2f * q[1] + q[2]) / (2f * 0.25f * 0.25f)
				val arch = (curve / height.coerceAtLeast(1e-3f)).coerceIn(ARCH_LOW, ARCH_HIGH)
				val far = (FAR_WIDTH + FAR_WIDTH_PER_ARCH * arch).coerceIn(0.55f, 0.8f)
				val l = marks["face.eyeLine.L"]; val r = marks["face.eyeLine.R"]; val chin = marks["face.chin"]
				val length = if (l != null && r != null && chin != null && abs(l.x - r.x) > 1e-3f) (chin.y - (l.y + r.y) / 2f) / abs(l.x - r.x) else null
				val gradient = length?.let { (FAR_GRADIENT - FAR_GRADIENT_PER_LENGTH * (it - FACE_LENGTH)).coerceIn(0.05f, 0.45f) } ?: FAR_GRADIENT
				return EyeStyle(far, gradient)
			}
		}
	}

	/**
	 * The far eye's width at a full turn: this plus this much per unit of its lid's arch (in its opening's heights, held
	 * to [ARCH_LOW]..[ARCH_HIGH]). Its inner quarter's lead over its outer: this, less this much per unit of the face's
	 * length past [FACE_LENGTH].
	 */
	private const val FAR_WIDTH = 0.526f
	private const val FAR_WIDTH_PER_ARCH = 0.119f
	private const val ARCH_LOW = 0.64f
	private const val ARCH_HIGH = 2.14f
	private const val FAR_GRADIENT = 0.234f
	private const val FAR_GRADIENT_PER_LENGTH = 1.485f
	private const val FACE_LENGTH = 0.537f
	/** The near eye's width at a full turn, and how much more of its width its outer quarter keeps than its inner one. */
	private const val NEAR_WIDTH = 1.02f
	private const val NEAR_GRADIENT = 0.15f
	/** Of the opening's narrowing, how much the iris takes. */
	private const val IRIS_SQUEEZE = 0.75f

	/** An eye's widths at its inner and outer ends at a turn of [turn] (0..1.5): a line across it with its mean width and its quarters' gradient. */
	private fun ends(far: Boolean, turn: Float, style: EyeStyle): Pair<Float, Float> {
		val mean = if (far) 1f - (1f - style.farWidth) * turn else 1f + (NEAR_WIDTH - 1f) * turn
		val gradient = if (far) style.farGradient * turn else -NEAR_GRADIENT * turn
		// The quarters' widths over the mean differ by the slope times 0.75.
		val slope = -gradient * mean / 0.75f
		return max(mean - slope / 2f, 0.2f) to max(mean + slope / 2f, 0.15f)
	}
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
	/** The brows: the near head and tail, the far head and tail, their lean and their bend. */
	private const val BROW_NEAR_HEAD = 0.05f
	private const val BROW_NEAR_TAIL = 0.12f
	private const val BROW_FAR_HEAD = 0.25f
	private const val BROW_FAR_TAIL = 0.45f
	private const val BROW_LEAN_NEAR = 0.15f
	private const val BROW_LEAN_FAR = 0.30f
	private const val BROW_BEND_OUTER = 0.16f
	private const val BROW_BEND_INNER = 0.04f

	/** How far an eye's outer end reaches from its middle at a turn of share [s], of its reach at rest: [far] from the turn or near it. */
	fun outerReach(far: Boolean, s: Float, style: EyeStyle = EyeStyle.TYPICAL): Float {
		val (inner, outer) = ends(far, min(abs(s), 1.5f), style)
		return (across(1f, inner, outer) - 0.5f) / 0.5f
	}

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
	fun eye(fit: EyeFit, field: (Float, Float) -> Pair<Float, Float>, s: Float, t: Float, roll: Float, x: Float, y: Float,
	        style: EyeStyle = EyeStyle.TYPICAL): Pair<Float, Float> {
		val (cx, cy) = fit.toCanvas(0.5f, middle(fit))
		val (u, v) = fit.toEye(x, y)
		val (nu, nv) = reshape(fit, s, t, u, v, style)
		return place(fit.toCanvas(nu, nv), cx, cy, field, roll)
	}

	/**
	 * How far an eye's point at [v] goes along its axis as its top leans into the turn: within the opening, so the margin
	 * above and below it goes as the lids do rather than sheared further.
	 */
	private fun lean(fit: EyeFit, far: Boolean, toward: Float, turn: Float, v: Float): Float {
		val half = (EyeFit.sample(fit.lower, 0.5f) - EyeFit.sample(fit.upper, 0.5f)) / 2f
		return toward * (if (far) LEAN_FAR else LEAN_NEAR) * turn * -(v - middle(fit)).coerceIn(-half, half)
	}

	/** An eye's point ([u], [v]) in its own frame, reshaped. */
	private fun reshape(fit: EyeFit, s: Float, t: Float, u: Float, v: Float, style: EyeStyle): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		// The far eye is the one on the side the face turns toward: its inner-to-outer axis points with the turn.
		val far = s * fit.axisX > 0f
		val v0 = middle(fit)
		val (inner, outer) = ends(far, turn, style)
		var nu = across(u, inner, outer)
		// The top leans into the turn: along the eye's axis, toward the turn's side.
		val toward = if (s * fit.axisX > 0f) 1f else -1f
		nu += lean(fit, far, toward, turn, v)
		var nv = v
		val dv = v - v0
		nv = v0 + dv * when {
			t < 0f -> 1f - (if (dv < 0f) CLOSE_TOP else CLOSE_BOTTOM) * -t
			else -> 1f - SQUEEZE_UP * t
		}
		val uc = u.coerceIn(0f, 1f)
		nv += t * (BEND_OUTER * uc * uc - BEND_INNER * (1f - uc) * (1f - uc))
		return nu to nv
	}

	/**
	 * Where a point ([x], [y]) of an eye's iris goes: kept at its share of the way between the opening's ends as they go,
	 * up and down and leaning as the opening there does, and narrowing by [IRIS_SQUEEZE] of the opening's narrowing.
	 */
	fun iris(fit: EyeFit, field: (Float, Float) -> Pair<Float, Float>, s: Float, t: Float, roll: Float, x: Float, y: Float,
	         style: EyeStyle = EyeStyle.TYPICAL): Pair<Float, Float> {
		val turn = min(abs(s), 1.5f)
		val (cx, cy) = fit.iris?.let { it.centerX to it.centerY } ?: fit.toCanvas(0.5f, middle(fit))
		val far = s * fit.axisX > 0f
		val (u, v) = fit.toEye(x, y)
		val (uc, vc) = fit.toEye(cx, cy)
		val (inner, outer) = ends(far, turn, style)
		val a = across(0f, inner, outer); val b = across(1f, inner, outer)
		val (lu, nvc) = reshape(fit, s, t, uc, vc, style)
		// The opening's own place for it, plus the lean the opening gives it there.
		val nuc = a + (b - a) * uc + (lu - across(uc, inner, outer))
		val k = 1f + ((b - a) - 1f) * (if (b - a < 1f) IRIS_SQUEEZE else 1f)
		val (ex, ey) = fit.toCanvas(0.5f, middle(fit))
		val (mx, my) = place(fit.toCanvas(nuc, nvc), ex, ey, field, roll)
		// It leans with the opening about it, or its warp, inside the opening's, would have to undo the opening's lean.
		val toward = if (s * fit.axisX > 0f) 1f else -1f
		val (px, py) = fit.toCanvas(uc + (u - uc) * k + lean(fit, far, toward, turn, v) - lean(fit, far, toward, turn, vc), v)
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
