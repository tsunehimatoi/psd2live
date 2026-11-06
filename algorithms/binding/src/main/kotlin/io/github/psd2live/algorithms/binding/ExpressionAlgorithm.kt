package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.*

/** Source-aware expression binding without application, project or history services. */
class ExpressionAlgorithm : ContextualModifierAlgorithmPlugin {
    override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): RigIR {
        checkpoint()
        val source = context.values
        require(source["schema"]?.jsonPrimitive?.content == "expression/1") { "Missing expression author inputs" }
        val layers = source.getValue("layers").jsonObject.mapValues { (_, value) ->
            val layer = value.jsonObject
            ExpressionChannels.Layer(layer.getValue("parameter").jsonPrimitive.content, layer["switch"]?.jsonPrimitive?.int)
        }
        val meshes = source.getValue("meshes").jsonObject
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        val ids = instance.targets.mapNotNullTo(linkedSetOf()) { target ->
            meshes[target.id]?.jsonPrimitive?.content?.let { layers[it]?.parameter }
        }
        if (ids.isEmpty()) return input
        val catalog = ExpressionChannels.catalog(layers.values.toList(), staggered = true)
        val definitions = io.github.psd2live.format.compile.RigIrBinary.decode(context.resources.getValue("parameters").read()).parameters
        val current = PuppetIr.toPuppet(input)
        val supplied = PuppetIr.toPuppet(RigIR(input.canvas, definitions)).parameters
        val existing = current.parameters.mapTo(HashSet()) { it.id.raw }
        val additions = supplied.filter { it.id.raw in ids && it.id.raw !in existing }
        val model = current.copy(parameters = current.parameters + additions,
            parameterTree = current.parameterTree + additions.map { ParameterNode.Param(it.id) })
        val opacities = source.getValue("opacity").jsonObject
        val drawables = model.drawables.map { actual ->
            val layerId = meshes[actual.id.raw]?.jsonPrimitive?.content
            val layer = layers[layerId]
            if (actual.id.raw !in targets || layer == null) return@map actual
            require(actual.channelGrids[FormChannel.OPACITY]?.axes.orEmpty().none { it.parameterId.raw in ids }) {
                "${instance.id}: expression track already exists on ${actual.id.raw}"
            }
            checkpoint()
            val grid = requireNotNull(ExpressionChannels.opacity(layer, catalog.switchKeys))
            val neutral = actual.copy(channelGrids = ChannelGrids.Empty, opacity = opacities.getValue(requireNotNull(layerId)).jsonPrimitive.float)
            val desired = neutral.copy(channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to grid)))
            actual.copy(channelGrids = ChannelResidual.channels(model, actual, neutral, desired, checkpoint))
        }
        val result = PuppetIr.toIr(model.copy(drawables = drawables).withParametersSyncedFromTree()).copy(
            textures = input.textures, physics = input.physics, clips = input.clips, parameterRoles = input.parameterRoles,
            restPose = input.restPose, advanced = input.advanced, hitAreas = input.hitAreas)
        val owners = Json.decodeFromJsonElement(ListSerializer(RigModifierInstance.serializer()), source.getValue("chain_owners"))
        return addPhysicsInputs(result, owners, ids.toList())
    }

    companion object {
        fun addPhysicsInputs(rig: RigIR, owners: List<RigModifierInstance>, parameters: List<String>): RigIR {
            val live = rig.parameters.mapTo(HashSet()) { it.id }
            val inputs = parameters.filter { it in live }.distinct().sorted().map {
                io.github.psd2live.format.model.PhysicsInput(it, 6f, io.github.psd2live.format.model.PhysicsSource.X, false)
            }
            if (inputs.isEmpty()) return rig
            val groups = rig.physics.groups.map { group ->
                val slot = owners.firstNotNullOfOrNull { it.localOutputName(group.id) }
                if (slot?.startsWith("physics:chain:") != true || slot.endsWith(":Y")) return@map group
                val existing = group.inputs.groupBy { it.parameter }
                for (input in inputs) require(existing[input.parameter].orEmpty().all { it == input }) {
                    "Incompatible expression input ${input.parameter} in physics group ${group.id}"
                }
                group.copy(inputs = group.inputs + inputs.filter { it.parameter !in existing })
            }
            return rig.copy(physics = rig.physics.copy(groups = groups))
        }
    }
}
