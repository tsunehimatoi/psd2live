package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Adds only the vowel parameters and the fitted mouth's additive vowel tracks. */
class MouthVowelAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        val scaffoldProvider = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth vowel v2 requires a shared v2 scaffold" }
        require(input.providers("mouth/fitted").any {
            it.instanceId == base && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == JsonPrimitive(scaffold)
        }) { "Mouth vowel v2 requires a compatible fitted mouth provider" }

        val structure = RigModifierInstance(scaffold, scaffoldProvider.type, scaffoldProvider.version, scaffoldProvider.targets,
            config = scaffoldProvider.config, outputAliases = scaffoldProvider.outputAliases, plugin = scaffoldProvider.plugin)
        val context = input.context
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject,
            context.resources, context.upstream))
        val vertices = requireNotNull(captured.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val mouth = requireNotNull(captured.source).rig()
        require(mouth.options.vowels)
        val follows = context.values.getValue("follows").jsonObject
        require(captured.layers.keys == vertices.targets.keys && captured.layers.keys == follows.keys &&
            follows.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val live = input.parameters
        val declared = parameters(context)
        val byParameter = live.associateBy { it.id }
        val additions = declared.filter { parameter ->
            val existing = byParameter[parameter.id]
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible vowel parameter ${parameter.id}"
            }
            existing == null
        }
        val open = requireNotNull(input.parameter("ParamMouthOpenY"))
        require(!open.blend && open.default == 0f)
        val mouthForm = ParameterId("ParamMouthForm")
        val mouthOpen = ParameterId(open.id)
        val rest = { parameter: ParameterId -> byParameter[parameter.raw]?.default ?: if (parameter.raw in eyeOpen) 1f else 0f }
        val updates = captured.layers.map { (id, layer) ->
            checkpoint()
            val mesh = requireNotNull(input.mesh(id))
            val target = vertices.targets.getValue(id)
            val geometry = requireNotNull(mesh.geometry)
            val material = raw.getValue(target.canonical)
            val parent = target.parent?.let { ScaffoldBinding.deformerId(structure, id, it) }
            require(mesh.shapes.none { it.parameter in vowelIds.mapTo(hashSetOf()) { param -> param.raw } }) {
                "Mouth vowel v2 requires a mesh without existing vowel tracks: $id"
            }
            val frame = layer.frame
            val rawPositions = mouthLayerPositions(material.rigPositions, frame, target.parent, checkpoint)
            val matchesSource = mesh.parent == parent &&
                geometry == MeshGeometry(Floats.wrap(rawPositions), Floats.wrap(material.atlasUvs), Ints.wrap(material.triangles))
            val points = if (matchesSource) material.rigPositions else mouthSourcePoints(material, geometry, checkpoint)
            val positions = if (matchesSource) rawPositions else mouthLayerPositions(points, frame, target.parent, checkpoint)
            val branch = if (matchesSource) emptyList() else input.parentsOfMesh(id)
            val model = PuppetIr.toPuppet(RigIR(input.canvas, live, meshes = listOf(mesh), deformers = branch))
            val drawable = model.drawables.single()
            val follow = follows.getValue(id).jsonPrimitive.contentOrNull
            val role = follow?.let(MouthRig.Follow::valueOf)
            val closed = layer.tag == "MOUTH_CLOSE"
            val shapes = if (role == null) {
                mouth.faceShapes(points, frame, captured.chin, false, mouthOpen, null, lippedSkin = true)
            } else {
                mouth.shapes(points, frame, closed, { opens -> if (!opens) Float.NaN else if (closed) 0f else 1f },
                    layer.tag == "TONGUE", role, chin = captured.chin)
            }
            val opacity = if (drawable.opacity <= 0f) 0f else drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { byParameter[it.raw]?.default ?: 0f })
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val neutralized = shapes.filter { it.parameterId in vowelIds }.map { binding ->
                binding.copy(forms = binding.forms.map { form ->
                    form?.let { MeshForm(it.positionDeltas, order,
                        if (drawable.opacity <= 0f) 0f else if (it.opacity.isNaN()) opacity else it.opacity * drawable.opacity,
                        multiply, screen) }
                })
            }
            val projected = if (matchesSource) neutralized else {
                val sourceGrid = when {
                    role == null -> mouth.faceGrid(points, frame, mouthForm, mouthOpen, captured.chin)
                    closed -> mouth.closedGrid(points, frame, mouthForm)
                    else -> mouth.grid(points, frame, mouthForm, mouthOpen, role, captured.chin)
                }
                mouthProjectShapes(input, live, mesh, drawable, target.parent, parent,
                    points, positions, frame, sourceGrid, neutralized, checkpoint)
            }
            val next = PuppetIr.meshToIr(drawable.copy(blendShapes = drawable.blendShapes + projected))
            ModifierObjectUpdate(mesh, mesh.copy(shapes = next.shapes))
        }
        val tree = input.parameterTree
        val parameterView = PuppetIr.toPuppet(RigIR(input.canvas, live, parameterTree = tree))
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupMouth")),
            context.values.getValue("folder").jsonPrimitive.content, true, declared.map { PuppetParameterNode.Param(ParameterId(it.id)) })
        val updatedTree = BindingMerge.appendTree(parameterView, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(parameterView.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions), meshes = ModifierObjectChanges(updated = updates),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    companion object {
        const val TYPE = "mouth.vowel"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e766f77656c.v2/vowel"
        val WRITES = setOf("mesh", "parameter", "hierarchy")
        val optionalUpstream = setOf("face/fitted", "face.depth-turn")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val vowelIds = MouthRig.VOWELS.mapTo(hashSetOf()) { it.second }

        fun context(source: ModifierAlgorithmContext, follows: Map<String, MouthRig.Follow?>, names: Map<String, String>, folder: String) =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "mouth-vowel/1")
                put("source", source.values)
                put("follows", JsonObject(follows.mapValues { it.value?.name?.let(::JsonPrimitive) ?: JsonNull }))
                put("names", JsonObject(names.mapValues { JsonPrimitive(it.value) }))
                put("folder", folder)
            }, source.resources)

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            require(context.values.keys == setOf("schema", "source", "follows", "names", "folder") &&
                context.values.getValue("schema").jsonPrimitive.content == "mouth-vowel/1")
            val names = context.values.getValue("names").jsonObject
            return MouthRig.VOWELS.map { (_, id) ->
                Parameter(id.raw, names.getValue(id.raw).jsonPrimitive.content, 0f, 1f, 0f, blend = true,
                    keys = Floats.wrap(floatArrayOf(0f, 1f)))
            }
        }
    }
}
