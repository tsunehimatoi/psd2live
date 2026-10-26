package io.github.psd2live.core.fit

import kotlin.math.max
import kotlin.math.min

/**
 * The features a user can correct on the canvas, each with its handles: an eye's corners and the middles of its lid
 * edges, a brow's ends and arch, the mouth's corners and the middles of its lips. A handle moved by ([dx], [dy])
 * canvas pixels moves its feature's fit; the lid or lip edges bend with the handles at their middles and follow the
 * corners at their ends, and every pose of the feature is rebuilt from the moved fit.
 */
object FeatureHandles {
	private val EYE = listOf("inner", "outer", "upper", "lower")
	private val BROW = listOf("inner", "outer", "arch")
	private val MOUTH = listOf("left", "right", "upper", "lower")
	val features: Map<String, List<String>> = mapOf("eye.L" to EYE, "eye.R" to EYE, "brow.L" to BROW, "brow.R" to BROW, "mouth" to MOUTH)

	fun known(feature: String, handle: String): Boolean = features[feature]?.contains(handle) == true

	/**
	 * The second coordinates, at [n] evenly spaced places along the first, of the rig points [points] seen in a frame
	 * ([toFrame]): what a curve becomes once its points moved and its frame changed.
	 */
	internal fun resample(points: List<Pair<Float, Float>>, toFrame: (Float, Float) -> Pair<Float, Float>, n: Int): List<Float> {
		val framed = points.map { toFrame(it.first, it.second) }.sortedBy { it.first }
		return List(n) { k ->
			val u = k.toFloat() / (n - 1)
			val i = framed.indexOfFirst { it.first >= u }
			when {
				i <= 0 -> framed.first().second
				else -> {
					val a = framed[i - 1]; val b = framed[i]
					val t = ((u - a.first) / max(b.first - a.first, 1e-5f)).coerceIn(0f, 1f)
					a.second + (b.second - a.second) * t
				}
			}
		}
	}

	/** How much of a handle at the middle of an edge moves the edge at [u]: all of it at the middle, none at the ends. */
	internal fun middle(u: Float): Float = EyeRig.hump(min(max(u, 0f), 1f)).coerceAtLeast(0f)
}

/** A rig-space move of a feature's handle. */
typealias RigMove = Pair<Float, Float>

private operator fun RigMove.times(k: Float): RigMove = first * k to second * k
private operator fun RigMove.plus(o: RigMove): RigMove = first + o.first to second + o.second
private val NONE: RigMove = 0f to 0f

/** This eye with its handles ([moves]: inner, outer, upper, lower, as rig moves) moved. */
fun EyeFit.moved(moves: Map<String, RigMove>): EyeFit {
	if (moves.isEmpty()) return this
	val inner = moves["inner"] ?: NONE; val outer = moves["outer"] ?: NONE
	fun shifted(curve: List<Float>, peak: RigMove): List<Pair<Float, Float>> = curve.indices.map { k ->
		val u = EyeFit.u(k)
		val (x, y) = toCanvas(u, curve[k])
		val d = inner * (1f - u) + outer * u + peak * FeatureHandles.middle(u)
		(x + d.first) to (y + d.second)
	}
	val upperPoints = shifted(upper, moves["upper"] ?: NONE)
	val lowerPoints = shifted(lower, moves["lower"] ?: NONE)
	val closedPoints = closed?.let { shifted(it, NONE) }
	val (ox, oy) = upperPoints.first(); val (ex, ey) = upperPoints.last()
	val width = ((ex - ox) * axisX + (ey - oy) * axisY).coerceAtLeast(1f)
	val frame = copy(originX = ox, originY = oy, width = width)
	return frame.copy(upper = FeatureHandles.resample(upperPoints, frame::toEye, EyeFit.SAMPLES),
		lower = FeatureHandles.resample(lowerPoints, frame::toEye, EyeFit.SAMPLES),
		closed = closedPoints?.let { FeatureHandles.resample(it, frame::toEye, EyeFit.SAMPLES) })
}

/** The eye's handles where they sit, in the rig's space. */
fun EyeFit.handles(): Map<String, Pair<Float, Float>> = mapOf(
	"inner" to toCanvas(0f, 0f), "outer" to toCanvas(1f, cornerV),
	"upper" to toCanvas(0.5f, EyeFit.sample(upper, 0.5f)), "lower" to toCanvas(0.5f, EyeFit.sample(lower, 0.5f)))

/** This mouth with its handles ([moves]: left, right, upper, lower) moved. */
fun MouthFit.moved(moves: Map<String, RigMove>): MouthFit {
	if (moves.isEmpty()) return this
	val left = moves["left"] ?: NONE; val right = moves["right"] ?: NONE
	fun shifted(curve: List<Float>, peak: RigMove): List<Pair<Float, Float>> = curve.indices.map { k ->
		val u = k.toFloat() / (curve.size - 1)
		val (x, y) = toCanvas(u, curve[k])
		val d = left * (1f - u) + right * u + peak * FeatureHandles.middle(u)
		(x + d.first) to (y + d.second)
	}
	val upperPoints = shifted(upper, moves["upper"] ?: NONE)
	val lowerPoints = shifted(lower, moves["lower"] ?: NONE)
	val (ox, oy) = upperPoints.first(); val (ex, ey) = upperPoints.last()
	val width = ((ex - ox) * axisX + (ey - oy) * axisY).coerceAtLeast(1f)
	val frame = copy(originX = ox, originY = oy, width = width)
	return frame.copy(upper = FeatureHandles.resample(upperPoints, frame::toMouth, MouthFit.SAMPLES),
		lower = FeatureHandles.resample(lowerPoints, frame::toMouth, MouthFit.SAMPLES))
}

/** The mouth's handles where they sit, in the rig's space. */
fun MouthFit.handles(): Map<String, Pair<Float, Float>> = mapOf(
	"left" to toCanvas(0f, 0f), "right" to toCanvas(1f, cornerV),
	"upper" to toCanvas(0.5f, MouthFit.sample(upper, 0.5f)), "lower" to toCanvas(0.5f, MouthFit.sample(lower, 0.5f)))

/** This brow with its handles ([moves]: inner, outer, arch) moved. */
fun BrowFit.moved(moves: Map<String, RigMove>): BrowFit {
	if (moves.isEmpty()) return this
	val inner = moves["inner"] ?: NONE; val outer = moves["outer"] ?: NONE; val arch = moves["arch"] ?: NONE
	val points = centre.indices.map { k ->
		val u = EyeFit.u(k)
		val (x, y) = toRig(u, centre[k])
		val d = inner * (1f - u) + outer * u + arch * FeatureHandles.middle(u)
		(x + d.first) to (y + d.second)
	}
	val (ox, oy) = toRig(0f, 0f).let { (x, y) -> (x + inner.first) to (y + inner.second) }
	val (ex, ey) = toRig(1f, 0f).let { (x, y) -> (x + outer.first) to (y + outer.second) }
	val length = ((ex - ox) * axisX + (ey - oy) * axisY).coerceAtLeast(1f)
	val frame = copy(originX = ox, originY = oy, length = length)
	return frame.copy(centre = FeatureHandles.resample(points, frame::toBrow, EyeFit.SAMPLES))
}

/** The brow's handles where they sit, in the rig's space. */
fun BrowFit.handles(): Map<String, Pair<Float, Float>> = mapOf(
	"inner" to toRig(0f, EyeFit.sample(centre, 0f)), "outer" to toRig(1f, EyeFit.sample(centre, 1f)),
	"arch" to toRig(0.5f, EyeFit.sample(centre, 0.5f)))
