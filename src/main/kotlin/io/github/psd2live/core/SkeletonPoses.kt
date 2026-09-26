package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.sign

/**
 * A whole-body pose. [legs] poses also move the hips and plant the feet by IK, unless [airborne] lets the
 * legs ride along with the hips; every other pose only turns bones by [SkeletonPoses.boneTurns].
 *
 * A [rig] pose is a blend-shape parameter of the rig. Any other pose is a gesture: it has no parameter of
 * its own, and a motion plays it on the bones' own parameters (see [SkeletonPoses.gestureTurns]).
 */
internal class SkeletonPose(
	val id: ParameterId,
	val nameKey: String,
	val min: Float,
	val max: Float,
	/** The values the pose is baked at, including the neutral 0. IK poses need them dense. */
	val keys: FloatArray,
	val legs: Boolean = false,
	val airborne: Boolean = false,
	val rig: Boolean = true,
)

/**
 * The preset poses of a skeleton.
 *
 * A mesh drawn across a joint carries the part past it in its own shapes, and where that part sits
 * depends on the joint's whole angle. Blend shapes only add what each parameter moves on its own, and two
 * turns of one joint moved apart do not add up to the joint turned by both: a pose on top of a bone's own
 * angle, or two poses on one joint, would tear the limb away from its bones. So a joint is turned by its
 * bone's parameter alone. A pose that only turns limb joints is a gesture that motions play on those
 * parameters; the rig keeps a pose as a blend-shape parameter only for what no bone parameter can stand
 * in for - the hips of the leg poses, with the feet held by IK, and the upper body leaning back over them
 * - and for the tail and the wings, whose parameters belong to the physics.
 */
internal object SkeletonPoses {
	val crouch = SkeletonPose(ParameterId("ParamSkelCrouch"), "skeleton.param.crouch", 0f, 1f,
		floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), legs = true)
	val weight = SkeletonPose(ParameterId("ParamSkelWeight"), "skeleton.param.weight", -1f, 1f,
		floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), legs = true)
	val tailSwing = SkeletonPose(ParameterId("ParamSkelTailSwing"), "skeleton.param.tailSwing", -1f, 1f, floatArrayOf(-1f, 0f, 1f))
	val armSway = SkeletonPose(ParameterId("ParamSkelArmSway"), "skeleton.param.armSway", -1f, 1f, floatArrayOf(-1f, 0f, 1f), rig = false)
	val wingFlap = SkeletonPose(ParameterId("ParamSkelWingFlap"), "skeleton.param.wingFlap", -1f, 1f, floatArrayOf(-1f, 0f, 1f))
	/** Knock-kneed: the hips sink a little and the knees give inward instead of out. */
	val kneesIn = SkeletonPose(ParameterId("ParamSkelKneesIn"), "skeleton.param.kneesIn", 0f, 1f,
		floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), legs = true)
	/** Both arms: -1 draws the hands together in front of the body, +1 throws the arms open and up. */
	val arms = SkeletonPose(ParameterId("ParamSkelArms"), "skeleton.param.arms", -1f, 1f, floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), rig = false)
	/** The character's right arm raised to wave, the forearm upright. */
	val wave = SkeletonPose(ParameterId("ParamSkelWave"), "skeleton.param.wave", 0f, 1f, floatArrayOf(0f, 0.5f, 1f), rig = false)
	/** The raised forearm and hand swinging side to side, on top of [wave]. */
	val waveSwing = SkeletonPose(ParameterId("ParamSkelWaveSwing"), "skeleton.param.waveSwing", -1f, 1f, floatArrayOf(-1f, 0f, 1f), rig = false)
	/** One leg kicked up behind: +1 the character's left, -1 the right. Pure turns, so it adds onto [weight]. */
	val legLift = SkeletonPose(ParameterId("ParamSkelLegLift"), "skeleton.param.legLift", -1f, 1f,
		floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), rig = false)
	/**
	 * The whole body lifted off the ground, legs and all. Nothing is planted, so it only moves the body
	 * warp and adds exactly onto a planted leg pose: a hop out of a [crouch] keeps the knees bent in the air.
	 */
	val hop = SkeletonPose(ParameterId("ParamSkelHop"), "skeleton.param.hop", 0f, 1f, floatArrayOf(0f, 1f), legs = true, airborne = true)

	val all = listOf(crouch, weight, kneesIn, hop, tailSwing, armSway, wingFlap, arms, wave, waveSwing, legLift)

	/** The poses that move the hips. Of the planted ones only one plays at a time. */
	val legPoses = all.filter { it.legs }

	/** The poses the rig carries as parameters. */
	val rigPoses = all.filter { it.rig }

	/** Bones a rig pose may turn: the body halves, which bend warps, and the physics' tails and wings. */
	private val rigRoles = setOf(BoneRole.UPPER_BODY, BoneRole.LOWER_BODY, BoneRole.TAIL, BoneRole.WING)

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
	 * [SkeletonBone.direction]) and inside its limits, by bone ID. The leg IK of [legPoses] is not here;
	 * it depends on where the rig puts the hips and is solved at bake time.
	 */
	fun boneTurns(spec: SkeletonSpec, pose: SkeletonPose, value: Float): Map<String, Float> {
		if (value == 0f) return emptyMap()
		val bones = SkeletonRig.limbBones(spec)
		fun side(bone: SkeletonBone) = if (bone.side == Side.RIGHT) -1f else 1f
		fun chain(roles: Set<BoneRole>, amplitude: Float, sided: Boolean) = bones.filter { it.role in roles }.associate { bone ->
			bone.id to amplitude * decay(depth(spec, bone)) * (if (sided) side(bone) else 1f) * value
		}
		// A fixed turn per arm joint, away from the body; [only] keeps it to one side.
		fun arm(upper: Float, fore: Float, hand: Float, only: Side? = null) = bones
			.filter { only == null || it.side == only }
			.mapNotNull { bone ->
				when (bone.role) {
					BoneRole.UPPER_ARM -> upper
					BoneRole.FOREARM -> fore
					BoneRole.HAND -> hand
					else -> null
				}?.let { bone.id to it * abs(value) }
			}.toMap()
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
			kneesIn -> bones.filter { it.role == BoneRole.UPPER_ARM }.associate { it.id to -4f * value }
			// Tucked, the forearms fold across the body; open, the arms lift clear of the shoulders.
			arms -> if (value < 0f) arm(upper = -12f, fore = -35f, hand = -15f) else arm(upper = 70f, fore = 20f, hand = 10f)
			// The elbow at shoulder height and the forearm upright, the way a hand waves beside the face.
			wave -> arm(upper = 100f, fore = 65f, hand = 0f, only = Side.RIGHT)
			waveSwing -> arm(upper = 0f, fore = 20f * value.sign, hand = 15f * value.sign, only = Side.RIGHT)
			// The thigh draws in under the body and the shin flicks out, which is how a front-facing figure
			// reads as kicking a foot up behind it.
			legLift -> bones.filter { it.side == (if (value > 0f) Side.LEFT else Side.RIGHT) }.mapNotNull { bone ->
				when (bone.role) {
					BoneRole.THIGH -> -5f
					BoneRole.SHIN -> 45f
					BoneRole.FOOT -> 20f
					else -> null
				}?.let { bone.id to it * abs(value) }
			}.toMap()
			else -> emptyMap()
		}
		val byId = bones.associateBy { it.id }
		return turns.mapValues { (id, turn) -> byId.getValue(id).let { turn.coerceIn(it.minAngle, it.maxAngle) } }
			.filterValues { abs(it) > 1e-4f }
	}

	/** The part of [boneTurns] a [SkeletonPose.rig] pose bakes into the rig; none for a gesture. */
	fun rigTurns(spec: SkeletonSpec, pose: SkeletonPose, value: Float): Map<String, Float> {
		if (!pose.rig) return emptyMap()
		return boneTurns(spec, pose, value).filterKeys { spec.bone(it)?.role in rigRoles }
	}

	/** The part of [boneTurns] a motion plays on the bones' own parameters: all of a gesture's, and a rig pose's limb turns. */
	fun gestureTurns(spec: SkeletonSpec, pose: SkeletonPose, value: Float): Map<String, Float> {
		val rig = rigTurns(spec, pose, value)
		return boneTurns(spec, pose, value).filterKeys { it !in rig }
	}

	/**
	 * [turns] at any [value]: interpolated linearly between the two keys around it and held at the end keys,
	 * the way a blend shape weighs its keys.
	 */
	fun turnsAt(
		spec: SkeletonSpec,
		pose: SkeletonPose,
		value: Float,
		turns: (SkeletonSpec, SkeletonPose, Float) -> Map<String, Float> = ::rigTurns,
	): Map<String, Float> {
		val out = HashMap<String, Float>()
		for ((key, weight) in bracket(pose, value)) {
			for ((id, turn) in turns(spec, pose, key)) out[id] = (out[id] ?: 0f) + turn * weight
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
