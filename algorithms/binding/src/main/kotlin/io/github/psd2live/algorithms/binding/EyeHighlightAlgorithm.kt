package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Source measurements for the fitted eye and the neutral frame of its highlight. */
@Serializable
data class EyeHighlightInput(
    val opening: String,
    val crease: Float,
    val irisWidth: Float,
    val width: Float,
    val height: Float,
    val parent: String?,
    val opacity: Float,
    val automaticOpacity: Boolean,
) {
    companion object {
        private val codec = MapSerializer(String.serializer(), EyeHighlightInput.serializer())

        fun encode(targets: Map<String, EyeHighlightInput>) = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "eye-highlight/1")
            put("targets", Json.encodeToJsonElement(codec, targets))
        })

        fun decode(context: ModifierAlgorithmContext): Map<String, EyeHighlightInput> {
            val data = context.values
            require(data.getValue("schema").jsonPrimitive.content == "eye-highlight/1")
            return Json.decodeFromJsonElement(codec, data.getValue("targets"))
        }
    }
}

/** Computes the fade and opposing head-angle translations for the existing highlight meshes. */
class EyeHighlightAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val targets = instance.targets.toSet()
        val captured = EyeHighlightInput.decode(input.context)
        require(captured.keys.all { ModifierTarget("mesh", it) in targets })
        val scaffold = instance.config.getValue("scaffold")
        val providers = mapOf("rig.scaffold" to scaffold, "eye/fitted" to instance.config.getValue("base"),
            "face/legacy" to instance.config.getValue("dependencies").jsonObject.getValue("face/legacy"))
        for ((capability, id) in providers) {
            require(input.providers(capability).any { it.instanceId == id.jsonPrimitive.content && it.targets.containsAll(targets) }) {
                "Missing eye highlight provider: $capability"
            }
        }
        val parallax = parallax(instance.config)
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val source = captured[target.id] ?: return@mapNotNull null
            val prior = input.upstream.filter { it.config["scaffold"] == scaffold && target in it.targets }
            val existing = prior.lastOrNull { it.capability == TYPE || it.capability == ENTRYPOINT }
            val driftChange = parallax - (existing?.let { parallax(it.config) } ?: 0f)
            if (existing != null && driftChange == 0f) return@mapNotNull null
            val opening = requireNotNull(input.parameter(source.opening)) { "Missing fitted eye opening parameter" }
            val wide = prior.any { it.capability == "eye.wide" }
            require(opening.min == 0f && opening.default == 1f && opening.max >= if (wide) 2f else 1f) {
                "Incompatible fitted eye opening parameter: ${opening.id}"
            }
            val mesh = requireNotNull(input.mesh(target.id))
            require(mesh.parent == source.parent) { "Eye highlight v2 requires the source highlight frame: ${mesh.id}" }
            val channels = if (source.automaticOpacity && existing == null) fade(input, mesh, source, wide, checkpoint) else mesh.channels
            val updated = drift(input, mesh, channels, source, driftChange, checkpoint)
            ModifierObjectUpdate(mesh, updated).takeIf { updated != mesh }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
    }

    private fun parallax(config: JsonObject): Float {
        val value = config["values"]?.jsonObject?.get("parallax")?.jsonPrimitive?.float ?: 100f
        require(value.isFinite() && value in 0f..200f) { "Highlight parallax must be within 0..200" }
        return value / 100f
    }

    private fun fade(input: ModifierEvaluationInput, mesh: Mesh, source: EyeHighlightInput, wide: Boolean,
                     checkpoint: () -> Unit): Channels {
        val normal = floatArrayOf(0f, source.crease, (source.crease + 1f) / 2f, 1f)
        val keys = if (wide) normal + 2f else normal
        val desired = KeyGrid(listOf(KeyAxis(source.opening, Floats.wrap(keys))), keys.indices.map { index ->
            KeyCell<ChannelValue>(Ints.wrap(intArrayOf(index)), ChannelValue.Scalar(if (index < 2) 0f else 1f))
        })
        val live = mesh.channels.filterKeys { it == Channel.OPACITY }
        val opacity = if (live.isEmpty() && mesh.opacity == source.opacity) desired else {
            val axes = live.values.flatMap { it.axes }.map { it.parameter }.toSet() + source.opening
            val actual = Mesh("actual", "actual", null, geometry = null, offsets = null, channels = live, opacity = mesh.opacity)
            val neutral = actual.copy(id = "neutral", opacity = source.opacity, channels = emptyMap())
            val generated = neutral.copy(id = "generated", channels = mapOf(Channel.OPACITY to desired))
            val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), axes.mapNotNull(input::parameter), meshes = listOf(actual, neutral, generated)))
            val (a, b, c) = model.drawables
            PuppetIr.meshToIr(a.copy(channelGrids = ChannelResidual.channels(model, a, b, c, checkpoint)))
                .channels.getValue(Channel.OPACITY)
        }
        return mesh.channels + (Channel.OPACITY to opacity)
    }

    private fun drift(input: ModifierEvaluationInput, mesh: Mesh, channels: Channels, source: EyeHighlightInput, parallax: Float,
                      checkpoint: () -> Unit): Mesh {
        val axes = listOf("ParamAngleX", "ParamAngleY")
        val parentChain = input.parentsOfMesh(mesh.id)
        val parameters = (axes + mesh.offsets?.axes.orEmpty().map { it.parameter } +
            channels.values.flatMap { it.axes }.map { it.parameter }).distinct().mapNotNull(input::parameter)
        require(axes.all { axis -> parameters.any { it.id == axis } }) { "Missing head angle parameters" }
        val model = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = parentChain, meshes = listOf(mesh.copy(channels = channels))))
        val drawable = model.drawables.single()
        val size = requireNotNull(mesh.geometry).positions.size
        val rest = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val defaults = parameters.associate { ParameterId(it.id) to it.default }
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it] ?: 0f })
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
        val neutral = meshGridDefaultDeltas(drawable, rest)
        val frame = Bounds(0f, 0f, source.width, source.height)
        val iris = Bounds(0f, 0f, source.irisWidth, 0f)
        val shapes = EyeModules.highlightShapes(FloatArray(size), frame, iris, EyeOptions(highlight = true, parallax = parallax))
            .map { binding -> binding.copy(forms = binding.forms.map { form -> form?.let {
                val deltas = if (neutral == null) it.positionDeltas else FloatArray(size) { i -> it.positionDeltas[i] + neutral[i] }
                MeshForm(deltas, order, opacity, multiply, screen)
            } }) }
        val withHighlight = drawable.copy(blendShapes = shapes)
        val lowered = BlendShapeLowering({ _, _ -> error("Eye highlight v2 cannot create a lowered head-turn carrier") }, checkpoint)
            .lawful(model.copy(drawables = listOf(withHighlight)))
        require(lowered.parameters == model.parameters) { "Eye highlight v2 requires bound head angles" }
        val result = PuppetIr.meshToIr(lowered.drawables.single())
        val priorOpacity = if (mesh.channels == channels || mesh.shapes.isEmpty()) opacity else {
            val original = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(mesh))).drawables.single()
            original.channelGrids.scalarAt(FormChannel.OPACITY, original.opacity, rest)
        }
        val existing = if (priorOpacity == opacity) mesh.shapes else mesh.shapes.map { binding ->
            binding.copy(shapes = binding.shapes.map { it?.copy(opacity = opacity + (it.opacity - priorOpacity)) })
        }
        return mesh.copy(offsets = result.offsets, channels = result.channels, shapes = result.shapes + existing)
    }

    companion object {
        const val TYPE = "eye.highlight"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652e686967686c69676874.v2/highlight"
        val optionalUpstream = setOf("eye.wide", TYPE, ENTRYPOINT)
    }
}
