package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Plans replacement of untouched source geometry before fitted eye motion is applied. */
object EyeScaffoldTransition {
    enum class Reason { SOURCE_HIERARCHY, SHARED_FRAME, AUTHORED_FRAME, AUTHORED_MESH, INDEXED_MESH }

    sealed interface Result {
        data class Ready(val changes: ModifierModelChanges) : Result
        data class Blocked(val reason: Reason, val ids: Set<String>) : Result
    }

    /**
     * Both source contexts come from the same scaffold provider, traced at its ordinary and fitted densities.
     * A changed ancestor belongs to this transition only when every live descendant is within the selected branch.
     */
    fun plan(
        input: RigIR, provider: RigModifierInstance, targets: Set<String>,
        ordinary: ModifierAlgorithmContext, fitted: ModifierAlgorithmContext, checkpoint: () -> Unit = {},
        preserveMeshes: Set<String> = emptySet(),
    ): Result {
        require(provider.type == "rig.scaffold" && provider.version == 2)
        val provided = provider.targets.filter { it.kind == "mesh" }.mapTo(hashSetOf()) { it.id }
        require(targets.all { it in provided })
        if (targets.isEmpty()) return Result.Ready(ModifierModelChanges())
        val canonicalTargets = ordinary.values.getValue("canonical_targets").jsonObject
        require(ordinary.resources.getValue("materials") == fitted.resources.getValue("materials") &&
            canonicalTargets == fitted.values.getValue("canonical_targets")) {
            "Fitted and ordinary scaffold captures must share source material identities"
        }
        val algorithm = ScaffoldAlgorithm()
        val old = PuppetIr.toIr(algorithm.buildSource(ordinary, checkpoint))
        val new = PuppetIr.toIr(algorithm.buildSource(fitted, checkpoint))
        val oldMeshes = old.meshes.associateBy { it.id }
        val newMeshes = new.meshes.associateBy { it.id }
        val oldDeformers = old.deformers.associateBy { it.id }
        val newDeformers = new.deformers.associateBy { it.id }
        val liveMeshes = input.meshes.associateBy { it.id }
        val liveDeformers = input.deformers.associateBy { it.id }
        fun ancestry(start: String?, deformers: Map<String, Deformer>): List<String> {
            val chain = linkedSetOf<String>()
            var id = start
            while (id != null) {
                checkpoint()
                require(chain.add(id)) { "Cyclic scaffold at $id" }
                id = requireNotNull(deformers[id]) { "Missing scaffold deformer: $id" }.parent
            }
            return chain.toList()
        }

        val branch = linkedSetOf<String>()
        val frameUpdates = linkedMapOf<String, ModifierObjectUpdate<Deformer>>()
        val meshUpdates = mutableListOf<ModifierObjectUpdate<Mesh>>()
        val indexedMeshes = buildSet {
            input.glues.forEach { add(it.meshA); add(it.meshB) }
            input.authoring.paths.forEach { add(it.mesh) }
            input.authoring.vertexGroups.forEach { add(it.mesh) }
            input.advanced.simulations.forEach { simulation -> simulation.targets.forEach { add(it.mesh) } }
            input.advanced.colliders.forEach { it.mesh?.let(::add) }
        }
        for (id in targets) {
            checkpoint()
            val canonical = canonicalTargets.getValue(id).jsonPrimitive.content
            val actual = requireNotNull(liveMeshes[id]) { "Missing fitted eye target: $id" }
            val source = oldMeshes[canonical] ?: return Result.Blocked(Reason.SOURCE_HIERARCHY, setOf(id))
            val desired = newMeshes[canonical] ?: return Result.Blocked(Reason.SOURCE_HIERARCHY, setOf(id))
            val chain = ancestry(source.parent, oldDeformers)
            if (chain != ancestry(desired.parent, newDeformers)) return Result.Blocked(Reason.SOURCE_HIERARCHY, setOf(id))
            fun alias(local: String) = ScaffoldBinding.deformerId(provider, id, local)
            val aliasedChain = chain.map(::alias)
            if (ancestry(actual.parent, liveDeformers) != aliasedChain) return Result.Blocked(Reason.AUTHORED_FRAME, setOf(id))
            branch += aliasedChain
            for (local in chain) {
                val liveId = alias(local)
                if (liveId in frameUpdates) continue
                val oldFrame = oldDeformers.getValue(local)
                val newFrame = newDeformers.getValue(local)
                if (oldFrame == newFrame) continue
                val current = liveDeformers.getValue(liveId)
                when (val update = frame(current, oldFrame, newFrame)) {
                    null -> return Result.Blocked(Reason.AUTHORED_FRAME, setOf(liveId))
                    current -> Unit
                    else -> frameUpdates[liveId] = ModifierObjectUpdate(current, update)
                }
            }
            val geometryChanged = source.geometry != desired.geometry || source.offsets != desired.offsets
            if (!geometryChanged && aliasedChain.none { it in frameUpdates }) continue
            if (id in preserveMeshes) continue
            if (actual.geometry != source.geometry || actual.offsets != source.offsets || actual.shapes.isNotEmpty())
                return Result.Blocked(Reason.AUTHORED_MESH, setOf(id))
            if (id in indexedMeshes) return Result.Blocked(Reason.INDEXED_MESH, setOf(id))
            if (geometryChanged) meshUpdates += ModifierObjectUpdate(actual, actual.copy(geometry = desired.geometry, offsets = desired.offsets))
        }

        if (frameUpdates.isNotEmpty()) {
            val changed = frameUpdates.keys
            val shared = buildSet {
                for (mesh in input.meshes) if (mesh.id !in targets)
                    addAll(ancestry(mesh.parent, liveDeformers).filter { it in changed })
                for (deformer in input.deformers) if (deformer.id !in branch)
                    addAll(ancestry(deformer.parent, liveDeformers).filter { it in changed })
                for (collider in input.advanced.colliders) collider.deformer?.let {
                    if (it in changed) add(it)
                    addAll(ancestry(liveDeformers[it]?.parent, liveDeformers).filter { parent -> parent in changed })
                }
            }
            if (shared.isNotEmpty()) return Result.Blocked(Reason.SHARED_FRAME, shared)
        }
        return Result.Ready(ModifierModelChanges(
            deformers = ModifierObjectChanges(updated = frameUpdates.values.toList()),
            meshes = ModifierObjectChanges(updated = meshUpdates),
        ))
    }

    private fun frame(current: Deformer, old: Deformer, new: Deformer): Deformer? = when {
        current is Deformer.Warp && old is Deformer.Warp && new is Deformer.Warp -> {
            val sourceLayout = current.rows == old.rows && current.columns == old.columns && current.bilinear == old.bilinear
            val sameLayout = old.rows == new.rows && old.columns == new.columns && old.bilinear == new.bilinear
            if (!sourceLayout || !sameLayout || current.lattice != old.lattice || current.shapes.isNotEmpty()) null
            else current.copy(lattice = new.lattice)
        }
        current is Deformer.Rotation && old is Deformer.Rotation && new is Deformer.Rotation -> {
            if (current.baseAngle != old.baseAngle || current.pivot != old.pivot || current.shapes.isNotEmpty() ||
                current.flipX != old.flipX || current.flipY != old.flipY) null
            else current.copy(baseAngle = new.baseAngle, pivot = new.pivot, flipX = new.flipX, flipY = new.flipY)
        }
        else -> null
    }
}
