package io.github.psd2live.algorithms.swing

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import io.github.psd2live.algorithms.physics.PhysicsGroups
import org.umamo.edit.withParametersSyncedFromTree
import kotlinx.serialization.json.jsonPrimitive

class SwingAlgorithm : ModifierAlgorithmPlugin {
    override fun evaluate(input: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR {
        val rig = input
        checkpoint()
        val source = RigSwingEdit.fromJson(instance.config)
        require(!source.baked) { "A baked swing is ordinary content" }
        require(instance.targets.all { it.kind == "mesh" || it.kind == "deformer" } &&
            source.targets == instance.targets.map { it.id }) { "Swing targets must match the captured modifier targets" }
        val requested = source.copy(id = instance.outputId("swing"), targets = instance.targets.map { it.id },
            motions = source.motions.map { motion -> motion.copy(parameterIds = motion.parameterIds.indices.map {
                instance.outputId("swing:${motion.kind.name.lowercase()}:$it")
            }) })
        val wrapped = SwingWrapping.wrap(PuppetIr.toPuppet(rig), requested)
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
        val result = PuppetIr.toIr(model.withParametersSyncedFromTree()).copy(
            textures = rig.textures, physics = rig.physics, clips = rig.clips, parameterRoles = rig.parameterRoles,
            restPose = rig.restPose, advanced = rig.advanced, hitAreas = rig.hitAreas)
        val available = model.parameters.mapTo(HashSet()) { it.id.raw }
        return PhysicsGroups.append(result, SwingPhysicsRules.rules(listOf(edit), available).map(PhysicsGroups::fromSetting))
    }
}
