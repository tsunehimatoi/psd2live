package io.github.psd2live.core.modifier

/** Stable entry point retained for the original body algorithm and its editable fields. */
internal object RigBodyModifier {
    const val TYPE = "body/legacy"
    val parameters get() = RigLegacyBindings.spec(TYPE).parameters
    val fields get() = RigLegacyBindings.spec(TYPE).fields
    fun definition(source: RigModifierSource?) = RigLegacyBindings.definition(spec = RigLegacyBindings.spec(TYPE), source = source)
}
