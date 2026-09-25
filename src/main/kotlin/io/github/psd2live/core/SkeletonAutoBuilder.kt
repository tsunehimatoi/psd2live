package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot

/**
 * Proposes a humanoid skeleton from the See-Through tags. Joint positions come from the canvas-space
 * vertices of the tagged meshes: a limb's root is the mesh region nearest its attachment on the torso,
 * its tip is the farthest region, and the joints in between sit on the mesh's distance-binned
 * centerline. Elbows and knees inside a single mesh are only a first guess the user is expected to drag.
 */
object SkeletonAutoBuilder {
	/** Tags the skeleton takes out of the shared breath warp. */
	val limbTags = setOf(SemanticTag.HANDWEAR, SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR, SemanticTag.TAIL, SemanticTag.WINGS)

	private class Limb(val drawableIds: List<String>, val points: FloatArray)

	fun build(analysis: PipelineAnalysis, rig: BuiltRig): SkeletonSpec =
		build(analysis, rig.puppet, rig.layerIdByDrawableId, canvasVertices(rig.puppet))

	internal fun canvasVertices(puppet: PuppetModel): Map<String, FloatArray> =
		CpuDeformationEvaluator().evaluate(puppet.copy(glues = emptyList()), emptyMap()).worldPositions
			.mapKeys { it.key.raw }
			.mapValues { (_, world) -> FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }

	internal fun build(
		analysis: PipelineAnalysis,
		puppet: PuppetModel,
		layerIdByDrawableId: Map<String, String>,
		verticesByDrawable: Map<String, FloatArray>,
	): SkeletonSpec {
		val layerById = analysis.layers.associateBy { it.source.id.raw }
		val anchors = analysis.anchors
		val entries = puppet.drawables.mapNotNull { drawable ->
			val layer = layerById[layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: return@mapNotNull null
			val points = verticesByDrawable[drawable.id.raw]?.takeIf { it.size >= 6 } ?: return@mapNotNull null
			if (layer.semantic.type != LayerType.PRESET) return@mapNotNull null
			Triple(drawable.id.raw, layer, points)
		}
		fun tagged(vararg tags: SemanticTag) = entries.filter { it.second.semantic.tag in tags }

		val torsoLayers = tagged(SemanticTag.TOPWEAR)
		val torso = torsoLayers.map { it.second.bounds }.reduceOrNull(Bounds::union) ?: anchors.body
		val centerX = torso.centerX
		val shoulderY = anchors.shoulderY
		val hipY = anchors.hipY.coerceAtLeast(shoulderY + 1f)

		val bones = mutableListOf<SkeletonBone>()
		fun anchor(id: String, parent: String?, role: BoneRole, hx: Float, hy: Float, tx: Float, ty: Float, meshes: List<String>) {
			bones += SkeletonBone(id, SkeletonNames.bone(role, Side.NONE), parent, role, Side.NONE, hx, hy, tx, ty, meshes)
		}
		anchor("root", null, BoneRole.ROOT, centerX, hipY + (hipY - shoulderY) * 0.15f, centerX, hipY, emptyList())
		anchor("hip", "root", BoneRole.HIP, centerX, hipY, centerX, hipY + (hipY - shoulderY) * 0.35f,
			tagged(SemanticTag.BOTTOMWEAR).map { it.first })
		anchor("chest", "root", BoneRole.CHEST, centerX, hipY, centerX, shoulderY,
			tagged(SemanticTag.TOPWEAR, SemanticTag.NECKWEAR).map { it.first })
		anchor("neck", "chest", BoneRole.NECK, centerX, shoulderY, anchors.chinX, anchors.chinY, tagged(SemanticTag.NECK).map { it.first })
		anchor("head", "neck", BoneRole.HEAD, anchors.chinX, anchors.chinY, anchors.faceCenterX, anchors.face.top, emptyList())

		fun sideOf(layer: ClassifiedLayer, x: Float): Side = when {
			// Cubism convention: L is the character's left, which faces the viewer's right.
			layer.semantic.side != Side.NONE -> layer.semantic.side
			x > centerX -> Side.LEFT
			x < centerX -> Side.RIGHT
			else -> Side.LEFT
		}
		fun direction(x: Float) = if (x < centerX) 1f else -1f
		fun limbsBySide(tag: SemanticTag): Map<Side, Limb> = tagged(tag)
			.groupBy { (_, layer, points) -> sideOf(layer, meanX(points)) }
			.mapValues { (_, list) -> Limb(list.map { it.first }, concat(list.map { it.third })) }
		fun meshesBySide(tag: SemanticTag): Map<Side, List<Pair<String, FloatArray>>> = tagged(tag)
			.groupBy { (_, layer, points) -> sideOf(layer, meanX(points)) }
			.mapValues { (_, list) -> list.map { it.first to it.third } }

		// Arms: shoulder on the torso edge at shoulder height.
		for ((side, meshes) in meshesBySide(SemanticTag.HANDWEAR)) {
			val all = concat(meshes.map { it.second })
			val edgeX = if (side == Side.LEFT) torso.right - torso.width * 0.18f else torso.left + torso.width * 0.18f
			val chain = chain(all, edgeX, shoulderY + (hipY - shoulderY) * 0.05f) ?: continue
			val s = side.name.first().lowercase()
			val assigned = assign(meshes, chain)
			val elbowT = assigned.startOf(1) ?: 0.5f
			val wristT = assigned.startOf(2) ?: 0.85f
			val root = chain.at(0f)
			val elbow = chain.at(elbowT)
			val wrist = chain.at(wristT.coerceAtLeast(elbowT + 0.05f))
			val tip = chain.at(1f)
			val dir = direction(root.first)
			bones += SkeletonBone("arm_upper_$s", SkeletonNames.bone(BoneRole.UPPER_ARM, side), "chest", BoneRole.UPPER_ARM, side,
				root.first, root.second, elbow.first, elbow.second, assigned.meshes(0), direction = dir)
			bones += SkeletonBone("arm_fore_$s", SkeletonNames.bone(BoneRole.FOREARM, side), "arm_upper_$s", BoneRole.FOREARM, side,
				elbow.first, elbow.second, wrist.first, wrist.second, assigned.meshes(1), direction = dir)
			bones += SkeletonBone("hand_$s", SkeletonNames.bone(BoneRole.HAND, side), "arm_fore_$s", BoneRole.HAND, side,
				wrist.first, wrist.second, tip.first, tip.second, assigned.meshes(2), direction = dir)
		}

		// Legs: root under the hip line, foot as its own bone when the shoes are separate meshes.
		val feet = limbsBySide(SemanticTag.FOOTWEAR)
		val legs = meshesBySide(SemanticTag.LEGWEAR)
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val s = side.name.first().lowercase()
			val legMeshes = legs[side]
			var parent = "hip"
			var ankle: Pair<Float, Float>? = null
			if (legMeshes != null) {
				val all = concat(legMeshes.map { it.second })
				val chain = chain(all, meanX(all), hipY) ?: continue
				val assigned = assign(legMeshes, chain, segments = 2)
				val kneeT = assigned.startOf(1) ?: 0.5f
				val root = chain.at(0f)
				val knee = chain.at(kneeT)
				val tip = chain.at(1f)
				val dir = direction(root.first)
				bones += SkeletonBone("leg_upper_$s", SkeletonNames.bone(BoneRole.THIGH, side), "hip", BoneRole.THIGH, side,
					root.first, root.second, knee.first, knee.second, assigned.meshes(0), direction = dir)
				bones += SkeletonBone("leg_lower_$s", SkeletonNames.bone(BoneRole.SHIN, side), "leg_upper_$s", BoneRole.SHIN, side,
					knee.first, knee.second, tip.first, tip.second, assigned.meshes(1), direction = dir)
				parent = "leg_lower_$s"
				ankle = tip
			}
			val foot = feet[side] ?: continue
			val from = ankle ?: (meanX(foot.points) to hipY)
			val chain = chain(foot.points, from.first, from.second) ?: continue
			val root = ankle?.let { a -> nearestOnChain(chain, a) } ?: chain.at(0f)
			val tip = chain.at(1f)
			bones += SkeletonBone("foot_$s", SkeletonNames.bone(BoneRole.FOOT, side), parent, BoneRole.FOOT, side,
				root.first, root.second, tip.first, tip.second, foot.drawableIds, direction = direction(root.first))
		}

		// Tail: a chain rooted at the hip; one rotation at the root, path bends along the rest.
		val tails = tagged(SemanticTag.TAIL)
		if (tails.isNotEmpty()) {
			val all = concat(tails.map { it.third })
			chain(all, centerX, hipY)?.let { chain ->
				val count = if (chain.length > (hipY - shoulderY) * 1.2f) 4 else 3
				var parent = "hip"
				for (i in 1..count) {
					val head = chain.at((i - 1) / count.toFloat())
					val tail = chain.at(i / count.toFloat())
					val meshes = if (i == 1) tails.map { it.first } else emptyList()
					bones += SkeletonBone("tail_$i", SkeletonNames.bone(BoneRole.TAIL, Side.NONE, i), parent, BoneRole.TAIL, Side.NONE,
						head.first, head.second, tail.first, tail.second, meshes, chainIndex = i, direction = 1f)
					parent = "tail_$i"
				}
			}
		}

		// Wings: one bone per side, rooted between the shoulder blades.
		for ((side, wing) in limbsBySide(SemanticTag.WINGS)) {
			val s = side.name.first().lowercase()
			val chain = chain(wing.points, centerX, shoulderY + (hipY - shoulderY) * 0.25f) ?: continue
			val root = chain.at(0f)
			val tip = chain.at(1f)
			bones += SkeletonBone("wing_$s", SkeletonNames.bone(BoneRole.WING, side), "chest", BoneRole.WING, side,
				root.first, root.second, tip.first, tip.second, wing.drawableIds, direction = direction(root.first))
		}
		return SkeletonSpec(enabled = true, bones = bones)
	}

	/**
	 * A limb centerline: vertices binned by their distance from the root, each bin averaged. Robust to
	 * curved limbs, and exact enough for a first guess the user refines.
	 */
	internal class Chain(val root: Pair<Float, Float>, val points: List<Pair<Float, Float>>, val length: Float) {
		fun at(t: Float): Pair<Float, Float> {
			if (points.size == 1) return points[0]
			val f = t.coerceIn(0f, 1f) * (points.size - 1)
			val i = f.toInt().coerceAtMost(points.size - 2)
			val w = f - i
			return (points[i].first + (points[i + 1].first - points[i].first) * w) to
				(points[i].second + (points[i + 1].second - points[i].second) * w)
		}
	}

	internal fun chain(points: FloatArray, attachX: Float, attachY: Float, bins: Int = 12): Chain? {
		val count = points.size / 2
		if (count < 3) return null
		val byAttach = (0 until count).sortedBy { hypot(points[it * 2] - attachX, points[it * 2 + 1] - attachY) }
		val nearCount = (count * 0.08f).toInt().coerceAtLeast(1)
		val root = mean(points, byAttach.take(nearCount))
		val distance = FloatArray(count) { hypot(points[it * 2] - root.first, points[it * 2 + 1] - root.second) }
		val byRoot = (0 until count).sortedByDescending { distance[it] }
		val tip = mean(points, byRoot.take((count * 0.05f).toInt().coerceAtLeast(1)))
		val length = hypot(tip.first - root.first, tip.second - root.second)
		if (length < 1f) return null
		val sums = Array(bins) { FloatArray(3) }
		for (i in 0 until count) {
			val bin = ((distance[i] / length) * bins).toInt().coerceIn(0, bins - 1)
			sums[bin][0] += points[i * 2]; sums[bin][1] += points[i * 2 + 1]; sums[bin][2] += 1f
		}
		val line = mutableListOf(root)
		for (bin in sums) if (bin[2] > 0f) line += (bin[0] / bin[2]) to (bin[1] / bin[2])
		line += tip
		return Chain(root, line, length)
	}

	/** Meshes of one limb split into proximal-to-distal segments by where along the chain they lie. */
	private class Assignment(private val segments: List<MutableList<Pair<String, Float>>>) {
		fun meshes(index: Int): List<String> = segments.getOrNull(index).orEmpty().map { it.first }
		/** Where the first mesh of [index] starts along the chain, or null when that segment has none. */
		fun startOf(index: Int): Float? = segments.getOrNull(index)?.minOfOrNull { it.second }
	}

	private fun assign(meshes: List<Pair<String, FloatArray>>, chain: Chain, segments: Int = 3): Assignment {
		val out = List(segments) { mutableListOf<Pair<String, Float>>() }
		if (meshes.size == 1) {
			out[0] += meshes[0].first to 0f
			return Assignment(out)
		}
		for ((id, points) in meshes) {
			var min = Float.MAX_VALUE
			var max = -Float.MAX_VALUE
			for (i in 0 until points.size / 2) {
				val d = hypot(points[i * 2] - chain.root.first, points[i * 2 + 1] - chain.root.second) / chain.length
				min = minOf(min, d); max = maxOf(max, d)
			}
			val index = when {
				max - min > 0.7f -> 0
				segments == 2 -> if ((min + max) * 0.5f < 0.5f) 0 else 1
				(min + max) * 0.5f < 0.45f -> 0
				(min + max) * 0.5f < 0.8f -> 1
				else -> 2
			}
			out[index] += id to min.coerceIn(0f, 1f)
		}
		return Assignment(out)
	}

	private fun nearestOnChain(chain: Chain, p: Pair<Float, Float>): Pair<Float, Float> =
		chain.points.minBy { hypot(it.first - p.first, it.second - p.second) }

	private fun mean(points: FloatArray, indices: List<Int>): Pair<Float, Float> {
		var x = 0f; var y = 0f
		for (i in indices) { x += points[i * 2]; y += points[i * 2 + 1] }
		return (x / indices.size) to (y / indices.size)
	}

	private fun meanX(points: FloatArray): Float {
		var x = 0f
		for (i in 0 until points.size / 2) x += points[i * 2]
		return x / (points.size / 2).coerceAtLeast(1)
	}

	private fun concat(arrays: List<FloatArray>): FloatArray {
		val out = FloatArray(arrays.sumOf { it.size })
		var offset = 0
		for (a in arrays) { a.copyInto(out, offset); offset += a.size }
		return out
	}
}
