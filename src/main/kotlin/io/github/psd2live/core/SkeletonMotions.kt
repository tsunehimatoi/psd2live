package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A parameter id and the (time, value) points a motion drives it through, linear between points. */
typealias MotionTrack = Pair<String, List<Pair<Float, Float>>>

/**
 * The body motions: the idle, and the one-shots written against the skeleton's poses.
 *
 * Every skeleton preset is its pose parameter moving (see [SkeletonPoses]): the rig already says what a
 * crouch or a tail swing does to each joint and each mesh, so a motion only says when. Besides the body
 * parameters the idle only drives the upper body's sway; nothing writes geometry, so preview and export
 * describe the same motion, and a limb's own parameter stays free for the hand, the pose tool or physics.
 */
object SkeletonMotions {
	/** One loop of the idle. Six seconds is long enough that the phases do not read as a pattern. */
	const val IDLE_DURATION = 6f
	const val TAIL_SWING_DURATION = 3f
	const val CROUCH_DURATION = 1.8f
	const val WEIGHT_SHIFT_DURATION = 3f
	const val SHY_DURATION = 3f
	const val WAVE_DURATION = 2.4f
	const val HEAD_TILT_DURATION = 2.2f
	const val CHEER_DURATION = 1.8f
	const val LEG_KICK_DURATION = 2.6f
	const val SWAY_DURATION = 3f

	/**
	 * A preset written against the skeleton's poses. A [loop] preset is another idle: the export puts it in
	 * the idle group beside the plain one, and the preview plays one cycle of it.
	 */
	class Preset(val name: String, val loop: Boolean = false, val tracks: (SkeletonSpec?) -> List<MotionTrack>)

	/** Every skeleton preset, in the order the panel lists and the export writes them. */
	val presets: List<Preset> = listOf(
		Preset("TailSwing", tracks = ::tailSwing),
		Preset("Crouch", tracks = ::crouch),
		Preset("WeightShift", tracks = ::weightShift),
		Preset("Shy", tracks = ::shy),
		Preset("Wave", tracks = ::wave),
		Preset("HeadTilt", tracks = ::headTilt),
		Preset("Cheer", tracks = ::cheer),
		Preset("LegKick", tracks = ::legKick),
		Preset("Sway", tracks = ::sway),
		Preset("IdleCute", loop = true) { idleCute(it) },
	)

	/** Samples per cycle of a sampled sine; Cubism interpolates linearly between the points a motion carries. */
	private const val SAMPLES_PER_CYCLE = 24

	/**
	 * The resting idle: the whole body on two clocks, a weight cycle and a breath twice as fast, every part
	 * following the one that drives it a little late, the way weight travels up a standing body.
	 *
	 * - The hips lead the weight cycle from one foot to the other. The upper body leans back over them,
	 *   the body turns and bends after it, the head tilts against the lean to keep the eyes level and
	 *   turns last, and the arms, tail and wings trail the part they hang from.
	 * - The breath lifts the chest, and the body and then the head nod a little after it.
	 *
	 * The body tracks hold without a skeleton too. A pose whose bones are all in [exclude] - driven by
	 * physics, typically - is left out so the two do not fight.
	 *
	 * The two leg poses never play together: each is solved with the other at rest, and their shapes only
	 * add, so a crouch on a shifted weight would bend the knees too far and slide the feet. The idle keeps
	 * to the weight, and each leg one-shot holds the other leg pose at rest while it plays.
	 */
	fun idle(spec: SkeletonSpec?, exclude: Set<String> = emptySet()): List<MotionTrack> {
		// Lags are fractions of a cycle behind the part that drives the track.
		fun weightCycle(id: String, amplitude: Float, lag: Float) = sine(id, amplitude, cycles = 1, phase = -lag * TAU)
		fun breathCycle(id: String, amplitude: Float, lag: Float, bias: Float = 0f) =
			sine(id, amplitude, cycles = 2, phase = -HALF_PI - lag * TAU, bias = bias)
		val body = listOf(
			breathCycle(StandardParameters.BREATH.raw, amplitude = 0.5f, lag = 0f, bias = 0.5f),
			breathCycle(StandardParameters.BODY_Y.raw, amplitude = 0.8f, lag = 0.06f),
			breathCycle(StandardParameters.ANGLE_Y.raw, amplitude = 1.5f, lag = 0.14f),
			weightCycle(StandardParameters.BODY_X.raw, amplitude = 2f, lag = 0.08f),
			weightCycle(StandardParameters.BODY_Z.raw, amplitude = -1.5f, lag = 0.12f),
			weightCycle(StandardParameters.ANGLE_Z.raw, amplitude = 2.5f, lag = 0.2f),
			weightCycle(StandardParameters.ANGLE_X.raw, amplitude = 3f, lag = 0.26f),
		)
		if (spec?.enabled != true) return body
		val poses = SkeletonPoses.available(spec).filterNot { pose ->
			SkeletonPoses.drivenParameters(spec, pose).let { it.isNotEmpty() && exclude.containsAll(it) }
		}
		val posed = poses.mapNotNull { pose ->
			val id = pose.id.raw
			when (pose) {
				SkeletonPoses.weight -> weightCycle(id, amplitude = 0.5f, lag = 0f)
				SkeletonPoses.armSway -> weightCycle(id, amplitude = 0.8f, lag = 0.22f)
				SkeletonPoses.tailSwing -> sine(id, amplitude = 0.4f, cycles = 2, phase = -0.3f * TAU)
				SkeletonPoses.wingFlap -> breathCycle(id, amplitude = 0.8f, lag = 0.1f)
				else -> null
			}
		}
		// On top of the counter-lean the weight pose already bakes, the upper body sways a little late.
		val sway = SkeletonRig.limbBones(spec).filter { it.role == BoneRole.UPPER_BODY && it.parameterId !in exclude }.map { bone ->
			weightCycle(bone.parameterId, amplitude = (UPPER_BODY_SWAY * bone.direction).coerceIn(bone.minAngle, bone.maxAngle), lag = 0.12f)
		}
		return (body + posed.clamped() + sway).distinctBy { it.first }
	}

	/** Degrees the upper body sways with the weight in the idle, leaning back over the hips. */
	private const val UPPER_BODY_SWAY = 1.5f

	private const val TAU = (2.0 * PI).toFloat()
	private const val HALF_PI = (PI / 2.0).toFloat()

	/** A deliberate tail swing, faster and wider than the one folded into the idle. */
	fun tailSwing(spec: SkeletonSpec?): List<MotionTrack> =
		if (SkeletonPoses.tailSwing !in SkeletonPoses.available(spec)) emptyList()
		else listOf(sine(SkeletonPoses.tailSwing.id.raw, amplitude = 1f, cycles = 3, phase = 0f, duration = TAIL_SWING_DURATION))

	/** A dip and a recovery: the knees give and the arms swing out. */
	fun crouch(spec: SkeletonSpec?): List<MotionTrack> = oneShotOf(spec, CROUCH_DURATION, needs = setOf(SkeletonPoses.crouch),
		SkeletonPoses.crouch.id.raw to listOf(0f to 0f, 0.55f to 1f, 1.1f to 0.85f, CROUCH_DURATION to 0f),
	)

	/** A shift onto one foot and back, the hips leading and the torso and arms following. */
	fun weightShift(spec: SkeletonSpec?): List<MotionTrack> = oneShotOf(spec, WEIGHT_SHIFT_DURATION, needs = setOf(SkeletonPoses.weight),
		SkeletonPoses.weight.id.raw to listOf(0f to 0f, 0.9f to 1f, 2.1f to 1f, WEIGHT_SHIFT_DURATION to 0f),
	)

	/** Knees drawn in and hands together, the head ducked and tilted, the body twisting side to side. */
	fun shy(spec: SkeletonSpec?): List<MotionTrack> = oneShotOf(spec, SHY_DURATION, needs = setOf(SkeletonPoses.kneesIn, SkeletonPoses.arms),
		SkeletonPoses.kneesIn.id.raw to listOf(0f to 0f, 0.5f to 0.8f, 2.3f to 0.8f, SHY_DURATION to 0f),
		SkeletonPoses.arms.id.raw to listOf(0f to 0f, 0.6f to -1f, 2.3f to -1f, SHY_DURATION to 0f),
		StandardParameters.ANGLE_Z.raw to listOf(0f to 0f, 0.6f to 8f, 1.5f to 6f, 2.3f to 8f, SHY_DURATION to 0f),
		StandardParameters.ANGLE_Y.raw to listOf(0f to 0f, 0.6f to -8f, 2.3f to -8f, SHY_DURATION to 0f),
		StandardParameters.BODY_X.raw to listOf(0f to 0f, 0.6f to 0f, 1f to 3f, 1.5f to -3f, 2f to 3f, 2.4f to 0f, SHY_DURATION to 0f),
	)

	/** The right hand raised beside the face and waved three times, the head turning toward the viewer. */
	fun wave(spec: SkeletonSpec?): List<MotionTrack> = oneShotOf(spec, WAVE_DURATION, needs = setOf(SkeletonPoses.wave),
		SkeletonPoses.wave.id.raw to listOf(0f to 0f, 0.35f to 1f, 2f to 1f, WAVE_DURATION to 0f),
		SkeletonPoses.waveSwing.id.raw to listOf(0f to 0f, 0.35f to 0f, 0.6f to 1f, 0.85f to -1f, 1.1f to 1f, 1.35f to -1f,
			1.6f to 1f, 1.85f to 0f, WAVE_DURATION to 0f),
		// The weight goes onto the left foot, away from the raised arm.
		SkeletonPoses.weight.id.raw to listOf(0f to 0f, 0.4f to 0.3f, 2f to 0.3f, WAVE_DURATION to 0f),
		StandardParameters.ANGLE_Z.raw to listOf(0f to 0f, 0.4f to 5f, 2f to 5f, WAVE_DURATION to 0f),
		StandardParameters.ANGLE_X.raw to listOf(0f to 0f, 0.4f to 6f, 2f to 6f, WAVE_DURATION to 0f),
	)

	/** A big head tilt held for a beat, the body and weight leaning with it and the hands drawn in a little. */
	fun headTilt(spec: SkeletonSpec?): List<MotionTrack> =
		oneShotOf(spec, HEAD_TILT_DURATION, needs = setOf(SkeletonPoses.weight, SkeletonPoses.arms),
			StandardParameters.ANGLE_Z.raw to listOf(0f to 0f, 0.45f to 12f, 1.6f to 12f, HEAD_TILT_DURATION to 0f),
			StandardParameters.ANGLE_Y.raw to listOf(0f to 0f, 0.45f to 4f, 1.6f to 4f, HEAD_TILT_DURATION to 0f),
			StandardParameters.BODY_Z.raw to listOf(0f to 0f, 0.55f to 3f, 1.6f to 3f, HEAD_TILT_DURATION to 0f),
			SkeletonPoses.weight.id.raw to listOf(0f to 0f, 0.5f to 0.4f, 1.6f to 0.4f, HEAD_TILT_DURATION to 0f),
			SkeletonPoses.arms.id.raw to listOf(0f to 0f, 0.5f to -0.4f, 1.6f to -0.4f, HEAD_TILT_DURATION to 0f),
		)

	/** Two little hops, each out of a dip, the arms thrown up on each, the head lifted and the tail and wings going. */
	fun cheer(spec: SkeletonSpec?): List<MotionTrack> = oneShotOf(spec, CHEER_DURATION, needs = setOf(SkeletonPoses.crouch, SkeletonPoses.arms),
		SkeletonPoses.crouch.id.raw to listOf(0f to 0f, 0.25f to 0.4f, 0.4f to 0f, 0.7f to 0f, 0.85f to 0.4f, 1f to 0f,
			1.3f to 0f, 1.45f to 0.2f, CHEER_DURATION to 0f),
		SkeletonPoses.hop.id.raw to listOf(0f to 0f, 0.4f to 0f, 0.55f to 0.4f, 0.7f to 0f, 1f to 0f, 1.15f to 0.4f, 1.3f to 0f,
			CHEER_DURATION to 0f),
		SkeletonPoses.arms.id.raw to listOf(0f to 0f, 0.25f to -0.2f, 0.55f to 1f, 0.8f to 0.6f, 1.05f to 1f, 1.4f to 1f, CHEER_DURATION to 0f),
		StandardParameters.ANGLE_Y.raw to listOf(0f to 0f, 0.25f to -3f, 0.55f to 10f, 1.4f to 10f, CHEER_DURATION to 0f),
		StandardParameters.BODY_Y.raw to listOf(0f to 0f, 0.25f to -3f, 0.55f to 3f, 0.75f to -3f, 1.05f to 3f, CHEER_DURATION to 0f),
		StandardParameters.BREATH.raw to listOf(0f to 0f, 0.55f to 1f, 1.2f to 1f, CHEER_DURATION to 0f),
		sine(SkeletonPoses.tailSwing.id.raw, amplitude = 0.8f, cycles = 3, phase = 0f, duration = CHEER_DURATION),
		sine(SkeletonPoses.wingFlap.id.raw, amplitude = 1f, cycles = 4, phase = 0f, duration = CHEER_DURATION),
	)

	/** The weight onto the left foot, then the right foot kicked up behind and held, the arms out for balance. */
	fun legKick(spec: SkeletonSpec?): List<MotionTrack> = oneShotOf(spec, LEG_KICK_DURATION, needs = setOf(SkeletonPoses.legLift),
		SkeletonPoses.weight.id.raw to listOf(0f to 0f, 0.5f to 0.8f, 2f to 0.8f, LEG_KICK_DURATION to 0f),
		SkeletonPoses.legLift.id.raw to listOf(0f to 0f, 0.4f to 0f, 0.8f to -1f, 1.8f to -1f, 2.2f to 0f, LEG_KICK_DURATION to 0f),
		SkeletonPoses.arms.id.raw to listOf(0f to 0f, 0.8f to 0.3f, 1.8f to 0.3f, 2.4f to 0f, LEG_KICK_DURATION to 0f),
		StandardParameters.ANGLE_Z.raw to listOf(0f to 0f, 0.8f to -6f, 1.8f to -6f, 2.4f to 0f, LEG_KICK_DURATION to 0f),
		StandardParameters.BODY_Z.raw to listOf(0f to 0f, 0.6f to 2f, 2f to 2f, LEG_KICK_DURATION to 0f),
	)

	/** A happy side-to-side sway, bigger than the idle's, every part trailing the hips; it fades in and out. */
	fun sway(spec: SkeletonSpec?): List<MotionTrack> {
		fun swing(id: String, amplitude: Float, lag: Float) = faded(id, amplitude, cycles = 2, lag = lag, duration = SWAY_DURATION)
		return oneShotOf(spec, SWAY_DURATION, needs = setOf(SkeletonPoses.weight, SkeletonPoses.armSway),
			swing(SkeletonPoses.weight.id.raw, amplitude = 0.8f, lag = 0f),
			swing(StandardParameters.BODY_Z.raw, amplitude = -2f, lag = 0.08f),
			swing(StandardParameters.ANGLE_Z.raw, amplitude = 4f, lag = 0.15f),
			swing(StandardParameters.ANGLE_X.raw, amplitude = 4f, lag = 0.2f),
			swing(SkeletonPoses.armSway.id.raw, amplitude = 1f, lag = 0.2f),
			swing(SkeletonPoses.tailSwing.id.raw, amplitude = 0.6f, lag = 0.3f),
		)
	}

	/**
	 * The idle standing knock-kneed with the hands drawn in. The knees hold [SkeletonPoses.kneesIn], so the
	 * weight cycle - another leg pose - drops out and the body tracks alone carry the sway.
	 */
	fun idleCute(spec: SkeletonSpec?, exclude: Set<String> = emptySet()): List<MotionTrack> {
		val available = SkeletonPoses.available(spec)
		if (SkeletonPoses.kneesIn !in available) return emptyList()
		val held = available.filter { it.legs }.associateWith { if (it == SkeletonPoses.kneesIn) 0.3f else 0f } +
			available.filter { it == SkeletonPoses.arms }.associateWith { -0.3f }
		val ids = held.keys.mapTo(HashSet()) { it.id.raw }
		return idle(spec, exclude).filterNot { it.first in ids } + held.map { (pose, value) -> pose.id.raw to listOf(0f to value, IDLE_DURATION to value) }
	}

	/**
	 * A one-shot of [tracks] across [duration], empty unless [spec] can play one of the poses it [needs].
	 * Tracks of poses [spec] cannot play are dropped, and every leg pose the motion leaves out is held at
	 * rest: the leg poses only add, so two of them never play together.
	 */
	private fun oneShotOf(spec: SkeletonSpec?, duration: Float, needs: Set<SkeletonPose>, vararg tracks: MotionTrack): List<MotionTrack> {
		val available = SkeletonPoses.available(spec)
		if (available.none { it in needs }) return emptyList()
		val playable = available.mapTo(HashSet()) { it.id.raw }
		val kept = tracks.filter { (id) -> SkeletonPoses.all.none { it.id.raw == id } || id in playable }
		val held = available.filter { pose -> pose.legs && kept.none { it.first == pose.id.raw } }
			.map { it.id.raw to listOf(0f to 0f, duration to 0f) }
		return (kept + held).clamped()
	}

	/**
	 * The values the idle holds at [elapsed] seconds, body parameters included. The preview evaluates the
	 * same tracks the export writes.
	 */
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

	/** A [sine] faded in and out over the first and last fifth of [duration], so a one-shot starts and ends at rest. */
	private fun faded(parameterId: String, amplitude: Float, cycles: Int, lag: Float, duration: Float): MotionTrack {
		val (id, points) = sine(parameterId, amplitude, cycles, phase = -lag * TAU, duration = duration)
		return id to points.map { (time, value) ->
			val edge = (minOf(time, duration - time) / (duration * 0.2f)).coerceIn(0f, 1f)
			time to value * edge * edge * (3f - 2f * edge)
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
