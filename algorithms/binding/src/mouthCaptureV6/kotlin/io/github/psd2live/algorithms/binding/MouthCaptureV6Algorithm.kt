package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.*

/** Applies open-mouth capture tracks in the live fitted head frames. */
class MouthCaptureV6Algorithm : ChangingModifierAlgorithmPlugin {
    companion object {
        const val VERSION = 6
        const val ENTRYPOINT = "builtin.6d6f7574682e63617074757265.v6/capture"
    }

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val upstream = context.upstream.distinctBy { it.instanceId }.mapNotNull { capability ->
            capability.instanceId?.let { id -> RigModifierInstance(id, capability.type, capability.version, capability.targets,
                config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin) }
        }
        val scaffold = upstream.single { it.id == scaffoldId && it.type == "rig.scaffold" && it.version == 2 &&
            it.targets.containsAll(instance.targets) }
        val base = instance.config.getValue("base").jsonPrimitive.content
        fun provider(type: String, id: String, versions: Set<Int>): RigModifierInstance = upstream.single {
            it.id == id && it.type == type && it.version in versions &&
                it.config["scaffold"] == JsonPrimitive(scaffoldId) && it.targets.containsAll(instance.targets)
        }
        provider("mouth/fitted", base, setOf(6))
        provider("mouth.outline", context.values.getValue("outline").jsonPrimitive.content, setOf(1, 8))
        val vowel = provider("mouth.vowel", context.values.getValue("vowel").jsonPrimitive.content, setOf(1, 7))
        require(vowel.config["base"] == instance.config["base"]) { "Mouth capture v6 requires the same fitted vowel source" }
        fun phase(name: String) = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue(name).read()))
        val prior = phase("before")
        val after = phase("after")
        val oldParameters = prior.parameters.mapTo(hashSetOf()) { it.id }
        val controls = after.parameters.filterNot { it.id in oldParameters }
        val desired = after.copy(parameterTree = listOf(ParameterNode.Group(ParameterGroupId("ParamGroupMouth"),
            context.values.getValue("group").jsonPrimitive.content, true, controls.map { ParameterNode.Param(it.id) })))
        val ids = prior.drawables.mapTo(linkedSetOf()) { it.id.raw }
        require(ids.isNotEmpty() && ids == desired.drawables.mapTo(linkedSetOf()) { it.id.raw } &&
            ids.all { ModifierTarget("mesh", it) in instance.targets })
        val live = PuppetIr.toPuppet(input)
        val liveDeformers = live.deformers.mapTo(hashSetOf()) { it.id }
        val liveParts = live.parts.mapTo(hashSetOf()) { it.id }
        val owners = listOf(scaffold) + upstream.filterNot { it.id == scaffoldId }
        val canonicalDeformers = (prior.deformers + desired.deformers).map { it.id }.distinct()
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = scaffold
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to desired
            override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                canonicalDeformers.associateWith { canonical ->
                    owners.asSequence().filter { target in it.targets }
                        .map { DeformerId(ScaffoldBinding.deformerId(it, target.id, canonical.raw, shared = true)) }
                        .firstOrNull { it in liveDeformers }
                        ?: DeformerId(ScaffoldBinding.deformerId(instance, target.id, canonical.raw, shared = true))
                }
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                (prior.deformers + desired.deformers).mapNotNull { it.partId }.distinct().associateWith { canonical ->
                    owners.asSequence().map { PartId(ScaffoldBinding.partId(it, target.id, canonical.raw, shared = true)) }
                        .first { it in liveParts }
                }
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                ScaffoldBinding.partId(instance, mesh, canonical, shared)
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val scoped = instance.copy(targets = instance.targets.filter { it.id in ids })
        val bound = PuppetIr.toIr(BindingMerge.apply(live, scoped, source, checkpoint).withParametersSyncedFromTree())
        require(bound.parts == input.parts && bound.deformers == input.deformers &&
            bound.rootChildren == input.rootChildren && bound.renderRoot == input.renderRoot &&
            bound.meshes.map { it.id } == input.meshes.map { it.id }) {
            "Mouth capture v6 cannot replace the fitted hierarchy"
        }
        require(bound.parameters.take(input.parameters.size) == input.parameters) {
            "Mouth capture v6 cannot replace existing parameters"
        }
        val updated = input.meshes.zip(bound.meshes).mapNotNull { (before, after) ->
            require(before.geometry == after.geometry && before.parent == after.parent) {
                "Mouth capture v6 cannot replace fitted mesh topology"
            }
            ModifierObjectUpdate(before, after).takeIf { before != after }
        }
        return ModifierModelChanges(
            parameters = ModifierObjectChanges(added = bound.parameters.drop(input.parameters.size)),
            meshes = ModifierObjectChanges(updated = updated),
            parameterTree = bound.parameterTree.takeIf { it != input.parameterTree }
                ?.let { ModifierFieldChange(input.parameterTree, it) },
        )
    }
}

object MouthCaptureV6Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthCaptureV6Algorithm.ENTRYPOINT to MouthCaptureV6Algorithm.VERSION) to MouthCaptureV6Algorithm(),
    ))
}
