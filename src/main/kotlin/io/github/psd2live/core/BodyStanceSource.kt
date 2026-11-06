package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BodyMotionTuning
import io.github.psd2live.algorithms.binding.BodyStance
import io.github.psd2live.algorithms.binding.BodyStance.Leg
import io.github.psd2live.algorithms.binding.TorsoFrame

/** Captures the measurements that the standing motion solver consumes. */
internal object BodyStanceSource {
	/**
	 * The stance of [analysis]. A skeleton's legs place the joints; without one they are read off the
	 * leg and foot layers. A figure with neither stands on nothing and has no legs warp.
	 */
	fun of(analysis: PipelineAnalysis, character: Bounds, skeleton: SkeletonSpec?, strength: Float = 1f,
		tuning: RigTuning = RigTuning()): BodyStance {
		val torso = RigBuilder.torsoFrame(analysis, character, skeleton)
		val legLayers = analysis.layers.filter {
			(it.semantic.tag == SemanticTag.LEGWEAR || it.semantic.tag == SemanticTag.FOOTWEAR) && it.opaquePixels > 0
		}
		val boneLegs = skeleton?.takeIf { it.enabled }?.let { SkeletonRig.legs(it) }.orEmpty()
		return if (boneLegs.isNotEmpty()) of(character, torso, boneLegs, legLayers.map { it.bounds }, strength, tuning)
		else onLegs(character, torso, drawnLegs(analysis, torso, legLayers), legLayers.map { it.bounds }, strength, tuning)
	}

	/** The stance of [spec] alone, its torso and legs placed by its bones. */
	fun of(spec: SkeletonSpec, character: Bounds, strength: Float = 1f, tuning: RigTuning = RigTuning()): BodyStance {
		val upper = spec.bones.firstOrNull { it.role == BoneRole.UPPER_BODY && it.length >= 1f }
		val shoulderY = upper?.let { minOf(it.headY, it.tailY) } ?: (character.top + character.height * 0.25f)
		val waistY = (upper?.let { maxOf(it.headY, it.tailY) } ?: character.centerY).coerceAtLeast(shoulderY + 1f)
		val torso = TorsoFrame(upper?.headX ?: character.centerX, shoulderY, waistY, character.width * 0.2f)
		return of(character, torso, SkeletonRig.legs(spec), emptyList(), strength, tuning)
	}

	/** A stance on a skeleton's [boneLegs], the floor under the lowest of their feet and of the leg [layers]. */
	private fun of(character: Bounds, torso: TorsoFrame, boneLegs: List<SkeletonRig.Leg>, layers: List<Bounds>, strength: Float, tuning: RigTuning): BodyStance {
		val floor = (layers.map { it.bottom.toDouble() } + boneLegs.map { leg ->
			maxOf(leg.ankleY, leg.foot?.let { maxOf(it.headY, it.tailY).toDouble() } ?: leg.ankleY)
		}).maxOrNull() ?: 0.0
		val legs = boneLegs.map { leg ->
			Leg(leg.thigh.side, leg.thigh.headX.toDouble(), leg.thigh.headY.toDouble(), leg.shin.headX.toDouble(),
				leg.shin.headY.toDouble(), leg.ankleX, leg.ankleY, floor.coerceAtLeast(leg.ankleY + 1.0))
		}
		return onLegs(character, torso, legs, layers, strength, tuning)
	}

	/** The legs warp spans [legs] and the leg [layers], padded so the soles and the hips sit inside it. */
	private fun onLegs(character: Bounds, torso: TorsoFrame, legs: List<Leg>, layers: List<Bounds>, strength: Float, tuning: RigTuning): BodyStance {
		if (legs.isEmpty()) return BodyStance(character, torso, emptyList(), null, strength, tuning.bodyMotion())
		val reach = legs.map { it.reach }.average()
		val points = legs.flatMap { listOf(it.hipX to it.hipY, it.kneeX to it.kneeY, it.ankleX to it.floorY) }
		val bounds = (layers + Bounds(
			points.minOf { it.first }.toFloat(), points.minOf { it.second }.toFloat(),
			points.maxOf { it.first }.toFloat(), points.maxOf { it.second }.toFloat(),
		)).reduce(Bounds::union)
		val padX = maxOf(bounds.width * 0.15f, (reach * 0.08).toFloat())
		val padY = (reach * 0.06).toFloat()
		val top = minOf(bounds.top, legs.minOf { it.hipY }.toFloat()) - padY
		val frame = Bounds(bounds.left - padX, top, bounds.right + padX, bounds.bottom + padY)
		return BodyStance(character, torso, legs, frame, strength, tuning.bodyMotion())
	}

	/**
	 * Legs read off the layers: a leg per side when they are drawn apart, else two legs a quarter of the
	 * way in from either edge of a layer that draws both. The hip joints sit just under the hip line,
	 * the ankles at the mouths of the shoes, the knees halfway.
	 */
	private fun drawnLegs(analysis: PipelineAnalysis, torso: TorsoFrame, layers: List<ClassifiedLayer>): List<Leg> {
		val legwear = layers.filter { it.semantic.tag == SemanticTag.LEGWEAR }
		val footwear = layers.filter { it.semantic.tag == SemanticTag.FOOTWEAR }
		val all = (legwear + footwear).map { it.bounds }.reduceOrNull(Bounds::union) ?: return emptyList()
		// Legs must reach down well below the hips to stand on.
		if (all.bottom < torso.waistY + torso.length) return emptyList()
		val centerX = torso.centerX
		val hipY = (analysis.anchors.hipY + torso.length * 0.12f).toDouble()
		val floor = all.bottom.toDouble()
		if (floor - 1.0 <= hipY + 10.0) return emptyList()
		val shoes = footwear.map { it.bounds }.reduceOrNull(Bounds::union)
		val ankleY = (shoes?.let { it.top + it.height * 0.3f }?.toDouble() ?: (floor - (floor - hipY) * 0.08))
			.coerceIn(hipY + 10.0, floor - 1.0)
		fun sided(side: Side) = (legwear + footwear).filter { it.semantic.side == side }.map { it.bounds }.reduceOrNull(Bounds::union)
		val left = sided(Side.LEFT)
		val right = sided(Side.RIGHT)
		val xs: List<Pair<Side, Double>> = if (left != null && right != null) {
			listOf(Side.LEFT to left.centerX.toDouble(), Side.RIGHT to right.centerX.toDouble())
		} else {
			// Cubism convention: the character's left faces the viewer's right.
			listOf(Side.LEFT to (all.right - all.width * 0.25f).toDouble(), Side.RIGHT to (all.left + all.width * 0.25f).toDouble())
		}
		return xs.map { (side, x) ->
			val hipX = x + (centerX - x) * 0.1
			Leg(side, hipX, hipY, (hipX + x) / 2, (hipY + ankleY) / 2, x, ankleY, floor)
		}
	}

}

internal fun RigTuning.bodyMotion() = BodyMotionTuning(
    hipShift = hipShift,
    sink = sink,
    rise = rise,
    kneesIn = kneesIn,
    kneesInRest = kneesInRest,
    turnDegrees = turnDegrees,
    armSwingDegrees = armSwingDegrees,
    turnFade = turnFade,
    torsoDepth = torsoDepth,
    turnCameraDistance = turnCameraDistance,
    leanCameraDistance = leanCameraDistance,
    openShorten = openShorten,
    openWiden = openWiden,
    standStretch = standStretch,
    standNarrow = standNarrow,
    leanDegrees = leanDegrees,
    leanBackDegrees = leanBackDegrees,
    pelvisShare = pelvisShare,
    headGrow = headGrow,
    chestGrow = chestGrow,
    bodyShrink = bodyShrink,
    legShrink = legShrink,
    bodyZDegrees = bodyZDegrees,
    breathLift = breathLift,
    breathWiden = breathWiden,
    faceLeanDown = faceLeanDown,
    faceLeanUp = faceLeanUp,
)
