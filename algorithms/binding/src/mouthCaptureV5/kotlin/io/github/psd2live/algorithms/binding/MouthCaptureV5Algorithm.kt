package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

object MouthCaptureV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthCaptureV5Algorithm.ENTRYPOINT to MouthCaptureV5Algorithm.VERSION) to MouthCaptureV5Algorithm(),
    ))
}

/** Adds the established capture tracks using the actual open or closed source chain. */
class MouthCaptureV5Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        val scaffoldProvider = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth capture v5 requires a shared v2 scaffold" }
        val context = input.context
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject,
            context.resources, context.upstream))
        val closedDrawing = captured.layers.values.any { it.tag == "MOUTH_CLOSE" }
        val fittedVersion = if (closedDrawing) 5 else 3
        val outlineVersion = if (closedDrawing) 6 else 7
        require(input.providers("mouth/fitted").singleOrNull()?.let {
            it.type == "mouth/fitted" && it.instanceId == base && it.version == fittedVersion &&
                it.targets.containsAll(instance.targets) && it.config["scaffold"] == JsonPrimitive(scaffold)
        } == true) { "Mouth capture v5 requires the matching fitted mouth provider" }

        val structure = RigModifierInstance(scaffold, scaffoldProvider.type, scaffoldProvider.version, scaffoldProvider.targets,
            config = scaffoldProvider.config, outputAliases = scaffoldProvider.outputAliases, plugin = scaffoldProvider.plugin)
        val vertices = requireNotNull(captured.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val mouth = requireNotNull(captured.source).rig()
        require(mouth.options.capture)
        val lippedSkin = context.values.getValue("lippedSkin").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        val follows = context.values.getValue("follows").jsonObject
        require(captured.layers.keys == vertices.targets.keys && captured.layers.keys == follows.keys &&
            captured.layers.keys.containsAll(lippedSkin) && follows.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val boundTargets = follows.keys.map { ModifierTarget("mesh", it) }
        val outline = input.providers("mouth.outline")
        require(outline.isEmpty() || outline.singleOrNull()?.let {
            it.type == "mouth.outline" && it.version == outlineVersion &&
                it.config["scaffold"] == JsonPrimitive(scaffold) && it.targets.containsAll(boundTargets)
        } == true) { "Mouth capture v5 requires a compatible outline on the same scaffold" }
        val vowel = input.providers("mouth.vowel")
        require(vowel.isEmpty() || vowel.singleOrNull()?.let {
            it.type == "mouth.vowel" && it.version == 6 && it.config["base"] == JsonPrimitive(base) &&
                it.config["scaffold"] == JsonPrimitive(scaffold) && it.targets.containsAll(boundTargets)
        } == true) { "Mouth capture v5 requires compatible vowels for the same fitted mouth" }
        val live = input.parameters
        val declared = MouthCaptureAlgorithm.parameters(context)
        val captureIds = declared.mapTo(hashSetOf()) { it.id }
        val byParameter = live.associateBy { it.id }
        val additions = declared.filter { parameter ->
            val existing = byParameter[parameter.id]
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible capture parameter ${parameter.id}"
            }
            existing == null
        }
        val mouthOpen = ParameterId("ParamMouthOpenY")
        val mouthForm = ParameterId("ParamMouthForm")
        require(listOf(mouthOpen, mouthForm).all { axis -> input.parameter(axis.raw)?.let { !it.blend && it.default == 0f } == true })
        val rest = { parameter: ParameterId -> if (parameter.raw in eyeOpen) 1f else 0f }
        val meshes = captured.layers.keys.map { requireNotNull(input.mesh(it)) }
        val tree = input.parameterTree
        val model = PuppetIr.toPuppet(RigIR(input.canvas, live + additions, meshes = meshes, parameterTree = tree))
        val closedSources = mutableMapOf<String, Drawable>()
        val drawables = meshes.zip(model.drawables).map { (mesh, drawable) ->
            checkpoint()
            val id = mesh.id
            val layer = captured.layers.getValue(id)
            val target = vertices.targets.getValue(id)
            val geometry = requireNotNull(mesh.geometry)
            val material = raw.getValue(target.canonical)
            val points = material.rigPositions
            val frame = layer.frame
            val positions = FloatArray(points.size) { i ->
                if (i % 1024 == 0) checkpoint()
                if (target.parent == null) points[i]
                else if (i % 2 == 0) (points[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (points[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
            }
            val parent = target.parent?.let { ScaffoldBinding.deformerId(structure, id, it) }
            require(mesh.parent == parent && geometry == MeshGeometry(Floats.wrap(positions), Floats.wrap(material.atlasUvs), Ints.wrap(material.triangles)) &&
                mesh.shapes.none { it.parameter in captureIds }) { "Mouth capture v5 requires the fitted source mesh without existing capture tracks: $id" }
            require(mesh.channels.values.none { track -> track.axes.any { it.parameter in captureIds } }) {
                "Mouth capture v5 found existing capture channel keys: $id"
            }
            val follow = follows.getValue(id).jsonPrimitive.contentOrNull
            val role = follow?.let(MouthRig.Follow::valueOf)
            val closed = layer.tag == "MOUTH_CLOSE"
            if (closed) {
                val baseline = mouth.closedGrid(points, frame, mouthForm)
                require(mesh.offsets == PuppetIr.meshToIr(drawable.copy(geometryGrid = baseline)).offsets) {
                    "Mouth capture v5 requires the fitted closed mouth grid: $id"
                }
            }
            val grid = when {
                closed -> drawable.geometryGrid
                role != null -> mouth.grid(points, frame, mouthForm, mouthOpen, role, captured.chin)
                id in lippedSkin -> mouth.faceGrid(points, frame, mouthForm, mouthOpen, captured.chin)
                else -> drawable.geometryGrid
            }
            if (grid !== drawable.geometryGrid) {
                // The neutral press slice is the fitted grid. Reject authored or lowered tracks
                // that would be lost when replacing that grid with a new axis.
                val expanded = requireNotNull(PuppetIr.meshToIr(drawable.copy(geometryGrid = grid)).offsets)
                val baseline = KeyGrid(expanded.axes.dropLast(1), expanded.cells.mapNotNull { cell ->
                    val coordinate = cell.coordinate.shared()
                    if (coordinate.last() != 1) null else cell.copy(coordinate = Ints.wrap(coordinate.copyOf(coordinate.size - 1)))
                })
                require(mesh.offsets == baseline) { "Mouth capture v5 requires the fitted mouth grid: $id" }
            }
            val shapes = if (role == null) {
                mouth.faceShapes(points, frame, captured.chin, layer.tag == "NOSE", mouthOpen, null, id in lippedSkin)
            } else {
                mouth.shapes(points, frame, closed, { opens -> if (!opens) Float.NaN else if (closed) 0f else 1f },
                    layer.tag == "TONGUE", role, chin = captured.chin)
            }
            val source = if (closed && layer.preset) {
                // Fold press on the source swap track before restoring the upstream opacity residual.
                val swap = KeyformGrid(listOf(KeyformAxis(mouthOpen, floatArrayOf(0f, MouthRig.SWAP, 1f))),
                    listOf(1f, 0f, 0f).mapIndexed { index, opacity ->
                        KeyformCell<ChannelValue>(intArrayOf(index), ChannelValue.Scalar(opacity))
                    })
                drawable.copy(channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel + (FormChannel.OPACITY to swap)))
                    .also { closedSources[id] = it }
            } else drawable
            val opacity = source.channelGrids.scalarAt(FormChannel.OPACITY, source.opacity, rest)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { byParameter[it.raw]?.default ?: 0f })
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val neutralized = shapes.filter { it.parameterId.raw in captureIds }.map { binding ->
                binding.copy(forms = binding.forms.map { form ->
                    form?.let { MeshForm(it.positionDeltas, order, if (it.opacity.isNaN()) opacity else it.opacity * drawable.opacity,
                        multiply, screen) }
                })
            }
            val (vowels, otherShapes) = drawable.blendShapes.partition { it.parameterId in vowelIds }
            source.copy(geometryGrid = grid, blendShapes = vowels + neutralized + otherShapes)
        }
        // Fold lip-press opacity into channel tracks using the same keyed axis as the fitted geometry.
        val lowered = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(model.copy(drawables = drawables))
        require(lowered.deformers.isEmpty()) { "Mouth capture v5 requires the lip press to fit the source mesh grids" }
        val original = model.drawables.associateBy { it.id.raw }
        val updates = meshes.zip(lowered.drawables).mapNotNull { (mesh, drawable) ->
            val source = closedSources[mesh.id]
            val restored = if (source == null) drawable else {
                fun Drawable.onlyOpacity() = copy(channelGrids = ChannelGrids(channelGrids.gridsByChannel.filterKeys { it == FormChannel.OPACITY }))
                val actual = original.getValue(mesh.id)
                val opacity = ChannelResidual.channels(model, actual.onlyOpacity(), source.onlyOpacity(), drawable.onlyOpacity(), checkpoint)
                drawable.copy(channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel + opacity.gridsByChannel))
            }
            val next = PuppetIr.meshToIr(restored)
            val after = mesh.copy(shapes = next.shapes, offsets = next.offsets, channels = next.channels)
            after.takeIf { it != mesh }?.let { ModifierObjectUpdate(mesh, it) }
        }
        val parameterView = model.copy(parameters = model.parameters.filter { it.id.raw in byParameter })
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupMouth")),
            context.values.getValue("folder").jsonPrimitive.content, true, declared.map { PuppetParameterNode.Param(ParameterId(it.id)) })
        val updatedTree = BindingMerge.appendTree(parameterView, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(parameterView.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions), meshes = ModifierObjectChanges(updated = updates),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    companion object {
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.6d6f7574682e63617074757265.v5/capture"
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val vowelIds = MouthRig.VOWELS.mapTo(hashSetOf()) { it.second }
    }
}
