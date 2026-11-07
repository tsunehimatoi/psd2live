package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierFieldChange
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.ParameterNode

object FittedFaceV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        ("builtin.666163652f666974746564.v2/binding" to 2) to FittedFaceV2Algorithm,
    ))
}

object FittedFaceV2Algorithm : ChangingModifierAlgorithmPlugin {
    const val TYPE = "face/fitted"
    const val VERSION = 2
    val WRITES = setOf("mesh", "deformer", "parameter", "hierarchy", "relations")

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val changes = FittedFaceBindingAlgorithm.changes(input, instance, context, checkpoint)
        val folder = requireNotNull(changes.parameterTree).after.filterIsInstance<ParameterNode.Group>()
            .single { it.id == "ParamGroupFace" }
        val id = instance.outputId("parameter-folder:${folder.id}")
        val previous = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
        val links = requireNotNull(changes.parameterLinks).after
        return changes.copy(
            parameterTree = ModifierFieldChange(input.parameterTree, previous + folder.copy(id = id)),
            parameterLinks = ModifierFieldChange(input.parameterLinks, input.parameterLinks + (links - input.parameterLinks.toSet())),
        )
    }
}
