package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.model.Parameter

/** Standalone legacy face binding algorithm eliminating host before/after transition delegation. */
class LegacyFaceAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        val base = LegacyFaceInputs.decode(context, checkpoint)
        val before = PuppetIr.toPuppet(input)
        val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ).mapIndexed { i, id ->
            val limit = if (i == 0) 45f else 30f
            Parameter(id, base.parameterLabels[i], -limit, limit, 0f)
        }
        val bound = LegacyFaceBinding.bind(
            before = before,
            source = base.measurements,
            tuning = base.tuning,
            strength = base.strength,
            angleParameters = angles,
            folderName = base.folderLabel,
            checkpoint = checkpoint,
        )
        return LegacyFaceChanges.lower(input, before, bound)
    }

    companion object {
        const val TYPE = "face/legacy"
        const val VERSION = 1
    }
}
