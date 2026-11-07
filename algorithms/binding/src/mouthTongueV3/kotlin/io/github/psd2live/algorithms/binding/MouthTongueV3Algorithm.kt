package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
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

object MouthTongueV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthTongueV3Algorithm.ENTRYPOINT to MouthTongueV3Algorithm.VERSION) to MouthTongueV3Algorithm(),
    ))
}

/** Adds tongue-out forms to the witnessed fitted mouth and capture tracks. */
class MouthTongueV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        val capture = instance.config.getValue("dependencies").jsonObject.getValue("mouth.capture").jsonPrimitive.content
        val structure = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth tongue v3 requires a shared v2 scaffold" }
        fun compatible(capability: String, id: String) = input.providers(capability).any {
            it.type == capability && it.instanceId == id && it.version in 1..3 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == JsonPrimitive(scaffold) &&
                (capability != "mouth.capture" || it.config["base"] == JsonPrimitive(base))
        }
        require(compatible("mouth/fitted", base) && compatible("mouth.capture", capture)) {
            "Mouth tongue v3 requires compatible fitted mouth and capture providers"
        }
        val scaffoldInstance = RigModifierInstance(scaffold, structure.type, structure.version, structure.targets,
            config = structure.config, outputAliases = structure.outputAliases, plugin = structure.plugin)
        val context = input.context
        val declared = MouthTongueAlgorithm.parameter(context)
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject,
            context.resources, context.upstream))
        val mouth = requireNotNull(captured.source).rig()
        require(mouth.options.capture && mouth.options.tongue && mouth.fit.layered == null &&
            captured.layers.values.none { it.tag == "MOUTH_CLOSE" || it.role in lipRoles }) { "Mouth tongue v3 requires a single fitted mouth drawing" }
        val vertices = requireNotNull(captured.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val follows = context.values.getValue("follows").jsonObject.mapValues { MouthRig.Follow.valueOf(it.value.jsonPrimitive.content) }
        require(captured.layers.keys == vertices.targets.keys && captured.layers.keys == follows.keys &&
            MouthRig.Follow.TONGUE in follows.values && follows.keys.all { ModifierTarget("mesh", it) in instance.targets })

        val live = input.parameters
        val existing = live.singleOrNull { it.id == declared.id }
        require(existing == null || existing.copy(name = declared.name) == declared) { "Incompatible tongue-out parameter" }
        require(live.any { it.id == "ParamMouthOpenY" && !it.blend && it.default == 0f } &&
            live.any { it.id == MouthRig.MOUTH_X.raw && it.blend }) { "Missing fitted mouth capture axes" }
        val additions = if (existing == null) listOf(declared) else emptyList()
        val meshes = captured.layers.keys.map { requireNotNull(input.mesh(it)) }
        val tree = input.parameterTree
        val model = PuppetIr.toPuppet(RigIR(input.canvas, live, meshes = meshes, parameterTree = tree))
        val defaults = live.associate { ParameterId(it.id) to it.default }
        val rest = { id: ParameterId -> if (id.raw in eyeOpen) 1f else 0f }
        val updates = meshes.zip(model.drawables).map { (mesh, drawable) ->
            checkpoint()
            val id = mesh.id
            val layer = captured.layers.getValue(id)
            val target = vertices.targets.getValue(id)
            val material = raw.getValue(target.canonical)
            val points = material.rigPositions
            val frame = layer.frame
            val positions = FloatArray(points.size) { i ->
                if (i % 1024 == 0) checkpoint()
                if (target.parent == null) points[i]
                else if (i % 2 == 0) (points[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (points[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
            }
            val parent = target.parent?.let { ScaffoldBinding.deformerId(scaffoldInstance, id, it) }
            require(mesh.parent == parent &&
                mesh.geometry == MeshGeometry(Floats.wrap(positions), Floats.wrap(material.atlasUvs), Ints.wrap(material.triangles)) &&
                drawable.blendShapes.none { it.parameterId == MouthRig.TONGUE_OUT } &&
                drawable.blendShapes.any { it.parameterId == MouthRig.MOUTH_X }) {
                "Mouth tongue v3 requires the captured source mesh without existing tongue tracks: $id"
            }
            val role = follows.getValue(id)
            val mouthShapes = mouth.shapes(points, frame, false, { opens -> if (opens) 1f else Float.NaN },
                layer.tag == "TONGUE", role, chin = captured.chin)
            val tongue = mouthShapes.single { it.parameterId == MouthRig.TONGUE_OUT }
            val precedingShapes = mouthShapes.takeWhile { it !== tongue }.mapTo(hashSetOf()) { it.parameterId }
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it] ?: 0f })
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val binding = tongue.copy(forms = tongue.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, it.opacity * drawable.opacity, multiply, screen) }
            })
            val shapes = drawable.blendShapes.toMutableList()
            val index = shapes.indexOfFirst { it.parameterId !in precedingShapes }.takeIf { it >= 0 } ?: shapes.size
            shapes.add(index, binding)
            ModifierObjectUpdate(mesh, mesh.copy(shapes = PuppetIr.meshToIr(drawable.copy(blendShapes = shapes)).shapes))
        }
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupMouth")),
            context.values.getValue("folder").jsonPrimitive.content, true, listOf(PuppetParameterNode.Param(MouthRig.TONGUE_OUT)))
        val updatedTree = BindingMerge.appendTree(model, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(model.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions), meshes = ModifierObjectChanges(updated = updates),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6d6f7574682e746f6e677565.v3/tongue"
        private val lipRoles = setOf("UPPER_LIP", "LOWER_LIP", "UPPER_LINE", "LOWER_LINE")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
    }
}
