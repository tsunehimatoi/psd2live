package org.umamo.render.eval

import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.flagAt
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.atan2

/**
 * A deformer's baked world transform - a warp lattice or a rotation affine - through which child
 * deformers and bound meshes transform their points. [accY] is the Y-scale accumulator passed to
 * child rotation deformers (warps pass the parent's through; rotations contribute their world scale).
 *
 * The three `accumulated*` channels are the deformer's own render channels composed with every
 * ancestor's, and they apply to every DRAWABLE under this deformer (see [DeformerChannels] for the
 * composition rules and [preparePose] for where they land). Riggers key a deformer's opacity to
 * show and hide a whole subtree, so these are not a decorative extra - ignoring them renders effect
 * subtrees permanently visible.
 */
internal sealed interface DeformerWorld {
	val accY: Float

	/** This deformer's opacity times every ancestor's; multiplies each descendant drawable's own. */
	val accumulatedOpacity: Float

	/** This deformer's multiply color composed with every ancestor's (componentwise product). */
	val accumulatedMultiplyColor: ColorRgb

	/** This deformer's screen color composed with every ancestor's (`a + b - a*b` per channel). */
	val accumulatedScreenColor: ColorRgb

	/**
	 * Transforms point `(x, y)` through this deformer, writing to `out[outIndex]`,`out[outIndex+1]`.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination for the transformed point.
	 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
	 */
	fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int)
}

/**
 * A deformer's render channels at one point in the cascade - either its own blended local values or
 * those composed with its ancestors'.
 *
 * @property Float    opacity       Opacity in [0,1].
 * @property ColorRgb multiplyColor Multiply tint (identity = white).
 * @property ColorRgb screenColor   Screen tint (identity = black).
 */
internal class DeformerChannels(
	val opacity: Float,
	val multiplyColor: ColorRgb,
	val screenColor: ColorRgb,
) {
	companion object {
		/** The no-op channels a root deformer composes against. */
		val Identity: DeformerChannels =
			DeformerChannels(1f, ColorRgb.MultiplyIdentity, ColorRgb.ScreenIdentity)
	}
}

/**
 * Composes a deformer's local channels onto its parent's accumulated ones.
 *
 * Opacity and multiply color compose by product; screen color composes by the screen operator
 * `a + b - a*b`. The identities are chosen so a ROOT deformer needs no special case: multiplying by
 * white and screening with black both return the local value unchanged.
 *
 * @param DeformerChannels local  This deformer's own blended channels.
 * @param DeformerWorld?   parent The parent's world transform, or null at the root.
 * @return DeformerChannels The accumulated channels to hand to children and descendant drawables.
 */
internal fun cascadeDeformerChannels(local: DeformerChannels, parent: DeformerWorld?): DeformerChannels {
	val parentOpacity = parent?.accumulatedOpacity ?: 1f
	val parentMultiply = parent?.accumulatedMultiplyColor ?: ColorRgb.MultiplyIdentity
	val parentScreen = parent?.accumulatedScreenColor ?: ColorRgb.ScreenIdentity
	return DeformerChannels(
		opacity = local.opacity * parentOpacity,
		multiplyColor =
			ColorRgb(
				local.multiplyColor.red * parentMultiply.red,
				local.multiplyColor.green * parentMultiply.green,
				local.multiplyColor.blue * parentMultiply.blue,
			),
		screenColor =
			ColorRgb(
				screenCompose(local.screenColor.red, parentScreen.red),
				screenCompose(local.screenColor.green, parentScreen.green),
				screenCompose(local.screenColor.blue, parentScreen.blue),
			),
	)
}

/**
 * The screen operator on one channel: `a + b - a*b`.
 *
 * @param Float a One operand.
 * @param Float b The other.
 * @return Float The screened channel.
 */
internal fun screenCompose(a: Float, b: Float): Float = a + b - a * b

/**
 * A warp deformer's world transform: its baked world control points + lattice mode. The control-point
 * fields are `internal` (not `private`) so the GPU renderer in this module can upload them as the
 * shader's lattice uniforms - the same baked data the CPU `apply` walks via [warpApply].
 */
internal class WarpWorld(
	internal val cp: FloatArray,
	internal val cols: Int,
	internal val rows: Int,
	internal val bilinear: Boolean,
	override val accY: Float,
	override val accumulatedOpacity: Float = 1f,
	override val accumulatedMultiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	override val accumulatedScreenColor: ColorRgb = ColorRgb.ScreenIdentity,
) : DeformerWorld {
	/**
	 * Warp-transforms `(x, y)` through the baked lattice.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination.
	 * @param Int        outIndex Index of the x slot.
	 */
	override fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int) {
		warpApply(cp, cols, rows, bilinear, x, y, out, outIndex)
	}
}

/** A rotation deformer's world transform: a baked affine. [xform] is `internal` so the GPU renderer can
 *  upload its 6 coefficients as shader uniforms (the same affine the CPU `apply` evaluates). */
internal class RotationWorld(
	internal val xform: RotationXform,
	override val accY: Float,
	override val accumulatedOpacity: Float = 1f,
	override val accumulatedMultiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	override val accumulatedScreenColor: ColorRgb = ColorRgb.ScreenIdentity,
) : DeformerWorld {
	/**
	 * Applies the rotation affine to `(x, y)`.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination.
	 * @param Int        outIndex Index of the x slot.
	 */
	override fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int) {
		xform.apply(x, y, out, outIndex)
	}
}

/**
 * Builds every deformer's world transform top-down (parents first), composing each local
 * keyform-blended transform with its parent's world transform.
 *
 * Out-of-range (hidden) deformers, and any whose ancestor is hidden, are omitted from the result. At
 * default parameters nothing is hidden, so this only affects out-of-range poses.
 *
 * @param List     deformers        All deformers in the model.
 * @param Function paramValue       Current value for a given parameter id.
 * @param Function defaultValue     Default value for a given parameter id (the blend-shape delta reference pose).
 * @param Map      channelOverrides Pending unkeyed channel edits, which win over a deformer's stored channel value.
 * @return Map<DeformerId, DeformerWorld> The built world transforms, keyed by deformer id.
 */
internal fun buildDeformerWorlds(
	deformers: List<Deformer>,
	paramValue: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float = paramValue,
	channelOverrides: Map<KeyableTarget, ChannelValue> = emptyMap(),
): Map<DeformerId, DeformerWorld> {
	val worlds = HashMap<DeformerId, DeformerWorld>(deformers.size)
	val ids = deformers.mapTo(HashSet()) { it.id }
	var pending = deformers
	var passes = 0
	while (pending.isNotEmpty() && passes <= deformers.size + 2) {
		passes++
		val deferred = ArrayList<Deformer>()
		var built = false
		for (deformer in pending) {
			val parentId = deformer.parent
			val parentKnown = parentId == null || parentId !in ids || parentId in worlds
			if (!parentKnown) {
				deferred.add(deformer)
				continue
			}
			val parentWorld = parentId?.let { worlds[it] }
			val world = buildDeformerWorld(deformer, paramValue, defaultValue, parentWorld, channelOverrides)
			if (world != null) {
				worlds[deformer.id] = world
				built = true
			}
		}
		if (!built) {
			break // no progress: the rest are hidden or orphaned
		}
		pending = deferred
	}
	return worlds
}

/**
 * Builds one deformer's world transform given its parent's (or null at the root).
 *
 * @param Deformer       deformer         The deformer.
 * @param Function       paramValue       Current value for a given parameter id.
 * @param Function       defaultValue     Default value for a given parameter id (the blend-shape delta reference pose).
 * @param DeformerWorld? parentWorld      The parent's world transform, or null.
 * @param Map            channelOverrides Pending unkeyed channel edits, which win over a deformer's stored channel value.
 * @return DeformerWorld? The world transform, or null when hidden.
 */
private fun buildDeformerWorld(
	deformer: Deformer,
	paramValue: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
	parentWorld: DeformerWorld?,
	channelOverrides: Map<KeyableTarget, ChannelValue>,
): DeformerWorld? =
	when (deformer) {
		is Deformer.Warp -> buildWarpWorld(deformer, paramValue, defaultValue, parentWorld, channelOverrides)
		is Deformer.Rotation -> buildRotationWorld(deformer, paramValue, defaultValue, parentWorld, channelOverrides)
	}

/**
 * Builds a warp's world transform: blend its keyform control points, then push each through the parent
 * (warps inherit the parent's accY; they add no scale of their own).
 *
 * @param Deformer.Warp  warp             The warp deformer.
 * @param Function       paramValue       Current value for a given parameter id.
 * @param Function       defaultValue     Default value for a given parameter id (the blend-shape delta reference pose).
 * @param DeformerWorld? parentWorld      The parent's world transform, or null.
 * @param Map            channelOverrides Pending unkeyed channel edits, which win over the warp's stored channel value.
 * @return DeformerWorld? The world transform, or null when hidden.
 */
private fun buildWarpWorld(
	warp: Deformer.Warp,
	paramValue: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
	parentWorld: DeformerWorld?,
	channelOverrides: Map<KeyableTarget, ChannelValue>,
): DeformerWorld? {
	val grid = warp.geometryGrid ?: return null
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	val pointCount = (warp.rows + 1) * (warp.columns + 1)
	val cp = FloatArray(pointCount * 2)
	for (corner in corners) {
		val controlPoints = cells[corner.linearIndex]?.form?.controlPoints ?: continue
		val count = minOf(cp.size, controlPoints.size)
		for (coordIndex in 0 until count) {
			cp[coordIndex] += corner.weight * controlPoints[coordIndex]
		}
	}
	// Blend shapes: additive control-point deltas on top of the grid blend, before parent transforms.
	warpBlendDeltas(warp, paramValue, defaultValue)?.let { deltas ->
		val count = minOf(cp.size, deltas.size)
		for (coordIndex in 0 until count) {
			cp[coordIndex] += deltas[coordIndex]
		}
	}
	// The render channels are blended, never snapped to the nearest key, or they step instead of fading
	// at mid-parameter values. Each track resolves its own corners; pre-compaction those are the same
	// corners in the same order as the control points', so the sums are unchanged.
	// A constant null in the steady state (no pending edit), so the per-channel lookups below allocate
	// no KeyableTarget keys on the per-frame path.
	val warpOverride: (FormChannel) -> ChannelValue? =
		if (channelOverrides.isEmpty()) {
			{ null }
		} else {
			{ channel -> channelOverrides[KeyableTarget(KeyformOwner.Deformer(warp.id), channel)] }
		}
	val channels =
		cascadeDeformerChannels(
			withBlendChannelDeltas(
				DeformerChannels(
					warp.channelGrids.scalarAt(FormChannel.OPACITY, warp.opacity, paramValue, warpOverride(FormChannel.OPACITY)),
					warp.channelGrids.colorAt(
						FormChannel.MULTIPLY_COLOR,
						warp.multiplyColor,
						paramValue,
						warpOverride(FormChannel.MULTIPLY_COLOR),
					),
					warp.channelGrids.colorAt(
						FormChannel.SCREEN_COLOR,
						warp.screenColor,
						paramValue,
						warpOverride(FormChannel.SCREEN_COLOR),
					),
				),
				deformerBlendChannelDeltas(
					warp.blendShapes,
					warp.channelGrids,
					warp.opacity,
					warp.multiplyColor,
					warp.screenColor,
					paramValue,
					defaultValue,
				) { form -> DeformerBlendChannelDeltas(form.opacity, form.multiplyColor, form.screenColor) },
			),
			parentWorld,
		)
	val parentAccY = parentWorld?.accY ?: 1f
	if (parentWorld != null) {
		for (pointIndex in 0 until pointCount) {
			parentWorld.apply(cp[pointIndex * 2], cp[pointIndex * 2 + 1], cp, pointIndex * 2)
		}
	}
	return WarpWorld(
		cp,
		warp.columns,
		warp.rows,
		warp.isQuadTransform,
		parentAccY,
		channels.opacity,
		channels.multiplyColor,
		channels.screenColor,
	)
}

/**
 * Builds a rotation's world transform: blend its keyform origin/angle/scale (flip snaps to the first
 * corner), compose scale with the parent's accY, and - for a non-root - derive the inherited angle by
 * numerically probing the parent transform.
 *
 * @param Deformer.Rotation rotation         The rotation deformer.
 * @param Function          paramValue       Current value for a given parameter id.
 * @param Function          defaultValue     Default value for a given parameter id (the blend-shape delta reference pose).
 * @param DeformerWorld?    parentWorld      The parent's world transform, or null.
 * @param Map               channelOverrides Pending unkeyed channel edits, which win over the rotation's stored channel value.
 * @return DeformerWorld? The world transform, or null when hidden.
 */
private fun buildRotationWorld(
	rotation: Deformer.Rotation,
	paramValue: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
	parentWorld: DeformerWorld?,
	channelOverrides: Map<KeyableTarget, ChannelValue>,
): DeformerWorld? {
	val grid = rotation.geometryGrid ?: return null
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	var originX = 0f
	var originY = 0f
	var scaleAccum = 0f
	var angleAccum = 0f
	for (corner in corners) {
		val form = cells[corner.linearIndex]?.form ?: continue
		originX += corner.weight * form.originX
		originY += corner.weight * form.originY
		scaleAccum += corner.weight * form.scale
		angleAccum += corner.weight * form.angle
	}
	// The render channels blend, never snap to the nearest key (unlike the flip flags below, which are
	// not interpolable and take the floor cell).
	// A constant null in the steady state, so the lookups allocate nothing (see buildWarpWorld's note).
	val rotationOverride: (FormChannel) -> ChannelValue? =
		if (channelOverrides.isEmpty()) {
			{ null }
		} else {
			{ channel -> channelOverrides[KeyableTarget(KeyformOwner.Deformer(rotation.id), channel)] }
		}
	val channels =
		cascadeDeformerChannels(
			withBlendChannelDeltas(
				DeformerChannels(
					rotation.channelGrids.scalarAt(
						FormChannel.OPACITY,
						rotation.opacity,
						paramValue,
						rotationOverride(FormChannel.OPACITY),
					),
					rotation.channelGrids.colorAt(
						FormChannel.MULTIPLY_COLOR,
						rotation.multiplyColor,
						paramValue,
						rotationOverride(FormChannel.MULTIPLY_COLOR),
					),
					rotation.channelGrids.colorAt(
						FormChannel.SCREEN_COLOR,
						rotation.screenColor,
						paramValue,
						rotationOverride(FormChannel.SCREEN_COLOR),
					),
				),
				deformerBlendChannelDeltas(
					rotation.blendShapes,
					rotation.channelGrids,
					rotation.opacity,
					rotation.multiplyColor,
					rotation.screenColor,
					paramValue,
					defaultValue,
				) { form -> DeformerBlendChannelDeltas(form.opacity, form.multiplyColor, form.screenColor) },
			),
			parentWorld,
		)
	// Blend shapes: additive origin/scale/angle deltas on top of the grid blend (flip stays grid-only).
	rotationBlendDeltas(rotation, paramValue, defaultValue)?.let { deltas ->
		originX += deltas.originX
		originY += deltas.originY
		scaleAccum += deltas.scale
		angleAccum += deltas.angle
	}
	// Reflections snap to the floor cell (corner 0 is the all-lower-key corner), which is what flagAt does.
	val flipX = rotation.channelGrids.flagAt(FormChannel.FLIP_X, rotation.flipX, paramValue, rotationOverride(FormChannel.FLIP_X))
	val flipY = rotation.channelGrids.flagAt(FormChannel.FLIP_Y, rotation.flipY, paramValue, rotationOverride(FormChannel.FLIP_Y))
	val angleDegrees = rotation.baseAngle + angleAccum
	val parentAccY = parentWorld?.accY ?: 1f
	val scale = parentAccY * scaleAccum
	val worldOriginX: Float
	val worldOriginY: Float
	val worldAngle: Float
	if (parentWorld == null) {
		worldOriginX = originX
		worldOriginY = originY
		worldAngle = angleDegrees
	} else {
		// Probe the parent's local rotation at the origin: displace along -Y and read the resulting
		// direction. -10 step for a rotation parent, -0.1 for a warp (degenerate steps shrink ×0.1).
		val disp = if (parentWorld is RotationWorld) -10f else -0.1f
		val scratch = FloatArray(2)
		parentWorld.apply(originX, originY, scratch, 0)
		val worldPosX = scratch[0]
		val worldPosY = scratch[1]
		var dx = 0f
		var dy = 0f
		var probeScale = 1f
		var iteration = 0
		while (iteration < 10) {
			parentWorld.apply(originX, originY + disp * probeScale, scratch, 0)
			dx = scratch[0] - worldPosX
			dy = scratch[1] - worldPosY
			if (dx != 0f || dy != 0f) {
				break
			}
			parentWorld.apply(originX, originY - disp * probeScale, scratch, 0)
			dx = worldPosX - scratch[0]
			dy = worldPosY - scratch[1]
			if (dx != 0f || dy != 0f) {
				break
			}
			probeScale *= 0.1f
			iteration++
		}
		var inherited = atan2(disp.toDouble(), 0.0).toFloat() - atan2(dy.toDouble(), dx.toDouble()).toFloat()
		while (inherited > PI_F) {
			inherited -= 2f * PI_F
		}
		while (inherited < -PI_F) {
			inherited += 2f * PI_F
		}
		worldOriginX = worldPosX
		worldOriginY = worldPosY
		worldAngle = angleDegrees - inherited * 180f / PI_F
	}
	return RotationWorld(
		rotationXform(worldAngle, scale, flipX, flipY, worldOriginX, worldOriginY),
		scale,
		channels.opacity,
		channels.multiplyColor,
		channels.screenColor,
	)
}

/**
 * Deforms a deformer-parented mesh: blend its local vertices, push each through the parent deformer's
 * world transform, then negate Y (reverse-coordinate). Returns null when the mesh is hidden.
 *
 * @param KeyformGrid    grid       The mesh's keyform grid.
 * @param FloatArray     base       The mesh's rest-pose positions (interleaved x,y).
 * @param DeformerWorld  parent     The mesh's parent deformer's world transform.
 * @param Function       paramValue Current value for a given parameter id.
 * @return FloatArray? World positions (interleaved x,y), or null when hidden.
 */
internal fun deformMeshThroughParent(grid: KeyformGrid<MeshDeltaForm>, base: FloatArray, parent: DeformerWorld, paramValue: (ParameterId) -> Float): FloatArray? {
	val local = sampleMeshLocal(grid, base, paramValue) ?: return null
	val world = FloatArray(local.size)
	val vertexCount = local.size / 2
	for (vertexIndex in 0 until vertexCount) {
		parent.apply(local[vertexIndex * 2], local[vertexIndex * 2 + 1], world, vertexIndex * 2)
		world[vertexIndex * 2 + 1] = -world[vertexIndex * 2 + 1]
	}
	return world
}

/**
 * Deforms a mesh to world positions from precomputed corners: blend `base + Σ wᵢ·Δᵢ`, push each vertex
 * through [parent] (or pass through when null - a direct, deformer-less mesh), then negate Y. This is the
 * corners-first union of [deformMeshThroughParent] and `evalDirectMeshWorld`, fed by `preparePose`'s
 * backend-neutral corner set; output is identical to the parameter-driven path.
 *
 * @param KeyformGrid        grid    The mesh's keyform grid.
 * @param FloatArray         base    The mesh's rest-pose positions (interleaved x,y).
 * @param List<WeightedCell> corners The active keyform corners + weights.
 * @param DeformerWorld?     parent  The mesh's parent deformer world transform, or null when direct.
 * @param MeshBlendState?    blend   The drawable's resolved blend-shape state, or null when binding-free.
 * @return FloatArray World positions (interleaved x,y).
 */
internal fun deformMeshWorldFromCorners(
	grid: KeyformGrid<MeshDeltaForm>?,
	base: FloatArray,
	corners: List<WeightedCell>,
	parent: DeformerWorld?,
	blend: MeshBlendState? = null,
): FloatArray {
	val local = blendLocalFromCorners(grid, base, corners)
	// Blend shapes: additive per-vertex deltas (form minus the grid-at-default reference) on top of
	// the grid blend, before the parent transform. Binding-free drawables never reach this loop.
	if (blend != null) {
		for (contribution in blend.contributions) {
			val deltas = contribution.form.positionDeltas
			val count = minOf(local.size, deltas.size)
			for (componentIndex in 0 until count) {
				val reference = blend.referenceDeltas?.get(componentIndex) ?: 0f
				local[componentIndex] += contribution.weight * (deltas[componentIndex] - reference)
			}
		}
	}
	val world = FloatArray(local.size)
	val vertexCount = local.size / 2
	for (vertexIndex in 0 until vertexCount) {
		if (parent != null) {
			parent.apply(local[vertexIndex * 2], local[vertexIndex * 2 + 1], world, vertexIndex * 2)
		} else {
			world[vertexIndex * 2] = local[vertexIndex * 2]
			world[vertexIndex * 2 + 1] = local[vertexIndex * 2 + 1]
		}
		world[vertexIndex * 2 + 1] = -world[vertexIndex * 2 + 1]
	}
	return world
}

/**
 * Applies a deformer's blend-shape channel offsets onto its grid-blended channels.
 *
 * Additive like every other blend contribution, clamped to 0..1 only AFTER the sum so a record whose
 * neighbours pull the other way is not biased.  Returns [local] untouched when nothing contributes,
 * which keeps the blend-free path allocation-identical to before.
 *
 * @param DeformerChannels            local  The deformer's own grid-blended channels.
 * @param DeformerBlendChannelDeltas? deltas The summed blend offsets, or null when none apply.
 * @return DeformerChannels The channels to cascade.
 */
internal fun withBlendChannelDeltas(
	local: DeformerChannels,
	deltas: DeformerBlendChannelDeltas?,
): DeformerChannels {
	if (deltas == null) {
		return local
	}
	return DeformerChannels(
		(local.opacity + deltas.opacity).coerceIn(0f, 1f),
		ColorRgb(
			local.multiplyColor.red + deltas.multiplyColor.red,
			local.multiplyColor.green + deltas.multiplyColor.green,
			local.multiplyColor.blue + deltas.multiplyColor.blue,
		).coerceToUnit(),
		ColorRgb(
			local.screenColor.red + deltas.screenColor.red,
			local.screenColor.green + deltas.screenColor.green,
			local.screenColor.blue + deltas.screenColor.blue,
		).coerceToUnit(),
	)
}