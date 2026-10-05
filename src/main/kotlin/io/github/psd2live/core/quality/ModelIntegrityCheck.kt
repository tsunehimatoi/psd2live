package io.github.psd2live.core.quality

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigIntegrityValidator
import org.umamo.runtime.model.PuppetModel

data class ModelIntegrityInput(val id: String, val model: PuppetModel, val expectedBounds: Map<String, Bounds>, val label: String = id)

object ModelIntegrityCheck : QualityCheck<ModelIntegrityInput> {
    override fun inspect(context: ModelIntegrityInput): QualityCheckResult {
        val neutral = RigIntegrityValidator.validateNeutralPose(context.label, context.model, context.expectedBounds)
        val findings = neutral.findings + RigIntegrityValidator.inspectHeadAnglePoses(context.label, context.model, neutral.boundsByDrawableId) +
            RigIntegrityValidator.inspectDirectionalWarpDimensions(context.label, context.model)
        return QualityCheckResult(context.id, "Neutral bounds, head-angle extremes and directional authoring assumptions for ${context.id}",
            findings.map { if (it.target == "model") it.copy(target = "model:${context.id}") else it })
    }
}
