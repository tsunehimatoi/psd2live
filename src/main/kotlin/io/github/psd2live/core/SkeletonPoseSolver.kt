package io.github.psd2live.core

import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/** A bone where the current pose puts it, in canvas pixels. */
internal class PosedBone(val bone: SkeletonBone, val headX: Float, val headY: Float, val tailX: Float, val tailY: Float)

/** Which part of a bone the pointer is on: the body turns it (FK), the tip drags its chain (IK). */
internal class BoneHit(val boneId: String, val tip: Boolean)

/**
 * Forward and inverse kinematics of a baked skeleton, without any UI: where [posed] holds the bones for a set
 * of parameter values, and which values [drag] needs to turn a bone or pull its tip to a canvas point.
 *
 * A bone's parameter is its angle in degrees (signed by [SkeletonBone.direction]), so a change of angle
 * converts straight into a parameter change, and the rig the export writes is exactly the rig being posed.
 */
internal object SkeletonPoseSolver {
	/** The leg solve of each baked model, which takes a few dozen deformer evaluations to rebuild. */
	private val legSolves = java.util.WeakHashMap<PuppetModel, Map<String, SkeletonRig.LegJointPose>>()

	private fun legJoints(model: PuppetModel, legs: List<SkeletonRig.Leg>, spec: SkeletonSpec): Map<String, SkeletonRig.LegJointPose> =
		synchronized(legSolves) { legSolves.getOrPut(model) { SkeletonRig.solveLegPoses(model, legs,
			BodyStance.of(spec, Bounds(0f, 0f, model.canvasWidth.coerceAtLeast(1f), model.canvasHeight.coerceAtLeast(1f)))) } }

	/** Most bones an IK drag moves: the grabbed bone and its two parents, like a Spine two/three-bone constraint. */
	const val IK_CHAIN = 3

	/**
	 * Every limb bone of [spec] where [model] holds it at [values].
	 *
	 * A bone that owns a deformer is read straight from it: a limb bone's rotation, or the warp a body half
	 * bends, whose lattice turns everything past the waist band with it. A bone whose deformer was pruned - a
	 * joint that bends inside an unsplit mesh's keyforms - turns about its head from wherever its parent
	 * went, by its own parameter plus its poses' turns and the leg poses' IK angle, which is exactly what
	 * those keyforms and blend shapes bake.
	 */
	fun posed(model: PuppetModel, spec: SkeletonSpec?, values: Map<ParameterId, Float>): List<PosedBone> {
		if (spec?.enabled != true) return emptyList()
		val bones = SkeletonRig.limbBones(spec)
		val frames = frames(model, spec, values)
		return bones.map { bone ->
			val frame = frames.getValue(bone.id)
			val head = frame(bone.headX.toDouble(), bone.headY.toDouble())
			val tail = frame(bone.tailX.toDouble(), bone.tailY.toDouble())
			PosedBone(bone, head[0].toFloat(), head[1].toFloat(), tail[0].toFloat(), tail[1].toFloat())
		}
	}

	/**
	 * Where every limb bone of [spec] carries a canvas point at [values], as [posed] reads the bones, by bone id: a
	 * point at rest in canvas pixels to where the bone moves it. [rest] is the rig's deformers at the defaults, when
	 * the caller already has them.
	 */
	fun frames(
		model: PuppetModel,
		spec: SkeletonSpec,
		values: Map<ParameterId, Float>,
		rest: Map<org.umamo.runtime.model.DeformerId, org.umamo.render.eval.DeformerWorld>? = null,
	): Map<String, (Double, Double) -> DoubleArray> {
		val defaults = model.parameters.associate { it.id to it.default }
		val worlds = buildDeformerWorlds(model.deformers, { values[it] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		val rest = rest ?: buildDeformerWorlds(model.deformers, { defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		val bones = SkeletonRig.limbBones(spec)
		val ids = bones.mapTo(HashSet()) { it.id }
		val legs = SkeletonRig.legs(spec)
		val frames = LinkedHashMap<String, (Double, Double) -> DoubleArray>()
		val scratch = FloatArray(2)
		// Every pose's turns in parameter units by bone, summed as the blend shapes sum them.
		val poseTurns = HashMap<String, Float>()
		for (pose in SkeletonPoses.available(spec)) {
			for ((id, turn) in SkeletonPoses.turnsAt(spec, pose, values[pose.id] ?: 0f)) poseTurns[id] = (poseTurns[id] ?: 0f) + turn
		}
		fun param(bone: SkeletonBone) = ((values[ParameterId(bone.parameterId)] ?: 0f) + (poseTurns[bone.id] ?: 0f)) * bone.direction
		// The IK turn the leg poses add to a shin or foot relative to its parent, weighed as the bake
		// weighed it into the mesh.
		val legValue = { pose: SkeletonPose -> values[pose.id] ?: 0f }
		val joints = if (legs.isEmpty() || SkeletonPoses.legPoses.all { legValue(it) == 0f }) emptyMap() else legJoints(model, legs, spec)
		fun poseTurn(bone: SkeletonBone): Double = joints[bone.id]?.turnAt(legValue)?.toDouble() ?: 0.0
		for (bone in bones) {
			val id = SkeletonRig.deformerOf(model, bone)
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
					val scale = joints[bone.id]?.scaleAt(legValue)?.toDouble() ?: 1.0
					val hx = bone.headX.toDouble()
					val hy = bone.headY.toDouble();
					{ x, y -> SkeletonIk.rotate(hx + (x - hx) * scale, hy + (y - hy) * scale, hx, hy, turn).let { parent(it[0], it[1]) } }
				}
				else -> {
					val turn = param(bone).toDouble()
					val hx = bone.headX.toDouble()
					val hy = bone.headY.toDouble();
					{ x, y -> SkeletonIk.rotate(x, y, hx, hy, turn) }
				}
			}
		}
		return frames
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
			.take(grabbed.bone.ik.chainLength).mapNotNull { byId[it.id] }.toList().asReversed()
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
		val seeds = DoubleArray(chain.size)
		chain.forEachIndexed { i, posed ->
			val bone = posed.bone
			val a = (bone.minAngle - current(bone)) * bone.direction
			val b = (bone.maxAngle - current(bone)) * bone.direction
			lower[i] = minOf(a, b).toDouble()
			upper[i] = maxOf(a, b).toDouble()
			val bend = grabbed.bone.ik.bendDirection
			if (bend != 0 && i > 0) {
				val previous = chain[i - 1]
				val angle = SkeletonIk.wrap(SkeletonIk.heading((posed.tailX - posed.headX).toDouble(), (posed.tailY - posed.headY).toDouble()) -
					SkeletonIk.heading((previous.tailX - previous.headX).toDouble(), (previous.tailY - previous.headY).toDouble()))
				lower[i] = maxOf(lower[i], (if (bend > 0) 0.0 else -180.0) - angle)
				upper[i] = minOf(upper[i], (if (bend > 0) 180.0 else 0.0) - angle)
				if (lower[i] > upper[i]) { lower[i] = 0.0; upper[i] = 0.0 }
				if (kotlin.math.abs(angle) < 0.01) {
					seeds[i] = bend.toDouble()
					// Counter-turn the root so CCD cannot immediately straighten the seeded joint again.
					seeds[0] = -bend * 0.5
				}
			}
		}
		val turns = SkeletonIk.ccd(joints, x.toDouble(), y.toDouble(), lower, upper,
			grabbed.bone.ik.iterations, grabbed.bone.ik.tolerancePx.toDouble(), seeds)
		return chain.withIndex().associate { (i, posed) ->
			val bone = posed.bone
			ParameterId(bone.parameterId) to (current(bone) + turns[i].toFloat() / bone.direction).coerceIn(bone.minAngle, bone.maxAngle)
		}
	}

	/** Fixed targets are authoring constraints; actual parameter results are what animation/export stores. */
	fun solveTargets(model: PuppetModel, spec: SkeletonSpec?, values: Map<ParameterId, Float>): Map<ParameterId, Float> {
		if (spec?.enabled != true || spec.ikTargets.isEmpty()) return emptyMap()
		val targets = spec.topological().mapNotNull { bone -> spec.ikTargets[bone.id]?.takeIf { it.enabled }?.let { bone.id to it } }
		var next = values
		repeat(8) {
			var satisfied = true
			for ((id, target) in targets) {
				val posed = posed(model, spec, next)
				val effector = posed.firstOrNull { it.bone.id == id } ?: continue
				if (kotlin.math.hypot(effector.tailX - target.x, effector.tailY - target.y) <= effector.bone.ik.tolerancePx) continue
				satisfied = false
				next = next + drag(spec, posed, BoneHit(id, true), target.x, target.y, next, true)
			}
			if (satisfied) return next.filter { (id, value) -> value != values[id] }
		}
		return next.filter { (id, value) -> value != values[id] }
	}
}
