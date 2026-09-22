package io.github.psd2live.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A parameter id and the (time, value) points a motion drives it through. */
typealias MotionTrack = Pair<String, List<Pair<Float, Float>>>

/**
 * Idle behaviour written against the bones, not against one deformer wrapping the whole character.
 *
 * What the skeleton changes is the shape of these curves rather than their size: each limb rides its
 * own parameter on its own phase, so the arms drift out of step with the torso and with each other. A
 * single body warp could only scale one motion up and down, which is why its idle read as the whole
 * figure wobbling in one piece.
 *
 * Nothing here writes geometry. The bones' keyforms already say what a parameter does to a joint, so
 * a joint tuned by hand keeps its tuning no matter which motion plays.
 */
object SkeletonMotions {
	/** One loop of the idle. Six seconds is long enough that the phases do not read as a pattern. */
	const val IDLE_DURATION = 6f

	/**
	 * Samples per cycle of a sampled sine. Cubism interpolates linearly between the points a motion
	 * carries, so this is the only knob between a curve that reads as smooth and one that ticks.
	 */
	private const val SAMPLES_PER_CYCLE = 16

	/** Radians a joint trails the one it hangs from. Roughly a quarter cycle reads as follow-through. */
	private const val SEGMENT_LAG = 0.7f

	/**
	 * The resting idle: the torso breathes, the limbs drift, the weight settles from one foot to the
	 * other and the knees give a little as the chest falls.
	 */
	fun idle(skeleton: Skeleton): List<MotionTrack> = buildList {
		// Breathing stays on the torso's own warp: it is a volume change, not a joint.
		add(StandardParameters.BREATH.raw to listOf(0f to 0f, 1.5f to 1f, 3f to 0f, 4.5f to 1f, 6f to 0f))

		driven(skeleton, SkeletonParameters.WEIGHT)?.let {
			add(sine(it, amplitude = 0.55f, cycles = 1, phase = 0f))
		}
		driven(skeleton, SkeletonParameters.LEAN)?.let {
			add(sine(it, amplitude = 0.4f, cycles = 1, phase = 0.9f))
		}
		// Opposed to the breath, so the knees give as the chest falls rather than as it rises.
		driven(skeleton, SkeletonParameters.CROUCH)?.let {
			add(sine(it, amplitude = 0.22f, cycles = 2, phase = PI.toFloat(), bias = 0.22f))
		}

		addAll(limbDrift(skeleton, BoneChain.ARM, amplitude = 0.6f, cycles = 1))
		addAll(limbDrift(skeleton, BoneChain.LEG, amplitude = 0.22f, cycles = 1))
		addAll(limbDrift(skeleton, BoneChain.WING, amplitude = 0.5f, cycles = 2))
		addAll(tailDrift(skeleton, amplitude = 0.45f, cycles = 2))
	}

	/** A deliberate tail swing, faster and wider than the one folded into the idle. */
	fun tailSwing(skeleton: Skeleton): List<MotionTrack> =
		tailDrift(skeleton, amplitude = 1f, cycles = 3, duration = 3f)

	/** A dip and a recovery: the knees give, and the chest tips forward a beat behind them. */
	fun crouch(skeleton: Skeleton): List<MotionTrack> = buildList {
		val crouch = driven(skeleton, SkeletonParameters.CROUCH) ?: return@buildList
		add(crouch to listOf(0f to 0f, 0.55f to 1f, 1.1f to 0.85f, 1.8f to 0f))
		driven(skeleton, SkeletonParameters.LEAN)?.let {
			add(it to listOf(0f to 0f, 0.55f to -0.45f, 1.1f to 0.15f, 1.8f to 0f))
		}
		add(StandardParameters.BREATH.raw to listOf(0f to 0.5f, 0.55f to 0f, 1.8f to 0.5f))
	}

	/** A shift onto one foot and back, which is the whole point of having a hip joint at all. */
	fun weightShift(skeleton: Skeleton): List<MotionTrack> = buildList {
		val weight = driven(skeleton, SkeletonParameters.WEIGHT) ?: return@buildList
		add(weight to listOf(0f to 0f, 0.9f to 1f, 2.1f to 1f, 3f to 0f))
		driven(skeleton, SkeletonParameters.LEAN)?.let {
			add(it to listOf(0f to 0f, 0.9f to -0.5f, 2.1f to -0.5f, 3f to 0f))
		}
		// The arms hang from the chest, so they trail the hips rather than leading them.
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val drive = skeleton.chain(BoneChain.ARM, side).firstNotNullOfOrNull { it.drive } ?: continue
			val sign = if (side == Side.LEFT) 1f else -1f
			add(drive.parameterId to listOf(0f to 0f, 1.1f to 0.45f * sign, 2.2f to 0.45f * sign, 3f to 0f))
		}
	}

	/**
	 * The values the idle holds at [elapsed] seconds, for every bone parameter it drives.
	 *
	 * The editor's preview computes parameters per frame rather than replaying a motion file, so this
	 * evaluates the same tracks continuously instead of handing back the sampled points. Preview and
	 * exported idle then describe one motion rather than two that drifted apart. Breath is left out
	 * because the preview already drives it on its own smooth curve.
	 */
	fun liveIdle(skeleton: Skeleton, elapsed: Double): Map<String, Float> {
		if (skeleton.isEmpty) return emptyMap()
		return idle(skeleton)
			.filterNot { (parameterId, _) -> parameterId == StandardParameters.BREATH.raw }
			.associate { (parameterId, points) -> parameterId to sample(points, elapsed) }
	}

	/**
	 * Each joint of a limb on its own phase, with the two sides opposed.
	 *
	 * Distal joints lag the ones they hang from and travel less, which is what makes a chain read as an
	 * arm following a shoulder rather than a whip cracking.
	 */
	private fun limbDrift(
		skeleton: Skeleton,
		chain: BoneChain,
		amplitude: Float,
		cycles: Int,
	): List<MotionTrack> = listOf(Side.LEFT, Side.RIGHT).flatMap { side ->
		val basePhase = if (side == Side.LEFT) 0f else PI.toFloat()
		skeleton.chain(chain, side).mapNotNull { bone ->
			val parameterId = bone.drive?.parameterId ?: return@mapNotNull null
			sine(
				parameterId = parameterId,
				amplitude = amplitude * decay(bone.segment),
				cycles = cycles,
				phase = basePhase + bone.segment * SEGMENT_LAG,
			)
		}
	}

	/** A tail swings as one wave travelling outward, so the lag matters more here than the side does. */
	private fun tailDrift(
		skeleton: Skeleton,
		amplitude: Float,
		cycles: Int,
		duration: Float = IDLE_DURATION,
	): List<MotionTrack> = skeleton.chain(BoneChain.TAIL, Side.NONE).mapNotNull { bone ->
		val parameterId = bone.drive?.parameterId ?: return@mapNotNull null
		sine(
			parameterId = parameterId,
			amplitude = amplitude * decay(bone.segment),
			cycles = cycles,
			phase = bone.segment * SEGMENT_LAG * 1.6f,
			duration = duration,
		)
	}

	/** [parameterId], but only when some bone of [skeleton] actually drives it. */
	private fun driven(skeleton: Skeleton, parameterId: String): String? =
		parameterId.takeIf { id -> skeleton.bones.any { it.drive?.parameterId == id } }

	/** How much of its chain's amplitude a joint [segment] deep keeps. */
	private fun decay(segment: Int): Float = 1f / (1f + segment * 0.45f)

	/**
	 * A sine sampled into linear points whose loop closes exactly: [cycles] whole cycles across
	 * [duration] means the last point repeats the first, so a looping motion has no seam.
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

	/** The value [points] holds at [elapsed] seconds, looping and interpolating linearly. */
	private fun sample(points: List<Pair<Float, Float>>, elapsed: Double): Float {
		if (points.isEmpty()) return 0f
		if (points.size == 1) return points.single().second
		val duration = points.last().first
		if (duration <= 1e-6f) return points.first().second
		val time = (elapsed % duration).toFloat().let { if (it < 0f) it + duration else it }
		val next = points.indexOfFirst { it.first >= time }.takeIf { it > 0 } ?: return points.first().second
		val (startTime, startValue) = points[next - 1]
		val (endTime, endValue) = points[next]
		val span = endTime - startTime
		if (abs(span) < 1e-6f) return endValue
		return startValue + (endValue - startValue) * ((time - startTime) / span)
	}
}
