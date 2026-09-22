package io.github.psd2live.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.github.psd2live.core.BoneBinding
import io.github.psd2live.core.BoneChain
import io.github.psd2live.core.Skeleton
import io.github.psd2live.core.SkeletonBone
import kotlin.math.hypot
import kotlin.math.max

/** Which end of a bone a gesture grabbed: the joint it turns about, or the end that aims it. */
internal enum class BoneHandle { NONE, PIVOT, TIP }

/**
 * The canvas-side skeleton editor.
 *
 * Nothing here touches the rig. The session holds a candidate [Skeleton] that the artist nudges into
 * place, and only [CanvasEditor.confirmSkeleton] hands it to the rig — which then rebuilds around it.
 * That is the same place-then-confirm shape the Warp and Rotation creators use, for the same reason:
 * an armature is a claim about anatomy, and a claim should be reviewable before it costs a rebuild.
 *
 * Joints are canvas pixels throughout, the space [io.github.psd2live.core.Bounds] and the inference
 * already speak. Screen is display-only, and the parent-local pivot a rotation deformer stores is
 * derived at lowering time rather than tracked here.
 */
@Stable
internal class SkeletonEditSession(
	initial: Skeleton,
	/** True when the rig already had this armature, which makes Confirm an edit rather than a create. */
	val wasOnRig: Boolean,
) {
	var skeleton by mutableStateOf(initial)
		private set

	var selectedBoneId by mutableStateOf(initial.bones.firstOrNull { it.drive != null }?.id ?: initial.bones.firstOrNull()?.id)

	var hoveredBoneId by mutableStateOf<String?>(null)

	/** The bone and end a live drag grabbed; null between gestures. */
	var dragBoneId by mutableStateOf<String?>(null)
		private set

	var dragHandle by mutableStateOf(BoneHandle.NONE)
		private set

	val selectedBone: SkeletonBone? get() = selectedBoneId?.let(skeleton.byId::get)

	/** True once the artist has changed anything, which is what makes Confirm worth offering. */
	var isDirty by mutableStateOf(false)
		private set

	fun replace(next: Skeleton) {
		if (next == skeleton) return
		skeleton = next
		isDirty = true
		if (selectedBoneId !in next.byId) selectedBoneId = next.bones.firstOrNull()?.id
	}

	/** Re-runs the inference, discarding hand adjustments — the escape hatch from a bad drag. */
	fun reset(inferred: Skeleton) {
		skeleton = inferred
		isDirty = wasOnRig
		selectedBoneId = inferred.bones.firstOrNull { it.drive != null }?.id ?: inferred.bones.firstOrNull()?.id
	}

	/**
	 * The colour a bone is drawn in, shared with the art it carries.
	 *
	 * Keyed on the bone id through the same palette the hierarchy and layer table use, so a limb keeps
	 * one hue everywhere it appears and matching a joint to its ArtMesh is a matter of looking.
	 */
	fun colorOf(boneId: String): Color = ComponentPalette.strong(boneId).let {
		Color(it.red, it.green, it.blue, 255)
	}

	/**
	 * The canvas wash for every bone's layers: strong for the bone in focus, faint for the rest.
	 *
	 * Every bone is washed, not just the selected one, because the question the artist is answering is
	 * "did each joint claim the right art" — and that is a question about all of them at once.
	 */
	fun tintByLayerId(): Map<String, Int> {
		val focus = hoveredBoneId ?: selectedBoneId
		val tints = mutableMapOf<String, Int>()
		for (bone in skeleton.bones) {
			if (bone.layerIds.isEmpty()) continue
			val color = ComponentPalette.strong(bone.id)
			val alpha = if (bone.id == focus) 0x8C else 0x30
			val argb = (alpha shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
			// A path bone shares its parent's layers; the parent's own wash is the one that reads.
			for (layerId in bone.layerIds) if (bone.binding == BoneBinding.ROTATION || layerId !in tints) {
				tints[layerId] = argb
			}
		}
		return tints
	}

	/** Canvas pixels to screen. World negates Y, so canvas Y runs down the screen as it does in the PSD. */
	fun screenOf(x: Float, y: Float, viewport: CanvasViewport): Offset =
		Offset(viewport.x(x).toFloat(), viewport.yFromWorld(-y).toFloat())

	/** Screen back to canvas pixels. */
	fun canvasOf(point: Offset, viewport: CanvasViewport): Pair<Float, Float> =
		viewport.canvasX(point.x) to viewport.canvasY(point.y)

	/**
	 * What [point] would grab: the nearest joint or tip within [radius] screen pixels, else the bone
	 * whose shaft it lies on. Joints win over tips, and tips over shafts, because a joint is the thing
	 * the artist came to move and the others are only there to aim and to select.
	 */
	fun hit(point: Offset, viewport: CanvasViewport, radius: Float = 11f): Pair<String, BoneHandle>? {
		var bestPivot: Pair<String, Float>? = null
		var bestTip: Pair<String, Float>? = null
		var bestShaft: Pair<String, Float>? = null
		for (bone in skeleton.bones) {
			val pivot = screenOf(bone.pivotX, bone.pivotY, viewport)
			val tip = screenOf(bone.tipX, bone.tipY, viewport)
			val toPivot = hypot(point.x - pivot.x, point.y - pivot.y)
			if (toPivot <= radius && (bestPivot == null || toPivot < bestPivot.second)) bestPivot = bone.id to toPivot
			val toTip = hypot(point.x - tip.x, point.y - tip.y)
			if (toTip <= radius && (bestTip == null || toTip < bestTip.second)) bestTip = bone.id to toTip
			val toShaft = distanceToSegment(point, pivot, tip)
			if (toShaft <= radius && (bestShaft == null || toShaft < bestShaft.second)) bestShaft = bone.id to toShaft
		}
		bestPivot?.let { return it.first to BoneHandle.PIVOT }
		bestTip?.let { return it.first to BoneHandle.TIP }
		bestShaft?.let { return it.first to BoneHandle.NONE }
		return null
	}

	fun beginDrag(boneId: String, handle: BoneHandle) {
		dragBoneId = boneId
		dragHandle = handle
		selectedBoneId = boneId
	}

	fun endDrag() {
		dragBoneId = null
		dragHandle = BoneHandle.NONE
	}

	/** Applies a live drag of the grabbed end to [point]. */
	fun drag(point: Offset, viewport: CanvasViewport) {
		val boneId = dragBoneId ?: return
		val (x, y) = canvasOf(point, viewport)
		replace(
			when (dragHandle) {
				BoneHandle.PIVOT -> skeleton.withJointMoved(boneId, x, y)
				BoneHandle.TIP -> skeleton.withTipMoved(boneId, x, y)
				BoneHandle.NONE -> return
			},
		)
	}

	/**
	 * Why [boneId] pivots or bends, phrased for the panel.
	 *
	 * The binding is not a preference, so there is no control for it: a joint between two ArtMeshes has
	 * a seam to pivot about and a joint inside one does not, and neither fact is something this editor
	 * can change — turning a seam into a bend would mean merging two meshes. Moving the joint to where
	 * the seam actually is, which the artist *can* do, does not change which of the two it is.
	 */
	fun bindingReason(boneId: String): String {
		val bone = skeleton.byId[boneId] ?: return ""
		return io.github.psd2live.i18n.tr(
			when (bone.binding) {
				BoneBinding.ROTATION -> "editor.skeleton.whyRotation"
				BoneBinding.PATH -> "editor.skeleton.whyPath"
			},
		)
	}

	/** Retunes how far a joint swings at its parameter's extreme, in degrees. */
	fun setSwing(boneId: String, degrees: Float) {
		val bone = skeleton.byId[boneId] ?: return
		val drive = bone.drive ?: return
		val clamped = degrees.coerceIn(0f, 45f)
		if (kotlin.math.abs(drive.angle - clamped) < 1e-3f) return
		replace(skeleton.withBone(bone.copy(drive = drive.copy(angle = clamped))))
	}

	/**
	 * True when [boneId] can be dropped: a limb the inference found where there is none, say.
	 *
	 * The spine is not removable. Every other chain hangs from it, so deleting a spine joint would take
	 * the whole armature with it — and "I do not want a skeleton at all" is already a button of its own.
	 */
	fun canRemove(boneId: String): Boolean =
		skeleton.byId[boneId]?.let { it.chain != BoneChain.SPINE } == true

	/** Drops [boneId] and everything hanging off it. */
	fun removeBone(boneId: String) {
		if (!canRemove(boneId)) return
		replace(skeleton.withoutBone(boneId))
	}

	/** Bones in tree order with their depth, which is how the panel indents a chain. */
	fun outline(): List<Pair<SkeletonBone, Int>> {
		val depthById = mutableMapOf<String, Int>()
		return skeleton.topological().map { bone ->
			val depth = bone.parentId?.let { depthById[it]?.plus(1) } ?: 0
			depthById[bone.id] = depth
			bone to depth
		}
	}

	private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
		val dx = b.x - a.x
		val dy = b.y - a.y
		val lengthSquared = dx * dx + dy * dy
		if (lengthSquared < 1e-6f) return hypot(point.x - a.x, point.y - a.y)
		val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
		return hypot(point.x - (a.x + dx * t), point.y - (a.y + dy * t))
	}

	/** Screen length of a bone, so the overlay can drop the decorations a short bone has no room for. */
	fun screenLength(bone: SkeletonBone, viewport: CanvasViewport): Float {
		val pivot = screenOf(bone.pivotX, bone.pivotY, viewport)
		val tip = screenOf(bone.tipX, bone.tipY, viewport)
		return max(0f, hypot(tip.x - pivot.x, tip.y - pivot.y))
	}
}
