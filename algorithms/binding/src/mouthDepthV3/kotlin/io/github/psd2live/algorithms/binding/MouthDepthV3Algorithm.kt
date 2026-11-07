package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Applies depth parallax and opening-dependent shading to the existing interior mouth meshes. */
class MouthDepthV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        require(context.values.getValue("schema").jsonPrimitive.content == "mouth-depth/1")
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        val structure = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth interior depth v3 requires a shared v2 scaffold" }
        require(input.providers("mouth/fitted").any {
            it.instanceId == base && it.version in 1..3 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == JsonPrimitive(scaffold)
        }) { "Mouth interior depth v3 requires a compatible fitted mouth" }
        val face = instance.config.getValue("dependencies").jsonObject.getValue("face/legacy").jsonPrimitive.content
        require(input.providers("face/legacy").any { it.instanceId == face && it.targets.containsAll(instance.targets) }) {
            "Mouth interior depth v3 requires a bound head provider"
        }
        val measurements = MouthFittedInputs.decode(ModifierAlgorithmContext(
            context.values.getValue("source").jsonObject, context.resources, context.upstream))
        val vertices = requireNotNull(measurements.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val amount = instance.config["values"]?.jsonObject?.get("depth")?.jsonPrimitive?.float ?: 100f
        require(amount in 0f..200f)
        val source = requireNotNull(measurements.source)
        val mouth = source.copy(options = source.options.copy(depth = true, depthAmount = amount / 100f)).rig()
        val scaffoldInstance = RigModifierInstance(scaffold, structure.type, structure.version, structure.targets,
            config = structure.config, outputAliases = structure.outputAliases, plugin = structure.plugin)
        val targets = context.values.getValue("interior").jsonObject
        require(targets.keys == measurements.layers.keys && targets.keys == vertices.targets.keys)
        val deformers = mutableListOf<Deformer>()
        val updates = targets.mapNotNull { (id, descriptor) ->
            checkpoint()
            require(ModifierTarget("mesh", id) in instance.targets)
            val layer = measurements.layers.getValue(id)
            val target = vertices.targets.getValue(id)
            val material = raw.getValue(target.canonical)
            val points = material.rigPositions
            val mesh = requireNotNull(input.mesh(id))
            val frame = layer.frame
            val parent = target.parent?.let { ScaffoldBinding.deformerId(scaffoldInstance, id, it) }
            val positions = FloatArray(points.size) { i ->
                if (target.parent == null) points[i]
                else if (i % 2 == 0) (points[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (points[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
            }
            require(mesh.parent == parent && mesh.geometry == MeshGeometry(Floats.wrap(positions), Floats.wrap(material.atlasUvs),
                Ints.wrap(material.triangles))) { "Mouth interior depth v3 requires an unedited source frame: $id" }

            val data = descriptor.jsonObject
            val depth = data.getValue("depth").jsonPrimitive.float
            val shaded = if (data.getValue("shade").jsonPrimitive.boolean) shade(mesh) else mesh
            val after = if (depth == 0f) shaded else {
                val (shifted, carrier) = parallax(input, instance, shaded, mouth, points, frame, depth,
                    measurements.turnCarrierName, checkpoint)
                if (carrier != null) deformers += carrier
                shifted
            }
            after.takeIf { it != mesh }?.let { ModifierObjectUpdate(mesh, it) }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates),
            deformers = ModifierObjectChanges(added = deformers))
    }

    private fun shade(mesh: Mesh): Mesh {
        require(Channel.MULTIPLY_COLOR !in mesh.channels && mesh.multiply == Rgb.White &&
            mesh.shapes.all { binding -> binding.shapes.all { it == null || it.multiply == Rgb.White } }) { "Mouth interior depth v3 requires an unshaded source mesh: ${mesh.id}" }
        fun color(value: Float) = Rgb(value, value * .97f, value * .97f)
        val cells = listOf(.72f, .82f, 1f).mapIndexed { index, value ->
            KeyCell<ChannelValue>(Ints.values(index), ChannelValue.Color(color(value)))
        }
        return mesh.copy(channels = mesh.channels + (Channel.MULTIPLY_COLOR to KeyGrid(
            listOf(KeyAxis("ParamMouthOpenY", Floats.values(0f, .25f, 1f))), cells)),
            shapes = mesh.shapes.map { binding ->
                binding.copy(shapes = binding.shapes.map { it?.copy(multiply = color(.72f)) })
            })
    }

    private fun parallax(input: ModifierEvaluationInput, instance: RigModifierInstance, mesh: Mesh, mouth: MouthRig,
                         points: FloatArray, frame: Bounds, depth: Float, carrierName: String,
                         checkpoint: () -> Unit): Pair<Mesh, Deformer.Warp?> {
        val parents = input.parentsOfMesh(mesh.id)
        val model = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters, deformers = parents, meshes = listOf(mesh)))
        val drawable = model.drawables.single()
        val defaults = model.parameters.associate { it.id to it.default }
        val rest = { axis: ParameterId -> if (axis.raw in setOf("ParamEyeLOpen", "ParamEyeROpen")) 1f else 0f }
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it] ?: 0f })
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
        val neutral = meshGridDefaultDeltas(drawable, rest)
        val shifts = mouth.depthShapes(points, frame, depth, ParameterId("ParamAngleX"), ParameterId("ParamAngleY")).map { binding ->
            binding.copy(forms = binding.forms.map { form -> form?.let {
                val delta = if (neutral == null) it.positionDeltas else FloatArray(points.size) { i -> it.positionDeltas[i] + neutral[i] }
                MeshForm(delta, order, opacity, multiply, screen)
            } })
        }
        val lowered = BlendShapeLowering({ head, name ->
            require(head) { "Mouth interior depth v3 cannot add a non-head carrier" }
            carrierName.replace("{meshName}", name)
        }, checkpoint).lawful(model.copy(drawables = listOf(drawable.copy(blendShapes = drawable.blendShapes + shifts))))
        require(lowered.parameters == model.parameters) { "Mouth interior depth v3 requires bound head angles" }
        val result = PuppetIr.meshToIr(lowered.drawables.single())
        val newCarriers = lowered.deformers.drop(model.deformers.size)
        require(newCarriers.size <= 1 && lowered.deformers.take(model.deformers.size) == model.deformers) {
            "Mouth interior depth v3 cannot rewrite ancestor deformers"
        }
        val carrier = newCarriers.singleOrNull()?.let {
            val warp = PuppetIr.deformerToIr(it) as Deformer.Warp
            warp.copy(id = ScaffoldBinding.deformerId(instance, mesh.id, warp.id, shared = true))
        }
        val channels = expandPressOpacity(result.channels, input, checkpoint)
        return mesh.copy(parent = carrier?.id ?: mesh.parent, geometry = result.geometry, offsets = result.offsets,
            channels = channels, shapes = result.shapes) to carrier
    }

    private fun expandPressOpacity(channels: Channels, input: ModifierEvaluationInput, checkpoint: () -> Unit): Channels {
        val opacity = channels[Channel.OPACITY] ?: return channels
        if (opacity.axes.none { it.parameter == "ParamMouthPressLipOpen" } ||
            opacity.axes.any { it.parameter == "ParamAngleX" || it.parameter == "ParamAngleY" }) return channels

        // Capture already folded lip press into the live opacity track. Legacy lowering folds press
        // and depth together, so its opacity grid also carries both head axes with constant slices.
        val angles = listOf("ParamAngleX", "ParamAngleY").map { id ->
            val range = requireNotNull(input.parameter(id))
            val keys = (listOf(range.min, -30f, 0f, 30f, range.max)).distinct().sorted()
            KeyAxis(id, Floats.wrap(keys.toFloatArray()))
        }
        require(opacity.cells.size.toLong() * angles[0].keys.size * angles[1].keys.size <= 262144) {
            "Mouth interior depth v3 exceeds the opacity keyform limit"
        }
        val cells = angles[1].keys.shared().indices.flatMap { y ->
            angles[0].keys.shared().indices.flatMap { x ->
                checkpoint()
                opacity.cells.map { cell -> cell.copy(coordinate = Ints.wrap(cell.coordinate.shared() + intArrayOf(x, y))) }
            }
        }
        return channels + (Channel.OPACITY to KeyGrid(opacity.axes + angles, cells))
    }

    companion object {
        const val TYPE = "mouth.interior-depth"
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6d6f7574682e696e746572696f722d6465707468.v3/depth"
        val optionalUpstream = setOf("face/fitted", "face.depth-turn", "mouth.outline", "mouth.vowel",
            "mouth.capture", "mouth.tongue", "mouth.jaw", "mouth.cheek-lid")
    }
}
