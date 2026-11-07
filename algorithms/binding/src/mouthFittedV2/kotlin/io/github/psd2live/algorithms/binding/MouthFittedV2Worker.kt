package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.ParameterNode
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*

object MouthFittedV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthFittedV2Algorithm.ENTRYPOINT to MouthFittedV2Algorithm.VERSION) to MouthFittedV2Algorithm(),
    ))
}

/** Computes the fitted mouth on neutral scaffold meshes and leaves their shared branch intact. */
class MouthFittedV2Algorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(context.upstream.none { it.type in HEAD_TURN || it.capability in HEAD_TURN }) {
            "Fitted mouth v2 cannot refresh the shared head field. Use v1 with fitted head turn."
        }
        val provider = instance.config.getValue("scaffold").jsonPrimitive.content
        require(context.upstream.any { it.type == "rig.scaffold" && it.instanceId == provider && instance.targets.all(it.targets::contains) }) {
            "Fitted mouth v2 requires its preceding scaffold provider"
        }
        val values = context.values
        val source = ModifierAlgorithmContext(values.getValue("mouth").jsonObject, context.resources, context.upstream)
        val captured = MouthFittedInputs.decode(source)
        require(captured.head == null && captured.source != null && captured.sourceVertices != null) {
            "Fitted mouth v2 requires neutral scaffold source measurements"
        }
        val scaffold = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("scaffoldGeometry"), checkpoint)
        val fitted = ScaffoldMeshContext.decodeRawMeshes(captured.sourceVertices.resource, checkpoint)
        val preparedMeshes = input.meshes.map { mesh ->
            val vertex = captured.sourceVertices.targets[mesh.id] ?: return@map mesh
            checkpoint()
            require(mesh.parent == vertex.parent && mesh.offsets?.axes.orEmpty().isEmpty() && mesh.shapes.isEmpty() &&
                mesh.offsets?.cells.orEmpty().all { cell -> cell.form.deltas.toArray().all { it == 0f } }) {
                "Fitted mouth v2 requires unchanged scaffold frames and neutral offsets"
            }
            val frame = captured.layers.getValue(mesh.id).frame
            val original = geometry(scaffold.getValue(vertex.canonical), frame, checkpoint)
            // A neutral source mesh may move to the fitted topology. Authored topology stays in place.
            if (mesh.geometry != original) mesh
            else mesh.copy(geometry = geometry(fitted.getValue(vertex.canonical), frame, checkpoint), offsets = null)
        }
        val definitions = parameters(values.getValue("formName").jsonPrimitive.content, values.getValue("openName").jsonPrimitive.content,
            values.getValue("reach").jsonPrimitive.float)
        val existing = input.parameters.associateBy { it.id }
        val added = definitions.filter { it.id !in existing }
        val prepared = input.copy(parameters = input.parameters + added, meshes = preparedMeshes)
        val changes = MouthFittedSourceAlgorithm().changes(prepared, instance, source, checkpoint)
        require(!changes.parameters.hasWrites) { "Fitted mouth v2 captured an incompatible opening range" }
        val tree = if (added.isEmpty()) null else {
            val group = ParameterNode.Group(instance.outputId("parameter-folder:ParamGroupMouth"),
                values.getValue("group").jsonPrimitive.content, true, added.map { ParameterNode.Param(it.id) })
            val previous = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
            ModifierFieldChange(input.parameterTree, previous + group)
        }
        val pose = input.restPose + ("ParamMouthOpenY" to 1f)
        val evaluated = changes.meshes.updated.associate { it.before.id to it.after }
        val updates = input.meshes.zip(preparedMeshes).mapNotNull { (before, mesh) ->
            val after = evaluated[mesh.id] ?: mesh
            if (after == before) null else ModifierObjectUpdate(before, after)
        }
        return changes.copy(meshes = ModifierObjectChanges(updated = updates), parameters = ModifierObjectChanges(added = added, reused = definitions.mapNotNull { existing[it.id] }),
            parameterTree = tree, restPose = pose.takeIf { it != input.restPose }?.let { ModifierFieldChange(input.restPose, it) })
    }

    private fun geometry(raw: ScaffoldMesh.RawMesh, frame: Bounds, checkpoint: () -> Unit): MeshGeometry {
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        val positions = FloatArray(raw.rigPositions.size)
        for (index in positions.indices step 2) {
            if (index % 1024 == 0) checkpoint()
            positions[index] = (raw.rigPositions[index] - frame.left) / width
            positions[index + 1] = (raw.rigPositions[index + 1] - frame.top) / height
        }
        return MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles))
    }

    companion object {
        const val TYPE = "mouth/fitted"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682f666974746564.v2/binding"
        val HEAD_TURN = setOf("face/fitted", "face.depth-turn")
        val COMPOSABLE = setOf("parameter", "mesh", "hierarchy")
        val WRITES = COMPOSABLE + "restPose"

        fun parameters(form: String, open: String, reach: Float): List<Parameter> =
            MouthLegacyAlgorithm.parameters(form, open).map { if (it.id == "ParamMouthOpenY") it.copy(max = reach) else it }

        fun capture(source: MouthFittedInputs, scaffold: ModifierAlgorithmResource, formName: String, openName: String, reach: Float, group: String): ModifierAlgorithmContext {
            val captured = source.encode()
            return ModifierAlgorithmContext(buildJsonObject {
                put("mouth", captured.values)
                put("formName", formName)
                put("openName", openName)
                put("reach", reach)
                put("group", group)
            }, captured.resources + ("scaffoldGeometry" to scaffold))
        }
    }
}
