package io.github.psd2live.ui.state

import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.sim.ModelPresets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a hair moves: simulated, the legacy sway, or still. */
@Serializable
internal enum class HairMode(val key: String) {
	SIMULATION("presets.hairMode.simulate"),
	CLASSIC("presets.classicSway"),
	OFF("presets.hairMode.off"),
}

/** Which preset parts the art has; a part it lacks is left alone whatever the choice says. */
internal data class PresetParts(val frontHair: Boolean, val backHair: Boolean, val clothing: Boolean, val eyeJelly: Boolean) {
	companion object {
		fun of(analysis: PipelineAnalysis?): PresetParts {
			val present = PhysicsGenerator.Presets.present(analysis)
			val clothing = analysis?.layers.orEmpty().any { it.semantic.tag in ModelPresets.CLOTHING_TAGS && it.opaquePixels > 0 }
			return PresetParts(present.frontHair, present.backHair, clothing, present.eyeJelly)
		}
	}
}

/**
 * The model presets the start page sets: rig strength and facial switches, motion groups, physics and simulation.
 * A PSD import applies them as soon as the model is built.
 */
@Serializable
internal data class StartPresetChoices(
	val headStrength: Float = 1f,
	val bodyStrength: Float = 1f,
	val featureDisplacement: Boolean = false,
	val mouthOutline: Boolean = true,
	val motionBasic: Boolean = true,
	val motionSkeleton: Boolean = true,
	val frontHair: HairMode = HairMode.CLASSIC,
	val backHair: HairMode = HairMode.CLASSIC,
	val clothing: Boolean = true,
	val eyeJelly: Boolean = true,
    val modifiers: io.github.psd2live.application.WorkspaceInitialModifiers? = null,
) {
	/** The choices as far as [parts] lets them matter, so two that differ only on a missing part compare equal. */
	fun within(parts: PresetParts) = copy(
		frontHair = if (parts.frontHair) frontHair else HairMode.OFF,
		backHair = if (parts.backHair) backHair else HairMode.OFF,
		clothing = clothing && parts.clothing,
		eyeJelly = eyeJelly && parts.eyeJelly,
	)

    fun initialMode(): io.github.psd2live.application.WorkspaceInitialModifiers = modifiers ?: when (this) {
        StartQuickPreset.MINIMAL.choices -> io.github.psd2live.application.WorkspaceInitialModifiers.MINIMAL
        StartQuickPreset.DEFAULT.choices -> io.github.psd2live.application.WorkspaceInitialModifiers.DEFAULT
        StartQuickPreset.FULL.choices -> io.github.psd2live.application.WorkspaceInitialModifiers.FULL
        else -> io.github.psd2live.application.WorkspaceInitialModifiers.CUSTOM
    }

    fun initialPreset() = io.github.psd2live.application.WorkspaceInitialPreset(
        headStrength, bodyStrength, featureDisplacement, mouthOutline, motionBasic, motionSkeleton,
        io.github.psd2live.application.WorkspaceInitialHair.valueOf(frontHair.name),
        io.github.psd2live.application.WorkspaceInitialHair.valueOf(backHair.name), clothing, eyeJelly,
    )

	fun encode(): String = choicesJson.encodeToString(serializer(), this)

	companion object {
		private val choicesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

		/** [raw] as written by [encode]; null when missing or unreadable. */
		fun decode(raw: String?): StartPresetChoices? =
			raw?.let { runCatching { choicesJson.decodeFromString(serializer(), it) }.getOrNull() }

		/** What the model in [state] has now. */
		fun of(state: PSD2LiveState) = StartPresetChoices(
			headStrength = state.headStrength,
			bodyStrength = state.bodyStrength,
			featureDisplacement = state.featureDisplacementEnabled,
			mouthOutline = state.mouthOutlineEnabled,
			motionBasic = state.motionBasic,
			motionSkeleton = state.motionSkeleton,
			frontHair = hairMode(state.hairSimulationFront, state.physicsFrontHair),
			backHair = hairMode(state.hairSimulationBack, state.physicsBackHair),
			clothing = state.rigEdits.simEdits.any { it.id in ModelPresets.CLOTHING_SIMS.values },
			eyeJelly = state.physicsEyeJelly,
		)

		private fun hairMode(simulated: Boolean, sway: Boolean) = when {
			simulated -> HairMode.SIMULATION
			sway -> HairMode.CLASSIC
			else -> HairMode.OFF
		}
	}
}

/** One-click sets of [StartPresetChoices] on the start page. */
internal enum class StartQuickPreset(val key: String, val choices: StartPresetChoices) {
    RECOMMENDED("modifier.start.recommended", StartPresetChoices(modifiers = io.github.psd2live.application.WorkspaceInitialModifiers.RECOMMENDED)),
    EMPTY("modifier.start.empty", StartPresetChoices(modifiers = io.github.psd2live.application.WorkspaceInitialModifiers.EMPTY)),
	/** The rig alone: no generated motions, physics or simulation, no mouth outline. */
	MINIMAL("start.quick.minimal", StartPresetChoices(
		mouthOutline = false, motionBasic = false, motionSkeleton = false,
		frontHair = HairMode.OFF, backHair = HairMode.OFF, clothing = false, eyeJelly = false,
	)),
	/** Both motion groups, classic hair sway, loose clothing simulated and the eye jelly. */
	DEFAULT("start.quick.default", StartPresetChoices()),
	/** The default with simulated hair and feature displacement. */
	FULL("start.quick.full", StartPresetChoices(
		featureDisplacement = true, frontHair = HairMode.SIMULATION, backHair = HairMode.SIMULATION,
	)),
}

/**
 * The start page's sections, as its navigation lists them. It opens on the recent files; the next three start a
 * project: a model generated from a PSD, an empty canvas to lay materials out on, or a Cubism model. The last is
 * what changed, to read.
 */
internal enum class StartSection { RECENT, GENERATE, LAYOUT, CMO3, UPDATES }

/** Canvas sizes a Layout project accepts: each side up to 8192, at most 16M pixels. */
internal fun validLayoutCanvasSize(width: Int?, height: Int?): Boolean =
	width != null && height != null && width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216
