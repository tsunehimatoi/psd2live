package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel

/** The fitted lid line and shadow share a fade on their eye's opening axis. */
class LidFadeAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val source = LidFadeInputs.decode(input.context)
        require(source.keys.all { id -> instance.targets.any { it.id == id } })
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base").jsonPrimitive.content
        require(input.providers("rig.scaffold").any { it.instanceId == scaffold.jsonPrimitive.content && it.targets.containsAll(instance.targets) }) {
            "Missing lid fade scaffold provider"
        }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            require(input.providers("eye/fitted").any { it.instanceId == base && target in it.targets }) { "Missing fitted eye provider" }
            val measured = source[target.id] ?: return@mapNotNull null
            if ((input.providers(TYPE) + input.providers(ENTRYPOINT)).any { target in it.targets && it.config["scaffold"] == scaffold })
                return@mapNotNull null
            val wide = input.providers("eye.wide").any { target in it.targets && it.config["scaffold"] == scaffold }
            val parameter = requireNotNull(input.parameter(measured.parameter)) { "Missing fitted eye opening parameter" }
            val mesh = requireNotNull(input.mesh(target.id))
            val keys = if (wide) floatArrayOf(0f, measured.crease + .15f, .75f, 1f, 2f)
                else floatArrayOf(0f, measured.crease + .15f, .75f, 1f)
            val desired = KeyGrid(listOf(KeyAxis(parameter.id, Floats.of(keys))), keys.indices.map { index ->
                KeyCell<ChannelValue>(Ints.wrap(intArrayOf(index)), ChannelValue.Scalar(if (index < 2) 0f else 1f))
            })
            val opacity = if (mesh.channels[Channel.OPACITY] == null && mesh.opacity == measured.opacity) desired else {
                val channels = mesh.channels.filterKeys { it == Channel.OPACITY }
                val axes = channels.values.flatMap { it.axes }.map { it.parameter }.toSet() + parameter.id
                val actual = Mesh("actual", "actual", null, geometry = null, offsets = null, channels = channels, opacity = mesh.opacity)
                val neutral = actual.copy(id = "neutral", opacity = measured.opacity, channels = emptyMap())
                val generated = neutral.copy(id = "generated", channels = mapOf(Channel.OPACITY to desired))
                val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), axes.mapNotNull(input::parameter), meshes = listOf(actual, neutral, generated)))
                val (live, before, after) = model.drawables
                PuppetIr.meshToIr(live.copy(channelGrids = ChannelResidual.channels(model, live, before, after, checkpoint)))
                    .channels.getValue(Channel.OPACITY)
            }
            val updated = mesh.copy(channels = mesh.channels + (Channel.OPACITY to opacity)).let { faded ->
                if (faded.shapes.isEmpty()) faded else {
                    val axes = listOf(mesh, faded).flatMap { it.channels[Channel.OPACITY]?.axes.orEmpty() }
                    val parameters = axes.map { it.parameter }.distinct().mapNotNull(input::parameter)
                    val defaults = parameters.associate { it.id to it.default }
                    val samples = listOf(mesh, faded).mapIndexed { index, sample ->
                        sample.copy(id = "sample-$index", parent = null, maskedBy = emptyList(),
                            geometry = null, offsets = null, channels = sample.channels.filterKeys { it == Channel.OPACITY }, shapes = emptyList())
                    }
                    val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), parameters, meshes = samples))
                    val (previous, current) = model.drawables.map { drawable ->
                        drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, paramValue = { defaults[it.raw] ?: 0f })
                    }
                    if (current == previous) faded else faded.copy(shapes = faded.shapes.map { binding ->
                        binding.copy(shapes = binding.shapes.map { shape ->
                            shape?.copy(opacity = current + if (shape.opacity.isFinite()) shape.opacity - previous else 0f)
                        })
                    })
                }
            }
            if (updated == mesh) null else ModifierObjectUpdate(mesh, updated)
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
    }

    companion object {
        const val TYPE = "eye.lid-fade"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652e6c69642d66616465.v2/fade"
    }
}
