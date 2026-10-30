package io.github.psd2live.core.fit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The `eye/fitted` base for one eye: how its lids close, posed from the eye's [fit] by the pseudo-3D [EyelidModel],
 * and how each kind of eye mesh follows them.
 *
 * - The upper lid sinks over the eyeball as the model's plane turns: its edge at an openness is the model's arc plus
 *   what the drawn edge has beyond it, scaled with the arc's height, so the drawn shape - an outer corner that droops, a
 *   peak off centre - carries into every lowered lid. The edge's middle comes down evenly to the corners' line by the
 *   [crease] openness, keeping the lid's upward arc - a lowered, sleepy lid - and only then turns into the closed lid's
 *   downward arc. When the art draws a closed line, the closed lid lies on it.
 * - The lower lid rises only [lowerShare] of the way to the closed lid, which lies just above it: closing, the white
 *   folds away under the upper lid, so nothing of the white or iris shows once the eye is shut.
 * - The white fills between the lids. The upper lash rides the upper edge and thins to [lashSquash]; the part of a lash
 *   layer that reaches down to the lower lid rides that instead. The lower lash rides the lower edge, and lid lines and
 *   shadows ([EyeMesh.SKIN]) move with the skin: a field set along both lid edges and spread smoothly to nothing
 *   ([BiharmonicField]).
 *
 * Widening above 1 adds two-dimensional polynomial displacements to the drawn edges, with shared corner movement
 * and no change to the lash's column thickness. Smiling ([smileClosed]), an emotion and the lids following the gaze
 * ([shape]) are also [Pose]s; the widened pose carries its horizontal displacements alongside the edge heights.
 *
 * [roundness] scales how far the eyeball bulges toward the viewer: higher keeps more of the arc while the lid is half
 * closed.
 */
class EyeRig(
	val fit: EyeFit, lowerShare: Float, roundness: Float, lashSquash: Float,
	/** How high the smiling closed lid arches, in eye widths, and how far the lower lid pushes up into it ([smileClosed]). */
	private val smileArc: Float = 0.12f, private val smilePush: Float = 0.5f,
	/** How far the lids open at the widest ([upper] at 2), as a share of the drawn opening. */
	private val wideLift: Float = 0.1f,
) {
	enum class EyeMesh { WHITE, UPPER_LASH, LOWER_LASH, SKIN }

	/**
	 * Where both lid edges are, sampled like [EyeFit.upper]; how far the white has folded up under the upper lid (1 shut);
	 * how thick the lashes are, as a share of their drawn thickness.
	 */
	class Pose(val upper: List<Float>, val lower: List<Float>, val fold: Float = 0f, val lashes: Float = 1f,
	           val upperDx: List<Float>? = null, val lowerDx: List<Float>? = null)

	private val model = EyelidModel(radius = 0.5f + 0.18f * roundness, centreY = -0.18f * roundness)
	private val mid = EyeFit.SAMPLES / 2
	private val closedTarget: List<Float> = fit.closed ?: (0 until EyeFit.SAMPLES).map { k ->
		// Without a drawn closed line, the lid closes just above the lower lid, which barely moves: the closed eye is a
		// line low in the eye that follows the lower lid's curve, as hand-made rigs close it.
		fit.lower[k] - 0.15f * (fit.lower[k] - fit.upper[k])
	}

	/** [curve] measured from the corners' line, as the lid model works: its arcs rise from the line between the corners. */
	private fun rise(curve: List<Float>, k: Int) = curve[k] - fit.baseline(EyeFit.u(k))
	private val openRise = rise(fit.upper, mid)
	private val closedRise = rise(closedTarget, mid)
	private val openAngle = model.angleFor(openRise)

	/**
	 * The openness at which the upper lid's middle reaches the corners' line: where an even closing would pass it - the
	 * share of the opening that lies below that line - kept between [CREASE] and [LATEST_CREASE], so an eye with high
	 * corners still lowers its lid steadily and one with low corners turns into the closed arc only at the end.
	 */
	val crease: Float = (closedRise / (closedRise - openRise).coerceAtLeast(1e-3f)).coerceIn(CREASE, LATEST_CREASE)
	private val share = lowerShare
	private val squash = lashSquash

	/** The drawn opening at the middle, in eye widths: the measure an emotion's or the gaze's shapes scale with. */
	val opening: Float = fit.lower[mid] - fit.upper[mid]

	/** The openness values the eye is keyed at: closed, the swap to a drawn closed line (or a near-closed key), the crease, and two on the way open. */
	val keys: FloatArray = floatArrayOf(0f, if (fit.closed != null) SWAP else crease / 2f, crease, crease + (1f - crease) / 3f, crease + 2f * (1f - crease) / 3f, 1f)

	// Meshes are posed in parallel, so the poses are shared through concurrent maps.
	private val upperByKey = java.util.concurrent.ConcurrentHashMap<Float, List<Float>>()
	private val lowerByKey = java.util.concurrent.ConcurrentHashMap<Float, List<Float>>()
	private val expandedByKey = java.util.concurrent.ConcurrentHashMap<Float, Pose>()

	/** The widening line, with its corners and lower rim moving with it, relative to the eye as drawn. */
	private fun expanded(open: Float): Pose = expandedByKey.getOrPut(open) {
		val moves = (0 until EyeFit.SAMPLES).map { EyeOpeningModel.DEFAULT.at(EyeFit.u(it), wideLift * (open - 1f)) }
		Pose(
			moves.indices.map { fit.upper[it] + moves[it][1] * opening },
			moves.indices.map { fit.lower[it] + moves[it][3] * opening },
			upperDx = moves.map { it[0] }, lowerDx = moves.map { it[2] },
		)
	}

	/**
	 * The upper lid edge at [open]: the drawn closed line when closed, the model's sinking arc between, and above 1 the
	 * drawn edge lifted by up to [wideLift] of the opening (a widened, surprised eye at 2).
	 */
	fun upper(open: Float): List<Float> = upperByKey.getOrPut(open) {
		if (open > 1f) return@getOrPut expanded(open).upper
		if (open == 1f) return@getOrPut fit.upper
		if (open <= 0f && fit.closed != null) return@getOrPut fit.closed
		val height = if (open >= crease) openRise * (open - crease) / (1f - crease) else closedRise * (1f - open / crease)
		val angle = model.angleFor(height)
		// What the drawn edge has beyond the model's arc shrinks with the arc, and is gone once the lid passes the corners;
		// past them the lid takes the closed lid's shape.
		val keep = (height / openRise).coerceIn(0f, 1f)
		val toClosed = if (open >= crease) 0f else 1f - open / crease
		(0 until EyeFit.SAMPLES).map { k ->
			val u = EyeFit.u(k)
			val lid = fit.baseline(u) + model.edge(u, angle) + (rise(fit.upper, k) - model.edge(u, openAngle)) * keep
			lid + (closedTarget[k] - (fit.baseline(u) + model.edge(u, model.angleFor(closedRise)))) * toClosed
		}
	}

	/** The lower lid edge at [open]: risen [share] of the way to the closed lid, and above 1 lowered a little. */
	fun lower(open: Float): List<Float> = lowerByKey.getOrPut(open) {
		if (open > 1f) return@getOrPut expanded(open).lower
		// The lower lid barely moves in a blink; the white folds away under the upper one instead.
		val closed = upper(0f)
		(0 until EyeFit.SAMPLES).map { k -> fit.lower[k] - share * (1f - open) * (fit.lower[k] - closed[k]).coerceAtLeast(0f) }
	}

	/** The lids at [open], closing as this eye closes. */
	fun pose(open: Float): Pose = if (open > 1f) expanded(open) else Pose(upper(open), lower(open), sqrt(((crease - open) / crease).coerceIn(0f, 1f)),
		squash + (1f - squash) * open.coerceAtMost(1f))

	/** The closed lid of a smile: an upward arch [smileArc] high from corner to corner, onto which the lower lid pushes. */
	val smileClosed: List<Float> = (0 until EyeFit.SAMPLES).map { k -> fit.baseline(EyeFit.u(k)) - smileArc * 4f * EyeFit.u(k) * (1f - EyeFit.u(k)) }

	/**
	 * The lids of a smiling eye at [open]: open, the eye is as drawn; closing, both lids meet on [smileClosed] rather than
	 * on the closed lid - the upper lid comes down to the arch, the lower lid pushes up into it, [smilePush] of the way
	 * already while the eye is still open. Both edges stay arched upward the whole way, so a straight blend loses nothing.
	 */
	fun smilePose(open: Float): Pose {
		val o = open.coerceIn(0f, 1f)
		val up = (0 until EyeFit.SAMPLES).map { k -> smileClosed[k] + (fit.upper[k] - smileClosed[k]) * o }
		val push = smilePush * (1f - o) + (1f - smilePush) * (1f - o) * (1f - o)
		val low = (0 until EyeFit.SAMPLES).map { k -> fit.lower[k] - push * (fit.lower[k] - smileClosed[k]) }
		return Pose(up, low, sqrt(((0.3f - o) / 0.3f).coerceIn(0f, 1f)), squash + (1f - squash) * o)
	}

	/** The open eye with its lid edges moved by [dUpper] and [dLower] (eye widths, down positive) at each place u. */
	fun shape(dUpper: (Float) -> Float, dLower: (Float) -> Float): Pose = Pose(
		(0 until EyeFit.SAMPLES).map { k -> fit.upper[k] + dUpper(EyeFit.u(k)) },
		(0 until EyeFit.SAMPLES).map { k -> fit.lower[k] + dLower(EyeFit.u(k)) })

	private val fields = java.util.concurrent.ConcurrentHashMap<Pose, BiharmonicField>()

	/** The skin's displacement field of [pose]: both lid edges' movement spread over the lattice around the eye. */
	private fun field(pose: Pose): BiharmonicField = fields.getOrPut(pose) {
		val fixed = HashMap<Int, Pair<Float, Float>>()
		for (k in 0 until EyeFit.SAMPLES) {
			val u = EyeFit.u(k)
			fixed[LATTICE.nearest(u, fit.upper[k])] = (pose.upperDx?.get(k) ?: 0f) to pose.upper[k] - fit.upper[k]
			fixed.putIfAbsent(LATTICE.nearest(u, fit.lower[k]), (pose.lowerDx?.get(k) ?: 0f) to pose.lower[k] - fit.lower[k])
		}
		BiharmonicField(LATTICE.columns, LATTICE.rows, LATTICE.left, LATTICE.top, LATTICE.right, LATTICE.bottom, fixed)
	}

	private val poses = java.util.concurrent.ConcurrentHashMap<Float, Pose>()

	/** How far a [mesh] vertex at canvas ([x], [y]) moves at [open], in canvas units. */
	fun delta(mesh: EyeMesh, x: Float, y: Float, open: Float): Pair<Float, Float> =
		if (open == 1f) 0f to 0f else move(mesh, x, y, poses.getOrPut(open) { pose(open) })

	/** How far a [mesh] vertex at canvas ([x], [y]) moves into [pose], in canvas units. */
	fun move(mesh: EyeMesh, x: Float, y: Float, pose: Pose): Pair<Float, Float> {
		val (u, v) = fit.toEye(x, y)
		if (pose.upperDx != null) return widenedMove(mesh, u, v, pose)
		val restUpper = EyeFit.sample(fit.upper, u); val restLower = EyeFit.sample(fit.lower, u)
		val dUpper = EyeFit.sample(pose.upper, u) - restUpper
		val dLower = EyeFit.sample(pose.lower, u) - restLower
		// What rides the lower lid - a lower lash, a lash layer's lower rim - closes onto the closed lid with the white, so
		// a shut eye shows one line, as hand-made rigs close it.
		val dLowerClosing = dLower + (EyeFit.sample(pose.upper, u) - (restLower + dLower)) * pose.fold
		val dv = when (mesh) {
			EyeMesh.WHITE -> {
				// The white's tips past the corners close with the lids just inside them.
				val w = u.coerceIn(TIP, 1f - TIP)
				val top = EyeFit.sample(fit.upper, w); val base = EyeFit.sample(fit.lower, w)
				val span = (base - top).coerceAtLeast(1e-3f)
				val t = ((v - top) / span).coerceIn(-0.5f, 1.5f)
				// Folding, the white tucks a little above the lid's edge, under the lashes, so not even its collapsed edge shows.
				val moveTop = EyeFit.sample(pose.upper, w) - top - TUCK * pose.fold
				val moveBase = EyeFit.sample(pose.lower, w) - base
				// Closing, the white folds up under the lid ahead of the lower lid, so no sliver of iris is left between them.
				val bottom = moveBase + (top + moveTop - (base + moveBase)) * pose.fold
				moveTop + t * (bottom - moveTop)
			}
			EyeMesh.UPPER_LASH -> {
				val reach = u.coerceIn(TAIL, 1f - TAIL)
				val upperMove = if (reach == u) dUpper + (v - restUpper) * (pose.lashes - 1f) else {
					// Lashes drawn past the corners - a liner's wing - ride with the point where the lid ends and droop outward as
					// the eye closes, as a closed eye's wing does, rather than follow the closed arc's rising end upward.
					val closing = (1f - pose.lashes) / (1f - squash).coerceAtLeast(1e-3f)
					val turn = WING_DROOP * closing.coerceIn(0f, 1f) * (if (u > reach) 1f else -1f)
					val du0 = u - reach; val dv0 = (v - EyeFit.sample(fit.upper, reach)) * pose.lashes
					val du1 = du0 * cos(turn) - dv0 * sin(turn); val dv1 = du0 * sin(turn) + dv0 * cos(turn)
					val anchor = EyeFit.sample(pose.upper, reach)
					return fit.canvasDelta(reach + du1 - u, anchor + dv1 - v)
				}
				// A lash layer that also draws the lower lash or the eye's outline: what lies toward the lower lid rides it.
				val t = ((v - restUpper) / (restLower - restUpper).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
				val towardLower = ((t - 0.35f) / 0.3f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
				upperMove + (dLowerClosing - upperMove) * towardLower
			}
			EyeMesh.LOWER_LASH -> dLowerClosing
			EyeMesh.SKIN -> {
				val moved = field(pose).at(u, v).second
				// Skin above the lid never ends below the lid's edge.
				val edge = EyeFit.sample(pose.upper, u)
				if (v < restUpper && v + moved > edge) edge - v else moved
			}
		}
		return fit.canvasDelta(0f, dv)
	}

	/**
	 * One continuous widening field for white and liner. The ends use the same displaced corner, including on a liner
	 * that also draws the lower rim; a wing outside the opening keeps its drawn offset from that corner. There is no
	 * separate tail branch inside the eye. The upper line keeps its column thickness; a lower stroke follows its
	 * closest rim point, so a thick wing below a steep corner is not sheared by the vertical column above it.
	 */
	private fun widenedMove(mesh: EyeMesh, u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		val top = EyeFit.sample(fit.upper, u); val bottom = EyeFit.sample(fit.lower, u)
		val span = (bottom - top).coerceAtLeast(1e-3f)
		val t = ((v - top) / span).coerceIn(0f, 1f)
		val dxUpper = EyeFit.sample(checkNotNull(pose.upperDx), u)
		val dyUpper = EyeFit.sample(pose.upper, u) - top
		if (mesh == EyeMesh.SKIN) {
			val moved = field(pose).at(u, v)
			val dy = if (v < top) minOf(moved.second, EyeFit.sample(pose.upper, u) - v) else moved.second
			return fit.canvasDelta(moved.first, dy)
		}
		val lowerWeight = when (mesh) {
			EyeMesh.WHITE -> t
			EyeMesh.LOWER_LASH -> 1f
			EyeMesh.UPPER_LASH -> ((t - 0.35f) / 0.3f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
			EyeMesh.SKIN -> error("Skin is handled above")
		}
		val lowerU = if (mesh != EyeMesh.WHITE && lowerWeight > 0f) nearestRimU(fit.lower, u, v) else u
		val dxLower = EyeFit.sample(checkNotNull(pose.lowerDx), lowerU)
		val dyLower = EyeFit.sample(pose.lower, lowerU) - EyeFit.sample(fit.lower, lowerU)
		return fit.canvasDelta(dxUpper + (dxLower - dxUpper) * lowerWeight, dyUpper + (dyLower - dyUpper) * lowerWeight)
	}

	/** Closest point on the drawn rim; line thickness and wing offsets stay attached to that point. */
	private fun nearestRimU(rim: List<Float>, u: Float, v: Float): Float {
		var best = Float.POSITIVE_INFINITY
		var at = u.coerceIn(0f, 1f)
		for (k in 0 until EyeFit.SAMPLES - 1) {
			val x = EyeFit.u(k); val dx = EyeFit.u(k + 1) - x; val dy = rim[k + 1] - rim[k]
			val t = (((u - x) * dx + (v - rim[k]) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
			val px = x + t * dx; val py = rim[k] + t * dy
			val distance = (u - px) * (u - px) + (v - py) * (v - py)
			if (distance < best) { best = distance; at = px }
		}
		return at
	}

	companion object {
		/** The openness below which a drawn closed line replaces the lashes. */
		const val SWAP = 0.06f

		/** The earliest openness at which the upper lid's middle reaches the corners' line: the end of its upward arc. */
		const val CREASE = 0.15f

		/** The latest openness at which it does, for eyes whose corners sit high. */
		const val LATEST_CREASE = 0.25f

		/** How far in from a corner the lid's movement is taken for lashes drawn past it. */
		private const val TAIL = 0.12f

		/** How far above the lid's edge the folded white tucks, in eye widths. */
		private const val TUCK = 0.03f

		/** How far a lash wing past a corner turns down by the time the eye is shut, in radians. */
		private const val WING_DROOP = 0.35f

		/** How far in from a corner the lids' movement is taken for the white's tips. */
		private const val TIP = 0.04f

		/** The lattice the skin's fields are solved on, around the eye in its own frame. */
		private val LATTICE = BiharmonicField(25, 33, -0.45f, -1.4f, 1.45f, 0.9f, emptyMap())

		/** A bump that is 1 in the middle of the eye and 0 at both corners. */
		fun hump(u: Float): Float = sin(PI.toFloat() * u.coerceIn(0f, 1f))
	}
}
