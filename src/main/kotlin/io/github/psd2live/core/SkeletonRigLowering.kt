package io.github.psd2live.core

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.RotationPivotForm
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * A [Skeleton] expressed as the rig pieces [RigBuilder] needs: the rotation deformers the bones
 * become, the frames their children are normalized against, the parameters that drive them and the
 * deform paths that bend the limbs a rotation cannot.
 *
 * Every bone lowers with a **zero base angle**. That is the one decision the rest of this file rests
 * on: with no rotation in any bone's rest frame, the whole chain composes to a pure translation, so a
 * bone's local space is exactly canvas pixels offset by its absolute pivot. A unit-sized [Bounds] at
 * that pivot therefore makes [RigBuilder]'s existing normalization — `(x - frame.left) / frame.width`
 * — produce rotation-local coordinates without a single change to how meshes or keyforms are built.
 * The bone's aim is kept in [SkeletonBone.tipX]/[SkeletonBone.tipY] and surfaces as the deformer's
 * handle length, which is what the canvas draws.
 */
internal class LoweredSkeleton(
	val skeleton: Skeleton,
	val deformers: List<Deformer>,
	/** Bone deformer id -> the frame its children normalize against. */
	val frames: Map<String, Bounds>,
	/** Classified layer id -> the bone deformer it hangs under, and that bone's frame. */
	val parentByLayerId: Map<String, Pair<DeformerId, Bounds>>,
	val parameters: List<Parameter>,
	/** The bone the torso's body/breath warps hang from, and that bone's absolute canvas pivot. */
	val torsoParentId: DeformerId?,
	val torsoOriginX: Float,
	val torsoOriginY: Float,
	/** Layers the skeleton re-parents, which the automatic left/right pair warps must leave alone. */
	val claimedLayerIds: Set<String>,
	private val pathBonesByLayerId: Map<String, List<SkeletonBone>>,
) {
	/**
	 * The deform paths that bend [layerId]'s unbroken mesh, in the drawable's own local units.
	 *
	 * Each path runs limb-root -> bend -> limb-tip with a corner at the bend, because a corner is what
	 * makes the curve fold there instead of easing through it — the polyline joint the artist expects
	 * at an elbow. The bend point starts at the inference's guess and the artist drags it; nothing
	 * downstream assumes it is still in the middle.
	 */
	fun bendPaths(drawableId: DrawableId, layerId: String, mesh: DrawableMesh, frame: Bounds): List<DeformPath> {
		val bones = pathBonesByLayerId[layerId].orEmpty()
		if (bones.isEmpty() || mesh.indices.isEmpty()) return emptyList()
		val extent = RigGeometryTools.bounds(mesh.positions).let { max(it[2], it[3]) }
		return bones.mapNotNull { bone ->
			val anchor = rotationAncestor(bone) ?: return@mapNotNull null
			val handles = listOf(
				Triple(anchor.pivotX, anchor.pivotY, false),
				Triple(bone.pivotX, bone.pivotY, true),
				Triple(bone.tipX, bone.tipY, false),
			).map { (x, y, corner) ->
				runCatching {
					DeformPathTools.bind(
						mesh.positions,
						mesh.indices,
						(x - frame.left) / frame.width.coerceAtLeast(1e-4f),
						(y - frame.top) / frame.height.coerceAtLeast(1e-4f),
						corner,
					)
				}.getOrNull() ?: return@mapNotNull null
			}
			DeformPath(
				id = "Path${bone.deformerId}",
				drawableId = drawableId,
				points = handles,
				width = max(extent * 0.12f, 1e-3f),
				hardness = 0.5f,
				closed = false,
				editLevel = 2,
			)
		}
	}

	/**
	 * The mesh keyforms that bend [layerId] at its path joints, or null when it has none.
	 *
	 * The keys are produced by running the path deformation itself, not by an independent bend formula.
	 * That is deliberate: the shape the rig animates is then the same shape the artist sees when they
	 * drag the path's tip in the canvas, so tuning a joint by hand and re-deriving it agree.
	 */
	fun bendGrid(layerId: String, mesh: DrawableMesh, paths: List<DeformPath>): KeyformGrid<MeshDeltaForm>? {
		val bones = pathBonesByLayerId[layerId].orEmpty().filter { it.drive != null }
		if (bones.isEmpty() || paths.isEmpty()) return null
		val axes = bones.mapNotNull { bone ->
			val drive = bone.drive ?: return@mapNotNull null
			paths.firstOrNull { it.id == "Path${bone.deformerId}" } ?: return@mapNotNull null
			KeyformAxis(ParameterId(drive.parameterId), drive.keys)
		}
		if (axes.isEmpty()) return null

		val cells = mutableListOf<KeyformCell<MeshDeltaForm>>()
		val coordinate = IntArray(axes.size)
		fun visit(axisIndex: Int) {
			if (axisIndex == axes.size) {
				val moved = mutableMapOf<String, List<Pair<Float, Float>>>()
				for ((index, bone) in bones.withIndex()) {
					val drive = bone.drive ?: continue
					val path = paths.firstOrNull { it.id == "Path${bone.deformerId}" } ?: continue
					val phase = drive.phase(axes[index].keys[coordinate[index]])
					if (abs(phase) < 1e-6f) continue
					moved[path.id] = bentHandles(path, mesh.positions, drive.angle * phase)
				}
				val deformed = if (moved.isEmpty()) {
					mesh.positions
				} else {
					runCatching { DeformPathTools.deformAll(mesh.positions, paths, moved) }.getOrDefault(mesh.positions)
				}
				cells += KeyformCell(
					coordinate.copyOf(),
					MeshDeltaForm(FloatArray(mesh.positions.size) { deformed[it] - mesh.positions[it] }),
				)
				return
			}
			for (keyIndex in axes[axisIndex].keys.indices) {
				coordinate[axisIndex] = keyIndex
				visit(axisIndex + 1)
			}
		}
		visit(0)
		return KeyformGrid(axes, cells)
	}

	/**
	 * A path's handles with everything past its corner swung by [degrees] about that corner, which is
	 * the drag that bends the limb at the joint rather than dragging the whole limb sideways.
	 */
	private fun bentHandles(path: DeformPath, positions: FloatArray, degrees: Float): List<Pair<Float, Float>> {
		val handles = DeformPathTools.positions(path, positions)
		val cornerIndex = path.points.indexOfFirst { it.corner }.takeIf { it > 0 } ?: (handles.size / 2)
		val (pivotX, pivotY) = handles[cornerIndex]
		val radians = degrees * Math.PI.toFloat() / 180f
		val c = cos(radians)
		val s = sin(radians)
		return handles.mapIndexed { index, handle ->
			if (index <= cornerIndex) return@mapIndexed handle
			val dx = handle.first - pivotX
			val dy = handle.second - pivotY
			(pivotX + c * dx - s * dy) to (pivotY + s * dx + c * dy)
		}
	}

	/** The nearest ancestor that actually became a rotation deformer; a path bone is not one. */
	private fun rotationAncestor(bone: SkeletonBone): SkeletonBone? {
		var walker = bone.parentId?.let(skeleton.byId::get)
		while (walker != null && walker.binding != BoneBinding.ROTATION) {
			walker = walker.parentId?.let(skeleton.byId::get)
		}
		return walker
	}
}

internal object SkeletonRigLowering {
	/**
	 * The frame a bone's children are normalized against: a one-pixel box at the bone's absolute canvas
	 * pivot, which turns [RigBuilder]'s normalization into a plain subtraction and lands every child
	 * vertex in rotation-local space. See [LoweredSkeleton] for why that is exact.
	 */
	fun frameFor(bone: SkeletonBone): Bounds =
		Bounds(bone.pivotX, bone.pivotY, bone.pivotX + 1f, bone.pivotY + 1f)

	fun lower(skeleton: Skeleton, bodyPartId: PartId, extraPartId: PartId): LoweredSkeleton? {
		if (skeleton.isEmpty) return null
		val ordered = skeleton.topological()
		val deformers = mutableListOf<Deformer>()
		val frames = mutableMapOf<String, Bounds>()
		val parentByLayerId = mutableMapOf<String, Pair<DeformerId, Bounds>>()
		val parameters = mutableListOf<Parameter>()
		val seenParameters = mutableSetOf<String>()
		val claimed = mutableSetOf<String>()
		val pathBones = mutableMapOf<String, MutableList<SkeletonBone>>()

		for (bone in ordered) {
			bone.drive?.let { drive ->
				if (seenParameters.add(drive.parameterId)) {
					parameters += Parameter(
						id = ParameterId(drive.parameterId),
						name = drive.label.ifBlank { drive.parameterId },
						min = drive.min,
						max = drive.max,
						default = drive.default,
					)
				}
			}
			claimed += bone.layerIds

			if (bone.binding == BoneBinding.PATH) {
				// A path bone has no pivot to turn: it bends the mesh its parent already carries, so the
				// layer stays mounted where it is and only gains a path and the keyforms that use it.
				for (layerId in bone.layerIds) pathBones.getOrPut(layerId) { mutableListOf() } += bone
				continue
			}

			val frame = frameFor(bone)
			frames[bone.deformerId] = frame
			val (localX, localY) = skeleton.localPivotOf(bone)
			deformers += Deformer.Rotation(
				id = DeformerId(bone.deformerId),
				name = bone.label,
				parent = rotationParentOf(skeleton, bone)?.let { DeformerId(it.deformerId) },
				partId = if (bone.chain == BoneChain.TAIL || bone.chain == BoneChain.WING) extraPartId else bodyPartId,
				baseAngle = 0f,
				geometryGrid = geometryGrid(bone, localX, localY),
				handleLength = bone.length.takeIf { it > 1e-3f },
			)
			for (layerId in bone.layerIds) parentByLayerId[layerId] = DeformerId(bone.deformerId) to frame
		}

		// The torso keeps its body and breath warps; they simply hang off the deepest spine joint now,
		// so breathing moves the chest and no longer drags every limb with it.
		val torso = skeleton.chain(BoneChain.SPINE, Side.NONE).lastOrNull { it.binding == BoneBinding.ROTATION }
			?: ordered.firstOrNull { it.binding == BoneBinding.ROTATION }

		return LoweredSkeleton(
			skeleton = skeleton,
			deformers = deformers,
			frames = frames,
			parentByLayerId = parentByLayerId,
			parameters = parameters,
			torsoParentId = torso?.let { DeformerId(it.deformerId) },
			torsoOriginX = torso?.pivotX ?: 0f,
			torsoOriginY = torso?.pivotY ?: 0f,
			claimedLayerIds = claimed,
			pathBonesByLayerId = pathBones,
		)
	}

	/**
	 * The bone's keyed pivot track. A driven bone keys its parameter's three values; an anchor bone
	 * holds a single neutral cell, which still gives the canvas a pivot to draw and drag.
	 */
	private fun geometryGrid(bone: SkeletonBone, localX: Float, localY: Float): KeyformGrid<RotationPivotForm> {
		val drive = bone.drive
			?: return KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(localX, localY, 0f, 1f))))
		val keys = drive.keys
		return KeyformGrid(
			listOf(KeyformAxis(ParameterId(drive.parameterId), keys)),
			keys.indices.map { index ->
				val phase = drive.phase(keys[index])
				KeyformCell(
					intArrayOf(index),
					RotationPivotForm(
						localX + drive.shiftX * phase,
						localY + drive.shiftY * phase,
						drive.angle * phase,
						// Scale is a magnitude, not a direction: a crouch shortens the body at either
						// extreme of a mirrored drive rather than stretching it at one of them.
						1f + (drive.scale - 1f) * abs(phase),
					),
				)
			},
		)
	}

	private fun rotationParentOf(skeleton: Skeleton, bone: SkeletonBone): SkeletonBone? {
		var walker = bone.parentId?.let(skeleton.byId::get)
		while (walker != null && walker.binding != BoneBinding.ROTATION) {
			walker = walker.parentId?.let(skeleton.byId::get)
		}
		return walker
	}
}

/** True when this bone reaches far enough to be worth a pivot at all. */
internal fun SkeletonBone.hasReach(): Boolean = hypot(tipX - pivotX, tipY - pivotY) > 1e-3f
