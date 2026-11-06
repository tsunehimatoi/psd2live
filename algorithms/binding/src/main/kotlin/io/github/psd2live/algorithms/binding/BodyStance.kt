package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.DeformerId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the figure stands on its feet, and how the body moves over them.
 *
 * The body and the legs are two root warps side by side, so nothing that moves the body reaches the floor:
 *
 * - **The body** ([bodyPoint]): a warp over the body's own parts - the torso, the arms, the skirt, the
 *   tail - and the neck the head turns on, never the whole figure. Body X turns the upper body as one
 *   solid about its centre line, waist and chest alike, easing out over the hips: the front of the chest
 *   moves toward the side it turns to, the near shoulder comes forward a little larger and lower, and the
 *   neck, near the axis, hardly moves, so the head stays in the middle of the body. Body Y opens the shoulders a little going down and stretches the back
 *   going up. Under both the whole body moves with the hips: a little sideways on Body X, down onto bent
 *   knees and up onto straightened legs on Body Y.
 * - **The lean and the proportions** ([leanPoint]): a warp under the body on two parameters of its own.
 *   The lean bows the body in three dimensions toward the viewer or back - the pelvis a little about the
 *   hips, the upper body the rest about the waist - seen in perspective; the proportions draw the head
 *   larger over a shorter torso toward a chibi, or the other way. Each arm hangs from its shoulder in a
 *   warp of its own ([armPoint]).
 * - **The legs** ([legPoint]): a warp over the legs alone. Its lowest rows, the feet, stay where they are
 *   drawn; everything above them follows two-bone legs whose hips move with the body. A knee bends
 *   forward, toward the viewer, so a front-facing thigh shortens rather than swinging out sideways, and
 *   turns in or out of that plane by how the pose holds the knees. One warp carries both legs, so legs
 *   drawn on a single layer bend at their own knees too.
 *
 * Legs skinned to bones hang from rotation deformers in the legs warp, which pass on only a pivot and an
 * angle; each of their bones carries its meshes through a warp of its own that puts them where [legPoint]
 * has the legs (see SkeletonRig). The leg poses of a skeleton (SkeletonPoses.legPoses) are poses of
 * the same model.
 */
class BodyStance(
	val character: Bounds,
	val torso: TorsoFrame,
	val legs: List<Leg>,
	/** The frame the legs warp spans, from above the hip joints to below the soles; null without legs. */
	val legsFrame: Bounds?,
	strength: Float,
	/** How far the body parameters move the body at their full values. */
	val tuning: BodyMotionTuning,
) {
	/** One leg as drawn, canvas pixels: the hip joint, the knee, the ankle and the floor under the sole. */
	class Leg(
		val side: Side,
		val hipX: Double,
		val hipY: Double,
		val kneeX: Double,
		val kneeY: Double,
		val ankleX: Double,
		val ankleY: Double,
		val floorY: Double,
	) {
		val thigh: Double get() = hypot(kneeX - hipX, kneeY - hipY).coerceAtLeast(1.0)
		val shin: Double get() = hypot(ankleX - kneeX, ankleY - kneeY).coerceAtLeast(1.0)
		val reach: Double get() = thigh + shin

		/** Hip to ankle over the full reach: the stance never straightens the leg past how it is drawn. */
		val straightness: Double get() = (hypot(ankleX - hipX, ankleY - hipY) / reach).coerceIn(0.5, 1.0)
	}

	/** A pose of the body over its feet: lengths in leg lengths, angles in degrees. */
	data class Pose(
		/** The hips sideways, toward canvas +x. */
		val shift: Double = 0.0,
		/** The pelvis turned about the hips, in the rig's convention (+x toward +y). */
		val tilt: Double = 0.0,
		/** The hips lowered, the knees bending to keep the feet down. */
		val drop: Double = 0.0,
		/** The hips raised by standing taller: the legs straighten, and stretch a little once straight. */
		val rise: Double = 0.0,
		/** How far a bending knee turns in from straight toward the viewer, degrees; negative turns it out. */
		val kneeIn: Double = 0.0,
		/** The whole figure off the floor, feet and all. */
		val lift: Double = 0.0,
	)

	/** Rigid motion of the pelvis: a turn about ([cx], [cy]), then a shift. */
	class Rigid(val cx: Double, val cy: Double, val degrees: Double, val dx: Double, val dy: Double) {
		fun apply(x: Double, y: Double): DoubleArray {
			val radians = Math.toRadians(degrees)
			val c = cos(radians)
			val s = sin(radians)
			val px = x - cx
			val py = y - cy
			return doubleArrayOf((cx + c * px - s * py) + dx, (cy + s * px + c * py) + dy)
		}
	}

	private val strength = strength.coerceIn(0f, 4f).toDouble()

	/** Every input of the stance, printed canonically, for content-hash cache keys of what it shapes. */
	val contentKey: String = buildString {
		append(character).append('|').append(torso.centerX).append(',').append(torso.shoulderY).append(',')
			.append(torso.waistY).append(',').append(torso.halfWidth).append('|')
		for (leg in legs) append(leg.side).append(':').append(leg.hipX).append(',').append(leg.hipY).append(',').append(leg.kneeX).append(',')
			.append(leg.kneeY).append(',').append(leg.ankleX).append(',').append(leg.ankleY).append(',').append(leg.floorY).append(';')
		append('|').append(legsFrame).append('|').append(this@BodyStance.strength).append('|').append(tuning)
	}

	// The [tuning] in the units the motion is worked out in: degrees, shares of a length, and lengths.
	/** Hips sideways at full Body X, lowered at full Body Y down and raised at full up, in leg lengths. */
	val hipShift = tuning.hipShift / 100.0
	val hipSink = tuning.sink / 100.0
	val hipRise = tuning.rise / 100.0
	/** Degrees the knees turn in more at full Body Y down. */
	private val kneesIn = tuning.kneesIn.toDouble()
	/** Degrees the knees turn in as they bend, however little the body sinks. */
	private val kneesInRest = tuning.kneesInRest.toDouble()
	/** The torso's turn and the arms' swing at full Body X, degrees. */
	private val turnDegrees = tuning.turnDegrees.toDouble()
	private val armSwingDegrees = tuning.armSwingDegrees.toDouble()
	/** How far below the waist the torso's turn eases out, in torso lengths. */
	private val turnFade = tuning.turnFade.toDouble()
	/** The torso's depth over its turning width. */
	private val torsoDepth = tuning.torsoDepth / 100.0
	/** How far in front of the torso the turn and the lean are seen from, in torso lengths. */
	private val turnCamera = tuning.turnCameraDistance.toDouble()
	private val leanCamera = tuning.leanCameraDistance.toDouble()
	/** At full Body Y down the back shortens and the shoulders open, and at full up it stretches and they draw in. */
	private val openShorten = tuning.openShorten / 100.0
	private val openWiden = tuning.openWiden / 100.0
	private val standStretch = tuning.standStretch / 100.0
	private val standNarrow = tuning.standNarrow / 100.0
	/** Degrees the body bows at a full lean in and a full lean back, and the share of them the pelvis takes. */
	private val leanDegrees = tuning.leanDegrees.toDouble()
	private val leanBackDegrees = tuning.leanBackDegrees.toDouble()
	private val pelvisShare = tuning.pelvisShare / 100.0
	/** At full chibi proportions: the head larger, the chest wider, the torso and arms shorter, the legs shorter. */
	private val headGrow = tuning.headGrow / 100.0
	private val chestGrow = tuning.chestGrow / 100.0
	private val bodyShrink = tuning.bodyShrink / 100.0
	private val legShrink = tuning.legShrink / 100.0

	/** The legs' mean length, hip joint to ankle; the torso's twice over without legs. */
	val legLength: Double = legs.map { it.reach }.average().takeIf { legs.isNotEmpty() } ?: (torso.length * 2.0)

	/** The point the pelvis turns about: between the hip joints, else on the waist. */
	val hipX: Double = legs.map { it.hipX }.average().takeIf { legs.isNotEmpty() } ?: torso.centerX.toDouble()
	val hipY: Double = legs.map { it.hipY }.average().takeIf { legs.isNotEmpty() } ?: torso.waistY.toDouble()

	val standing: Boolean get() = legs.isNotEmpty()

	/** Rows of the legs lattice, with enough samples to bend across the knee bands. */
	val legWarpRows: Int = legsFrame?.let { (it.height / (legLength * LEG_ROW_SPACING)).toInt().coerceIn(10, 20) } ?: 10

	// Keep the entire lattice cell containing either hip rigid. Otherwise interpolating toward a
	// bent thigh moves its root away from the pelvis, even though the solved joint itself is fixed.
	private val hipAttachmentY: Double = (legs.maxOfOrNull { it.hipY } ?: hipY) +
		(legsFrame?.height?.toDouble() ?: 0.0) / legWarpRows

	/** Weight over the supporting foot, measured from the drawn stance rather than the leg length. */
	fun weightPose(value: Float): Pose {
		val k = value.coerceIn(-1f, 1f).toDouble()
		if (k == 0.0 || legs.isEmpty()) return Pose()
		val foot = if (k > 0.0) legs.maxOf { it.ankleX } else legs.minOf { it.ankleX }
		// A narrow stance needs a smaller transfer; never push the pelvis past its supporting foot.
		val shift = ((foot - hipX) * 0.6 / legLength).coerceIn(-0.055, 0.055) * abs(k)
		return Pose(shift = shift, tilt = -HIP_TILT * k, kneeIn = 8.0 * abs(k))
	}

	/** The stance Body X and Body Y hold at [bodyX], [bodyY] (each -10..10). */
	fun bodyPose(bodyX: Float, bodyY: Float): Pose {
		if (!standing) return Pose()
		val x = bodyX / 10.0 * strength
		val y = bodyY / 10.0 * strength
		return Pose(
			shift = hipShift * x,
			drop = hipSink * (-y).coerceAtLeast(0.0),
			rise = hipRise * y.coerceAtLeast(0.0),
			kneeIn = kneesInRest + kneesIn * (-y).coerceAtLeast(0.0),
		)
	}

	// ---------------------------------------------------------------------------------------------------
	// Pelvis and legs

	/**
	 * Where the hips go under [pose]. A hip carried away from its foot past how straight the leg is drawn
	 * - and how far a rise lets it stretch - lowers the whole pelvis until every leg reaches again, which
	 * is how a weight shift settles onto the standing leg.
	 */
	fun pelvis(pose: Pose): Rigid {
		val dx = pose.shift * legLength
		val base = (pose.drop - pose.rise - pose.lift) * legLength
		fun at(extra: Double) = Rigid(hipX, hipY, pose.tilt, dx, base + extra)
		fun reaches(motion: Rigid) = legs.all { leg ->
			val hip = motion.apply(leg.hipX, leg.hipY)
			val ankleY = leg.ankleY - pose.lift * legLength
			hypot(leg.ankleX - hip[0], ankleY - hip[1]) <= leg.straightness * leg.reach + pose.rise * legLength + 1e-6
		}
		if (reaches(at(0.0))) return at(0.0)
		var low = 0.0
		var high = legLength * 0.25
		repeat(40) {
			val mid = (low + high) / 2
			if (reaches(at(mid))) high = mid else low = mid
		}
		return at(high)
	}

	/** One leg under a pose: its hip, knee and ankle, canvas pixels. */
	private class Placed(val hipX: Double, val hipY: Double, val kneeX: Double, val kneeY: Double, val ankleX: Double, val ankleY: Double)

	/** Every leg of [pose] solved once, with the pelvis that carries them. */
	inner class Solved(val pose: Pose) {
		val pelvis: Rigid = pelvis(pose)
		private val lift = pose.lift * legLength
		private val placed = legs.map { leg ->
			val hip = pelvis.apply(leg.hipX, leg.hipY)
			val ankleY = leg.ankleY - lift
			val knee = knee(leg, hip[0], hip[1], leg.ankleX, ankleY, pose.kneeIn)
			Placed(hip[0], hip[1], knee[0], knee[1], leg.ankleX, ankleY)
		}

		/** Where canvas point ([x], [y]) of the legs warp goes: each leg's bones blended along it, the legs across. */
		fun legPoint(x: Double, y: Double): DoubleArray {
			if (legs.isEmpty()) return doubleArrayOf(x, y)
			val blend = smooth((y - hipAttachmentY) / (HIP_BAND * legLength))
			if (blend >= 1.0) return blendedLegPoint(x, y)
			val hip = pelvis.apply(x, y)
			if (blend <= 0.0) return hip
			val leg = blendedLegPoint(x, y)
			return doubleArrayOf(hip[0] + (leg[0] - hip[0]) * blend, hip[1] + (leg[1] - hip[1]) * blend)
		}

		private fun blendedLegPoint(x: Double, y: Double): DoubleArray {
			if (legs.size == 1) return onLeg(0, x, y)
			val (left, right) = if (legs[0].hipX <= legs[1].hipX) 0 to 1 else 1 to 0
			val xl = lineX(legs[left], y)
			val xr = lineX(legs[right], y)
			val mid = (xl + xr) / 2
			val half = (abs(xr - xl) / 2).coerceAtLeast(1.0)
			val t = smooth(((x - mid) / (half * LEG_SEAM) + 1.0) / 2.0)
			if (t <= 0.0) return onLeg(left, x, y)
			if (t >= 1.0) return onLeg(right, x, y)
			val a = onLeg(left, x, y)
			val b = onLeg(right, x, y)
			return doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
		}

		/**
		 * ([x], [y]) carried by leg [index]: the pelvis above the hip joint, the thigh and the shin as the
		 * bones that map each drawn bone onto its solved one - a thigh bending toward the viewer comes out
		 * shorter, not narrower - and the foot below the ankle, which only a hop lifts. Each hands over to
		 * the next across a band, so the leg bends instead of creasing.
		 */
		private fun onLeg(index: Int, x: Double, y: Double): DoubleArray {
			val leg = legs[index]
			val to = placed[index]
			val s = chain(leg, x, y)
			val hipBand = HIP_BAND * legLength
			val kneeBand = KNEE_BAND * legLength
			val ankleBand = ANKLE_BAND * legLength
			val pastHip = smooth((s + hipBand) / (2 * hipBand))
			val pastKnee = smooth((s - leg.thigh + kneeBand) / (2 * kneeBand))
			val pastAnkle = smooth((s - leg.reach + ankleBand) / (2 * ankleBand))
			var outX = 0.0
			var outY = 0.0
			fun add(weight: Double, p: DoubleArray) {
				if (weight <= 0.0) return
				outX += weight * p[0]
				outY += weight * p[1]
			}
			add(1 - pastHip, pelvis.apply(x, y))
			add(pastHip - pastKnee, bone(leg.hipX, leg.hipY, leg.kneeX, leg.kneeY, to.hipX, to.hipY, to.kneeX, to.kneeY, x, y))
			add(pastKnee - pastAnkle, bone(leg.kneeX, leg.kneeY, leg.ankleX, leg.ankleY, to.kneeX, to.kneeY, to.ankleX, to.ankleY, x, y))
			add(pastAnkle, doubleArrayOf(x + to.ankleX - leg.ankleX, y + to.ankleY - leg.ankleY))
			return doubleArrayOf(outX, outY)
		}
	}

	/** Where canvas point ([x], [y]) of the legs warp goes under [pose]. */
	fun legPoint(x: Double, y: Double, pose: Pose): DoubleArray = Solved(pose).legPoint(x, y)

	/**
	 * The knee of [leg] with its hip at ([hipX], [hipY]) and its ankle at ([ankleX], [ankleY]). The leg
	 * keeps the sideways bend it is drawn with; whatever more it has to bend goes toward the viewer, turned
	 * [kneeIn] degrees toward the body's centre line, and is seen from the front. A leg asked to reach past
	 * its length stretches both its bones alike.
	 */
	fun knee(leg: Leg, hipX: Double, hipY: Double, ankleX: Double, ankleY: Double, kneeIn: Double): DoubleArray {
		// The drawn bend, sideways off the hip-to-ankle line.
		val restDx = leg.ankleX - leg.hipX
		val restDy = leg.ankleY - leg.hipY
		val restLength = hypot(restDx, restDy).coerceAtLeast(1e-6)
		val restLateral = ((leg.kneeX - leg.hipX) * -restDy + (leg.kneeY - leg.hipY) * restDx) / restLength
		val dx = ankleX - hipX
		val dy = ankleY - hipY
		val length = hypot(dx, dy).coerceAtLeast(1e-6)
		val stretch = (length / (leg.reach - 1e-6)).coerceAtLeast(1.0)
		val l1 = leg.thigh * stretch
		val l2 = leg.shin * stretch
		val ux = dx / length
		val uy = dy / length
		val px = -uy
		val py = ux
		val d = length.coerceIn(abs(l1 - l2) + 1e-6, l1 + l2 - 1e-6)
		val along = (l1 * l1 - l2 * l2 + d * d) / (2 * d)
		val bend = sqrt((l1 * l1 - along * along).coerceAtLeast(0.0))
		val inward = sign((this.hipX - leg.hipX) * px).let { if (it == 0.0) 1.0 else it }
		val turn = Math.toRadians(kneeIn)
		val s = sin(turn) * inward
		val lateral = if (bend <= abs(restLateral)) {
			sign(restLateral) * bend
		} else {
			// (restLateral + e s)^2 + (e cos)^2 = bend^2, for the forward reach e >= 0.
			val e = -restLateral * s + sqrt((restLateral * s) * (restLateral * s) - (restLateral * restLateral - bend * bend))
			restLateral + e * s
		}
		return doubleArrayOf(hipX + ux * along + px * lateral, hipY + uy * along + py * lateral)
	}

	/** Canvas x of [leg]'s drawn line at height [y]: hip to knee to ankle, held past either end. */
	private fun lineX(leg: Leg, y: Double): Double = when {
		y <= leg.hipY -> leg.hipX
		y <= leg.kneeY -> leg.hipX + (leg.kneeX - leg.hipX) * (y - leg.hipY) / (leg.kneeY - leg.hipY).coerceAtLeast(1e-6)
		y <= leg.ankleY -> leg.kneeX + (leg.ankleX - leg.kneeX) * (y - leg.kneeY) / (leg.ankleY - leg.kneeY).coerceAtLeast(1e-6)
		else -> leg.ankleX
	}

	/**
	 * How far along [leg] ([x], [y]) lies, in pixels from the hip joint: negative above it, past the reach
	 * below the ankle. Read off the nearest of the thigh, the shin and the line down to the floor.
	 */
	private fun chain(leg: Leg, x: Double, y: Double): Double {
		val points = doubleArrayOf(leg.hipX, leg.hipY, leg.kneeX, leg.kneeY, leg.ankleX, leg.ankleY, leg.ankleX, leg.floorY.coerceAtLeast(leg.ankleY + 1.0))
		var best = Double.MAX_VALUE
		var at = 0.0
		var start = 0.0
		for (i in 0 until 3) {
			val ax = points[i * 2]
			val ay = points[i * 2 + 1]
			val bx = points[i * 2 + 2]
			val by = points[i * 2 + 3]
			val length = hypot(bx - ax, by - ay).coerceAtLeast(1e-6)
			var t = ((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / (length * length)
			// The chain runs on past its ends: above the hip and below the floor.
			if (i > 0) t = t.coerceAtLeast(0.0)
			if (i < 2) t = t.coerceAtMost(1.0)
			val distance = hypot(x - (ax + (bx - ax) * t), y - (ay + (by - ay) * t))
			if (distance < best - 1e-9) {
				best = distance
				at = start + t * length
			}
			start += length
		}
		return at
	}

	/** [x], [y] carried by the bone drawn from (ax, ay) to (bx, by) onto (cx, cy) to (dx, dy), stretched along its length only. */
	private fun bone(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double, x: Double, y: Double): DoubleArray {
		val l0 = hypot(bx - ax, by - ay).coerceAtLeast(1e-6)
		val l1 = hypot(dx - cx, dy - cy).coerceAtLeast(1e-6)
		val u0x = (bx - ax) / l0
		val u0y = (by - ay) / l0
		val u1x = (dx - cx) / l1
		val u1y = (dy - cy) / l1
		val along = (x - ax) * u0x + (y - ay) * u0y
		val across = (x - ax) * -u0y + (y - ay) * u0x
		val scaled = along * l1 / l0
		return doubleArrayOf(cx + u1x * scaled - u1y * across, cy + u1y * scaled + u1x * across)
	}

	// ---------------------------------------------------------------------------------------------------
	// Body

	/**
	 * Where canvas point ([x], [y]) of the body goes at [bodyX], [bodyY]: the torso turned and opened as
	 * [torsoPoint] has it, then carried with the hips (see [bodyPose]). Below the waist nothing deforms,
	 * so a skirt moves with the hips as one piece and the legs warp meets it there.
	 */
	fun bodyPoint(x: Double, y: Double, bodyX: Float, bodyY: Float): DoubleArray {
		val p = torsoPoint(x, y, bodyX, bodyY)
		if (!standing) return p
		return pelvis(bodyPose(bodyX, bodyY)).apply(p[0], p[1])
	}

	/**
	 * The torso's own motion on Body X and Body Y.
	 *
	 * - **Turn** (Body X): the torso is a solid whose cross-section is an ellipse about the centre line,
	 *   [torsoDepth] as deep as it is wide, narrowing to the neck above the shoulders. It turns about that
	 *   line and is seen in perspective from a viewer in front of the head: the front of the chest moves
	 *   toward the side it turns to, the side coming forward is drawn a little larger and lower and the one
	 *   going back smaller, and the neck, near the axis, hardly moves. Everything above the waist turns
	 *   alike, as artists turn the upper body, so the torso does not wring; the turn eases out over the hips
	 *   below it ([BodyMotionTuning.turnFade]). Past the torso's sides points move with its silhouette, so an arm stays on
	 *   its shoulder.
	 * - **Open** (Body Y): going down the shoulders open a little and the back shortens; going up it
	 *   stretches and the shoulders draw in, as when standing tall.
	 */
	fun torsoPoint(x: Double, y: Double, bodyX: Float, bodyY: Float): DoubleArray {
		val turn = bodyX / 10.0 * strength
		val pitch = bodyY / 10.0 * strength
		val cx = torso.centerX.toDouble()
		val waist = torso.waistY.toDouble()
		val length = torso.length.toDouble()
		var px = x
		var py = y
		val above = waist - y
		if (turn != 0.0) {
			val yaw = Math.toRadians(turnDegrees * turn) * smooth((above + length * turnFade) / (length * turnFade))
			if (yaw != 0.0) {
				val radius = torso.halfWidth * YAW_RADIUS
				val neck = smooth((torso.shoulderY + length * 0.05 - y) / (length * 0.2))
				val depth = radius * (torsoDepth + (NECK_DEPTH - torsoDepth) * neck)
				val u = (x - cx) / radius
				val camera = turnCamera * length
				val cameraY = torso.shoulderY - length * CAMERA_HEIGHT
				fun seen(z0: Double, z1: Double) = (camera - z0) / (camera - z1)
				if (abs(u) < 1.0) {
					val c = sqrt(1 - u * u)
					val turnedX = radius * u * cos(yaw) + depth * c * sin(yaw)
					val turnedZ = -radius * u * sin(yaw) + depth * c * cos(yaw)
					val scale = seen(depth * c, turnedZ)
					px = cx + turnedX * scale
					py = cameraY + (y - cameraY) * scale
				} else {
					val side = sign(u)
					val scale = seen(0.0, -side * radius * sin(yaw))
					px = cx + (side * radius * cos(yaw) + (x - cx - side * radius)) * scale
					py = cameraY + (y - cameraY) * scale
				}
			}
		}
		if (pitch != 0.0) {
			val lever = lever(above, length * WAIST_BAND)
			val shoulders = smooth(above / length)
			val (stretch, widen) = if (pitch < 0.0) (-openShorten * -pitch) to (openWiden * -pitch)
			else (standStretch * pitch) to (-standNarrow * pitch)
			py -= lever * stretch
			px = cx + (px - cx) * (1 + widen * shoulders)
		}
		return doubleArrayOf(px, py)
	}

	/**
	 * Where canvas point ([x], [y]) goes on the body leaning at [lean] and drawn in the proportions of
	 * [size] (each -10..10). The proportions come first ([sized]), then the lean, so a chibi body leans as
	 * one.
	 *
	 * The lean bows the body toward the viewer at positive values and back at negative ones. The body is a
	 * solid ([surfaceDepth]) bowing in three dimensions and seen in perspective from in front of it: the
	 * pelvis tilts a share of the lean ([pelvisShare]) about the hip joints, carrying the skirt with it, and
	 * the upper body the rest about the waist, eased in across it. Leaning in, the chest foreshortens and
	 * comes down, a little nearer and larger, and the skirt below the hips swings
	 * back a little; leaning back, the other way. The head rides the neck upright, keeping its shape, and
	 * the arms hang apart ([armPoint]).
	 */
	fun leanPoint(x: Double, y: Double, lean: Float, size: Float = 0f): DoubleArray {
		val p = shaped(x, y, lean, size)
		return doubleArrayOf(p[0], p[1])
	}

	/**
	 * Where canvas point ([x], [y]) of an arm hanging from the shoulder at canvas x [shoulderX] goes at
	 * [lean], [size] and [bodyX]: it hangs straight down whatever the body does, so it moves with its
	 * shoulder as one piece, drawn as much larger as the shoulder is - and shorter as the body is on a chibi -
	 * instead of bending with the body it hangs beside. On Body X the shoulder goes where the turning torso
	 * takes it ([bodyPoint]) and the arm swings a little the other way about it ([armSwing]), the hand
	 * trailing the body as artists key the arms, never drawn larger or lower with the near side.
	 */
	fun armPoint(x: Double, y: Double, shoulderX: Double, lean: Float, size: Float = 0f, bodyX: Float = 0f): DoubleArray {
		val shoulderY = torso.shoulderY.toDouble()
		val shoulder = shaped(shoulderX, shoulderY, lean, size)
		val k = shoulder[2] * limbSize(size)
		val dx = (x - shoulderX) * k
		val dy = (y - shoulderY) * k
		if (bodyX == 0f) return doubleArrayOf(shoulder[0] + dx, shoulder[1] + dy)
		val anchor = bodyPoint(shoulder[0], shoulder[1], bodyX, 0f)
		val swing = Math.toRadians(armSwing(bodyX))
		return doubleArrayOf(anchor[0] + dx * cos(swing) - dy * sin(swing), anchor[1] + dx * sin(swing) + dy * cos(swing))
	}

	/**
	 * Degrees an arm swings about its shoulder at [bodyX], clockwise on the canvas: against the body's turn,
	 * so the hand trails it.
	 */
	fun armSwing(bodyX: Float): Double = armSwingDegrees * bodyX / 10.0 * strength

	/** How much larger the body is drawn at canvas height [y] on its centre line at [lean] and [size]. */
	fun leanScale(y: Double, lean: Float, size: Float = 0f): Double =
		shaped(torso.centerX.toDouble(), y, lean, size)[2]

	/**
	 * How much larger a limb hanging from the body at canvas height [y] is drawn at [lean] and [size]: as the
	 * head where it hangs from the head, else as the arms ([armPoint]).
	 */
	fun limbScale(y: Double, lean: Float, size: Float = 0f): Double {
		val k = leanScale(y, lean, size)
		return if (y < neckY - torso.length * HEAD_BAND) k else k * limbSize(size)
	}

	/** Where the neck turns into the head, canvas y: the head above it is drawn as one rigid piece. */
	private val neckY: Double get() = torso.shoulderY - torso.length * NECK_RISE

	/** ([x], [y]) at [lean] and [size]: its canvas x and y and how much larger it is drawn. */
	private fun shaped(x: Double, y: Double, lean: Float, size: Float): DoubleArray {
		val proportion = (size / 10.0).coerceIn(-1.0, 1.0)
		val pitch = lean / 10.0 * strength
		val p = if (proportion == 0.0) doubleArrayOf(x, y, 1.0) else sized(x, y, proportion)
		if (pitch == 0.0) return p
		val q = leaned(p[0], p[1], pitch)
		return doubleArrayOf(q[0], q[1], p[2] * q[2])
	}

	/**
	 * ([x], [y]) in the proportions of [proportion] (-1..1): toward a chibi at positive values - the head
	 * larger ([headGrow]), the chest wider ([chestGrow]) over a shorter torso ([bodyShrink]), and the
	 * legs and the skirt shorter ([legShrink], see [shortened]), carrying everything above them down - and
	 * toward a taller figure at negative ones. The head grows from the neck as one piece, so it still sits
	 * on the shoulders, and the feet stay where they are.
	 */
	private fun sized(x: Double, y: Double, proportion: Double): DoubleArray {
		val length = torso.length.toDouble()
		val waist = torso.waistY.toDouble()
		val head = 1 + headGrow * proportion
		val chest = 1 + chestGrow * proportion
		val body = 1 - bodyShrink * proportion
		// Heights above the waist are drawn at a rate easing from 1 to the body's across the waist and from
		// the body's to the head's across the neck; the drawn height is that rate summed up from below.
		val h = waist - y
		val waistBand = length * WAIST_BAND
		val neck = waist - neckY
		val headBand = length * HEAD_BAND
		val drawn = (body - 1) * 2 * waistBand * ramp((h + waistBand) / (2 * waistBand)) +
			(head - body) * headBand * ramp((h - neck) / headBand)
		// Widths grow from the waist to the chest's at the shoulders, and to the head's across the neck.
		val k = 1 + (chest - 1) * smooth(h / length) + (head - chest) * smooth((h - neck) / headBand)
		val cx = torso.centerX.toDouble()
		val lower = if (y >= waist) shortened(y, proportion) else y + shortened(waist, proportion) - waist
		return doubleArrayOf(cx + (x - cx) * k, lower - drawn, k)
	}

	/**
	 * Canvas height [y] with the legs drawn in the proportions of [proportion] (-1..1): everything between
	 * the ankles and the waist drawn [legShrink] shorter toward a chibi, or longer toward a taller figure,
	 * the feet below the ankles where they are. Without legs nothing changes.
	 */
	private fun shortened(y: Double, proportion: Double): Double {
		if (!standing || y >= ankleY) return y
		return ankleY - (ankleY - y) * (1 - legShrink * proportion)
	}

	/** Where the legs end in the feet, canvas y: the ankles' mean height. */
	private val ankleY: Double get() = legs.map { it.ankleY }.average()

	/**
	 * Where canvas point ([x], [y]), already put by the legs' pose, goes with the legs drawn at [size]
	 * (-10..10): shorter toward a chibi, their tops coming down with the body ([leanPoint]), the feet still.
	 */
	fun legsAt(p: DoubleArray, size: Float): DoubleArray {
		val proportion = (size / 10.0).coerceIn(-1.0, 1.0)
		if (proportion == 0.0) return p
		return doubleArrayOf(p[0], shortened(p[1], proportion))
	}

	/** How much larger an arm is drawn at [size]: as much shorter as the torso. */
	private fun limbSize(size: Float): Double = 1 - bodyShrink * (size / 10.0).coerceIn(-1.0, 1.0)

	/** ([x], [y]) on the body pitched by [pitch] (-1..1 times the strength): its canvas x and y and how much larger it is drawn. */
	private fun leaned(x: Double, y: Double, pitch: Double): DoubleArray {
		val band = torso.length * HEAD_BAND
		val up = smooth((neckY - y) / band)
		if (up <= 0.0) return bowed(x, y, pitch)
		// The head rides the neck: moved and scaled as the neck is, never foreshortened with the chest.
		val cx = torso.centerX.toDouble()
		val neck = bowed(cx, neckY, pitch)
		val k = neck[2]
		val rigid = doubleArrayOf(neck[0] + (x - cx) * k, neck[1] - (neckY - y) * k, k)
		if (up >= 1.0) return rigid
		val body = bowed(x, y, pitch)
		return DoubleArray(3) { body[it] + (rigid[it] - body[it]) * up }
	}

	/**
	 * ([x], [y]) on the body bowed by [pitch] as a solid: its canvas x and y and how much larger it is drawn.
	 * The spine bends along an arc: the pelvis's share of the lean below the waist, the full lean above it,
	 * easing from one to the other across the waist, so the waist curves rather than folding.
	 */
	private fun bowed(x: Double, y: Double, pitch: Double): DoubleArray {
		val theta = Math.toRadians((if (pitch >= 0.0) leanDegrees else leanBackDegrees) * pitch)
		val length = torso.length.toDouble()
		val waist = torso.waistY.toDouble()
		val pivot = maxOf(hipY, waist)
		val z0 = surfaceDepth(x, y)
		val pelvis = theta * pelvisShare
		val band = length * BEND_BAND
		val bend = pivot - waist
		// The spine's pitch at height [s] above the pivot, toward the viewer.
		fun angle(s: Double) = pelvis + (theta - pelvis) * smooth((s - bend + band) / (2 * band))
		// Walk the spine up from the pivot to the point's height: up is -y, near is +z.
		val h = pivot - y
		val step = h / SPINE_STEPS
		var up = 0.0
		var near = 0.0
		for (i in 0 until SPINE_STEPS) {
			val a = angle((i + 0.5) * step)
			up += cos(a) * step
			near += sin(a) * step
		}
		// The surface stands off the spine along its normal, as deep as the solid is there. The spine bends
		// halfway to the front, through the soft belly, so the front shortens about as the back lengthens.
		val axis = torso.halfWidth * YAW_RADIUS * torsoDepth * BEND_AXIS
		val a = angle(h)
		val wy = pivot - (up - (z0 - axis) * sin(a))
		val wz = axis + near + (z0 - axis) * cos(a)
		val camera = leanCamera * length
		val cameraY = torso.shoulderY - length * CAMERA_HEIGHT
		val k = (camera - z0) / (camera - wz)
		val cx = torso.centerX.toDouble()
		return doubleArrayOf(cx + (x - cx) * k, cameraY + (wy - cameraY) * k, k)
	}

	/**
	 * How far the drawn surface at canvas ([x], [y]) stands toward the viewer from the body's centre plane:
	 * the torso's cross-section is an ellipse [torsoDepth] as deep as it is wide, narrowing to the neck
	 * above the shoulders; past its sides, nothing.
	 */
	private fun surfaceDepth(x: Double, y: Double): Double {
		val length = torso.length.toDouble()
		val radius = torso.halfWidth * YAW_RADIUS
		val u = (x - torso.centerX) / radius
		if (abs(u) >= 1.0) return 0.0
		val neck = smooth((torso.shoulderY + length * 0.05 - y) / (length * 0.2))
		return radius * (torsoDepth + (NECK_DEPTH - torsoDepth) * neck) * sqrt(1 - u * u)
	}

	companion object {
		/** The legs' root warp, and the lean's warp under the body warp. */
		val legsWarpId = DeformerId("DeformLegs")
		val leanWarpId = DeformerId("DeformBodyLean")

		/** The pelvis's tilt up over the standing leg in the weight pose, degrees. */
		const val HIP_TILT = 1.5

		/** The turning torso's half width over its half width at the chest, and the neck's depth over it. */
		private const val YAW_RADIUS = 1.1
		private const val NECK_DEPTH = 0.15

		/** How far above the shoulders the viewer is, in torso lengths. */
		private const val CAMERA_HEIGHT = 0.5

		/** How far above the shoulders the head's rigid piece begins, and the band it eases in over, in torso lengths. */
		private const val NECK_RISE = 0.08
		private const val HEAD_BAND = 0.1

		/**
		 * Half width of the band about the waist where the spine bends from the pelvis's pitch to the full
		 * lean, in torso lengths: the front of the body, inside the bend, shortens as fast as it turns.
		 */
		private const val BEND_BAND = 0.6

		/** How far toward the front of the torso the spine bends, as a share of its depth at the chest. */
		private const val BEND_AXIS = 0.5

		/** Steps the bowing spine is walked in. */
		private const val SPINE_STEPS = 24

		/** Half width of the band about the waist where the upper body's lean eases in, in torso lengths. */
		private const val WAIST_BAND = 0.35

		/** Bands where the pelvis hands over to the thigh, the thigh to the shin and the shin to the foot, in leg lengths. */
		private const val HIP_BAND = 0.05
		private const val LEG_ROW_SPACING = 0.06
		private const val KNEE_BAND = 0.07
		private const val ANKLE_BAND = 0.025

		/** The two legs hand over across this share of the gap between their lines. */
		private const val LEG_SEAM = 0.6

		private fun smooth(t: Double): Double {
			val c = t.coerceIn(0.0, 1.0)
			return c * c * (3 - 2 * c)
		}

		/** A height over the waist eased in across [band]: nothing below the waist, the full lever above the band. */
		/** The integral of [smooth] from 0 to [t]: 0 below 0, then the eased ramp, then t - 1/2 past 1. */
		private fun ramp(t: Double): Double = when {
			t <= 0.0 -> 0.0
			t >= 1.0 -> t - 0.5
			else -> t * t * t - t * t * t * t / 2
		}

		private fun lever(above: Double, band: Double): Double = when {
			above <= -band -> 0.0
			above >= band -> above
			else -> (above + band) * (above + band) / (4 * band)
		}
	}
}
