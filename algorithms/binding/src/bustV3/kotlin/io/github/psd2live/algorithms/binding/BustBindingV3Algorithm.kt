package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.*

/** Computes the chest shape residual in the actual preceding source frame. */
class BustBindingV3Algorithm : ReadingModifierAlgorithmPlugin {
    private val ordinary = BustBindingAlgorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        val original = instance.copy(version = BustBindingAlgorithm.VERSION)
        if ("bustBefore" !in context.resources) return ordinary.changes(input, original, checkpoint)
        val captured = ModifierAlgorithmContext(context.values, context.resources - setOf("bustBefore", "bustAfter"), input.upstream)
        val before = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("bustBefore").read()))
        val after = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("bustAfter").read()))
        val targets = before.drawables.mapTo(hashSetOf()) { it.id.raw }
        require(after.drawables.mapTo(hashSetOf()) { it.id.raw } == targets && instance.targets.map { it.id }.containsAll(targets))
        val liveMeshes = targets.associateWith { requireNotNull(input.mesh(it)) }
        val branches = targets.flatMap { input.parentsOfMesh(it) }.distinctBy { it.id }
        val live = RigIR(input.canvas, input.parameters, deformers = branches, meshes = liveMeshes.values.toList(),
            parameterTree = input.parameterTree, textures = input.textures, authoring = input.authoring, physics = input.physics)
        val declarations = ordinary.changes(ModifierEvaluationInput(live, captured), original, checkpoint)
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val scaffold = input.providers("rig.scaffold").single { it.instanceId == scaffoldId && it.type == "rig.scaffold" && it.version == 2 }
        val structure = RigModifierInstance(scaffoldId, scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = structure
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = before to after
            override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                before.deformers.associate { it.id to DeformerId(ScaffoldBinding.deformerId(structure, target.id, it.id.raw)) }
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                error("Chest binding cannot replace its source carrier")
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                error("Chest binding cannot replace an organizational part")
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val runtime = PuppetIr.toPuppet(live.copy(parameters = live.parameters + declarations.parameters.added))
        val applied = BindingMerge.apply(runtime, instance.copy(targets = instance.targets.filter { it.id in targets }), source, checkpoint)
        val updates = applied.drawables.mapNotNull { drawable ->
            val mesh = PuppetIr.meshToIr(drawable)
            val previous = liveMeshes.getValue(mesh.id)
            previous.copy(shapes = mesh.shapes).takeIf { it != previous }?.let { ModifierObjectUpdate(previous, it) }
        }
        return declarations.copy(meshes = ModifierObjectChanges(updated = updates))
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.626f64792e62757374.v3/bust"
    }
}

object BustBindingV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BustBindingV3Algorithm.ENTRYPOINT to BustBindingV3Algorithm.VERSION) to BustBindingV3Algorithm(),
    ))
}
