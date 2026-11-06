package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId

/**
 * Color-only input. The host certifies that adding a mood axis does not change blend-shape lowering
 * for these targets. Measurements describe the canonical source, never the authored preview.
 */
data class CheekSource(
    val hasFace: Boolean,
    val parameterName: String,
    val folderName: String,
    val targets: Map<String, Layer>,
) {
    enum class LayerKind(val warmth: Float, val skin: Boolean) {
        FACE(.06f, true), NOSE(.06f, true), EARS(.05f, true), BLUSH(.3f, true),
        FACE_DETAIL(0f, true), OTHER(0f, false),
    }

    data class Layer(
        val kind: LayerKind,
        val priorCheek: Float? = null,
        val priorGloom: Float? = null,
        val generatedMultiplyTrack: Boolean = false,
    ) {
        init {
            require(listOfNotNull(priorCheek, priorGloom).all { it.isFinite() && it in 0f..200f })
        }

        fun influence(strength: Float) = MoodBinding.Influence(
            kind.warmth * strength / 100f,
            (if (kind.skin) 1f else 0f) * (priorGloom ?: 0f) / 100f,
            highlight = false,
        )
    }

    fun parameter() = Parameter("ParamCheek", parameterName, 0f, 1f, 0f)
    fun context() = ModifierAlgorithmContext(buildJsonObject {
        put("schema", SCHEMA)
        put("parameter_name", parameterName)
        put("folder_name", folderName)
        put("has_face", hasFace)
        putJsonObject("targets") {
            for ((id, layer) in targets) put(id, buildJsonObject {
                put("kind", layer.kind.name)
                layer.priorCheek?.let { put("prior_cheek", it) }
                layer.priorGloom?.let { put("prior_gloom", it) }
                put("generated_multiply_track", layer.generatedMultiplyTrack)
            })
        }
    })

    companion object {
        private const val SCHEMA = "expression-cheek/1"

        fun read(context: ModifierAlgorithmContext): CheekSource {
            val source = context.values
            require(source["schema"] == JsonPrimitive(SCHEMA)) { "Missing cheek source measurements" }
            return CheekSource(
                source.getValue("has_face").jsonPrimitive.boolean,
                source.getValue("parameter_name").jsonPrimitive.content,
                source.getValue("folder_name").jsonPrimitive.content,
                source.getValue("targets").jsonObject.mapValues { (_, encoded) ->
                    val layer = encoded.jsonObject
                    Layer(LayerKind.valueOf(layer.getValue("kind").jsonPrimitive.content),
                        layer["prior_cheek"]?.jsonPrimitive?.float, layer["prior_gloom"]?.jsonPrimitive?.float,
                        layer.getValue("generated_multiply_track").jsonPrimitive.boolean)
                },
            )
        }
    }
}

/** Owns only the cheek parameter, its folder entry, and the affected multiply-color tracks. */
class CheekAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        val source = CheekSource.read(context)
        val strength = instance.config["values"]?.jsonObject?.get("strength")?.jsonPrimitive?.float ?: 100f
        require(strength.isFinite() && strength in 0f..200f) { "Cheek strength must be within 0..200" }
        require(instance.targets.all { it.kind == "mesh" && it.id in source.targets }) { "Missing cheek target measurement" }
        if (!source.hasFace) return ModifierModelChanges()

        val parameter = source.parameter()
        val existing = input.parameters.firstOrNull { it.id == parameter.id }
        require(existing == null || existing.copy(name = parameter.name) == parameter) { "Incompatible cheek parameter" }
        val parameters = if (existing == null) input.parameters + parameter else input.parameters
        val runtime = PuppetIr.toPuppet(RigIR(input.canvas, parameters))
        val meshes = input.meshes.associateBy { it.id }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val mesh = requireNotNull(meshes[target.id]) { "Missing cheek mesh: ${target.id}" }
            val layer = source.targets.getValue(target.id)
            if (layer.generatedMultiplyTrack || layer.kind.warmth == 0f) return@mapNotNull null
            val before = tracks(layer, layer.priorCheek)
            val after = tracks(layer, strength)
            val channel = Channel.MULTIPLY_COLOR
            val beforeGrid = PuppetIr.meshToIr(sample(mesh.id, before)).channels[channel]
            val afterGrid = PuppetIr.meshToIr(sample(mesh.id, after)).channels[channel]
            if (beforeGrid == afterGrid) return@mapNotNull null

            val actual = mesh.channels[channel]
            val next = if (actual == beforeGrid && mesh.multiply == Rgb.White) afterGrid else {
                // Compose just this channel. Geometry and unrelated authored tracks retain their references.
                val live = PuppetIr.toPuppet(RigIR(input.canvas, emptyList(), meshes = listOf(
                    Mesh(mesh.id, mesh.id, null, geometry = null, offsets = null, multiply = mesh.multiply,
                        channels = actual?.let { mapOf(channel to it) }.orEmpty())
                ))).drawables.single()
                val merged = ChannelResidual.channels(runtime, live, sample(mesh.id, before), sample(mesh.id, after), checkpoint)
                PuppetIr.meshToIr(sample(mesh.id, merged)).channels[channel]
            }
            ModifierObjectUpdate(mesh, mesh.copy(channels = if (next == null) mesh.channels - channel else mesh.channels + (channel to next)))
        }
        val tree = if (existing == null) insertParameter(input, instance, source) else null
        return ModifierModelChanges(
            parameters = if (existing == null) ModifierObjectChanges(added = listOf(parameter))
                else ModifierObjectChanges(reused = listOf(existing)),
            meshes = ModifierObjectChanges(updated = updates),
            parameterTree = tree,
        )
    }

    private fun tracks(layer: CheekSource.Layer, strength: Float?): ChannelGrids {
        val influence = layer.influence(strength ?: 0f)
        return MoodChannels.apply(ChannelGrids.Empty, influence.warmth, influence.darkness,
            influence.highlight, strength != null, layer.priorGloom != null)
    }

    private fun sample(id: String, tracks: ChannelGrids) = Drawable(
        DrawableId(id), id, null, BlendMode.Normal, emptyList(), null, null,
        channelGrids = tracks,
    )

    private fun insertParameter(input: RigIR, instance: RigModifierInstance, source: CheekSource): ModifierFieldChange<List<ParameterNode>> {
        val folder = instance.outputId("parameter-folder:ParamGroupFace")
        val node = ParameterNode.Param(source.parameter().id)
        val tree = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
        fun insert(nodes: List<ParameterNode>): List<ParameterNode> = nodes.map {
            if (it is ParameterNode.Group) {
                if (it.id == folder) it.copy(children = it.children + node)
                else it.copy(children = insert(it.children))
            } else it
        }
        fun contains(nodes: List<ParameterNode>): Boolean =
            nodes.filterIsInstance<ParameterNode.Group>().any { it.id == folder || contains(it.children) }
        val result = if (contains(tree)) insert(tree) else tree + ParameterNode.Group(folder, source.folderName, true, listOf(node))
        return ModifierFieldChange(input.parameterTree, result)
    }
}
