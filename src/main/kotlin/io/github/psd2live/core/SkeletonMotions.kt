package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A parameter id and the (time, value) points a motion drives it through, linear between points. */
typealias MotionTrack = Pair<String, List<Pair<Float, Float>>>

/**
 * Motions written against the skeleton's poses, not against its bones.
 *
 * Every preset is its pose parameter moving (see [SkeletonPoses]): the rig already says what a crouch
 * or a tail swing does to each joint and each mesh, so a motion only says when. Nothing here writes
 * geometry or a bone angle, so preview and export describe the same motion, and a bone's own parameter
 * stays free for the hand, the pose tool or physics.
 */
object SkeletonMotions {
	/** One loop of the idle. Six seconds is long enough that the phases do not read as a pattern. */
	const val IDLE_DURATION = 6f
	const val TAIL_SWING_DURATION = 3f
	const val CROUCH_DURATION = 1.8f
	const val WEIGHT_SHIFT_DURATION = 3f

	/** Samples per cycle of a sampled sine; Cubism interpolates linearly between the points a motion carries. */
	private const val SAMPLES_PER_CYCLE = 16

	/**
	 * The resting idle: the weight settles from one foot to the other and the arms, tail and wings drift
	 * each on their own phase. A pose whose bones are all in [exclude] - driven by physics, typically - is
	 * left out so the two do not fight.
	 *
	 * The two leg poses never play together: each is solved with the other at rest, and their shapes only
	 * add, so a crouch on a shifted weight would bend the knees too far and slide the feet. The idle keeps
	 * to the weight, and each leg one-shot holds the other leg pose at rest while it plays.
	 */
	fun idle(spec: SkeletonSpec?, exclude: Set<String> = emptySet()): List<MotionTrack> {
		val poses = SkeletonPoses.available(spec).filterNot { pose ->
			SkeletonPoses.drivenParameters(spec!!, pose).let { it.isNotEmpty() && exclude.containsAll(it) }
		}
		return poses.mapNotNull { pose ->
			val id = pose.id.raw
			when (pose) {
				SkeletonPoses.weight -> sine(id, amplitude = 0.55f, cycles = 1, phase = 0f)
				SkeletonPoses.armSway -> sine(id, amplitude = 1f, cycles = 1, phase = 0.9f)
				SkeletonPoses.tailSwing -> sine(id, amplitude = 0.36f, cycles = 2, phase = 0.4f)
				SkeletonPoses.wingFlap -> sine(id, amplitude = 1f, cycles = 2, phase = 1.7f)
				else -> null
			}
		}.clamped()
	}

	/** A deliberate tail swing, faster and wider than the one folded into the idle. */
	fun tailSwing(spec: SkeletonSpec?): List<MotionTrack> =
		if (SkeletonPoses.tailSwing !in SkeletonPoses.available(spec)) emptyList()
		else listOf(sine(SkeletonPoses.tailSwing.id.raw, amplitude = 1f, cycles = 3, phase = 0f, duration = TAIL_SWING_DURATION))

	/** A dip and a recovery: the knees give and the arms swing out. */
	fun crouch(spec: SkeletonSpec?): List<MotionTrack> =
		if (SkeletonPoses.crouch !in SkeletonPoses.available(spec)) emptyList()
		else listOf(
			SkeletonPoses.crouch.id.raw to listOf(0f to 0f, 0.55f to 1f, 1.1f to 0.85f, CROUCH_DURATION to 0f),
			SkeletonPoses.weight.id.raw to listOf(0f to 0f, CROUCH_DURATION to 0f),
		)

	/** A shift onto one foot and back, the hips leading and the torso and arms following. */
	fun weightShift(spec: SkeletonSpec?): List<MotionTrack> =
		if (SkeletonPoses.weight !in SkeletonPoses.available(spec)) emptyList()
		else listOf(
			SkeletonPoses.weight.id.raw to listOf(0f to 0f, 0.9f to 1f, 2.1f to 1f, WEIGHT_SHIFT_DURATION to 0f),
			SkeletonPoses.crouch.id.raw to listOf(0f to 0f, WEIGHT_SHIFT_DURATION to 0f),
		)

	/** The values the idle holds at [elapsed] seconds. The preview evaluates the same tracks the export writes. */
	fun liveIdle(spec: SkeletonSpec?, elapsed: Double): Map<ParameterId, Float> =
		idle(spec).associate { (id, points) -> ParameterId(id) to sample(points, elapsed, loop = true) }

	/** The values a one-shot motion holds [elapsed] seconds in; null once it has finished. */
	fun oneShot(tracks: List<MotionTrack>, elapsed: Double): Map<ParameterId, Float>? {
		val duration = tracks.maxOfOrNull { it.second.last().first } ?: return null
		if (elapsed > duration) return null
		return tracks.associate { (id, points) -> ParameterId(id) to sample(points, elapsed, loop = false) }
	}

	/** Every point kept inside the range of the pose it drives. */
	private fun List<MotionTrack>.clamped(): List<MotionTrack> = map { (id, points) ->
		val pose = SkeletonPoses.all.firstOrNull { it.id.raw == id } ?: return@map id to points
		id to points.map { (time, value) -> time to value.coerceIn(pose.min, pose.max) }
	}

	/**
	 * A sine sampled into linear points whose loop closes exactly: [cycles] whole cycles across [duration]
	 * means the last point repeats the first, so a looping motion has no seam.
	 */
	private fun sine(
		parameterId: String,
		amplitude: Float,
		cycles: Int,
		phase: Float,
		bias: Float = 0f,
		duration: Float = IDLE_DURATION,
	): MotionTrack {
		val samples = (SAMPLES_PER_CYCLE * cycles).coerceAtLeast(4)
		val points = (0..samples).map { index ->
			val time = duration * index / samples
			val angle = 2.0 * PI * cycles * index / samples + phase
			time to (bias + amplitude * sin(angle).toFloat())
		}
		return parameterId to points
	}

	/** The value [points] holds at [elapsed] seconds, interpolating linearly and optionally looping. */
	internal fun sample(points: List<Pair<Float, Float>>, elapsed: Double, loop: Boolean): Float {
		if (points.isEmpty()) return 0f
		if (points.size == 1) return points.single().second
		val duration = points.last().first
		if (duration <= 1e-6f) return points.first().second
		val time = if (loop) (elapsed % duration).toFloat().let { if (it < 0f) it + duration else it }
		else elapsed.toFloat().coerceIn(0f, duration)
		val next = points.indexOfFirst { it.first >= time }.takeIf { it > 0 } ?: return points.first().second
		val (startTime, startValue) = points[next - 1]
		val (endTime, endValue) = points[next]
		val span = endTime - startTime
		if (abs(span) < 1e-6f) return endValue
		return startValue + (endValue - startValue) * ((time - startTime) / span)
	}
}
