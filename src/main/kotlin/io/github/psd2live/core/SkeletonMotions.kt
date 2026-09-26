package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A parameter id and the (time, value) points a motion drives it through, linear between points. */
typealias MotionTrack = Pair<String, List<Pair<Float, Float>>>

/**
 * Motions written against the bones, not against one deformer wrapping the whole character.
 *
 * What the skeleton changes is the shape of these curves rather than their size: each limb rides its own
 * parameter on its own phase, so the arms drift out of step with the torso and with each other, and a
 * joint trails the one it hangs from. Nothing here writes geometry - the rig's keyforms already say what
 * a parameter does to a joint - so preview and export describe the same motion.
 */
object SkeletonMotions {
	/** One loop of the idle. Six seconds is long enough that the phases do not read as a pattern. */
	const val IDLE_DURATION = 6f
	const val TAIL_SWING_DURATION = 3f
	const val CROUCH_DURATION = 1.8f
	const val WEIGHT_SHIFT_DURATION = 3f

	/** Samples per cycle of a sampled sine; Cubism interpolates linearly between the points a motion carries. */
	private const val SAMPLES_PER_CYCLE = 16

	/** Radians a joint trails the one it hangs from. Roughly a quarter cycle reads as follow-through. */
	private const val SEGMENT_LAG = 0.7f

	private val bodyAngleZ = StandardParameters.BODY_Z.raw

	/** Whether [spec] bakes the leg poses, which is what the weight and crouch motions drive. */
	fun hasLegPoses(spec: SkeletonSpec?): Boolean = spec?.enabled == true && SkeletonRig.legs(spec).isNotEmpty()

	fun hasTail(spec: SkeletonSpec?): Boolean = spec?.enabled == true && SkeletonRig.limbBones(spec).any { it.role == BoneRole.TAIL }

	/**
	 * The resting idle: the limbs drift, the weight settles from one foot to the other and the knees give
	 * a little as the chest falls. Parameters in [exclude] are left to whatever else drives them - physics,
	 * typically - so the two do not fight.
	 */
	fun idle(spec: SkeletonSpec?, exclude: Set<String> = emptySet()): List<MotionTrack> {
		if (spec?.enabled != true) return emptyList()
		val tracks = buildList {
			if (hasLegPoses(spec)) {
				add(sine(SkeletonRig.weightId.raw, amplitude = 0.55f, cycles = 1, phase = 0f))
				add(sine(bodyAngleZ, amplitude = 1.2f, cycles = 1, phase = 0.9f))
				// Opposed to the breath, so the knees give as the chest falls rather than as it rises.
				add(sine(SkeletonRig.crouchId.raw, amplitude = 0.12f, cycles = 2, phase = PI.toFloat(), bias = 0.12f))
			}
			addAll(drift(spec, setOf(BoneRole.UPPER_ARM, BoneRole.FOREARM, BoneRole.HAND), amplitude = 4f, cycles = 1))
			addAll(drift(spec, setOf(BoneRole.THIGH, BoneRole.SHIN, BoneRole.FOOT), amplitude = 1f, cycles = 1))
			addAll(drift(spec, setOf(BoneRole.WING), amplitude = 6f, cycles = 2))
			addAll(tail(spec, amplitude = 8f, cycles = 2, duration = IDLE_DURATION))
			addAll(drift(spec, setOf(BoneRole.CUSTOM), amplitude = 3f, cycles = 1))
		}
		return tracks.filter { it.first !in exclude }.clamped(spec)
	}

	/** A deliberate tail swing, faster and wider than the one folded into the idle. */
	fun tailSwing(spec: SkeletonSpec?): List<MotionTrack> =
		if (!hasTail(spec)) emptyList() else tail(spec!!, amplitude = 22f, cycles = 3, duration = TAIL_SWING_DURATION).clamped(spec)

	/** A dip and a recovery: the knees give and the arms swing out a beat behind. */
	fun crouch(spec: SkeletonSpec?): List<MotionTrack> {
		if (!hasLegPoses(spec)) return emptyList()
		return buildList {
			add(SkeletonRig.crouchId.raw to listOf(0f to 0f, 0.55f to 1f, 1.1f to 0.85f, 1.8f to 0f))
			add(StandardParameters.BREATH.raw to listOf(0f to 0.5f, 0.55f to 0f, 1.8f to 0.5f))
			for (bone in spec!!.bones.filter { it.role == BoneRole.UPPER_ARM }) {
				add(bone.parameterId to listOf(0f to 0f, 0.7f to 8f, 1.25f to 5f, 1.8f to 0f))
			}
		}.clamped(spec!!)
	}

	/** A shift onto one foot and back, the hips leading and the torso and arms following. */
	fun weightShift(spec: SkeletonSpec?): List<MotionTrack> {
		if (!hasLegPoses(spec)) return emptyList()
		return buildList {
			add(SkeletonRig.weightId.raw to listOf(0f to 0f, 0.9f to 1f, 2.1f to 1f, 3f to 0f))
			add(bodyAngleZ to listOf(0f to 0f, 0.9f to -2.5f, 2.1f to -2.5f, 3f to 0f))
			// The arms hang from the chest, so they trail the hips rather than leading them.
			for (bone in spec!!.bones.filter { it.role == BoneRole.UPPER_ARM }) {
				val sign = if (bone.direction > 0f) 1f else -1f
				add(bone.parameterId to listOf(0f to 0f, 1.1f to 5f * sign, 2.2f to 5f * sign, 3f to 0f))
			}
		}.clamped(spec!!)
	}

	/**
	 * The values the idle holds at [elapsed] seconds, for every parameter it drives except breath, which
	 * the preview already runs on its own curve. The preview evaluates the same tracks the export writes.
	 */
	fun liveIdle(spec: SkeletonSpec?, elapsed: Double): Map<ParameterId, Float> =
		idle(spec).filterNot { it.first == StandardParameters.BREATH.raw }
			.associate { (id, points) -> ParameterId(id) to sample(points, elapsed, loop = true) }

	/** The values a one-shot motion holds [elapsed] seconds in; null once it has finished. */
	fun oneShot(tracks: List<MotionTrack>, elapsed: Double): Map<ParameterId, Float>? {
		val duration = tracks.maxOfOrNull { it.second.last().first } ?: return null
		if (elapsed > duration) return null
		return tracks.associate { (id, points) -> ParameterId(id) to sample(points, elapsed, loop = false) }
	}

	/**
	 * Each joint of the chains in [roles] on its own phase, with the two sides opposed. Distal joints lag
	 * the ones they hang from and travel less, which reads as an arm following a shoulder rather than a
	 * whip cracking.
	 */
	private fun drift(spec: SkeletonSpec, roles: Set<BoneRole>, amplitude: Float, cycles: Int): List<MotionTrack> =
		SkeletonRig.limbBones(spec).filter { it.role in roles }.distinctBy { it.parameterId }.map { bone ->
			val segment = depth(spec, bone)
			val sidePhase = if (bone.side == Side.RIGHT) PI.toFloat() else 0f
			sine(bone.parameterId, amplitude = amplitude * decay(segment), cycles = cycles, phase = sidePhase + segment * SEGMENT_LAG)
		}

	/** A tail swings as one wave travelling outward, so the lag matters more here than the side does. */
	private fun tail(spec: SkeletonSpec, amplitude: Float, cycles: Int, duration: Float): List<MotionTrack> =
		SkeletonRig.limbBones(spec).filter { it.role == BoneRole.TAIL }.distinctBy { it.parameterId }.map { bone ->
			val segment = depth(spec, bone)
			sine(bone.parameterId, amplitude = amplitude * decay(segment) * (1f + segment * 0.25f), cycles = cycles,
				phase = segment * SEGMENT_LAG * 1.6f, duration = duration)
		}

	/** How many limb bones [bone] hangs below. */
	private fun depth(spec: SkeletonSpec, bone: SkeletonBone): Int {
		val ids = SkeletonRig.limbBones(spec).mapTo(HashSet()) { it.id }
		return generateSequence(SkeletonRig.limbParent(spec, bone, ids)) { SkeletonRig.limbParent(spec, it, ids) }.count()
	}

	/** How much of its chain's amplitude a joint [segment] deep keeps. */
	private fun decay(segment: Int): Float = 1f / (1f + segment * 0.45f)

	/** Every point kept inside the limits of the bone it drives. */
	private fun List<MotionTrack>.clamped(spec: SkeletonSpec): List<MotionTrack> {
		val limits = spec.bones.groupBy { it.parameterId }.mapValues { (_, bones) ->
			bones.minOf { it.minAngle } to bones.maxOf { it.maxAngle }
		}
		return map { (id, points) ->
			val range = limits[id] ?: return@map id to points
			id to points.map { (time, value) -> time to value.coerceIn(range.first, range.second) }
		}
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
