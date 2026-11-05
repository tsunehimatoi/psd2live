package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RigRecipe

/** Complete pre-stack defaults, independent of the previous project's recipe or preset tier. */
internal object WorkspacePresetBaseline {
    val ids = listOf("legacy", "modern", "empty")

    fun configure(config: PipelineConfig, id: String): PipelineConfig {
        require(id == "legacy" || id == "modern") { "Unknown preset baseline: $id" }
        return WorkspaceInitialPreset().configure(config).copy(
            recipe = if (id == "legacy") RigRecipe.Legacy else RigRecipe.Modern,
            rigTuning = RigTuning(), meshOnly = false, generateDeformers = true,
            seeThroughBrows = true, seeThroughEyes = true,
            generatePhysics = true, exportMotions = true,
            motionIdle = true, motionBlink = true, motionNod = true, motionShake = true,
            hairSimulationFront = false, hairSimulationBack = false,
        )
    }
}
