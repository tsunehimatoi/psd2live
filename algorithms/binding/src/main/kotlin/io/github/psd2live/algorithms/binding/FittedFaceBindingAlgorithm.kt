package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonObject
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.model.*

/** Composes the neutral scaffold, the base face axes and the measured depth turn in one worker. */
object FittedFaceBindingAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        val provider = requireNotNull(context.upstream.singleOrNull { it.capability == "rig.scaffold" && it.version >= 2 })
        val scaffold = RigModifierInstance(requireNotNull(provider.instanceId), provider.type, provider.version,
            provider.targets, outputAliases = provider.outputAliases)
        require(instance.targets.isNotEmpty() && instance.targets.toSet() == scaffold.targets.toSet() &&
            instance.targets.all { it.kind == "mesh" })
        val base = LegacyFaceInputs.decode(ModifierAlgorithmContext(context.values.getValue("base").jsonObject,
            context.resources), checkpoint)
        val turn = DepthTurnInputs.decode(ModifierAlgorithmContext(context.values.getValue("turn").jsonObject, context.resources))
        val head = base.measurements.head
        require(head.headFrame == turn.head && head.faceFrame == turn.face &&
            head.alignedPivot.x == turn.container.pivotX && head.alignedPivot.y == turn.container.pivotY &&
            base.strength == turn.container.strength)
        val names = (ScaffoldHead.build(head, { it }, checkpoint).deformers.map { it.id } +
            ScaffoldHead.leanId + turn.frames.keys).distinct()
        val existing = input.deformers.mapTo(hashSetOf()) { it.id }
        val representative = instance.targets.first().id
        val live = names.mapNotNull { canonical ->
            val alias = ScaffoldBinding.deformerId(scaffold, representative, canonical.raw, shared = true)
            if (alias in existing) canonical to DeformerId(alias) else null
        }.toMap()
        val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ).mapIndexed { i, id ->
            val limit = if (i == 0) 45f else 30f
            Parameter(id, base.parameterLabels[i], -limit, limit, 0f)
        }
        if (ScaffoldHead.faceId !in live) {
            val oldParameters = input.parameters.mapTo(hashSetOf()) { it.id }
            val added = angles.filter { it.id.raw !in oldParameters }.map {
                io.github.psd2live.format.model.Parameter(it.id.raw, it.name, it.min, it.max, it.default)
            }
            return ModifierModelChanges(parameters = ModifierObjectChanges(added = added))
        }
        require(live.values.toSet().size == live.size) { "Invalid scaffold head aliases" }
        require(live.none { (canonical, actual) -> canonical != actual && canonical.raw in existing }) {
            "Scaffold head alias collides with a canonical deformer"
        }
        val before = ModelIdRemap(deformers = live.entries.associate { (canonical, actual) -> actual to canonical })
            .model(PuppetIr.toPuppet(input))
        require(before.parameters.none { p -> angles.any { it.id == p.id } })
        val bound = LegacyFaceBinding.bind(before, base.measurements, base.tuning, base.strength, angles,
            base.folderLabel, checkpoint)
        val targets = instance.targets.mapTo(linkedSetOf()) { it.id }
        val turned = DepthTurnAlgorithm().project(bound, targets, turn, checkpoint)
        val originals = before.deformers.mapTo(hashSetOf()) { it.id }
        val carriers = turned.deformers.filterNot { it.id in originals }.mapTo(linkedSetOf()) { it.id }
        val carrierAliases = carriers.associateWith {
            DeformerId(ScaffoldBinding.deformerId(instance, representative, it.raw, shared = true))
        }
        require(carrierAliases.values.none { it.raw in existing } && carrierAliases.values.toSet().size == carriers.size) {
            "Fitted face carrier aliases collide with another deformer"
        }
        val remap = ModelIdRemap(deformers = live + carrierAliases)
        val keys = turned.parameters.mapTo(hashSetOf()) { it.id }
        val lean = ParameterId("ParamBodyLean")
        val result = remap.model(if (lean in keys) turned else turned.copy(deformers = turned.deformers.map {
            if (it !is Deformer.Warp || it.geometryGrid?.axes.orEmpty().none { axis -> axis.parameterId == lean }) it
            else it.copy(geometryGrid = BindingProjection.restrict(it.geometryGrid, keys, emptyMap(), WarpLatticeInterpolator, checkpoint))
        }))
        val deformers = result.deformers.associateBy { it.id.raw }
        val meshes = result.drawables.associateBy { it.id.raw }
        val parameters = PuppetIr.parametersToIr(result).associateBy { it.id }
        val oldParameters = input.parameters.mapTo(hashSetOf()) { it.id }
        val liveIds = live.values.mapTo(hashSetOf()) { it.raw }
        require(input.parameters.all { parameters[it.id] == it }) { "Fitted face cannot retype an existing parameter" }
        val metadata = PuppetIr.toIr(result.copy(deformers = emptyList(), drawables = emptyList()))
        return ModifierModelChanges(
            deformers = ModifierObjectChanges(
                added = result.deformers.filter { it.id.raw !in existing }.map(PuppetIr::deformerToIr),
                updated = input.deformers.filter { it.id in liveIds }.mapNotNull {
                    update(it, PuppetIr.deformerToIr(deformers.getValue(it.id)))
                },
            ),
            meshes = ModifierObjectChanges(updated = input.meshes.filter { it.id in targets }.mapNotNull {
                val after = PuppetIr.meshToIr(meshes.getValue(it.id))
                update(it, it.copy(parent = after.parent, geometry = after.geometry, offsets = after.offsets,
                    channels = after.channels, shapes = after.shapes))
            }),
            parameters = ModifierObjectChanges(added = parameters.values.filterNot { it.id in oldParameters }),
            parameterLinks = field(input.parameterLinks, metadata.parameterLinks),
            parameterTree = field(input.parameterTree, metadata.parameterTree),
        )
    }

    private fun <T> update(before: T, after: T) = if (before == after) null else ModifierObjectUpdate(before, after)
    private fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
}
