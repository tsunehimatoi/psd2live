package org.umamo.render.eval

import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId

/*
 * Grid sampling over the runtime's shared multilinear corner selection. The corner selection,
 * bracket, and pose-sampling primitives (bindBracket / gridCorners / cellsByLinearIndex /
 * plus the blend-shape default-pose reference helpers) live in
 * org.umamo.runtime.eval (KeyformGridSampling.kt) so Moc3Import shares them - see the note there.
 * This file keeps the render-side blends built on top of them.
 */

/**
 * Samples an art-mesh keyform grid into local (parent-deformer-space) vertex positions:
 * `base + Σ wᵢ·Δᵢ` (interleaved x,y). Because the corner weights sum to 1, this equals the
 * Umamo C++ Runtime's absolute-keyform blend. Returns null when the mesh is hidden at
 * these parameters.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param FloatArray  base       The mesh's rest-pose positions.
 * @param Function    paramValue Current value for a given parameter id.
 * @return FloatArray? The local vertex positions, or null when hidden.
 */
internal fun sampleMeshLocal(
	grid: KeyformGrid<MeshDeltaForm>,
	base: FloatArray,
	paramValue: (ParameterId) -> Float,
): FloatArray? {
	val corners = gridCorners(grid, paramValue) ?: return null
	return blendLocalFromCorners(grid, base, corners)
}

/**
 * Blends a mesh's local positions from precomputed corners: `base + Σ wᵢ·Δᵢ`. Splitting corner selection
 * out of [sampleMeshLocal] lets `preparePose` compute the weights once - backend-neutrally - and feed both
 * the CPU apply path and (later) the GPU shader the same corner set.
 *
 * A null [grid] is an UNKEYED drawable, not an error: it contributes no deltas, so the result is the rest
 * mesh.  An unkeyed drawable is a normal state - a freshly created one, or one whose last keyform axis was
 * just removed - and it has to render, or the rigger cannot see the thing they are about to key.
 *
 * @param KeyformGrid?       grid    The mesh's keyform grid, or null when the drawable is unkeyed.
 * @param FloatArray         base    The mesh's rest-pose positions (interleaved x,y).
 * @param List<WeightedCell> corners The active keyform corners + weights (from [gridCorners]).
 * @return FloatArray The blended local positions (interleaved x,y).
 */
internal fun blendLocalFromCorners(grid: KeyformGrid<MeshDeltaForm>?, base: FloatArray, corners: List<WeightedCell>): FloatArray {
	val cells = if (grid != null) cellsByLinearIndex(grid) else emptyMap()
	val out = base.copyOf()
	for (corner in corners) {
		val delta = cells[corner.linearIndex]?.form?.positionDeltas ?: continue
		val count = minOf(out.size, delta.size)
		for (coordIndex in 0 until count) {
			out[coordIndex] += corner.weight * delta[coordIndex]
		}
	}
	return out
}

/**
 * An isolated part's pose-blended composite channels - what the renderer applies when it
 * composites the part's subtree layer back into the scene.
 *
 * @property Float    opacity       The composite opacity (0..1).
 * @property ColorRgb multiplyColor The composite multiply color.
 * @property ColorRgb screenColor   The composite screen color.
 */
internal class PartRenderState(
	val opacity: Float,
	val multiplyColor: ColorRgb,
	val screenColor: ColorRgb,
)

/**
 * Evaluates a direct (deformer-less) art mesh into world positions: the keyform-blended local
 * vertices with the Y component negated (`vp = (x, −y)`; only Y flips). This matches the CMO3/MOC3
 * world-space convention, which is Y-down relative to the local mesh space Umamo blends in - required for
 * preview parity with the official Cubism Editor. Returns null when the mesh is hidden at these
 * parameters. Deformer-parented meshes go through the cascade instead, which applies the same negation
 * after composing through the parent transform.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param FloatArray  base       The mesh's rest-pose positions (interleaved x,y).
 * @param Function    paramValue Current value for a given parameter id.
 * @return FloatArray? World positions (interleaved x,y), or null when hidden.
 */
internal fun evalDirectMeshWorld(
	grid: KeyformGrid<MeshDeltaForm>,
	base: FloatArray,
	paramValue: (ParameterId) -> Float,
): FloatArray? {
	val world = sampleMeshLocal(grid, base, paramValue) ?: return null
	var yIndex = 1
	while (yIndex < world.size) {
		world[yIndex] = -world[yIndex]
		yIndex += 2
	}
	return world
}