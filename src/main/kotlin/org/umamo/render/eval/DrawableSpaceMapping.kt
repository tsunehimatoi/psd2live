package org.umamo.render.eval

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Newton iteration cap for the warp inverse; drag-sized motion converges in a handful of steps. */
private const val WARP_INVERSE_MAX_ITERATIONS = 20

/** Finite-difference step (in the lattice's normalized UV units) for the warp inverse's Jacobian. */
private const val WARP_INVERSE_JACOBIAN_STEP = 1e-4f

/** Damping clamp on one Newton step (UV units), so an extrapolation seam cannot fling the estimate. */
private const val WARP_INVERSE_MAX_STEP = 0.5f

/**
 * Iteration cap for the per-vertex reach-uncapped Newton in the world-rigid inverse. Higher than the
 * damped [WARP_INVERSE_MAX_ITERATIONS] because there is no per-step clamp: a far target is reached by
 * full Newton steps that re-anchor the Jacobian, converging in a handful once the estimate lands in the
 * lattice's far-field affine region; the cap only bounds a pathological (folded / multivalued) case.
 */
private const val RIGID_INVERSE_MAX_ITERATIONS = 24

/**
 * Maps between a drawable's local mesh space and the evaluator's world space at one fixed pose - the
 * `DeformedGeometry.worldPositions` space the viewport camera and picker use. Local space is whatever
 * the drawable's `DrawableMesh.positions` are stored in: model space for a direct (deformer-less)
 * drawable, the parent's normalized lattice UV for a warp child, the parent's affine frame for a
 * rotation child. The parent transform here is the FULLY COMPOSED chain (`buildDeformerWorlds` bakes
 * every ancestor in), so both directions are a single transform, never a chain walk.
 *
 * The Edit-mode gizmo is the intended caller: it projects the active drawable's local shape to world
 * for drawing and hit-testing (forward), and converts a world-space drag back into local vertex
 * positions to write into the model (inverse).
 *
 * ドロウアブルのローカル座標と評価器のワールド座標を、固定ポーズで相互変換する。
 */
class DrawableSpaceMapping internal constructor(
	private val parentWorld: DeformerWorld?,
) {
	/**
	 * Maps interleaved (x, y) local positions to world positions: the composed parent transform (or a
	 * pass-through for a direct drawable) followed by the renderer's Y negation - the exact composition
	 * of `deformMeshWorldFromCorners`.
	 *
	 * @param FloatArray local The interleaved local positions.
	 * @return FloatArray The interleaved world positions (a new array).
	 */
	fun localToWorld(local: FloatArray): FloatArray {
		val world = FloatArray(local.size)
		val vertexCount = local.size / 2
		for (vertexIndex in 0 until vertexCount) {
			if (parentWorld != null) {
				parentWorld.apply(local[vertexIndex * 2], local[vertexIndex * 2 + 1], world, vertexIndex * 2)
			} else {
				world[vertexIndex * 2] = local[vertexIndex * 2]
				world[vertexIndex * 2 + 1] = local[vertexIndex * 2 + 1]
			}
			world[vertexIndex * 2 + 1] = -world[vertexIndex * 2 + 1]
		}
		return world
	}

	/**
	 * Maps world positions back to local positions for the given vertex [indices], leaving every other
	 * vertex at its [seedLocal] value. The rotation affine inverts in closed form; the warp lattice
	 * inverts by damped Newton iteration on the forward transform, seeded per vertex from [seedLocal]
	 * (the vertex's own original UV), keeping the best estimate when it cannot converge - a vertex
	 * sticks rather than jumps. A degenerate transform (zero determinant) keeps the seed.
	 *
	 * @param FloatArray world The interleaved world positions to invert.
	 * @param FloatArray seedLocal The local positions to start from (and to keep for untouched vertices).
	 * @param Set<Int> indices The vertex indices to solve.
	 * @return FloatArray The interleaved local positions (a new array).
	 */
	fun worldToLocal(world: FloatArray, seedLocal: FloatArray, indices: Set<Int>): FloatArray {
		val local = seedLocal.copyOf()
		val scratch = FloatArray(2)
		for (vertexIndex in indices) {
			val coordIndex = vertexIndex * 2
			if (coordIndex + 1 >= world.size || coordIndex + 1 >= local.size) {
				continue
			}
			// Un-negate Y first: the parent transforms operate in the pre-negate model space.
			val targetX = world[coordIndex]
			val targetY = -world[coordIndex + 1]
			when (parentWorld) {
				null -> {
					local[coordIndex] = targetX
					local[coordIndex + 1] = targetY
				}

				is RotationWorld -> invertRotation(parentWorld, targetX, targetY, local, coordIndex)
				is WarpWorld -> invertWarp(parentWorld, targetX, targetY, local, coordIndex, scratch)
			}
		}
		return local
	}

	/**
	 * Maps world positions back to local for the given vertex [indices] with a per-vertex EXACT inverse,
	 * leaving every other vertex at its [seedLocal] value. A direct parent inverts by pure Y un-negation and
	 * a rotation parent by its closed-form affine inverse (both one step); a warp parent inverts each vertex
	 * independently by a reach-uncapped Newton ([invertWarpUncapped]) seeded from its own rest UV, so every
	 * vertex lands on its own world target - the mesh stays rigid in world space (no squish), which the
	 * shared-Jacobian linearizations cannot achieve on a curved cage.
	 *
	 * Seeding each warp vertex from its rest UV keeps neighbours converging to nearby pre-images, so the
	 * result stays coherent (no scatter) wherever the forward map is injective. Where a target sits in a
	 * folded / multivalued extrapolation region the Newton keeps its best estimate near the seed rather than
	 * flinging - so a truly unreachable vertex lags instead of boiling. Not capping the per-step reach
	 * (unlike [invertWarp]) is what lets a large grab follow instead of pinning / ovalizing; the risk traded
	 * back is that a strongly non-injective cage can still scatter far outside the lattice (the reason the
	 * shared-Jacobian inverse exists).
	 *
	 * The warp tolerance and scratch are hoisted out of the vertex loop (constant per mapping), and the
	 * Newton early-exits the moment a vertex converges, so the whole-selection Object-mode grab (hundreds of
	 * warp vertices per drawable, over many drawables) stays interactive. A degenerate Jacobian (a
	 * zero-scale deformer) keeps the seed.
	 *
	 * @param FloatArray world The interleaved transformed world targets to invert.
	 * @param FloatArray seedLocal The rest local coords (per-vertex Newton seed; kept for untouched vertices).
	 * @param FloatArray worldRest The frozen pre-transform world positions (unused here; kept for the shared
	 *   caller signature and the linearized-inverse contract).
	 * @param Set<Int> indices The vertex indices to solve.
	 * @return FloatArray The interleaved local positions (a new array).
	 */
	fun worldToLocalLinearized(world: FloatArray, seedLocal: FloatArray, worldRest: FloatArray, indices: Set<Int>): FloatArray {
		val local = seedLocal.copyOf()
		when (parentWorld) {
			null -> {
				for (vertexIndex in indices) {
					val coordIndex = vertexIndex * 2
					if (coordIndex + 1 >= world.size || coordIndex + 1 >= local.size) {
						continue
					}
					// Un-negate Y: the parent frame is the pre-negate model space.
					local[coordIndex] = world[coordIndex]
					local[coordIndex + 1] = -world[coordIndex + 1]
				}
			}

			is RotationWorld -> {
				for (vertexIndex in indices) {
					val coordIndex = vertexIndex * 2
					if (coordIndex + 1 >= world.size || coordIndex + 1 >= local.size) {
						continue
					}
					invertRotation(parentWorld, world[coordIndex], -world[coordIndex + 1], local, coordIndex)
				}
			}

			is WarpWorld -> {
				// Constant per mapping, so it's computed once here rather than per vertex.
				val toleranceSquared = warpInverseToleranceSquared(parentWorld.cp)
				val scratch = FloatArray(2)
				for (vertexIndex in indices) {
					val coordIndex = vertexIndex * 2
					if (coordIndex + 1 >= world.size || coordIndex + 1 >= local.size) {
						continue
					}
					invertWarpUncapped(parentWorld, world[coordIndex], -world[coordIndex + 1], local, coordIndex, toleranceSquared, scratch)
				}
			}
		}
		return local
	}

	/**
	 * Reach-uncapped world-rigid inverse of the warp lattice: solves `warpApply(u, v) = (targetX, targetY)`
	 * from the seed already in `local[coordIndex]`,`local[coordIndex+1]`, writing the best estimate back.
	 * Identical to [invertWarp] except it has no per-step damping clamp, so a full Newton step reaches a
	 * far target instead of pinning at [WARP_INVERSE_MAX_STEP], which keeps the mesh from pinning /
	 * ovalizing on a big grab. Allocation-free (reuses [scratch]) and early-exits at [toleranceSquared], so
	 * it stays cheap for a whole-selection Object-mode grab; [toleranceSquared] is passed in because it is
	 * constant per lattice.
	 *
	 * @param WarpWorld warp The baked world lattice.
	 * @param Float targetX The pre-negate world x to invert.
	 * @param Float targetY The pre-negate world y to invert.
	 * @param FloatArray local The output array, seeded with the starting UV at [coordIndex].
	 * @param Int coordIndex Index of the u slot in [local].
	 * @param Float toleranceSquared The squared convergence tolerance for this lattice.
	 * @param FloatArray scratch A reusable 2-slot scratch for forward evaluations.
	 */
	private fun invertWarpUncapped(warp: WarpWorld, targetX: Float, targetY: Float, local: FloatArray, coordIndex: Int, toleranceSquared: Float, scratch: FloatArray) {
		var currentU = local[coordIndex]
		var currentV = local[coordIndex + 1]
		var bestU = currentU
		var bestV = currentV
		var bestErrorSquared = Float.MAX_VALUE
		for (iteration in 0 until RIGID_INVERSE_MAX_ITERATIONS) {
			warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, currentU, currentV, scratch, 0)
			val residualX = scratch[0] - targetX
			val residualY = scratch[1] - targetY
			val errorSquared = residualX * residualX + residualY * residualY
			if (errorSquared < bestErrorSquared) {
				bestErrorSquared = errorSquared
				bestU = currentU
				bestV = currentV
			}
			if (errorSquared <= toleranceSquared) {
				break
			}
			warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, currentU + WARP_INVERSE_JACOBIAN_STEP, currentV, scratch, 0)
			val dxDu = (scratch[0] - (residualX + targetX)) / WARP_INVERSE_JACOBIAN_STEP
			val dyDu = (scratch[1] - (residualY + targetY)) / WARP_INVERSE_JACOBIAN_STEP
			warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, currentU, currentV + WARP_INVERSE_JACOBIAN_STEP, scratch, 0)
			val dxDv = (scratch[0] - (residualX + targetX)) / WARP_INVERSE_JACOBIAN_STEP
			val dyDv = (scratch[1] - (residualY + targetY)) / WARP_INVERSE_JACOBIAN_STEP
			val determinant = dxDu * dyDv - dxDv * dyDu
			if (abs(determinant) < 1e-12f) {
				break
			}
			currentU += (-residualX * dyDv + dxDv * residualY) / determinant
			currentV += (-residualY * dxDu + dyDu * residualX) / determinant
		}
		local[coordIndex] = bestU
		local[coordIndex + 1] = bestV
	}
}

/**
 * Builds the local<->world mapping for [drawableId] at the given pose, resolving parameters with the
 * evaluator's fallback (`parameters[id] ?: parameter.default ?: 0`). Returns null when the drawable
 * does not exist or its parent deformer's world transform cannot be built (a hidden ancestor) - the
 * drawable is not on screen, so there is nothing to map onto.
 *
 * @param PuppetModel model The rig.
 * @param Map parameters Parameter id -> value (partial; the rest default).
 * @param DrawableId drawableId The drawable to map.
 * @return DrawableSpaceMapping? The mapping, or null when unmappable.
 */
fun drawableSpaceMapping(model: PuppetModel, parameters: Map<ParameterId, Float>, drawableId: DrawableId): DrawableSpaceMapping? {
	val drawable = model.drawables.firstOrNull { it.id == drawableId } ?: return null
	val parentDeformerId = drawable.parentDeformerId ?: return DrawableSpaceMapping(null)
	val defaults = model.parameters.associate { it.id to it.default }
	val paramValue: (ParameterId) -> Float = { parameters[it] ?: defaults[it] ?: 0f }
	val parentWorld = buildDeformerWorlds(model.deformers, paramValue)[parentDeformerId] ?: return null
	return DrawableSpaceMapping(parentWorld)
}

/**
 * The drawable's blended LOCAL posed positions at the given pose - base plus the multilinear keyform
 * blend, before any parent deformer. This is the local shape the renderer feeds the deformer chain, so
 * a display that projects it through [DrawableSpaceMapping.localToWorld] lands exactly on the rendered
 * art at ANY pose (including between keys, where a single cell's deltas would not).
 *
 * @param PuppetModel model The rig.
 * @param Map parameters Parameter id -> value (partial; the rest default).
 * @param DrawableId drawableId The drawable to sample.
 * @return FloatArray? The interleaved local posed positions, or null when the drawable / mesh / grid is
 *   missing or the pose hides it (out of range).
 */
fun drawableLocalPosed(model: PuppetModel, parameters: Map<ParameterId, Float>, drawableId: DrawableId): FloatArray? {
	val drawable = model.drawables.firstOrNull { it.id == drawableId } ?: return null
	val mesh = drawable.mesh ?: return null
	// An unkeyed drawable sits at its rest mesh, which is exactly the state a rigger needs the gizmo to
	// show - returning null here would hide the vertices of the drawable they are about to key.
	val grid = drawable.geometryGrid ?: return mesh.positions.copyOf()
	val defaults = model.parameters.associate { it.id to it.default }
	val paramValue: (ParameterId) -> Float = { parameters[it] ?: defaults[it] ?: 0f }
	return sampleMeshLocal(grid, mesh.positions, paramValue)
}

/**
 * Closed-form inverse of the baked rotation affine `x' = c12*x + c15*y + ox, y' = c14*x + c13*y + oy`,
 * writing the solved local point to `local[coordIndex]`,`local[coordIndex+1]`. A near-zero determinant
 * (a zero-scale deformer) keeps the seed untouched.
 *
 * @param RotationWorld rotation The baked world affine.
 * @param Float targetX The pre-negate world x to invert.
 * @param Float targetY The pre-negate world y to invert.
 * @param FloatArray local The output array (seeded; written only on success).
 * @param Int coordIndex Index of the x slot in [local].
 */
private fun invertRotation(rotation: RotationWorld, targetX: Float, targetY: Float, local: FloatArray, coordIndex: Int) {
	val xform = rotation.xform
	val determinant = xform.c12 * xform.c13 - xform.c15 * xform.c14
	if (abs(determinant) < 1e-12f) {
		return
	}
	val relativeX = targetX - xform.ox
	val relativeY = targetY - xform.oy
	local[coordIndex] = (relativeX * xform.c13 - xform.c15 * relativeY) / determinant
	local[coordIndex + 1] = (xform.c12 * relativeY - xform.c14 * relativeX) / determinant
}

/**
 * Damped Newton inverse of the warp lattice: solves `warpApply(u, v) = target` starting from the seed
 * already in `local[coordIndex]`,`local[coordIndex+1]`, using a finite-difference Jacobian. Writes the
 * best estimate found (by residual) back into [local]; the tolerance scales with the lattice's control
 * point extent so convergence means sub-art-pixel accuracy at any canvas size.
 *
 * @param WarpWorld warp The baked world lattice.
 * @param Float targetX The pre-negate world x to invert.
 * @param Float targetY The pre-negate world y to invert.
 * @param FloatArray local The output array, seeded with the starting UV.
 * @param Int coordIndex Index of the u slot in [local].
 * @param FloatArray scratch A reusable 2-slot scratch for forward evaluations.
 */
private fun invertWarp(warp: WarpWorld, targetX: Float, targetY: Float, local: FloatArray, coordIndex: Int, scratch: FloatArray) {
	val toleranceSquared = warpInverseToleranceSquared(warp.cp)
	var currentU = local[coordIndex]
	var currentV = local[coordIndex + 1]
	var bestU = currentU
	var bestV = currentV
	var bestErrorSquared = Float.MAX_VALUE
	for (iteration in 0 until WARP_INVERSE_MAX_ITERATIONS) {
		warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, currentU, currentV, scratch, 0)
		val residualX = scratch[0] - targetX
		val residualY = scratch[1] - targetY
		val errorSquared = residualX * residualX + residualY * residualY
		if (errorSquared < bestErrorSquared) {
			bestErrorSquared = errorSquared
			bestU = currentU
			bestV = currentV
		}
		if (errorSquared <= toleranceSquared) {
			break
		}
		warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, currentU + WARP_INVERSE_JACOBIAN_STEP, currentV, scratch, 0)
		val dxDu = (scratch[0] - (residualX + targetX)) / WARP_INVERSE_JACOBIAN_STEP
		val dyDu = (scratch[1] - (residualY + targetY)) / WARP_INVERSE_JACOBIAN_STEP
		warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, currentU, currentV + WARP_INVERSE_JACOBIAN_STEP, scratch, 0)
		val dxDv = (scratch[0] - (residualX + targetX)) / WARP_INVERSE_JACOBIAN_STEP
		val dyDv = (scratch[1] - (residualY + targetY)) / WARP_INVERSE_JACOBIAN_STEP
		val determinant = dxDu * dyDv - dxDv * dyDu
		if (abs(determinant) < 1e-12f) {
			break
		}
		var stepU = (-residualX * dyDv + dxDv * residualY) / determinant
		var stepV = (-residualY * dxDu + dyDu * residualX) / determinant
		val stepLength = sqrt(stepU * stepU + stepV * stepV)
		if (stepLength > WARP_INVERSE_MAX_STEP) {
			val damping = WARP_INVERSE_MAX_STEP / stepLength
			stepU *= damping
			stepV *= damping
		}
		currentU += stepU
		currentV += stepV
	}
	local[coordIndex] = bestU
	local[coordIndex + 1] = bestV
}

/**
 * The squared convergence tolerance for the warp inverse, scaled to the lattice's control-point extent
 * (1e-5 of the larger bounding-box span, floored well above float noise).
 *
 * @param FloatArray cp The lattice's world control points, interleaved x,y.
 * @return Float The squared world-distance tolerance.
 */
private fun warpInverseToleranceSquared(cp: FloatArray): Float {
	var minX = Float.MAX_VALUE
	var maxX = -Float.MAX_VALUE
	var minY = Float.MAX_VALUE
	var maxY = -Float.MAX_VALUE
	val pointCount = cp.size / 2
	for (pointIndex in 0 until pointCount) {
		val x = cp[pointIndex * 2]
		val y = cp[pointIndex * 2 + 1]
		minX = minOf(minX, x)
		maxX = maxOf(maxX, x)
		minY = minOf(minY, y)
		maxY = maxOf(maxY, y)
	}
	val span = max(maxX - minX, maxY - minY)
	val tolerance = max(1e-5f * span, 1e-6f)
	return tolerance * tolerance
}