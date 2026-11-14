package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MeshSplitAlgorithm

/** Bundled in-process algorithm entrypoint for splitting disconnected mesh islands. */
internal object MeshSplitAlgorithmPlugin {
    private val implementation = MeshSplitAlgorithm()
    val definition = RigModifierDefinition(
        type = "mesh.split",
        version = 1,
        validate = { _, instance ->
            require(instance.targets.isNotEmpty() && instance.targets.all { it.kind == "mesh" }) {
                "A mesh split modifier targets one or more meshes"
            }
        },
        composableKinds = setOf("mesh", "part", "hierarchy", "relations", "glue"),
        changeWrites = setOf("mesh", "part", "hierarchy", "relations", "glue", "textures", "authoring", "hitArea", "advanced"),
        plugin = implementation,
    )
}
