package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.*
import io.github.psd2live.format.model.Deformer as IrDeformer

/** Adds only the legacy hair contribution to the live scaffold and its authored residuals. */
class HairLegacyAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        val captured = HairLegacyInputs.decode(context)
        val targets = instance.targets.mapTo(hashSetOf()) { target ->
            require(target.kind == "mesh") { "Legacy hair requires mesh targets" }
            target.id
        }
        require(targets.containsAll(captured.targets.keys)) { "Legacy hair source exceeds the captured target scope" }
        val source = PuppetIr.toPuppet(input)
        val additions = captured.parameters.filter { parameter -> input.parameters.none { it.id == parameter.id } }
        val parameters = source.parameters + additions.map { Parameter(ParameterId(it.id), it.name, it.min, it.max, it.default) }
        val folder = ParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupPhysics")),
            captured.physicsFolderName, true, captured.parameters.map { ParameterNode.Param(ParameterId(it.id)) })
        val tree = if (additions.isEmpty()) source.parameterTree else BindingMerge.appendTree(
            source.copy(parameters = parameters), listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val defaults = parameters.associate { it.id to it.default }
        val original = source.deformers.associateBy { it.id }
        val drawables = source.drawables.associateBy { it.id.raw }
        val updated = linkedMapOf<DeformerId, Deformer.Warp>()
        val movedAtRest = hashSetOf<DeformerId>()
        val nativeCarriers = hashSetOf<DeformerId>()
        var model = source.copy(parameters = parameters, parameterTree = tree)

        for ((id, target) in captured.targets) {
            checkpoint()
            val sample = drawables.getValue(id)
            val measurements = captured.planes.getValue(target.plane)
            val frame = measurements.frame
            val sway = target.sway
            val face = target.fittedFace?.let { original[DeformerId(it)] as? Deformer.Warp }
            val holdTurn = face?.geometryGrid?.axes?.any { axis ->
                axis.parameterId == ParameterId("ParamAngleX") && axis.keys.size > 3
            } == true
            val geometry = listOfNotNull(
                Carrier(target.follow, target.head, 4,
                    if (target.earlierBinding) HairLegacyMotion.follow(frame, measurements.defaults, target.activeTurnAxes, holdTurn, checkpoint)
                    else ScaffoldPairs.identityWarp(target.plane.follow, "", ScaffoldHead.headId, frame.hair, frame.head, PartId("PartHair" + target.plane.token), 4, 3).geometryGrid!!,
                    HairLegacyMotion.follow(frame, measurements.current, target.activeTurnAxes, holdTurn, checkpoint)),
                sway?.let {
                    Carrier(it, target.follow, target.plane.rows,
                        HairLegacyMotion.sway(target.plane, frame.hair, measurements.defaults, false, checkpoint),
                        HairLegacyMotion.sway(target.plane, frame.hair, measurements.current, true, checkpoint))
                },
            )
            // The old merge applies the branch from the mesh upwards and keeps a shared carrier once.
            for (carrier in geometry.asReversed()) {
                checkpoint()
                val carrierId = DeformerId(carrier.id)
                if (carrierId in updated) continue
                val actual = original[carrierId] as? Deformer.Warp
                    ?: error("${instance.id}: missing legacy hair scaffold warp ${carrier.id}")
                require(actual.rows == carrier.rows && actual.columns == 3) {
                    "${instance.id}: incompatible legacy hair lattice dimensions on ${carrier.id}"
                }
                val anchor = DeformerId(carrier.parent)
                fun projected(grid: KeyformGrid<WarpLatticeForm>): Deformer.Warp {
                    val canonical = actual.copy(parent = anchor, geometryGrid = grid, blendShapes = emptyList())
                    return if (actual.parent == anchor) canonical else BindingFrames.reparent(model, sample, canonical,
                        anchor, actual.parent, instance.outputId("$id/frame"), checkpoint)
                }
                val before = projected(carrier.before)
                val after = projected(carrier.after)
                if (actual.parent == anchor && actual.blendShapes.isEmpty() &&
                    PuppetIr.deformerToIr(actual) == PuppetIr.deformerToIr(before)) nativeCarriers += carrierId
                val merged = actual.copy(geometryGrid = BindingMerge.warpDelta(actual.geometryGrid, before.geometryGrid,
                    after.geometryGrid, defaults, checkpoint))
                val result = merged.copy(blendShapes = WarpBlendResidual.warp(model, model, model, actual, before, after,
                    merged, DrawableId(instance.outputId("$id/warp-morph:${if (sway == carrier.id) target.plane.sway.raw else target.plane.follow.raw}")),
                    checkpoint))
                fun rest(warp: Deformer.Warp) = requireNotNull(warpControlPointsAt(warp.geometryGrid) { defaults[it] ?: 0f })
                if (!rest(actual).contentEquals(rest(result))) movedAtRest += carrierId
                updated[carrierId] = result
            }
        }

        model = model.copy(deformers = model.deformers.map { updated[it.id] ?: it })
        val meshUpdates = mutableListOf<ModifierObjectUpdate<io.github.psd2live.format.model.Mesh>>()
        val meshes = input.meshes.associateBy { it.id }
        for ((id, target) in captured.targets) {
            checkpoint()
            val sample = drawables.getValue(id)
            val visited = hashSetOf<DeformerId>()
            var parent = sample.parentDeformerId
            while (parent != null && parent !in movedAtRest) {
                require(visited.add(parent)) { "Cyclic legacy hair parent branch" }
                parent = original.getValue(parent).parent
            }
            if (parent == null) continue
            val geometry = sample.mesh ?: continue
            val native = sample.parentDeformerId?.raw == (target.sway ?: target.follow) &&
                sample.geometryGrid == null && sample.blendShapes.isEmpty() &&
                DeformerId(target.follow) in nativeCarriers &&
                (target.sway == null || DeformerId(target.sway) in nativeCarriers) &&
                HairLegacyInputs.meshIdentity(geometry) == target.neutralMesh
            if (native) continue
            val carried = BindingFrames.carriedToParent(source, model, sample.id, checkpoint)
            val moved = PuppetIr.meshToIr(carried.drawables.single { it.id == sample.id })
            val before = meshes.getValue(id)
            val after = before.copy(geometry = moved.geometry, offsets = moved.offsets, shapes = moved.shapes)
            if (before != after) meshUpdates += ModifierObjectUpdate(before, after)
        }

        val byId = input.deformers.associateBy { it.id }
        val warpUpdates = updated.values.mapNotNull { value ->
            val before = byId.getValue(value.id.raw)
            val after = PuppetIr.deformerToIr(value)
            if (before == after) null else ModifierObjectUpdate<IrDeformer>(before, after)
        }
        val nextTree = if (tree == source.parameterTree) input.parameterTree else PuppetIr.toIr(source.copy(
            parameterTree = tree, drawables = emptyList(), deformers = emptyList(), parts = emptyList())).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions), meshes = ModifierObjectChanges(updated = meshUpdates),
            deformers = ModifierObjectChanges(updated = warpUpdates),
            parameterTree = nextTree.takeIf { it != input.parameterTree }?.let { ModifierFieldChange(input.parameterTree, it) })
    }

    private data class Carrier(
        val id: String, val parent: String, val rows: Int,
        val before: KeyformGrid<WarpLatticeForm>, val after: KeyformGrid<WarpLatticeForm>,
    )
}
