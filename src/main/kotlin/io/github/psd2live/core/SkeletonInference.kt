package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The straight line a limb runs along, fitted to the opaque pixels of one layer.
 *
 * A limb's bounding box does not say where its joints are — a diagonal arm fills a box whose corners
 * are both empty. The principal axis does: it is the direction the pixels actually extend in, so its
 * two ends are the limb's ends and a fraction along it is a point inside the limb rather than beside
 * it. [halfWidth] is the spread across that axis, which sets the deform path's width.
 */
data class LimbAxis(
	val centerX: Float,
	val centerY: Float,
	/** Unit vector along the limb's long direction. */
	val dirX: Float,
	val dirY: Float,
	val minT: Float,
	val maxT: Float,
	val halfWidth: Float,
) {
	val startX: Float get() = centerX + dirX * minT
	val startY: Float get() = centerY + dirY * minT
	val endX: Float get() = centerX + dirX * maxT
	val endY: Float get() = centerY + dirY * maxT
	val length: Float get() = maxT - minT

	/** The point [fraction] of the way from [startX]/[startY] to [endX]/[endY]. */
	fun pointAt(fraction: Float): Pair<Float, Float> {
		val t = minT + (maxT - minT) * fraction
		return (centerX + dirX * t) to (centerY + dirY * t)
	}

	/** This axis with its ends swapped, so [startX]/[startY] is the end nearer ([x], [y]). */
	fun orientedTowards(x: Float, y: Float): LimbAxis {
		val startDistance = hypot(startX - x, startY - y)
		val endDistance = hypot(endX - x, endY - y)
		if (startDistance <= endDistance) return this
		return copy(dirX = -dirX, dirY = -dirY, minT = -maxT, maxT = -minT)
	}
}

/**
 * Derives an armature from a classified PSD, using the layer preset as the only source of anatomy.
 *
 * The inference answers two questions per limb, and nothing else is guessed. *Where* a joint is comes
 * from the limb's principal axis, so a shoulder lands on the seam between the arm and the torso
 * rather than on a bounding-box corner. *What kind* of joint it is comes from how the art was cut: a
 * seam between two ArtMeshes can pivot, so it becomes a rotation deformer, while a joint inside one
 * unbroken mesh has to curve it, so it becomes a deform path with a corner control point the artist
 * can drag to the real elbow.
 *
 * See-Through's layer vocabulary is the floor this works from: arms under `handwear`, legs under
 * `legwear`/`footwear`, tail under `tail`. A PSD naming nothing recognizable yields a spine-only
 * skeleton rather than a wrong one.
 */
object SkeletonInference {
	/** Layers that stay on the torso: the body and breath warps keep deforming these. */
	private val torsoTags = setOf(
		SemanticTag.TOPWEAR,
		SemanticTag.BOTTOMWEAR,
		SemanticTag.NECK,
		SemanticTag.NECKWEAR,
	)

	private val armTags = setOf(SemanticTag.HANDWEAR)
	private val legTags = setOf(SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR)

	/** A limb slimmer than this fraction of the character's height is a prop, not a bone chain. */
	private const val MIN_LIMB_LENGTH_FRACTION = 0.06f

	/**
	 * Where a single-mesh limb's bend point is guessed, as a fraction of the limb's length. Halfway is
	 * the honest answer for an elbow the pixels cannot locate, and the canvas session exists so the
	 * artist can drag it to the real one.
	 */
	private const val GUESSED_BEND_FRACTION = 0.48f

	fun infer(analysis: PipelineAnalysis, alphaThreshold: Int = 8): Skeleton {
		val anchors = analysis.anchors
		val character = anchors.character
		val layers = analysis.layers.filter { it.opaquePixels > 0 }
		val bones = mutableListOf<SkeletonBone>()

		// 1. The spine: four joints stacked from the ground up, each with one job. Every other chain
		// hangs from the hip or the chest, so the spine is built first even when nothing else resolves.
		val hipY = anchors.hipY.coerceIn(character.top, character.bottom)
		val shoulderY = anchors.shoulderY.coerceIn(character.top, hipY)
		val waistY = (hipY + shoulderY) * 0.5f
		val centerX = character.centerX
		val neckY = min(shoulderY, anchors.chinY)

		bones += SkeletonBone(
			id = "Root",
			parentId = null,
			chain = BoneChain.SPINE,
			side = Side.NONE,
			segment = 0,
			label = tr("skeleton.bone.root"),
			pivotX = centerX,
			pivotY = character.bottom,
			tipX = centerX,
			tipY = hipY,
			layerIds = emptyList(),
			binding = BoneBinding.ROTATION,
			drive = BoneDrive(
				parameterId = SkeletonParameters.CROUCH,
				label = tr("skeleton.parameter.crouch"),
				min = 0f,
				max = 1f,
				default = 0f,
				shiftY = character.height * 0.018f,
				scale = 0.994f,
			),
		)
		bones += SkeletonBone(
			id = "Hip",
			parentId = "Root",
			chain = BoneChain.SPINE,
			side = Side.NONE,
			segment = 1,
			label = tr("skeleton.bone.hip"),
			pivotX = centerX,
			pivotY = hipY,
			tipX = centerX,
			tipY = waistY,
			layerIds = emptyList(),
			binding = BoneBinding.ROTATION,
			drive = BoneDrive(
				parameterId = SkeletonParameters.WEIGHT,
				label = tr("skeleton.parameter.weight"),
				min = -1f,
				max = 1f,
				default = 0f,
				angle = -1.1f,
				shiftX = character.width * 0.011f,
			),
		)
		bones += SkeletonBone(
			id = "Waist",
			parentId = "Hip",
			chain = BoneChain.SPINE,
			side = Side.NONE,
			segment = 2,
			label = tr("skeleton.bone.waist"),
			pivotX = centerX,
			pivotY = waistY,
			tipX = centerX,
			tipY = shoulderY,
			layerIds = emptyList(),
			binding = BoneBinding.ROTATION,
			drive = BoneDrive(
				parameterId = SkeletonParameters.LEAN,
				label = tr("skeleton.parameter.lean"),
				min = -1f,
				max = 1f,
				default = 0f,
				angle = 2.6f,
			),
		)
		bones += SkeletonBone(
			id = "Chest",
			parentId = "Waist",
			chain = BoneChain.SPINE,
			side = Side.NONE,
			segment = 3,
			label = tr("skeleton.bone.chest"),
			pivotX = centerX,
			pivotY = shoulderY,
			tipX = centerX,
			tipY = neckY,
			layerIds = emptyList(),
			binding = BoneBinding.ROTATION,
			// The chest only anchors: the torso's own breathing is still the body warp's job, and giving
			// this joint a swing of its own would double up on it.
			drive = null,
		)

		val minLength = character.height * MIN_LIMB_LENGTH_FRACTION

		// 2. Arms, from the chest. Each side is its own chain even when the PSD cut only one of them.
		for (side in listOf(Side.RIGHT, Side.LEFT)) {
			bones += limbChain(
				layers = sided(layers, armTags, side, centerX),
				chain = BoneChain.ARM,
				side = side,
				parentBoneId = "Chest",
				attachX = centerX,
				attachY = shoulderY,
				alphaThreshold = alphaThreshold,
				minLength = minLength,
			)
		}

		// 3. Legs, from the hip.
		for (side in listOf(Side.RIGHT, Side.LEFT)) {
			bones += limbChain(
				layers = sided(layers, legTags, side, centerX),
				chain = BoneChain.LEG,
				side = side,
				parentBoneId = "Hip",
				attachX = centerX,
				attachY = hipY,
				alphaThreshold = alphaThreshold,
				minLength = minLength,
			)
		}

		// 4. Tail and wings: extras that read as limbs and swing like them.
		bones += limbChain(
			layers = layers.filter { it.semantic.tag == SemanticTag.TAIL },
			chain = BoneChain.TAIL,
			side = Side.NONE,
			parentBoneId = "Hip",
			attachX = centerX,
			attachY = hipY,
			alphaThreshold = alphaThreshold,
			minLength = minLength,
		)
		for (side in listOf(Side.RIGHT, Side.LEFT)) {
			bones += limbChain(
				layers = sided(layers, setOf(SemanticTag.WINGS), side, centerX),
				chain = BoneChain.WING,
				side = side,
				parentBoneId = "Chest",
				attachX = centerX,
				attachY = shoulderY,
				alphaThreshold = alphaThreshold,
				minLength = minLength,
			)
		}

		return Skeleton(bones)
	}

	/** Every layer of [tags] belonging to [side], with an unsided layer placed by its centroid. */
	private fun sided(
		layers: List<ClassifiedLayer>,
		tags: Set<SemanticTag>,
		side: Side,
		centerX: Float,
	): List<ClassifiedLayer> = layers.filter { layer ->
		if (layer.semantic.tag !in tags) return@filter false
		// Viewer-left is the character's right, matching ComponentSplitter's own split.
		val effective = when (layer.semantic.side) {
			Side.NONE -> if (layer.centroidX < centerX) Side.RIGHT else Side.LEFT
			else -> layer.semantic.side
		}
		effective == side
	}

	/**
	 * Builds one limb's bones from the layers that make it up.
	 *
	 * Two or more layers means the artist already cut the limb at its joints, so each seam becomes a
	 * rotation deformer nested under the one above it. One layer means the limb is unbroken, so it gets
	 * a rotation at the joint it shares with the torso — that seam is real — and a deform path for the
	 * bend inside it, which no rotation can express.
	 */
	private fun limbChain(
		layers: List<ClassifiedLayer>,
		chain: BoneChain,
		side: Side,
		parentBoneId: String,
		attachX: Float,
		attachY: Float,
		alphaThreshold: Int,
		minLength: Float,
	): List<SkeletonBone> {
		if (layers.isEmpty()) return emptyList()
		val measured = layers.mapNotNull { layer ->
			val axis = principalAxis(layer, alphaThreshold)?.orientedTowards(attachX, attachY) ?: return@mapNotNull null
			if (axis.length < minLength) null else layer to axis
		}
		if (measured.isEmpty()) return emptyList()

		// Proximal first: how far a segment's near end sits from the joint it all hangs off.
		val ordered = measured.sortedBy { (_, axis) -> hypot(axis.startX - attachX, axis.startY - attachY) }
		val prefix = chainPrefix(chain, side)
		val bones = mutableListOf<SkeletonBone>()

		if (ordered.size == 1) {
			val (layer, axis) = ordered.single()
			val bendPoint = axis.pointAt(GUESSED_BEND_FRACTION)
			bones += SkeletonBone(
				id = "$prefix" + "A",
				parentId = parentBoneId,
				chain = chain,
				side = side,
				segment = 0,
				label = segmentLabel(chain, side, 0),
				pivotX = axis.startX,
				pivotY = axis.startY,
				tipX = axis.endX,
				tipY = axis.endY,
				layerIds = listOf(layer.source.id.raw),
				binding = BoneBinding.ROTATION,
				drive = drive(chain, side, 0),
			)
			// A bend inside one mesh: the path curves the art, and the corner point starts at a guess.
			bones += SkeletonBone(
				id = "$prefix" + "B",
				parentId = "$prefix" + "A",
				chain = chain,
				side = side,
				segment = 1,
				label = segmentLabel(chain, side, 1),
				pivotX = bendPoint.first,
				pivotY = bendPoint.second,
				tipX = axis.endX,
				tipY = axis.endY,
				layerIds = listOf(layer.source.id.raw),
				binding = BoneBinding.PATH,
				drive = drive(chain, side, 1),
			)
			return bones
		}

		for ((index, entry) in ordered.withIndex()) {
			val (layer, axis) = entry
			val segmentId = "$prefix" + ('A' + index)
			bones += SkeletonBone(
				id = segmentId,
				parentId = if (index == 0) parentBoneId else "$prefix" + ('A' + index - 1),
				chain = chain,
				side = side,
				segment = index,
				label = segmentLabel(chain, side, index),
				pivotX = axis.startX,
				pivotY = axis.startY,
				tipX = axis.endX,
				tipY = axis.endY,
				layerIds = listOf(layer.source.id.raw),
				binding = BoneBinding.ROTATION,
				drive = drive(chain, side, index),
			)
		}
		return bones
	}

	private fun chainPrefix(chain: BoneChain, side: Side): String = when (chain) {
		BoneChain.ARM -> "Arm${SkeletonParameters.sideTag(side)}"
		BoneChain.LEG -> "Leg${SkeletonParameters.sideTag(side)}"
		BoneChain.TAIL -> "Tail"
		BoneChain.WING -> "Wing${SkeletonParameters.sideTag(side)}"
		BoneChain.SPINE -> "Spine"
	}

	private fun segmentLabel(chain: BoneChain, side: Side, segment: Int): String {
		val sideKey = when (side) {
			Side.LEFT -> "skeleton.side.left"
			Side.RIGHT -> "skeleton.side.right"
			Side.NONE -> null
		}
		val base = when (chain) {
			BoneChain.ARM -> tr(if (segment == 0) "skeleton.bone.armUpper" else if (segment == 1) "skeleton.bone.armLower" else "skeleton.bone.hand")
			BoneChain.LEG -> tr(if (segment == 0) "skeleton.bone.thigh" else if (segment == 1) "skeleton.bone.shin" else "skeleton.bone.foot")
			BoneChain.TAIL -> tr("skeleton.bone.tail", segment + 1)
			BoneChain.WING -> tr("skeleton.bone.wing")
			BoneChain.SPINE -> tr("skeleton.bone.spine")
		}
		return if (sideKey == null) base else "$base ${tr(sideKey)}"
	}

	/**
	 * How far a joint swings at its parameter's extreme. Distal joints move less than the ones they
	 * hang from, which is what keeps a chain reading as a limb rather than as a whip.
	 */
	private fun drive(chain: BoneChain, side: Side, segment: Int): BoneDrive {
		val parameterId = when (chain) {
			BoneChain.ARM -> SkeletonParameters.arm(side, segment)
			BoneChain.LEG -> SkeletonParameters.leg(side, segment)
			BoneChain.TAIL -> SkeletonParameters.tail(segment)
			BoneChain.WING -> SkeletonParameters.wing(side)
			BoneChain.SPINE -> SkeletonParameters.LEAN
		}
		val angle = when (chain) {
			BoneChain.ARM -> max(2.5f, 5.5f - segment * 1.4f)
			BoneChain.LEG -> max(1.2f, 2.6f - segment * 0.7f)
			BoneChain.TAIL -> max(4f, 9f - segment * 1.6f)
			BoneChain.WING -> 6f
			BoneChain.SPINE -> 2.6f
		}
		return BoneDrive(
			parameterId = parameterId,
			label = "${segmentLabel(chain, side, segment)} ${tr("skeleton.parameter.swing")}",
			min = -1f,
			max = 1f,
			default = 0f,
			angle = angle,
		)
	}

	/**
	 * The principal axis of [layer]'s opaque pixels, or null when they are too few or too round to
	 * have a direction.
	 *
	 * This is a two-pass PCA: the first pass finds the alpha-weighted centroid, the second the
	 * covariance about it, whose major eigenvector is the limb's direction. The closed form for a
	 * symmetric 2x2 matrix is a single `atan2`, so there is no iteration to tune.
	 */
	fun principalAxis(layer: ClassifiedLayer, alphaThreshold: Int): LimbAxis? {
		val raster = layer.source.raster
		if (raster.width <= 0 || raster.height <= 0) return null
		val rgba = raster.rgba
		// Sampling every pixel of a large limb buys nothing: a moment of inertia converges long before
		// the last row. The stride keeps a full-canvas layer around a few thousand samples.
		val stride = max(1, min(raster.width, raster.height) / 72)
		val originX = layer.source.bounds.left.toFloat()
		val originY = layer.source.bounds.top.toFloat()

		var count = 0
		var sumX = 0.0
		var sumY = 0.0
		var y = 0
		while (y < raster.height) {
			var x = 0
			val row = y * raster.width
			while (x < raster.width) {
				if ((rgba[(row + x) * 4 + 3].toInt() and 0xff) >= alphaThreshold) {
					count++
					sumX += x
					sumY += y
				}
				x += stride
			}
			y += stride
		}
		if (count < 12) return null
		val meanX = sumX / count
		val meanY = sumY / count

		var xx = 0.0
		var xy = 0.0
		var yy = 0.0
		y = 0
		while (y < raster.height) {
			var x = 0
			val row = y * raster.width
			while (x < raster.width) {
				if ((rgba[(row + x) * 4 + 3].toInt() and 0xff) >= alphaThreshold) {
					val dx = x - meanX
					val dy = y - meanY
					xx += dx * dx
					xy += dx * dy
					yy += dy * dy
				}
				x += stride
			}
			y += stride
		}
		xx /= count
		xy /= count
		yy /= count
		if (xx + yy < 1e-6) return null

		val theta = 0.5 * atan2(2.0 * xy, xx - yy)
		val dirX = cos(theta).toFloat()
		val dirY = sin(theta).toFloat()

		var minT = Float.MAX_VALUE
		var maxT = -Float.MAX_VALUE
		var minS = Float.MAX_VALUE
		var maxS = -Float.MAX_VALUE
		y = 0
		while (y < raster.height) {
			var x = 0
			val row = y * raster.width
			while (x < raster.width) {
				if ((rgba[(row + x) * 4 + 3].toInt() and 0xff) >= alphaThreshold) {
					val dx = (x - meanX).toFloat()
					val dy = (y - meanY).toFloat()
					val t = dx * dirX + dy * dirY
					val s = -dx * dirY + dy * dirX
					minT = min(minT, t)
					maxT = max(maxT, t)
					minS = min(minS, s)
					maxS = max(maxS, s)
				}
				x += stride
			}
			y += stride
		}
		if (maxT - minT < 1e-3f) return null

		return LimbAxis(
			centerX = originX + meanX.toFloat(),
			centerY = originY + meanY.toFloat(),
			dirX = dirX,
			dirY = dirY,
			minT = minT,
			maxT = maxT,
			halfWidth = max(1f, (maxS - minS) * 0.5f),
		).takeIf { abs(it.length) > abs(it.halfWidth) * 0.8f }
	}

	/** True when the torso keeps [tag]: the body and breath warps still own these layers. */
	internal fun isTorso(tag: SemanticTag): Boolean = tag in torsoTags
}
