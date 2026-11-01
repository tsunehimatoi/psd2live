package io.github.psd2live.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Body motions worked out for the figure at hand rather than written as fixed angles.
 *
 * Each motion names what the body does - a hand raised beside the face, the hips dropped while the
 * balance stays over the feet - and reads where that is from the drawn rest pose and the figure's
 * proportions ([SkeletonAnatomy]), inside what a person comfortably does. Its timing follows the usual
 * rules of character animation: a small anticipation before a move, the joints down a chain arriving one
 * after another, an overshoot that settles, and the rest of the body answering the gesture. Keys go on
 * the beats only; [MotionCurveMath.eased] eases between them.
 *
 * Only parameters the rig already has are driven: the bones' own, the leg poses and the standard angles.
 */
internal object MotionSynth {
	const val WAVE_DURATION = 2.8f
	const val CROUCH_DURATION = 2.2f
	const val WEIGHT_SHIFT_DURATION = 3f
	const val SHY_DURATION = 3f
	const val HEAD_TILT_DURATION = 2.2f
	const val CHEER_DURATION = 2.2f
	const val LEG_KICK_DURATION = 2.6f
	const val SWAY_DURATION = 3f

	/** One loop of the idle: long enough for three breaths of different depths and an unhurried weight cycle. */
	const val IDLE_DURATION = 12f

	/** Keys per parameter, every one starting and ending at rest. */
	private class Timeline(val duration: Float) {
		/** A key; a null slope is left to [MotionCurveMath.easedKnots]. */
		private class Key(val time: Float, val value: Float, val inSlope: Float? = null, val outSlope: Float? = null)

		private val tracks = LinkedHashMap<String, MutableList<Key>>()

		fun key(id: String, time: Float, value: Float) {
			tracks.getOrPut(id) { mutableListOf() } += Key(time.coerceIn(0f, duration), value)
		}

		/** A key the curve reaches with [inSlope] and leaves with [outSlope], per second: a push-off or a landing. */
		fun kink(id: String, time: Float, value: Float, inSlope: Float, outSlope: Float) {
			tracks.getOrPut(id) { mutableListOf() } += Key(time.coerceIn(0f, duration), value, inSlope, outSlope)
		}

		/** Keys at the given times and values on [id]. */
		fun keys(id: String, vararg points: Pair<Float, Float>) = points.forEach { (t, v) -> key(id, t, v) }

		/**
		 * [beats] on [id], [lag] seconds late and scaled by [scale]: a part following the one that drives it.
		 * A beat that would land at the very end is dropped, so the follower still settles.
		 */
		fun follow(id: String, beats: List<Pair<Float, Float>>, lag: Float, scale: Float) {
			if (abs(scale) < 1e-4f) return
			for ((time, value) in beats) if (time + lag < duration - 0.05f) key(id, time + lag, value * scale)
		}

		fun curves(): List<MotionCurve> = tracks.keys.map(::curve)

		/** What [id] holds at [time] with the keys it has so far. */
		fun value(id: String, time: Float): Float = if (id in tracks) MotionCurveMath.value(curve(id), time) else 0f

		private fun curve(id: String): MotionCurve {
			val sorted = tracks.getValue(id).sortedBy { it.time }
			val full = buildList {
				if (sorted.first().time > MotionClips.TIME_EPSILON) add(Key(0f, 0f))
				addAll(sorted)
				if (sorted.last().time < duration - MotionClips.TIME_EPSILON) add(Key(duration, 0f))
			}
			val eased = MotionCurveMath.easedKnots(full.map { it.time to it.value })
			return MotionCurveMath.curve(id, full.zip(eased) { key, knot ->
				MotionCurveMath.Knot(knot.time, knot.value, key.inSlope ?: knot.inSlope, key.outSlope ?: knot.outSlope)
			})
		}
	}

	/** The parameters of one arm and the values that put it where a motion wants it. */
	private class ArmPose(val arm: SkeletonAnatomy.Arm, val upper: Float, val fore: Float, val hand: Float) {
		val upperId = arm.upper.parameterId
		val foreId = arm.fore.parameterId
		val handId = arm.hand?.parameterId
	}

	/**
	 * Parameter values that put [arm]'s bones at elevations [upperE], [foreE] and [handE]. A child bone's
	 * parameter turns it on top of its parent's, so each one takes what its parents have not already done.
	 */
	private fun armAt(arm: SkeletonAnatomy.Arm, upperE: Float, foreE: Float, handE: Float): ArmPose {
		val upper = (upperE - arm.restUpper).coerceIn(arm.upper.minAngle, arm.upper.maxAngle)
		val fore = (foreE - arm.restFore - upper).coerceIn(arm.fore.minAngle, arm.fore.maxAngle)
		val hand = arm.hand?.let { (handE - arm.restHand - upper - fore).coerceIn(it.minAngle, it.maxAngle) } ?: 0f
		return ArmPose(arm, upper, fore, hand)
	}

	/**
	 * [pose] carried past itself by [upper], [fore] and [hand] degrees of parameter - an overshoot - as
	 * far as stays comfortable, with every joint taking the same share of its push.
	 */
	private fun past(pose: ArmPose, upper: Float, fore: Float, hand: Float): FloatArray {
		val arm = pose.arm
		var share = 1f
		while (share > 0f) {
			val u = pose.upper + upper * share
			val f = pose.fore + fore * share
			val h = pose.hand + (if (arm.hand != null) hand * share else 0f)
			if (SkeletonAnatomy.armStrain(arm.restUpper + u, arm.restFore + u + f, arm.restHand + u + f + h) <= 0f) return floatArrayOf(u, f, h)
			share -= 0.125f
		}
		return floatArrayOf(pose.upper, pose.fore, pose.hand)
	}

	/**
	 * A key that points [arm]'s forearm at [elevation] at [time], whatever the upper arm is doing then: its
	 * keys must already be in. Keying a moving arm this way lets the elbow fold ahead of the arm, so a hand
	 * travels in an arc close to the body instead of swinging out on a straight arm.
	 */
	private fun Timeline.forearm(arm: SkeletonAnatomy.Arm, time: Float, elevation: Float) =
		key(arm.fore.parameterId, time, elevation - arm.restFore - value(arm.upper.parameterId, time))

	/** Which way the head, the body and the weight lean toward [side]: +1 for the character's right. */
	private fun sign(side: Side) = if (side == Side.LEFT) -1f else 1f

	/** The arms of [anatomy] whose turning moves a mesh. */
	private fun movingArms(spec: SkeletonSpec, anatomy: SkeletonAnatomy) = anatomy.arms.filter { SkeletonPoses.isSkinned(spec, it.upper) }

	/** An arm pose: elevations of the upper arm and the forearm. */
	internal class Reach(val upper: Float, val fore: Float)

	/**
	 * The pose of [arm] with the upper arm in [upperRange] and the forearm in [foreRange] (elevations) that
	 * [cost] likes best, given the elbow and the wrist it puts on the canvas. Only poses inside the
	 * comfortable range and the bones' limits are tried; null when there is none.
	 */
	private fun solveArm(
		arm: SkeletonAnatomy.Arm,
		upperRange: ClosedFloatingPointRange<Float>,
		foreRange: ClosedFloatingPointRange<Float>,
		cost: (upper: Float, fore: Float, at: FloatArray) -> Float,
	): Reach? {
		var best: Reach? = null
		var bestCost = Float.MAX_VALUE
		var upper = max(upperRange.start, SkeletonAnatomy.UPPER_ARM_COMFORT.start)
		while (upper <= min(upperRange.endInclusive, SkeletonAnatomy.UPPER_ARM_COMFORT.endInclusive)) {
			val upperTurn = upper - arm.restUpper
			if (upperTurn in arm.upper.minAngle..arm.upper.maxAngle) {
				val elbows = SkeletonAnatomy.elbowComfort(upper)
				var fore = max(foreRange.start, SkeletonAnatomy.FOREARM_COMFORT.start)
				while (fore <= min(foreRange.endInclusive, SkeletonAnatomy.FOREARM_COMFORT.endInclusive)) {
					if (fore - upper in elbows && fore - arm.restFore - upperTurn in arm.fore.minAngle..arm.fore.maxAngle) {
						val c = cost(upper, fore, arm.reach(upper, fore))
						if (c < bestCost) {
							bestCost = c
							best = Reach(upper, fore)
						}
					}
					fore += 2f
				}
			}
			upper += 2f
		}
		return best
	}

	/**
	 * Loose arms answering the body. [beats] are where the hips are across the canvas, +1 toward +x; each
	 * arm swings [degrees] the other way [lag] seconds late, the forearm and the hand later still.
	 */
	private fun Timeline.trailArms(arms: List<SkeletonAnatomy.Arm>, beats: List<Pair<Float, Float>>, degrees: Float, lag: Float) {
		for (arm in arms) {
			// A turn of the parameter moves a hanging hand [outward] across the canvas.
			follow(arm.upper.parameterId, beats, lag, -degrees * arm.outward)
			follow(arm.fore.parameterId, beats, lag + 0.08f, -degrees * 0.6f * arm.outward)
			arm.hand?.let { follow(it.parameterId, beats, lag + 0.16f, -degrees * 0.5f * arm.outward) }
		}
	}

	/**
	 * Where [arm] goes to bring the hands together in front of the body, a little above the waist - as
	 * near as the arm reaches, folding the elbow rather than swinging the whole arm across.
	 */
	internal fun tuckReach(anatomy: SkeletonAnatomy, arm: SkeletonAnatomy.Arm): Reach? {
		val targetX = anatomy.midX + arm.outward * anatomy.headHeight * 0.08f
		val targetY = arm.shoulderY + (anatomy.waistY - arm.shoulderY) * 0.85f
		val length = arm.upper.length + arm.fore.length
		return solveArm(arm, -30f..45f, -110f..90f) { upper, fore, at ->
			val dx = (at[2] - targetX) / length
			val dy = (at[3] - targetY) / length
			6f * (dx * dx + dy * dy) + ((upper - arm.restUpper) / 90f).pow(2) + 0.3f * ((fore - upper) / 120f).pow(2)
		}
	}

	/** Where [arm] is thrown up to cheer: the hand up by the top of the head and out to the side of it, the elbow a little bent. */
	internal fun cheerReach(anatomy: SkeletonAnatomy, arm: SkeletonAnatomy.Arm): Reach? {
		val h = anatomy.headHeight
		val targetX = anatomy.faceX + arm.outward * h * 0.85f
		val targetY = anatomy.crownY + h * 0.15f
		return solveArm(arm, 60f..115f, 90f..180f) { upper, fore, at ->
			val dx = (at[2] - targetX) / h
			val dy = (at[3] - targetY) / h
			val overlap = max(0f, h * 0.6f - abs(at[2] - anatomy.faceX)) / h
			3f * dy * dy + 1.5f * dx * dx + 8f * overlap * overlap + ((upper - 100f) / 60f).pow(2) + 0.5f * ((fore - upper - 35f) / 80f).pow(2)
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Wave

	/**
	 * Where [arm] is raised to wave: the hand beside the face at about eye height and clear of the head,
	 * the elbow out to the side below it, the forearm close to upright - whichever such pose bends the
	 * joints least, all inside a comfortable range. A short arm settles for lower rather than strain.
	 */
	internal fun waveReach(anatomy: SkeletonAnatomy, arm: SkeletonAnatomy.Arm): Reach? {
		val h = anatomy.headHeight
		val clear = h * 0.55f
		return solveArm(arm, 35f..110f, 110f..172f) { upper, fore, at ->
			val height = (at[3] - anatomy.eyeY) / h
			val overlap = max(0f, clear - abs(at[2] - anatomy.faceX)) / h
			val elbow = fore - upper
			4f * height * height + 8f * overlap * overlap +
				((upper - 55f) / 60f).pow(2) + 0.5f * ((elbow - 95f) / 80f).pow(2) + 0.3f * ((fore - 160f) / 40f).pow(2)
		}
	}

	/**
	 * A hand raised beside the face and waved, three swings dying away, then lowered.
	 *
	 * The upper arm gathers a little first and lifts, the forearm and the hand arriving after it and
	 * settling from a small overshoot. The forearm swings about the elbow and the hand trails it. The
	 * weight goes onto the other foot, the body leans with it, and the head tilts toward the hand.
	 */
	fun wave(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		val available = SkeletonPoses.available(spec)
		val arm = anatomy.arm(Side.RIGHT)?.takeIf { SkeletonPoses.isSkinned(spec!!, it.upper) } ?: return emptyList()
		val s = sign(arm.side)
		val reach = waveReach(anatomy, arm) ?: return emptyList()
		val handE = reach.fore + (arm.restHand - arm.restFore) * 0.5f
		val raised = armAt(arm, reach.upper, reach.fore, handE)
		// The swing stays on the comfortable side of the elbow, and goes at most a little past upright.
		val elbows = SkeletonAnatomy.elbowComfort(reach.upper)
		val elbow = reach.fore - reach.upper
		val swing = minOf(12f + 4f * anatomy.chibi, elbows.endInclusive - elbow, elbow - elbows.start,
			SkeletonAnatomy.FOREARM_COMFORT.endInclusive - 3f - reach.fore).coerceAtLeast(4f)
		val handSwing = 16f
		val half = 0.24f - 0.03f * anatomy.chibi

		val t = Timeline(WAVE_DURATION)
		val amplitudes = floatArrayOf(1f, 0.95f, 0.8f, 0.6f)
		val start = 0.72f
		val end = start + half * (amplitudes.size + 1)
		val down = end + 0.55f
		// The upper arm gathers, lifts past its mark and settles; after the waves it lowers, dipping just
		// past rest before it stops.
		t.keys(raised.upperId, 0.14f to -3f, 0.5f to raised.upper * 1.05f, 0.66f to raised.upper,
			end to raised.upper, down to -2f, WAVE_DURATION - 0.08f to 0f)
		// The forearm is keyed by where it points, ahead of the upper arm: the elbow folds as the arm rises,
		// so the hand comes up close in an arc instead of swinging out on a straight arm, and on the way
		// down the forearm stays up a moment, then the elbow straightens as the arm falls.
		fun forearm(time: Float, elevation: Float) = t.forearm(arm, time, elevation)
		val foreE = reach.fore
		forearm(0.1f, arm.restFore)
		forearm(0.34f, arm.restFore + (foreE - arm.restFore) * 0.75f)
		forearm(0.56f, foreE + 6f)
		forearm(0.72f, foreE)
		raised.handId?.let { t.keys(it, 0.2f to 0f, 0.62f to raised.hand + 6f, 0.78f to raised.hand) }
		// Three swings and a half about the elbow, dying away; the hand a beat behind the forearm.
		for ((k, amplitude) in amplitudes.withIndex()) {
			val way = if (k % 2 == 0) 1f else -1f
			val time = start + half * (k + 1)
			t.key(raised.foreId, time, raised.fore + way * swing * amplitude)
			raised.handId?.let { t.key(it, time + 0.06f, raised.hand + way * handSwing * amplitude) }
		}
		forearm(end, foreE)
		raised.handId?.let { t.key(it, end + 0.06f, raised.hand) }
		forearm(end + 0.2f, foreE - 6f)
		t.key(raised.foreId, end + 0.42f, raised.fore * 0.45f)
		forearm(down + 0.06f, arm.restFore - 3f)
		raised.handId?.let { t.key(it, down + 0.12f, 0f) }

		// The rest of the body answers the arm.
		val settle = WAVE_DURATION - 0.15f
		if (SkeletonPoses.weight in available) t.keys(SkeletonPoses.weight.id.raw, 0.12f to 0f, 0.55f to 0.25f * s, end to 0.25f * s, settle to 0f)
		t.keys(StandardParameters.BODY_Z.raw, 0.55f to 1.2f * s, end to 1.2f * s, settle to 0f)
		t.keys(StandardParameters.ANGLE_Z.raw, 0.6f to 4f * s, 1.3f to 3f * s, end to 4f * s, settle to 0f)
		t.keys(StandardParameters.ANGLE_X.raw, 0.62f to 5f * s, end to 5f * s, settle to 0f)
		t.keys(StandardParameters.ANGLE_Y.raw, 0.6f to 3f, end to 2f, settle to 0f)
		// The other arm eases out a little, for balance.
		anatomy.arms.firstOrNull { it !== arm }?.let { other ->
			t.keys(other.upper.parameterId, 0.6f to 2.5f, end to 2.5f, settle to 0f)
		}
		return t.curves()
	}

	// ---------------------------------------------------------------------------------------------------
	// Crouch

	/**
	 * A dip and a recovery. A small lift first, then the hips drop with the body leaning forward over the
	 * feet and the head lifting to keep looking out; the arms come in front, a beat late. The knees give a
	 * little bounce at the bottom, and the rise overshoots into a slight lean back before it settles.
	 */
	fun crouch(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		if (SkeletonPoses.crouch !in SkeletonPoses.available(spec)) return emptyList()
		val bounce = 1f + 0.4f * anatomy.chibi
		val t = Timeline(CROUCH_DURATION)
		t.keys(SkeletonPoses.crouch.id.raw, 0.16f to 0f, 0.58f to 1f, 0.74f to 1f - 0.1f * bounce, 1.25f to 0.92f, 1.62f to 0f)
		t.keys(StandardParameters.BODY_Y.raw, 0.16f to 1.5f, 0.6f to -4f, 0.76f to -3.4f, 1.25f to -3.4f, 1.68f to 2f * bounce, 1.98f to 0f)
		t.keys(StandardParameters.ANGLE_Y.raw, 0.16f to 2f, 0.66f to 4f, 1.25f to 3.5f, 1.72f to -1.5f, 2.04f to 0f)
		t.keys(StandardParameters.BREATH.raw, 0.6f to 0.2f, 1.25f to 0.7f, 1.7f to 0.3f)
		for (arm in anatomy.arms) {
			if (!SkeletonPoses.isSkinned(spec!!, arm.upper)) continue
			// The hands come in front of the body: the upper arm draws in and the elbow folds inward.
			val elbows = SkeletonAnatomy.elbowComfort(arm.restUpper - 6f)
			val drawnElbow = arm.restFore - arm.restUpper
			val fold = maxOf(-14f, elbows.start - drawnElbow).coerceAtMost(0f)
			t.keys(arm.upper.parameterId, 0.16f to -1f, 0.68f to -6f, 1.25f to -5f, 1.72f to 3f, 2.06f to 0f)
			t.keys(arm.fore.parameterId, 0.74f to fold, 1.25f to fold * 0.85f, 1.78f to 4f, 2.12f to 0f)
			arm.hand?.let { t.keys(it.parameterId, 0.8f to -6f, 1.25f to -5f, 1.84f to 3f, CROUCH_DURATION to 0f) }
		}
		return t.curves()
	}

	// ---------------------------------------------------------------------------------------------------
	// Weight

	/**
	 * The upper body and the head over hips that are at [hips] across the canvas (+1 toward +x, as the
	 * weight pose takes them): the body shifts and leans a little late, the head tilts back against the
	 * lean to keep the eyes level and turns last, the way weight travels up a standing body.
	 */
	private fun Timeline.bodyOver(hips: List<Pair<Float, Float>>, scale: Float) {
		follow(StandardParameters.BODY_X.raw, hips, 0.08f, 3f * scale)
		follow(StandardParameters.BODY_Z.raw, hips, 0.12f, -2.5f * scale)
		follow(StandardParameters.ANGLE_Z.raw, hips, 0.2f, 4f * scale)
		follow(StandardParameters.ANGLE_X.raw, hips, 0.26f, 4f * scale)
	}

	/** Onto one foot, held steadily, then back: the chest and arms follow without a preparatory kick or bounce. */
	fun weightShift(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		val t = Timeline(WEIGHT_SHIFT_DURATION)
		val hips = listOf(0.2f to 0f, 1f to 0.85f, 1.9f to 0.85f, 2.8f to 0f)
		t.follow(SkeletonPoses.weight.id.raw, hips, 0f, 1f)
		t.follow(StandardParameters.BODY_X.raw, hips, 0.1f, 0.8f)
		t.follow(StandardParameters.BODY_Z.raw, hips, 0.15f, -0.5f)
		t.follow(StandardParameters.ANGLE_Z.raw, hips, 0.22f, 1.2f)
		t.follow(StandardParameters.ANGLE_X.raw, hips, 0.25f, 1.5f)
		t.trailArms(movingArms(spec!!, anatomy), hips, 1f, 0.18f)
		return t.curves()
	}

	/** A happy side-to-side sway, two swings out of a small push and dying into rest, every part trailing the hips. */
	fun sway(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		val t = Timeline(SWAY_DURATION)
		val hips = listOf(0.15f to -0.08f, 0.75f to 0.75f, 1.5f to -0.75f, 2.2f to 0.5f, 2.75f to 0f)
		t.follow(SkeletonPoses.weight.id.raw, hips, 0f, 1f)
		t.bodyOver(hips, 1f)
		t.trailArms(movingArms(spec!!, anatomy), hips, 3f, 0.2f)
		t.follow(SkeletonPoses.tailSwing.id.raw, hips, 0.3f, 0.6f)
		return t.curves()
	}

	/**
	 * Onto the left foot, then the right foot kicked up behind and bobbing there, the arms out for balance
	 * and the head tilted happily against the lean.
	 */
	fun legKick(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		val t = Timeline(LEG_KICK_DURATION)
		t.keys(SkeletonPoses.weight.id.raw, 0.12f to -0.06f, 0.55f to 0.8f, 0.72f to 0.74f, 1.95f to 0.76f, 2.45f to 0f)
		t.keys(SkeletonPoses.legLift.id.raw, 0.45f to 0f, 0.78f to -1f, 0.98f to -0.78f, 1.18f to -1f, 1.4f to -0.82f, 1.62f to -0.95f, 2f to 0f)
		t.keys(StandardParameters.BODY_X.raw, 0.6f to 2f, 2.05f to 2f, 2.5f to 0f)
		t.keys(StandardParameters.BODY_Z.raw, 0.65f to 2f, 2.05f to 2f, 2.5f to 0f)
		t.keys(StandardParameters.ANGLE_Z.raw, 0.85f to -6f, 1.5f to -4.5f, 1.95f to -6f, 2.4f to 0.8f)
		t.keys(StandardParameters.ANGLE_Y.raw, 0.85f to 3f, 2f to 3f, 2.45f to 0f)
		// The arms lift away from the body as the leg goes up, the forearm and the hand following.
		for (arm in movingArms(spec!!, anatomy)) {
			val upperE = maxOf(arm.restUpper + 14f, 32f).coerceAtMost(60f)
			val out = armAt(arm, upperE, upperE + 14f, upperE + 22f)
			t.keys(out.upperId, 0.15f to -1.5f, 0.55f to out.upper * 0.7f, 0.82f to out.upper * 1.08f, 1f to out.upper, 1.95f to out.upper, 2.45f to 0f)
			t.keys(out.foreId, 0.25f to 0f, 0.9f to out.fore * 1.1f, 1.08f to out.fore, 2f to out.fore, 2.5f to 0f)
			out.handId?.let { t.keys(it, 0.3f to 0f, 0.98f to out.hand * 1.15f, 1.15f to out.hand, 2.05f to out.hand, 2.55f to 0f) }
		}
		return t.curves()
	}

	// ---------------------------------------------------------------------------------------------------
	// Gestures of the head and the hands

	/** [amount] of the hands-together pose of each arm, as parameter values: what the cute idle holds. */
	fun tucked(spec: SkeletonSpec?, amount: Float): Map<String, Float> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyMap()
		return buildMap {
			for (arm in movingArms(spec!!, anatomy)) {
				val pose = tuckPose(anatomy, arm) ?: continue
				put(pose.upperId, pose.upper * amount)
				put(pose.foreId, pose.fore * amount)
				pose.handId?.let { put(it, pose.hand * amount) }
			}
		}
	}

	private fun tuckPose(anatomy: SkeletonAnatomy, arm: SkeletonAnatomy.Arm): ArmPose? =
		tuckReach(anatomy, arm)?.let { armAt(arm, it.upper, it.fore, it.fore - 10f) }

	/**
	 * Shy: a quick peek up, then the head ducks and tilts away, the knees draw in and the hands come
	 * together in front, fidgeting while the body twists from side to side.
	 */
	fun shy(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		val t = Timeline(SHY_DURATION)
		t.keys(SkeletonPoses.kneesIn.id.raw, 0.2f to 0f, 0.62f to 0.8f, 0.75f to 0.72f, 2.25f to 0.75f, 2.75f to 0f)
		t.keys(StandardParameters.ANGLE_Y.raw, 0.18f to 1.5f, 0.65f to -9f, 0.8f to -8f, 2.25f to -8f, 2.75f to 0f)
		t.keys(StandardParameters.ANGLE_Z.raw, 0.65f to 8f, 1.4f to 6f, 2.05f to 8.5f, 2.7f to 0f)
		t.keys(StandardParameters.ANGLE_X.raw, 0.7f to -6f, 1.6f to -3.5f, 2.3f to -6f, 2.8f to 0f)
		t.keys(StandardParameters.BODY_X.raw, 0.7f to 0f, 1.05f to 2.5f, 1.55f to -2.5f, 2.05f to 2f, 2.5f to 0f)
		t.keys(StandardParameters.BODY_Y.raw, 0.65f to -2f, 2.25f to -2f, 2.75f to 0f)
		t.keys(StandardParameters.BODY_Z.raw, 0.7f to 1.5f, 2.3f to 1.5f, 2.75f to 0f)
		val hands = listOf(0.15f to -0.05f, 0.62f to 1.05f, 0.78f to 1f, 2.25f to 1f, 2.75f to 0f)
		for (arm in movingArms(spec!!, anatomy)) {
			val pose = tuckPose(anatomy, arm) ?: continue
			t.follow(pose.upperId, hands, 0f, pose.upper)
			t.follow(pose.foreId, hands, 0.07f, pose.fore)
			// The hands fidget a little while they are together.
			t.keys(pose.foreId, 1.3f to pose.fore + 3f, 1.75f to pose.fore - 2.5f)
			pose.handId?.let { t.follow(it, hands, 0.14f, pose.hand) }
		}
		return t.curves()
	}

	/** A big head tilt with a small dip the other way first, held for a beat; the body leans after it and the hands draw in a little. */
	fun headTilt(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		val t = Timeline(HEAD_TILT_DURATION)
		val tilt = listOf(0.12f to -0.12f, 0.48f to 1.08f, 0.62f to 0.96f, 1.55f to 1f, 1.95f to -0.08f)
		t.follow(StandardParameters.ANGLE_Z.raw, tilt, 0f, 12f)
		t.follow(StandardParameters.ANGLE_X.raw, tilt, 0.04f, 4f)
		t.follow(StandardParameters.BODY_Z.raw, tilt, 0.1f, 3f)
		t.follow(SkeletonPoses.weight.id.raw, tilt, 0.12f, 0.3f)
		t.keys(StandardParameters.ANGLE_Y.raw, 0.12f to -1f, 0.5f to 4f, 1.55f to 4f, 2f to 0f)
		val hands = listOf(0.18f to 0f, 0.62f to 1.1f, 0.78f to 1f, 1.55f to 1f, 2.05f to 0f)
		for (arm in movingArms(spec!!, anatomy)) {
			val pose = tuckPose(anatomy, arm) ?: continue
			t.follow(pose.upperId, hands, 0f, pose.upper * 0.3f)
			t.follow(pose.foreId, hands, 0.06f, pose.fore * 0.3f)
			pose.handId?.let { t.follow(it, hands, 0.12f, pose.hand * 0.3f) }
		}
		return t.curves()
	}

	// ---------------------------------------------------------------------------------------------------
	// Cheer

	/**
	 * Two little hops out of a dip, the arms thrown up beside the head on the first and pumping on the
	 * landings, the head lifted. The legs push off and land with speed and the knees take the landing.
	 */
	fun cheer(spec: SkeletonSpec?): List<MotionCurve> {
		val anatomy = SkeletonAnatomy.of(spec) ?: return emptyList()
		val t = Timeline(CHEER_DURATION)
		val bounce = 1f + 0.4f * anatomy.chibi
		val crouch = SkeletonPoses.crouch.id.raw
		val hop = SkeletonPoses.hop.id.raw
		// Dip, push off fast, fly, land into the knees; then a smaller second hop.
		val push = 0.12f
		t.keys(crouch, 0.1f to 0f, 0.3f to 0.4f)
		t.kink(crouch, 0.42f, 0f, -0.4f * 2f / push, 0f)
		t.kink(crouch, 0.66f, 0f, 0f, 0.35f * 2f / push)
		t.key(crouch, 0.78f, 0.35f)
		t.kink(crouch, 0.9f, 0f, -0.35f * 2f / push, 0f)
		t.kink(crouch, 1.1f, 0f, 0f, 0.25f * 2f / push)
		t.keys(crouch, 1.22f to 0.25f, 1.55f to 0f)
		for ((from, to, height) in listOf(Triple(0.42f, 0.66f, 0.4f * bounce), Triple(0.9f, 1.1f, 0.25f * bounce))) {
			// A thrown body rises and falls on a parabola, leaving and landing at four times its height over the flight.
			val speed = 4f * height / (to - from)
			t.kink(hop, from, 0f, 0f, speed)
			t.key(hop, (from + to) / 2f, height)
			t.kink(hop, to, 0f, -speed, 0f)
		}
		t.keys(StandardParameters.BODY_Y.raw, 0.3f to -3f, 0.5f to 3f, 0.72f to -2f, 0.98f to 2.5f, 1.2f to -1f, 1.6f to 1f, 2f to 0f)
		t.keys(StandardParameters.ANGLE_Y.raw, 0.3f to -3f, 0.55f to 8f, 0.75f to 6f, 1f to 9f, 1.55f to 7f, 2.05f to 0f)
		t.keys(StandardParameters.ANGLE_Z.raw, 0.6f to 3f, 1.1f to -3f, 1.6f to 2f, 2.05f to 0f)
		t.keys(StandardParameters.BREATH.raw, 0.5f to 1f, 1.3f to 0.8f, 1.9f to 0f)
		for ((index, arm) in movingArms(spec!!, anatomy).withIndex()) {
			val reach = cheerReach(anatomy, arm) ?: continue
			val up = armAt(arm, reach.upper, reach.fore, reach.fore + 10f)
			// The arms do not move quite together.
			val d = index * 0.03f
			// Drawn back in the dip, thrown up past the mark on the push, sagging and pumping on the landings,
			// then lowered with the elbow giving.
			// The overshoot of the throw and the pump of the second hop, kept comfortable.
			val throwUp = past(up, up.upper * 0.06f, 10f, 10f)
			val pump = past(up, up.upper * 0.04f, 5f, 0f)
			t.keys(up.upperId, 0.3f + d to -6f, 0.5f + d to throwUp[0], 0.62f + d to up.upper, 0.74f + d to up.upper * 0.9f,
				0.98f + d to pump[0], 1.12f + d to up.upper * 0.93f, 1.45f + d to up.upper, 2.08f + d to 0f)
			t.keys(up.foreId, 0.32f + d to -8f, 0.56f + d to throwUp[1], 0.68f + d to up.fore, 0.8f + d to up.fore + 6f,
				1.04f + d to up.fore - 3f, 1.18f + d to pump[1], 1.5f + d to up.fore)
			// Coming down the forearm stays up a moment and the elbow gives, rather than a straight arm sweeping down.
			t.forearm(arm, 1.72f + d, reach.fore - 8f)
			// Late in the drop the elbow straightens as the arm falls, never left bent out flat.
			t.key(up.foreId, 1.92f + d, up.fore * 0.4f)
			t.forearm(arm, 2.12f, arm.restFore - 3f)
			up.handId?.let { t.keys(it, 0.35f + d to -6f, 0.6f + d to throwUp[2], 0.72f + d to up.hand, 1.5f + d to up.hand, 2.12f + d to 0f) }
		}
		return t.curves()
	}

	// -----------------------------------------------------------------------------------------------
	// The idle

	/**
	 * The two clocks a standing body keeps, as signals over one loop of [IDLE_DURATION].
	 *
	 * - The breath: three breaths of different lengths and depths, each drawn in quickly and let out
	 *   slowly, with the lungs nearly empty for a moment before the next. 0 is breathed out.
	 * - The weight: a slow cycle from one foot to the other with a second, quicker one on top - six
	 *   seconds and four, which never fall into step within the loop - so no two shifts are alike.
	 *   Normalized to peaks of ±1, positive toward the hips' +x.
	 */
	private object IdleClock {
		private val breathCurve = MotionCurveMath.eased("breath", listOf(
			0f to 0f, 1.15f to 1f, 3.45f to 0f, 3.9f to 0f, 5.4f to 0.62f, 7.35f to 0f, 8.65f to 0.95f, IDLE_DURATION to 0f,
		), loop = true)

		fun breath(time: Float): Float = MotionCurveMath.looped(breathCurve, time.toDouble(), IDLE_DURATION)

		/** Where the breath sits on average, so a part that nods with it rocks about its rest. */
		val breathMean: Float = run {
			val samples = 240
			(0 until samples).sumOf { breath(IDLE_DURATION * it / samples).toDouble() }.toFloat() / samples
		}

		private fun rawWeight(time: Float) = wave(time, 2, 0.4f) + 0.4f * wave(time, 3, 2.2f)
		private val weightPeak = (0 until 1200).maxOf { abs(rawWeight(IDLE_DURATION * it / 1200f)) }

		fun weight(time: Float): Float = rawWeight(time) / weightPeak

		/** A slow drift of the head's gaze, once around the loop. */
		fun drift(time: Float): Float = wave(time, 1, 1.1f)

		/** A sine of [cycles] whole cycles over the loop. */
		fun wave(time: Float, cycles: Int, phase: Float): Float = sin(2.0 * PI * cycles * time / IDLE_DURATION + phase).toFloat()
	}

	/**
	 * The resting idle, before its poses are moved onto the bones: a body standing at ease, breathing and
	 * shifting its weight, every part following the one that carries it a little late, the way weight
	 * travels up a standing body.
	 *
	 * - The breath lifts the shoulders and the chest (the body's breath warp): a quick draw in, a slower
	 *   let out, and a beat empty before the next. The body nods with it and the head after that, with a
	 *   second quicker cycle so the head does not trace the same path on every breath.
	 * - The hips lead the weight cycle from one foot to the other, the knees giving in turn. The body
	 *   turns after them and leans back over them, the head tilts against the lean and turns last, drifting
	 *   a little on its own as well.
	 * - The arms hang relaxed: each joint swings a few degrees after the one above, the elbow and the
	 *   hand trailing furthest, the two arms not quite in step. The tail swings lazily and the wings breathe.
	 *
	 * The body tracks hold without a skeleton too. A pose whose bones are all in [exclude] - driven by
	 * physics, typically - is left out, as is any bone in it, so the two do not fight. Parameters in [hold]
	 * stand at their value through the loop - the cute idle's knees and tucked hands - with an arm's sway
	 * on top of the hold rather than instead of it; a held leg pose takes the weight cycle's place.
	 */
	fun idle(spec: SkeletonSpec?, exclude: Set<String>, hold: Map<String, Float> = emptyMap()): List<MotionCurve> {
		fun follow(id: String, scale: Float, lag: Float, bias: Float = 0f, signal: (Float) -> Float) =
			loopCurve(id) { bias + scale * signal(it - lag) }
		val breath = IdleClock::breath
		val weight = IdleClock::weight
		// The breath's mean, so the parts that nod with it rock about their rest pose.
		val breathMean = IdleClock.breathMean
		val body = listOf(
			loopCurve(StandardParameters.BREATH.raw, breath),
			follow(StandardParameters.BODY_Y.raw, 3.1f, 0.15f, -3.1f * breathMean, breath),
			// The head nods with the breath, drifts once around the loop, and adds a quicker second
			// cycle so two breaths never move it the same way.
			loopCurve(StandardParameters.ANGLE_Y.raw) {
				5.2f * (breath(it - 0.4f) - breathMean) + 3.2f * IdleClock.wave(it, 1, 2f) + 1.3f * IdleClock.wave(it, 2, 0.7f)
			},
			follow(StandardParameters.BODY_X.raw, 4.2f, 0.45f, signal = weight),
			follow(StandardParameters.BODY_Z.raw, -3f, 0.7f, signal = weight),
			follow(StandardParameters.ANGLE_Z.raw, 5f, 1.2f, signal = weight),
			loopCurve(StandardParameters.ANGLE_X.raw) {
				5.5f * weight(it - 1.5f) + 4.2f * IdleClock.drift(it) + 1.1f * IdleClock.wave(it, 3, 0.4f)
			},
		)
		if (spec?.enabled != true) return body
		val available = SkeletonPoses.available(spec).filterNot { pose ->
			SkeletonPoses.drivenParameters(spec, pose).let { it.isNotEmpty() && exclude.containsAll(it) }
		}
		val held = hold.filterKeys { it !in exclude }
		val heldLegs = available.any { it.legs && it.id.raw in held }
		val posed = available.mapNotNull { pose ->
			val id = pose.id.raw
			if (id in held) return@mapNotNull null
			when (pose) {
				SkeletonPoses.weight -> if (heldLegs) null else follow(id, WEIGHT_SWAY, 0f, signal = weight)
				SkeletonPoses.tailSwing -> loopCurve(id) { 0.55f * IdleClock.wave(it, 5, -1.9f) }
				SkeletonPoses.wingFlap -> follow(id, 1.6f, 0.1f, -1.6f * breathMean, breath)
				else -> null
			}.let { curve -> curve?.let { MotionCurveMath.clamped(it, pose.min, pose.max) } }
		}
		val swaying = posed.any { it.parameterId == SkeletonPoses.weight.id.raw }
		val bones = SkeletonRig.limbBones(spec).filter { it.parameterId !in exclude }
		// On top of the counter-lean the weight pose already bakes, the upper body sways a little late.
		val sway = bones.filter { it.role == BoneRole.UPPER_BODY }.map { bone ->
			MotionCurveMath.clamped(follow(bone.parameterId, UPPER_BODY_SWAY * bone.direction, 0.7f, signal = weight), bone.minAngle, bone.maxAngle)
		}
		val arms = SkeletonAnatomy.of(spec)?.let { anatomy ->
			relaxedArms(spec, anatomy, bones.mapTo(HashSet()) { it.parameterId }, held, swaying)
		}.orEmpty()
		val moved = (posed + sway + arms).mapTo(HashSet()) { it.parameterId }
		val still = held.filterKeys { it !in moved }.map { (id, value) -> MotionCurveMath.linear(id, listOf(0f to value, IDLE_DURATION to value)) }
		return (body + posed + sway + arms + still).distinctBy { it.parameterId }
	}

	/** How far the idle shifts the weight pose, of its full range. */
	private const val WEIGHT_SWAY = 0.7f

	/** Degrees the upper body sways with the weight in the idle, leaning back over the hips. */
	private const val UPPER_BODY_SWAY = 2.5f

	/**
	 * Arms hanging at ease, swung a little by the weight cycle, each joint a moment after the one above
	 * with the elbow and the hand trailing furthest. When the weight pose plays it already turns the upper
	 * arms with the hips, so only the forearm and the hand are added; otherwise the upper arm swings too,
	 * a few degrees, less when the hands are held tucked. The swing is larger on a chibi figure and
	 * smaller for an arm drawn held out rather than hanging, and the two arms keep slightly different time.
	 * Only bones in [free] move, about their value in [held].
	 */
	private fun relaxedArms(
		spec: SkeletonSpec,
		anatomy: SkeletonAnatomy,
		free: Set<String>,
		held: Map<String, Float>,
		swaying: Boolean,
	): List<MotionCurve> = movingArms(spec, anatomy).withIndex().flatMap { (index, arm) ->
		val hanging = if (arm.restUpper > 50f) 0.5f else 1f
		val own = (if (arm.upper.parameterId in held) 1.5f else 3f) * (1f + 0.3f * anatomy.chibi) * hanging
		// The shoulder's swing the joints below follow: the weight pose's turn of it, or the arm's own.
		val shoulder = if (swaying) SkeletonPoses.WEIGHT_ARM_TURN * WEIGHT_SWAY else own
		val lag = if (index == 0) 0f else 0.12f
		val side = if (index == 0) 1f else 0.85f
		// A turn of the parameter moves a hanging hand [outward] across the canvas.
		val swing = -arm.outward * side
		fun joint(bone: SkeletonBone?, degrees: Float, delay: Float) = bone?.parameterId?.takeIf { it in free }?.let { id ->
			val curve = loopCurve(id) { time -> (held[id] ?: 0f) + swing * degrees * IdleClock.weight(time - lag - delay) }
			MotionCurveMath.clamped(curve, bone.minAngle, bone.maxAngle)
		}
		listOfNotNull(
			if (swaying) null else joint(arm.upper, own, 0.5f),
			joint(arm.fore, shoulder * 0.6f, if (swaying) 0.25f else 0.75f),
			joint(arm.hand, shoulder * 0.5f, if (swaying) 0.45f else 0.95f),
		)
	}

	/**
	 * [signal] over one idle loop as sparse keys: the loop's ends, every turn - eased flat, the way an
	 * animator keys a sway - and, where two turns are far apart, the steepest point between them with its
	 * slope. The signal must repeat over the loop; the ends then meet with the same value and slope.
	 */
	internal fun loopCurve(id: String, signal: (Float) -> Float): MotionCurve {
		val period = IDLE_DURATION
		val h = 1e-3f
		fun slope(t: Float) = (signal(t + h) - signal(t - h)) / (2f * h)
		val samples = 720
		val step = period / samples
		val turns = ArrayList<Float>()
		var s0 = slope(0f)
		for (i in 1..samples) {
			val t1 = i * step
			val s1 = slope(t1)
			if ((s0 > 0f && s1 <= 0f) || (s0 < 0f && s1 >= 0f)) {
				var lo = t1 - step
				var hi = t1
				repeat(24) {
					val mid = (lo + hi) / 2f
					if ((slope(mid) > 0f) == (s0 > 0f)) lo = mid else hi = mid
				}
				val turn = (lo + hi) / 2f
				if (turn > MotionClips.TIME_EPSILON * 4 && turn < period - MotionClips.TIME_EPSILON * 4) turns += turn
			}
			s0 = s1
		}
		val knots = ArrayList<MotionCurveMath.Knot>()
		fun steepest(from: Float, to: Float): Float {
			var best = from
			var bestSlope = -1f
			val n = 48
			for (i in 1 until n) {
				val t = from + (to - from) * i / n
				val s = abs(slope(t))
				if (s > bestSlope) { bestSlope = s; best = t }
			}
			return best
		}
		val stops = listOf(0f) + turns + period
		for ((i, time) in stops.withIndex()) {
			val turn = i in 1 until stops.lastIndex
			knots += MotionCurveMath.Knot(time, signal(time), if (turn) 0f else slope(time))
			if (i < stops.lastIndex && stops[i + 1] - time > LONG_SPAN) {
				val mid = steepest(time, stops[i + 1])
				knots += MotionCurveMath.Knot(mid, signal(mid), slope(mid))
			}
		}
		return MotionCurveMath.curve(id, knots)
	}

	/** Seconds between two turns of an idle curve past which it takes a key between them too. */
	private const val LONG_SPAN = 1.6f
}
