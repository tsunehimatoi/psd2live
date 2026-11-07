package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Adds facial capture axes to the fitted mouth and its surrounding skin. */
class MouthCaptureAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        val scaffoldProvider = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth capture v2 requires a shared v2 scaffold" }
        require(input.providers("mouth/fitted").any {
            it.instanceId == base && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == JsonPrimitive(scaffold)
        }) { "Mouth capture v2 requires a compatible fitted mouth provider" }

        val structure = RigModifierInstance(scaffold, scaffoldProvider.type, scaffoldProvider.version, scaffoldProvider.targets,
            config = scaffoldProvider.config, outputAliases = scaffoldProvider.outputAliases, plugin = scaffoldProvider.plugin)
        val context = input.context
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject,
            context.resources, context.upstream))
        require(captured.layers.values.none { it.tag == "MOUTH_CLOSE" }) { "Use mouth capture v1 for closed-drawing swaps" }
        val vertices = requireNotNull(captured.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val mouth = requireNotNull(captured.source).rig()
        require(mouth.options.capture)
        val lippedSkin = context.values.getValue("lippedSkin").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        val follows = context.values.getValue("follows").jsonObject
        require(captured.layers.keys == vertices.targets.keys && captured.layers.keys == follows.keys &&
            captured.layers.keys.containsAll(lippedSkin) && follows.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val live = input.parameters
        val declared = parameters(context)
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
                mesh.shapes.none { it.parameter in captureIds }) { "Mouth capture v2 requires the fitted source mesh without existing capture tracks: $id" }
            require(mesh.channels.values.none { track -> track.axes.any { it.parameter in captureIds } }) {
                "Mouth capture v2 found existing capture channel keys: $id"
            }
            val follow = follows.getValue(id).jsonPrimitive.contentOrNull
            val role = follow?.let(MouthRig.Follow::valueOf)
            val grid = when {
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
                require(mesh.offsets == baseline) { "Mouth capture v2 requires the fitted mouth grid: $id" }
            }
            val shapes = if (role == null) {
                mouth.faceShapes(points, frame, captured.chin, layer.tag == "NOSE", mouthOpen, null, id in lippedSkin)
            } else {
                mouth.shapes(points, frame, false, { opens -> if (opens) 1f else Float.NaN },
                    layer.tag == "TONGUE", role, chin = captured.chin)
            }
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
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
            drawable.copy(geometryGrid = grid, blendShapes = vowels + neutralized + otherShapes)
        }
        // Fold lip-press opacity into channel tracks using the same keyed axis as the fitted geometry.
        val lowered = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(model.copy(drawables = drawables))
        require(lowered.deformers.isEmpty()) { "Mouth capture v2 requires the lip press to fit the source mesh grids" }
        val updates = meshes.zip(lowered.drawables).mapNotNull { (mesh, drawable) ->
            val next = PuppetIr.meshToIr(drawable)
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
        const val TYPE = "mouth.capture"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e63617074757265.v2/capture"
        val WRITES = setOf("mesh", "parameter", "hierarchy")
        val optionalUpstream = setOf("face/fitted", "face.depth-turn", "mouth.outline", "mouth.vowel")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val ranges = linkedMapOf(
            MouthRig.MOUTH_X to -1f, MouthRig.PUCKER_WIDEN to -1f, MouthRig.SHRUG to 0f,
            MouthRig.PRESS_OPEN to -1f, MouthRig.FUNNEL to 0f, MouthRig.CORNER_L to -1f,
            MouthRig.CORNER_R to -1f, MouthRig.PUCKER_WIDEN_L to -1f, MouthRig.PUCKER_WIDEN_R to -1f,
            MouthRig.CHEEK_PUFF to 0f, MouthRig.JAW_OPEN to 0f,
        )
        private val captureIds = ranges.keys.mapTo(hashSetOf()) { it.raw }
        private val vowelIds = MouthRig.VOWELS.mapTo(hashSetOf()) { it.second }

        fun context(source: ModifierAlgorithmContext, follows: Map<String, MouthRig.Follow?>, lippedSkin: Set<String>, names: Map<String, String>, folder: String) =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "mouth-capture/1")
                put("source", source.values)
                put("follows", JsonObject(follows.mapValues { it.value?.name?.let(::JsonPrimitive) ?: JsonNull }))
                put("lippedSkin", JsonArray(lippedSkin.map(::JsonPrimitive)))
                put("names", JsonObject(names.mapValues { JsonPrimitive(it.value) }))
                put("folder", folder)
            }, source.resources)

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            require(context.values.keys == setOf("schema", "source", "follows", "lippedSkin", "names", "folder") &&
                context.values.getValue("schema").jsonPrimitive.content == "mouth-capture/1")
            val names = context.values.getValue("names").jsonObject
            return ranges.map { (id, min) ->
                val blend = id != MouthRig.PRESS_OPEN
                Parameter(id.raw, names.getValue(id.raw).jsonPrimitive.content, min, 1f, 0f, blend = blend,
                    keys = if (!blend) null else Floats.wrap(if (min < 0f) floatArrayOf(-1f, 0f, 1f) else floatArrayOf(0f, 1f)))
            }
        }
    }
}
