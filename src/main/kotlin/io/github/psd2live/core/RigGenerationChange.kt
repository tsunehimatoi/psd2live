package io.github.psd2live.core

/**
 * Whether an edit changes what the generators make: a generation setting, or a layer's classification. A generated
 * model then merges the new generation onto the user's rig ([RigRegenerationCheckpoint]); an imported one migrates.
 * New source pieces inherit classification without changing the existing generated rig.
 */
internal object RigGenerationChange {
    fun changed(current: RigPreviewModel, after: PipelineConfig): Boolean {
        val before = current.config
        if (settingsChanged(before, after)) return true
        return current.analysis.source.layers.any { layer ->
            if (before.layerOverrides[layer.id.raw] == after.layerOverrides[layer.id.raw]) false else {
                val old = CharacterAnalyzer.classify(layer, before).semantic
                val next = CharacterAnalyzer.classify(layer, after).semantic
                old.type != next.type || old.tag != next.tag || old.side != next.side ||
                    old.parameter != next.parameter || old.switchId != next.switchId
            }
        }
    }

    private fun settingsChanged(before: PipelineConfig, after: PipelineConfig): Boolean =
        before.meshOnly != after.meshOnly || (!before.meshOnly && before.generateDeformers) != (!after.meshOnly && after.generateDeformers) ||
            before.featureDisplacementEnabled != after.featureDisplacementEnabled || before.mouthOutlineEnabled != after.mouthOutlineEnabled ||
            before.mouthShape != after.mouthShape || before.mouthCurve != after.mouthCurve || before.mouthColor != after.mouthColor ||
            before.mouthThickness != after.mouthThickness ||
            ((!before.meshOnly || !after.meshOnly) && (before.headTurnStrength != after.headTurnStrength || before.bodyStrength != after.bodyStrength)) ||
            before.rigTuning != after.rigTuning
}
