package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Replays the fitted brow's local spring transition through its live shared head frames. */
class BrowSpringV6Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        val data = BrowSpringInputs.decode(ModifierAlgorithmContext(context.values,
            context.resources - setOf("springBefore", "springAfter")), checkpoint)
        val ids = data.targets.keys
        require(ids.all { ModifierTarget("mesh", it) in instance.targets })
        val scaffoldId = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base")
        fun provider(type: String, version: Int) = input.providers(type).single {
            it.type == type && it.version == version && ids.all { id -> ModifierTarget("mesh", id) in it.targets }
        }
        val scaffold = provider("rig.scaffold", 2)
        require(scaffold.instanceId == scaffoldId.jsonPrimitive.content && scaffold.targets.toSet() == instance.targets.toSet())
        val fitted = provider("brow/fitted", 4)
        val head = provider("face/fitted", 3)
        val eye = provider("eye/fitted", 5)
        val shape = provider("brow.shape", 5)
        val coupling = provider("brow.lid-coupling", 7)
        require(listOf(fitted, head, eye, shape, coupling).all { it.config["scaffold"] == scaffoldId } &&
            fitted.instanceId == base.jsonPrimitive.content && shape.instanceId == data.shapeProvider &&
            shape.config["base"] == base && coupling.config["base"] == base &&
            (data.eyeProvider == null || eye.instanceId == data.eyeProvider)) { "Brow spring v6 requires its shared-frame source bindings" }
        val blink = provider("brow.blink", 5)
        require(blink.config["scaffold"] == scaffoldId && blink.config["base"] == base) {
            "Brow spring v6 requires its captured blink on the fitted brow"
        }
        val declared = BrowSpringAlgorithm.parameters(data)
        if (declared.isEmpty()) return ModifierModelChanges()
        val parameters = input.parameters
        val existing = parameters.associateBy { it.id }
        val reused = declared.mapNotNull { next ->
            existing[next.id]?.also { require(it.copy(name = next.name) == next) }
        }
        val added = declared.filterNot { it.id in existing }
        val tree = input.parameterTree
        val meshes = ids.associateWith { requireNotNull(input.mesh(it)) }
        val branches = ids.flatMap { input.parentsOfMesh(it) }.distinctBy { it.id }
        val old = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("springBefore").read()))
        val nativeAfter = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("springAfter").read()))
        require(old.drawables.map { it.id.raw }.toSet() == ids && nativeAfter.drawables.map { it.id.raw }.toSet() == ids)
        val axes = declared.mapTo(linkedSetOf()) { ParameterId(it.id) }
        val folder = ParameterNode.Group(ParameterGroupId("ParamGroupPhysics"), data.group, true, axes.map { ParameterNode.Param(it) })
        val after = nativeAfter.copy(parameterTree = BindingMerge.appendTree(nativeAfter, listOf(folder), axes, preserveSourceOrder = false))
        val providers = input.upstream.distinctBy { it.instanceId }.map {
            RigModifierInstance(requireNotNull(it.instanceId), it.type, it.version, it.targets,
                config = it.config, outputAliases = it.outputAliases, plugin = it.plugin)
        }
        val structure = providers.single { it.id == scaffold.instanceId }
        val byId = branches.associateBy { it.id }
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = structure
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = old to after
            override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                old.deformers.mapNotNull { node ->
                    providers.firstNotNullOfOrNull { provider ->
                        val id = ScaffoldBinding.deformerId(provider, target.id, node.id.raw, shared = true)
                        (node.id to DeformerId(id)).takeIf { id in byId }
                    }
                }.toMap()
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                error("Brow spring v6 cannot replace its existing carrier")
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                error("Brow spring v6 cannot replace an organizational part")
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val live = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = branches, meshes = meshes.values.toList(),
            parameterTree = tree, textures = input.textures, authoring = input.authoring))
        val bound = PuppetIr.toIr(BindingMerge.apply(live, instance.copy(targets = instance.targets.filter { it.id in ids }), source, checkpoint))
        require(bound.parameters == parameters + added) { "Brow spring v6 cannot replace its source parameters" }
        val meshUpdates = bound.meshes.mapNotNull { next ->
            val before = meshes.getValue(next.id)
            next.takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }
        val deformerUpdates = bound.deformers.mapNotNull { next ->
            val before = byId.getValue(next.id)
            next.takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }
        val physics = if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) ModifierObjectChanges()
        else BrowSpringPhysics.changes(input.physics, instance, bound.parameters.mapTo(hashSetOf()) { it.id }, data.physicsNames)
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added, reused = reused),
            meshes = ModifierObjectChanges(updated = meshUpdates), deformers = ModifierObjectChanges(updated = deformerUpdates),
            parameterTree = bound.parameterTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) }, physicsGroups = physics)
    }

    companion object {
        const val VERSION = 6
        const val ENTRYPOINT = "builtin.62726f772e737072696e67.v6/spring"
    }
}

object BrowSpringV6Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowSpringV6Algorithm.ENTRYPOINT to BrowSpringV6Algorithm.VERSION) to BrowSpringV6Algorithm(),
    ))
}
