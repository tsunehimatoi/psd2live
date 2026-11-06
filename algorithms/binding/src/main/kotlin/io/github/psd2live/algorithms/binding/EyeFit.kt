package io.github.psd2live.algorithms.binding

import kotlin.math.*

/**
 * One eye's feature points read off the art, in the rig's aligned space: the inner and outer corners and the upper and
 * lower lid edges sampled at [SAMPLES] places from corner to corner, plus the drawn closed-eye line when the art has
 * one and the iris box.
 *
 * Everything is kept in the eye's own frame, set by the head rather than by the eye: the first axis ([axisX], [axisY])
 * runs along the head's level - the line through both eyes - from the inner corner toward the outer one, the second
 * points down the face, a unit is the corners' distance along the first axis ([width]), and the inner corner is the
 * origin. A head drawn tilted, or turned so the eyes sit askew, keeps its lids closing down its own face. The outer
 * corner may sit above or below the inner one ([cornerV]): the corners' line need not be level with the head.
 * [upper], [lower] and [closed] are the second coordinates of the lid edges at u = k / (SAMPLES - 1); [closed] is null
 * when no closed line is drawn.
 */
data class EyeFit(
	val side: Side,
	val originX: Float, val originY: Float,
	val axisX: Float, val axisY: Float,
	val width: Float,
	val upper: List<Float>,
	val lower: List<Float>,
	val closed: List<Float>?,
	val iris: Bounds?,
	/** Observed material components and stroke paths; absent on a geometry-only fit. */
	val artwork: EyeArtwork? = null,
	/** Opening material selected for this fit; other drawings retain their own association. */
	val openingLayers: Set<String> = emptySet(),
	/** Authored displacement of the upper feature path, expressed in this eye frame. */
	val upperCorrection: List<Float>? = null,
) {
	/** The frame's second axis, perpendicular to the head's level and pointing down the face. */
	private val downX: Float get() = if (axisX >= 0f) -axisY else axisY
	private val downY: Float get() = if (axisX >= 0f) axisX else -axisX

	/** Where the outer corner sits on the second axis: 0 when it is level with the inner corner. */
	val cornerV: Float get() = upper.last()

	/** The corners' line at [u]: where the lid edges meet at both ends, and what the lids' arcs rise from. */
	fun baseline(u: Float): Float = cornerV * u

	fun toCanvas(u: Float, v: Float): Pair<Float, Float> =
		(originX + (u * axisX + v * downX) * width) to (originY + (u * axisY + v * downY) * width)

	fun toEye(x: Float, y: Float): Pair<Float, Float> {
		val dx = (x - originX) / width; val dy = (y - originY) / width
		return (dx * axisX + dy * axisY) to (dx * downX + dy * downY)
	}

	/** A canvas displacement of the eye-frame displacement ([du], [dv]). */
	fun canvasDelta(du: Float, dv: Float): Pair<Float, Float> = ((du * axisX + dv * downX) * width) to ((du * axisY + dv * downY) * width)

	companion object {
		const val SAMPLES = 17

		/** Sample [k]'s place from corner to corner. */
		fun u(k: Int) = k.toFloat() / (SAMPLES - 1)

		/** [curve] at [u], linear between samples, held at its ends outside 0..1. */
		fun sample(curve: List<Float>, u: Float): Float {
			val f = (u.coerceIn(0f, 1f) * (SAMPLES - 1))
			val i = f.toInt().coerceAtMost(SAMPLES - 2)
			return curve[i] + (curve[i + 1] - curve[i]) * (f - i)
		}
	}
}

/**
 * The eyelid as a plane turning about the corners' line over a round eyeball (the pseudo-3D lid): where the plane
 * cuts the ball is the lid's edge, so as the lid closes its edge stays an arc that sinks over the ball, flattens only as
 * it passes the corners and turns into the downward arc of a closed eye - never the flattened lens a straight blend
 * of the open and closed shapes passes through. The ball has radius [radius] (of the eye's width) about (0.5,
 * [centreY], 0); the corners are on its surface.
 */
class EyelidModel(private val radius: Float = 0.68f, private val centreY: Float = -0.18f) {
	private val cornerDepth = sqrt((radius * radius - 0.25f - centreY * centreY).coerceAtLeast(1e-4f))

	/** The lid edge's height at [u] (the eye frame's second coordinate, negative above the corners) with the plane at [angle] radians. */
	fun edge(u: Float, angle: Float): Float {
		val b = 2f * (centreY * cos(angle) + cornerDepth * sin(angle))
		val c = (u - 0.5f) * (u - 0.5f) - 0.25f
		val root = sqrt((b * b - 4f * c).coerceAtLeast(0f))
		// The front of the two cuts: the larger s faces the viewer while the plane leans toward them.
		val s = if (sin(angle) >= 0f) (-b + root) / 2f else (-b - root) / 2f
		return -s * cos(angle)
	}

	/** The angle whose edge reaches [height] at the eye's middle, between [from] and [to] radians (bisection). */
	fun angleFor(height: Float, from: Float = 0.05f, to: Float = 2.6f): Float {
		var lo = from; var hi = to
		val rising = edge(0.5f, lo) < edge(0.5f, hi)
		repeat(48) {
			val mid = (lo + hi) / 2f
			if ((edge(0.5f, mid) < height) == rising) lo = mid else hi = mid
		}
		return (lo + hi) / 2f
	}
}
