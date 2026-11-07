package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

object BrowBlinkV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowBlinkV5Algorithm.ENTRYPOINT to BrowBlinkV5Algorithm.VERSION) to BrowBlinkV5Algorithm(),
    ))
}

/** Transfers the source blink delta through the live brow on a shared fitted frame and its existing head turn. */
class BrowBlinkV5Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffoldId = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base")
        val capabilities = input.upstream
        val owners = capabilities.distinctBy { it.instanceId }.mapNotNull { provider ->
            provider.instanceId?.let { id ->
                RigModifierInstance(id, provider.type, provider.version, provider.targets,
                    config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
            }
        }
        val scaffold = owners.single {
            it.type == "rig.scaffold" && it.id == scaffoldId.jsonPrimitive.content && it.version == 2 &&
                it.targets.containsAll(instance.targets)
        }
        require(capabilities.any {
            it.type == "brow/fitted" && it.instanceId == base.jsonPrimitive.content && it.version == 4 &&
                it.config["scaffold"] == scaffoldId && it.targets.containsAll(instance.targets)
        }) { "Brow blink v5 requires its fitted brow v4" }
        val context = input.context
        val before = RigIrBinary.decode(context.resources.getValue("before").read())
        val after = RigIrBinary.decode(context.resources.getValue("after").read())
        val beforeMeshes = before.meshes.associateBy { it.id }
        val afterMeshes = after.meshes.associateBy { it.id }
        val canonicalIds = context.values.getValue("native_ids").jsonObject
        val shapeNames = context.values.getValue("shape_names").jsonObject
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        require(beforeMeshes.keys == afterMeshes.keys && beforeMeshes.keys == canonicalIds.keys &&
            beforeMeshes.keys == shapeNames.keys && beforeMeshes.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val parameters = input.parameters
        val added = linkedMapOf<String, Deformer>()
        val updated = linkedMapOf<String, ModifierObjectUpdate<Deformer>>()
        val meshes = beforeMeshes.mapNotNull { (id, source) ->
            checkpoint()
            val target = ModifierTarget("mesh", id)
            val providers = capabilities.filter { it.config["scaffold"] == scaffoldId && target in it.targets }
            require(providers.none { it.capability == "brow.blink" })
            require(providers.any { it.type == "eye/fitted" && it.version == 5 }) { "Brow blink v5 requires fitted eyes v5" }
            require(providers.filter { it.capability == "brow.shape" }.all {
                it.type == "brow.shape" && it.version in setOf(1, 5) && it.config["base"] == base
            }) { "Brow blink v5 requires compatible fitted brow shapes" }
            require(providers.filter { it.capability == "brow.lid-coupling" }.all {
                it.type == "brow.lid-coupling" && it.version in setOf(1, 7) && it.config["base"] == base
            }) { "Brow blink v5 requires compatible brow coupling" }

            val desired = afterMeshes.getValue(id)
            val live = requireNotNull(input.mesh(id))
            val ancestry = input.parentsOfMesh(id)
            val actualDeformers = ancestry.associateBy { it.id }
            val nativeId = canonicalIds.getValue(id).jsonPrimitive.content
            val shapeName = shapeNames.getValue(id).jsonPrimitive.content
            val blinkAxes = desired.shapes.map { it.parameter }.toSet() - source.shapes.map { it.parameter }.toSet()
            val witnesses = instance.targets.asSequence().filter { it.id != id }.mapNotNull { input.mesh(it.id) }
                .filter { mesh -> mesh.offsets?.axes.orEmpty().any { it.parameter in blinkAxes } }
                .map { it.copy(shapes = emptyList()) }.toList()
            fun lower(phase: RigIR, mesh: Mesh): PuppetModel {
                val model = PuppetIr.toPuppet(RigIR(input.canvas, parameters,
                    meshes = listOf(mesh.copy(id = nativeId)) + witnesses, deformers = phase.deformers))
                val lowered = BlendShapeLowering({ head, name -> if (head) turnName.replace("{meshName}", name) else shapeName },
                    checkpoint).lawful(model)
                require(lowered.parameters == model.parameters)
                return lowered.copy(drawables = listOf(lowered.drawables.first().copy(id = DrawableId(id))))
            }
            val prior = lower(before, source)
            val next = lower(after, desired)
            val aliases = linkedMapOf<DeformerId, DeformerId>()
            for (deformer in prior.deformers) {
                val actualId = owners.asSequence().filter { target in it.targets }
                    .map { ScaffoldBinding.deformerId(it, id, deformer.id.raw, shared = true) }.distinct()
                    .singleOrNull { it in actualDeformers }
                if (actualId != null) aliases[deformer.id] = DeformerId(actualId)
            }
            val priorBranch = generateSequence(prior.drawables.single().parentDeformerId) { parent ->
                prior.deformers.single { it.id == parent }.parent
            }.toList()
            require(priorBranch.all { it in aliases }) { "Cannot resolve preceding brow blink frame: ${live.id}" }
            require(priorBranch.firstOrNull()?.let(aliases::get)?.raw == live.parent)
            for (deformer in next.deformers) {
                aliases.putIfAbsent(deformer.id, DeformerId(ScaffoldBinding.deformerId(instance, id, deformer.id.raw, shared = true)))
            }
            val parts = prior.deformers.mapNotNull { deformer ->
                val actual = aliases[deformer.id]?.raw?.let(actualDeformers::get)?.part ?: return@mapNotNull null
                deformer.partId?.let { it to PartId(actual) }
            }.toMap()
            val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(live), deformers = ancestry,
                parts = parts.values.distinct().map { requireNotNull(input.part(it.raw)) }, textures = input.textures, authoring = input.authoring))
            val transfer = object : BindingMerge.Source {
                override fun provider(instance: RigModifierInstance) = scaffold
                override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to next
                override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
                override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
                override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) = false
                override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = aliases
                override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = parts
                override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
                override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                    requireNotNull(parts[PartId(canonical)]).raw
                override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                           checkpoint: () -> Unit): List<VertexSource> =
                    BodyVertexSources.match(to, original, target, checkpoint)
            }
            val local = instance.copy(targets = listOf(target), outputAliases = instance.outputAliases.filterKeys {
                !it.startsWith("parameter-folder:")
            })
            val merged = BindingMerge.apply(current, local, transfer, checkpoint)
            require(merged.deformers.map { it.id.raw }.containsAll(actualDeformers.keys))
            for (deformer in merged.deformers.map(PuppetIr::deformerToIr)) {
                val original = actualDeformers[deformer.id]
                if (original == null) require(added.putIfAbsent(deformer.id, deformer)?.let { it == deformer } != false)
                else if (original != deformer) {
                    val change = ModifierObjectUpdate(original, deformer)
                    require(updated.putIfAbsent(deformer.id, change)?.let { it == change } != false)
                }
            }
            val result = PuppetIr.meshToIr(merged.drawables.single())
            ModifierObjectUpdate(live, result).takeIf { live != result }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = meshes),
            deformers = ModifierObjectChanges(added = added.values.toList(), updated = updated.values.toList()))
    }

    companion object {
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.62726f772e626c696e6b.v5/blink"
    }
}
