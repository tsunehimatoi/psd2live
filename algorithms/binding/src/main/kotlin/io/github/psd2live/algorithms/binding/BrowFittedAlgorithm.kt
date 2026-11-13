package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.*

/** The height-only fitted base. Angle, form, blink and spring belong to their own modifiers. */
class BrowFittedAlgorithm : ChangingModifierAlgorithmPlugin {
    /** Standalone entry point for neutral scaffold input. Authored residuals use [project] and BindingMerge. */
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): ModifierModelChanges {
        val inputs = BrowFittedInputs.decode(context)
        require(context.upstream.any { it.type == "rig.scaffold" && it.instanceId == inputs.scaffold && it.targets.containsAll(instance.targets) }) {
            "Fitted brow requires its captured scaffold provider"
        }
        for (eye in inputs.eyes.values) require(context.upstream.any { it.type == "eye/fitted" && it.instanceId == eye.provider }) {
            "Fitted brow requires its captured eye measurements provider"
        }
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        val source = PuppetIr.toPuppet(input.copy(meshes = input.meshes.filter { it.id in targets }))
        require(source.drawables.filter { it.id.raw in inputs.layers }.all { mesh ->
            mesh.geometryGrid?.axes.isNullOrEmpty() && mesh.blendShapes.isEmpty()
        }) { "Fitted brow requires a neutral scaffold before authored motion is merged" }
        val desired = project(source, instance, inputs, context.resources.getValue(BrowFittedInputs.VERTICES), checkpoint)
        val declared = PuppetIr.parametersToIr(desired)
        val existing = input.parameters.associateBy { it.id }
        val added = declared.filter { it.id !in existing }
        for (parameter in declared) require(existing[parameter.id]?.let { it.copy(name = parameter.name) == parameter } != false) {
            "Incompatible fitted brow parameter ${parameter.id}"
        }
        val original = input.meshes.associateBy { it.id }
        val before = source.drawables.associateBy { it.id }
        val updated = desired.drawables.mapNotNull { mesh ->
            if (mesh === before[mesh.id]) null else {
                val old = original.getValue(mesh.id.raw)
                val next = old.copy(offsets = PuppetIr.meshToIr(mesh).offsets)
                ModifierObjectUpdate(old, next).takeIf { old != next }
            }
        }
        val groupId = instance.outputId("parameter-folder:ParamGroupBrows")
        val tree = if (added.isEmpty()) null else {
            val children = added.map { io.github.psd2live.format.model.ParameterNode.Param(it.id) }
            val group = io.github.psd2live.format.model.ParameterNode.Group(groupId, inputs.groupName, true, children)
            require(input.parameterTree.none { it is io.github.psd2live.format.model.ParameterNode.Group && it.id == groupId }) {
                "Fitted brow parameter folder already exists"
            }
            ModifierFieldChange(input.parameterTree, input.parameterTree + group)
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added),
            meshes = ModifierObjectChanges(updated = updated), parameterTree = tree)
    }

    /** Produces the canonical local transition for the host's authored residual merge. */
    fun project(
        source: PuppetModel,
        instance: RigModifierInstance,
        inputs: BrowFittedInputs,
        vertices: ModifierAlgorithmResource,
        checkpoint: () -> Unit = {},
    ): PuppetModel {
        require(instance.version >= 1 && instance.targets.all { it.kind == "mesh" }) { "Unsupported fitted brow instance" }
        require(instance.config["scaffold"]?.jsonPrimitive?.content == inputs.scaffold) { "Mismatched fitted brow scaffold" }
        val lift = (instance.config["values"]?.jsonObject?.get("lift")?.jsonPrimitive?.float ?: 45f) / 100f
        require(lift in 0.2f..1f) { "Invalid fitted brow lift" }
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices, checkpoint)
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        require(source.drawables.mapTo(hashSetOf()) { it.id.raw }.containsAll(targets))
        val rigs = inputs.fits.mapValues { (side, fit) ->
            val travel = inputs.eyes[side]?.let { it.opening * it.width * lift } ?: (fit.length * 0.25f * lift / 0.45f)
            BrowRig(fit, travel, BrowOptions(), checkpoint)
        }
        val left = ParameterId("ParamBrowLY")
        val right = ParameterId("ParamBrowRY")
        val parameters = listOf(Parameter(left, inputs.leftName, -1f, 1f, 0f), Parameter(right, inputs.rightName, -1f, 1f, 0f))
        val meshes = source.drawables.map { mesh ->
            checkpoint()
            if (mesh.id.raw !in targets) return@map mesh
            val layer = inputs.layers[mesh.id.raw] ?: return@map mesh
            val geometry = mesh.mesh ?: return@map mesh
            val captured = raw.getValue(layer.canonical)
            val positions = captured.rigPositions
            require(geometry.indices.contentEquals(captured.triangles) && geometry.positions.size == positions.size) {
                "Fitted brow requires canonical scaffold topology"
            }
            val y = if (layer.side == Side.LEFT) left else right
            val brow = rigs[layer.side]?.takeIf { layer.primary }
            val grid = brow?.grid(positions, layer.frame, y, ParameterId(if (layer.side == Side.LEFT) "ParamBrowLAngle" else "ParamBrowRAngle"))
                ?: fallback(positions.size, layer, y, inputs.fallbackLift)
            mesh.copy(geometryGrid = grid)
        }
        val group = ParameterNode.Group(ParameterGroupId("ParamGroupBrows"), inputs.groupName, true,
            parameters.map { ParameterNode.Param(it.id) })
        return source.copy(drawables = meshes, parameters = parameters, parameterTree = listOf(group))
    }

    private fun fallback(size: Int, layer: BrowFittedInputs.Layer, parameter: ParameterId, lift: Float): KeyformGrid<MeshDeltaForm> {
        val keys = floatArrayOf(-1f, 0f, 1f)
        return KeyformGrid(listOf(KeyformAxis(parameter, keys)), keys.mapIndexed { index, value ->
            val dy = -value * layer.height * lift / 100f / layer.frame.height
            KeyformCell(intArrayOf(index), MeshDeltaForm(FloatArray(size) { if (it % 2 == 0) 0f else dy }))
        })
    }
}
