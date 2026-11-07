package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.core.modifier.ReadingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId

/** Applies the arm swing kernel in the source frame measured for the preceding fitted bindings. */
class ArmSwingV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val changes = ArmSwingAlgorithm().changes(input, instance.copy(version = ArmSwingAlgorithm.VERSION), checkpoint)
        if (changes.deformers.updated.isEmpty()) return changes

        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val capability = input.providers("rig.scaffold").single { it.instanceId == scaffoldId }
        val provider = RigModifierInstance(scaffoldId, capability.type, capability.version, capability.targets,
            config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin)
        val values = input.context.values
        val frames = ScaffoldFramesContext.build(values.getValue("frames").jsonObject, checkpoint)
        val branches = ScaffoldBranchesContext.build(values.getValue("branches").jsonObject, frames, checkpoint)
        val natives = (frames + branches.deformers).associateBy { it.id }
        val parameters = input.parameters
        val defaults = parameters.associate { it.id to it.default }
        val representative = instance.targets.first().id
        fun alias(deformer: Deformer) = ScaffoldBinding.deformerId(provider, representative, deformer.id.raw)
        val meshUpdates = ArrayList<ModifierObjectUpdate<Mesh>>()
        val updates = changes.deformers.updated.map { update ->
            val native = branches.deformers.single { alias(it) == update.before.id }
            val parent = natives.getValue(requireNotNull(native.parent))
            if (!parent.id.raw.startsWith("DeformPair_")) return@map update
            require(parent is Deformer.Warp)
            val sourcePoints = requireNotNull(parent.geometryGrid).cells.single().form.controlPoints
            val actualParent = requireNotNull(input.deformer(alias(parent)))
            val liveParent = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = listOf(actualParent)))
                .deformers.single() as Deformer.Warp
            val livePoints = requireNotNull(warpControlPointsAt(liveParent.geometryGrid) { defaults[it.raw] ?: 0f })
            if (sourcePoints.contentEquals(livePoints)) return@map update

            // The pair's live neutral frame can differ after upstream fitting. Transport only the new swing shape.
            val source = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = natives.values.map(PuppetIr::deformerToIr)))
            val members = instance.targets.mapNotNull { input.mesh(it.id)?.takeIf { mesh -> mesh.parent == update.before.id } }
            val liveBranch = input.parentsOfMesh(members.first().id)
            val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = members, deformers = liveBranch))
            val actual = current.deformers.first() as Deformer.Warp
            val result = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = listOf(update.after)))
                .deformers.single() as Deformer.Warp
            val added = result.blendShapes.filter { shape -> actual.blendShapes.none { it.parameterId == shape.parameterId } }
            val shapes = WarpBlendResidual.warp(current, source, source, actual, native,
                native.copy(blendShapes = added), result, DrawableId(instance.outputId("${update.before.id}/arm-morph")), checkpoint)
            val swing = result.copy(blendShapes = shapes)
            val moved = current.copy(deformers = listOf(swing) + current.deformers.drop(1))
            for (member in members) {
                require(member.geometry != null && member.shapes.isEmpty() && member.offsets?.axes.orEmpty().isEmpty() &&
                    member.offsets?.cells.orEmpty().all { cell -> cell.form.deltas.shared().all { it == 0f } }) {
                    "Arm swing v3 cannot carry edited arm keyforms through a fitted pair"
                }
                val id = DrawableId(member.id)
                val carried = BindingFrames.carriedToParent(current, moved, id, checkpoint)
                val rebound = PuppetIr.meshToIr(carried.drawables.single { it.id == id }).copy(offsets = null)
                if (rebound != member) meshUpdates += ModifierObjectUpdate(member, rebound)
            }
            ModifierObjectUpdate(update.before, PuppetIr.deformerToIr(swing))
        }
        return changes.copy(deformers = ModifierObjectChanges(updated = updates), meshes = ModifierObjectChanges(updated = meshUpdates))
    }

    companion object {
        val WRITES = ArmSwingAlgorithm.WRITES + "mesh"
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.626f64792e61726d73.v3/swing"
    }
}

object ArmSwingV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (ArmSwingV3Algorithm.ENTRYPOINT to ArmSwingV3Algorithm.VERSION) to ArmSwingV3Algorithm(),
    ))
}
