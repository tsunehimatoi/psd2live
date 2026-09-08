package org.umamo.render.eval

import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * CPU implementation of [DeformationEvaluator]. Deformation is split into a cheap, backend-neutral
 * [preparePose] (keyform weights + baked deformer transforms) and the per-vertex [applyCpuDeform] that
 * finishes it (blend → cascade → Y-flip → glue). The GPU path reuses the same [preparePose] and
 * uploads its inputs to a vertex shader instead of finishing on the CPU.
 */
class CpuDeformationEvaluator : DeformationEvaluator {
	/**
	 * Evaluates [model] at [parameters], falling back to each parameter's default when unspecified.
	 *
	 * @param PuppetModel model      The rig.
	 * @param Map         parameters Parameter id → value (partial; the rest default).
	 * @return DeformedGeometry World positions per visible drawable.
	 */
	override fun evaluate(model: PuppetModel, parameters: Map<ParameterId, Float>): DeformedGeometry = applyCpuDeform(model, preparePose(model, parameters))
}