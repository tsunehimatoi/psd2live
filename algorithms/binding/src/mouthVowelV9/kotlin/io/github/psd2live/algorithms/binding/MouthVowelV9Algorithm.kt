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

/** Adds source vowel shapes to the open mouth on the fallback head frames. */
object MouthVowelV9Algorithm : ChangingModifierAlgorithmPlugin {
    const val VERSION = 9
    val WRITES = setOf("mesh", "parameter", "hierarchy")
    private val vowelIds = MouthRig.VOWELS.mapTo(hashSetOf()) { it.second }

    fun parameters(context: ModifierAlgorithmContext): List<Parameter> =
        RigIrBinary.decode(context.resources.getValue("after").read()).parameters.filter { ParameterId(it.id) in vowelIds }
    const val ENTRYPOINT = "builtin.6d6f7574682e766f77656c.v9/vowel"

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
        val base = instance.config.getValue("base")
        require(upstream.any { it.type == "mouth/fitted" && it.version == 7 &&
            JsonPrimitive(it.id) == base && it.config["scaffold"] == JsonPrimitive(scaffoldId) &&
            it.targets.containsAll(instance.targets) }) { "Mouth vowel v9 requires its fitted mouth v7" }
        val outline = context.values.getValue("outline").jsonPrimitive.content
        require(upstream.any { it.id == outline && it.type == "mouth.outline" && it.version == 10 &&
            it.config["scaffold"] == JsonPrimitive(scaffoldId) &&
            it.targets.containsAll(instance.targets) }) { "Mouth vowel v9 requires its captured outline" }
        fun phase(name: String) = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue(name).read()))
        val prior = phase("before")
        val native = phase("after")
        val controls = native.parameters.filter { it.id in vowelIds }
        require(controls.mapTo(hashSetOf()) { it.id } == vowelIds)
        val folder = ParameterNode.Group(ParameterGroupId("ParamGroupMouth"), context.values.getValue("folder").jsonPrimitive.content,
            true, controls.map { ParameterNode.Param(it.id) })
        val desired = native.copy(parameterTree = listOf(folder))
        val ids = prior.drawables.mapTo(linkedSetOf()) { it.id.raw }
        require(ids.isNotEmpty() && ids == desired.drawables.mapTo(linkedSetOf()) { it.id.raw } &&
            ids.all { ModifierTarget("mesh", it) in instance.targets })
        val live = PuppetIr.toPuppet(input)
        val liveDeformers = live.deformers.mapTo(hashSetOf()) { it.id }
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
                error("Mouth vowel v9 cannot replace a carrier branch")
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                error("Mouth vowel v9 cannot replace an organizational part")
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val scoped = instance.copy(targets = instance.targets.filter { it.id in ids })
        val bound = PuppetIr.toIr(BindingMerge.apply(live, scoped, source, checkpoint).withParametersSyncedFromTree())
        val actualParameters = input.parameters.associateBy { it.id }
        val additions = bound.parameters.filter { it.id !in actualParameters }
        require(additions.all { ParameterId(it.id) in vowelIds } &&
            bound.parameters.filter { it.id in actualParameters } == input.parameters)
        require(bound.deformers == input.deformers && bound.parts == input.parts && bound.meshes.map { it.id } == input.meshes.map { it.id }) {
            "Mouth vowel v9 requires stable fitted frames"
        }
        val meshes = input.meshes.zip(bound.meshes).mapNotNull { (before, after) ->
            require(before.copy(shapes = after.shapes) == after) { "Mouth vowel v9 cannot change the fitted mesh: ${before.id}" }
            after.takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = meshes),
            parameterTree = bound.parameterTree.takeIf { it != input.parameterTree }?.let { ModifierFieldChange(input.parameterTree, it) })
    }
}

object MouthVowelV9Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthVowelV9Algorithm.ENTRYPOINT to MouthVowelV9Algorithm.VERSION) to MouthVowelV9Algorithm,
    ))
}
