package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Replay the local shell and chain branches in one binding pass, in the persisted target order. */
internal object MouthOutlineHairOrder {
    fun apply(input: RigIR, outline: ModifierModelChanges, instance: RigModifierInstance,
              context: ModifierAlgorithmContext, checkpoint: () -> Unit): ModifierModelChanges {
        val live = PuppetIr.toPuppet(input)
        val liveMeshes = live.drawables.associateBy { it.id }
        val liveNodes = live.deformers.associateBy { it.id }
        val providers = context.upstream.distinctBy { it.instanceId }.map {
            RigModifierInstance(requireNotNull(it.instanceId), it.type, it.version, it.targets,
                config = it.config, outputAliases = it.outputAliases, plugin = it.plugin)
        }
        val scaffold = providers.single { it.type == "rig.scaffold" }
        val hairProvider = providers.single { it.type == "hair/chains" }
        val hair = Json.decodeFromJsonElement<Map<String, MouthOutlineHair>>(context.values.getValue("hair"))
        val carrierByTarget = hair.mapValues { (id, observation) ->
            val parent = liveMeshes.getValue(DrawableId(id)).parentDeformerId
            val expected = DeformerId(ScaffoldBinding.deformerId(hairProvider, id, observation.chain, shared = true))
            require(parent == expected)
            liveNodes.getValue(expected) as Deformer.Warp
        }
        val chainAliases = hair.entries.flatMap { (id, observation) ->
            val alias = carrierByTarget.getValue(id).id
            listOf(observation.before, observation.after).map { DeformerId(it.motion().warpId) to alias }
        }.toMap()

        fun phase(key: String, resource: String, topology: String, before: Boolean): PuppetModel {
            val captured = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue(resource).read()))
            val meshes = captured.drawables.associateBy { it.id }
            val nodes = captured.deformers.associateBy { it.id }
            val head = context.values.getValue("head").jsonObject.getValue(key).jsonObject
            val turn = DepthTurnInputs.decode(ModifierAlgorithmContext(head.getValue("turn").jsonObject))
            val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(context.values.getValue(topology).jsonPrimitive.content), checkpoint)
            val projectedMeshes = hashMapOf<DrawableId, Drawable>()
            val projectedWarps = hashMapOf<DeformerId, Deformer.Warp>()
            val chainParameters = hashSetOf<ParameterId>()
            for ((id, observation) in hair) {
                checkpoint()
                val item = if (before) observation.before else observation.after
                val nativeMesh = meshes.getValue(DrawableId(id))
                val nativeWarp = nodes.getValue(DeformerId(item.motion().warpId))
                val actual = liveMeshes.getValue(nativeMesh.id)
                val (mesh, warp) = observation.project(live, actual, carrierByTarget.getValue(id),
                    raw.getValue(observation.canonical), item, turn, hairProvider, checkpoint)
                projectedMeshes[mesh.id] = mesh.copy(parentDeformerId = nativeMesh.parentDeformerId)
                projectedWarps[nativeWarp.id] = warp.copy(id = nativeWarp.id, parent = nativeWarp.parent, partId = nativeWarp.partId)
                chainParameters += item.motion().aliases(hairProvider).values.map(::ParameterId)
            }
            return captured.copy(
                drawables = captured.drawables.map { projectedMeshes[it.id] ?: it },
                deformers = captured.deformers.map { projectedWarps[it.id] ?: it },
                parameters = captured.parameters + live.parameters.filter { it.id in chainParameters },
            )
        }
        val previous = phase("before", "outlineHairBefore", "source_meshes", before = true)
        val desired = phase("after", "outlineHairAfter", "component_meshes", before = false)
        val oldNodes = previous.deformers.associateBy { it.id }
        val newNodes = desired.deformers.associateBy { it.id }
        val oldMeshes = previous.drawables.associateBy { it.id }
        val newMeshes = desired.drawables.associateBy { it.id }
        fun alias(target: ModifierTarget, id: DeformerId): DeformerId? = chainAliases[id] ?: providers.firstNotNullOfOrNull {
            val mapped = DeformerId(ScaffoldBinding.deformerId(it, target.id, id.raw, shared = true))
            mapped.takeIf(liveNodes::containsKey)
        }
        fun branch(mesh: Drawable, nodes: Map<DeformerId, Deformer>) =
            generateSequence(mesh.parentDeformerId) { nodes.getValue(it).parent }.toList()
        val lastHair = instance.targets.indexOfLast { it.id in hair }
        val targets = instance.targets.take(lastHair + 1).filter { target ->
            val old = oldMeshes.getValue(DrawableId(target.id))
            val next = newMeshes.getValue(old.id)
            val parents = branch(old, oldNodes)
            val known = parents == branch(next, newNodes) && parents.all { alias(target, it) != null }
            require(known || target.id !in hair) { "Fitted outline hair branch changed: ${target.id}" }
            known
        }
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = scaffold
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = previous to desired
            override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                          declared: io.github.psd2live.format.model.Parameter) = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                oldNodes.keys.mapNotNull { id -> alias(target, id)?.let { id to it } }.toMap()
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                error("Fitted outline order projection cannot replace a carrier branch")
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                error("Fitted outline order projection cannot replace an organizational part")
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val merged = BindingMerge.apply(live, instance.copy(targets = targets), source, checkpoint)
        val existingMeshes = outline.meshes.updated.filterNot { it.before.id in hair }
        val hairMeshes = merged.drawables.mapNotNull { mesh ->
            val next = PuppetIr.meshToIr(mesh)
            if (next.id !in hair) return@mapNotNull null
            val before = input.meshes.single { it.id == next.id }
            val after = before.copy(geometry = next.geometry, offsets = next.offsets, shapes = next.shapes)
            after.takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }
        val carriers = carrierByTarget.values.mapTo(hashSetOf()) { it.id.raw }
        val existingWarps = outline.deformers.updated.filterNot { it.before.id in carriers }
        val hairWarps = merged.deformers.mapNotNull { warp ->
            val id = warp.id.raw
            if (id !in carriers) return@mapNotNull null
            val before = input.deformers.single { it.id == id }
            val next = PuppetIr.deformerToIr(warp)
            next.takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }
        return outline.copy(
            meshes = ModifierObjectChanges(updated = existingMeshes + hairMeshes),
            deformers = ModifierObjectChanges(updated = existingWarps + hairWarps),
        )
    }
}
