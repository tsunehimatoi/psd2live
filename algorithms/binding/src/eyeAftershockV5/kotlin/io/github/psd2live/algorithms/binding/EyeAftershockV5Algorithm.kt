package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.*

/** Projects blink aftershock in the fitted lid frames and emits its pendulums. */
class EyeAftershockV5Algorithm : ChangingModifierAlgorithmPlugin {
    companion object {
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.6579652e616674657273686f636b.v5/aftershock"
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
        for ((type, version, key) in listOf(Triple("face/fitted", 3, "face"), Triple("eye/fitted", 5, "base"),
            Triple("brow/fitted", 4, "brow"))) {
            val id = (if (key == "base") instance.config else context.values).getValue(key).jsonPrimitive.content
            require(upstream.any { it.id == id && it.type == type && it.version == version &&
                it.config["scaffold"] == JsonPrimitive(scaffoldId) && it.targets.containsAll(instance.targets) }) {
                "Blink aftershock v5 requires its captured $type@$version provider"
            }
        }
        fun phase(name: String) = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue(name).read()))
        val prior = phase("before")
        val after = phase("after")
        val oldParameters = prior.parameters.mapTo(hashSetOf()) { it.id }
        val controls = after.parameters.filterNot { it.id in oldParameters }
        val desired = after.copy(parameterTree = listOf(ParameterNode.Group(ParameterGroupId("ParamGroupPhysics"),
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
        fun <T> objects(before: List<T>, after: List<T>, id: (T) -> String): ModifierObjectChanges<T> {
            val old = before.associateBy(id)
            val next = after.associateBy(id)
            val added = after.filter { id(it) !in old }
            require(after.map(id) == before.map(id).filter { it in next } + added.map(id)) {
                "Blink aftershock v5 cannot reorder existing objects"
            }
            return ModifierObjectChanges(added = added, removed = before.filter { id(it) !in next },
                updated = after.mapNotNull { value -> old[id(value)]?.takeIf { it != value }?.let { ModifierObjectUpdate(it, value) } })
        }
        fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        return ModifierModelChanges(parameters = objects(input.parameters, bound.parameters) { it.id },
            meshes = objects(input.meshes, bound.meshes) { it.id }, deformers = objects(input.deformers, bound.deformers) { it.id },
            parts = objects(input.parts, bound.parts) { it.id }, parameterTree = field(input.parameterTree, bound.parameterTree),
            rootChildren = field(input.rootChildren, bound.rootChildren), renderRoot = field(input.renderRoot, bound.renderRoot),
            physicsGroups = physics(input, instance, context, bound.parameters.mapTo(hashSetOf()) { it.id }))
    }
    private fun physics(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                        available: Set<String>): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val rules = context.values.getValue("physics").jsonArray.map { RigPhysicsEdit.fromJson(it.jsonObject) }
            .filter { rule -> rule.parameters.all { it in available } }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { id -> rules.any { it.id == id } }) { "Unavailable blink aftershock physics slot" }
        val existing = input.physics.groups
        val added = mutableListOf<PhysicsGroup>()
        val reused = mutableListOf<PhysicsGroup>()
        for (rule in rules) {
            val setting = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
            require(setting.inputs.isNotEmpty() && setting.outputs.isNotEmpty() && setting.parameters.all { it in available } &&
                setting.inputs.none { it.parameter in setting.outputParameters }) { "Invalid blink aftershock physics inputs or outputs" }
            val group = PhysicsGroups.fromSetting(setting.copy(id = instance.outputId("physics:${rule.id}")))
            val compatible = (existing + added).firstOrNull { it.copy(id = group.id, name = group.name) == group }
            if (compatible != null) {
                if (compatible in existing && compatible !in reused) reused += compatible
                continue
            }
            val outputs = group.outputs.mapTo(hashSetOf()) { it.parameter }
            require((existing + added).none { it.id == group.id || it.outputs.any { output -> output.parameter in outputs } }) {
                "Conflicting blink aftershock physics group: ${group.id}"
            }
            added += group
        }
        return ModifierObjectChanges(added = added, reused = reused)
    }

}

object EyeAftershockV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeAftershockV5Algorithm.ENTRYPOINT to EyeAftershockV5Algorithm.VERSION) to EyeAftershockV5Algorithm(),
    ))
}
