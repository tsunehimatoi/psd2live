package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierFieldChange
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.PuppetModel

/** Lowers the local face transition after shared authored residual composition. */
object LegacyFaceChanges {
    fun lower(input: RigIR, before: PuppetModel, result: PuppetModel): ModifierModelChanges {
        val after = result.withParametersSyncedFromTree()
        require(before.parts == after.parts && before.rootChildren == after.rootChildren && before.rootPartId == after.rootPartId)
        require(before.glues == after.glues && before.deformPaths == after.deformPaths && before.vertexGroups == after.vertexGroups)
        val oldIds = before.parameters.mapTo(hashSetOf()) { it.id }
        require(after.parameters.filter { it.id in oldIds } == before.parameters) { "Face binding must preserve parameter order" }
        val metadata = PuppetIr.toIr(after.copy(parts = emptyList(), deformers = emptyList(), drawables = emptyList(),
            atlas = before.atlas.copy(tiles = emptyList(), pages = emptyList()), sources = emptyList()))
        val deformers = updates(input.deformers, before.deformers, after.deformers, { it.id }, PuppetIr::deformerToIr)
        val meshes = updates(input.meshes, before.drawables, after.drawables, { it.id }, PuppetIr::meshToIr)
        val oldRawIds = oldIds.mapTo(hashSetOf()) { it.raw }
        return ModifierModelChanges(
            parameters = ModifierObjectChanges(added = metadata.parameters.filter { it.id !in oldRawIds }),
            deformers = ModifierObjectChanges(updated = deformers),
            meshes = ModifierObjectChanges(updated = meshes),
            parameterLinks = field(input.parameterLinks, metadata.parameterLinks),
            parameterTree = field(input.parameterTree, metadata.parameterTree),
            renderRoot = field(input.renderRoot, metadata.renderRoot),
        )
    }

    private fun <E : Any, I : Any, K> updates(original: List<I>, before: List<E>, after: List<E>,
                                              id: (E) -> K, encode: (E) -> I): List<ModifierObjectUpdate<I>> {
        require(original.size == before.size && before.size == after.size)
        return before.indices.mapNotNull { index ->
            val old = before[index]
            val next = after[index]
            if (old === next) return@mapNotNull null
            require(id(old) == id(next)) { "Face binding must preserve object identities and order" }
            val expected = original[index]
            val replacement = encode(next)
            if (expected == replacement) null else ModifierObjectUpdate(expected, replacement)
        }
    }

    private fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
}
