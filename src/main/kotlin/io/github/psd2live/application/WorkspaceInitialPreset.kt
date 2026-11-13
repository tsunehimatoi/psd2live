package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import kotlinx.serialization.Serializable

/** Captured start-page choices, shared by desktop and source commands. */
@Serializable
enum class WorkspaceInitialHair { CLASSIC, SIMULATION, OFF }

@Serializable
data class WorkspaceInitialPreset(
    val headStrength: Float = 1f,
    val bodyStrength: Float = 1f,
    val featureDisplacement: Boolean = false,
    val mouthOutline: Boolean = true,
    val motionBasic: Boolean = true,
    val motionSkeleton: Boolean = true,
    val frontHair: WorkspaceInitialHair = WorkspaceInitialHair.CLASSIC,
    val backHair: WorkspaceInitialHair = WorkspaceInitialHair.CLASSIC,
    val clothing: Boolean = true,
    val eyeJelly: Boolean = true,
) {
    init {
        require(headStrength.isFinite() && headStrength in 0f..4f && bodyStrength.isFinite() && bodyStrength in 0f..4f) {
            "Initial binding strengths must be between 0 and 4"
        }
    }
    internal fun configure(config: PipelineConfig) = config.copy(
        headTurnStrength = headStrength, bodyStrength = bodyStrength, featureDisplacementEnabled = featureDisplacement,
        mouthOutlineEnabled = mouthOutline, motionBasic = motionBasic, motionSkeleton = motionSkeleton,
        physicsFrontHair = frontHair == WorkspaceInitialHair.CLASSIC, physicsBackHair = backHair == WorkspaceInitialHair.CLASSIC,
        physicsEyeJelly = eyeJelly,
    )
    companion object {
        fun forMode(mode: WorkspaceInitialModifiers) = WorkspaceModelPresetLibrary.shared.get(when (mode) {
            WorkspaceInitialModifiers.MINIMAL -> "modern-light"
            WorkspaceInitialModifiers.DEFAULT -> "modern"
            WorkspaceInitialModifiers.FULL -> "modern-dynamic"
            else -> error("This mode does not describe a shortcut: $mode")
        }).choices

        internal fun resolveChoices(mode: WorkspaceInitialModifiers?, preset: WorkspaceInitialPreset?): WorkspaceInitialPreset? {
            require(preset == null || mode == WorkspaceInitialModifiers.CUSTOM) { "Custom choices require the custom modifier mode" }
            return when (mode) {
                WorkspaceInitialModifiers.RECOMMENDED -> WorkspaceModelPresetLibrary.shared.get("modern").choices
                WorkspaceInitialModifiers.CUSTOM -> requireNotNull(preset) { "Custom modifier choices are required" }
                WorkspaceInitialModifiers.MINIMAL, WorkspaceInitialModifiers.DEFAULT, WorkspaceInitialModifiers.FULL -> forMode(mode)
                WorkspaceInitialModifiers.EMPTY, null -> null
            }
        }

        internal fun applyToConfig(mode: WorkspaceInitialModifiers?, choices: WorkspaceInitialPreset?, base: io.github.psd2live.core.PipelineConfig): io.github.psd2live.core.PipelineConfig {
            val baseline = if (mode == WorkspaceInitialModifiers.RECOMMENDED) WorkspacePresetBaseline.configure(base, "modern") else base
            return (choices?.configure(baseline) ?: baseline).copy(
                hairSimulationFront = choices?.frontHair == WorkspaceInitialHair.SIMULATION,
                hairSimulationBack = choices?.backHair == WorkspaceInitialHair.SIMULATION,
            )
        }
    }
}
