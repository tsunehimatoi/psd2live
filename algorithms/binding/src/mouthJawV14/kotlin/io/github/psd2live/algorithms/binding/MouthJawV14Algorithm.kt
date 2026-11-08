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

/** Applies jaw motion to the lower face and authored layers of a fitted mouth. */
object MouthJawV14Algorithm : ChangingModifierAlgorithmPlugin {
    const val VERSION = 14
    val WRITES = setOf("mesh")
    const val ENTRYPOINT = "builtin.6d6f7574682e6a6177.v14/jaw"

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
        val mouthId = instance.config.getValue("base").jsonPrimitive.content
        require(upstream.any { it.id == mouthId && it.type == "mouth/fitted" && it.version == 8 &&
            it.config["scaffold"] == JsonPrimitive(scaffoldId) && it.targets.containsAll(instance.targets) }) {
            "Mouth jaw v14 requires its captured fitted mouth"
        }
        val expectedProviders = mapOf("mouth.outline" to setOf(1, 11), "mouth.vowel" to setOf(8), "mouth.capture" to setOf(7), "mouth.tongue" to setOf(9))
        val observed = context.values.getValue("providers").jsonObject
        require(observed.keys == expectedProviders.keys)
        for ((type, versions) in expectedProviders) {
            require(upstream.any { provider -> provider.id == observed.getValue(type).jsonPrimitive.content &&
                provider.type == type && provider.version in versions && provider.targets.containsAll(instance.targets) &&
                provider.config["scaffold"] == JsonPrimitive(scaffoldId) &&
                (type == "mouth.outline" || provider.config["base"] == instance.config["base"]) &&
                (type != "mouth.tongue" || provider.config["dependencies"]?.jsonObject?.get("mouth.capture") == observed["mouth.capture"]) }) {
                "Mouth jaw v14 requires its captured $type provider"
            }
        }
        val tongue = upstream.single { it.id == observed.getValue("mouth.tongue").jsonPrimitive.content }
        require(tongue.plugin?.id == MouthTongueV9Algorithm.ENTRYPOINT.substringBefore('/')) {
            "Mouth jaw v14 requires the layered tongue-tip package"
        }
        val sourceMeshes = input.meshes.associateBy { it.id }
        val tips = input.meshes.filter { mesh ->
            mesh.textureSource?.let { source -> mesh.id == tongue.outputId(MouthTongueV9Algorithm.tipOutput(source)) } == true
        }
        require(tips.isNotEmpty() && tips.all { tip ->
            val owner = sourceMeshes.getValue(requireNotNull(tip.textureSource))
            ModifierTarget("mesh", owner.id) in tongue.targets &&
                owner.maskedBy.isNotEmpty() && tip.maskedBy.isEmpty() && owner.tile == tip.tile && owner.page == tip.page
        }) { "Mouth jaw v14 requires live tips owned by its tongue provider" }
        fun phase(name: String) = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue(name).read()))
        val prior = phase("before")
        val desired = phase("after")
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
        require(bound.meshes.map { it.id } == input.meshes.map { it.id } &&
            bound.deformers == input.deformers && bound.parameters == input.parameters && bound.parts == input.parts) {
            "Mouth jaw v14 requires mesh-local lower-face motion"
        }
        val resultMeshes = bound.meshes.associateBy { it.id }
        require(tips.all { tip ->
            val ownerId = requireNotNull(tip.textureSource)
            val before = sourceMeshes.getValue(ownerId)
            val after = resultMeshes.getValue(ownerId)
            resultMeshes.getValue(tip.id) == tip && before.parent == after.parent && before.maskedBy == after.maskedBy &&
                before.invertMask == after.invertMask && before.tile == after.tile && before.page == after.page &&
                before.textureSource == after.textureSource
        }) { "Mouth jaw v14 must preserve source tongue and tip ownership" }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = input.meshes.zip(bound.meshes).mapNotNull { (before, after) ->
            after.takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }))
    }
}

object MouthJawV14Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthJawV14Algorithm.ENTRYPOINT to MouthJawV14Algorithm.VERSION) to MouthJawV14Algorithm,
    ))
}
