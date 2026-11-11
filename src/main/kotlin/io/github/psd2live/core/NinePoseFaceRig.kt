package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/** Semantic regions that receive a second, feature-specific pass after the face-surface warp. */
internal enum class FaceFeature {
	EYE,
	IRIS,
	BROW,
	NOSE,
	MOUTH,
	EAR,
}

internal data class FaceRegion(
	val feature: FaceFeature,
	val side: Side,
	val bounds: Bounds,
)

/**
 * Nine-pose (AngleX × AngleY = 3 × 3) face model.
 *
 * This is deliberately not a camera projection.  It implements the hand-rigging decomposition
 * documented by the user: surface position (T), asymmetric volume (V), view-dependent redraw (S),
 * and an explicit corner interaction term Cxy.  The global surface supplies the low-frequency face
 * latitude/longitude motion; child feature deformers add the high-frequency drawing corrections.
 */
internal class NinePoseFaceRig(
	val face: Bounds,
	val centerX: Float,
	val centerY: Float,
	val radiusX: Float,
	val radiusY: Float,
	val eyeLineY: Float,
	val noseLineY: Float,
	val mouthLineY: Float,
	val chinX: Float,
	val chinY: Float,
	val regions: List<FaceRegion>,
	val coordinateSpace: HeadCoordinateSpace = HeadCoordinateSpace.Identity,
	/** How far the turn and the tilt move the face and each feature. */
	val tuning: RigTuning = RigTuning(),
) {
	val initialAngleZ: Float get() = coordinateSpace.angleDegrees

	companion object {
		val angleXKeys = floatArrayOf(-45f, 0f, 45f)
		val angleYKeys = floatArrayOf(-30f, 0f, 30f)

		fun from(analysis: PipelineAnalysis, tuning: RigTuning = RigTuning()): NinePoseFaceRig {
			val initialAngleZ = HeadOrientationEstimator.estimate(analysis.layers, analysis.anchors.face)
			val coordinateSpace = HeadCoordinateSpace(
				initialAngleZ,
				analysis.anchors.faceCenterX,
				analysis.anchors.faceCenterY,
			)
			val nonEmpty = analysis.layers.filter { it.opaquePixels > 0 }.map { layer ->
				val center = coordinateSpace.toAligned(layer.centroidX, layer.centroidY)
				layer.copy(
					bounds = coordinateSpace.boundsToAligned(layer.bounds),
					centroidX = center.first,
					centroidY = center.second,
				)
			}
			val face = coordinateSpace.boundsToAligned(analysis.anchors.face)

			fun unionOrNull(layers: List<ClassifiedLayer>): Bounds? =
				layers.map { it.bounds }.takeIf { it.isNotEmpty() }?.reduce(Bounds::union)

			fun groupedRegions(feature: FaceFeature, layers: List<ClassifiedLayer>, padding: Float): List<FaceRegion> =
				layers.groupBy { it.semantic.side }.map { (side, grouped) ->
					FaceRegion(feature, side, grouped.map { it.bounds }.reduce(Bounds::union).expanded(padding))
				}

			val irisLayers = nonEmpty.filter { it.semantic.tag == SemanticTag.IRIDES }
			val eyeBaseLayers = nonEmpty.filter {
				it.semantic.tag in setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
			}
			val eyeSeed = if (eyeBaseLayers.isNotEmpty()) eyeBaseLayers else irisLayers
			val baseEyeRegions = eyeSeed.groupBy { it.semantic.side }.map { (side, grouped) ->
				val sameSideIris = irisLayers.filter { iris ->
					iris.semantic.side == side || (side == Side.NONE && eyeSeed.none { it.semantic.side != Side.NONE })
				}
				val bounds = (grouped + sameSideIris).distinctBy { it.source.id.raw }.map { it.bounds }.reduce(Bounds::union)
				FaceRegion(FaceFeature.EYE, side, bounds.expanded(0.18f))
			}
			val irisRegions = groupedRegions(FaceFeature.IRIS, irisLayers, 0.28f)
			// An unsided iris still needs a real eye frame if the user skips naming/splitting.
			// regionFor() can synthesize this union, but a synthesized-only frame leaves the
			// iris warp pointing at a deformer that neither the native nor shared scaffold emits.
			val eyeRegions = if (irisRegions.any { it.side == Side.NONE } &&
				baseEyeRegions.isNotEmpty() && baseEyeRegions.none { it.side == Side.NONE }) {
				baseEyeRegions + FaceRegion(FaceFeature.EYE, Side.NONE, baseEyeRegions.map { it.bounds }.reduce(Bounds::union))
			} else baseEyeRegions
			val browRegions = groupedRegions(
				FaceFeature.BROW,
				nonEmpty.filter { it.semantic.tag == SemanticTag.EYEBROW },
				0.22f,
			)
			val noseRegions = groupedRegions(
				FaceFeature.NOSE,
				nonEmpty.filter { it.semantic.tag == SemanticTag.NOSE },
				0.30f,
			)
			val mouthRegions = groupedRegions(
				FaceFeature.MOUTH,
				nonEmpty.filter { it.semantic.tag in CharacterAnalyzer.MOUTH_TAGS },
				0.22f,
			)
			val earRegions = groupedRegions(
				FaceFeature.EAR,
				nonEmpty.filter { it.semantic.tag == SemanticTag.EARS || it.semantic.tag == SemanticTag.EARWEAR },
				0.12f,
			)
			val regions = eyeRegions + irisRegions + browRegions + noseRegions + mouthRegions + earRegions

			val pairedEyes = eyeRegions.filter { it.side != Side.NONE }
			val eyeBounds = unionOrNull(eyeSeed + irisLayers)
			val eyeCenterX = when {
				pairedEyes.size >= 2 -> pairedEyes.map { it.bounds.centerX }.average().toFloat()
				eyeBounds != null -> eyeBounds.centerX
				else -> coordinateSpace.toAligned(analysis.anchors.faceCenterX, analysis.anchors.faceCenterY).first
			}
			val eyeLineY = eyeBounds?.centerY ?: (face.top + face.height * 0.38f)
			val noseBounds = unionOrNull(nonEmpty.filter { it.semantic.tag == SemanticTag.NOSE })
			val mouthBounds = unionOrNull(nonEmpty.filter { it.semantic.tag in CharacterAnalyzer.MOUTH_TAGS })
			val mouthLineY = mouthBounds?.centerY ?: (face.top + face.height * 0.70f)
			val noseLineY = noseBounds?.centerY ?: (eyeLineY + (mouthLineY - eyeLineY) * 0.52f)
			// A hand-rigged face turns around its eye/nose cage, not the alpha centroid of the skin.
			val centerX = when {
				noseBounds != null -> eyeCenterX * 0.72f + noseBounds.centerX * 0.28f
				mouthBounds != null -> eyeCenterX * 0.82f + mouthBounds.centerX * 0.18f
				else -> eyeCenterX
			}.coerceIn(face.left + face.width * 0.30f, face.right - face.width * 0.30f)
			val centerY = (eyeLineY + (mouthLineY - eyeLineY) * 0.38f)
				.coerceIn(face.top + face.height * 0.30f, face.top + face.height * 0.58f)
			val radiusX = max(centerX - face.left, face.right - centerX).coerceAtLeast(1f)
			val radiusY = max(centerY - face.top, face.bottom - centerY).coerceAtLeast(1f)
			val chinX = when {
				mouthBounds != null -> centerX * 0.68f + mouthBounds.centerX * 0.32f
				else -> centerX
			}
			return NinePoseFaceRig(
				face = face,
				centerX = centerX,
				centerY = centerY,
				radiusX = radiusX,
				radiusY = radiusY,
				eyeLineY = eyeLineY,
				noseLineY = noseLineY,
				mouthLineY = mouthLineY,
				chinX = chinX,
				chinY = face.bottom,
				regions = regions,
				coordinateSpace = coordinateSpace,
				tuning = tuning,
			)
		}
	}

	fun regionFor(feature: FaceFeature, side: Side): FaceRegion? {
		val candidates = regions.filter { it.feature == feature }
		return candidates.firstOrNull { it.side == side }
			?: candidates.firstOrNull { it.side == Side.NONE }
			?: if (side == Side.NONE && candidates.isNotEmpty()) {
				FaceRegion(feature, Side.NONE, candidates.map { it.bounds }.reduce(Bounds::union))
			} else null
	}

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
		feature: FaceFeature,
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
			FaceFeature.EYE -> {
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

			FaceFeature.IRIS -> {
				// Iris/pupil preserves its graphic symbol more strongly than the eye white and receives a
				// small counter-shift so it does not look glued to a shrinking white.
				val compensationScale = (if (farSide) tuning.irisKeepWidth else tuning.irisKeepWidth / 5f) / 100f * absYaw
				val dx = (u - 0.5f) * region.width * compensationScale - direction * region.width * absYaw * tuning.irisShift / 100f
				// The iris is parented to the eye plane and must not apply that shear a second time.
				val dy = -pitch * region.height * tuning.irisTilt / 100f
				dx to dy
			}

			FaceFeature.BROW -> {
				// Brows tolerate a little more perspective than eyes but share the exact same projective
				// plane, keeping each brow and the inter-brow line parallel in diagonal poses.
				val scale = if (farSide) 1f - absYaw * tuning.browFarShrink / 100f else 1f + absYaw * tuning.browNearGrow / 100f
				val dx = direction * absYaw * radiusX * tuning.browShift / 100f + (u - 0.5f) * region.width * (scale - 1f)
				var dy = perspectiveSlope * ((pointX + dx) - centerX)
				dy += -pitch * region.height * (tuning.browTilt / 100f - arch * 0.025f)
				dx to dy
			}

			FaceFeature.NOSE -> {
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

			FaceFeature.MOUTH -> {
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

			FaceFeature.EAR -> {
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
}
