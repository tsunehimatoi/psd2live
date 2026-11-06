package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonPrimitive

/** Bundled in-process algorithm entrypoint. */
internal object SwingAlgorithmPlugin {
    val definition = RigModifierDefinition("swing", 1, validate = { _, instance ->
        require(instance.targets.all { it.kind == "deformer" || it.kind == "mesh" }) { "A swing modifier targets Warps or meshes" }
        val source = RigSwingEdit.fromJson(instance.config)
        require(!source.baked) { "A baked swing is ordinary content" }
        require(source.targets == instance.targets.map { it.id }) { "Swing targets must match the captured modifier targets" }
    }, composableKinds = setOf("deformer", "mesh", "hierarchy"), evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val source = RigSwingEdit.fromJson(instance.config)
        val requested = source.copy(id = instance.outputId("swing"), targets = instance.targets.map { it.id },
            motions = source.motions.map { motion -> motion.copy(parameterIds = motion.parameterIds.indices.map {
                instance.outputId("swing:${motion.kind.name.lowercase()}:$it")
            }) })
        val wrapped = SwingAuthoring.wrap(PuppetIr.toPuppet(rig), requested)
        // The native wrapper can allocate a free suffix for ordinary authoring. Modifier identities must be fixed.
        wrapped.commands.forEachIndexed { index, command ->
            checkpoint()
            val expected = "Warp_Swing_${requested.id}" + if (index == 0) "" else "_${index + 1}"
            require(command["id"]?.jsonPrimitive?.content == expected) { "Swing wrapper ID collides with existing content: $expected" }
        }
        val edit = requested.copy(targets = wrapped.targets)
        val (model, issues) = SwingGenerator.applyOne(wrapped.model, edit)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        checkpoint()
        val result = rig.withPuppet(model)
        val available = model.parameters.mapTo(HashSet()) { it.id.raw }
        RigModifierAlgorithms.addPhysics(result, PhysicsGenerator.swingRules(listOf(edit), available).map(Physics3Json::group))
    })

}
