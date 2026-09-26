package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Anatomical role of a bone. Anchor roles describe the torso and head that the generated body and
 * head rig already deform; they carry no deformer of their own and only give limbs a parent.
 *
 * The limits are in degrees after [SkeletonBone.direction] is applied, so a positive value swings
 * the limb away from the body on either side of the character. They are wide on purpose: a pose tool
 * that stops a drag short of where the artist is pulling reads as broken, and every rigid part turns
 * exactly at any angle anyway. Tighten them per bone when a joint must not go further.
 */
enum class BoneRole(val anchor: Boolean, val minAngle: Float, val maxAngle: Float) {
	ROOT(true, 0f, 0f),
	HIP(true, 0f, 0f),
	CHEST(true, 0f, 0f),
	NECK(true, 0f, 0f),
	HEAD(true, 0f, 0f),
	UPPER_ARM(false, -90f, 150f),
	FOREARM(false, -150f, 150f),
	HAND(false, -90f, 90f),
	THIGH(false, -60f, 120f),
	SHIN(false, -120f, 120f),
	FOOT(false, -60f, 60f),
	TAIL(false, -90f, 90f),
	WING(false, -60f, 120f),
	CUSTOM(false, -120f, 120f),
}

/**
 * One bone in canvas pixels. The bone pivots about its head; a connected child's head sits on the
 * parent's tail, like an armature in Blender.
 */
data class SkeletonBone(
	val id: String,
	val name: String,
	val parentId: String?,
	val role: BoneRole,
	val side: Side = Side.NONE,
	val headX: Float,
	val headY: Float,
	val tailX: Float,
	val tailY: Float,
	/** Meshes skinned to this bone's limb. Which vertices follow which bone is weighted automatically. */
	val drawableIds: List<String> = emptyList(),
	/** Position within a tail chain, 1-based; 0 for every other bone. */
	val chainIndex: Int = 0,
	/** +1 or -1 so that a positive parameter swings the limb away from the body on either side. */
	val direction: Float = 1f,
	/** Lower limit of the joint, degrees in parameter space. */
	val minAngle: Float = role.minAngle,
	/** Upper limit of the joint, degrees in parameter space. */
	val maxAngle: Float = role.maxAngle,
	/**
	 * Half width in pixels of the band around this bone's head joint where its meshes blend from the
	 * parent bone into this one; null sizes it from the bone lengths.
	 */
	val blendWidth: Float? = null,
) {
	init {
		require(id.isNotBlank() && id.none(Char::isISOControl)) { "Bone ID must not be blank" }
		require(parentId != id) { "A bone cannot be its own parent" }
		require(listOf(headX, headY, tailX, tailY, direction, minAngle, maxAngle).all(Float::isFinite)) { "Bone coordinates must be finite" }
		require(minAngle <= 0f && maxAngle >= 0f) { "Bone limits must contain the rest pose" }
		require(blendWidth == null || (blendWidth.isFinite() && blendWidth >= 0f)) { "Blend width must be positive" }
	}

	val length: Float get() = kotlin.math.hypot(tailX - headX, tailY - headY)

	/** Angle of the bone vector from head to tail in degrees (Cubism standard: 0° is UP, positive clockwise). */
	val angleDeg: Float
		get() {
			val dx = tailX - headX
			val dy = tailY - headY
			return if (kotlin.math.hypot(dx, dy) < 1e-4f) 0f
			else Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()
		}

	/** The parameter that drives this joint. Standard Cubism IDs where one exists. */
	val parameterId: String
		get() {
			val s = when (side) {
				Side.LEFT -> "L"
				Side.RIGHT -> "R"
				Side.NONE -> ""
			}
			return when (role) {
				BoneRole.UPPER_ARM -> "ParamArm${s}A"
				BoneRole.FOREARM -> "ParamArm${s}B"
				BoneRole.HAND -> "ParamHand$s"
				BoneRole.THIGH -> "ParamLeg${s}A"
				BoneRole.SHIN -> "ParamLeg${s}B"
				BoneRole.FOOT -> "ParamFoot$s"
				BoneRole.TAIL -> "ParamTail${chainIndex.coerceAtLeast(1)}"
				BoneRole.WING -> "ParamWing$s"
				else -> "ParamSkel_${id.filter { it.isLetterOrDigit() || it == '_' }}"
			}
		}

	val deformerId: String get() = "DeformSkel_${id.filter { it.isLetterOrDigit() || it == '_' }}"

	fun toJson(): JsonObject = buildJsonObject {
		put("id", id)
		put("name", name)
		parentId?.let { put("parent", it) }
		put("role", role.name)
		put("side", side.name)
		putJsonArray("head") { add(JsonPrimitive(headX)); add(JsonPrimitive(headY)) }
		putJsonArray("tail") { add(JsonPrimitive(tailX)); add(JsonPrimitive(tailY)) }
		putJsonArray("drawables") { drawableIds.forEach { add(JsonPrimitive(it)) } }
		put("chainIndex", chainIndex)
		put("direction", direction)
		put("minAngle", minAngle)
		put("maxAngle", maxAngle)
		blendWidth?.let { put("blendWidth", it) }
	}

	companion object {
		fun fromJson(o: JsonObject): SkeletonBone {
			val head = o.getValue("head").jsonArray
			val tail = o.getValue("tail").jsonArray
			val role = BoneRole.valueOf(o.getValue("role").jsonPrimitive.content)
			// Version 1 stored a per-bone "joint" mode; every joint now uses the same skinning, so it is ignored.
			return SkeletonBone(
				id = o.getValue("id").jsonPrimitive.content,
				name = o["name"]?.jsonPrimitive?.contentOrNull ?: o.getValue("id").jsonPrimitive.content,
				parentId = o["parent"]?.jsonPrimitive?.contentOrNull,
				role = role,
				side = o["side"]?.jsonPrimitive?.contentOrNull?.let(Side::valueOf) ?: Side.NONE,
				headX = head[0].jsonPrimitive.float,
				headY = head[1].jsonPrimitive.float,
				tailX = tail[0].jsonPrimitive.float,
				tailY = tail[1].jsonPrimitive.float,
				drawableIds = o["drawables"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
				chainIndex = o["chainIndex"]?.jsonPrimitive?.intOrNull ?: 0,
				direction = o["direction"]?.jsonPrimitive?.float ?: 1f,
				minAngle = o["minAngle"]?.jsonPrimitive?.floatOrNull ?: role.minAngle,
				maxAngle = o["maxAngle"]?.jsonPrimitive?.floatOrNull ?: role.maxAngle,
				blendWidth = o["blendWidth"]?.jsonPrimitive?.floatOrNull,
			)
		}
	}
}

/**
 * The authored skeleton. [enabled] false keeps the legacy rig where every body layer hangs under the
 * shared breath warp.
 */
data class SkeletonSpec(
	val enabled: Boolean = true,
	val bones: List<SkeletonBone> = emptyList(),
) {
	init {
		require(bones.map { it.id }.distinct().size == bones.size) { "Duplicate bone IDs" }
		val ids = bones.map { it.id }.toSet()
		require(bones.all { it.parentId == null || it.parentId in ids }) { "Bone parent not found" }
		val byId = bones.associateBy { it.id }
		for (bone in bones) {
			val seen = mutableSetOf(bone.id)
			var parent = bone.parentId
			while (parent != null) {
				require(seen.add(parent)) { "Bone hierarchy contains a cycle at ${bone.id}" }
				parent = byId[parent]?.parentId
			}
		}
	}

	fun bone(id: String): SkeletonBone? = bones.firstOrNull { it.id == id }

	fun children(id: String?): List<SkeletonBone> = bones.filter { it.parentId == id }

	/** Parents before children. */
	fun topological(): List<SkeletonBone> {
		val out = ArrayList<SkeletonBone>(bones.size)
		fun visit(parent: String?) {
			for (child in children(parent)) {
				out += child
				visit(child.id)
			}
		}
		visit(null)
		return out
	}

	/** The bone that owns [drawableId], if any; the first owner in hierarchy order wins. */
	fun ownerOf(drawableId: String): SkeletonBone? = topological().firstOrNull { drawableId in it.drawableIds }

	/** Moves a joint: the bone head and the tail of a parent that ends on it travel together. */
	fun withJointMoved(boneId: String, end: BoneEnd, x: Float, y: Float): SkeletonSpec {
		val bone = bone(boneId) ?: return this
		val (oldX, oldY) = if (end == BoneEnd.HEAD) bone.headX to bone.headY else bone.tailX to bone.tailY
		fun near(ax: Float, ay: Float) = kotlin.math.hypot(ax - oldX, ay - oldY) < 0.5f
		val linked = mutableSetOf(bone.id to end)
		if (end == BoneEnd.HEAD) {
			bone.parentId?.let { p -> bone(p)?.takeIf { near(it.tailX, it.tailY) }?.let { linked += it.id to BoneEnd.TAIL } }
		}
		val jointOwner = if (end == BoneEnd.TAIL) bone.id else bone.parentId
		for (child in bones.filter { it.parentId == jointOwner && it.id != bone.id }) {
			if (near(child.headX, child.headY)) linked += child.id to BoneEnd.HEAD
		}
		if (end == BoneEnd.TAIL) {
			for (child in children(bone.id)) if (near(child.headX, child.headY)) linked += child.id to BoneEnd.HEAD
		}
		return copy(bones = bones.map { b ->
			var next = b
			if ((b.id to BoneEnd.HEAD) in linked) next = next.copy(headX = x, headY = y)
			if ((b.id to BoneEnd.TAIL) in linked) next = next.copy(tailX = x, tailY = y)
			next
		})
	}

	/** Rebinds [drawableId] to [boneId] alone, or unbinds it when [boneId] is null. */
	fun withDrawableBound(drawableId: String, boneId: String?): SkeletonSpec {
		require(boneId == null || bone(boneId) != null) { "Bone not found: $boneId" }
		return copy(bones = bones.map { b ->
		when {
			b.id == boneId -> if (drawableId in b.drawableIds) b else b.copy(drawableIds = b.drawableIds + drawableId)
			drawableId in b.drawableIds -> b.copy(drawableIds = b.drawableIds - drawableId)
			else -> b
		}
		})
	}

	fun withBone(bone: SkeletonBone): SkeletonSpec {
		val index = bones.indexOfFirst { it.id == bone.id }
		return copy(bones = if (index < 0) bones + bone else bones.toMutableList().also { it[index] = bone })
	}

	/** Removes a bone and re-parents its children to the removed bone's parent. */
	fun withoutBone(boneId: String): SkeletonSpec {
		val bone = bone(boneId) ?: return this
		return copy(bones = bones.filter { it.id != boneId }.map { if (it.parentId == boneId) it.copy(parentId = bone.parentId) else it })
	}

	fun toJson(): JsonObject = buildJsonObject {
		put("version", 2)
		put("enabled", enabled)
		putJsonArray("bones") { bones.forEach { add(it.toJson()) } }
	}

	companion object {
		val Disabled = SkeletonSpec(enabled = false)

		fun fromJson(o: JsonObject): SkeletonSpec = SkeletonSpec(
			enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
			bones = o["bones"]?.jsonArray?.map { SkeletonBone.fromJson(it.jsonObject) }.orEmpty(),
		)
	}
}

enum class BoneEnd { HEAD, TAIL }

object SkeletonNames {
	fun bone(role: BoneRole, side: Side, chainIndex: Int = 0): String {
		val base = when (role) {
			BoneRole.ROOT -> tr("skeleton.bone.root")
			BoneRole.HIP -> tr("skeleton.bone.hip")
			BoneRole.CHEST -> tr("skeleton.bone.chest")
			BoneRole.NECK -> tr("skeleton.bone.neck")
			BoneRole.HEAD -> tr("skeleton.bone.head")
			BoneRole.UPPER_ARM -> tr("skeleton.bone.upperArm")
			BoneRole.FOREARM -> tr("skeleton.bone.forearm")
			BoneRole.HAND -> tr("skeleton.bone.hand")
			BoneRole.THIGH -> tr("skeleton.bone.thigh")
			BoneRole.SHIN -> tr("skeleton.bone.shin")
			BoneRole.FOOT -> tr("skeleton.bone.foot")
			BoneRole.TAIL -> tr("skeleton.bone.tail", chainIndex)
			BoneRole.WING -> tr("skeleton.bone.wing")
			BoneRole.CUSTOM -> tr("skeleton.bone.custom")
		}
		return when (side) {
			Side.LEFT -> tr("skeleton.side.left", base)
			Side.RIGHT -> tr("skeleton.side.right", base)
			Side.NONE -> base
		}
	}
}
