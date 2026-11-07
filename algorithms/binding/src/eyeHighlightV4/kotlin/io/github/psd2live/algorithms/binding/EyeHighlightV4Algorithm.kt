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

/** Projects source glints and their iris carriers through the live fitted eye frames. */
class EyeHighlightV4Algorithm : ChangingModifierAlgorithmPlugin {
    companion object {
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6579652e686967686c69676874.v4/highlight"
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
                "Eye highlight v4 requires its captured $type@$version provider"
            }
        }
        for ((type, key) in listOf("eye.smile" to "smile", "eye.wide" to "wide",
            "eye.gaze-lid" to "gaze", "eye.emotion" to "emotion")) {
            val id = context.values.getValue(key).jsonPrimitive.content
            require(upstream.any { it.id == id && it.type == type && it.version == 4 &&
                it.config["base"] == instance.config["base"] && it.config["scaffold"] == JsonPrimitive(scaffoldId) &&
                it.targets.containsAll(instance.targets) }) { "Eye highlight v4 requires its captured $type@4 provider" }
        }
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
        fun <T> objects(before: List<T>, after: List<T>, id: (T) -> String): ModifierObjectChanges<T> {
            val old = before.associateBy(id)
            val next = after.associateBy(id)
            val added = after.filter { id(it) !in old }
            require(after.map(id) == before.map(id).filter { it in next } + added.map(id)) {
                "Eye highlight v4 cannot reorder existing objects"
            }
            return ModifierObjectChanges(added = added, removed = before.filter { id(it) !in next },
                updated = after.mapNotNull { value -> old[id(value)]?.takeIf { it != value }?.let { ModifierObjectUpdate(it, value) } })
        }
        fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        require(bound.parameters == input.parameters && bound.parameterTree == input.parameterTree) {
            "Eye highlight v4 cannot change fitted parameters"
        }
        return ModifierModelChanges(meshes = objects(input.meshes, bound.meshes) { it.id }, deformers = objects(input.deformers, bound.deformers) { it.id },
            parts = objects(input.parts, bound.parts) { it.id },
            rootChildren = field(input.rootChildren, bound.rootChildren), renderRoot = field(input.renderRoot, bound.renderRoot))
    }
}

object EyeHighlightV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeHighlightV4Algorithm.ENTRYPOINT to EyeHighlightV4Algorithm.VERSION) to EyeHighlightV4Algorithm(),
    ))
}
