package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/** Future-only transition: reproduce the entire prior branch before touching its exact owners. */
object BrowSourceTransitionV1 {
    fun merge(input: ModifierEvaluationInput, instance: RigModifierInstance, owners: List<RigModifierInstance>,
              live: Mesh, prior: PuppetModel, next: PuppetModel, controls: List<Parameter>,
              checkpoint: () -> Unit): ModifierModelChanges {
        val scaffold = owners.single { it.type == "rig.scaffold" }
        val added = mutableListOf<Deformer>()
        val updated = mutableListOf<ModifierObjectUpdate<Deformer>>()
        val meshTarget = ModifierTarget("mesh", live.id)
        val ancestry = input.parentsOfMesh(live.id)
        val existing = ancestry.associateBy { it.id }
        val aliases = linkedMapOf<DeformerId, DeformerId>()
        for (carrier in prior.deformers) {
            val id = owners.asSequence().filter { meshTarget in it.targets }
                .map { ScaffoldBinding.deformerId(it, live.id, carrier.id.raw, shared = true) }.distinct()
                .singleOrNull { it in existing }
            aliases[carrier.id] = DeformerId(requireNotNull(id) { "Cannot resolve preceding brow frame: ${carrier.id.raw}" })
        }
        require(aliases.values.map { it.raw }.toSet() == existing.keys && aliases.values.distinct().size == aliases.size) { "Source brow transition requires the complete preceding brow branch" }
        require(next.deformers.map { it.id }.containsAll(prior.deformers.map { it.id })) { "Cannot retire a preceding brow carrier" }
        for (carrier in next.deformers) {
            aliases.putIfAbsent(carrier.id, DeformerId(ScaffoldBinding.deformerId(instance, live.id, carrier.id.raw, shared = true)))
        }
        require(prior.drawables.single().parentDeformerId?.let(aliases::get)?.raw == live.parent)
        val parts = prior.deformers.mapNotNull { carrier ->
            val livePart = existing[aliases.getValue(carrier.id).raw]?.part ?: return@mapNotNull null
            carrier.partId?.let { it to PartId(livePart) }
        }.toMap()
        val remap = ModelIdRemap(deformers = aliases, parts = parts)
        val expectedMesh = PuppetIr.meshToIr(remap.drawable(prior.drawables.single()))
        require(live.parent == expectedMesh.parent && live.geometry == expectedMesh.geometry &&
            live.offsets == expectedMesh.offsets && live.shapes == expectedMesh.shapes && live.channels.isEmpty()) {
            "Source brow transition requires the exact preceding brow topology and height grid: ${live.id}"
        }
        for (carrier in prior.deformers) {
            val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
            require(existing[expected.id] == expected) {
                "Source brow transition requires the exact preceding brow frame: ${expected.id}"
            }
        }
        val liveParts = parts.values.distinct().map { requireNotNull(input.part(it.raw)) }
        val current = PuppetIr.toPuppet(RigIR(input.canvas, controls, parts = liveParts, deformers = ancestry, meshes = listOf(live),
            textures = input.textures, authoring = input.authoring))
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
        val geometryOnly = instance.copy(targets = listOf(meshTarget), outputAliases = instance.outputAliases.filterKeys {
            !it.startsWith("parameter-folder:")
        })
        val merged = BindingMerge.apply(current, geometryOnly, transfer, checkpoint)
        require(merged.deformers.map { it.id.raw }.containsAll(existing.keys))
        for (deformer in merged.deformers.map(PuppetIr::deformerToIr)) {
            val original = existing[deformer.id]
            if (original == null) added += deformer
            else if (original != deformer) {
                val update = ModifierObjectUpdate(original, deformer)
                BrowShapeCarrierScope.check(input, listOf(update), live.id, existing.keys, checkpoint)
                updated += update
            }
        }
        val result = PuppetIr.meshToIr(merged.drawables.single())
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = listOfNotNull(ModifierObjectUpdate(live, result).takeIf { live != result })),
            deformers = ModifierObjectChanges(added = added, updated = updated))
    }
}
