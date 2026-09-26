package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonIk
import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeights
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot

/** A bone where the current pose puts it, in canvas pixels. */
internal class PosedBone(val bone: SkeletonBone, val headX: Float, val headY: Float, val tailX: Float, val tailY: Float)

/** Which part of a bone the pointer is on: the body turns it (FK), the tip drags its chain (IK). */
internal class BoneHit(val boneId: String, val tip: Boolean)

/**
 * The canvas pose tool: bones drawn where the rig currently holds them, turned by dragging their body
 * and pulled by their tip.
 *
 * Posing writes parameters, never geometry. A bone's parameter is its angle in degrees (signed by
 * [SkeletonBone.direction]), so a drag's angle change converts straight into a parameter change, and
 * the rig the export writes is exactly the rig being posed.
 */
internal object SkeletonPoseTool {
	/** Screen pixels within which the pointer grabs a bone's tip. */
	private const val TIP_RADIUS = 10f

	/** Most bones an IK drag moves: the grabbed bone and its two parents, like a Spine two/three-bone constraint. */
	private const val IK_CHAIN = 3

	/**
	 * Every limb bone of [spec] where [model] holds it at [values].
	 *
	 * A bone that owns a rotation deformer is read straight from it. A bone whose deformer was pruned - a
	 * joint that bends inside an unsplit mesh's keyforms - turns about its head from wherever its parent
	 * went, by its own parameter plus the leg pose's IK angle, which is exactly what those keyforms bake.
	 */
	fun posed(model: PuppetModel, spec: SkeletonSpec?, values: Map<ParameterId, Float>): List<PosedBone> {
		if (spec?.enabled != true) return emptyList()
		val defaults = model.parameters.associate { it.id to it.default }
		val worlds = buildDeformerWorlds(model.deformers, { values[it] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		val rest = buildDeformerWorlds(model.deformers, { defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		val bones = SkeletonRig.limbBones(spec)
		val ids = bones.mapTo(HashSet()) { it.id }
		val legs = SkeletonRig.legs(spec)
		val center = if (legs.isEmpty()) 0.0 else SkeletonRig.legCenter(legs)
		val frames = HashMap<String, (Double, Double) -> DoubleArray>()
		val scratch = FloatArray(2)
		fun param(bone: SkeletonBone) = (values[ParameterId(bone.parameterId)] ?: 0f) * bone.direction
		/** The IK turn a leg pose adds to [bone] relative to its parent, given where its thigh's hip went. */
		fun poseTurn(bone: SkeletonBone): Double {
			val leg = legs.firstOrNull { it.shin.id == bone.id || it.foot?.id == bone.id } ?: return 0.0
			val thigh = frames[leg.thigh.id] ?: return 0.0
			val hip = thigh(leg.thigh.headX.toDouble(), leg.thigh.headY.toDouble())
			val (thighTurn, shinTurn) = SkeletonRig.legTurns(leg, hip[0], hip[1], center)
			return if (bone.id == leg.shin.id) shinTurn - thighTurn else -shinTurn
		}
		for (bone in bones) {
			val id = DeformerId(bone.deformerId)
			val world = worlds[id]
			val restWorld = rest[id]
			val parent = SkeletonRig.limbParent(spec, bone, ids)?.let { frames[it.id] }
			frames[bone.id] = when {
				world != null && restWorld != null -> { x, y ->
					val local = SkeletonRig.inverse(restWorld, x.toFloat(), y.toFloat())
					world.apply(local[0], local[1], scratch, 0)
					doubleArrayOf(scratch[0].toDouble(), scratch[1].toDouble())
				}
				parent != null -> {
					val turn = param(bone) + poseTurn(bone)
					val hx = bone.headX.toDouble()
					val hy = bone.headY.toDouble();
					{ x, y -> SkeletonIk.rotate(x, y, hx, hy, turn).let { parent(it[0], it[1]) } }
				}
				else -> {
					val turn = param(bone).toDouble()
					val hx = bone.headX.toDouble()
					val hy = bone.headY.toDouble();
					{ x, y -> SkeletonIk.rotate(x, y, hx, hy, turn) }
				}
			}
		}
		return bones.map { bone ->
			val frame = frames.getValue(bone.id)
			val head = frame(bone.headX.toDouble(), bone.headY.toDouble())
			val tail = frame(bone.tailX.toDouble(), bone.tailY.toDouble())
			PosedBone(bone, head[0].toFloat(), head[1].toFloat(), tail[0].toFloat(), tail[1].toFloat())
		}
	}

	/** The bone under [pos] (screen), tips first, then the nearest body within a bone-proportional reach. */
	fun hit(bones: List<PosedBone>, pos: Offset, viewport: CanvasViewport): BoneHit? {
		fun screen(x: Float, y: Float) = Offset(viewport.x(x).toFloat(), (viewport.offsetY + y * viewport.scale).toFloat())
		bones.asReversed().firstOrNull { (screen(it.tailX, it.tailY) - pos).getDistance() <= TIP_RADIUS }
			?.let { return BoneHit(it.bone.id, tip = true) }
		var best: PosedBone? = null
		var bestDistance = Float.MAX_VALUE
		for (posed in bones) {
			val h = screen(posed.headX, posed.headY)
			val t = screen(posed.tailX, posed.tailY)
			val dx = t.x - h.x
			val dy = t.y - h.y
			val lengthSquared = dx * dx + dy * dy
			if (lengthSquared < 1f) continue
			val u = (((pos.x - h.x) * dx + (pos.y - h.y) * dy) / lengthSquared).coerceIn(0f, 1f)
			val distance = hypot(pos.x - (h.x + u * dx), pos.y - (h.y + u * dy))
			val reach = (kotlin.math.sqrt(lengthSquared) * 0.14f).coerceIn(6f, 18f)
			if (distance <= reach && distance < bestDistance) {
				best = posed
				bestDistance = distance
			}
		}
		return best?.let { BoneHit(it.bone.id, tip = false) }
	}

	/**
	 * The parameter values that turn [hit]'s bone toward canvas point ([x], [y]).
	 *
	 * A body drag is forward kinematics on that bone alone. A tip drag, or any drag with [ik], solves the
	 * bone and up to two of its parents by CCD so the tip follows the pointer, each joint inside its
	 * limits. Both are incremental from the current pose, so a drag keeps converging as it moves.
	 */
	fun drag(
		spec: SkeletonSpec,
		bones: List<PosedBone>,
		hit: BoneHit,
		x: Float,
		y: Float,
		values: Map<ParameterId, Float>,
		ik: Boolean,
	): Map<ParameterId, Float> {
		val byId = bones.associateBy { it.bone.id }
		val grabbed = byId[hit.boneId] ?: return emptyMap()
		fun current(bone: SkeletonBone) = values[ParameterId(bone.parameterId)] ?: 0f
		if (!hit.tip && !ik) {
			val turn = SkeletonIk.wrap(
				SkeletonIk.heading((x - grabbed.headX).toDouble(), (y - grabbed.headY).toDouble()) -
					SkeletonIk.heading((grabbed.tailX - grabbed.headX).toDouble(), (grabbed.tailY - grabbed.headY).toDouble()),
			)
			val bone = grabbed.bone
			val next = (current(bone) + turn / bone.direction).toFloat().coerceIn(bone.minAngle, bone.maxAngle)
			return mapOf(ParameterId(bone.parameterId) to next)
		}
		val limbIds = bones.mapTo(HashSet()) { it.bone.id }
		// A limb's IK stops at the body bone it hangs from: pulling a hand should not tilt the torso.
		val chain = generateSequence(grabbed.bone) { bone -> SkeletonRig.limbParent(spec, bone, limbIds)?.takeUnless { it.role.body } }
			.take(IK_CHAIN).mapNotNull { byId[it.id] }.toList().asReversed()
		val joints = DoubleArray((chain.size + 1) * 2)
		chain.forEachIndexed { i, posed ->
			joints[i * 2] = posed.headX.toDouble()
			joints[i * 2 + 1] = posed.headY.toDouble()
		}
		joints[chain.size * 2] = grabbed.tailX.toDouble()
		joints[chain.size * 2 + 1] = grabbed.tailY.toDouble()
		// Limits on each bone's turn in world degrees, from where its parameter sits now to its limits.
		val lower = DoubleArray(chain.size)
		val upper = DoubleArray(chain.size)
		chain.forEachIndexed { i, posed ->
			val bone = posed.bone
			val a = (bone.minAngle - current(bone)) * bone.direction
			val b = (bone.maxAngle - current(bone)) * bone.direction
			lower[i] = minOf(a, b).toDouble()
			upper[i] = maxOf(a, b).toDouble()
		}
		val turns = SkeletonIk.ccd(joints, x.toDouble(), y.toDouble(), lower, upper)
		return chain.withIndex().associate { (i, posed) ->
			val bone = posed.bone
			ParameterId(bone.parameterId) to (current(bone) + turns[i].toFloat() / bone.direction).coerceIn(bone.minAngle, bone.maxAngle)
		}
	}

	/** Every limb parameter back at rest. */
	fun rest(spec: SkeletonSpec?): Map<ParameterId, Float> =
		if (spec?.enabled != true) emptyMap()
		else SkeletonRig.limbBones(spec).associate { ParameterId(it.parameterId) to 0f } +
			mapOf(SkeletonRig.crouchId to 0f, SkeletonRig.weightId to 0f)

	/**
	 * Per-vertex weights for the heat map: for every skinned mesh, each vertex's two bones and the weight
	 * of the second, the same answer the bake used, keyed by drawable. Computed on the rest pose, so it is
	 * worth caching per model.
	 */
	fun weights(model: PuppetModel, spec: SkeletonSpec?): Map<DrawableId, Pair<List<SkeletonBone>, List<io.github.psd2live.core.VertexSkin>>> {
		if (spec?.enabled != true) return emptyMap()
		val bones = SkeletonRig.limbBones(spec)
		val ids = bones.mapTo(HashSet()) { it.id }
		val parentOf = bones.associate { it.id to SkeletonRig.limbParent(spec, it, ids) }
		val rootOf = SkeletonRig.skinRoots(bones, parentOf)
		val trees = bones.groupBy { rootOf.getValue(it.id) }
		val rest = SkeletonRig.restCanvas(model)
		val out = LinkedHashMap<DrawableId, Pair<List<SkeletonBone>, List<io.github.psd2live.core.VertexSkin>>>()
		for (bone in bones) for (id in bone.drawableIds) {
			val drawableId = DrawableId(id)
			if (drawableId in out) continue
			val canvas = rest[drawableId] ?: continue
			val tree = trees.getValue(rootOf.getValue(bone.id))
			val skinBones = SkeletonRig.skinBones(tree, parentOf)
			out[drawableId] = tree to SkeletonWeights.skin(canvas, skinBones)
		}
		return out
	}
}
