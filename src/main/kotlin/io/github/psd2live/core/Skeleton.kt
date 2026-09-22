package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Which limb a bone belongs to. The chain decides the bone's generated name, the parameter that
 * drives it and the phase the idle motion swings it on, so two arms read as a pair rather than as
 * two unrelated pivots.
 */
enum class BoneChain { SPINE, ARM, LEG, TAIL, WING }

/**
 * How a joint reaches the artwork.
 *
 * A joint between two ArtMeshes has a seam to pivot about, so it lowers to a rotation deformer: the
 * forearm turns about the elbow and the upper arm does not follow. A joint *inside* one mesh has no
 * seam — bending an unbroken arm at the elbow has to curve the mesh itself, which is a deform path
 * along the limb. Which one a joint gets is a fact about how the PSD was cut, not a preference.
 */
enum class BoneBinding { ROTATION, PATH }

/**
 * What a bone's parameter does to the bone at the parameter's positive extreme, with the negative
 * extreme mirroring it. [angle] is the rotation about the joint in degrees; [shiftX]/[shiftY] move
 * the joint itself in canvas pixels (the crouch and the weight shift are translations, not turns);
 * [scale] resizes everything below the joint.
 *
 * A drive is deliberately a *response*, not a curve: the rig holds three keys ([min], [default],
 * [max]) and every animation is a parameter value between them. That keeps a hand-edited keyform
 * authoritative — the motions in [SkeletonMotions] only ever write parameter values.
 */
data class BoneDrive(
	val parameterId: String,
	val label: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val angle: Float = 0f,
	val shiftX: Float = 0f,
	val shiftY: Float = 0f,
	val scale: Float = 1f,
) {
	init {
		require(parameterId.isNotBlank()) { "A bone drive needs a parameter id" }
		require(min.isFinite() && max.isFinite() && default.isFinite() && min < max && default in min..max) {
			"Bone drive $parameterId needs a finite range with minimum < maximum and default inside it"
		}
		require(listOf(angle, shiftX, shiftY, scale).all(Float::isFinite)) { "Bone drive $parameterId has a non-finite response" }
		require(scale > 1e-4f) { "Bone drive $parameterId must keep a positive scale" }
	}

	/**
	 * The parameter values this drive keys. [default] is always among them so the neutral pose stays
	 * a key of its own — without it a rig at rest would interpolate between the two extremes.
	 */
	val keys: FloatArray
		get() = floatArrayOf(min, default, max).distinct().sorted().toFloatArray()

	/**
	 * How far along the drive [value] sits: -1 at [min], 0 at [default], 1 at [max]. The two sides are
	 * scaled independently, so an asymmetric range (a 0..1 crouch) still reaches its extremes exactly.
	 */
	fun phase(value: Float): Float {
		if (value >= default) {
			val span = max - default
			return if (span <= 1e-6f) 0f else ((value - default) / span).coerceIn(0f, 1f)
		}
		val span = default - min
		return if (span <= 1e-6f) 0f else -((default - value) / span).coerceIn(0f, 1f)
	}

	fun toJson(): JsonObject = buildJsonObject {
		put("parameterId", parameterId)
		put("label", label)
		put("min", min)
		put("max", max)
		put("default", default)
		put("angle", angle)
		put("shiftX", shiftX)
		put("shiftY", shiftY)
		put("scale", scale)
	}

	companion object {
		fun fromJson(json: JsonObject): BoneDrive = BoneDrive(
			parameterId = json.getValue("parameterId").jsonPrimitive.content,
			label = json["label"]?.jsonPrimitive?.content ?: json.getValue("parameterId").jsonPrimitive.content,
			min = json.getValue("min").jsonPrimitive.float,
			max = json.getValue("max").jsonPrimitive.float,
			default = json.getValue("default").jsonPrimitive.float,
			angle = json["angle"]?.jsonPrimitive?.float ?: 0f,
			shiftX = json["shiftX"]?.jsonPrimitive?.float ?: 0f,
			shiftY = json["shiftY"]?.jsonPrimitive?.float ?: 0f,
			scale = json["scale"]?.jsonPrimitive?.float ?: 1f,
		)
	}
}

/**
 * One joint of the inferred armature.
 *
 * [pivotX]/[pivotY] and [tipX]/[tipY] are **absolute canvas pixels**, the same space [Bounds] and
 * [RigAnchors] use. Absolute is what the canvas session can draw and drag directly; the parent-local
 * pivot a rotation deformer actually stores is derived once, at lowering time, by subtracting the
 * parent bone's pivot (see [Skeleton.localPivotOf]).
 *
 * [layerIds] are the classified layers this bone carries *directly*. A descendant bone carries its
 * own, so the upper arm does not list the forearm: the forearm's rotation nests under the upper
 * arm's and inherits the turn through the deformer tree instead of being keyed twice.
 */
data class SkeletonBone(
	val id: String,
	val parentId: String?,
	val chain: BoneChain,
	val side: Side,
	/** 0 at the chain's root joint, increasing distally. Shoulder 0, elbow 1, wrist 2. */
	val segment: Int,
	val label: String,
	val pivotX: Float,
	val pivotY: Float,
	val tipX: Float,
	val tipY: Float,
	val layerIds: List<String>,
	val binding: BoneBinding,
	/** Null for a bone that only carries hierarchy, such as the chest the arms hang from. */
	val drive: BoneDrive?,
) {
	init {
		require(id.isNotBlank() && id.none(Char::isISOControl)) { "A bone needs a nonblank id" }
		require(parentId != id) { "Bone $id cannot parent itself" }
		require(segment >= 0) { "Bone $id has a negative segment" }
		require(listOf(pivotX, pivotY, tipX, tipY).all(Float::isFinite)) { "Bone $id has a non-finite joint" }
		require(layerIds.distinct().size == layerIds.size) { "Bone $id lists a layer twice" }
	}

	/** Joint-to-tip distance in canvas pixels: the rotation deformer's handle length. */
	val length: Float get() = hypot(tipX - pivotX, tipY - pivotY)

	/** The deformer id this bone lowers to. Prefixed so a bone is recognizable in the rig tree. */
	val deformerId: String get() = "Bone$id"

	fun toJson(): JsonObject = buildJsonObject {
		put("id", id)
		parentId?.let { put("parentId", it) }
		put("chain", chain.name)
		put("side", side.name)
		put("segment", segment)
		put("label", label)
		put("pivotX", pivotX)
		put("pivotY", pivotY)
		put("tipX", tipX)
		put("tipY", tipY)
		put("binding", binding.name)
		put("layerIds", buildJsonArray { layerIds.forEach { add(it) } })
		drive?.let { put("drive", it.toJson()) }
	}

	companion object {
		fun fromJson(json: JsonObject): SkeletonBone = SkeletonBone(
			id = json.getValue("id").jsonPrimitive.content,
			parentId = json["parentId"]?.jsonPrimitive?.content,
			chain = BoneChain.valueOf(json.getValue("chain").jsonPrimitive.content),
			side = Side.valueOf(json["side"]?.jsonPrimitive?.content ?: Side.NONE.name),
			segment = json["segment"]?.jsonPrimitive?.int ?: 0,
			label = json["label"]?.jsonPrimitive?.content ?: json.getValue("id").jsonPrimitive.content,
			pivotX = json.getValue("pivotX").jsonPrimitive.float,
			pivotY = json.getValue("pivotY").jsonPrimitive.float,
			tipX = json.getValue("tipX").jsonPrimitive.float,
			tipY = json.getValue("tipY").jsonPrimitive.float,
			layerIds = json["layerIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
			binding = BoneBinding.valueOf(json["binding"]?.jsonPrimitive?.content ?: BoneBinding.ROTATION.name),
			drive = json["drive"]?.jsonObject?.let(BoneDrive::fromJson),
		)
	}
}

/**
 * The armature a rig is built around: a forest of [SkeletonBone]s over the classified layers.
 *
 * This is authoring intent, not rig output. It is persisted in [RigEditOverlay] and lowered into
 * rotation deformers, deform paths, parameters and keyforms by [SkeletonRigLowering] on every base
 * rig build — so editing a joint is a rebuild, exactly like editing a layer's mesh settings, and the
 * lowered deformers never drift from the skeleton that explains them.
 */
data class Skeleton(val bones: List<SkeletonBone>) {
	init {
		require(bones.map { it.id }.distinct().size == bones.size) { "Duplicate bone ids" }
		val byId = bones.associateBy { it.id }
		for (bone in bones) {
			val parentId = bone.parentId ?: continue
			require(parentId in byId) { "Bone ${bone.id} names a missing parent $parentId" }
		}
		for (bone in bones) {
			var walker = bone.parentId
			var steps = 0
			while (walker != null) {
				require(walker != bone.id) { "Bone cycle through ${bone.id}" }
				require(steps++ <= bones.size) { "Bone cycle through ${bone.id}" }
				walker = byId.getValue(walker).parentId
			}
		}
		val claimed = mutableMapOf<String, String>()
		for (bone in bones) {
			for (layerId in bone.layerIds) {
				// A PATH bone bends its parent's mesh rather than carrying one of its own, so it is the
				// one case where two bones legitimately name the same layer.
				val holder = claimed.put(layerId, bone.id)
				if (holder != null) {
					val sharesWithParent = bone.binding == BoneBinding.PATH && bone.parentId == holder ||
						byId[holder]?.binding == BoneBinding.PATH && byId[holder]?.parentId == bone.id
					require(sharesWithParent) { "Layer $layerId is claimed by both $holder and ${bone.id}" }
					claimed[layerId] = holder
				}
			}
		}
	}

	val byId: Map<String, SkeletonBone> = bones.associateBy { it.id }

	val isEmpty: Boolean get() = bones.isEmpty()

	fun childrenOf(id: String): List<SkeletonBone> = bones.filter { it.parentId == id }

	fun roots(): List<SkeletonBone> = bones.filter { it.parentId == null }

	/**
	 * Parents before children. Lowering walks this order so a bone's rotation deformer can name a
	 * parent that already exists, and so a local pivot is always measured against a placed joint.
	 */
	fun topological(): List<SkeletonBone> {
		val ordered = mutableListOf<SkeletonBone>()
		val placed = mutableSetOf<String>()
		fun visit(bone: SkeletonBone) {
			if (!placed.add(bone.id)) return
			ordered += bone
			childrenOf(bone.id).forEach(::visit)
		}
		roots().forEach(::visit)
		// A cycle is rejected in init, so anything left here is unreachable rather than circular.
		bones.filterNot { it.id in placed }.forEach(::visit)
		return ordered
	}

	/**
	 * The pivot a bone's rotation deformer stores: its canvas pivot relative to its parent bone's.
	 *
	 * Every bone lowers with a zero base angle, so the whole chain composes to a pure translation and
	 * the local pivot is a plain difference. That is also what lets a bone's children be normalized
	 * against a unit frame at its canvas pivot — see [SkeletonRigLowering.frameFor].
	 */
	fun localPivotOf(bone: SkeletonBone): Pair<Float, Float> {
		val parent = bone.parentId?.let(byId::get) ?: return bone.pivotX to bone.pivotY
		return (bone.pivotX - parent.pivotX) to (bone.pivotY - parent.pivotY)
	}

	/** The bone that carries [layerId] through a rotation, ignoring the path bones that bend it. */
	fun rotationOwnerOf(layerId: String): SkeletonBone? =
		bones.firstOrNull { it.binding == BoneBinding.ROTATION && layerId in it.layerIds }

	/** Every bone in [chain] on [side], proximal first. */
	fun chain(chain: BoneChain, side: Side): List<SkeletonBone> =
		bones.filter { it.chain == chain && it.side == side }.sortedBy { it.segment }

	fun withBone(bone: SkeletonBone): Skeleton {
		val index = bones.indexOfFirst { it.id == bone.id }
		return Skeleton(if (index < 0) bones + bone else bones.toMutableList().also { it[index] = bone })
	}

	/** Removes [id] and everything under it; a joint never survives without the joint it hangs from. */
	fun withoutBone(id: String): Skeleton {
		val doomed = mutableSetOf<String>()
		fun visit(boneId: String) {
			if (!doomed.add(boneId)) return
			childrenOf(boneId).forEach { visit(it.id) }
		}
		visit(id)
		return Skeleton(bones.filterNot { it.id in doomed })
	}

	/**
	 * Moves [id]'s joint to ([x], [y]), dragging the bones below it by the same amount.
	 *
	 * Descendants follow because a joint is a position in the limb, not an offset into it: pulling a
	 * shoulder to where the seam really is should not leave the elbow behind in the old arm.
	 */
	fun withJointMoved(id: String, x: Float, y: Float): Skeleton {
		val bone = byId[id] ?: return this
		val dx = x - bone.pivotX
		val dy = y - bone.pivotY
		if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return this
		val moved = mutableSetOf<String>()
		fun visit(boneId: String) {
			if (!moved.add(boneId)) return
			childrenOf(boneId).forEach { visit(it.id) }
		}
		visit(id)
		return Skeleton(
			bones.map { candidate ->
				when {
					candidate.id == id -> candidate.copy(pivotX = x, pivotY = y, tipX = candidate.tipX + dx, tipY = candidate.tipY + dy)
					candidate.id in moved -> candidate.copy(
						pivotX = candidate.pivotX + dx,
						pivotY = candidate.pivotY + dy,
						tipX = candidate.tipX + dx,
						tipY = candidate.tipY + dy,
					)
					else -> candidate
				}
			},
		)
	}

	/** Moves only [id]'s tip, which aims the bone without disturbing the joint it turns about. */
	fun withTipMoved(id: String, x: Float, y: Float): Skeleton {
		val bone = byId[id] ?: return this
		return withBone(bone.copy(tipX = x, tipY = y))
	}

	fun toJson(): JsonObject = buildJsonObject {
		put("bones", buildJsonArray { bones.forEach { add(it.toJson()) } })
	}

	companion object {
		val Empty = Skeleton(emptyList())

		fun fromJson(json: JsonObject): Skeleton =
			Skeleton(json["bones"]?.jsonArray.orEmpty().map { SkeletonBone.fromJson(it.jsonObject) })
	}
}

/** The parameter ids the lowered skeleton drives, so motions and the rig agree on one vocabulary. */
object SkeletonParameters {
	const val CROUCH = "ParamBodyCrouch"
	const val WEIGHT = "ParamBodyWeight"
	const val LEAN = "ParamBodyLean"

	/** The parameter folder the lowered bone parameters are filed under. */
	const val GROUP = "ParamGroupSkeleton"

	fun sideTag(side: Side): String = when (side) {
		Side.LEFT -> "L"
		Side.RIGHT -> "R"
		Side.NONE -> ""
	}

	fun arm(side: Side, segment: Int): String = "ParamArm${sideTag(side)}${segmentTag(segment)}"

	fun leg(side: Side, segment: Int): String = "ParamLeg${sideTag(side)}${segmentTag(segment)}"

	fun tail(segment: Int): String = "ParamTail${segment + 1}"

	fun wing(side: Side): String = "ParamWing${sideTag(side)}"

	/** A, B, C… so a chain reads proximal to distal without a digit colliding with a side letter. */
	private fun segmentTag(segment: Int): String = ('A' + segment.coerceIn(0, 25)).toString()
}