package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RigRecipe

/** Complete pre-stack defaults, independent of the previous project's recipe or preset tier. */
internal object WorkspacePresetBaseline {
    val ids get() = WorkspaceModelPresetLibrary.shared.snapshot().map { it.id }

    fun configure(config: PipelineConfig, id: String): PipelineConfig {
        val entry = WorkspaceModelPresetLibrary.shared.get(id)
        require(entry.baseline == "legacy" || entry.baseline == "modern") { "Preset has no binding baseline: $id" }
        return entry.choices.configure(config).copy(
            recipe = if (entry.baseline == "legacy") RigRecipe.Legacy else RigRecipe.Modern,
            rigTuning = RigTuning(), meshOnly = false, generateDeformers = true,
            seeThroughBrows = true, seeThroughEyes = true,
            generatePhysics = true, exportMotions = true,
            motionIdle = true, motionBlink = true, motionNod = true, motionShake = true,
            hairSimulationFront = entry.choices.frontHair == WorkspaceInitialHair.SIMULATION,
            hairSimulationBack = entry.choices.backHair == WorkspaceInitialHair.SIMULATION,
        )
    }
}
