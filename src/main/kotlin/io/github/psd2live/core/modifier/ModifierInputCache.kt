package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR

/** A conservative component dependency key. Writes are reads too, so replay cannot erase another writer. */
internal object ModifierInputCache {
    fun input(rig: RigIR, kinds: Set<String>): Any {
        if ("*" in kinds || kinds.any { it !in known }) return rig
        return kinds.sorted().map { kind -> when (kind) {
            "mesh" -> rig.meshes
            "deformer" -> rig.deformers
            "part" -> rig.parts
            "parameter" -> rig.parameters
            "physics" -> rig.physics
            "motion" -> rig.clips
            "glue" -> rig.glues
            "textures" -> rig.textures
            "hierarchy" -> listOf(rig.rootPart, rig.rootChildren, rig.parameterTree, rig.renderRoot)
            "relations" -> listOf(rig.parameterLinks, rig.parameterRoles, rig.meshes)
            else -> rig
        } } + listOf(rig.canvas, rig.restPose, rig.authoring, rig.advanced, rig.hitAreas)
    }
    private val known = setOf("mesh", "deformer", "part", "parameter", "physics", "motion", "glue", "textures", "hierarchy", "relations")

    /** Replace only changed components; untouched components always come from the actual upstream input. */
    class Patch(private val before: RigIR, private val after: RigIR) {
        fun apply(current: RigIR): RigIR {
            fun <T> field(old: T, new: T, current: T): T = if (old == new) current else new
            return current.copy(
                canvas = field(before.canvas, after.canvas, current.canvas),
                parameters = field(before.parameters, after.parameters, current.parameters),
                parameterLinks = field(before.parameterLinks, after.parameterLinks, current.parameterLinks),
                parameterTree = field(before.parameterTree, after.parameterTree, current.parameterTree),
                parameterRoles = field(before.parameterRoles, after.parameterRoles, current.parameterRoles),
                parts = field(before.parts, after.parts, current.parts),
                rootChildren = field(before.rootChildren, after.rootChildren, current.rootChildren),
                rootPart = field(before.rootPart, after.rootPart, current.rootPart),
                deformers = field(before.deformers, after.deformers, current.deformers),
                meshes = field(before.meshes, after.meshes, current.meshes),
                glues = field(before.glues, after.glues, current.glues),
                renderRoot = field(before.renderRoot, after.renderRoot, current.renderRoot),
                textures = field(before.textures, after.textures, current.textures),
                physics = field(before.physics, after.physics, current.physics),
                clips = field(before.clips, after.clips, current.clips),
                restPose = field(before.restPose, after.restPose, current.restPose),
                authoring = field(before.authoring, after.authoring, current.authoring),
                advanced = field(before.advanced, after.advanced, current.advanced),
                hitAreas = field(before.hitAreas, after.hitAreas, current.hitAreas),
            )
        }
    }
    data class Entry(val instance: RigModifierInstance, val input: Any, val patch: Patch)
}
