package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Emits only Gloom's incremental channel and parameter changes against the preceding binding. */
class GloomAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        require(instance.version == 1 && instance.targets.all { it.kind == "mesh" })
        val source = GloomInputs.decode(input.context)
        if (!source.applies) return ModifierModelChanges()
        val strength = instance.config["values"]?.jsonObject?.get("strength")?.jsonPrimitive?.float ?: 100f
        require(strength in 0f..200f) { "Gloom strength must be within 0..200" }
        val definition = Parameter(PARAMETER, source.parameterName, 0f, 1f, 0f)
        val existing = input.parameter(PARAMETER)
        require(existing == null || existing.copy(name = definition.name) == definition) { "Incompatible gloom parameter" }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val layer = requireNotNull(source.layers[target.id]) { "Missing gloom measurement: ${target.id}" }
            val mesh = requireNotNull(input.mesh(target.id)) { "Missing gloom mesh: ${target.id}" }
            val neutral = source.neutral.getValue(target.id)
            val cheek = layer.precedingCheekStrength
            val gloom = layer.precedingGloomStrength
            fun influence(amount: Float) = MoodBinding.Influence(layer.cheekWarmth * (cheek ?: 0f) / 100f,
                (if (layer.skin) 1f else 0f) * amount / 100f, layer.highlight)
            val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), emptyList(),
                meshes = listOf(mesh.copy(id = "actual", parent = null, geometry = null, offsets = null, shapes = emptyList()),
                    neutral.copy(id = "neutral"))))
            val (actual, baseline) = model.drawables
            val previous = MoodBinding.applyChannels(baseline, influence(gloom ?: 0f), cheek != null, gloom != null)
            val desired = MoodBinding.applyChannels(baseline, influence(strength), cheek != null, true)
            if (previous.channelGrids === desired.channelGrids) return@mapNotNull null
            val before = PuppetIr.meshToIr(previous).channels
            val after = PuppetIr.meshToIr(desired).channels
            if (before == after) return@mapNotNull null
            val channels = if (mesh.channels == before && actual.opacity == previous.opacity &&
                actual.drawOrder == previous.drawOrder && actual.multiplyColor == previous.multiplyColor &&
                actual.screenColor == previous.screenColor) after else {
                val ids = (mesh.channels.values + before.values + after.values).flatMap { it.axes }.map { it.parameter }.distinct()
                val parameters = ids.mapNotNull { id -> if (id == PARAMETER) existing ?: definition else input.parameter(id) }
                val current = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), parameters))
                PuppetIr.meshToIr(actual.copy(channelGrids = ChannelResidual.channels(current, actual, previous, desired, checkpoint))).channels
            }
            if (channels == mesh.channels) null else ModifierObjectUpdate(mesh, mesh.copy(channels = channels))
        }
        val originalTree = if (existing == null) input.parameterTree else null
        val tree = originalTree?.let { addToTree(it.ifEmpty { input.parameters.map { p -> ParameterNode.Param(p.id) } },
            instance.outputId("parameter-folder:ParamGroupFace"), source.groupName) }
        return ModifierModelChanges(
            parameters = if (existing == null) ModifierObjectChanges(added = listOf(definition)) else ModifierObjectChanges(reused = listOf(existing)),
            meshes = ModifierObjectChanges(updated = updates),
            parameterTree = tree?.let { ModifierFieldChange(requireNotNull(originalTree), it) },
        )
    }

    private fun addToTree(tree: List<ParameterNode>, groupId: String, groupName: String): List<ParameterNode> {
        val entry = ParameterNode.Param(PARAMETER)
        var found = false
        fun insert(nodes: List<ParameterNode>): List<ParameterNode> = nodes.map { node ->
            when {
                node !is ParameterNode.Group -> node
                node.id == groupId -> node.copy(children = node.children + entry).also { found = true }
                else -> node.copy(children = insert(node.children))
            }
        }
        val next = insert(tree)
        return if (found) next else next + ParameterNode.Group(groupId, groupName, true, listOf(entry))
    }

    companion object {
        const val PARAMETER = "ParamGloom"
    }
}
