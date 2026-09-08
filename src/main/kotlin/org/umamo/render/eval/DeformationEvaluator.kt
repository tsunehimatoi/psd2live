package org.umamo.render.eval

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * Computed render state for one parameter pose. [worldPositions] are interleaved (x,y) world vertices
 * per drawable (in the CMO3/MOC3 reverse-coordinate / Y-down model space). [drawOrder] and
 * [opacity] are the per-drawable scalars blended from the same keyform grid with the same weights -
 * [drawOrder] is the primary render-order sort key (the parts-tree order breaks ties), [opacity] is
 * multiplied into the drawable's alpha. All three maps share the same visible-drawable keys; hidden
 * drawables are absent from every map.
 */
class DeformedGeometry(
	val worldPositions: Map<DrawableId, FloatArray>,
	val drawOrder: Map<DrawableId, Float>,
	val opacity: Map<DrawableId, Float>,
)

/**
 * Evaluates a [PuppetModel] at a set of parameter values into drawable geometry.
 *
 * The core math is parameter-driven morph blending (`p = base + Σ wᵢ·Δᵢ`, multilinear across the
 * N-D keyform grid) composed through the deformer hierarchy. The weights are cheap and computed
 * CPU-side; the per-vertex delta-sum runs in a vertex shader in `:gpu`.
 *
 * パラメータ値からドロウアブルのジオメトリを評価する契約。
 */
interface DeformationEvaluator {
	/**
	 * Evaluates [model] at [parameters]; unspecified parameters fall back to their defaults.
	 *
	 * @param PuppetModel model      The rig.
	 * @param Map         parameters Parameter id → value (partial; the rest default).
	 * @return DeformedGeometry World positions per visible drawable.
	 */
	fun evaluate(model: PuppetModel, parameters: Map<ParameterId, Float>): DeformedGeometry
}