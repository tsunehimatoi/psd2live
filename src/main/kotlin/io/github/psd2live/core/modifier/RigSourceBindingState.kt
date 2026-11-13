package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigTuning

/**
 * Preceding binding instances indexed for native config assembly.
 *
 * A later instance replaces an earlier one of the same type or base area, tuning follows
 * [RigLegacyBindings.specs] order, and a fitted face supplies the head strength in preference
 * to a legacy face.
 */
internal class RigSourceBindingState(instances: List<RigModifierInstance>) {
    val byType: Map<String, RigModifierInstance> = instances.associateBy { it.type }
    val byArea: Map<String, RigModifierInstance> by lazy {
        instances.filter { RigLegacyBindings.spec(it.type).isBase }.associateBy { RigLegacyBindings.spec(it.type).area }
    }
    val tuning: RigTuning by lazy {
        RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            byType[spec.type]?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
    }
    val bodyStrength: Float by lazy { byType["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f }
    val headStrength: Float by lazy {
        (byType[RigLegacyBindings.FITTED_FACE] ?: byType["face/legacy"])?.let(RigLegacyBindings::strength) ?: 1f
    }
}
