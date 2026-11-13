package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierFieldChange
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.format.model.RigIR

/** Composes sequential in-memory RigIR steps into one structured [ModifierModelChanges] diff. */
internal object PartChangeComposer {
    fun <T> diffObjects(before: List<T>, after: List<T>, id: (T) -> String): ModifierObjectChanges<T> {
        val beforeById = before.associateBy(id)
        val afterById = after.associateBy(id)
        val added = after.filter { id(it) !in beforeById }
        val removed = before.filter { id(it) !in afterById }
        val updated = after.mapNotNull { next ->
            val prev = beforeById[id(next)] ?: return@mapNotNull null
            if (prev == next) null else ModifierObjectUpdate(prev, next)
        }
        return ModifierObjectChanges(added = added, updated = updated, removed = removed)
    }

    fun <T> diffField(before: T, after: T): ModifierFieldChange<T>? =
        if (before == after) null else ModifierFieldChange(before, after)

    fun between(before: RigIR, after: RigIR, checkpoint: () -> Unit = {}): ModifierModelChanges {
        checkpoint()
        return ModifierModelChanges(
            parameters = diffObjects(before.parameters, after.parameters) { it.id },
            parts = diffObjects(before.parts, after.parts) { it.id },
            deformers = diffObjects(before.deformers, after.deformers) { it.id },
            meshes = diffObjects(before.meshes, after.meshes) { it.id },
            physicsGroups = diffObjects(before.physics.groups, after.physics.groups) { it.id },
            clips = diffObjects(before.clips, after.clips) { it.id },
            canvas = diffField(before.canvas, after.canvas),
            parameterLinks = diffField(before.parameterLinks, after.parameterLinks),
            parameterTree = diffField(before.parameterTree, after.parameterTree),
            parameterRoles = diffField(before.parameterRoles, after.parameterRoles),
            rootChildren = diffField(before.rootChildren, after.rootChildren),
            rootPart = diffField(before.rootPart, after.rootPart),
            glues = diffField(before.glues, after.glues),
            renderRoot = diffField(before.renderRoot, after.renderRoot),
            textures = diffField(before.textures, after.textures),
            physicsFps = diffField(before.physics.fps, after.physics.fps),
            restPose = diffField(before.restPose, after.restPose),
            authoring = diffField(before.authoring, after.authoring),
            advanced = diffField(before.advanced, after.advanced),
            hitAreas = diffField(before.hitAreas, after.hitAreas),
        )
    }
}
