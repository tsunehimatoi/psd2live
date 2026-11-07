package io.github.psd2live.core.modifier

/** Stable body binding identity shared by the source adapters. */
internal object RigBodyModifier {
    const val TYPE = "body/legacy"
    val parameters get() = RigLegacyBindings.spec(TYPE).parameters
}
