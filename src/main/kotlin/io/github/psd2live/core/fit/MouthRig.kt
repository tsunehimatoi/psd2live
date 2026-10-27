package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.MouthCurve
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which mouth modules a project builds, with their values as fractions. */
data class MouthOptions(
	val early: Float = 0.35f, val formStrength: Float = 1f,
	val curve: MouthCurve = MouthCurve.preset("smile"), val exactClose: Boolean = true,
	val vowels: Boolean = false, val capture: Boolean = false,
	val jaw: Boolean = false, val jawShare: Float = 0.15f,
	val depth: Boolean = false, val depthAmount: Float = 1f,
	val spring: Boolean = false,
	/** Whether the art has a tongue to put out. */
	val tongue: Boolean = false,
	/**
	 * How much of its height a layered mouth's inside keeps when the lips shut, with lip layers over it to hide it: 0
	 * closes it into the lips' line, 1 leaves it as drawn behind the lips.
	 */
	val inside: Float = 0.5f,
	/** How far the opening goes, of the drawing: past 1 the mouth opens on beyond it, a shout. */
	val reach: Float = 1f,
)

/**
 * The `mouth/fitted` base and the mouth modules for the drawn open mouth ([fit]). Every mouth the rig makes is a pose
 * of the two lip edges ([Pose]): the drawn upper and lower edge carried toward a closed line, rounded, flattened,
 * narrowed or shifted, and each vertex of the mouth's layers keeps its place between the edges - a lip line's band
 * along each edge stays as drawn and the inside closes between them - so the lips meet in one line with their own
 * thickness, the teeth ride the lip they belong to and the interior shuts with them.
 *
 * Opening goes in stages, as hand-made mouths do: at a hair the lips part ([SWAP], where a drawn closed mouth hands
 * over), at a quarter the lower lip has dropped into a crescent while the upper lip and the corners stay, at a half
 * the mouth is an oval, at 1 the drawing. The form runs from a pout - closed a small frown, open a round "o" - to a
 * smile - corners up, open a wide D under a flat upper lip - with keys between the ends.
 */
class MouthRig(val fit: MouthFit, val options: MouthOptions, lineWidth: Float? = null) {
	/**
	 * A mouth's shape: how far the upper and lower lip have gone from the closed line toward the drawing ([upper],
	 * [lower]), its width and sideways shift (of the corners' distance), how far the corners rise ([corner], of the
	 * width), how round it opens ([round]) and how flat the upper lip stays ([flatTop]), the closed line lifted at its
	 * middle ([lift]) and the lips pressed together ([press]).
	 */
	data class Pose(
		val upper: Float, val lower: Float, val width: Float = 1f, val shift: Float = 0f, val corner: Float = 0f,
		val round: Float = 0f, val flatTop: Float = 0f, val lift: Float = 0f, val press: Float = 0f,
	)

	/**
	 * How a layer of the mouth follows the lips. A mouth drawn whole ([WHOLE], [WHOLE_TIGHT]) keeps each point between the
	 * lip edges ([move]); a mouth drawn in layers moves each the way its kind does in a hand-made rig:
	 *
	 * - [OPENING]: the opening's colour, which clips the teeth and tongue, kept between the two lip lines ([between]): its
	 *   edge stays under its line in every pose, so it never shows past the lines, and shut it closes into their one line.
	 * - [INSIDE]: the opening of a mouth with lip skin over it: kept between the lines, but closing by [MouthOptions.inside]
	 *   of its height less, the rest hidden behind the lips.
	 * - [UPPER_LINE], [LOWER_LINE]: a lip's edge line rides its edge whole, keeping its thickness; shut, the two lines meet
	 *   in one, and where they are drawn as one - at the corners - they stay one.
	 * - [UPPER_LIP], [LOWER_LIP]: a lip with the skin about it rides its edge whole near the lips and less further out,
	 *   so the lip's paint bends with it without squashing and its skin margin blends into the face.
	 * - [UPPER_TEETH], [LOWER_TEETH]: a row of teeth moves whole with its lip's middle, lagging where the lip closes over
	 *   it ([teeth]); drawn running on under the lips, the opening clips it, so it meets the lines without a gap and a
	 *   small, round or pouting mouth hides it.
	 * - [TONGUE]: the tongue moves whole with the jaw, about half the lower lip's drop.
	 * - [DEEP]: the dark at the back of the mouth hardly moves; the opening uncovers it.
	 */
	enum class Follow { WHOLE, WHOLE_TIGHT, OPENING, INSIDE, UPPER_LINE, LOWER_LINE, UPPER_LIP, LOWER_LIP, UPPER_TEETH, LOWER_TEETH, TONGUE, DEEP }

	private val h = fit.opening

	/** The opening's keys: [OPEN_KEYS], and the [MouthOptions.reach] past the drawing where it goes on. */
	val openKeys: FloatArray = if (options.reach > 1.001f) OPEN_KEYS + options.reach else OPEN_KEYS
	/** A lip line's band along each edge, which moves with its edge as drawn. */
	private val band = min(0.045f, h * 0.18f)
	/** How thick a layered mouth's lip lines are drawn ([MouthFitting.lineWidth]); none without line layers, whose lips meet as drawn. */
	private val line = lineWidth ?: 0f
	/** The gap left between the lips when they close over nothing else ([MouthOptions.exactClose] off). */
	private val gap = if (options.exactClose) 0f else (1.2f / fit.width).coerceIn(0.004f, 0.04f)

	private fun drawnUpper(u: Float) = MouthFit.sample(fit.upper, u) - fit.baseline(u)
	private fun drawnLower(u: Float) = MouthFit.sample(fit.lower, u) - fit.baseline(u)

	/** The closed line below the corners' line, from the mouth's closed shape (positive down). */
	private fun closed(u: Float) = options.curve.yAt(u) * h * 0.6f

	/** The lip edges of [pose] at [u] (of the drawing), below the corners' line. */
	fun edges(pose: Pose, u: Float): Pair<Float, Float> {
		val hump = EyeRig.hump(u).coerceAtLeast(0f)
		var ur = drawnUpper(u); var lr = drawnLower(u)
		// A round mouth is an oval about the middle of its opening, its corners come to that middle line: upright there,
		// so the lips meet without a point, and as deep above as below, so it does not taper to a point at the bottom.
		// Toward the corners the edges go wholly to the oval whatever the drawing keeps at its middle, or a drawn corner's
		// wall stays as a spike; and it closes toward that middle line, not the drawn closed shape.
		val oval = 2f * sqrt((u * (1f - u)).coerceAtLeast(0f))
		val middle = (drawnUpper(0.5f) + drawnLower(0.5f)) / 2f
		val half = (drawnLower(0.5f) - drawnUpper(0.5f)) / 2f
		val round = if (pose.round <= 0f) 0f else pose.round + (1f - pose.round) * (pose.round / 0.3f).coerceAtMost(1f) * (1f - oval)
		ur += (middle - half * oval - ur) * round
		lr += (middle + half * oval - lr) * round
		ur *= 1f - 0.6f * pose.flatTop
		val c = closed(u) * (1f - pose.press) * (1f - round) + middle * round - pose.lift * hump
		var top = c + (ur - c) * pose.upper - gap * 0.5f * hump
		var bottom = c + (lr - c) * pose.lower + gap * 0.5f * hump
		val rise = pose.corner * (1f - hump)
		top -= rise; bottom -= rise
		if (bottom < top) { val m = (top + bottom) / 2f; top = m; bottom = m }
		return top to bottom
	}

	/**
	 * Where a point of the mouth at ([u], [v]) in the mouth's frame goes in [pose], as a change in the frame. A [tight]
	 * point keeps no lip line's band: a ribbon, which is the line itself, and a fill the ribbons outline, which closes
	 * whole under them into the one line they meet in.
	 */
	fun move(u: Float, v: Float, pose: Pose, tight: Boolean = false): Pair<Float, Float> {
		val uc = u.coerceIn(0f, 1f)
		val r = v - fit.baseline(u)
		val u0 = drawnUpper(uc); val l0 = drawnLower(uc)
		val (u1, l1) = edges(pose, uc)
		val band = if (tight) 0f else band
		val tb = min(band, max(0f, l0 - u0) * 0.3f)
		// Past an edge a lip line's thickness stays as drawn; more than that - a round mouth's side bending away near a
		// corner - closes with the mouth, or a shut mouth keeps hooks at its corners.
		val ratio = ((edges(pose, 0.5f).let { it.second - it.first }) / h.coerceAtLeast(1e-4f)).coerceIn(0f, 1.5f)
		fun beyond(d: Float): Float {
			val keep = d.coerceIn(-band, band)
			return keep + (d - keep) * ratio
		}
		// Past an edge a point keeps to the point of the drawn edge nearest it, not the one straight above or below: by a
		// mouth's end the edge runs steep, and a drawn corner measured upright stands off a rounded mouth as a spike.
		if (r < u0 || r > l0) {
			val upper = r < u0
			val (at, dx, dy) = nearest(if (upper) ::drawnUpper else ::drawnLower, u, r)
			val length = sqrt(dx * dx + dy * dy)
			val k = if (length < 1e-6f) 1f else beyond(length) / length
			val edge = edges(pose, at).let { if (upper) it.first else it.second }
			val un = 0.5f + (at + dx * k - 0.5f) * pose.width + pose.shift
			return (un - u) to (fit.baseline(un) + edge + dy * k - v)
		}
		val r1 = when {
			r <= u0 + tb -> u1 + beyond(r - u0)
			r >= l0 - tb -> l1 + beyond(r - l0)
			else -> {
				val inTop = u1 + tb; val inBottom = max(l1 - tb, inTop)
				val t = (r - (u0 + tb)) / ((l0 - tb) - (u0 + tb)).coerceAtLeast(1e-5f)
				inTop + (inBottom - inTop) * t
			}
		}
		val un = 0.5f + (u - 0.5f) * pose.width + pose.shift
		val vn = fit.baseline(un) + r1
		return (un - u) to (vn - v)
	}

	/** Where a point at ([u], [v]) of a layer that follows the lips as [follow] goes in [pose], as a change in the frame. */
	fun moveAs(follow: Follow, u: Float, v: Float, pose: Pose): Pair<Float, Float> = when (follow) {
		Follow.WHOLE -> move(u, v, pose)
		Follow.WHOLE_TIGHT -> move(u, v, pose, tight = true)
		Follow.OPENING -> between(u, v, pose)
		// Toward the corners, where the lips are thin, the inside closes whole.
		Follow.INSIDE -> between(u, v, pose).let { (du, dv) -> du to dv * (1f - options.inside.coerceIn(0f, 1f) * EyeRig.hump(u.coerceIn(0f, 1f)).coerceAtLeast(0f)) }
		Follow.UPPER_LINE, Follow.LOWER_LINE -> rides(nearerUpper(u, v), u, v, pose, share = 1f, falloff = false, meet = true)
		Follow.UPPER_LIP -> rides(true, u, v, pose, share = 1f, falloff = true, meet = true)
		Follow.LOWER_LIP -> rides(false, u, v, pose, share = 1f, falloff = true, meet = true)
		Follow.UPPER_TEETH -> teeth(u, pose, upper = true)
		Follow.LOWER_TEETH -> teeth(u, pose, upper = false)
		Follow.TONGUE -> pose.shift to middleDrop(pose, upper = false) * TONGUE_SHARE
		Follow.DEEP -> pose.shift to (middleDrop(pose, upper = true) + middleDrop(pose, upper = false)) * 0.5f * DEEP_SHARE
	}

	/**
	 * A point riding the upper or lower lip whole: it keeps its offset from the drawn edge's nearest point while that
	 * point moves to the posed edge, [share] of the way down the face. With [falloff] it rides less the further out it
	 * lies - a lip's skin margin. With [meet] the edge's band closes half its thickness toward the other lip as the mouth
	 * shuts, so two lines - and the lips beyond them - meet in one line rather than stacking.
	 */
	private fun rides(upper: Boolean, u: Float, v: Float, pose: Pose, share: Float, falloff: Boolean, meet: Boolean): Pair<Float, Float> {
		val r = v - fit.baseline(u)
		val (at, ox, oy) = nearest(if (upper) ::drawnUpper else ::drawnLower, u, r)
		val edge = edges(pose, at).let { if (upper) it.first else it.second }
		val ratio = (edges(pose, 0.5f).let { it.second - it.first } / h.coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
		val closing = if (!meet) 0f else 1f - ratio
		// A line keeps its thickness from its edge; more than that - a round mouth's side running up past its corner - closes
		// with the mouth, or a shut mouth keeps ticks standing at its corners. A lip's skin margin keeps its reach.
		val (dx, dy) = if (falloff) ox to oy else {
			val reach = sqrt(ox * ox + oy * oy); val keep = if (line > 0f) line else band
			if (reach <= keep) ox to oy else ((keep + (reach - keep) * ratio) / reach).let { k -> ox * k to oy * k }
		}
		// Each line lies inside its edge: shut, the upper one rises and the lower one drops half a line onto the other. Where
		// they are drawn as one stroke - toward the corners, the edges less than two lines apart - they already are one.
		val apart = if (line <= 0f) 0f else ((drawnLower(at) - drawnUpper(at) - line) / line).coerceIn(0f, 1f)
		val toward = (if (upper) -1f else 1f) * line * 0.5f * closing * apart
		// Pressed, the lips roll in: each pushes on past the other at the middle, the skin further out following less, so less of
		// the lips' colour shows; their lines stay met in one.
		val roll = if (!falloff) 0f else (if (upper) 1f else -1f) * pose.press * PRESS_ROLL * EyeRig.hump(at).coerceAtLeast(0f)
		if (!falloff) {
			// A line is a stroke along its edge: its offset from the edge turns as the edge turns, so where a round mouth's edge
			// stands upright at its corner the stroke's end bends with it, not standing off as a spur.
			val drawn = if (upper) ::drawnUpper else ::drawnLower
			val posed = { a: Float -> edges(pose, a).let { if (upper) it.first else it.second } }
			val turn = atan2(fit.cornerV * pose.width + slope(posed, at), pose.width) - atan2(fit.cornerV + slope(drawn, at), 1f)
			var gx = dx; var gy = dy + fit.cornerV * dx
			// Past its end at a corner a line runs on over the other line; turned upright there, that run would stand off
			// beside the other as a second stroke, so it draws back as the corner turns.
			if (at <= 0f || at >= 1f) {
				val tx = 1f; val ty = fit.cornerV + slope(drawn, at); val tl = sqrt(tx * tx + ty * ty)
				val along = (gx * tx + gy * ty) / tl
				if (if (at <= 0f) along < 0f else along > 0f) {
					val cut = along * (1f - cos(turn).coerceAtLeast(0f))
					gx -= cut * tx / tl; gy -= cut * ty / tl
				}
			}
			// Only the stroke's own thickness turns; what lies further off - the opening's inside - keeps its direction.
			val reach = sqrt(gx * gx + gy * gy); val keep = if (line > 0f) line else band
			val near = if (reach <= keep) 1f else keep / reach
			val nx = gx * near; val ny = gy * near; val fx = gx - nx; val fy = gy - ny
			val x = 0.5f + (at - 0.5f) * pose.width + pose.shift
			val px = x + nx * cos(turn) - ny * sin(turn) + fx * pose.width
			val py = fit.baseline(x) + edge + nx * sin(turn) + ny * cos(turn) + fy + toward
			return (px - u) to (py - v) * share
		}
		val un = 0.5f + (at + dx - 0.5f) * pose.width + pose.shift
		val du = un - u
		val dv = fit.baseline(un) + edge + dy + toward + roll - v
		val w = if (!falloff) 1f else {
			val t = ((sqrt(dx * dx + dy * dy) - SKIN_NEAR) / SKIN_REACH).coerceIn(0f, 1f)
			1f - SKIN_FAR_LOSS * t * t * (3f - 2f * t)
		}
		return du * w to dv * w * share
	}

	/**
	 * Whether a point of a lip line lies nearer the upper drawn edge than the lower. The two lines make one outline: at a
	 * corner, where one line's end runs on over the other's, that end is the other line there and goes with its edge.
	 */
	private fun nearerUpper(u: Float, v: Float): Boolean {
		val r = v - fit.baseline(u)
		val (_, ux, uy) = nearest(::drawnUpper, u, r); val (_, lx, ly) = nearest(::drawnLower, u, r)
		return ux * ux + uy * uy <= lx * lx + ly * ly
	}

	/**
	 * A point of the opening between the lips: it keeps its place between the two lines as they move, at the upper
	 * line's motion along the upper edge and the lower line's along the lower one, so the opening's edge stays under its
	 * line in every pose - at the corners too - and shut, closes into the lines' one line. Past an edge it rides that line.
	 */
	private fun between(u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		val uc = u.coerceIn(0f, 1f)
		val r = v - fit.baseline(u)
		// Under each line's band - measured to the nearest point of its edge, as the line itself rides, so a round mouth's
		// steep sides agree too - the opening rides that line, its edge keeping its place in the band. Between the bands it
		// goes with both lines by how near each is: as the lips close each draws its side in, and the two meet between them.
		val lined = min(line, (drawnLower(uc) - drawnUpper(uc)).coerceAtLeast(0f) / 2f)
		val (_, ux, uy) = nearest(::drawnUpper, u, r); val (_, lx, ly) = nearest(::drawnLower, u, r)
		val toUpper = sqrt(ux * ux + uy * uy); val toLower = sqrt(lx * lx + ly * ly)
		val upper = rides(true, u, v, pose, share = 1f, falloff = false, meet = true)
		if (r <= drawnUpper(uc) || toUpper <= lined) return upper
		val lower = rides(false, u, v, pose, share = 1f, falloff = false, meet = true)
		if (r >= drawnLower(uc) || toLower <= lined) return lower
		// Squared, so a point close by one line - a round mouth's side wall - goes with that line, not half with the other.
		val a = (toUpper - lined).let { it * it }; val b = (toLower - lined).let { it * it }
		val t = (a / (a + b).coerceAtLeast(1e-10f)).coerceIn(0f, 1f)
		return (upper.first + (lower.first - upper.first) * t) to (upper.second + (lower.second - upper.second) * t)
	}

	/**
	 * A row of teeth, as hand-made rigs move it: with the middle of its lip - the upper row with the upper lip, the lower
	 * with the lower - and bending [TEETH_BEND] of the way the lip bends from its middle, so a smile's teeth curve with it
	 * part way, each column at its own height, never squashed. Drawn running on under the lips, the opening clips it, so it
	 * meets the lines without a gap however the lips curve. It keeps its width when the mouth pouts and widens evenly with
	 * a wide or smiling mouth. Going behind its lip it lags - the upper row follows [UPPER_TEETH_SHARE] of its lip's coming
	 * down, the lower row [LOWER_TEETH_SHARE] of its lip's rise, and a rounding mouth draws either row in hardly at all - so
	 * the opening hides the teeth of a small, round or pouting mouth rather than squeezing them into it.
	 */
	private fun teeth(u: Float, pose: Pose, upper: Boolean): Pair<Float, Float> {
		val smile = (pose.corner / 0.06f).coerceIn(0f, 1.5f)
		val widen = (pose.width - 1f).coerceAtLeast(0f) + TEETH_SMILE_WIDEN * smile
		val un = 0.5f + (u - 0.5f) * (1f + widen) + pose.shift
		val middle = middleDrop(pose, upper)
		var drop = middle
		// Where its lip closes over it - the upper lip coming down, the lower one up - a row lags, the lip covering it.
		val inward = if (upper) drop > 0f else drop < 0f
		if (inward) drop *= (if (upper) UPPER_TEETH_SHARE else LOWER_TEETH_SHARE) * (1f - pose.round.coerceIn(0f, 1f))
		// The lip's bend at this column: how much further it has moved here than at its middle.
		val at = un.coerceIn(0f, 1f)
		val drawn = if (upper) drawnUpper(at) else drawnLower(at)
		val bend = edges(pose, at).let { if (upper) it.first else it.second } - drawn - middle
		return (un - u) to drop + fit.baseline(un) - fit.baseline(u) + TEETH_BEND * bend
	}

	/** The slope of [edge] at [a], one-sided at the corners. */
	private fun slope(edge: (Float) -> Float, a: Float): Float {
		val a0 = (a - 0.01f).coerceAtLeast(0f); val a1 = (a + 0.01f).coerceAtMost(1f)
		return (edge(a1) - edge(a0)) / (a1 - a0)
	}

	/** How far the upper or lower lip's middle has moved down from the drawing in [pose]. */
	private fun middleDrop(pose: Pose, upper: Boolean): Float = edges(pose, 0.5f).let { if (upper) it.first - drawnUpper(0.5f) else it.second - drawnLower(0.5f) }

	/** The point of the drawn [edge] nearest ([u], [r]): where along the mouth it is, and the offset from it. */
	private fun nearest(edge: (Float) -> Float, u: Float, r: Float): Triple<Float, Float, Float> {
		var best = 0f; var bestDistance = Float.MAX_VALUE
		for (k in 0..NEAREST_STEPS) {
			val at = k.toFloat() / NEAREST_STEPS
			val dx = u - at; val dy = r - edge(at)
			val distance = dx * dx + dy * dy
			if (distance < bestDistance) { bestDistance = distance; best = at }
		}
		return Triple(best, u - best, r - edge(best))
	}

	/** The mouth at [form] (−1 pout … 1 smile) and [open] (0 … 1). */
	fun pose(form: Float, open: Float): Pose {
		val (up, lo) = staged(open)
		val width = interpolate(OPEN_KEYS, WIDTHS, open) * (1f - 0.06f * (open - 1f).coerceAtLeast(0f))
		val f = (form * options.formStrength).coerceIn(-1.5f, 1.5f)
		val opened = (open / 0.25f).coerceIn(0f, 1f)
		return if (f >= 0f) Pose(up, lo, width * (1f + 0.08f * f), corner = 0.06f * f, flatTop = f * opened)
		else Pose(up, lo, width * (1f + f * (0.22f + 0.3f * (open / 0.5f).coerceIn(0f, 1f))), corner = 0.05f * f * (1f - 0.6f * open.coerceIn(0f, 1f)),
			round = -f * 0.9f * opened)
	}

	/** How far the upper and the lower lip have gone at [open]: the lower lip first, the upper one late. */
	private fun staged(open: Float): Pair<Float, Float> {
		// Past the drawing the jaw goes on dropping: the lower lip twice as far as the upper one rises.
		if (open > 1f) return (1f + 0.45f * (open - 1f)) to (1f + 0.9f * (open - 1f))
		val lower = floatArrayOf(0f, 0.08f, options.early, (options.early + 1f) / 2f + 0.05f, 1f)
		return interpolate(OPEN_KEYS, UPPERS, open) to interpolate(OPEN_KEYS, lower, open)
	}

	/** The vowel poses lip sync drives, each over the closed mouth. */
	fun vowel(name: Char): Pose = when (name) {
		'A' -> pose(0f, 1f)
		'I' -> staged(0.3f).let { (u, l) -> Pose(u, l, 1.18f, corner = 0.03f, flatTop = 0.5f) }
		'U' -> staged(0.3f).let { (u, l) -> Pose(u, l, 0.62f, round = 1f) }
		'E' -> staged(0.55f).let { (u, l) -> Pose(u, l, 1.1f, corner = 0.01f) }
		else -> staged(0.75f).let { (u, l) -> Pose(u, l, 0.78f, round = 0.8f) }
	}

	private fun form(positions: FloatArray, frame: Bounds, pose: Pose, follow: Follow = Follow.WHOLE): MeshDeltaForm =
		form(positions, frame) { u, v -> moveAs(follow, u, v, pose) }

	private fun form(positions: FloatArray, frame: Bounds, change: (Float, Float) -> Pair<Float, Float>): MeshDeltaForm {
		val out = FloatArray(positions.size)
		for (i in positions.indices step 2) {
			val (u, v) = fit.toMouth(positions[i], positions[i + 1])
			val (du, dv) = change(u, v)
			val (dx, dy) = fit.canvasDelta(du, dv)
			out[i] = dx / frame.width.coerceAtLeast(1e-4f); out[i + 1] = dy / frame.height.coerceAtLeast(1e-4f)
		}
		return MeshDeltaForm(out)
	}

	/** [positions] (x, y pairs) where the mouth at rest - shut - holds them, a layer moving as [follow]. */
	fun atRest(positions: FloatArray, follow: Follow): FloatArray {
		val rest = pressed(pose(0f, 0f), 0f, 0f)
		return FloatArray(positions.size).also { out ->
			for (i in positions.indices step 2) {
				val (u, v) = fit.toMouth(positions[i], positions[i + 1])
				val (du, dv) = moveAs(follow, u, v, rest)
				val (dx, dy) = fit.canvasDelta(du, dv)
				out[i] = positions[i] + dx; out[i + 1] = positions[i + 1] + dy
			}
		}
	}

	/** A mouth layer's grid: its form by its openness. Deltas are over the parent [frame]; the layer moves as [follow]. */
	fun grid(positions: FloatArray, frame: Bounds, mouthForm: ParameterId, mouthOpen: ParameterId, follow: Follow = Follow.WHOLE): KeyformGrid<MeshDeltaForm> =
		keyed(mouthForm, mouthOpen) { pose -> form(positions, frame, pose, follow) }

	/**
	 * A grid over the form and the openness - and with the face capture, the lips pressed or parted ([pressed]) - whose
	 * every key is [form] of that key's pose. Pressing and parting are keyed with the opening and the form, not added to
	 * them, so a smile bares its teeth and a half-open mouth presses its lips each its own way.
	 */
	fun keyed(mouthForm: ParameterId, mouthOpen: ParameterId, form: (Pose) -> MeshDeltaForm): KeyformGrid<MeshDeltaForm> {
		val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
		val presses = if (options.capture) PRESS_KEYS else floatArrayOf(0f)
		for ((p, press) in presses.withIndex()) for ((o, open) in openKeys.withIndex()) for ((f, value) in FORM_KEYS.withIndex()) {
			val pose = pressed(pose(value, open), press, open)
			cells += KeyformCell(if (options.capture) intArrayOf(f, o, p) else intArrayOf(f, o), form(pose))
		}
		val axes = listOf(KeyformAxis(mouthForm, FORM_KEYS), KeyformAxis(mouthOpen, openKeys)) +
			if (options.capture) listOf(KeyformAxis(PRESS_OPEN, PRESS_KEYS)) else emptyList()
		return KeyformGrid(axes, cells)
	}

	/**
	 * [pose] (at [open]) with the lips pressed ([press] < 0: rolled in on each other, the mouth a little narrower) or
	 * parted ([press] > 0: open at least to a slit that bares the teeth, the corners still shut).
	 */
	fun pressed(pose: Pose, press: Float, open: Float): Pose = when {
		press < 0f -> pose.copy(press = 0.7f * -press, width = pose.width * (1f + 0.04f * press))
		press > 0f -> {
			val (u, l) = staged(0.2f)
			val k = press * (1f - open.coerceIn(0f, 1f))
			pose.copy(upper = pose.upper + (max(pose.upper, u + 0.08f) - pose.upper) * k, lower = pose.lower + (max(pose.lower, l * 0.7f) - pose.lower) * k)
		}
		else -> pose
	}

	/** Where the point ([x], [y]) of the mouth (in the rig's space) goes in [pose]. */
	fun point(x: Float, y: Float, pose: Pose): Pair<Float, Float> {
		val (u, v) = fit.toMouth(x, y)
		val (du, dv) = move(u, v, pose, tight = true)
		val (dx, dy) = fit.canvasDelta(du, dv)
		return (x + dx) to (y + dy)
	}

	/**
	 * A drawn closed mouth's grid: it bends with the form - corners up into a smile, down into a pout - narrowing or
	 * widening about its middle. It shows only while the mouth is shut.
	 */
	fun closedGrid(positions: FloatArray, frame: Bounds, mouthForm: ParameterId): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(listOf(KeyformAxis(mouthForm, FORM_KEYS)), FORM_KEYS.mapIndexed { f, form ->
			KeyformCell(intArrayOf(f), form(positions, frame) { u, v -> closedMove(u, v, pose(form, 0f)) })
		})

	private fun closedMove(u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		val uc = u.coerceIn(0f, 1f)
		val un = 0.5f + (u - 0.5f) * pose.width / WIDTHS[0] + pose.shift
		val rest = closed(uc)
		val (top, _) = edges(pose.copy(upper = 0f, lower = 0f), uc)
		return (un - u) to (fit.baseline(un) - fit.baseline(u) + (top - rest) + gap * 0.5f * EyeRig.hump(uc))
	}

	/**
	 * The mouth's additive shapes (each over the closed mouth): the vowels lip sync drives (`mouth.vowel`) and the face
	 * capture's mouth movements (`mouth.capture`) - sideways, pucker and widen, shrug, the lips pressed or parted.
	 * [visible] gives a form's opacity: a drawn closed mouth hides and the open mouth's parts show while a vowel opens
	 * the mouth.
	 */
	fun shapes(positions: FloatArray, frame: Bounds, closedLayer: Boolean, visible: (Boolean) -> Float, tongue: Boolean = false,
	           follow: Follow = Follow.WHOLE): List<BlendShapeBinding<MeshForm>> =
		shapes({ pose -> (if (closedLayer) form(positions, frame) { u, v -> closedMove(u, v, pose) } else form(positions, frame, pose, follow)).positionDeltas }, pressKeyed = !closedLayer, visible = visible) +
			tongueShapes(positions, frame, closedLayer, tongue, visible, follow)

	/**
	 * The tongue put out (`mouth.capture` with a tongue layer): the mouth opens to about half and the tongue slides down
	 * past where the lower lip was, inside the opened mouth.
	 */
	private fun tongueShapes(positions: FloatArray, frame: Bounds, closedLayer: Boolean, tongue: Boolean, visible: (Boolean) -> Float,
	                         follow: Follow): List<BlendShapeBinding<MeshForm>> {
		if (!options.capture || !options.tongue) return emptyList()
		val open = staged(0.5f).let { (u, l) -> Pose(u, l, 0.95f) }
		val deltas = if (closedLayer) form(positions, frame) { u, v -> closedMove(u, v, open) }.positionDeltas
			else form(positions, frame) { u, v -> moveAs(follow, u, v, open).let { (du, dv) -> du to dv + if (tongue) 0.3f * h * EyeRig.hump(u.coerceIn(0f, 1f)).coerceAtLeast(0.4f) else 0f } }.positionDeltas
		return listOf(BlendShapeBinding(TONGUE_OUT, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(deltas, opacity = visible(true)))))
	}

	/**
	 * The same shapes for a mesh whose deltas at a pose [deltas] gives - a lip ribbon laid along the posed lips. A mesh
	 * whose grid keys the lips pressed and parted ([keyed]; [pressKeyed]) takes from that shape only what the parting
	 * shows, its geometry being in its keys.
	 */
	fun shapes(deltas: (Pose) -> FloatArray, pressKeyed: Boolean = true, visible: (Boolean) -> Float): List<BlendShapeBinding<MeshForm>> = buildList {
		fun shapeOf(pose: Pose, opens: Boolean): MeshForm = MeshForm(deltas(pose), opacity = visible(opens))
		if (options.vowels) for ((name, id) in VOWELS) add(BlendShapeBinding(id, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(vowel(name), true))))
		if (options.capture) {
			val rest = pose(0f, 0f)
			add(BlendShapeBinding(MOUTH_X, floatArrayOf(-1f, 0f, 1f), 1, listOf(shapeOf(rest.copy(shift = -0.18f, corner = 0.01f), false), null,
				shapeOf(rest.copy(shift = 0.18f, corner = 0.01f), false))))
			add(BlendShapeBinding(PUCKER_WIDEN, floatArrayOf(-1f, 0f, 1f), 1, listOf(shapeOf(rest.copy(width = rest.width * 0.7f, corner = -0.015f, lift = 0.01f), false), null,
				shapeOf(rest.copy(width = rest.width * 1.16f, corner = 0.01f), false))))
			add(BlendShapeBinding(SHRUG, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(rest.copy(corner = -0.025f, lift = 0.05f), false))))
			// The funnel of "ch", "sh" and "u": the corners drawn in and the lips pushed forward round a small round opening.
			val funnel = staged(0.22f).let { (u, l) -> rest.copy(upper = u, lower = l, width = rest.width * 0.62f, round = 1f) }
			add(BlendShapeBinding(FUNNEL, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(funnel, true))))
			// The jaw opened behind closed lips ("mmm"): the shut mouth drops and narrows a little with the chin.
			add(BlendShapeBinding(JAW_OPEN, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(rest.copy(lift = -0.06f, width = rest.width * 0.94f), false))))
			if (pressKeyed) {
				// Only the parting's opacity - the open mouth's parts show and a drawn closed mouth hides - at the shut mouth's place,
				// which the grid holds at rest, so it moves nothing.
				val shows = visible(true)
				if (!shows.isNaN()) add(BlendShapeBinding(PRESS_OPEN, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(deltas(rest), opacity = shows))))
			} else add(BlendShapeBinding(PRESS_OPEN, floatArrayOf(-1f, 0f, 1f), 1, listOf(shapeOf(pressed(rest, -1f, 0f), false), null,
				shapeOf(pressed(rest, 1f, 0f), true))))
		}
	}

	/**
	 * The lower face following the jaw (`mouth.jaw`): below the mouth the skin drops with the opening, from nothing at
	 * the corners' line to [MouthOptions.jawShare] of the mouth's drawn opening at [chin], the cheeks narrowing a
	 * little; [nose] only nudges down. Over the closed mouth on the openness, and on the face capture's cheek puff.
	 */
	fun faceShapes(positions: FloatArray, frame: Bounds, chin: Float, nose: Boolean, mouthOpen: ParameterId, spring: ParameterId?): List<BlendShapeBinding<MeshForm>> = buildList {
		val drop = options.jawShare * h
		fun weight(v: Float) = ((v - 0.02f) / (chin - 0.02f).coerceAtLeast(0.05f)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
		fun jaw(amount: Float) = MeshForm(form(positions, frame) { u, v ->
			if (nose) 0f to amount * drop * 0.08f
			else weight(v).let { w -> -(u - 0.5f) * 0.25f * amount * drop * w to amount * drop * w }
		}.positionDeltas, opacity = Float.NaN)
		if (options.jaw) {
			add(if (options.reach > 1.001f) BlendShapeBinding(mouthOpen, floatArrayOf(0f, 1f, options.reach), 0, listOf(null, jaw(1f), jaw(options.reach)))
				else BlendShapeBinding(mouthOpen, floatArrayOf(0f, 1f), 0, listOf(null, jaw(1f))))
			if (spring != null) add(BlendShapeBinding(spring, floatArrayOf(-1f, 0f, 1f), 1, listOf(jaw(-0.35f), null, jaw(0.35f))))
		}
		if (options.capture) add(BlendShapeBinding(JAW_OPEN, floatArrayOf(0f, 1f), 0, listOf(null, jaw(1.4f))))
		if (options.capture && !nose) add(BlendShapeBinding(CHEEK_PUFF, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(form(positions, frame) { u, v ->
			// The cheeks swell beside the mouth, most level with it and a little below.
			val side = u - 0.5f
			val across = ((abs(side) - 0.35f) / 0.6f).coerceIn(0f, 1f)
			val down = (1f - abs(v - 0.15f) / 0.75f).coerceIn(0f, 1f)
			val w = sin(across * Math.PI.toFloat() / 2f) * down * down
			(if (side >= 0f) 1f else -1f) * 0.1f * w to 0.02f * w
		}.positionDeltas, opacity = Float.NaN))))
	}

	/**
	 * The mouth's inside lagging behind its lips as the head turns (`mouth.interior-depth`): [depth] of the mouth's
	 * width against the turn, deeper parts more.
	 */
	fun depthShapes(positions: FloatArray, frame: Bounds, depth: Float, angleX: ParameterId, angleY: ParameterId): List<BlendShapeBinding<MeshForm>> {
		if (!options.depth) return emptyList()
		val d = depth * options.depthAmount
		fun shift(du: Float, dv: Float) = MeshForm(form(positions, frame) { _, _ -> du to dv }.positionDeltas, opacity = Float.NaN)
		val yaw = sin(Math.toRadians(30.0)).toFloat() * d
		return listOf(
			BlendShapeBinding(angleX, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(yaw * (if (fit.axisX >= 0f) 1f else -1f), 0f), null,
				shift(-yaw * (if (fit.axisX >= 0f) 1f else -1f), 0f))),
			BlendShapeBinding(angleY, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(0f, -yaw * 0.6f), null, shift(0f, yaw * 0.6f))),
		)
	}

	companion object {
		/** The openness where the lips part and a drawn closed mouth hands over to the open one. */
		const val SWAP = 0.05f
		/** Samples of a drawn edge searched for the point nearest a point past it. */
		private const val NEAREST_STEPS = 96
		val OPEN_KEYS = floatArrayOf(0f, SWAP, 0.25f, 0.5f, 1f)
		val PRESS_KEYS = floatArrayOf(-1f, 0f, 1f)
		val FORM_KEYS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
		private val UPPERS = floatArrayOf(0f, 0.03f, 0.1f, 0.38f, 1f)
		private val WIDTHS = floatArrayOf(1.04f, 1.04f, 1.08f, 1.06f, 1f)
		private const val UPPER_TEETH_SHARE = 0.85f
		private const val LOWER_TEETH_SHARE = 0.7f
		/** How much wider a full smile's teeth spread, toward its raised corners. */
		private const val TEETH_SMILE_WIDEN = 0.25f
		/** How much of its lip's bend a row of teeth follows, as hand-made rigs bend them part way or not at all. */
		private const val TEETH_BEND = 0.6f
		private const val TONGUE_SHARE = 0.55f
		private const val DEEP_SHARE = 0.2f
		/** How far pressed lips roll in past each other at the middle, of the mouth's width. */
		private const val PRESS_ROLL = 0.05f
		/** A lip's skin margin rides whole within [SKIN_NEAR] of its edge and loses [SKIN_FAR_LOSS] of the ride over [SKIN_REACH] further out. */
		private const val SKIN_NEAR = 0.06f
		private const val SKIN_REACH = 0.45f
		private const val SKIN_FAR_LOSS = 0.65f

		val VOWELS = listOf('A', 'I', 'U', 'E', 'O').map { it to ParameterId("Param$it") }
		val MOUTH_X = ParameterId("ParamMouthX")
		val PUCKER_WIDEN = ParameterId("ParamMouthPuckerWiden")
		val SHRUG = ParameterId("ParamMouthShrug")
		val FUNNEL = ParameterId("ParamMouthFunnel")
		val PRESS_OPEN = ParameterId("ParamMouthPressLipOpen")
		val CHEEK_PUFF = ParameterId("ParamCheekPuff")
		val JAW_OPEN = ParameterId("ParamJawOpen")
		val TONGUE_OUT = ParameterId("ParamTongueOut")
		val SPRING = ParameterId("ParamMouthSpring")
		/** The shapes that open the mouth: they show its open parts and hide a drawn closed mouth. */
		val OPENING = VOWELS.map { it.second }.toSet() + PRESS_OPEN + TONGUE_OUT + FUNNEL

		private fun interpolate(keys: FloatArray, values: FloatArray, x: Float): Float {
			if (x <= keys.first()) return values.first()
			for (i in 1 until keys.size) if (x <= keys[i]) {
				val t = (x - keys[i - 1]) / (keys[i] - keys[i - 1])
				return values[i - 1] + (values[i] - values[i - 1]) * t
			}
			return values.last()
		}
	}
}

/**
 * The fitted mouth of a project: [rig] when the drawn mouth could be read ([result]), and which layers it moves;
 * [chin] is how far down the face reaches below the mouth, in the mouth's frame.
 */
class FittedMouth(val result: MouthFitting.Result, val rig: MouthRig?, val chin: Float = 0.6f, layers: List<io.github.psd2live.core.ClassifiedLayer> = emptyList()) {
	private val variant = result.layers.firstOrNull()?.semantic?.variant

	/** Whether a closed mouth is drawn beside the open one: it shows at rest and hands over as the lips part. */
	val closedLine: Boolean = layers.any { it.semantic.tag == io.github.psd2live.core.SemanticTag.MOUTH_CLOSE && it.opaquePixels > 0 && covers(it) }

	/** Whether [layer] is a part of the fitted mouth: the mouth itself, its closed line, teeth, tongue or inside, its lips and lines. */
	fun covers(layer: io.github.psd2live.core.ClassifiedLayer): Boolean = rig != null && (layer.semantic.role == null || layer.semantic.role in ROLES) &&
		layer.semantic.variant == variant && layer.semantic.tag in TAGS

	private val own = layers.filter { covers(it) && it.opaquePixels > 0 }

	/** Whether the mouth is drawn in layers - its lips or lip lines apart from its inside - rather than whole. */
	val layered: Boolean = own.any { it.semantic.role in LIPS }

	/** Whether lip layers with their skin cover the mouth's inside, so it need not close whole. */
	val lipped: Boolean = own.any { it.semantic.role == io.github.psd2live.core.PartRole.UPPER_LIP || it.semantic.role == io.github.psd2live.core.PartRole.LOWER_LIP }

	/**
	 * How [layer] follows the lips ([MouthRig.Follow]). A mouth drawn whole moves as one, closing whole when ribbons
	 * outline it ([MouthOptions.exactClose]); a layered mouth's inside closes as far as lips cover it, and its lips, lines,
	 * teeth, tongue and the dark each move their own way. A highlight or shadow on the mouth follows what it lies on: the
	 * lip above or below the opening, or the dark inside it.
	 */
	fun follow(layer: io.github.psd2live.core.ClassifiedLayer): MouthRig.Follow {
		val tag = layer.semantic.tag
		val whole = if (rig?.options?.exactClose == true) MouthRig.Follow.WHOLE_TIGHT else MouthRig.Follow.WHOLE
		return when (tag) {
			io.github.psd2live.core.SemanticTag.TOOTH_T -> MouthRig.Follow.UPPER_TEETH
			io.github.psd2live.core.SemanticTag.TOOTH_B -> MouthRig.Follow.LOWER_TEETH
			io.github.psd2live.core.SemanticTag.TONGUE -> MouthRig.Follow.TONGUE
			io.github.psd2live.core.SemanticTag.MOUTH_CLOSE -> whole
			else -> when (layer.semantic.role) {
				io.github.psd2live.core.PartRole.UPPER_LINE -> MouthRig.Follow.UPPER_LINE
				io.github.psd2live.core.PartRole.LOWER_LINE -> MouthRig.Follow.LOWER_LINE
				io.github.psd2live.core.PartRole.UPPER_LIP -> MouthRig.Follow.UPPER_LIP
				io.github.psd2live.core.PartRole.LOWER_LIP -> MouthRig.Follow.LOWER_LIP
				io.github.psd2live.core.PartRole.HIGHLIGHT, io.github.psd2live.core.PartRole.SHADOW -> lying(layer)
				else -> if (!layered) whole else if (lipped) MouthRig.Follow.INSIDE else MouthRig.Follow.OPENING
			}
		}
	}

	/** What a highlight or shadow on the mouth lies on, from where its middle is against the drawn lip edges. */
	private fun lying(layer: io.github.psd2live.core.ClassifiedLayer): MouthRig.Follow {
		val fit = rig?.fit ?: return MouthRig.Follow.WHOLE
		val (u, v) = fit.toMouth(layer.centroidX, layer.centroidY)
		val r = v - fit.baseline(u)
		val top = MouthFit.sample(fit.upper, u) - fit.baseline(u); val bottom = MouthFit.sample(fit.lower, u) - fit.baseline(u)
		val margin = (bottom - top) * 0.2f
		return when {
			r < top + margin -> MouthRig.Follow.UPPER_LIP
			r > bottom - margin -> MouthRig.Follow.LOWER_LIP
			layer.semantic.role == io.github.psd2live.core.PartRole.SHADOW -> MouthRig.Follow.DEEP
			lipped -> MouthRig.Follow.INSIDE
			else -> MouthRig.Follow.OPENING
		}
	}

	companion object {
		/** The roles that set a layer of the mouth apart from its inside. */
		val LIPS = setOf(io.github.psd2live.core.PartRole.UPPER_LIP, io.github.psd2live.core.PartRole.LOWER_LIP,
			io.github.psd2live.core.PartRole.UPPER_LINE, io.github.psd2live.core.PartRole.LOWER_LINE)
		/** The roles a layer of the fitted mouth can have. */
		val ROLES = LIPS + io.github.psd2live.core.PartRole.HIGHLIGHT + io.github.psd2live.core.PartRole.SHADOW
		val TAGS = setOf(io.github.psd2live.core.SemanticTag.MOUTH, io.github.psd2live.core.SemanticTag.MOUTH_OPEN,
			io.github.psd2live.core.SemanticTag.MOUTH_CLOSE, io.github.psd2live.core.SemanticTag.TOOTH_T,
			io.github.psd2live.core.SemanticTag.TOOTH_B, io.github.psd2live.core.SemanticTag.TONGUE)
	}
}
