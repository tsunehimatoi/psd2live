package io.github.autolive2d.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A canvas coordinate system whose axes follow the authored head roll.
 *
 * Head deformers are authored in the aligned space and the head rotation deformer applies
 * [angleDegrees] to put them back into the PSD's canvas space.  Keeping the conversion in one
 * object prevents feature, hair, and drawable meshes from drifting into subtly different axes.
 */
internal data class HeadCoordinateSpace(
	val angleDegrees: Float,
	val pivotX: Float,
	val pivotY: Float,
) {
	private val radians = angleDegrees * PI.toFloat() / 180f
	private val cosine = cos(radians)
	private val sine = sin(radians)

	fun toAligned(canvasX: Float, canvasY: Float): Pair<Float, Float> {
		val dx = canvasX - pivotX
		val dy = canvasY - pivotY
		return (pivotX + cosine * dx + sine * dy) to (pivotY - sine * dx + cosine * dy)
	}

	fun toCanvas(alignedX: Float, alignedY: Float): Pair<Float, Float> {
		val dx = alignedX - pivotX
		val dy = alignedY - pivotY
		return (pivotX + cosine * dx - sine * dy) to (pivotY + sine * dx + cosine * dy)
	}

	fun boundsToAligned(bounds: Bounds): Bounds = transformedBounds(bounds, ::toAligned)

	fun boundsToCanvas(bounds: Bounds): Bounds = transformedBounds(bounds, ::toCanvas)

	private fun transformedBounds(
		bounds: Bounds,
		transform: (Float, Float) -> Pair<Float, Float>,
	): Bounds {
		val points = listOf(
			transform(bounds.left, bounds.top),
			transform(bounds.right, bounds.top),
			transform(bounds.right, bounds.bottom),
			transform(bounds.left, bounds.bottom),
		)
		return Bounds(
			points.minOf { it.first },
			points.minOf { it.second },
			points.maxOf { it.first },
			points.maxOf { it.second },
		)
	}

	companion object {
		val Identity = HeadCoordinateSpace(0f, 0f, 0f)
	}
}

/** Estimates authored head roll from stable facial baselines, with a vertical-axis fallback. */
internal object HeadOrientationEstimator {
	private data class Candidate(val degrees: Float, val weight: Float)

	private val horizontalFeatures = listOf(
		SemanticTag.EYEWHITE to 6f,
		SemanticTag.IRIDES to 5f,
		SemanticTag.EYELASH to 4f,
		SemanticTag.EYEBROW to 3f,
		SemanticTag.EYE_CLOSE to 2f,
		SemanticTag.EARS to 1f,
	)

	fun estimate(layers: List<ClassifiedLayer>, face: Bounds): Float {
		val visible = layers.filter { it.opaquePixels > 0 }
		val candidates = horizontalFeatures.mapNotNull { (tag, baseWeight) ->
			val tagged = visible.filter { it.semantic.tag == tag }
			val pair = pairedCenters(tagged) ?: return@mapNotNull null
			val left = pair.first
			val right = pair.second
			val dx = right.first - left.first
			val dy = right.second - left.second
			if (dx < face.width * 0.08f) return@mapNotNull null
			val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
			if (abs(degrees) > MAX_ROLL_DEGREES) return@mapNotNull null
			Candidate(degrees, baseWeight * (dx / face.width.coerceAtLeast(1f)).coerceIn(0.2f, 1f))
		}.toMutableList()

		// A central vertical axis is weaker under yaw, so only use it when no bilateral baseline exists.
		if (candidates.isEmpty()) {
			verticalFallback(visible, face)?.let { candidates += Candidate(it, 1f) }
		}
		if (candidates.isEmpty()) return 0f

		val median = weightedMedian(candidates)
		val inliers = candidates.filter { abs(it.degrees - median) <= MAX_BASELINE_DISAGREEMENT }
		val weight = inliers.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1e-4f)
		val estimated = inliers.sumOf { (it.degrees * it.weight).toDouble() }.toFloat() / weight
		return if (abs(estimated) < ROLL_DEAD_ZONE) 0f else estimated.coerceIn(-MAX_ROLL_DEGREES, MAX_ROLL_DEGREES)
	}

	private fun pairedCenters(layers: List<ClassifiedLayer>): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
		if (layers.size < 2) return null
		val sided = layers.filter { it.semantic.side != Side.NONE }.groupBy { it.semantic.side }
		val centers = when {
			sided.keys.containsAll(setOf(Side.LEFT, Side.RIGHT)) ->
				listOf(weightedCenter(sided.getValue(Side.LEFT)), weightedCenter(sided.getValue(Side.RIGHT)))
			sided.isEmpty() -> {
				val sorted = layers.sortedBy { it.centroidX }
				listOf(sorted.first().centroidX to sorted.first().centroidY, sorted.last().centroidX to sorted.last().centroidY)
			}
			// Several variants/components belonging to only one anatomical side are not an eye line.
			else -> return null
		}
		return centers.sortedBy { it.first }.let { it[0] to it[1] }
	}

	private fun weightedCenter(layers: List<ClassifiedLayer>): Pair<Float, Float> {
		val total = layers.sumOf { it.opaquePixels.toDouble() }.toFloat().coerceAtLeast(1f)
		return layers.sumOf { (it.centroidX * it.opaquePixels).toDouble() }.toFloat() / total to
			(layers.sumOf { (it.centroidY * it.opaquePixels).toDouble() }.toFloat() / total)
	}

	private fun verticalFallback(layers: List<ClassifiedLayer>, face: Bounds): Float? {
		val upper = layers.filter { it.semantic.tag in UPPER_FEATURES }.takeIf { it.isNotEmpty() }?.let(::weightedCenter)
		val lower = layers.filter { it.semantic.tag in LOWER_FEATURES }.takeIf { it.isNotEmpty() }?.let(::weightedCenter)
		if (upper == null || lower == null) return null
		val dx = lower.first - upper.first
		val dy = lower.second - upper.second
		if (dy < face.height * 0.08f) return null
		// A clockwise-rolled local +Y axis points toward canvas -X.
		return Math.toDegrees(atan2((-dx).toDouble(), dy.toDouble())).toFloat()
			.takeIf { abs(it) <= MAX_ROLL_DEGREES }
	}

	private fun weightedMedian(candidates: List<Candidate>): Float {
		val sorted = candidates.sortedBy { it.degrees }
		val halfway = sorted.sumOf { it.weight.toDouble() }.toFloat() * 0.5f
		var accumulated = 0f
		for (candidate in sorted) {
			accumulated += candidate.weight
			if (accumulated >= halfway) return candidate.degrees
		}
		return sorted.last().degrees
	}

	private val UPPER_FEATURES = setOf(
		SemanticTag.EYEWHITE,
		SemanticTag.IRIDES,
		SemanticTag.EYELASH,
		SemanticTag.EYE_CLOSE,
		SemanticTag.EYEBROW,
	)
	private val LOWER_FEATURES = CharacterAnalyzer.MOUTH_TAGS + SemanticTag.NOSE
	private const val MAX_ROLL_DEGREES = 45f
	private const val MAX_BASELINE_DISAGREEMENT = 12f
	private const val ROLL_DEAD_ZONE = 0.25f
}
