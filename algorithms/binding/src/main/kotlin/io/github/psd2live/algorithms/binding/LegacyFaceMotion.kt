package io.github.psd2live.algorithms.binding

import kotlin.math.*

/** Tuning read by the legacy face, including inherited eye, brow, mouth, hair and body settings. */
data class LegacyFaceTuning(
    val faceTurn: Float = 11.2f,
    val faceTurnBase: Float = 2f,
    val faceTilt: Float = 6.2f,
    val faceLift: Float = 1.8f,
    val faceArch: Float = 4f,
    val faceCorner: Float = 100f,
    val faceOutsideFollow: Float = 1.8f,
    val yawEase: Float = 32f,
    val pitchEase: Float = 22f,
    val featurePlane: Float = 5f,
    val headShellTurn: Float = 0.9f,
    val headShellCrown: Float = 0.4f,
    val headShellTilt: Float = 0.8f,
    val faceContour: Float = 1.8f,
    val faceLeanDown: Float = 12f,
    val faceLeanUp: Float = 8f,
    val displacementShift: Float = 5.5f,
    val displacementNarrow: Float = 15f,
    val displacementTilt: Float = 5f,
    val displacementTwist: Float = 3f,
    val eyeFarShrink: Float = 8.5f,
    val eyeNearGrow: Float = 1.2f,
    val eyeShift: Float = 2f,
    val eyeTilt: Float = 8f,
    val eyeLidCurve: Float = 3f,
    val eyeTiltStretch: Float = 7f,
    val irisShift: Float = 5.5f,
    val irisKeepWidth: Float = 5f,
    val irisTilt: Float = 3.5f,
    val browFarShrink: Float = 10.5f,
    val browNearGrow: Float = 1.8f,
    val browShift: Float = 1.8f,
    val browTilt: Float = 10.5f,
    val mouthShift: Float = 5.8f,
    val mouthCornerLag: Float = 8.5f,
    val mouthNearLag: Float = 3.5f,
    val mouthTurnArch: Float = 6f,
    val mouthTilt: Float = 13f,
    val noseShift: Float = 10.5f,
    val noseTilt: Float = 10f,
    val earFade: Float = 48f,
    val earFarShrink: Float = 28f,
    val earShift: Float = 3.5f,
    val frontHairTurn: Float = 2f,
    val frontHairTilt: Float = 0.6f,
    val frontHairPerspective: Float = 10f,
    val backHairTurn: Float = 1.8f,
    val backHairTilt: Float = -0.4f,
    val hairRollHold: Float = 80f,
)

class LegacyFaceMotion(
    private val centerX: Float,
    private val centerY: Float,
    private val radiusX: Float,
    private val radiusY: Float,
    private val tuning: LegacyFaceTuning,
) {
	/** Low-frequency facial surface: horizontal/vertical budget redistribution plus Cxy. */
	fun surfacePoint(canvasX: Float, canvasY: Float, angleX: Float, angleY: Float, strength: Float): Pair<Float, Float> {
		if (angleX == 0f && angleY == 0f) return canvasX to canvasY
		val yaw = artisticYaw(angleX, strength)
		val pitch = artisticPitch(angleY, strength)
		val absYaw = abs(yaw)
		val direction = if (yaw == 0f) 0f else sign(yaw)
		val x = (canvasX - centerX) / radiusX
		val y = (canvasY - centerY) / radiusY
		val surfaceX = x.coerceIn(-1f, 1f)
		val surfaceY = y.coerceIn(-1.2f, 1.2f)
		val orientedX = surfaceX * direction
		val lowerFace = ((y + 0.15f) / 1.15f).coerceIn(0f, 1f)
		val horizontalArch = max(0f, 1f - surfaceX * surfaceX).toDouble().pow(1.30).toFloat()
		val verticalArch = max(0f, 1f - (surfaceY * 0.72f) * (surfaceY * 0.72f))

		// Continuous curve roll with a deliberately broad near-side plateau.  Eye identity is very
		// sensitive to width, so the face surface mostly TRANSLATES the near eye instead of stretching
		// it.  The far half descends continuously and supplies the visible perspective compression.
		// Feature deformers then add their own, much smaller, eye/brow/mouth-specific width changes.
		val rollCurve = (tuning.faceTurnBase + perspectiveRollProfile(orientedX) * tuning.faceTurn) / 100f
		var shiftX = yaw * radiusX * rollCurve * (0.90f + verticalArch * 0.10f)

		// Positive AngleY looks up: ^ curvature plus increased latitude spacing.  Negative AngleY
		// looks down: V curvature plus concentration of eye/nose/mouth/chin bands.
		var shiftY = -pitch * radiusY * tuning.faceLift / 100f
		shiftY += pitch * surfaceY * radiusY * tuning.faceTilt / 100f * (0.78f + horizontalArch * 0.22f)
		shiftY += -pitch * horizontalArch * radiusY * tuning.faceArch / 100f

		// The four corners are authored poses, not X+Y.  This interaction stabilizes the far cheek and
		// skews the V/^ so it follows the new near/far relationship instead of staying symmetric.
		val interaction = yaw * pitch * tuning.faceCorner / 100f
		shiftX += interaction * radiusX * horizontalArch * (0.012f + lowerFace * 0.018f)
		shiftY += interaction * radiusY * surfaceX * horizontalArch * 0.028f
		shiftY += -absYaw * pitch * tuning.faceCorner / 100f * radiusY * horizontalArch * (0.007f + lowerFace * 0.008f)

		// Hair and other head layers outside the skin silhouette follow the pose, but do not inherit the
		// full cheek/jaw sculpt.  Blend toward a rigid center shift rather than letting them tear away.
		val outsideX = max(0f, abs(x) - 1f)
		val outsideY = max(0f, abs(y) - 1f)
		val surfaceInfluence = (1f - outsideX * 0.62f - outsideY * 0.38f).coerceIn(0.24f, 1f)
		val rigidX = yaw * radiusX * tuning.faceOutsideFollow / 100f
		val rigidY = -pitch * radiusY * tuning.faceOutsideFollow / 100f
		shiftX = shiftX * surfaceInfluence + rigidX * (1f - surfaceInfluence)
		shiftY = shiftY * surfaceInfluence + rigidY * (1f - surfaceInfluence)
		return (canvasX + shiftX) to (canvasY + shiftY)
	}

	/** High-frequency per-feature correction in pixels, evaluated at one local lattice point. */
	fun featureOffset(
		feature: ScaffoldHead.Feature,
		region: Bounds,
		u: Float,
		v: Float,
		angleX: Float,
		angleY: Float,
		strength: Float,
	): Pair<Float, Float> {
		if (angleX == 0f && angleY == 0f) return 0f to 0f
		val yaw = artisticYaw(angleX, strength)
		val pitch = artisticPitch(angleY, strength)
		val absYaw = abs(yaw)
		val direction = if (yaw == 0f) 0f else sign(yaw)
		val pointX = region.left + u * region.width
		val sideSign = when {
			pointX < centerX - radiusX * 0.025f -> -1f
			pointX > centerX + radiusX * 0.025f -> 1f
			else -> if (region.centerX < centerX) -1f else 1f
		}
		// If the nose rolls toward +X, the -X half is the near side and +X is the far side.
		// The previous comparison was reversed, making the near eye smaller.
		val farSide = direction != 0f && sideSign * direction > 0f
		val centeredU = (u - 0.5f) * 2f
		val arch = max(0f, 1f - centeredU * centeredU)
		// A single signed projective plane is shared by both eyes and both brows.  It is exactly zero
		// on the AngleY=0 row.  In the four corners its sign flips with either X or Y, so the line
		// joining both feature centres stays parallel to every feature's left/right envelope edge.
		val perspectiveSlope = yaw * pitch * tuning.featurePlane / 100f

		return when (feature) {
			ScaffoldHead.Feature.EYE -> {
				// Eye-specific curve: the near eye retains almost all of its authored width; the far eye
				// becomes smaller by a restrained uniform envelope scale.  Keeping the scale independent
				// of pitch prevents the four diagonal poses from reversing near/far perspective.
				val yawShape = absYaw.toDouble().pow(1.35).toFloat()
				val widthScale = if (farSide) {
					1f - tuning.eyeFarShrink / 100f * yawShape
				} else {
					1f + tuning.eyeNearGrow / 100f * yawShape
				}
				val dx = direction * absYaw * radiusX * tuning.eyeShift / 100f + (u - 0.5f) * region.width * (widthScale - 1f)
				var dy = perspectiveSlope * ((pointX + dx) - centerX)
				// The envelope corners remain on the plane; only interior lid rows receive curved redraw.
				dy += -pitch * region.height * (tuning.eyeTilt + arch * tuning.eyeLidCurve) / 100f
				dy += pitch * (v - 0.5f) * region.height * tuning.eyeTiltStretch / 100f
				dx to dy
			}

			ScaffoldHead.Feature.IRIS -> {
				// Iris/pupil preserves its graphic symbol more strongly than the eye white and receives a
				// small counter-shift so it does not look glued to a shrinking white.
				val compensationScale = (if (farSide) tuning.irisKeepWidth else tuning.irisKeepWidth / 5f) / 100f * absYaw
				val dx = (u - 0.5f) * region.width * compensationScale - direction * region.width * absYaw * tuning.irisShift / 100f
				// The iris is parented to the eye plane and must not apply that shear a second time.
				val dy = -pitch * region.height * tuning.irisTilt / 100f
				dx to dy
			}

			ScaffoldHead.Feature.BROW -> {
				// Brows tolerate a little more perspective than eyes but share the exact same projective
				// plane, keeping each brow and the inter-brow line parallel in diagonal poses.
				val scale = if (farSide) 1f - absYaw * tuning.browFarShrink / 100f else 1f + absYaw * tuning.browNearGrow / 100f
				val dx = direction * absYaw * radiusX * tuning.browShift / 100f + (u - 0.5f) * region.width * (scale - 1f)
				var dy = perspectiveSlope * ((pointX + dx) - centerX)
				dy += -pitch * region.height * (tuning.browTilt / 100f - arch * 0.025f)
				dx to dy
			}

			ScaffoldHead.Feature.NOSE -> {
				// Nose is the depth ruler: root < bridge < tip, so it outruns mouth/chin and rotates as
				// a drawn symbol instead of translating as one bitmap.
				val depthByLatitude = 0.58f + v * 0.52f
				var dx = direction * absYaw * radiusX * tuning.noseShift / 100f * depthByLatitude
				dx += direction * absYaw * region.width * arch * (v - 0.28f) * 0.055f
				var dy = -pitch * region.height * (tuning.noseTilt / 100f + v * 0.19f)
				dy += absYaw * absYaw * region.height * arch * 0.045f
				dx += yaw * pitch * radiusX * (0.018f + v * 0.014f)
				dy += -absYaw * pitch * region.height * v * 0.055f
				dx to dy
			}

			ScaffoldHead.Feature.MOUTH -> {
				// A mouth is a line on a cylinder.  Its center follows the face quickly, the near corner
				// lags a little (expanding that segment), and the far corner lags much more (compression).
				val orientedU = centeredU * direction
				val farWeight = ((orientedU + 1f) * 0.5f).coerceIn(0f, 1f)
				val cornerLag = abs(centeredU).toDouble().pow(1.35).toFloat() * (tuning.mouthNearLag + farWeight * (tuning.mouthCornerLag - tuning.mouthNearLag)) / 100f
				var dx = direction * absYaw * radiusX * tuning.mouthShift / 100f - direction * absYaw * region.width * cornerLag
				dx += yaw * pitch * region.width * (0.028f + centeredU * 0.018f)
				var dy = perspectiveSlope * ((pointX + dx) - centerX)
				dy += arch * region.height * absYaw * tuning.mouthTurnArch / 100f
				dy += -pitch * region.height * (tuning.mouthTilt / 100f + v * 0.12f)
				dy += -absYaw * pitch * arch * region.height * 0.075f
				dx to dy
			}

			ScaffoldHead.Feature.EAR -> {
				// Negative perceived depth: ears lag behind the face surface.  Far-ear opacity is handled
				// on the deformer channel; geometry also narrows and retreats toward the silhouette.
				val scale = if (farSide) 1f - absYaw * tuning.earFarShrink / 100f else 1f + absYaw * 0.025f
				val dx = -direction * absYaw * radiusX * tuning.earShift / 100f + (u - 0.5f) * region.width * (scale - 1f)
				val dy = -pitch * region.height * 0.055f + yaw * pitch * centeredU * region.height * 0.022f
				dx to dy
			}
		}
	}

	fun earOpacity(region: Bounds, angleX: Float, strength: Float): Float {
		val yaw = artisticYaw(angleX, strength)
		if (yaw == 0f || abs(region.centerX - centerX) < radiusX * 0.08f) return 1f
		val farSide = sign(region.centerX - centerX) * sign(yaw) > 0f
		return if (farSide) (1f - abs(yaw) * tuning.earFade / 100f).coerceIn(0f, 1f) else 1f
	}

	private fun artisticYaw(angleX: Float, strength: Float): Float =
		(tanh(angleX / tuning.yawEase) * strength.coerceIn(0f, 4f)).coerceIn(-4.6f, 4.6f)

	private fun artisticPitch(angleY: Float, strength: Float): Float =
		(tanh(angleY / tuning.pitchEase) * strength.coerceIn(0f, 4f)).coerceIn(-4.4f, 4.4f)

	/** C1-continuous roll: short near-contour reveal, broad identity plateau, long far compression. */
	private fun perspectiveRollProfile(orientedX: Float): Float {
		val x = orientedX.coerceIn(-1f, 1f)
		val nearPlateau = -0.72f
		val farFall = 0.08f
		return when {
			x < nearPlateau -> smoothStep((x + 1f) / (nearPlateau + 1f))
			x <= farFall -> 1f
			else -> 1f - smoothStep((x - farFall) / (1f - farFall))
		}
	}

	private fun smoothStep(value: Float): Float {
		val t = value.coerceIn(0f, 1f)
		return t * t * (3f - 2f * t)
	}

    companion object {
		fun headContainerPoint(
			head: Bounds,
			originX: Float,
			originY: Float,
			u: Float,
			v: Float,
			angleX: Float,
			angleY: Float,
			strength: Float,
			tuning: LegacyFaceTuning = LegacyFaceTuning(),
		): Pair<Float, Float> {
			val boundedStrength = strength.coerceIn(0f, 2f)
			val canvasX = head.left + u * head.width
			val canvasY = head.top + v * head.height
			val yaw = angleX / 45f * boundedStrength
			val pitch = angleY / 30f * boundedStrength
			val crownArch = sin(PI * u).toFloat().coerceAtLeast(0f)
			val shellX = yaw * head.width * (tuning.headShellTurn + crownArch * tuning.headShellCrown) / 100f
			val shellY = -pitch * head.height * tuning.headShellTilt / 100f
			return (canvasX - originX + shellX) to (canvasY - originY + shellY)
		}

		fun faceContourPoint(
			u: Float, v: Float, angleX: Float, strength: Float, socketY: Float, tuning: LegacyFaceTuning = LegacyFaceTuning(),
		): Pair<Float, Float> {
			val turn = (-angleX / 45f * strength).coerceIn(0f, 1f)
			val distance = (abs(v - socketY) / 0.18f).coerceIn(0f, 1f)
			val horizontal = (u / 0.35f).coerceIn(0f, 1f)
			// Two joined cubic Bezier segments have zero tangent at the socket and support edges.
			val socket = cubic(1f, 1f, 0f, 0f, distance)
			val edge = cubic(1f, 1f, 0f, 0f, horizontal)
			return (u + turn * tuning.faceContour / 100f * socket * edge) to v
		}

		fun featureDisplacementPoint(
			u: Float, v: Float, angleX: Float, angleY: Float, strength: Float,
			tuning: LegacyFaceTuning = LegacyFaceTuning(),
			aspectRatio: Float = 1f,
		): Pair<Float, Float> {
			val yaw = (angleX / 45f * strength).coerceIn(-1f, 1f)
			val pitch = (angleY / 30f * strength).coerceIn(-1f, 1f)
			// Cubic Bezier with endpoints 0 and handles 4/3 peaks at 1 at t=1/2.
			fun bow(t: Float): Float = cubic(0f, 4f / 3f, 4f / 3f, 0f, t)
			val x = 0.5f + (u - 0.5f) * (1f - tuning.displacementNarrow / 100f * abs(yaw)) +
				yaw * (0.025f + tuning.displacementShift / 100f * bow(v))
			// Up: compress the whole height down toward the bottom, with extra compression
			// in the upper half. Down: compress only the lower half up toward the middle.
			// The squared half profiles are cubic Beziers with zero slope at their join.
			fun compressedV(value: Float): Float {
				fun halfCompression(t: Float) = cubic(0f, 0f, 1f / 3f, 1f, t)
				return if (pitch > 0f) {
					value + pitch * (0.08f * (1f - value) +
						0.10f * halfCompression((1f - 2f * value).coerceAtLeast(0f)))
				} else {
					value + pitch * 0.10f * halfCompression((2f * value - 1f).coerceAtLeast(0f))
				}
			}
			// Canvas Y grows downwards; negative AngleY is a downward look (U-shaped rows).
			val y = compressedV(v) - pitch * (0.020f + tuning.displacementTilt / 100f * bow(u))
			// In canvas coordinates positive rotation is clockwise. Upper-left/lower-right
			// have yaw*pitch < 0. Rotate the entire curved surface about its displaced center;
			// pure horizontal/vertical poses stay unchanged. Correct for non-square face frames.
			val radians = -yaw * pitch * (tuning.displacementTwist * PI.toFloat() / 180f)
			val centerX = 0.5f + yaw * 0.080f
			val centerY = compressedV(0.5f) - pitch * 0.070f
			val dx = (x - centerX) * aspectRatio
			val dy = y - centerY
			val cosine = cos(radians)
			val sine = sin(radians)
			return (centerX + (dx * cosine - dy * sine) / aspectRatio) to
				(centerY + dx * sine + dy * cosine)
		}

		data class HairRoll(val pivotX: Float, val pivotY: Float, val chinY: Float, val hold: Float) {
			/** The canvas offset that turns ([x], [y]) back for an Angle Z of [angleZ], on hair ending at [bottom]. */
			fun turnBack(x: Float, y: Float, bottom: Float, angleZ: Float): Pair<Float, Float> {
				// Full hold a little below the chin - about half the chin's depth under the pivot, times six - or at the tips if they are nearer.
				val reach = minOf(bottom - chinY, (chinY - pivotY).coerceAtLeast(1f) * 6f).coerceAtLeast(1f)
				val t = ((y - chinY) / reach).coerceIn(0f, 1f)
				val weight = t * t * (3f - 2f * t) * hold
				if (weight <= 0f || angleZ == 0f) return 0f to 0f
				val radians = Math.toRadians((-angleZ * weight).toDouble())
				val cos = kotlin.math.cos(radians).toFloat(); val sin = kotlin.math.sin(radians).toFloat()
				val rx = x - pivotX; val ry = y - pivotY
				return (rx * cos - ry * sin - rx) to (rx * sin + ry * cos - ry)
			}
		}

		fun hairFollowPoint(
			inHead: Bounds,
			u: Float,
			v: Float,
			angleX: Float,
			angleY: Float,
			yawParallax: Float,
			pitchParallax: Float,
			yawPerspective: Float = 0f,
		): Pair<Float, Float> {
			val yaw = angleX / 45f
			val pitch = angleY / 30f
			val yawDepthWeight = 0.62f + v * 0.38f
			// Both endpoints receive the same vertical offset. The middle retains a depth bulge without
			// turning signed pitch into a global height scale.
			val pitchDepthWeight = 0.78f + sin(PI * v).toFloat().coerceAtLeast(0f) * 0.22f
			val uPerspective = u + yaw * yawPerspective * 4f * u * (1f - u)
			return (inHead.left + uPerspective * inHead.width + yaw * yawParallax * yawDepthWeight) to
				(inHead.top + v * inHead.height + pitch * pitchParallax * pitchDepthWeight)
		}


        private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val s = 1f - t
            return s * s * s * p0 + 3f * s * s * t * p1 + 3f * s * t * t * p2 + t * t * t * p3
        }

    }
}
