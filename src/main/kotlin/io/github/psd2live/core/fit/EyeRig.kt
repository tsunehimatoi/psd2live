package io.github.psd2live.core.fit

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
 * - The lower lid rises [lowerShare] of the way to the closed lid while the eye is open, and below the crease meets the
 *   upper one, so nothing of the white or iris shows once the eye is shut.
 * - The white fills between the lids. The upper lash rides the upper edge and thins to [lashSquash]; the part of a lash
 *   layer that reaches down to the lower lid rides that instead. The lower lash rides the lower edge, and lid lines and
 *   shadows ([EyeMesh.SKIN]) move with the skin: a field set along both lid edges and spread smoothly to nothing
 *   ([BiharmonicField]).
 *
 * [roundness] scales how far the eyeball bulges toward the viewer: higher keeps more of the arc while the lid is half
 * closed.
 */
class EyeRig(val fit: EyeFit, lowerShare: Float, roundness: Float, lashSquash: Float) {
	enum class EyeMesh { WHITE, UPPER_LASH, LOWER_LASH, SKIN }

	private val model = EyelidModel(radius = 0.5f + 0.18f * roundness, centreY = -0.18f * roundness)
	private val mid = EyeFit.SAMPLES / 2
	private val closedTarget: List<Float> = fit.closed ?: (0 until EyeFit.SAMPLES).map { k ->
		// Without a drawn closed line, the lid closes onto a gentle downward arc just above the lower lid.
		val u = EyeFit.u(k)
		val bottom = fit.lower[mid] * 0.6f
		bottom * (1f - (2f * u - 1f) * (2f * u - 1f))
	}
	private val openAngle = model.angleFor(fit.upper[mid])

	/**
	 * The openness at which the upper lid's middle reaches the corners' line: where an even closing would pass it - the
	 * share of the opening that lies below that line - kept between [CREASE] and [LATEST_CREASE], so an eye with high
	 * corners still lowers its lid steadily and one with low corners turns into the closed arc only at the end.
	 */
	val crease: Float = (closedTarget[mid] / (closedTarget[mid] - fit.upper[mid]).coerceAtLeast(1e-3f)).coerceIn(CREASE, LATEST_CREASE)
	private val share = lowerShare
	private val squash = lashSquash

	/** The openness values the eye is keyed at: closed, the swap to a drawn closed line (or a near-closed key), the crease, and two on the way open. */
	val keys: FloatArray = floatArrayOf(0f, if (fit.closed != null) SWAP else crease / 2f, crease, crease + (1f - crease) / 3f, crease + 2f * (1f - crease) / 3f, 1f)

	// Meshes are posed in parallel, so the poses are shared through concurrent maps.
	private val upperByKey = java.util.concurrent.ConcurrentHashMap<Float, List<Float>>()
	private val lowerByKey = java.util.concurrent.ConcurrentHashMap<Float, List<Float>>()

	/** The upper lid edge at [open]: the drawn closed line when closed, the model's sinking arc between. */
	fun upper(open: Float): List<Float> = upperByKey.getOrPut(open) {
		if (open >= 1f) return@getOrPut fit.upper
		if (open <= 0f && fit.closed != null) return@getOrPut fit.closed
		val height = if (open >= crease) fit.upper[mid] * (open - crease) / (1f - crease) else closedTarget[mid] * (1f - open / crease)
		val angle = model.angleFor(height)
		// What the drawn edge has beyond the model's arc shrinks with the arc, and is gone once the lid passes the corners.
		val keep = (height / fit.upper[mid]).coerceIn(0f, 1f)
		(0 until EyeFit.SAMPLES).map { k ->
			val u = EyeFit.u(k)
			model.edge(u, angle) + (fit.upper[k] - model.edge(u, openAngle)) * keep
		}
	}

	/** The lower lid edge at [open]: risen [share] of the way to the closed lid. */
	fun lower(open: Float): List<Float> = lowerByKey.getOrPut(open) {
		val closed = upper(0f)
		val meet = ((crease - open) / crease).coerceIn(0f, 1f)
		val rise = share * (1f - open) + (1f - share * (1f - crease)) * meet * meet
		(0 until EyeFit.SAMPLES).map { k -> fit.lower[k] - rise.coerceAtMost(1f) * (fit.lower[k] - closed[k]).coerceAtLeast(0f) }
	}

	private val fields = java.util.concurrent.ConcurrentHashMap<Float, BiharmonicField>()

	/** The skin's displacement field at [open]: both lid edges' movement spread over the lattice around the eye. */
	private fun field(open: Float): BiharmonicField = fields.getOrPut(open) {
		val up = upper(open); val low = lower(open)
		val fixed = HashMap<Int, Pair<Float, Float>>()
		val lattice = BiharmonicField(25, 33, -0.45f, -1.4f, 1.45f, 0.9f, emptyMap())
		for (k in 0 until EyeFit.SAMPLES) {
			val u = EyeFit.u(k)
			fixed[lattice.nearest(u, fit.upper[k])] = 0f to up[k] - fit.upper[k]
			fixed.putIfAbsent(lattice.nearest(u, fit.lower[k]), 0f to low[k] - fit.lower[k])
		}
		BiharmonicField(25, 33, -0.45f, -1.4f, 1.45f, 0.9f, fixed)
	}

	/** How far a [mesh] vertex at canvas ([x], [y]) moves at [open], in canvas units. */
	fun delta(mesh: EyeMesh, x: Float, y: Float, open: Float): Pair<Float, Float> {
		if (open >= 1f) return 0f to 0f
		val (u, v) = fit.toEye(x, y)
		val restUpper = EyeFit.sample(fit.upper, u); val restLower = EyeFit.sample(fit.lower, u)
		val dUpper = EyeFit.sample(upper(open), u) - restUpper
		val dLower = EyeFit.sample(lower(open), u) - restLower
		val dv = when (mesh) {
			EyeMesh.WHITE -> {
				// The white's tips past the corners close with the lids just inside them.
				val w = u.coerceIn(TIP, 1f - TIP)
				val top = EyeFit.sample(fit.upper, w); val base = EyeFit.sample(fit.lower, w)
				val span = (base - top).coerceAtLeast(1e-3f)
				val t = ((v - top) / span).coerceIn(-0.5f, 1.5f)
				val moveTop = EyeFit.sample(upper(open), w) - top
				val moveBase = EyeFit.sample(lower(open), w) - base
				// Below the crease the white folds up under the closing lid ahead of the lower lid, so no sliver of iris
				// is left between them.
				val fold = sqrt(((crease - open) / crease).coerceIn(0f, 1f))
				val bottom = moveBase + (top + moveTop - (base + moveBase)) * fold
				moveTop + t * (bottom - moveTop)
			}
			EyeMesh.UPPER_LASH -> {
				// Lashes drawn past the corners - a liner's tail - ride with the lid's ends rather than stay behind.
				val reach = u.coerceIn(TAIL, 1f - TAIL)
				val dTail = EyeFit.sample(upper(open), reach) - EyeFit.sample(fit.upper, reach)
				val upperMove = (if (reach == u) dUpper else dTail) + (v - restUpper) * (squash + (1f - squash) * open - 1f)
				// A lash layer that also draws the lower lash or the eye's outline: what lies toward the lower lid rides it.
				val t = ((v - restUpper) / (restLower - restUpper).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
				val towardLower = ((t - 0.35f) / 0.3f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
				upperMove + (dLower - upperMove) * towardLower
			}
			EyeMesh.LOWER_LASH -> dLower
			EyeMesh.SKIN -> {
				val moved = field(open).at(u, v).second
				// Skin above the lid never ends below the lid's edge.
				val edge = EyeFit.sample(upper(open), u)
				if (v < restUpper && v + moved > edge) edge - v else moved
			}
		}
		return fit.canvasDelta(0f, dv)
	}

	companion object {
		/** The openness below which a drawn closed line replaces the lashes. */
		const val SWAP = 0.06f

		/** The earliest openness at which the upper lid's middle reaches the corners' line: the end of its upward arc. */
		const val CREASE = 0.15f

		/** The latest openness at which it does, for eyes whose corners sit high. */
		const val LATEST_CREASE = 0.35f

		/** How far in from a corner the lid's movement is taken for lashes drawn past it. */
		private const val TAIL = 0.12f

		/** How far in from a corner the lids' movement is taken for the white's tips. */
		private const val TIP = 0.04f
	}
}
