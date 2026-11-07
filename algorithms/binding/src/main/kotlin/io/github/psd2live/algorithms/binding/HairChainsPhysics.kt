package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.ParameterNode
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val ChainMotion.key get() = "${kind.word}:${layerId.length}:$layerId"
internal fun ChainMotion.suffix(parameter: String) = parameter.removePrefix(kind.prefix + kind.word + index)
internal fun ChainMotion.aliases(instance: RigModifierInstance) =
    parameters.associate { it.raw to instance.outputId("chain-parameter:$key:${suffix(it.raw)}") }

internal object HairChainsPhysics {
    fun groups(input: RigIR, instance: RigModifierInstance, active: List<ChainMotion>, parameters: Set<String>,
               softness: Float, source: HairChainsInputs, checkpoint: () -> Unit): List<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.booleanOrNull == false) return emptyList()
        val available = input.parameters.mapTo(parameters.toMutableSet()) { it.id }
        val rules = linkedMapOf<String, RigPhysicsEdit>()
        for (chain in active) {
            checkpoint()
            val aliases = chain.aliases(instance)
            for (rule in ChainMotion.physics(available + aliases.keys, softness) { key, name ->
                (source.physicsLabels[key] ?: HairChainsAlgorithm.NAME_TOKEN).replace(HairChainsAlgorithm.NAME_TOKEN, name)
            }.filter { rule -> rule.outputs.all { it.parameter in aliases } }) {
                val slot = "chain:${chain.key}:${chain.suffix(rule.outputs.first().parameter)}"
                rules[slot] = rule.copy(outputs = rule.outputs.map { it.copy(parameter = aliases.getValue(it.parameter)) })
            }
        }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { it in rules }) { "${instance.id}: unknown or unavailable module physics group" }
        val groups = rules.mapNotNull { (slot, native) ->
            val edit = overrides[slot]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: native
            require(edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() && edit.parameters.all { it in available } &&
                edit.inputs.none { source -> edit.outputs.any { it.parameter == source.parameter } }) {
                "${instance.id}: invalid physics $slot"
            }
            val impulses = ChainMotion.expressionInputs(source.expressionInputs.filter { parameter ->
                !slot.endsWith(":Y") &&
                parameter in available && edit.inputs.none { it.parameter == parameter } && edit.outputs.none { it.parameter == parameter }
            }.distinct().sorted())
            val group = PhysicsGroups.fromSetting(edit.copy(id = instance.outputId("physics:$slot")))
            if (input.physics.groups.any { it.copy(id = group.id, name = group.name) == group }) null
            else PhysicsGroups.fromSetting(edit.copy(id = group.id, inputs = edit.inputs + impulses))
        }
        PhysicsGroups.validateAppend(input, groups)
        return groups
    }
}

internal object HairChainsParameterTree {
    fun append(input: RigIR, folder: String, name: String, ids: List<String>): List<ParameterNode> {
        val additions = ids.map { ParameterNode.Param(it) }
        var found = false
        fun update(nodes: List<ParameterNode>): List<ParameterNode> = nodes.map { node ->
            when (node) {
                is ParameterNode.Param -> node
                is ParameterNode.Group -> if (node.id == folder) {
                    found = true
                    node.copy(children = node.children + additions)
                } else node.copy(children = update(node.children))
            }
        }
        val tree = update(input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } })
        return if (found) tree else tree + ParameterNode.Group(folder, name, true, additions)
    }
}
