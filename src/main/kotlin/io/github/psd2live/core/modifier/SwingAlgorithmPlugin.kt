package io.github.psd2live.core.modifier

import io.github.psd2live.core.*

/** Bundled in-process algorithm entrypoint. */
internal object SwingAlgorithmPlugin {
    private val implementation = io.github.psd2live.algorithms.swing.SwingAlgorithm()
    val definition = RigModifierDefinition("swing", 1, validate = { _, instance ->
        require(instance.targets.all { it.kind == "deformer" || it.kind == "mesh" }) { "A swing modifier targets Warps or meshes" }
        val source = RigSwingEdit.fromJson(instance.config)
        require(!source.baked) { "A baked swing is ordinary content" }
        require(source.targets == instance.targets.map { it.id }) { "Swing targets must match the captured modifier targets" }
    }, composableKinds = setOf("deformer", "mesh", "hierarchy"), evaluate = implementation::evaluate, plugin = implementation)
}
