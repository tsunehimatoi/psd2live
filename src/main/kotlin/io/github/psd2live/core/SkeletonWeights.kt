package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * One bone of a limb as the skinning sees it: its rest segment in canvas pixels, the index of its parent
 * within the same limb (-1 at the limb's root), and the half width of the band around its head joint in
 * which vertices blend from the parent into it.
 */
internal class SkinBone(
	val parent: Int,
	val headX: Double,
	val headY: Double,
	val tailX: Double,
	val tailY: Double,
	val blend: Double,
) {
	val length: Double get() = hypot(tailX - headX, tailY - headY)
	val dirX: Double get() = (tailX - headX) / length.coerceAtLeast(1e-9)
	val dirY: Double get() = (tailY - headY) / length.coerceAtLeast(1e-9)
}

/**
 * How one vertex follows its limb: rigidly with bone [from], turned toward [from]'s child [to] by
 * [weight] of the angle between the two. [from] == [to] (weight 0) is a vertex that is rigid to one bone.
 */
internal class VertexSkin(val from: Int, val to: Int, val weight: Float) {
	val rigid: Boolean get() = from == to || weight <= 0f
}

/**
 * Automatic weights and the joint blend.
 *
 * Every vertex follows at most two bones, and only across one joint: the parent and the child that meet
 * there. The blend runs across a band centered on the joint whose axis is the bisector of the two bones,
 * so the band is symmetric however far the limb is bent at rest.
 *
 * The blend is by angle, not by position: a vertex [weight] of the way into the band turns about the joint
 * by that fraction of the child's angle. Linear blend skinning averages the two rigid positions instead,
 * which pulls the inside of a bent elbow toward the joint and pinches it; turning about the joint keeps
 * every vertex at its distance from it, so the limb keeps its width through the bend.
 */
internal object SkeletonWeights {
	/** Default half width of a joint band, as a fraction of the shorter of the two bones meeting there. */
	private const val DEFAULT_BLEND = 0.35

	/** Hard cap on the half width, so the bands at the two ends of one bone never overlap. */
	private const val MAX_BLEND = 0.45

	/** The half width of [bone]'s head joint band given its parent's length, clamped to the geometry. */
	fun blendHalfWidth(bone: SkeletonBone, parentLength: Float?): Double {
		if (parentLength == null) return 0.0
		val shorter = minOf(bone.length, parentLength).toDouble()
		val wanted = bone.blendWidth?.toDouble() ?: (shorter * DEFAULT_BLEND)
		return wanted.coerceIn(1.0, (shorter * MAX_BLEND).coerceAtLeast(1.0))
	}

	/**
	 * The axis of the band at [child]'s head joint: the unit bisector of the parent and child directions.
	 * Signed distance along it is how far past the joint a point lies.
	 */
	fun bandNormal(bones: List<SkinBone>, child: Int): DoubleArray {
		val c = bones[child]
		val p = bones.getOrNull(c.parent) ?: return doubleArrayOf(c.dirX, c.dirY)
		val sx = p.dirX + c.dirX
		val sy = p.dirY + c.dirY
		val length = hypot(sx, sy)
		return if (length < 1e-6) doubleArrayOf(c.dirX, c.dirY) else doubleArrayOf(sx / length, sy / length)
	}

	/** Signed distance of ([x], [y]) past [child]'s head joint along its band axis. */
	fun bandDistance(bones: List<SkinBone>, child: Int, x: Double, y: Double): Double {
		val n = bandNormal(bones, child)
		val c = bones[child]
		return (x - c.headX) * n[0] + (y - c.headY) * n[1]
	}

	/** Cubic smoothstep of [t] clamped to [0, 1]; flat at both ends so the band has no crease at its edges. */
	fun smoothstep(t: Double): Double {
		val x = t.coerceIn(0.0, 1.0)
		return x * x * (3.0 - 2.0 * x)
	}

	/**
	 * Skins every vertex of [canvas] (interleaved x, y in canvas pixels) to [bones].
	 *
	 * The nearest bone owns a vertex; the joint at either end of that bone then decides how much of the
	 * neighbour across it the vertex follows. Near both joints of a short bone the closer joint wins.
	 */
	fun skin(canvas: FloatArray, bones: List<SkinBone>): List<VertexSkin> {
		require(bones.isNotEmpty())
		val children = bones.indices.groupBy { bones[it].parent }
		return (0 until canvas.size / 2).map { vertex ->
			val x = canvas[vertex * 2].toDouble()
			val y = canvas[vertex * 2 + 1].toDouble()
			val primary = bones.indices.minBy { segmentDistance(bones[it], x, y) }

			var best: VertexSkin? = null
			var bestDistance = Double.MAX_VALUE
			fun consider(parent: Int, child: Int) {
				val half = bones[child].blend
				if (half <= 0.0) return
				val s = bandDistance(bones, child, x, y)
				// The head joint only matters while the vertex is short of the far edge of its band; a
				// tail joint only once the vertex reaches the band's near edge.
				val relevant = if (child == primary) s < half else s > -half
				if (!relevant || abs(s) >= bestDistance) return
				bestDistance = abs(s)
				val weight = smoothstep((s + half) / (2.0 * half)).toFloat()
				best = when {
					weight <= 0f -> VertexSkin(parent, parent, 0f)
					weight >= 1f -> VertexSkin(child, child, 0f)
					else -> VertexSkin(parent, child, weight)
				}
			}
			bones[primary].parent.takeIf { it >= 0 }?.let { consider(it, primary) }
			for (child in children[primary].orEmpty()) consider(primary, child)
			best ?: VertexSkin(primary, primary, 0f)
		}
	}

	/** Distance from ([x], [y]) to [bone]'s segment. */
	fun segmentDistance(bone: SkinBone, x: Double, y: Double): Double {
		val dx = bone.tailX - bone.headX
		val dy = bone.tailY - bone.headY
		val lengthSquared = dx * dx + dy * dy
		val t = if (lengthSquared < 1e-12) 0.0 else (((x - bone.headX) * dx + (y - bone.headY) * dy) / lengthSquared).coerceIn(0.0, 1.0)
		return hypot(x - (bone.headX + dx * t), y - (bone.headY + dy * t))
	}
}
