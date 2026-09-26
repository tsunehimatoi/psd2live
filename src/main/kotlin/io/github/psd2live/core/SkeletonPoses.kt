package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/**
 * A whole-body pose the rig carries as one blend-shape parameter. [legs] poses also move the hips and
 * plant the feet by IK; every other pose only turns bones by [SkeletonPoses.boneTurns].
 */
internal class SkeletonPose(
	val id: ParameterId,
	val nameKey: String,
	val min: Float,
	val max: Float,
	/** The values the pose is baked at, including the neutral 0. IK poses need them dense. */
	val keys: FloatArray,
	val legs: Boolean = false,
)

/**
 * The preset poses of a skeleton, one parameter each.
 *
 * A pose is a blend shape rather than another keyform axis: it adds its turns on top of whatever the
 * bones' own parameters hold, so poses stack without multiplying anyone's keyforms, and a preset motion
 * is nothing but these parameters moving. The bones' own angles stay keyform parameters, which keeps
 * every limb an exact rigid rotation.
 */
internal object SkeletonPoses {
	val crouch = SkeletonPose(ParameterId("ParamSkelCrouch"), "skeleton.param.crouch", 0f, 1f,
		floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), legs = true)
	val weight = SkeletonPose(ParameterId("ParamSkelWeight"), "skeleton.param.weight", -1f, 1f,
		floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), legs = true)
	val tailSwing = SkeletonPose(ParameterId("ParamSkelTailSwing"), "skeleton.param.tailSwing", -1f, 1f, floatArrayOf(-1f, 0f, 1f))
	val armSway = SkeletonPose(ParameterId("ParamSkelArmSway"), "skeleton.param.armSway", -1f, 1f, floatArrayOf(-1f, 0f, 1f))
	val wingFlap = SkeletonPose(ParameterId("ParamSkelWingFlap"), "skeleton.param.wingFlap", -1f, 1f, floatArrayOf(-1f, 0f, 1f))

	val all = listOf(crouch, weight, tailSwing, armSway, wingFlap)

	/** World degrees the upper body leans back against the hips' tilt at full weight shift. */
	private const val UPPER_BODY_COUNTER = 1.5f

	/**
	 * The poses [spec] can play: the leg poses when a skinned leg exists, every other pose when it turns
	 * a bone whose skinning tree holds a mesh.
	 */
	fun available(spec: SkeletonSpec?): List<SkeletonPose> {
		if (spec?.enabled != true) return emptyList()
		val hasLegs = SkeletonRig.legs(spec).isNotEmpty()
		val skinned = skinnedBones(spec)
		return all.filter { pose ->
			if (pose.legs) hasLegs
			else pose.keys.any { key -> boneTurns(spec, pose, key).keys.any(skinned::contains) }
		}
	}

	/** The bone parameters a non-leg pose turns; empty for a leg pose, whose IK the parameters cannot stand in for. */
	fun drivenParameters(spec: SkeletonSpec, pose: SkeletonPose): Set<String> {
		if (pose.legs) return emptySet()
		val byId = spec.bones.associateBy { it.id }
		return pose.keys.flatMap { boneTurns(spec, pose, it).keys }.mapNotNullTo(HashSet()) { byId[it]?.parameterId }
	}

	/**
	 * The turn each bone takes at [value], in its parameter's units (degrees signed by
	 * [SkeletonBone.direction]) and inside its limits, by bone ID. The leg IK of [crouch] and [weight] is
	 * not here; it depends on where the rig puts the hips and is solved at bake time.
	 */
	fun boneTurns(spec: SkeletonSpec, pose: SkeletonPose, value: Float): Map<String, Float> {
		if (value == 0f) return emptyMap()
		val bones = SkeletonRig.limbBones(spec)
		fun side(bone: SkeletonBone) = if (bone.side == Side.RIGHT) -1f else 1f
		fun chain(roles: Set<BoneRole>, amplitude: Float, sided: Boolean) = bones.filter { it.role in roles }.associate { bone ->
			bone.id to amplitude * decay(depth(spec, bone)) * (if (sided) side(bone) else 1f) * value
		}
		val turns = when (pose) {
			crouch -> bones.filter { it.role == BoneRole.UPPER_ARM }.associate { it.id to 8f * value }
			// The arms hang from the chest, so they trail the hips; the chest leans back against the tilt.
			weight -> bones.filter { it.role == BoneRole.UPPER_ARM }.associate { it.id to 5f * (if (it.direction > 0f) 1f else -1f) * value } +
				bones.filter { it.role == BoneRole.UPPER_BODY }.associate { it.id to UPPER_BODY_COUNTER * value * it.direction }
			tailSwing -> bones.filter { it.role == BoneRole.TAIL }.associate { bone ->
				val segment = depth(spec, bone)
				bone.id to 22f * decay(segment) * (1f + segment * 0.25f) * value
			}
			armSway -> chain(setOf(BoneRole.UPPER_ARM, BoneRole.FOREARM, BoneRole.HAND), 4f, sided = true)
			wingFlap -> chain(setOf(BoneRole.WING), 6f, sided = true)
			else -> emptyMap()
		}
		val byId = bones.associateBy { it.id }
		return turns.mapValues { (id, turn) -> byId.getValue(id).let { turn.coerceIn(it.minAngle, it.maxAngle) } }
			.filterValues { abs(it) > 1e-4f }
	}

	/**
	 * [boneTurns] as the rig plays it at any [value]: interpolated linearly between the two keys around
	 * it and held at the end keys, the way a blend shape weighs its keys.
	 */
	fun turnsAt(spec: SkeletonSpec, pose: SkeletonPose, value: Float): Map<String, Float> {
		val out = HashMap<String, Float>()
		for ((key, weight) in bracket(pose, value)) {
			for ((id, turn) in boneTurns(spec, pose, key)) out[id] = (out[id] ?: 0f) + turn * weight
		}
		return out
	}

	/** The keys of [pose] around [value] and how much each weighs there, held at the end keys. */
	fun bracket(pose: SkeletonPose, value: Float): List<Pair<Float, Float>> {
		val keys = pose.keys
		val clamped = value.coerceIn(keys.first(), keys.last())
		val upper = keys.indexOfFirst { it >= clamped }.coerceAtLeast(1)
		val a = keys[upper - 1]
		val b = keys[upper]
		val t = if (b > a) (clamped - a) / (b - a) else 0f
		return listOf(a to 1f - t, b to t)
	}

	/** How many limb bones [bone] hangs below, not counting the body bone its chain starts from. */
	fun depth(spec: SkeletonSpec, bone: SkeletonBone): Int {
		val ids = SkeletonRig.limbBones(spec).mapTo(HashSet()) { it.id }
		return generateSequence(SkeletonRig.limbParent(spec, bone, ids)) { SkeletonRig.limbParent(spec, it, ids) }
			.count { !it.role.body }
	}

	/** How much of its chain's amplitude a joint [segment] deep keeps. */
	private fun decay(segment: Int): Float = 1f / (1f + segment * 0.45f)

	/** Bones whose skinning tree holds at least one mesh, so turning them moves something. */
	private fun skinnedBones(spec: SkeletonSpec): Set<String> {
		val bones = SkeletonRig.limbBones(spec)
		val ids = bones.mapTo(HashSet()) { it.id }
		val parentOf = bones.associate { it.id to SkeletonRig.limbParent(spec, it, ids) }
		val rootOf = SkeletonRig.skinRoots(bones, parentOf)
		val skinnedRoots = bones.filter { it.drawableIds.isNotEmpty() }.mapTo(HashSet()) { rootOf.getValue(it.id) }
		return bones.filter { rootOf.getValue(it.id) in skinnedRoots }.mapTo(HashSet()) { it.id }
	}
}
