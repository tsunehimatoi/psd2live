package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.PuppetModel
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Proposes a humanoid skeleton from the layers' semantic tags, the way the head rig is placed from the
 * face: a body frame first (the torso's centre, shoulder and hip lines), then every limb fitted to the
 * meshes tagged for it on its side, with human proportions filling in whatever the drawing does not say.
 *
 * Binding follows the tags. Arms take the handwear of their side, legs the legwear, feet the footwear,
 * and tails and wings their own layers; toggle and switch variants of a part are bound with it but do
 * not steer where its joints go, since an alternate pose would pull the fit toward a limb that is not
 * shown. Limbs are expected to arrive split per side (the import's mesh split does that); a mesh still
 * drawn across both sides cannot follow either side's bones, so it only helps place the joints and is
 * left unbound.
 *
 * Joints follow the limb's medial line measured *through the mesh*: vertices are ordered by their
 * shortest path inside the triangulation from the limb's root, so an arm folded back on itself still
 * reads as one continuous arm. An elbow or knee goes where that line bends most, if it clearly bends
 * within the anatomical range, and at the proportional point otherwise.
 */
object SkeletonAutoBuilder {
	/** Tags the skeleton takes out of the shared breath warp. */
	val limbTags = setOf(SemanticTag.HANDWEAR, SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR, SemanticTag.TAIL, SemanticTag.WINGS)

	/** Upper arm : forearm : hand, shoulder to fingertip. */
	private const val UPPER_ARM = 1.0
	private const val FOREARM = 0.85
	private const val HAND = 0.55

	/** A joint only goes to a bend at least this sharp; a straighter limb uses the proportional point. */
	private const val MIN_BEND_DEGREES = 20.0

	/** One tagged mesh: its drawable, its layer, its rest vertices in canvas pixels and its triangles. */
	internal class Part(val drawableId: String, val layer: ClassifiedLayer, val points: FloatArray, val indices: IntArray) {
		/** Toggle and switch variants are bound but do not place joints. */
		val placesJoints: Boolean get() = layer.semantic.type == LayerType.PRESET
	}

	fun build(analysis: PipelineAnalysis, rig: BuiltRig): SkeletonSpec =
		build(analysis, rig.puppet, rig.layerIdByDrawableId, canvasVertices(rig.puppet))

	internal fun canvasVertices(puppet: PuppetModel): Map<String, FloatArray> =
		CpuDeformationEvaluator().evaluate(puppet.copy(glues = emptyList()), emptyMap()).worldPositions
			.mapKeys { it.key.raw }
			.mapValues { (_, world) -> FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }

	private fun parts(
		analysis: PipelineAnalysis,
		puppet: PuppetModel,
		layerIdByDrawableId: Map<String, String>,
		verticesByDrawable: Map<String, FloatArray>,
	): List<Part> {
		val layerById = analysis.layers.associateBy { it.source.id.raw }
		return puppet.drawables.mapNotNull { drawable ->
			val layer = layerById[layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: return@mapNotNull null
			val points = verticesByDrawable[drawable.id.raw]?.takeIf { it.size >= 6 } ?: return@mapNotNull null
			Part(drawable.id.raw, layer, points, drawable.mesh?.indices ?: IntArray(0))
		}
	}

	/** The torso the limbs hang from: its centre line, shoulder and hip lines, and shoulder width. */
	private class BodyFrame(val centerX: Float, val shoulderY: Float, val hipY: Float, val shoulderHalf: Float, val torso: Bounds) {
		val torsoHeight: Float get() = hipY - shoulderY

		fun sideOf(part: Part): Side = part.layer.semantic.side.takeIf { it != Side.NONE }
			// Cubism convention: L is the character's left, which faces the viewer's right.
			?: if (meanX(part.points) >= centerX) Side.LEFT else Side.RIGHT

		/** A mesh with a real share of its vertices clearly on each side of the centre line. */
		fun straddles(part: Part): Boolean {
			if (part.layer.semantic.side != Side.NONE) return false
			val margin = torso.width * 0.05f
			var left = 0
			var right = 0
			for (i in 0 until part.points.size / 2) {
				val x = part.points[i * 2]
				if (x < centerX - margin) right++ else if (x > centerX + margin) left++
			}
			return minOf(left, right) >= part.points.size / 2 * 0.2f
		}

		/** The vertices of [part] on [side] of the centre line. */
		fun half(part: Part, side: Side): FloatArray = (0 until part.points.size / 2)
			.filter { (part.points[it * 2] >= centerX) == (side == Side.LEFT) }
			.flatMap { listOf(part.points[it * 2], part.points[it * 2 + 1]) }.toFloatArray()

		companion object {
			fun of(analysis: PipelineAnalysis, parts: List<Part>): BodyFrame {
				val anchors = analysis.anchors
				val torsoParts = parts.filter { it.layer.semantic.tag == SemanticTag.TOPWEAR && it.placesJoints }
				val torso = torsoParts.map { it.layer.bounds }.reduceOrNull(Bounds::union) ?: anchors.body
				val shoulderY = anchors.shoulderY
				val hipY = anchors.hipY.coerceAtLeast(shoulderY + 1f)
				// The torso's width just below the shoulder line; the shoulder joints sit inside its edges.
				val band = torsoParts.flatMap { part ->
					(0 until part.points.size / 2).filter { part.points[it * 2 + 1] in shoulderY..(shoulderY + (hipY - shoulderY) * 0.2f) }
						.map { part.points[it * 2] }
				}
				val halfWidth = if (band.size >= 4) (band.max() - band.min()) * 0.5f else torso.width * 0.5f
				return BodyFrame(torso.centerX, shoulderY, hipY, halfWidth * 0.8f, torso)
			}
		}
	}

	/** One side's share of a tag: the meshes to bind, and the geometry the joints are fitted to. */
	private class SideParts(val bind: List<String>, val fit: List<Pair<FloatArray, IntArray>>)

	internal fun build(
		analysis: PipelineAnalysis,
		puppet: PuppetModel,
		layerIdByDrawableId: Map<String, String>,
		verticesByDrawable: Map<String, FloatArray>,
	): SkeletonSpec {
		val anchors = analysis.anchors
		val all = parts(analysis, puppet, layerIdByDrawableId, verticesByDrawable)
		val body = BodyFrame.of(analysis, all)
		val centerX = body.centerX
		val shoulderY = body.shoulderY
		val hipY = body.hipY
		val torsoHeight = body.torsoHeight
		fun tagged(tag: SemanticTag) = all.filter { it.layer.semantic.tag == tag }

		val bones = mutableListOf<SkeletonBone>()
		fun anchor(id: String, parent: String?, role: BoneRole, hx: Float, hy: Float, tx: Float, ty: Float, meshes: List<String>) {
			bones += SkeletonBone(id, SkeletonNames.bone(role, Side.NONE), parent, role, Side.NONE, hx, hy, tx, ty, meshes)
		}
		anchor("root", null, BoneRole.ROOT, centerX, hipY + torsoHeight * 0.15f, centerX, hipY, emptyList())
		anchor("hip", "root", BoneRole.HIP, centerX, hipY, centerX, hipY + torsoHeight * 0.35f,
			tagged(SemanticTag.BOTTOMWEAR).map { it.drawableId })
		anchor("chest", "root", BoneRole.CHEST, centerX, hipY, centerX, shoulderY,
			(tagged(SemanticTag.TOPWEAR) + tagged(SemanticTag.NECKWEAR)).map { it.drawableId })
		anchor("neck", "chest", BoneRole.NECK, centerX, shoulderY, anchors.chinX, anchors.chinY, tagged(SemanticTag.NECK).map { it.drawableId })
		anchor("head", "neck", BoneRole.HEAD, anchors.chinX, anchors.chinY, anchors.faceCenterX, anchors.face.top, emptyList())

		fun direction(x: Float) = if (x < centerX) 1f else -1f

		/** A mesh across both sides gives each side its half of the geometry and binds to neither. */
		fun bySide(tag: SemanticTag): Map<Side, SideParts> {
			val bind = HashMap<Side, MutableList<String>>()
			val fit = HashMap<Side, MutableList<Pair<FloatArray, IntArray>>>()
			val parts = tagged(tag)
			val placing = parts.filter { it.placesJoints }.ifEmpty { parts }
			for (part in parts) {
				if (body.straddles(part)) continue
				bind.getOrPut(body.sideOf(part)) { mutableListOf() } += part.drawableId
			}
			for (part in placing) {
				if (body.straddles(part)) {
					// Halves lose their triangles; the medial line falls back to nearest-neighbour paths.
					for (side in listOf(Side.LEFT, Side.RIGHT)) {
						body.half(part, side).takeIf { it.size >= 6 }?.let { fit.getOrPut(side) { mutableListOf() } += it to IntArray(0) }
					}
				} else {
					fit.getOrPut(body.sideOf(part)) { mutableListOf() } += part.points to part.indices
				}
			}
			return (bind.keys + fit.keys).associateWith { SideParts(bind[it].orEmpty(), fit[it].orEmpty()) }
		}

		// Arms: from the shoulder joint, just inside the torso's edge below the shoulder line.
		for ((side, arm) in bySide(SemanticTag.HANDWEAR)) {
			if (arm.fit.isEmpty()) continue
			val sx = centerX + (if (side == Side.LEFT) 1f else -1f) * body.shoulderHalf
			val sy = shoulderY + torsoHeight * 0.08f
			val line = medial(arm.fit, sx, sy) ?: continue
			val total = UPPER_ARM + FOREARM + HAND
			val tip = line.at(1.0)
			val armPoints = concat(arm.fit.map { it.first })
			// A bent arm has its elbow at the fold's outer corner; a straight one where the proportions say.
			val folded = foldCorner(armPoints, sx, sy, tip.first, tip.second)
			val e = folded ?: line.at(line.jointNear(0.28, 0.62, UPPER_ARM / total))
			val wristShare = FOREARM / (FOREARM + HAND)
			val w = (e.first + (tip.first - e.first) * wristShare).toFloat() to (e.second + (tip.second - e.second) * wristShare).toFloat()
			val s = side.name.first().lowercase()
			val dir = direction(sx)
			bones += SkeletonBone("arm_upper_$s", SkeletonNames.bone(BoneRole.UPPER_ARM, side), "chest", BoneRole.UPPER_ARM, side,
				sx, sy, e.first, e.second, arm.bind, direction = dir)
			bones += SkeletonBone("arm_fore_$s", SkeletonNames.bone(BoneRole.FOREARM, side), "arm_upper_$s", BoneRole.FOREARM, side,
				e.first, e.second, w.first, w.second, direction = dir)
			bones += SkeletonBone("hand_$s", SkeletonNames.bone(BoneRole.HAND, side), "arm_fore_$s", BoneRole.HAND, side,
				w.first, w.second, tip.first, tip.second, direction = dir)
		}

		// Legs: hip joints under the hip line at the top of each leg; the ankle at the mouth of the shoe
		// when the shoes are their own meshes, the knee halfway or where the leg bends.
		val legs = bySide(SemanticTag.LEGWEAR)
		val feet = bySide(SemanticTag.FOOTWEAR)
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val leg = legs[side]?.takeIf { it.fit.isNotEmpty() }
			val foot = feet[side]?.takeIf { it.fit.isNotEmpty() }
			if (leg == null && foot == null) continue
			val s = side.name.first().lowercase()
			var parent = "hip"
			var ankle: Pair<Float, Float>? = null
			val footPoints = foot?.let { concat(it.fit.map { f -> f.first }) }
			// The top of the shoe, where the leg goes in.
			val shoeMouth = footPoints?.let { points ->
				val ys = (0 until points.size / 2).map { points[it * 2 + 1] }
				val top = ys.min()
				val height = ys.max() - top
				mean(points, (0 until points.size / 2).filter { points[it * 2 + 1] <= top + height * 0.3f })
			}
			if (leg != null) {
				val legPoints = concat(leg.fit.map { it.first })
				val ys = (0 until legPoints.size / 2).map { legPoints[it * 2 + 1] }
				val legTop = ys.min()
				val legHeight = ys.max() - legTop
				val topX = mean(legPoints, (0 until legPoints.size / 2).filter { legPoints[it * 2 + 1] <= legTop + legHeight * 0.15f }).first
				// The hip joint is where the thigh turns, just under the hip line - hidden under a skirt when
				// the drawn leg starts lower - and a little inside the top of the drawn leg.
				val hy = hipY + torsoHeight * 0.12f
				val hx = topX + (centerX - topX) * 0.1f
				val line = medial(leg.fit, hx, hy) ?: continue
				val ankleAt = shoeMouth?.let { line.nearest(it.first, it.second) } ?: 0.92
				val a = line.at(ankleAt)
				// Thigh and shin are about the same length: the knee is where hip-to-knee equals knee-to-ankle.
				val even = (0..100).map { ankleAt * it / 100.0 }.minBy { f ->
					val p = line.at(f)
					abs(hypot(p.first - hx, p.second - hy) - hypot(a.first - p.first, a.second - p.second)).toDouble()
				}
				val legPointsAll = concat(leg.fit.map { it.first })
				val k = foldCorner(legPointsAll, hx, hy, a.first, a.second)
					?: line.at(line.jointNear((even - 0.15).coerceAtLeast(0.05), (even + 0.15).coerceAtMost(ankleAt - 0.05), even))
				val dir = direction(hx)
				bones += SkeletonBone("leg_upper_$s", SkeletonNames.bone(BoneRole.THIGH, side), "hip", BoneRole.THIGH, side,
					hx, hy, k.first, k.second, leg.bind, direction = dir)
				bones += SkeletonBone("leg_lower_$s", SkeletonNames.bone(BoneRole.SHIN, side), "leg_upper_$s", BoneRole.SHIN, side,
					k.first, k.second, a.first, a.second, direction = dir)
				parent = "leg_lower_$s"
				ankle = a
				// Legwear that goes on past the ankle already draws the foot.
				if (foot == null && ankleAt < 0.99) {
					val toe = line.at(1.0)
					bones += SkeletonBone("foot_$s", SkeletonNames.bone(BoneRole.FOOT, side), parent, BoneRole.FOOT, side,
						a.first, a.second, toe.first, toe.second, direction = dir)
				}
			}
			if (foot != null && footPoints != null && shoeMouth != null) {
				val from = ankle ?: shoeMouth
				val count = footPoints.size / 2
				val far = (0 until count).sortedByDescending { hypot(footPoints[it * 2] - from.first, footPoints[it * 2 + 1] - from.second) }
				val toe = mean(footPoints, far.take((count * 0.05f).toInt().coerceAtLeast(1)))
				bones += SkeletonBone("foot_$s", SkeletonNames.bone(BoneRole.FOOT, side), parent, BoneRole.FOOT, side,
					from.first, from.second, toe.first, toe.second, foot.bind, direction = direction(from.first))
			}
		}

		// Tail: rooted at the hips, split into equal lengths along its medial line.
		val tails = tagged(SemanticTag.TAIL)
		if (tails.isNotEmpty()) {
			val fit = tails.filter { it.placesJoints }.ifEmpty { tails }.map { it.points to it.indices }
			medial(fit, centerX, hipY)?.let { line ->
				val count = if (line.length > torsoHeight * 1.2f) 4 else 3
				var parent = "hip"
				for (i in 1..count) {
					val head = line.at((i - 1).toDouble() / count)
					val tail = line.at(i.toDouble() / count)
					val meshes = if (i == 1) tails.map { it.drawableId } else emptyList()
					bones += SkeletonBone("tail_$i", SkeletonNames.bone(BoneRole.TAIL, Side.NONE, i), parent, BoneRole.TAIL, Side.NONE,
						head.first, head.second, tail.first, tail.second, meshes, chainIndex = i, direction = 1f)
					parent = "tail_$i"
				}
			}
		}

		// Wings: rooted between the shoulder blades, one bone out to the wing's far edge.
		for ((side, wing) in bySide(SemanticTag.WINGS)) {
			if (wing.fit.isEmpty()) continue
			val rootX = centerX + (if (side == Side.LEFT) 1f else -1f) * body.shoulderHalf * 0.3f
			val rootY = shoulderY + torsoHeight * 0.25f
			val line = medial(wing.fit, rootX, rootY) ?: continue
			val tip = line.at(1.0)
			val s = side.name.first().lowercase()
			bones += SkeletonBone("wing_$s", SkeletonNames.bone(BoneRole.WING, side), "chest", BoneRole.WING, side,
				rootX, rootY, tip.first, tip.second, wing.bind, direction = direction(rootX))
		}
		return SkeletonSpec(enabled = true, bones = bones)
	}

	/**
	 * The joint of a limb bent between root ([rx], [ry]) and end ([ex], [ey]), or null when it is about
	 * straight. The outer corner of a fold is the drawn point that makes the path root-corner-end longest;
	 * the joint sits inside it, at the middle of the limb there. A limb folded back on itself defeats any
	 * line traced through the mesh - the two halves touch - but not this.
	 */
	internal fun foldCorner(points: FloatArray, rx: Float, ry: Float, ex: Float, ey: Float): Pair<Float, Float>? {
		val count = points.size / 2
		if (count < 3) return null
		val straight = hypot(ex - rx, ey - ry)
		if (straight < 1f) return null
		var best = -1
		var bestPath = 0f
		// Near either end the path is long for the wrong reason: the width of a shoulder, or a foot past
		// the ankle. A joint is well inside the limb.
		val clearance = straight * 0.25f
		for (i in 0 until count) {
			val x = points[i * 2]
			val y = points[i * 2 + 1]
			val fromRoot = hypot(x - rx, y - ry)
			val toEnd = hypot(ex - x, ey - y)
			if (fromRoot < clearance || toEnd < clearance) continue
			val path = fromRoot + toEnd
			if (path > bestPath) { bestPath = path; best = i }
		}
		if (best < 0 || bestPath < straight * 1.12f) return null
		val cx = points[best * 2]
		val cy = points[best * 2 + 1]
		// The corner is on the outline; the joint is the middle of the limb around it.
		val radius = bestPath * 0.12f
		val near = (0 until count).filter { hypot(points[it * 2] - cx, points[it * 2 + 1] - cy) <= radius }
		return mean(points, near)
	}

	/**
	 * A limb's medial line: from its root, the mean of the vertices at each distance travelled inside the
	 * mesh. Positions along it are fractions of its arc length.
	 */
	internal class Medial(private val points: List<Pair<Float, Float>>) {
		private val cumulative = DoubleArray(points.size).also { c ->
			for (i in 1 until points.size) {
				c[i] = c[i - 1] + hypot((points[i].first - points[i - 1].first).toDouble(), (points[i].second - points[i - 1].second).toDouble())
			}
		}
		val length: Float get() = cumulative.last().toFloat()

		fun at(fraction: Double): Pair<Float, Float> {
			if (points.size == 1 || cumulative.last() <= 0.0) return points.first()
			val target = fraction.coerceIn(0.0, 1.0) * cumulative.last()
			val i = (1 until points.size).firstOrNull { cumulative[it] >= target } ?: (points.size - 1)
			val span = cumulative[i] - cumulative[i - 1]
			val t = if (span <= 0.0) 0.0 else (target - cumulative[i - 1]) / span
			return (points[i - 1].first + (points[i].first - points[i - 1].first) * t).toFloat() to
				(points[i - 1].second + (points[i].second - points[i - 1].second) * t).toFloat()
		}

		/** The fraction along the line closest to ([x], [y]). */
		fun nearest(x: Float, y: Float): Double = (0..200).map { it / 200.0 }.minBy { f ->
			val p = at(f)
			hypot((p.first - x).toDouble(), (p.second - y).toDouble())
		}

		/**
		 * Where a joint goes between fractions [low] and [high]: at a clear bend of the line there
		 * (at least [MIN_BEND_DEGREES]), otherwise at the proportional point [fallback].
		 *
		 * Bends are weighed by how close they sit to [fallback], so the kink a puffed sleeve makes at the
		 * shoulder does not outbid the elbow where the proportions expect one.
		 */
		fun jointNear(low: Double, high: Double, fallback: Double): Double {
			if (high <= low || cumulative.last() <= 0.0) return fallback.coerceIn(0.0, 1.0)
			// The bend is measured between points an eighth of the limb before and after, so the wobble
			// of the vertex averages does not read as a joint.
			val reach = 0.12
			var best = fallback
			var bestScore = 0.0
			for (step in 0..60) {
				val f = low + (high - low) * step / 60.0
				val a = at(f - reach)
				val b = at(f)
				val c = at(f + reach)
				val ux = (b.first - a.first).toDouble()
				val uy = (b.second - a.second).toDouble()
				val vx = (c.first - b.first).toDouble()
				val vy = (c.second - b.second).toDouble()
				val lu = hypot(ux, uy)
				val lv = hypot(vx, vy)
				if (lu < 1e-6 || lv < 1e-6) continue
				val bend = Math.toDegrees(acos(((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)))
				if (bend < MIN_BEND_DEGREES) continue
				val offset = (f - fallback) / 0.15
				val score = bend * exp(-offset * offset)
				if (score > bestScore) { bestScore = score; best = f }
			}
			return best.coerceIn(0.0, 1.0)
		}
	}

	/**
	 * The medial line of [parts] (vertices and triangles) rooted at ([attachX], [attachY]). Distance is
	 * measured along mesh edges, with the meshes of one limb joined at their closest vertices; points
	 * with no triangles are joined to their nearest neighbours instead.
	 */
	internal fun medial(parts: List<Pair<FloatArray, IntArray>>, attachX: Float, attachY: Float, bins: Int = 16): Medial? {
		val points = concat(parts.map { it.first })
		val count = points.size / 2
		if (count < 3) return null
		val offsets = parts.runningFold(0) { acc, part -> acc + part.first.size / 2 }
		val edges = Array(count) { HashSet<Int>() }
		fun link(a: Int, b: Int) { if (a != b) { edges[a] += b; edges[b] += a } }
		fun d(a: Int, b: Int) = hypot(points[a * 2] - points[b * 2], points[a * 2 + 1] - points[b * 2 + 1]).toDouble()
		parts.forEachIndexed { p, (vertices, indices) ->
			val base = offsets[p]
			val n = vertices.size / 2
			if (indices.size >= 3 && indices.all { it in 0 until n }) {
				for (i in indices.indices step 3) for (k in 0..2) link(base + indices[i + k], base + indices[i + (k + 1) % 3])
			} else {
				for (a in 0 until n) {
					(0 until n).filter { it != a }.sortedBy { d(base + a, base + it) }.take(6).forEach { link(base + a, base + it) }
				}
			}
		}
		for (p in parts.indices) for (q in p + 1 until parts.size) {
			var best = -1 to -1
			var bestDistance = Double.MAX_VALUE
			for (a in offsets[p] until offsets[p + 1]) for (b in offsets[q] until offsets[q + 1]) {
				val distance = d(a, b)
				if (distance < bestDistance) { bestDistance = distance; best = a to b }
			}
			if (best.first >= 0) link(best.first, best.second)
		}

		// Shortest paths from the root. The walk starts at the vertices nearest the attachment, each
		// carrying its straight-line distance to it.
		val straight = DoubleArray(count) { hypot(points[it * 2] - attachX, points[it * 2 + 1] - attachY).toDouble() }
		val nearest = straight.min()
		val seedLimit = nearest + (straight.max() - nearest) * 0.03 + 1e-6
		val start = DoubleArray(count) { Double.MAX_VALUE }
		val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
		for (i in 0 until count) if (straight[i] <= seedLimit) {
			start[i] = straight[i]
			queue += straight[i] to i
		}
		while (queue.isNotEmpty()) {
			val (cost, at) = queue.poll()
			if (cost > start[at]) continue
			for (next in edges[at]) {
				val through = cost + d(at, next)
				if (through < start[next]) {
					start[next] = through
					queue += through to next
				}
			}
		}
		val reached = (0 until count).filter { start[it] < Double.MAX_VALUE }
		if (reached.size < 3) return null
		val far = reached.map { start[it] }.sorted().let { it[(it.size * 0.97).toInt().coerceAtMost(it.size - 1)] }
		if (far <= 1e-6) return null
		val sums = Array(bins) { DoubleArray(3) }
		for (i in reached) {
			val bin = ((start[i] / far) * bins).toInt().coerceIn(0, bins - 1)
			sums[bin][0] += points[i * 2].toDouble(); sums[bin][1] += points[i * 2 + 1].toDouble(); sums[bin][2] += 1.0
		}
		val line = mutableListOf(attachX to attachY)
		for (bin in sums) if (bin[2] > 0.0) line += (bin[0] / bin[2]).toFloat() to (bin[1] / bin[2]).toFloat()
		val tipCount = (reached.size * 0.03).toInt().coerceAtLeast(1)
		line += mean(points, reached.sortedByDescending { start[it] }.take(tipCount))
		val clean = mutableListOf(line.first())
		for (p in line.drop(1)) if (hypot(p.first - clean.last().first, p.second - clean.last().second) > 0.5f) clean += p
		return Medial(clean)
	}

	private fun mean(points: FloatArray, indices: List<Int>): Pair<Float, Float> {
		var x = 0f; var y = 0f
		for (i in indices) { x += points[i * 2]; y += points[i * 2 + 1] }
		val n = indices.size.coerceAtLeast(1)
		return (x / n) to (y / n)
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
