package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ContextualModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object ExpressionV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        ("builtin.65787072657373696f6e2e746f67676c6573.v2/toggles" to ExpressionV2Algorithm.VERSION) to ExpressionV2Algorithm(),
    ))
}

/** Reuses live standard axes while retaining the existing opacity and chain-physics rules. */
class ExpressionV2Algorithm : ContextualModifierAlgorithmPlugin {
    private val expression = ExpressionAlgorithm()

    override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                          checkpoint: () -> Unit): RigIR {
        require(instance.version == VERSION)
        val existing = input.parameters.mapTo(HashSet()) { it.id }
        val missing = context.values.getValue("requiredParameters").jsonArray.map { it.jsonPrimitive.content }
            .filterNot { it in existing }
        require(missing.isEmpty()) { "${instance.id}: add a preceding binding for expression parameters ${missing.joinToString()}" }
        return expression.evaluate(input, instance, context, checkpoint)
    }

    companion object {
        const val TYPE = "expression.toggles"
        const val VERSION = 2
    }
}
